package com.tablegame.piece;

import com.tablegame.TableGame;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 蓝图网络层 —— 只有一个 C2S 包：确认捕获（带玩家输入的棋子名）。
 * 选角不需要自定义包：右键 / Shift+右键走原版交互管线（useOn/use），服务端裁决后写进物品数据组件，
 * 原版库存同步自动刷回客户端；只有「按键盘键」原版管线覆盖不了，所以只有它需要一个包。
 * hand = 主副手哪只手；name = 棋子名（服务端写进 CUSTOM_NAME，悬停提示自动变绿；服务端裁剪长度防伪造包）。
 */
public final class BlueprintPackets {
    private BlueprintPackets() {}

    /** C2S：确认当前手持蓝图的选区 → 服务端捕获并锁定（name = 棋子名）。 */
    public record ConfirmBlueprintPayload(int hand, String name) implements CustomPacketPayload {
        public static final Type<ConfirmBlueprintPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "confirm_blueprint"));
        public static final StreamCodec<ByteBuf, ConfirmBlueprintPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ConfirmBlueprintPayload::hand,
                ByteBufCodecs.STRING_UTF8, ConfirmBlueprintPayload::name,
                ConfirmBlueprintPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 注册 =====

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ConfirmBlueprintPayload.TYPE, ConfirmBlueprintPayload.CODEC,
                BlueprintPackets::handleConfirm);
    }

    private static void handleConfirm(ConfirmBlueprintPayload payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            InteractionHand hand = payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            // 服务端再次校验手里确实是蓝图（防伪造包直接调逻辑）
            if (sp.getItemInHand(hand).getItem() instanceof BlueprintItem) {
                // 名字清洗：裁到 32 字符（与客户端窗口限制一致）；空名 → null（不设自定义名，显示默认「蓝图」）
                String name = payload.name().trim();
                if (name.length() > BlueprintNameScreen.MAX_NAME) {
                    name = name.substring(0, BlueprintNameScreen.MAX_NAME);
                }
                BlueprintItem.confirmAndLock(sp, hand, name.isEmpty() ? null : name);
            }
        }
    }
}