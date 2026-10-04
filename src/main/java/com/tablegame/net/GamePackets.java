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
import com.tablegame.host.GameManager;
import com.tablegame.host.HostManager;

/**
 * 游戏定义功能的网络载荷。模式同 {@link com.tablegame.drawboard.BoardPackets}。
 *
 * <p>S2C（handler 在客户端注册，见 ClientGameHandler）：
 * <ul>
 *   <li>{@link GamesListPayload} 游戏列表（/tablegame games 与新建/删除后刷新共用）
 *   <li>{@link GameDataPayload}  游戏定义全文（JSON 字符串，客户端 Gson 解析）
 *   <li>{@link FacePixelsPayload} 某画板引用的卡面像素（客户端按 art 键缓存）
 *   <li>{@link BlueprintListPayload} 候选蓝图 key 列表（棋子编辑页的绑定数据源，组过滤服务端做完才发）
 * </ul>
 * C2S：RequestGames / CreateGame / OpenGame / RequestFace / DeleteGame / SaveGame / RequestBlueprints。
 *
 * <p>卡面像素由服务端下发：联机时客户端读不到服务器磁盘上的画板文件，服务端代读后按 art 键发一次、
 * 客户端缓存——多张卡共用一画板只走一次线。
 * ponytail: 读画板不做组权限校验（卡面引用即视为允许展示）；
 * 对局可见性（手牌/暗牌）后续一起做，那时才有真实权限场景。
 */
public final class GamePackets {
    private GamePackets() {}

    /**
     * 大块文本（游戏定义 / 舞台定义 / 蓝图原文）在网络上的编码方式 —— 本包下所有载荷共用。
     *
     * <p>不用 {@code ByteBufCodecs.STRING_UTF8}：它是 {@code stringUtf8(32767)}，MC 网络层的单字符串硬上限，
     * 大定义在编码期会抛 EncoderException（String too big）→ 连接被掐断、客户端退出世界。
     *
     * <p>改走字节数组 = VarInt 长度 + 原始 UTF-8 字节，没有字符数上限；天花板交给平台：
     * NeoForge 的 {@code GenericPacketSplitter} 自动把大包拆片、对端重组（压缩链路 2MB / 未压缩 8MB），
     * 不用自定数字、也不用升协议版本。编码格式与原字符串一致，只是拿掉 32767 那道闸。
     *
     * <p>ponytail: 单份定义真涨到 MB 级就该换设计（按段拉取 / 只传 diff），而不是再找更大的上限。
     */
    public static final StreamCodec<ByteBuf, String> BIG_TEXT = ByteBufCodecs.BYTE_ARRAY.map(
            b -> new String(b, java.nio.charset.StandardCharsets.UTF_8),
            s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // ===== S2C =====

    /** 游戏列表条目：名字 + 卡/牌组数量 + 简介（列表行 / 游戏台总览页展示用）。 */
    public record GameInfoPayload(String name, int cardCount, int deckCount, String desc) implements CustomPacketPayload {
        public static final Type<GameInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "game_info"));
        public static final StreamCodec<ByteBuf, GameInfoPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GameInfoPayload::name,
                ByteBufCodecs.VAR_INT, GameInfoPayload::cardCount,
                ByteBufCodecs.VAR_INT, GameInfoPayload::deckCount,
                ByteBufCodecs.STRING_UTF8, GameInfoPayload::desc,
                GameInfoPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GamesListPayload(List<GameInfoPayload> games) implements CustomPacketPayload {
        public static final Type<GamesListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "games_list"));
        public static final StreamCodec<ByteBuf, GamesListPayload> CODEC = StreamCodec.composite(
                GameInfoPayload.CODEC.apply(ByteBufCodecs.list(256)), GamesListPayload::games,
                GamesListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 游戏定义全文。json = GameStore.toJson 的产物（客户端编辑后经 {@link SaveGamePayload} 回传）。 */
    public record GameDataPayload(String name, String json) implements CustomPacketPayload {
        public static final Type<GameDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "game_data"));
        public static final StreamCodec<ByteBuf, GameDataPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, GameDataPayload::name,
                BIG_TEXT, GameDataPayload::json,
                GameDataPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 某画板引用的卡面像素。argb = 每像素 4 字节（大端 ARGB），行优先。找不到画板 = w*h 为 0（客户端画占位）。 */
    public record FacePixelsPayload(String art, int width, int height, byte[] argb) implements CustomPacketPayload {
        public static final Type<FacePixelsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "face_pixels"));
        public static final StreamCodec<ByteBuf, FacePixelsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FacePixelsPayload::art,
                ByteBufCodecs.VAR_INT, FacePixelsPayload::width,
                ByteBufCodecs.VAR_INT, FacePixelsPayload::height,
                ByteBufCodecs.BYTE_ARRAY, FacePixelsPayload::argb,
                FacePixelsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 候选蓝图 key 列表。entries = "组/蓝图名"（客户端显示与存值都用它）。
     * 组过滤在服务端完成（只扫我所在组的库目录），客户端拿到即可信。
     */
    public record BlueprintListPayload(List<String> entries) implements CustomPacketPayload {
        public static final Type<BlueprintListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "blueprint_list"));
        public static final StreamCodec<ByteBuf, BlueprintListPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), BlueprintListPayload::entries,
                BlueprintListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== C2S =====

    public record RequestGamesPayload() implements CustomPacketPayload {
        public static final Type<RequestGamesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "games_list_request"));
        public static final StreamCodec<ByteBuf, RequestGamesPayload> CODEC = StreamCodec.unit(new RequestGamesPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 新建游戏（带示例卡组）。成功后服务端直接回 {@link GameDataPayload} 进入详情。 */
    public record CreateGamePayload(String name) implements CustomPacketPayload {
        public static final Type<CreateGamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "create_game"));
        public static final StreamCodec<ByteBuf, CreateGamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CreateGamePayload::name,
                CreateGamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求打开某游戏定义（进编辑器）。 */
    public record OpenGamePayload(String name) implements CustomPacketPayload {
        public static final Type<OpenGamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "open_game"));
        public static final StreamCodec<ByteBuf, OpenGamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenGamePayload::name,
                OpenGamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求某画板引用的卡面像素（客户端未缓存时发）。 */
    public record RequestFacePayload(String art) implements CustomPacketPayload {
        public static final Type<RequestFacePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "face_request"));
        public static final StreamCodec<ByteBuf, RequestFacePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestFacePayload::art,
                RequestFacePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删除游戏定义（客户端已两次点击确认；全服共享，权限待组系统收紧）。 */
    public record DeleteGamePayload(String name) implements CustomPacketPayload {
        public static final Type<DeleteGamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "delete_game"));
        public static final StreamCodec<ByteBuf, DeleteGamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DeleteGamePayload::name,
                DeleteGamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 保存编辑后的整份游戏定义。json = 客户端编辑器序列化的定义全文。
     * 服务端走同一套 fromJson 规范化再落盘——客户端伪造的非法字段会被解析器剔掉。
     */
    public record SaveGamePayload(String name, String json, boolean quiet) implements CustomPacketPayload {
        public static final Type<SaveGamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "save_game"));
        public static final StreamCodec<ByteBuf, SaveGamePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveGamePayload::name,
                BIG_TEXT, SaveGamePayload::json,
                ByteBufCodecs.BOOL, SaveGamePayload::quiet,      // quiet = 别回执（画布上每动一下都存一次）
                SaveGamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：请求候选蓝图列表（棋子编辑页 init 时发；服务端按我的组过滤）。 */
    public record RequestBlueprintsPayload() implements CustomPacketPayload {
        public static final RequestBlueprintsPayload INSTANCE = new RequestBlueprintsPayload();
        public static final Type<RequestBlueprintsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "blueprint_list_request"));
        public static final StreamCodec<ByteBuf, RequestBlueprintsPayload> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 注册 =====

    /**
     * 客户端射线报告：我看向这格（世界事件 {@code on look} 的入口）。
     *
     * <p>只带坐标 —— 方块 id 由**服务端自己查**（它有世界，客户端报的 id 不能信）。客户端侧去抖
     * （同格不重发，见 {@code StageHudLayer} 的射线段）。
     */
    public record LookPayload(int x, int y, int z, String lookPlayer) implements CustomPacketPayload {
        public static final Type<LookPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "look"));
        public static final StreamCodec<ByteBuf, LookPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LookPayload::x,
                ByteBufCodecs.VAR_INT, LookPayload::y,
                ByteBufCodecs.VAR_INT, LookPayload::z,
                ByteBufCodecs.STRING_UTF8, LookPayload::lookPlayer,
                LookPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(GamesListPayload.TYPE, GamesListPayload.CODEC);
        registrar.playToClient(GameDataPayload.TYPE, GameDataPayload.CODEC);
        registrar.playToClient(FacePixelsPayload.TYPE, FacePixelsPayload.CODEC);
        registrar.playToClient(BlueprintListPayload.TYPE, BlueprintListPayload.CODEC);
        registrar.playToServer(RequestGamesPayload.TYPE, RequestGamesPayload.CODEC, ServerHandler::handleRequestGames);
        registrar.playToServer(CreateGamePayload.TYPE, CreateGamePayload.CODEC, ServerHandler::handleCreate);
        registrar.playToServer(OpenGamePayload.TYPE, OpenGamePayload.CODEC, ServerHandler::handleOpen);
        registrar.playToServer(RequestFacePayload.TYPE, RequestFacePayload.CODEC, ServerHandler::handleRequestFace);
        registrar.playToServer(DeleteGamePayload.TYPE, DeleteGamePayload.CODEC, ServerHandler::handleDelete);
        registrar.playToServer(SaveGamePayload.TYPE, SaveGamePayload.CODEC, ServerHandler::handleSave);
        registrar.playToServer(RequestBlueprintsPayload.TYPE, RequestBlueprintsPayload.CODEC, ServerHandler::handleRequestBlueprints);
        registrar.playToServer(LookPayload.TYPE, LookPayload.CODEC, ServerHandler::handleLook);
    }

    // ===== 服务端处理器（参数搬运，逻辑在 GameManager） =====

    static final class ServerHandler {
        static void handleRequestGames(RequestGamesPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.sendGamesList(sp);
        }

        static void handleCreate(CreateGamePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.createGame(sp, p.name());
        }

        static void handleOpen(OpenGamePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.openGame(sp, p.name());
        }

        static void handleRequestFace(RequestFacePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.sendFacePixels(sp, p.art());
        }

        static void handleDelete(DeleteGamePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.deleteGame(sp, p.name());
        }

        static void handleSave(SaveGamePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.saveGame(sp, p.name(), p.json(), p.quiet());
        }

        static void handleRequestBlueprints(RequestBlueprintsPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) gm.sendBlueprintList(sp);
        }

        /** 客户端报「我看向这格」→ 转给宿主（宿主判是不是局内成员 + 棋盘维度，再发给脚本的 on look）。 */
        static void handleLook(LookPayload p, IPayloadContext ctx) {
            HostManager hm = HostManager.get();
            if (ctx.player() instanceof ServerPlayer sp && hm != null) {
                hm.look(sp, new net.minecraft.core.BlockPos(p.x(), p.y(), p.z()), p.lookPlayer());
            }
        }
    }
}
