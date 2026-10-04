package com.tablegame.piece;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
// 种子函数住在 GameDefinition.AreaDef（复用现成的几何工具箱）。
import com.tablegame.core.GameDefinition;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 棋子实体渲染器：把隐形承载体在世界里画成体素棋子。
 * 管线复用 {@link GamePieceSpecialRenderer} 的逐体素提交（modelSet.get → collectParts → submitBlockModel；
 * 随机源固定 + 逐格重播种子防多变体闪烁），区别只在入口：实体走 EntityRenderer.submit
 * （26.x 实体渲染也是提交阶段，方法名 submit，不是 1.21 的 render）。
 * 26.x 提交阶段拿不到实体实例（EntityRenderState 是纯快照）：{@link #extractRenderState} 先把 ItemStack 快照进
 * {@link PieceRenderState}，submit 再从快照读。
 * 缩放语义：每块边长（格）= vox.scale()/16.0F，体素 y=0 是底部，水平按 sx/sz 居中；实体原点 = 实体 pos（脚底）。
 */
public class GamePieceEntityRenderer extends EntityRenderer<GamePieceEntity, GamePieceEntityRenderer.PieceRenderState> {

    /** 模型变体选择的共享随机源（种子固定 → 不闪烁，同 GamePieceSpecialRenderer）。 */
    private static final RandomSource RANDOM = RandomSource.create(42L);

    // ⚠ 每格必须独立列表：26.x 延迟提交，收集阶段结束后才绘制；共享列表逐格 clear 会让所有体素
    // 读到「最后一格」的部件（朝向某向时全变成同一种方块）。每格新建小列表，部件只是引用，拷贝可忽略。

    public GamePieceEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /** 缩放语义（勿改）：世界内每体素边长 = scale/16 格（蓝图÷16×规格）；物品/掉落物不 ÷16（原生 1px/体素）。 */

    @Override
    public void submit(PieceRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        ItemStack stack = state.pieceStack;
        if (stack.isEmpty()) {
            return; // 空数据（坏档兜底）→ 不画
        }
        PieceData data = PieceData.get(stack);
        if (!data.isLocked() || data.voxels() == null) {
            return; // 未锁定（不应存在）→ 不画
        }
        PieceData.VoxelData vox = data.voxels();
        BlockState[] states = vox.expand();

        // 运行时方块模型集（ModelManager 缓存，运行时查表无 IO）
        var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();

        // 世界内尺寸：scale 存分母 N（2/4/8），每体素 = 1/(16N) 格，N 越大棋子越小（16³ 蓝图选 1/2 → 总占 0.5 格）。
        // （物品/掉落物侧不 ÷16：见 GamePieceSpecialRenderer，恒为原生 1px/体素。）
        float block = 1.0F / (16.0F * vox.scale());
        float sx = vox.sizeX() * block;
        float sz = vox.sizeZ() * block;

        // 头颅式朝向：绕棋子水平中心转 yRot（原点=实体 pos，先居中 translate 再转）
        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(state.yRot));

        // 水平按 sx/sz 居中（旋转后局部 -x/2 ~ +x/2），y=0 是底部贴放置面
        poseStack.translate(-sx / 2.0F, 0.0F, -sz / 2.0F);

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
                    // 每格独立列表（见类头注释：共享列表+延迟提交=串染 bug 根因）
                    List<BlockStateModelPart> parts = new ArrayList<>(16);
                    // 多变体方块按位置种子挑变体：种子必须逐格重播，否则共享 RandomSource 每帧前跑 → 同格每帧换样（闪烁）。
                    RANDOM.setSeed(GameDefinition.AreaDef.voxelSeed(x, y, z));
                    model.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, st, RANDOM, parts);
                    if (parts.isEmpty()) {
                        continue;
                    }

                    poseStack.pushPose();
                    // 体素在棋子内的位置：数据行序 x→y→z（y=0 底部），与渲染坐标一致
                    poseStack.translate(x * block, y * block, z * block);
                    poseStack.scale(block, block, block);
                    // 光照用基类 extract 算好的实体本格光照（贴墙不至于全黑）
                    collector.submitBlockModel(poseStack, RenderTypes.solidMovingBlock(),
                            parts, BlockModelRenderState.EMPTY_TINTS,
                            state.lightCoords, 0, 0);
                    poseStack.popPose();
                }
            }
        }
        poseStack.popPose();
    }

    /** 渲染状态：快照棋子物品 + 本格光照 + 放置朝向（26.x EntityRenderState 无 yRot 字段，朝向自己快照）。 */
    public static class PieceRenderState extends EntityRenderState {
        public ItemStack pieceStack = ItemStack.EMPTY;
        /** 头颅式放置朝向（度）。 */
        public float yRot;
    }

    @Override
    public PieceRenderState createRenderState() {
        return new PieceRenderState();
    }

    /** 提交前快照：把同步 stack 和光照从实体拷进渲染状态（26.x 标准流程）。 */
    @Override
    public void extractRenderState(GamePieceEntity entity, PieceRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick); // 位置/年龄/光照等基类字段
        state.pieceStack = entity.getPieceStack();
        state.yRot = entity.getYRot(); // 放置朝向快照（存档随实体 yRot 自动恢复）
    }
}
