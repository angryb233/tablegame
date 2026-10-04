package com.tablegame.net;

import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.tablegame.TableGame;
import com.tablegame.host.HostManager;

/**
 * 游戏台（tablegame:dealer）的网络载荷。
 *
 * <p>台子状态在服务端，客户端只是视图：选中哪款 / 这台在跑哪一局都归 {@link HostManager} 的台级状态所有 ——
 * 「别人打开同一台看到的是已选好的游戏」、「同一台开不出第二局」这两件事天然成立，不用客户端互相同步。
 *
 * <p>C2S 四件（都带台坐标）：{@link SelectPayload} 选游戏 · {@link RunPayload} 运行 ·
 * {@link EnterPayload} 进入游戏界面（= 当观众，点准备才入座）· {@link AbortPayload} 中断。
 * <br>S2C 一件：{@link StatePayload} 台状态（打开/操作后下发，台边 16 格内广播）。
 */
public final class DealerPackets {

    // ===== C2S =====

    /** 【进入游戏】（列表页）／换选：把这款设成这台选中的游戏，并跳到它的总览页。 */
    public record SelectPayload(BlockPos pos, String gameName) implements CustomPacketPayload {
        public static final Type<SelectPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "dealer_select"));
        public static final StreamCodec<ByteBuf, SelectPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SelectPayload::pos,
                ByteBufCodecs.STRING_UTF8, SelectPayload::gameName,
                SelectPayload::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 【运行】（总览页）：用这台选中的游戏开局。同一台已有局 → 回话拒绝。 */
    public record RunPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<RunPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "dealer_run"));
        public static final StreamCodec<ByteBuf, RunPayload> CODEC =
                BlockPos.STREAM_CODEC.map(RunPayload::new, RunPayload::pos);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 【进入游戏界面】：接进这台正在跑的局当观众（没在跑 → 回话）。 */
    public record EnterPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<EnterPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "dealer_enter"));
        public static final StreamCodec<ByteBuf, EnterPayload> CODEC =
                BlockPos.STREAM_CODEC.map(EnterPayload::new, EnterPayload::pos);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 【中断游戏】：收掉这台正在跑的局（只有参与过这一局的人能中断）。 */
    public record AbortPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<AbortPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "dealer_abort"));
        public static final StreamCodec<ByteBuf, AbortPayload> CODEC =
                BlockPos.STREAM_CODEC.map(AbortPayload::new, AbortPayload::pos);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ===== S2C =====

    /**
     * 台状态（打开台子 / 每次操作后下发）。
     *
     * @param pos      哪台（客户端据此决定开屏 or 就地刷新）
     * @param selected 这台选中的游戏名（空串 = 还没选 → 列表页）
     * @param running  这台正在跑的游戏名（空串 = 没在跑）
     * @param seats    正在跑那一局的局内成员名（没局 = 空表；席位表归脚本）
     * @param joined   收包的人是否已在这一局里（决定按钮文案：进入 / 已进入）
     * @param paused   这台那一局是**挂起**的吗（重进存档后非常驻局的常态）：是 → 【运行】变成【继续游戏】
     * @param note     这台要显示的一行提示（空串 = 没有）：重进存档时那一局接不上（脚本改过）时会带上
     */
    public record StatePayload(BlockPos pos, String selected, String running, List<String> seats, boolean joined,
                               boolean paused, String note)
            implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "dealer_state"));
        public static final StreamCodec<ByteBuf, StatePayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StatePayload::pos,
                ByteBufCodecs.STRING_UTF8, StatePayload::selected,
                ByteBufCodecs.STRING_UTF8, StatePayload::running,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), StatePayload::seats,
                ByteBufCodecs.BOOL, StatePayload::joined,
                ByteBufCodecs.BOOL, StatePayload::paused,
                ByteBufCodecs.STRING_UTF8, StatePayload::note,
                StatePayload::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(StatePayload.TYPE, StatePayload.CODEC);      // 客户端处理在 TableGameClient
        registrar.playToServer(SelectPayload.TYPE, SelectPayload.CODEC, DealerPackets::handleSelect);
        registrar.playToServer(RunPayload.TYPE, RunPayload.CODEC, DealerPackets::handleRun);
        registrar.playToServer(EnterPayload.TYPE, EnterPayload.CODEC, DealerPackets::handleEnter);
        registrar.playToServer(AbortPayload.TYPE, AbortPayload.CODEC, DealerPackets::handleAbort);
    }

    // ===== 服务端处理器（逻辑在 HostManager，这里只搬运） =====

    static void handleSelect(SelectPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && HostManager.get() != null) {
            HostManager.get().dealerSelect(sp, p.pos(), p.gameName());
        }
    }

    static void handleRun(RunPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && HostManager.get() != null) {
            HostManager.get().dealerRun(sp, p.pos());
        }
    }

    static void handleEnter(EnterPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && HostManager.get() != null) {
            HostManager.get().dealerEnter(sp, p.pos());
        }
    }

    static void handleAbort(AbortPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && HostManager.get() != null) {
            HostManager.get().dealerAbort(sp, p.pos());
        }
    }

    private DealerPackets() { }
}
