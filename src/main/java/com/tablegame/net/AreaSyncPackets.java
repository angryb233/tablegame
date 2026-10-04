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
import com.tablegame.host.GameManager;

/**
 * 区域 ↔ 场景的双向同步：一个 C2S 包、两种动作。
 *
 * <ul>
 *   <li>{@code op = "capture"} → 【更新进编辑器】：按区域声明的盒抓一份快照写进档（{@code GameManager.captureRegion}）；</li>
 *   <li>{@code op = "place"} → 【应用到世界】：把区域引用的那份快照按声明的盒盖章（{@code GameManager.placeRegion}，{@code mode} = 覆盖策略三档）。</li>
 * </ul>
 *
 * <p>不复用捕获 / 落地那两个包：那两条的「范围 / 落点」是客户端给的（框选两角、准星定点），
 * 这两条是声明给的 —— 服务端解析脚本就算得出来，所以客户端只说「对哪个游戏的哪条区域做哪件事」。
 */
public final class AreaSyncPackets {
    private AreaSyncPackets() {}

    /** C2S：对某条区域做一次同步（op = capture / place；mode = 覆盖策略，只有 place 用）。 */
    public record SyncPayload(String game, String region, String op, String mode)
            implements CustomPacketPayload {
        public static final Type<SyncPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_sync"));
        public static final StreamCodec<ByteBuf, SyncPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SyncPayload::game,
                ByteBufCodecs.STRING_UTF8, SyncPayload::region,
                ByteBufCodecs.STRING_UTF8, SyncPayload::op,
                ByteBufCodecs.STRING_UTF8, SyncPayload::mode,
                SyncPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SyncPayload.TYPE, SyncPayload.CODEC, AreaSyncPackets::handle);
    }

    /** 服务端只搬运：权限 / 档 / 区域 / 尺寸四道校验在 {@code GameManager} 那两个方法里。 */
    private static void handle(SyncPayload p, IPayloadContext ctx) {
        GameManager gm = GameManager.get();
        if (!(ctx.player() instanceof ServerPlayer sp) || gm == null) return;
        if ("capture".equals(p.op())) {
            gm.captureRegion(sp, p.game(), p.region());
        } else if ("place".equals(p.op())) {
            gm.placeRegion(sp, p.game(), p.region(), p.mode());
        }
    }
}
