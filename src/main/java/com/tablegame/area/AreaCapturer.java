package com.tablegame.area;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import com.tablegame.core.GameDefinition;

/**
 * 区域捕获的执行侧：按 chunk 列分相、每 tick 一个相，把一个大盒「慢慢收」。
 * 必须分相：每边 ≤32 放开后，一个 500³ 盒就是 1.25 亿格 —— 一把棱先 OOM、再按死主线程。
 * 分相规划在 {@link AreaPlanner#capturePhases}（纯逻辑，那边能自检）；小块（一个 chunk 列装得下）仍一相跑完。
 * 大区域的表现是「慢慢收 + 状态条亮着」而非客户端假死：分相期间会话不结束，收完才写档 / 回执 / 收工具。
 */
public final class AreaCapturer {
    private AreaCapturer() { }

    /** 一份进行中的捕获任务。 */
    private static final class Job {
        final ServerPlayer player;
        final ServerLevel level;
        final String id;
        final int minX, minY, minZ, sx, sy, sz;
        final List<List<int[]>> phases;
        /** 按快照行序收（相里那格写 {@code cells[idx]}）；收满 = 每一格恰好写过一次。 */
        final BlockState[] cells;
        /** 收完交回业务侧（写档 / 回执 / 清会话 / 收回工具）——那是 GameManager 的活，这里只管「收」。 */
        final Consumer<GameDefinition.AreaDef> done;
        int at;

        Job(ServerPlayer player, ServerLevel level, String id, BoundingBox box,
                List<List<int[]>> phases, BlockState[] cells, Consumer<GameDefinition.AreaDef> done) {
            this.player = player;
            this.level = level;
            this.id = id;
            this.minX = box.minX();
            this.minY = box.minY();
            this.minZ = box.minZ();
            this.sx = box.getXSpan();
            this.sy = box.getYSpan();
            this.sz = box.getZSpan();
            this.phases = phases;
            this.cells = cells;
            this.done = done;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    /**
     * 排一份捕获任务（两条捕获路共用：框选确认 / 按声明盒）。
     *
     * @param done 收完后回调（在主线程、服务端 tick 里跑）
     * @return false = 排不上（盒空 / 尺寸非法）——调用侧提示一句
     */
    public static boolean begin(ServerPlayer sp, ServerLevel level, String id, BoundingBox box,
            Consumer<GameDefinition.AreaDef> done) {
        int sx = box.getXSpan(), sy = box.getYSpan(), sz = box.getZSpan();
        List<List<int[]>> phases = AreaPlanner.capturePhases(sx, sy, sz, box.minX(), box.minZ());
        if (phases == null || phases.isEmpty()) return false;
        JOBS.add(new Job(sp, level, id, box, phases, new BlockState[sx * sy * sz], done));
        if (phases.size() > 1) {
            // 多相 = 跨好几 tick，先告诉他一声，免得以为没反应去退世界。
            sp.sendSystemMessage(Component.literal("[收] " + sx + "×" + sy + "×" + sz + " = "
                    + ((long) sx * sy * sz) + " 格，分 " + phases.size() + " 相收（慢慢来，别退世界）"));
        }
        return true;
    }

    /** 每服务端 tick 推进一个相（照 AreaPlacer：相之间回主线程）。 */
    public static void tick() {
        for (Job job : new ArrayList<>(JOBS)) {
            if (job.player != null && !job.player.isAlive()) {
                JOBS.remove(job);                // 人没了：不收了、也不写档（半份快照比没有更坏）
                continue;
            }
            if (job.at >= job.phases.size()) {
                JOBS.remove(job);
                job.done.accept(GameDefinition.AreaDef.of(job.id, job.sx, job.sy, job.sz,
                        java.util.Arrays.asList(job.cells)));
                continue;
            }
            runPhase(job, job.phases.get(job.at++));
            if (job.player != null && job.phases.size() > 1) {
                job.player.sendOverlayMessage(Component.literal("[收] " + job.at + "/" + job.phases.size() + " 相"));
            }
        }
    }

    /** 一个相：把这一列里的格读进快照（**只读**，不改世界 ⇒ 不必像盖章那样补邻居更新）。 */
    private static void runPhase(Job job, List<int[]> phase) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] c : phase) {
            pos.set(job.minX + c[0], job.minY + c[1], job.minZ + c[2]);
            job.cells[c[3]] = job.level.getBlockState(pos);
        }
    }
}
