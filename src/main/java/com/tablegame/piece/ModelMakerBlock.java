package com.tablegame.piece;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 模型制作器方块（tablegame:model_maker）。
 *
 * <p>功能方块：右键打开制作 GUI。实现 {@link EntityBlock} 提供方块实体（存蓝图槽/粘土槽/比例记忆）——
 * 26.x 标准做法，接口只有 newBlockEntity 一个抽象方法。
 *
 * <p>占位外观：模型引用原版切石机（stonecutter）的方块模型，blockstate 指向
 * minecraft:block/stonecutter 即可，Java 侧不用动（美术期再换）。
 *
 * <p>碰撞箱同切石机（高 9/16 的台面）。非满方块必须 noOcclusion。
 */
public class ModelMakerBlock extends Block implements EntityBlock {

    public static final MapCodec<ModelMakerBlock> CODEC = simpleCodec(ModelMakerBlock::new);

    /** 切石机同款碰撞箱（占位；台面高度 9/16）。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 9, 16);

    public ModelMakerBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends ModelMakerBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /** 提供方块实体：newBlockEntity 是 EntityBlock 唯一必须实现的方法。 */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ModelMakerBlockEntity(pos, state);
    }

    /**
     * 右键制作器（无论手持什么）→ 打开制作 GUI。
     *
     * <p>双端分工：客户端只回 SUCCESS（让原版把交互发包到服务端），服务端用 NF 扩展
     * {@code player.openMenu(MenuProvider, BlockPos)} 打开界面 —— 带 BlockPos 的重载把坐标写进打开包的
     * 额外数据，客户端工厂从 buf 读回坐标，构造「指向同一个方块」的 Menu。槽位内容由原版容器同步协议分发。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            // 服务端 Menu 包真 BE（权威数据）；SimpleMenuProvider 只是个「菜单工厂壳」
            sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new ModelMakerMenu(id, inv, pos,
                            level.getBlockEntity(pos) instanceof ModelMakerBlockEntity be
                                    ? be : new net.minecraft.world.SimpleContainer(ModelMakerBlockEntity.NUM_SLOTS),
                            ContainerLevelAccess.create(level, pos)),
                    Component.translatable("block.tablegame.model_maker")), pos);
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}