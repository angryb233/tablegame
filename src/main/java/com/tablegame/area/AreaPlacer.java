package com.tablegame.area;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.tablegame.core.GameDefinition;

    /**
     * 区域落地（盖章）的执行侧：把一份区域快照写回世界。
     *
     * <p>三点照投影（Litematica）的现成先例：① 按 chunk 分相、分 tick 推进（一个相只处理一个
     * chunk 列，相之间回主线程）；② 写方块不带邻居更新（{@code setBlock(pos, state, 18)}），
     * 相末对这批格统一 {@code updateNeighborsAt}；③ 覆盖策略三值 {@link AreaPlanner.Replace}。
     * 只跑在服务端主线程；分相 / 覆盖判定在 {@link AreaPlanner}（纯逻辑，可自检）。
     */
public final class AreaPlacer {
    private AreaPlacer() { }

    /** {@code Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE} = 18（照投影 {@code 0x12}）。 */
    private static final int FLAGS = 2 | 16;

    /** 一个进行中的盖章任务。 */
    private static final class Job {
        final ServerPlayer player;
        final ServerLevel level;
        final String game;
        final String areaId;
        final BlockPos origin;
        final AreaPlanner.Replace mode;
        final List<BlockState> palette;
        final List<List<int[]>> phases;
        /** 要**先跑完**的那份任务（移动 = 先落地、后清源）—— null = 没有前置。 */
        final Job after;
        /** 清源时要**绕开**的盒（{@code minX,minY,minZ,maxX,maxY,maxZ}）—— 移动的目标位置；
         *  重叠处不清（那正是刚落下的新方块）。null = 没有禁区。 */
        final int[] guard;
        int at;
        int placed;
        int skipped;
        int missing;

        Job(ServerPlayer player, ServerLevel level, String game, String areaId, BlockPos origin,
                AreaPlanner.Replace mode, List<BlockState> palette, List<List<int[]>> phases,
                Job after, int[] guard) {
            this.player = player;
            this.level = level;
            this.game = game;
            this.areaId = areaId;
            this.origin = origin;
            this.mode = mode;
            this.palette = palette;
            this.phases = phases;
            this.after = after;
            this.guard = guard;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    /**
     * 开始盖章（服务端调）。返回 false = 没接（区域没快照 / 形状对不上）。
     * 落点在区域原点那个角上（与捕获时选的角对齐，不旋转）。
     */
    public static boolean begin(ServerPlayer sp, ServerLevel level, String game, GameDefinition.AreaDef area,
            BlockPos origin, AreaPlanner.Replace mode) {
        return enqueue(sp, level, game, area, origin, mode, null, null) != null;
    }

    /**
     * 移动一个区域：先落到新位置，落完再清空原处 —— 两份任务串行（{@link Job#after}），
     * 重叠处靠 {@link Job#guard} 绕开（清掉就白搬了）。
     *
     * <p>清源走同一条落地路径：造一份「全空气的快照」（每格 {@code -1}）配合
     * {@link AreaPlanner.Replace#ALL} 判成写空气，不必另引一套写方块机制。
     *
     * @param srcOrigin 源盒的最小角（= 脚本里那条 {@code area} 声明的盒）
     * @return false = 一件都没做（没快照 / 形状对不上 / 清源排不上 —— 宁可不动，也不搬一半）
     */
    public static boolean move(ServerPlayer sp, ServerLevel level, String game, GameDefinition.AreaDef area,
            BlockPos origin, BlockPos srcOrigin, int sx, int sy, int sz) {
        Job land = enqueue(sp, level, game, area, origin, AreaPlanner.Replace.ALL, null, null);
        if (land == null) return false;

        List<Integer> blank = new ArrayList<>(Math.max(0, sx * sy * sz));
        for (int i = 0; i < sx * sy * sz; i++) blank.add(-1);            // -1 = 空气（快照口径）
        List<List<int[]>> phases = AreaPlanner.phases(sx, sy, sz, srcOrigin.getX(), srcOrigin.getZ(), blank);
        if (phases == null || phases.isEmpty()) {
            JOBS.remove(land);                                           // 清源排不上 ⇒ 整件不做（别搬一半）
            return false;
        }
        int[] guard = { origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + area.sizeX() - 1, origin.getY() + area.sizeY() - 1,
                origin.getZ() + area.sizeZ() - 1 };
        JOBS.add(new Job(sp, level, game, area.id(), srcOrigin, AreaPlanner.Replace.ALL, List.of(), phases,
                land, guard));
        sp.sendSystemMessage(Component.literal("[章] 移动 " + area.id() + "：先落地，落完清原处（重叠处不动）"
                + " · 声明里那条盒没跟着搬 —— 要它指向新位置，去区域属性页改盒或在脚本里改 box"));
        return true;
    }

    /** 建一份任务入队（{@code begin} / {@code move} 共用）。返回 null = 排不上。 */
    private static Job enqueue(ServerPlayer sp, ServerLevel level, String game, GameDefinition.AreaDef area,
            BlockPos origin, AreaPlanner.Replace mode, Job after, int[] guard) {
        if (area == null || !area.captured()) return null;
        List<List<int[]>> phases = AreaPlanner.phases(area.sizeX(), area.sizeY(), area.sizeZ(),
                origin.getX(), origin.getZ(), area.blocks());
        if (phases == null || phases.isEmpty()) return null;

        Job job = new Job(sp, level, game, area.id(), origin, mode,
                area.palette() == null ? List.of() : area.palette(), phases, after, guard);
        JOBS.add(job);
        sp.sendSystemMessage(Component.literal("[章] 开始落地《" + game + "》的区域 " + area.id()
                + "（" + area.sizeX() + "×" + area.sizeY() + "×" + area.sizeZ()
                + " · " + phases.size() + " 相）"));
        return job;
    }

    /**
     * 每服务端 tick 推进一个相（照投影的分相法）。相内：写方块（不带邻居更新）→ 相末统一补邻居。
     *
     * <p>目标格所在 chunk 没加载 → 跳过并计数（不拓局、不抛）：远处棋盘先不问。
     */
    public static void tick() {
        for (Job job : new ArrayList<>(JOBS)) {
            if (job.player != null && !job.player.isAlive()) {
                // 等它的那份**一起撤**：没搬成之前绝不清源（否则建筑两面都没了）
                JOBS.removeIf(j -> j == job || j.after == job);
                continue;
            }
            if (job.after != null && JOBS.contains(job.after)) {
                continue;                                   // 前置（落地）还没跑完：先等，别同时动手
            }
            if (job.at >= job.phases.size()) {
                finish(job);
                continue;
            }
            runPhase(job, job.phases.get(job.at++));
        }
    }

    private static void runPhase(Job job, List<int[]> phase) {
        List<BlockPos> touched = new ArrayList<>();
        for (int[] c : phase) {
            int idx = c[3];
            if (idx >= job.palette.size()) {   // 认不出的方块（跨版本老档）：当挖空跳过
                job.skipped++;
                continue;
            }
            BlockPos pos = job.origin.offset(c[0], c[1], c[2]);
            if (AreaPlanner.inside(job.guard, pos.getX(), pos.getY(), pos.getZ())) {
                continue;                                   // 落在**新位置**那一片里：那是刚落下的方块，不能清
            }
            if (!job.level.isLoaded(pos)) {
                job.missing++;
                continue;
            }
            int want = AreaPlanner.decide(idx, job.level.getBlockState(pos).isAir(), job.mode);
            if (want == AreaPlanner.SKIP) {
                job.skipped++;
                continue;
            }
            BlockState st = want == AreaPlanner.AIR
                    ? Blocks.AIR.defaultBlockState() : job.palette.get(want);
            if (job.level.setBlock(pos, st, FLAGS)) {   // 18 = 推客户端 + 已知形状（不触发邻居更新）
                touched.add(pos.immutable());
                job.placed++;
            } else {
                job.skipped++;
            }
        }
        // 相末统一补邻居（照投影 SchematicPlacingUtils:399）：红石/沙子/光影这类靠这一遍落到正确状态
        for (BlockPos p : touched) {
            job.level.updateNeighborsAt(p, job.level.getBlockState(p).getBlock(), null);
        }
        if (job.player != null && job.phases.size() > 1) {
            job.player.sendOverlayMessage(Component.literal("[章] " + job.at + "/" + job.phases.size() + " 相"));
        }
    }

    private static void finish(Job job) {
        JOBS.remove(job);
        String tail = job.skipped > 0 ? " · 跳过 " + job.skipped : "";
        String miss = job.missing > 0 ? " · 未加载 " + job.missing : "";
        if (job.player != null) {
            job.player.sendSystemMessage(Component.literal("[章] " + job.game + " / " + job.areaId
                    + " 落地完成：写 " + job.placed + " 格" + tail + miss));
        }
    }
}
