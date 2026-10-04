package com.tablegame.net;

import com.tablegame.TableGame;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 蓝图幽灵预览的网络层：一条 S2C 包（服务端 → 那颗玩家）。只有 S2C：玩家侧是「看」不是「放」，开关 / 落点 / 收回全由脚本说。
 * 包里不带快照：收包客户端已有整份定义（{@code ClientGameHandler.stageGame()}），只发「哪条区域 + 落点」，客户端自己查。
 *
 * @param game 游戏名（客户端拿它跟手里那份定义对一下）
 * @param area 区域 id（项目档 {@code areas} 段里的那条快照）
 * @param x,y,z 落点 = 区域最小角那格
 * @param on   true = 开（钉在这格）· false = 关（收回）
 */
public final class AreaGhostPackets {
    private AreaGhostPackets() {}

    /** S2C：给这个玩家开 / 关一份幽灵预览。 */
    public record GhostPayload(String game, String area, int x, int y, int z, boolean on)
            implements CustomPacketPayload {
        public static final Type<GhostPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "area_ghost"));
        public static final StreamCodec<ByteBuf, GhostPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GhostPayload::game,
                ByteBufCodecs.STRING_UTF8, GhostPayload::area,
                ByteBufCodecs.VAR_INT, GhostPayload::x,
                ByteBufCodecs.VAR_INT, GhostPayload::y,
                ByteBufCodecs.VAR_INT, GhostPayload::z,
                ByteBufCodecs.BOOL, GhostPayload::on,
                GhostPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(GhostPayload.TYPE, GhostPayload.CODEC);
    }
}
