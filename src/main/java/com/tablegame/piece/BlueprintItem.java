package com.tablegame.piece;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.ChatFormatting;

/**
 * 蓝图（tablegame:blueprint）—— 选区工具 + 体素数据载体。
 *
 * <p>右键 = 定/重定角1（已锁定则无反应）；Shift+右键 = 定角2（须已有角1）；Shift+Enter 发确认包 →
 * 服务端捕获选区并锁定：选角清除，之后右键永远 PASS。
 *
 * <p>数据改动全在服务端裁决（防伪造包）；ItemStack.set 后原版库存同步把新组件刷回客户端，客户端返回 SUCCESS
 * 是让原版把交互发包给服务端的开关。26.x 差异：use() 无 ItemStack 参数；提示用 sendOverlayMessage。
 */
public class BlueprintItem extends Item {

    public BlueprintItem(Properties props) {
        super(props);
    }

    // ===== 右键方块面：定角（对实心方块右键时走这里） =====

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (level.isClientSide()) {
            // 客户端：未锁定就返回 SUCCESS——让原版把这次右键完整发包到服务端执行
            return PieceData.get(stack).isLocked() ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        if (player == null) {
            return InteractionResult.PASS;
        }
        PieceData data = PieceData.get(stack);
        if (data.isLocked()) {
            return InteractionResult.PASS; // 已锁定 = 选角已清空，右键不再有任何反应
        }
        BlockPos clicked = context.getClickedPos();
        if (player.isSecondaryUseActive()) {
            // Shift+右键 = 定角2（须已有角1）
            if (data.selection() == null) {
                player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.no_corner1"));
                return InteractionResult.FAIL;
            }
            PieceData.set(stack, new PieceData(data.selection().withCorner2(clicked), null, null));
            player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.corner2", clicked.toShortString()));
        } else {
            // 普通右键 = 定/重定角1
            PieceData.set(stack, new PieceData(new PieceData.Selection(clicked, java.util.Optional.empty()), null, null));
            player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.corner1", clicked.toShortString()));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    // ===== 对空气右键（想定天上/远处一个点时）走这里：同样定角1 =====
    // 26.x 签名：use(Level, Player, InteractionHand)——没有 ItemStack 参数。

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return PieceData.get(stack).isLocked() ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        PieceData data = PieceData.get(stack);
        if (data.isLocked()) {
            return InteractionResult.PASS;
        }
        // 沿准星射线取 5.0 格内的命中点（includeFluids=false 只打实体），取其方块坐标
        HitResult hit = sp.pick(5.0, 1.0F, false);
        // 同上：取「点中的那格」（整数）—— 别 floor 命中点浮点坐标（带 ε，会落到隔壁那格）
        BlockPos clicked = hit instanceof net.minecraft.world.phys.BlockHitResult bh
                ? bh.getBlockPos() : BlockPos.containing(hit.getLocation());
        PieceData.set(stack, new PieceData(new PieceData.Selection(clicked, java.util.Optional.empty()), null, null));
        sp.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.corner1", clicked.toShortString()));
        return InteractionResult.SUCCESS_SERVER;
    }

    // ===== 悬停提示：一眼看出蓝图处于什么状态、怎么用 =====
    // 26.x 新签名（TooltipDisplay + Consumer）；自定义行无条件追加即可

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display,
            java.util.function.Consumer<Component> out, TooltipFlag flag) {
        PieceData d = PieceData.get(stack);
        if (d.isLocked()) {
            // 已锁定：描述 = 作者 · 制作日期
            if (d.meta() != null) {
                out.accept(Component.translatable("tooltip.tablegame.blueprint.meta",
                        d.meta().author(), d.meta().date()).withStyle(ChatFormatting.GRAY));
            }
        } else {
            out.accept(Component.translatable("tooltip.tablegame.blueprint.selecting")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    // ===== 服务端确认并锁定（BlueprintPackets 收到确认包后调用） =====
    // name = 玩家输入的棋子名（null = 不命名，物品显示默认名「蓝图」）

    public static void confirmAndLock(ServerPlayer player, InteractionHand hand, String name) {
        ItemStack stack = player.getItemInHand(hand);
        PieceData data = PieceData.get(stack);
        if (data.selection() == null || !data.selection().hasCorner2()) {
            player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.need_two_corners"));
            return;
        }
        BlockPos c1 = data.selection().corner1();
        BlockPos c2 = data.selection().corner2OrNull();
        PieceData captured = PieceData.capture(player.level(), c1, c2);
        if (captured == null) {
            player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.too_big"));
            return;
        }
        // 元数据（作者/日期）随锁定写入：悬停提示展示「作者 · 日期」，随 applyComponents 拷给棋子
        String date = java.time.LocalDate.now().toString(); // ISO 格式 YYYY-MM-DD
        captured = new PieceData(null, captured.voxels(),
                new PieceData.Meta(player.getGameProfile().name(), date));
        PieceData.set(stack, captured); // selection = null → 锁定
        // 棋子名写进原版 CUSTOM_NAME 组件：悬停提示自动显示（斜体绿名），零自定义渲染
        if (name != null) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        }
        player.sendOverlayMessage(Component.translatable("message.tablegame.blueprint.locked",
                name != null ? name : Component.translatable("item.tablegame.blueprint").getString()));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.4f, 1.8f);
    }
}