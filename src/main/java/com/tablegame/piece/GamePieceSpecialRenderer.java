package com.tablegame.piece;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
// 种子函数住在体素几何工具箱（AreaDef，与 rayVoxel/entryFace 同处）。
import com.tablegame.core.GameDefinition;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

    /**
     * 棋子的 3D 物品渲染器：物品栏 / 手持 / 掉落物里直接渲染缩小后的体素模型，不显示占位图标。
     *
     * <p>管线（26.x 提交阶段）：走 SpecialModelRenderer.submit(PoseStack, SubmitNodeCollector, ...)，
     * 与盾牌 / 床 / 潜影盒物品形态同一条路径，GUI 与世界共用；逐体素 {@code submitBlockModel(...)}，
     * 每块先 PoseStack 缩放到自己在棋子里的位置。
     *
     * <p>等比缩小语义：体素固定缩到 1/16 基准，规格档位 1/2、1/4、1/8（存分母 2/4/8）表示在基准上放大，
     * 每块边长 = 规格/16 格（1/2→2px，1/4→4px，1/8→8px）。
     *
     * <p>数据从物品的 {@link PieceData} 组件提取，渲染纯读无状态；模型从 ModelManager 查（固定种子随机源防闪烁）。
     */
public class GamePieceSpecialRenderer implements SpecialModelRenderer<PieceData> {

    /**
     * 模型变体选择的共享随机源（种子固定 → 不闪烁）。
     * 每格画之前必须 {@code setSeed(体素坐标算出的种子)}：共享源不重播就会每帧抽到别的变体（石头闪烁）。
     */
    private static final RandomSource RANDOM = RandomSource.create(42L);

    @Override
    public PieceData extractArgument(ItemStack stack) {
        PieceData data = PieceData.get(stack);
        return data.isLocked() ? data : null; // 未锁定（不应存在）→ 不画体素
    }

    @Override
    public void submit(PieceData data, PoseStack poseStack, SubmitNodeCollector collector,
            int light, int overlay, boolean useFoil, int partialTick) {
        if (data == null || !data.isLocked()) {
            return;
        }
        PieceData.VoxelData vox = data.voxels();
        BlockState[] states = vox.expand();

        // 运行时方块模型集（ModelManager 缓存）
        var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();

        // 物品/掉落物渲染：按棋子最大边长缩到「一格」并对齐 0..1（原版方块模型约定：模型占 0..1，
        // 居中交给 display 变换末尾的 translate(-0.5)）。⚠ 世界里按规格显示那套不受影响（GamePieceEntityRenderer 自己 scale/16）。
        float block = 1.0F / Math.max(Math.max(vox.sizeX(), vox.sizeY()), vox.sizeZ());

        poseStack.pushPose();

        // 复用的部件列表（submitBlockModel 只在提交期读它，无跨帧持有）
        List<BlockStateModelPart> parts = new ArrayList<>(16);

        int idx = 0;
        for (int z = 0; z < vox.sizeZ(); z++) {
            for (int y = 0; y < vox.sizeY(); y++) {
                for (int x = 0; x < vox.sizeX(); x++, idx++) {
                    BlockState st = states[idx];
                    if (st.isAir() || st.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(st);
                    if (model == null) {
                        continue;
                    }
                    parts.clear();
                    // 多变体方块按位置种子挑变体：种子必须逐格重播，否则共享 RandomSource 每帧往前跑 ⇒ 同一格每帧换样子。
                    RANDOM.setSeed(GameDefinition.AreaDef.voxelSeed(x, y, z));
                    model.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, st, RANDOM, parts);
                    if (parts.isEmpty()) {
                        continue;
                    }

                    poseStack.pushPose();
                    // 体素在棋子内的位置：数据行序 x→y→z（y=0 底部），与渲染坐标一致
                    poseStack.translate(x * block, y * block, z * block);
                    poseStack.scale(block, block, block);
                    collector.submitBlockModel(poseStack, RenderTypes.solidMovingBlock(),
                            parts, net.minecraft.client.renderer.block.BlockModelRenderState.EMPTY_TINTS,
                            light, overlay, 0);
                    poseStack.popPose();
                }
            }
        }
        poseStack.popPose();
    }

    @Override
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> out) {
        // 展示框（item frame 等）的包围盒提示：保守 1×1×1 半径 0.5
        out.accept(new org.joml.Vector3f(0.5F, 0.5F, 0.5F));
    }

    // ==================== Unbaked（注册入口） ====================

    /** 无参数 Unbaked：JSON 里 "model": {"type": "tablegame:game_piece"} 一个词即可实例化。 */
    public record Unbaked() implements SpecialModelRenderer.Unbaked<PieceData> {
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public SpecialModelRenderer<PieceData> bake(SpecialModelRenderer.BakingContext ctx) {
            return new GamePieceSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<PieceData>> type() {
            return CODEC;
        }
    }
}
