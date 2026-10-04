package com.tablegame.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.tablegame.TableGame;

    /**
     * 椅子方块（tablegame:chair）。
     *
     * <p>继承 {@link HorizontalDirectionalBlock}，4 向 FACING；玩家坐下时面朝 FACING（靠背在身后）。
     * 碰撞形状与 v0.5 模型逐部件对应（10 部件，四朝向硬编码），座面顶 10.4/16 = 0.65 格，椅顶 21.6/16 = 1.35 格。
     *
     * <p>座位生命周期：{@link #onPlace} 生成 {@link SeatEntity}；座位每 tick 对位到椅子、椅子被破坏则销毁；
     * {@link #useWithoutItem} 右键挂载到座位；起身落点见 SeatEntity#getDismountLocationForPassenger。
     */
public class ChairBlock extends HorizontalDirectionalBlock {

    // Codec：26.x 方块必须提供，用于序列化（存档 / 数据包）。
    public static final MapCodec<ChairBlock> CODEC = simpleCodec(ChairBlock::new);

    // ===== 四个朝向的碰撞形状（单位 1/16 格，与 v0.5 模型逐部件一致；硬编码） =====
    // 基础摆位（朝北）：靠背在 +z（南）侧。y 旋转映射：
    //   north: (x,z)          east: (x,z)→(16-z,x)
    //   south: (x,z)→(16-x,16-z)   west: (x,z)→(z,16-x)
    private static final VoxelShape SHAPE_NORTH = Shapes.or(
            Block.box(2.8, 0, 2.8, 4.8, 8.4, 4.8),
            Block.box(11.2, 0, 2.8, 13.2, 8.4, 4.8),
            Block.box(2.8, 0, 11.2, 4.8, 8.4, 13.2),
            Block.box(11.2, 0, 11.2, 13.2, 8.4, 13.2),
            Block.box(2.8, 8.4, 2.8, 13.2, 10.4, 13.2),
            Block.box(2.8, 10.4, 11.2, 4.8, 21.6, 13.2),
            Block.box(11.2, 10.4, 11.2, 13.2, 21.6, 13.2),
            Block.box(2.8, 13.4, 12.2, 13.2, 14.4, 13.2),
            Block.box(2.8, 17.4, 12.2, 13.2, 18.4, 13.2),
            Block.box(2.8, 20.6, 12.2, 13.2, 21.6, 13.2)
    );

    private static final VoxelShape SHAPE_EAST = Shapes.or(
            Block.box(11.2, 0, 2.8, 13.2, 8.4, 4.8),
            Block.box(11.2, 0, 11.2, 13.2, 8.4, 13.2),
            Block.box(2.8, 0, 2.8, 4.8, 8.4, 4.8),
            Block.box(2.8, 0, 11.2, 4.8, 8.4, 13.2),
            Block.box(2.8, 8.4, 2.8, 13.2, 10.4, 13.2),
            Block.box(2.8, 10.4, 2.8, 4.8, 21.6, 4.8),
            Block.box(2.8, 10.4, 11.2, 4.8, 21.6, 13.2),
            Block.box(2.8, 13.4, 2.8, 3.8, 14.4, 13.2),
            Block.box(2.8, 17.4, 2.8, 3.8, 18.4, 13.2),
            Block.box(2.8, 20.6, 2.8, 3.8, 21.6, 13.2)
    );

    private static final VoxelShape SHAPE_SOUTH = Shapes.or(
            Block.box(11.2, 0, 11.2, 13.2, 8.4, 13.2),
            Block.box(2.8, 0, 11.2, 4.8, 8.4, 13.2),
            Block.box(11.2, 0, 2.8, 13.2, 8.4, 4.8),
            Block.box(2.8, 0, 2.8, 4.8, 8.4, 4.8),
            Block.box(2.8, 8.4, 2.8, 13.2, 10.4, 13.2),
            Block.box(11.2, 10.4, 2.8, 13.2, 21.6, 4.8),
            Block.box(2.8, 10.4, 2.8, 4.8, 21.6, 4.8),
            Block.box(2.8, 13.4, 2.8, 13.2, 14.4, 3.8),
            Block.box(2.8, 17.4, 2.8, 13.2, 18.4, 3.8),
            Block.box(2.8, 20.6, 2.8, 13.2, 21.6, 3.8)
    );

    private static final VoxelShape SHAPE_WEST = Shapes.or(
            Block.box(2.8, 0, 11.2, 4.8, 8.4, 13.2),
            Block.box(2.8, 0, 2.8, 4.8, 8.4, 4.8),
            Block.box(11.2, 0, 11.2, 13.2, 8.4, 13.2),
            Block.box(11.2, 0, 2.8, 13.2, 8.4, 4.8),
            Block.box(2.8, 8.4, 2.8, 13.2, 10.4, 13.2),
            Block.box(11.2, 10.4, 11.2, 13.2, 21.6, 13.2),
            Block.box(11.2, 10.4, 2.8, 13.2, 21.6, 4.8),
            Block.box(12.2, 13.4, 2.8, 13.2, 14.4, 13.2),
            Block.box(12.2, 17.4, 2.8, 13.2, 18.4, 13.2),
            Block.box(12.2, 20.6, 2.8, 13.2, 21.6, 13.2)
    );

    public ChairBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // 默认状态：朝北
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends ChairBlock> codec() {
        return CODEC;
    }

    // 声明方块状态属性：FACING（继承自 HorizontalDirectionalBlock）
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    // 放置时：朝向 = 玩家面朝的水平方向。
    // 玩家朝北放椅子 → FACING=north → 坐下时面朝北、靠背在南（身后）。
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    // 碰撞形状：按当前朝向返回对应形状（含靠背阻挡，镂空处可通行/透光）。
    // 26.x 里碰撞（getCollisionShape）与拾取（射线检测）都默认委托本方法，
    // 只覆写这一个就同时解决「能选中有碰撞」。
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            default -> SHAPE_WEST;
        };
    }

    /**
     * 椅子被放置（含活塞推入）时同步生成座位实体。
     * 已有座位则不重复生成（活塞推来旧座位会因「椅子没了」自销毁，这里补新的，实现座位跟随椅子）。
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide() && state.is(this)) {
            spawnSeat(level, pos);
        }
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    // 在椅子位置生成座位实体（若**这把**椅子还没有座位）
    private void spawnSeat(Level level, BlockPos pos) {
        if (findSeat(level, pos) != null) {
            return;
        }
        SeatEntity seat = new SeatEntity(TableGame.SEAT.get(), level);
        seat.bindToChair(pos);
        level.addFreshEntity(seat);
        TableGame.LOGGER.info("[Chair] seat spawned at {}", pos);
    }

    /**
     * 右键椅子（空手）：坐到椅子上（仅服务端挂载）。
     *
     * <p>潜行中 / 已在骑乘 → 不响应；否则挂载到本椅子的座位（缺失则兜底补一个），成功则把玩家转向椅子朝向。
     * 客户端只返回 SUCCESS（播右键动画）。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // 潜行 = 起身意图；已在骑乘 = 不能重复坐
        if (player.isSecondaryUseActive() || player.isPassenger()) {
            return InteractionResult.PASS;
        }
        // 客户端：只播动画，真正逻辑在服务端
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        // 服务端：找到本椅子的座位（兜底：没有就现场补一个）
        SeatEntity seat = findSeat(level, pos);
        if (seat == null) {
            spawnSeat(level, pos);
            seat = findSeat(level, pos);
        }
        if (seat == null) {
            TableGame.LOGGER.warn("[Chair] no seat found at {}", pos);
            return InteractionResult.PASS;
        }
        boolean mounted = player.startRiding(seat);
        // 取证字段：潜行 / 主手物品 / 玩家坐标；riderDelta = 玩家脚底相对座位实体的偏移（骑乘附件点），
        // 座位高度靠它校准。
        TableGame.LOGGER.info("[Chair] use at {}, mount={}, sneak={}, item={}, player={}, seatY={}, riderDelta={}",
                pos, mounted, player.isShiftKeyDown(), player.getMainHandItem(),
                player.blockPosition(), seat.getY(), mounted ? player.getY() - seat.getY() : 0);
        if (mounted) {
            player.setYRot(state.getValue(FACING).toYRot()); // 面朝椅子朝向
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * 找这把椅子自己的座位实体（按 {@link SeatEntity#chairPos()} 精确匹配）。
     *
     * <p>⚠ 别用「探针 AABB 里第一个座位」：膨胀后探针会罩到隔壁方块中心的座位，并排椅子会互相抢
     * （后放的以为「已有座位」不生成 ⇒ 右键它反而坐上旁边那把）。探针只负责找得到，归属说了算。
     */
    private SeatEntity findSeat(Level level, BlockPos pos) {
        for (SeatEntity s : level.getEntitiesOfClass(SeatEntity.class, new AABB(pos).inflate(1.0))) {
            if (pos.equals(s.chairPos())) {
                return s;
            }
        }
        return null;
    }
}
