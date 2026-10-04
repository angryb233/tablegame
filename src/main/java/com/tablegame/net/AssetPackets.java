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

/**
 * 组件库的包协议（与 BoardPackets / GamePackets 同套写法）：包只搬参数，逻辑在服务端总管 {@link GameManager}。
 * S2C：PackListPayload 库列表 · PackContentPayload 资产清单 · AssetPixelsPayload 资产像素（就地画预览）。
 * C2S：RequestPacksPayload 要列表 · OpenPackPayload 打开包 · RequestAssetPixelsPayload 要像素 ·
 * PackFromBoardPayload 打包（把画板项目像素收进库里某个包）。
 * 没有「导出」：包只存在于库里，库 → 项目是导入即拷贝。
 */
public final class AssetPackets {

    private AssetPackets() {
    }

    // ==================== S2C ====================

    /** 库里有哪些包（字母序）。 */
    public record PackListPayload(List<String> packs) implements CustomPacketPayload {
        public static final Type<PackListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_list"));
        public static final StreamCodec<ByteBuf, PackListPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), PackListPayload::packs,
                PackListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 一条资产的元数据（列表行 / 预览标题用）。**不是**独立包，只作嵌套 codec 用（随包内容一起发）。 */
    public record AssetInfo(String id, String kind, String name, String color, int w, int h,
            String base, List<String> lore) {
        public static final StreamCodec<ByteBuf, AssetInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, AssetInfo::id,
                ByteBufCodecs.STRING_UTF8, AssetInfo::kind,
                ByteBufCodecs.STRING_UTF8, AssetInfo::name,
                ByteBufCodecs.STRING_UTF8, AssetInfo::color,
                ByteBufCodecs.VAR_INT, AssetInfo::w,
                ByteBufCodecs.VAR_INT, AssetInfo::h,
                ByteBufCodecs.STRING_UTF8, AssetInfo::base,                       // 物品类：基底原版物品 id
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), AssetInfo::lore,
                AssetInfo::new);
    }

    /** 某个包的资产清单。 */
    public record PackContentPayload(String pack, String desc, List<AssetInfo> assets) implements CustomPacketPayload {
        public static final Type<PackContentPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_content"));
        public static final StreamCodec<ByteBuf, PackContentPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PackContentPayload::pack,
                ByteBufCodecs.STRING_UTF8, PackContentPayload::desc,
                AssetInfo.CODEC.apply(ByteBufCodecs.list(512)), PackContentPayload::assets,
                PackContentPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 某条资产的像素（ARGB 字节流；size 为 0 = 没有，屏上不画预览）。 */
    public record AssetPixelsPayload(String pack, String id, int w, int h, byte[] argb) implements CustomPacketPayload {
        public static final Type<AssetPixelsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pixels"));
        public static final StreamCodec<ByteBuf, AssetPixelsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, AssetPixelsPayload::pack,
                ByteBufCodecs.STRING_UTF8, AssetPixelsPayload::id,
                ByteBufCodecs.VAR_INT, AssetPixelsPayload::w,
                ByteBufCodecs.VAR_INT, AssetPixelsPayload::h,
                ByteBufCodecs.BYTE_ARRAY, AssetPixelsPayload::argb,
                AssetPixelsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==================== C2S ====================

    public record RequestPacksPayload() implements CustomPacketPayload {
        public static final Type<RequestPacksPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_packs"));
        public static final StreamCodec<ByteBuf, RequestPacksPayload> CODEC = StreamCodec.unit(new RequestPacksPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenPackPayload(String pack) implements CustomPacketPayload {
        public static final Type<OpenPackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_open_pack"));
        public static final StreamCodec<ByteBuf, OpenPackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenPackPayload::pack,
                OpenPackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestAssetPixelsPayload(String pack, String id) implements CustomPacketPayload {
        public static final Type<RequestAssetPixelsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_pixels"));
        public static final StreamCodec<ByteBuf, RequestAssetPixelsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestAssetPixelsPayload::pack,
                ByteBufCodecs.STRING_UTF8, RequestAssetPixelsPayload::id,
                RequestAssetPixelsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 打包：把画板项目 {@code boardKey}（= {@code 组/项目名}）的像素收成资产，放进（或新建）包 {@code pack}。 */
    public record PackFromBoardPayload(String boardKey, String pack, String name, String kind) implements CustomPacketPayload {
        public static final Type<PackFromBoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_from_board"));
        public static final StreamCodec<ByteBuf, PackFromBoardPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PackFromBoardPayload::boardKey,
                ByteBufCodecs.STRING_UTF8, PackFromBoardPayload::pack,
                ByteBufCodecs.STRING_UTF8, PackFromBoardPayload::name,
                ByteBufCodecs.STRING_UTF8, PackFromBoardPayload::kind,
                PackFromBoardPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 新建（或确保存在）一个库。 */
    public record CreatePackPayload(String name) implements CustomPacketPayload {
        public static final Type<CreatePackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_create_pack"));
        public static final StreamCodec<ByteBuf, CreatePackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CreatePackPayload::name,
                CreatePackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 改一条资产的显示名（双击行用）。 */
    public record RenameAssetPayload(String pack, String id, String name) implements CustomPacketPayload {
        public static final Type<RenameAssetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_rename"));
        public static final StreamCodec<ByteBuf, RenameAssetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RenameAssetPayload::pack,
                ByteBufCodecs.STRING_UTF8, RenameAssetPayload::id,
                ByteBufCodecs.STRING_UTF8, RenameAssetPayload::name,
                RenameAssetPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 要「可选的画板项目」列表（= 添加美术时的来源；只列我所在组的，与画板菜单口径一致）。 */
    public record RequestBoardsPayload() implements CustomPacketPayload {
        public static final Type<RequestBoardsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_boards"));
        public static final StreamCodec<ByteBuf, RequestBoardsPayload> CODEC = StreamCodec.unit(new RequestBoardsPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 可选画板项目 key（{@code 组/名}）。 */
    public record BoardsPayload(List<String> keys) implements CustomPacketPayload {
        public static final Type<BoardsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_boards"));
        public static final StreamCodec<ByteBuf, BoardsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), BoardsPayload::keys,
                BoardsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 要「可选的蓝图」列表（模型页添加来源 = **蓝图库**，不是画板项目）。 */
    public record RequestBlueprintsPayload() implements CustomPacketPayload {
        public static final Type<RequestBlueprintsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_blueprints"));
        public static final StreamCodec<ByteBuf, RequestBlueprintsPayload> CODEC = StreamCodec.unit(new RequestBlueprintsPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 可选蓝图 key（{@code 组/名}）。 */
    public record BlueprintsPayload(List<String> keys) implements CustomPacketPayload {
        public static final Type<BlueprintsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_blueprints"));
        public static final StreamCodec<ByteBuf, BlueprintsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), BlueprintsPayload::keys,
                BlueprintsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 把一条蓝图**拷进**某个库（模型美术资产）。 */
    public record PackBlueprintPayload(String key, String pack, String id) implements CustomPacketPayload {
        public static final Type<PackBlueprintPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_blueprint"));
        public static final StreamCodec<ByteBuf, PackBlueprintPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PackBlueprintPayload::key,
                ByteBufCodecs.STRING_UTF8, PackBlueprintPayload::pack,
                ByteBufCodecs.STRING_UTF8, PackBlueprintPayload::id,
                PackBlueprintPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 蓝图原文（客户端造物品、画棋子缩略图用）。 */
    public record BlueprintDataPayload(String pack, String id, String json) implements CustomPacketPayload {
        public static final Type<BlueprintDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_blueprint_data"));
        public static final StreamCodec<ByteBuf, BlueprintDataPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BlueprintDataPayload::pack,
                ByteBufCodecs.STRING_UTF8, BlueprintDataPayload::id,
                GamePackets.BIG_TEXT, BlueprintDataPayload::json,
                BlueprintDataPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 要某条模型资产的蓝图原文。 */
    public record RequestBlueprintDataPayload(String pack, String id) implements CustomPacketPayload {
        public static final Type<RequestBlueprintDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_blueprint_data"));
        public static final StreamCodec<ByteBuf, RequestBlueprintDataPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestBlueprintDataPayload::pack,
                ByteBufCodecs.STRING_UTF8, RequestBlueprintDataPayload::id,
                RequestBlueprintDataPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 库里一条资产（拍平版：带上它属于哪个包 —— 编辑器「组件」页要跨包列）。 */
    public record Entry(String pack, String id, String kind, String name, int w, int h,
            String base, List<String> lore) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::pack,
                ByteBufCodecs.STRING_UTF8, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::kind,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                ByteBufCodecs.VAR_INT, Entry::w,
                ByteBufCodecs.VAR_INT, Entry::h,
                ByteBufCodecs.STRING_UTF8, Entry::base,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Entry::lore,
                Entry::new);
    }

    /** 导入到当前项目：把库里一条资产拷进游戏档（导入即拷贝）。 */
    public record ImportAssetPayload(String game, String pack, String id) implements CustomPacketPayload {
        public static final Type<ImportAssetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_import"));
        public static final StreamCodec<ByteBuf, ImportAssetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ImportAssetPayload::game,
                ByteBufCodecs.STRING_UTF8, ImportAssetPayload::pack,
                ByteBufCodecs.STRING_UTF8, ImportAssetPayload::id,
                ImportAssetPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** **导入整个库到当前项目**（把包里所有资产一次拷进游戏档；同名覆盖）。 */
    public record ImportPackPayload(String game, String pack) implements CustomPacketPayload {
        public static final Type<ImportPackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_import_pack"));
        public static final StreamCodec<ByteBuf, ImportPackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ImportPackPayload::game,
                ByteBufCodecs.STRING_UTF8, ImportPackPayload::pack,
                ImportPackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删掉库里一条资产（卡牌/模型/物品页用；「组件库」页不管删除）。 */
    public record DeleteAssetPayload(String pack, String id) implements CustomPacketPayload {
        public static final Type<DeleteAssetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_delete"));
        public static final StreamCodec<ByteBuf, DeleteAssetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DeleteAssetPayload::pack,
                ByteBufCodecs.STRING_UTF8, DeleteAssetPayload::id,
                DeleteAssetPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删掉**整个库**（连里面的资产）—— 组件库屏库行右键「删除」。 */
    public record DeletePackPayload(String pack) implements CustomPacketPayload {
        public static final Type<DeletePackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_delete"));
        public static final StreamCodec<ByteBuf, DeletePackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, DeletePackPayload::pack,
                DeletePackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 给库**改个名**（= 改目录名）—— 组件库屏库行右键「编辑」。 */
    public record RenamePackPayload(String pack, String name) implements CustomPacketPayload {
        public static final Type<RenamePackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_pack_rename"));
        public static final StreamCodec<ByteBuf, RenamePackPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RenamePackPayload::pack,
                ByteBufCodecs.STRING_UTF8, RenamePackPayload::name,
                RenamePackPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 建一条「基底覆盖」资产（带 {@code kind}）：基底原版物品或基底原版方块（木牌这类也算）+ 组件覆盖（名字 / 描述）。
     * {@code pack} 写库名 = 进那个库；写 {@code @游戏名} = 进项目自己那份。
     */
    public record PackItemPayload(String kind, String base, String pack, String name, List<String> lore) implements CustomPacketPayload {
        public static final Type<PackItemPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_item_pack"));
        public static final StreamCodec<ByteBuf, PackItemPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PackItemPayload::kind,
                ByteBufCodecs.STRING_UTF8, PackItemPayload::base,
                ByteBufCodecs.STRING_UTF8, PackItemPayload::pack,
                ByteBufCodecs.STRING_UTF8, PackItemPayload::name,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), PackItemPayload::lore,
                PackItemPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** **改一条物品资产的描述行**（组件 {@code minecraft:lore}；库里的 / 项目里的都走这条）。 */
    public record SetItemLorePayload(String pack, String id, List<String> lore) implements CustomPacketPayload {
        public static final Type<SetItemLorePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_item_lore"));
        public static final StreamCodec<ByteBuf, SetItemLorePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SetItemLorePayload::pack,
                ByteBufCodecs.STRING_UTF8, SetItemLorePayload::id,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), SetItemLorePayload::lore,
                SetItemLorePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==================== 外部素材 ====================

    /**
     * {@code games/<游戏>/art/} 里的外部图片清单（文件名，只收 {@code .png}）。
     * 只发清单不发字节：外部图片由服务端自己解（JDK ImageIO），客户端只需看见有哪些文件、点一条让服务端去转。
     */
    public record ArtFilesPayload(List<String> files) implements CustomPacketPayload {
        public static final Type<ArtFilesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_art_files"));
        public static final StreamCodec<ByteBuf, ArtFilesPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), ArtFilesPayload::files,
                ArtFilesPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 要那个游戏 {@code art/} 里的外部图片清单（打开「选来源」时问一句）。 */
    public record RequestArtFilesPayload(String game) implements CustomPacketPayload {
        public static final Type<RequestArtFilesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_request_art_files"));
        public static final StreamCodec<ByteBuf, RequestArtFilesPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestArtFilesPayload::game,
                RequestArtFilesPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 把一张外部图片收进本项目：{@code file} = {@code art/} 里那个文件名。
     * 服务端解码 → 存成项目自己那份的像素资产（引用 {@code @游戏名/资产名}，资产名 = 文件名去后缀）。
     */
    public record ImportArtPayload(String game, String file) implements CustomPacketPayload {
        public static final Type<ImportArtPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "asset_import_art"));
        public static final StreamCodec<ByteBuf, ImportArtPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ImportArtPayload::game,
                ByteBufCodecs.STRING_UTF8, ImportArtPayload::file,
                ImportArtPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==================== 注册 ====================

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PackListPayload.TYPE, PackListPayload.CODEC);
        registrar.playToClient(PackContentPayload.TYPE, PackContentPayload.CODEC);
        registrar.playToClient(AssetPixelsPayload.TYPE, AssetPixelsPayload.CODEC);
        registrar.playToServer(RequestPacksPayload.TYPE, RequestPacksPayload.CODEC, ServerHandler::handleRequestPacks);
        registrar.playToServer(OpenPackPayload.TYPE, OpenPackPayload.CODEC, ServerHandler::handleOpenPack);
        registrar.playToServer(RequestAssetPixelsPayload.TYPE, RequestAssetPixelsPayload.CODEC, ServerHandler::handleRequestPixels);
        registrar.playToServer(PackFromBoardPayload.TYPE, PackFromBoardPayload.CODEC, ServerHandler::handlePackFromBoard);
        registrar.playToClient(BoardsPayload.TYPE, BoardsPayload.CODEC);
        registrar.playToServer(CreatePackPayload.TYPE, CreatePackPayload.CODEC, ServerHandler::handleCreatePack);
        registrar.playToServer(RenameAssetPayload.TYPE, RenameAssetPayload.CODEC, ServerHandler::handleRename);
        registrar.playToServer(RequestBoardsPayload.TYPE, RequestBoardsPayload.CODEC, ServerHandler::handleRequestBoards);
        registrar.playToClient(BlueprintsPayload.TYPE, BlueprintsPayload.CODEC);
        registrar.playToClient(BlueprintDataPayload.TYPE, BlueprintDataPayload.CODEC);
        registrar.playToServer(RequestBlueprintsPayload.TYPE, RequestBlueprintsPayload.CODEC, ServerHandler::handleRequestBlueprints);
        registrar.playToServer(PackBlueprintPayload.TYPE, PackBlueprintPayload.CODEC, ServerHandler::handlePackBlueprint);
        registrar.playToServer(RequestBlueprintDataPayload.TYPE, RequestBlueprintDataPayload.CODEC, ServerHandler::handleRequestBlueprintData);
        registrar.playToServer(PackItemPayload.TYPE, PackItemPayload.CODEC, ServerHandler::handlePackItem);
        registrar.playToServer(SetItemLorePayload.TYPE, SetItemLorePayload.CODEC, ServerHandler::handleSetItemLore);
        registrar.playToServer(DeletePackPayload.TYPE, DeletePackPayload.CODEC, ServerHandler::handleDeletePack);
        registrar.playToServer(RenamePackPayload.TYPE, RenamePackPayload.CODEC, ServerHandler::handleRenamePack);
        registrar.playToServer(ImportAssetPayload.TYPE, ImportAssetPayload.CODEC, ServerHandler::handleImport);
        registrar.playToServer(ImportPackPayload.TYPE, ImportPackPayload.CODEC, ServerHandler::handleImportPack);
        registrar.playToServer(DeleteAssetPayload.TYPE, DeleteAssetPayload.CODEC, ServerHandler::handleDeleteAsset);
        registrar.playToClient(ArtFilesPayload.TYPE, ArtFilesPayload.CODEC);
        registrar.playToServer(RequestArtFilesPayload.TYPE, RequestArtFilesPayload.CODEC, ServerHandler::handleRequestArtFiles);
        registrar.playToServer(ImportArtPayload.TYPE, ImportArtPayload.CODEC, ServerHandler::handleImportArt);
    }

    // ==================== 服务端处理器（参数搬运，逻辑在 GameManager） ====================

    static final class ServerHandler {
        static void handleRequestPacks(RequestPacksPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendPackList(sp);
            }
        }

        static void handleOpenPack(OpenPackPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendPackContent(sp, p.pack());
            }
        }

        static void handleRequestPixels(RequestAssetPixelsPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendAssetPixels(sp, p.pack(), p.id());
            }
        }

        static void handleCreatePack(CreatePackPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.createPack(sp, p.name());
            }
        }

        static void handleRename(RenameAssetPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.renameAsset(sp, p.pack(), p.id(), p.name());
            }
        }

        static void handleRequestBoards(RequestBoardsPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendBoards(sp);
            }
        }

        static void handlePackItem(PackItemPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.packItem(sp, p.kind(), p.base(), p.pack(), p.name(), p.lore());
            }
        }

        static void handleSetItemLore(SetItemLorePayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.setItemLore(sp, p.pack(), p.id(), p.lore());
            }
        }

        static void handleDeletePack(DeletePackPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.deletePack(sp, p.pack());
            }
        }

        static void handleRenamePack(RenamePackPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.renamePack(sp, p.pack(), p.name());
            }
        }

        static void handleRequestArtFiles(RequestArtFilesPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendArtFiles(sp, p.game());
            }
        }

        static void handleImportArt(ImportArtPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.importArt(sp, p.game(), p.file());
            }
        }

        static void handleDeleteAsset(DeleteAssetPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.deleteAsset(sp, p.pack(), p.id());
            }
        }

        static void handleImportPack(ImportPackPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.importPack(sp, p.game(), p.pack());
            }
        }

        static void handleImport(ImportAssetPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.importAsset(sp, p.game(), p.pack(), p.id());
            }
        }

        static void handleRequestBlueprints(RequestBlueprintsPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendBlueprintKeys(sp);
            }
        }

        static void handlePackBlueprint(PackBlueprintPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.packBlueprint(sp, p.key(), p.pack(), p.id());
            }
        }

        static void handleRequestBlueprintData(RequestBlueprintDataPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.sendBlueprintData(sp, p.pack(), p.id());
            }
        }

        static void handlePackFromBoard(PackFromBoardPayload p, IPayloadContext ctx) {
            GameManager gm = GameManager.get();
            if (ctx.player() instanceof ServerPlayer sp && gm != null) {
                gm.packFromBoard(sp, p.boardKey(), p.pack(), p.name(), p.kind());
            }
        }
    }
}
