package com.tablegame.area;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tablegame.TableGame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import com.tablegame.core.GameDefinition;

    /**
     * 区域幽灵预览的渲染。
     *
     * <p>本类只吃两样东西：一份快照 + 一个落点。两个来源共用：编辑器摆放模式（{@link AreaPlaceClient}，
     * 准星选位置 + Shift+Enter 落地）与玩家侧幽灵（{@code ghost} / {@code ghost_off}，收 S2C 包后调 {@link #show}）。
     * ponytail: 静态单份状态（同一客户端同时只画一份幽灵）；真要两份就改成「按玩家 id 分账」的小 Map。
     *
     * <p>怎么画的：{@link #show} 时烘一次「每个调色板下标的模型部件」+ 可见格清单（同一份快照只烘一次）；
     * 每帧在 {@link SubmitCustomGeometryEvent} 里对每格调 {@code collector.submitBlockModel(...)}，与棋子 / 移动方块
     * 同一条提交路径，走实体方块层（不透明的真方块观感）。光照给全亮（幽灵约定）。
     * ponytail: 每帧仍逐格提交；真掉帧再上 {@code RenderType.draw(MeshData)} + {@code AfterTranslucentBlocks} 顶点缓冲缓存。
     */
@EventBusSubscriber(modid = TableGame.MODID, value = Dist.CLIENT)
public final class AreaGhostRenderer {
    private AreaGhostRenderer() {}

    /** 全亮（15/15）。 */
    private static final int FULL_BRIGHT = 15728880;

    /** 显示落点向目标追的平滑系数（每帧一次）；编辑器与玩家侧跟随共用同一支动画。 */
    private static final double GLIDE = 0.35;

    /** 调色板下标 → 模型部件（一次烘好；空气/非模型方块给空表）。 */
    private static List<List<BlockStateModelPart>> parts;
    /** 要画的格：{dx, dy, dz, 调色板下标}（已去掉包在实心里的格子）。 */
    private static List<int[]> cells;
    /** 烘的是哪份快照（同一份不重烘，引用比；客户端那份定义是缓存住的）。 */
    private static GameDefinition.AreaDef baked;

    // 落点：t* = 目标（区域最小角）· p* = 显示位置（动画追）
    private static double tx;
    private static double ty;
    private static double tz;
    private static double px;
    private static double py;
    private static double pz;
    /** 此刻在画吗（编辑器与玩家侧两个来源都要它，不再问编辑器会话状态）。 */
    private static boolean active;

    /**
     * 显示一份快照的幽灵，落点钉在 (x, y, z)（= 区域最小角那格）。
     * 第一次（换了一份快照）直接瞬移到位；同一份快照再调 = 换落点滑过去（玩家侧每换一格都调它一次，动画跟手）。
     */
    public static void show(GameDefinition.AreaDef area, int x, int y, int z) {
        if (area != baked) {
            bake(area);
            baked = area;
            snapTo(x, y, z);
        }
        active = parts != null && cells != null && !cells.isEmpty();
        moveTo(x, y, z);
    }

    /** 换个落点（滑过去）；没在显示 = 什么也不做。 */
    public static void moveTo(int x, int y, int z) {
        if (!active) return;
        tx = x;
        ty = y;
        tz = z;
    }

    /** 收回（还能再 {@link #show}）。 */
    public static void hide() {
        active = false;
        baked = null;
        parts = null;
        cells = null;
    }

    /** 此刻在画吗。 */
    public static boolean active() {
        return active;
    }

    /** 动画中的显示落点（编辑器的外框线框与幽灵共用它 —— 两者永远同步）。 */
    public static Vec3 pos() {
        return new Vec3(px, py, pz);
    }

    /** 落点瞬移（换快照那一刻：别让它从上一个落点滑过来）。 */
    private static void snapTo(int x, int y, int z) {
        tx = x;
        ty = y;
        tz = z;
        px = x;
        py = y;
        pz = z;
    }

    /** 把这份区域烘成可复用的部件表 + 可见格清单（坏档不喂给渲染器）。 */
    private static void bake(GameDefinition.AreaDef area) {
        parts = null;
        cells = null;
        if (area == null || area.palette() == null || area.palette().isEmpty()) return;
        int sx = area.sizeX();
        int sy = area.sizeY();
        int sz = area.sizeZ();
        if (area.blocks().size() != sx * sy * sz) return;                 // 坏档：不喂给渲染器

        var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        RandomSource random = RandomSource.create(42L);                   // 多变体方块固定种子（同棋子渲染口径）
        List<List<BlockStateModelPart>> bakedParts = new ArrayList<>(area.palette().size());
        for (BlockState st : area.palette()) {
            List<BlockStateModelPart> one = new ArrayList<>(4);
            if (st != null && st.getRenderShape() == RenderShape.MODEL) {
                BlockStateModel model = modelSet.get(st);
                if (model != null) {
                    model.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, st, random, one);
                }
            }
            bakedParts.add(one);
        }
        parts = bakedParts;

        List<Integer> vis = GameDefinition.AreaDef.visibleOnly(sx, sy, sz, area.blocks());
        List<int[]> out = new ArrayList<>();
        for (int dz = 0; dz < sz; dz++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dx = 0; dx < sx; dx++) {
                    int i = (dz * sy + dy) * sx + dx;
                    if (i >= vis.size()) continue;
                    int idx = vis.get(i);
                    if (idx < 0 || idx >= parts.size() || parts.get(idx).isEmpty()) continue;
                    out.add(new int[] {dx, dy, dz, idx});
                }
            }
        }
        cells = out;
    }

    // ===== 每帧提交 =====

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (!active) return;                                               // 没人要画：一帧的开销都不花
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            hide();                          // 退出世界 / 掉线：状态别留给下一个世界
            return;
        }
        if (cells == null || cells.isEmpty() || parts == null) return;

        // 就位 / 跟随动画：显示位置追目标位置（每帧推一次）
        px += (tx - px) * GLIDE;
        py += (ty - py) * GLIDE;
        pz += (tz - pz) * GLIDE;

        var cam = mc.gameRenderer.getMainCamera().position();              // 26.x：Camera#position()
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        PoseStack pose = new PoseStack();                                  // 相机空间（提交阶段坐标已减相机）
        for (int[] c : cells) {
            pose.pushPose();
            pose.translate((float) (px + c[0] - cam.x),
                    (float) (py + c[1] - cam.y),
                    (float) (pz + c[2] - cam.z));
            collector.submitBlockModel(pose, RenderTypes.solidMovingBlock(), parts.get(c[3]),
                    BlockModelRenderState.EMPTY_TINTS, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }
}
