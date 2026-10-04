package com.tablegame.table;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import com.tablegame.host.HostManager;

/**
 * 游戏台（tablegame:dealer）—— 对局从它开：右键 → 服务端下台状态 → 客户端开屏；列表页选游戏【进入游戏】→ 总览页 → 【准备】走脚本 {@code seat(actor)} 入座。
 * 零方块实体、零本地状态：台子选了哪款、在跑哪局，全在 {@link HostManager} 里按坐标记（客户端只是视图）——
 * 所以「别人打开看到的是已选好的游戏」「同一台开不出第二局」自动成立。外观 = 原版制箭台。
 */
public class DealerBlock extends Block {

    public static final MapCodec<DealerBlock> CODEC = simpleCodec(DealerBlock::new);

    public DealerBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends DealerBlock> codec() {
        return CODEC;
    }

    /**
     * 右键（无论手持什么）：服务端回一份台状态，客户端收到就开屏。
     * 开屏走 S2C 包而不是 {@code openMenu}：台子屏不需要容器，一个包说完省掉一个空壳 Menu。
     * 客户端回 SUCCESS，服务端回 SUCCESS_SERVER（否则原版交互包不会发上来）。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp) {
            HostManager hm = HostManager.get();
            if (hm != null) hm.dealerOpen(sp, pos.immutable());
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
