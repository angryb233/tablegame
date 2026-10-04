package com.tablegame.net;

import com.tablegame.TableGame;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.tablegame.area.AreaCaptureServer;
import com.tablegame.area.AreaToolKit;
import com.tablegame.host.GameManager;

/**
 * 世界区域捕获的网络层 —— C2S 包：{@link RequestPayload} 右键「选择区域」让服务端置入捕获模式
 * 并发一枚区域工具（拿着它才在模式里）；{@link ConfirmPayload} 世界内 Shift+Enter 捕获两角间的
 * 方块快照写进 areas 段，回发 GameData 刷新编辑器。
 *
 * <p>定角（右键 / Shift+右键）不需要包：服务端在捕获模式下拦「对方块右键」事件裁决，客户端只画线框。
 */
public final class AreaCapturePackets {
    private AreaCapturePackets() {}

    /** C2S：请求进入区域捕获模式（game = 游戏名，area = 区域 id）。 */
    public record RequestPayload(String game, String area) implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_capture_request"));
        public static final StreamCodec<ByteBuf, RequestPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestPayload::game,
                ByteBufCodecs.STRING_UTF8, RequestPayload::area,
                RequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：确认捕获（服务端校验两角已定，收完写档回发；尺寸不限）。 */
    public record ConfirmPayload(String game, String area) implements CustomPacketPayload {
        public static final Type<ConfirmPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_capture_confirm"));
        public static final StreamCodec<ByteBuf, ConfirmPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ConfirmPayload::game,
                ByteBufCodecs.STRING_UTF8, ConfirmPayload::area,
                ConfirmPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：取消捕获（服务端清账）。 */
    public record CancelPayload() implements CustomPacketPayload {
        public static final Type<CancelPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_capture_cancel"));
        public static final StreamCodec<ByteBuf, CancelPayload> CODEC = StreamCodec.unit(new CancelPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：定角（x/y/z + shift=是否角2）。服务端记账 + 尺寸校验。 */
    public record CornerPayload(int x, int y, int z, boolean shift) implements CustomPacketPayload {
        public static final Type<CornerPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_capture_corner"));
        public static final StreamCodec<ByteBuf, CornerPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CornerPayload::x,
                ByteBufCodecs.VAR_INT, CornerPayload::y,
                ByteBufCodecs.VAR_INT, CornerPayload::z,
                ByteBufCodecs.BOOL, CornerPayload::shift,
                CornerPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 注册 =====

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, AreaCapturePackets::handleRequest);
        registrar.playToServer(ConfirmPayload.TYPE, ConfirmPayload.CODEC, AreaCapturePackets::handleConfirm);
        registrar.playToServer(CancelPayload.TYPE, CancelPayload.CODEC, AreaCapturePackets::handleCancel);
        registrar.playToServer(CornerPayload.TYPE, CornerPayload.CODEC, AreaCapturePackets::handleCorner);
    }

    private static void handleCancel(CancelPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            AreaCaptureServer.end(sp.getUUID());
            GameManager.takeAreaTool(sp);            // 取消 = 把工具收走（换手退出也走这里）
        }
    }

    private static void handleCorner(CornerPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            var pos = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
            if (p.shift()) {
                AreaCaptureServer.corner2(sp, pos);
            } else {
                AreaCaptureServer.corner1(sp, pos);
            }
        }
    }

    private static void handleRequest(RequestPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && GameManager.get() != null) {
            // 真进了模式才发工具（档/区域不存在时 startAreaCapture 已回话并返回 false）
            if (GameManager.get().startAreaCapture(sp, p.game(), p.area())) {
                GameManager.giveAreaTool(sp, AreaToolKit.CAPTURE, p.game(), p.area(), "");
            }
        }
    }

    private static void handleConfirm(ConfirmPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && GameManager.get() != null) {
            GameManager.get().confirmAreaCapture(sp, p.game(), p.area());
        }
    }
}
