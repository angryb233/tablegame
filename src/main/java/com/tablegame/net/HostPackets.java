package com.tablegame.net;

import java.util.List;

import io.netty.buffer.ByteBuf;

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
 * 主持人的网络载荷。
 * 通道以指令为主（{@code /tablegame start|act|abort}，指令本就在服务端执行）；
 * 客户端有东西要发时才走 C2S（如全屏界面输入，见 {@link ActPayload}）。
 * 可见性过滤在服务端做——手牌/暗牌机制的地基。
 */
public final class HostPackets {
    private HostPackets() {}

    /**
     * 对局状态快照（每次状态变化无条件重发，客户端就地刷新）。
     *
     * @param gameName  游戏名
     * @param seats     局内成员名（席位表归脚本自己的数组）
     * @param varValues 槽位当前值，一行一个（{@code 名字: 值}，行格式见 {@link GameDefinition#snapLine}）；
     *                  已按接收者可见性过滤；舞台「引用显示」按槽位名取（{@link GameDefinition#snapValue}）
     * @param nodeTitle 当前阶段名
     * @param hint      给这个玩家看的一行提示（等输入 / 已结束 / 当前阶段）
     * @param inGame    这个玩家是否还在局里（false = 局已结束/被中断，客户端收起界面）
     * @param waitAction 现在在等的动作 id（空 = 不等输入）；提交时原样回给服务端（服务端再校一次）
     */
    public record HostStatePayload(String gameName, List<String> seats, List<String> varValues,
                                   String nodeTitle, String hint, boolean inGame, String waitAction)
            implements CustomPacketPayload {
        public static final Type<HostStatePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "host_state"));
        public static final StreamCodec<ByteBuf, HostStatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HostStatePayload::gameName,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), HostStatePayload::seats,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), HostStatePayload::varValues,
                ByteBufCodecs.STRING_UTF8, HostStatePayload::nodeTitle,
                ByteBufCodecs.STRING_UTF8, HostStatePayload::hint,
                ByteBufCodecs.BOOL, HostStatePayload::inGame,
                ByteBufCodecs.STRING_UTF8, HostStatePayload::waitAction,
                HostStatePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 舞台定义：开局发一次（json = 宿主按人合成好的线口径定义，客户端用 {@code GameStore.fromJsonWire} 解析），
     * 结束时发一次 {@code inGame=false} 收起界面。舞台静态，故不塞进每次重发的状态快照。
     */
    public record StagePayload(String gameName, String json, boolean inGame) implements CustomPacketPayload {
        public static final Type<StagePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "host_stage"));
        public static final StreamCodec<ByteBuf, StagePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, StagePayload::gameName,
                GamePackets.BIG_TEXT, StagePayload::json,
                ByteBufCodecs.BOOL, StagePayload::inGame,
                StagePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 本局临时画板的全量（{@code art} 框：HUD 直接画，不弹画板屏——收包的人可能在看别处，弹屏会顶掉界面）。
     * 之后的笔迹增量走 {@code BoardCellsPayload}，客户端同时存进 HUD 缓存与打开着的画板屏。
     */
    public record StageBoardPayload(String key, int width, int height, byte[] cells)
            implements CustomPacketPayload {
        public static final Type<StageBoardPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "stage_board"));
        public static final StreamCodec<ByteBuf, StageBoardPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, StageBoardPayload::key,
                ByteBufCodecs.VAR_INT, StageBoardPayload::width,
                ByteBufCodecs.VAR_INT, StageBoardPayload::height,
                ByteBufCodecs.BYTE_ARRAY, StageBoardPayload::cells,
                StageBoardPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 玩家在全屏界面里提交的输入；服务端走同一条 {@code HostManager.input}（校验一致：局内人 + 正在等输入）。
     *
     * @param actionId 提交给哪个动作（来自快照的 waitAction，客户端不自行编造）
     * @param text     输入文本（@input 的值）
     * @param box      点了哪个框（舞台去模板化点击；空 = 不是点击，是普通输入提交）
     */
    public record ActPayload(String actionId, String text, String box) implements CustomPacketPayload {
        public static final Type<ActPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "host_act"));
        public static final StreamCodec<ByteBuf, ActPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ActPayload::actionId,
                ByteBufCodecs.STRING_UTF8, ActPayload::text,
                ByteBufCodecs.STRING_UTF8, ActPayload::box,
                ActPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 「现在打开 / 收掉全屏舞台界面」：{@code /tablegame ui} 开屏，对局结束收屏。
     * 不带布局数据（走 {@link StagePayload}）；形态该不该自动开由客户端自判（它知道 stage.mode）。
     */
    public record StageUiPayload(boolean open) implements CustomPacketPayload {
        public static final Type<StageUiPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "host_stage_ui"));
        public static final StreamCodec<ByteBuf, StageUiPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, StageUiPayload::open,
                StageUiPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 服务端处理全屏界面来的输入，参数搬运到主持人（校验在 {@code HostManager}）。
     * 按 {@code actionId} 分：空 = 点击（{@link HostManager#pick}）；非空 = 输入提交（{@link HostManager#input}，带 box）。
     */
    static void handleAct(ActPayload p, IPayloadContext ctx) {
        HostManager hm = HostManager.get();
        if (hm == null || !(ctx.player() instanceof ServerPlayer sp)) return;
        if (p.actionId().isEmpty()) hm.pick(sp, p.box());
        else hm.input(sp, p.actionId(), p.text(), p.box());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(HostStatePayload.TYPE, HostStatePayload.CODEC);
        registrar.playToClient(StagePayload.TYPE, StagePayload.CODEC);
        registrar.playToClient(StageBoardPayload.TYPE, StageBoardPayload.CODEC);
        registrar.playToClient(StageUiPayload.TYPE, StageUiPayload.CODEC);
        registrar.playToServer(ActPayload.TYPE, ActPayload.CODEC, HostPackets::handleAct);
    }
}
