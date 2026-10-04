package com.tablegame.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 棋牌桌方块（tablegame:game_table）：相邻自动连接 —— 紧挨着放拼成一张大桌，共享边的桌沿隐藏、桌腿合并。
 * 实现 = 方块状态 + multipart 模型：本类用 8 个布尔状态记录哪些方向有相邻桌子，资源侧 multipart 据此显隐部件。
 *
 * <pre>
 * 8 状态：north/east/south/west + 四对角 north_east/north_west/south_east/south_west。
 * 西/东桌沿连接变体（+z 向南；北桌沿 z[0,2]，南桌沿 z[14,16]）：
 *   w00: 南北都无邻居 → z[2,14]    w01: 南边有邻居 → z[2,16]
 *   w10: 北边有邻居   → z[0,14]    w11: 南北都有邻居 → z[0,16]（通长）
 * </pre>
 * ⚠ 东桌沿是同一模型转 180° 渲染（z 翻转）⇒ 东侧 w01/w10 条件与西侧相反。
 */
public class GameTableBlock extends Block {

    // ==================== 形状（碰撞 + 光照遮挡基准） ====================
    // 桌面：y=12..16、4 像素厚；Block.box 单位是 1/16 格（内部自动 /16）。
    private static final VoxelShape TOP = Block.box(0, 12, 0, 16, 16, 16);
    // 四根桌腿：2×2 见方、y=0..12（顶到桌面底部）。
    private static final VoxelShape LEG_NW = Block.box(0, 0, 0, 2, 12, 2);    // 西北角 (x小, z小)
    private static final VoxelShape LEG_NE = Block.box(14, 0, 0, 16, 12, 2);   // 东北角 (x大, z小)
    private static final VoxelShape LEG_SW = Block.box(0, 0, 14, 2, 12, 16);   // 西南角 (x小, z大)
    private static final VoxelShape LEG_SE = Block.box(14, 0, 14, 16, 12, 16); // 东南角 (x大, z大)
    // 把桌面 + 四根腿合并成整体形状。
    private static final VoxelShape SHAPE = Shapes.or(TOP, LEG_NW, LEG_NE, LEG_SW, LEG_SE);

    // Codec：26.x 起方块必须提供；simpleCodec = 用 Properties 反序列化出方块实例。
    public static final MapCodec<GameTableBlock> CODEC = simpleCodec(GameTableBlock::new);

    // 8 个「是否有相邻同款桌子」的布尔状态，multipart 据此显隐；对角状态供转角件判断 L/T 形内角。
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty NORTH_EAST = BooleanProperty.create("north_east");
    public static final BooleanProperty NORTH_WEST = BooleanProperty.create("north_west");
    public static final BooleanProperty SOUTH_EAST = BooleanProperty.create("south_east");
    public static final BooleanProperty SOUTH_WEST = BooleanProperty.create("south_west");

    public GameTableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // 注册默认状态：八个方向都「未连接」（单独一张桌子 = 四边桌沿 + 四根腿全渲染）。
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(NORTH_EAST, false)
                .setValue(NORTH_WEST, false)
                .setValue(SOUTH_EAST, false)
                .setValue(SOUTH_WEST, false));
    }

    @Override
    protected MapCodec<? extends GameTableBlock> codec() {
        return CODEC;
    }

    // 声明本方块有哪些方块状态属性（必须与 registerDefaultState 用到的属性一致）。
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST);
    }

    /**
     * 返回碰撞形状（桌面 + 四腿），而非默认整格立方：
     * 1) 碰撞 —— 只能站上桌面、被桌腿挡，而不是整格实心墙；
     * 2) 光照 —— 默认整格被当成实心块挡死天光，换成非 full block 后光从桌腿间透下，阴影消失。
     * （getOcclusionShape 默认委托 getShape，光照遮挡一并修正。）
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // 放置时：探测四正 + 四对角邻居，设置 8 个连接状态。
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
                .setValue(NORTH, isTable(context.getLevel().getBlockState(pos.north())))
                .setValue(EAST, isTable(context.getLevel().getBlockState(pos.east())))
                .setValue(SOUTH, isTable(context.getLevel().getBlockState(pos.south())))
                .setValue(WEST, isTable(context.getLevel().getBlockState(pos.west())))
                .setValue(NORTH_EAST, isTable(context.getLevel().getBlockState(pos.north().east())))
                .setValue(NORTH_WEST, isTable(context.getLevel().getBlockState(pos.north().west())))
                .setValue(SOUTH_EAST, isTable(context.getLevel().getBlockState(pos.south().east())))
                .setValue(SOUTH_WEST, isTable(context.getLevel().getBlockState(pos.south().west())));
    }

    // 邻居变化时重算 8 个状态，让桌沿实时伸缩（自动连接实时刷新的关键）。
    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess ticks,
            BlockPos pos,
            Direction directionToNeighbour,
            BlockPos neighbourPos,
            BlockState neighbourState,
            RandomSource random) {
        return state
                .setValue(NORTH, isTable(level.getBlockState(pos.north())))
                .setValue(EAST, isTable(level.getBlockState(pos.east())))
                .setValue(SOUTH, isTable(level.getBlockState(pos.south())))
                .setValue(WEST, isTable(level.getBlockState(pos.west())))
                .setValue(NORTH_EAST, isTable(level.getBlockState(pos.north().east())))
                .setValue(NORTH_WEST, isTable(level.getBlockState(pos.north().west())))
                .setValue(SOUTH_EAST, isTable(level.getBlockState(pos.south().east())))
                .setValue(SOUTH_WEST, isTable(level.getBlockState(pos.south().west())));
    }

    // 判断某方块是不是同款棋牌桌（state.is(this) = 同一方块类型）。
    private boolean isTable(BlockState state) {
        return state.is(this);
    }
}
