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
import com.tablegame.area.AreaToolKit;
import com.tablegame.host.GameManager;

/**
 * 区域落地的网络层：一个 C2S 包 —— 玩家选好位置、确认盖章时发。
 *
 * <p>与捕获那套（{@link AreaCapturePackets}）的分工：捕获是「世界 → 快照」（要两个角，多一个「定角」包 +
 * 服务端拦右键），落地是「快照 → 世界」（只要一个落点，客户端用准星自己算，服务端不拦手势）⇒ 只要一个包。
 *
 * @param mode 覆盖策略：{@code "none"} / {@code "all"} / {@code "non_air"}（认不出的当 {@code none}，最安全）
 */
public final class AreaPlacePackets {
    private AreaPlacePackets() {}

    /** C2S：在那一点落地这份区域（mode = 覆盖策略）。 */
    public record PlacePayload(String game, String area, int x, int y, int z, String mode)
            implements CustomPacketPayload {
        public static final Type<PlacePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_place"));
        public static final StreamCodec<ByteBuf, PlacePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlacePayload::game,
                ByteBufCodecs.STRING_UTF8, PlacePayload::area,
                ByteBufCodecs.VAR_INT, PlacePayload::x,
                ByteBufCodecs.VAR_INT, PlacePayload::y,
                ByteBufCodecs.VAR_INT, PlacePayload::z,
                ByteBufCodecs.STRING_UTF8, PlacePayload::mode,
                PlacePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S：进 / 出摆放模式（{@code op} = {@code begin} / {@code end}）。
     *
     * <p>「摆放模式」原来整个住在客户端（服务端只在落地那一下才知道），而「发一枚区域工具给你」必须服务端做
     * ⇒ 进出各报一声。服务端只做两件事：begin = 权限闸（与落地同一道）+ 发工具；end = 收走工具（幂等）。
     */
    public record ToolPayload(String game, String area, String mode, String op) implements CustomPacketPayload {
        public static final Type<ToolPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_place_tool"));
        public static final StreamCodec<ByteBuf, ToolPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ToolPayload::game,
                ByteBufCodecs.STRING_UTF8, ToolPayload::area,
                ByteBufCodecs.STRING_UTF8, ToolPayload::mode,
                ByteBufCodecs.STRING_UTF8, ToolPayload::op,
                ToolPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(PlacePayload.TYPE, PlacePayload.CODEC, AreaPlacePackets::handlePlace);
        registrar.playToServer(ToolPayload.TYPE, ToolPayload.CODEC, AreaPlacePackets::handleTool);
    }

    /** 进模式：权限闸（与落地同一道）+ 发工具；出模式：收走工具。 */
    private static void handleTool(ToolPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        if ("begin".equals(p.op())) {
            if (!sp.canUseGameMasterBlocks()) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[章] 摆放要创造模式 + 游戏主管权限（OP）"));
                return;                              // 客户端拿不到工具 ⇒ 那一帧就自己退出模式（设计自洽）
            }
            GameManager.giveAreaTool(sp, AreaToolKit.PLACE, p.game(), p.area(), p.mode());
        } else {
            GameManager.takeAreaTool(sp);
        }
    }

    /** 服务端只搬运：权限 / 档 / 区域 / 快照四道校验在 {@code GameManager.startAreaPlace}。 */
    private static void handlePlace(PlacePayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && GameManager.get() != null) {
            GameManager.get().startAreaPlace(sp, p.game(), p.area(), p.x(), p.y(), p.z(), p.mode());
            GameManager.takeAreaTool(sp);            // 落完地就把工具收走（客户端这一下也 stop() 退出模式了）
        }
    }
}
