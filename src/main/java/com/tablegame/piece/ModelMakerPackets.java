package com.tablegame.piece;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardManager;
import com.tablegame.drawboard.BoardStore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 模型制作器网络层 —— 两个 C2S 包，模式同 BlueprintPackets：
 *
 * <ul>
 *   <li>{@link SetScalePayload}：点规格按钮（1/2、1/4、1/8 = 内部值 2/4/8）。只发按钮值，
 *       服务端校验合法后写进 BE（持久化）。</li>
 *   <li>{@link CraftPayload}：点「制作」。服务端全量重验：方块还在？蓝图已锁定？粘土够 1 个？
 *       → 扣粘土、把 PieceData 原样拷到新棋子、产出进背包（满则掉在玩家脚下）。</li>
 * </ul>
 *
 * <p>GUI 操作要发包：按钮点击是客户端事件，服务端看不到。客户端只表达意图、服务端裁决 ——
 * 每个包都带方块坐标，服务端按坐标重取 BE、重验所有条件，伪造包拿不到便宜。
 * 槽位拖放不走这条通道（原版容器协议自带防刷）。
 */
public final class ModelMakerPackets {
    private ModelMakerPackets() {}

    // ===== 包 1：设置比例 =====

    public record SetScalePayload(BlockPos pos, int scale) implements CustomPacketPayload {
        public static final Type<SetScalePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "model_maker_set_scale"));
        public static final StreamCodec<ByteBuf, SetScalePayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetScalePayload::pos,
                ByteBufCodecs.VAR_INT, SetScalePayload::scale,
                SetScalePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 包 2：制作 =====

    public record CraftPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<CraftPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "model_maker_craft"));
        public static final StreamCodec<ByteBuf, CraftPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, CraftPayload::pos,
                CraftPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 包 3~7：蓝图库（保存/导出/导入） =====

    /** S2C：我的组名列表（单人组第一，BoardManager.myGroupNames）。保存浮层的选组按钮数据源。 */
    public record MyGroupsPayload(List<String> groups) implements CustomPacketPayload {
        public static final Type<MyGroupsPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_my_groups"));
        public static final StreamCodec<ByteBuf, MyGroupsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MyGroupsPayload::groups,
                MyGroupsPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：导入候选列表（条目 = 名字 + 来源标注；组过滤在服务端做完才发）。 */
    public record LibraryListPayload(List<String> names, List<String> sources) implements CustomPacketPayload {
        public static final Type<LibraryListPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_list"));
        public static final StreamCodec<ByteBuf, LibraryListPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), LibraryListPayload::names,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), LibraryListPayload::sources,
                LibraryListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：请求我的组列表（保存浮层 init 时发）。 */
    public record RequestMyGroupsPayload() implements CustomPacketPayload {
        public static final RequestMyGroupsPayload INSTANCE = new RequestMyGroupsPayload();
        public static final Type<RequestMyGroupsPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_req_groups"));
        public static final StreamCodec<ByteBuf, RequestMyGroupsPayload> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：请求导入候选列表（导入浮层 init 时发）。 */
    public record RequestLibraryPayload() implements CustomPacketPayload {
        public static final RequestLibraryPayload INSTANCE = new RequestLibraryPayload();
        public static final Type<RequestLibraryPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_req_list"));
        public static final StreamCodec<ByteBuf, RequestLibraryPayload> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：保存蓝图（带选组与名字）。权限 = 该组可写（只读成员拒绝）。 */
    public record SaveBlueprintPayload(String group, String name) implements CustomPacketPayload {
        public static final Type<SaveBlueprintPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_save"));
        public static final StreamCodec<ByteBuf, SaveBlueprintPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SaveBlueprintPayload::group,
                ByteBufCodecs.STRING_UTF8, SaveBlueprintPayload::name,
                SaveBlueprintPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：导出蓝图（槽里已锁定的蓝图 → export/pieces/<名>.json）。权限 = 组员即可。 */
    public record ExportBlueprintPayload() implements CustomPacketPayload {
        public static final ExportBlueprintPayload INSTANCE = new ExportBlueprintPayload();
        public static final Type<ExportBlueprintPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_export"));
        public static final StreamCodec<ByteBuf, ExportBlueprintPayload> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：导入蓝图（fromExport=true 选的是导出目录那条；name = 条目名）。 */
    public record ImportBlueprintPayload(boolean fromExport, String name) implements CustomPacketPayload {
        public static final Type<ImportBlueprintPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_lib_import"));
        public static final StreamCodec<ByteBuf, ImportBlueprintPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ImportBlueprintPayload::fromExport,
                ByteBufCodecs.STRING_UTF8, ImportBlueprintPayload::name,
                ImportBlueprintPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 蓝图库菜单（/tablegame pieces：查看/删除/打开文件夹） =====

    /** C2S：请求蓝图库列表（组过滤服务端做，数据源同 RequestLibraryPayload）。 */
    public record RequestBrowserPayload() implements CustomPacketPayload {
        public static final RequestBrowserPayload INSTANCE = new RequestBrowserPayload();
        public static final Type<RequestBrowserPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_browser_req"));
        public static final StreamCodec<ByteBuf, RequestBrowserPayload> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：删除库里的一条蓝图。group 空 = 导出目录。权限服务端判（canWrite）。 */
    public record BrowserDeletePayload(String group, String name) implements CustomPacketPayload {
        public static final Type<BrowserDeletePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_browser_delete"));
        public static final StreamCodec<ByteBuf, BrowserDeletePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BrowserDeletePayload::group,
                ByteBufCodecs.STRING_UTF8, BrowserDeletePayload::name,
                BrowserDeletePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：库列表 + 环境标记（dedicated=true 专用服，客户端隐藏「打开文件夹」）。 */
    public record BrowserListPayload(List<String> names, List<String> sources, boolean dedicated) implements CustomPacketPayload {
        public static final Type<BrowserListPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(TableGame.MODID, "piece_browser_list"));
        public static final StreamCodec<ByteBuf, BrowserListPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BrowserListPayload::names,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BrowserListPayload::sources,
                ByteBufCodecs.BOOL, BrowserListPayload::dedicated,
                BrowserListPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ===== 注册（与 BlueprintPackets 同模式） =====

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SetScalePayload.TYPE, SetScalePayload.CODEC, ModelMakerPackets::handleSetScale);
        registrar.playToServer(CraftPayload.TYPE, CraftPayload.CODEC, ModelMakerPackets::handleCraft);
        // 蓝图库
        registrar.playToServer(RequestMyGroupsPayload.TYPE, RequestMyGroupsPayload.CODEC,
                ModelMakerPackets::handleRequestMyGroups);
        registrar.playToServer(RequestLibraryPayload.TYPE, RequestLibraryPayload.CODEC,
                ModelMakerPackets::handleRequestLibrary);
        registrar.playToServer(SaveBlueprintPayload.TYPE, SaveBlueprintPayload.CODEC,
                ModelMakerPackets::handleSave);
        registrar.playToServer(ExportBlueprintPayload.TYPE, ExportBlueprintPayload.CODEC,
                ModelMakerPackets::handleExport);
        registrar.playToServer(ImportBlueprintPayload.TYPE, ImportBlueprintPayload.CODEC,
                ModelMakerPackets::handleImport);
        // 蓝图库菜单（/tablegame pieces）
        registrar.playToServer(RequestBrowserPayload.TYPE, RequestBrowserPayload.CODEC,
                ModelMakerPackets::handleRequestBrowser);
        registrar.playToServer(BrowserDeletePayload.TYPE, BrowserDeletePayload.CODEC,
                ModelMakerPackets::handleBrowserDelete);
        // S2C：handler 在客户端注册（ModelMakerClient，与专用服务器 classpath 隔离）
        registrar.playToClient(MyGroupsPayload.TYPE, MyGroupsPayload.CODEC);
        registrar.playToClient(LibraryListPayload.TYPE, LibraryListPayload.CODEC);
        registrar.playToClient(BrowserListPayload.TYPE, BrowserListPayload.CODEC);
    }

    // ===== 蓝图库服务端处理 =====

    /** 蓝图库存取器（挂在 BoardManager 上，服务端单例随开服初始化；未开服 = null 由调用方兜底）。 */
    private static PieceLibrary lib() {
        BoardManager bm = BoardManager.get();
        return bm == null ? null : bm.pieceLibrary();
    }

    private static void say(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s));
    }

    private static void overlay(ServerPlayer p, String s) {
        p.sendOverlayMessage(Component.literal(s));
    }

    /** 名字清洗：长度裁剪 + safeName（防路径穿越——../ 与分隔符全变 _）。 */
    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() > BlueprintNameScreen.MAX_NAME) {
            name = name.substring(0, BlueprintNameScreen.MAX_NAME);
        }
        name = BoardStore.safeName(name);
        return name;
    }

    /**
     * 蓝图槽守卫（保存/导出/导入共用）：菜单还开着（BE 上下文有效）、方块还在、
     * 槽里是已锁定的蓝图 → 返回蓝图 ItemStack，否则发提示返回 null。
     */
    private static ItemStack lockedBlueprintOf(ServerPlayer sp, ModelMakerBlockEntity be) {
        if (be.isRemoved()) {
            return null;
        }
        ItemStack bp = be.getBlueprint();
        PieceData data = bp.isEmpty() ? null : PieceData.get(bp);
        if (data == null || !data.isLocked()) {
            overlay(sp, "[蓝图库] 槽里需要一个已捕获锁定的蓝图");
            return null;
        }
        return bp;
    }

    private static void handleRequestMyGroups(RequestMyGroupsPayload payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp && BoardManager.get() != null) {
            PacketDistributor.sendToPlayer(sp,
                    new MyGroupsPayload(BoardManager.get().myGroupNames(sp)));
        }
    }

    // ===== 蓝图库菜单（/tablegame pieces）服务端处理 =====

    /** 发库列表给玩家（C2S 请求与 /tablegame pieces 指令共用）。 */
    public static void requestBrowserList(ServerPlayer sp) {
        if (BoardManager.get() == null) {
            return;
        }
        List<PieceLibrary.Entry> entries = lib().listFor(BoardManager.get().myGroupNames(sp));
        List<String> names = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (PieceLibrary.Entry e : entries) {
            names.add(e.name());
            sources.add(e.source());
        }
        PacketDistributor.sendToPlayer(sp, new BrowserListPayload(names, sources,
                sp.level().getServer().isDedicatedServer()));
    }

    /** 库列表 = 我的组库 + 导出目录（数据源同导入列表）；dedicated 标记随包下发。 */
    private static void handleRequestBrowser(RequestBrowserPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        requestBrowserList(sp);
    }

    /**
     * 删除一条蓝图。权限 = 沿用画板 canWrite：单人组恒可写，真实组要求可写成员
     * （只读成员拒删），导出目录无组概念谁都能删。删后重发列表（快照刷新惯例）。
     */
    private static void handleBrowserDelete(BrowserDeletePayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || BoardManager.get() == null) {
            return;
        }
        String name = cleanName(payload.name());
        if (name.isEmpty()) {
            say(sp, "[蓝图库] 删除名不合法");
            return;
        }
        boolean ok;
        if (payload.group() == null || payload.group().isBlank()) {
            ok = lib().deleteExport(name);
        } else {
            String group = cleanName(payload.group());
            if (!BoardManager.get().myGroupNames(sp).contains(group)) {
                say(sp, "[蓝图库] 你不在组「" + group + "」中，不能删它的蓝图");
                return;
            }
            if (!BoardManager.get().canWrite(group, sp.getName().getString())) {
                say(sp, "[蓝图库] 你在组「" + group + "」内为只读，不能删除蓝图");
                return;
            }
            ok = lib().delete(group, name);
        }
        say(sp, ok ? "[蓝图库] 已删除: " + name : "[蓝图库] 删除失败（文件不存在或被占用）: " + name);
        handleRequestBrowser(new RequestBrowserPayload(), ctx);   // 删后无条件重发列表
    }

    private static void handleRequestLibrary(RequestLibraryPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || BoardManager.get() == null) {
            return;
        }
        // 组过滤在服务端做：只扫我所在组的库目录，别人私有组的文件名不进列表
        List<PieceLibrary.Entry> entries = lib().listFor(BoardManager.get().myGroupNames(sp));
        List<String> names = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (PieceLibrary.Entry e : entries) {
            names.add(e.name());
            sources.add(e.source());
        }
        PacketDistributor.sendToPlayer(sp, new LibraryListPayload(names, sources));
    }

    private static void handleSave(SaveBlueprintPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        // 目标组：空 = 我的单人组；必须是我所在组（伪造包塞别人的组直接拒）
        BoardManager bm = BoardManager.get();
        if (bm == null) return;
        String group = payload.group() == null || payload.group().isBlank()
                ? BoardManager.defaultGroup(sp) : payload.group();
        if (!bm.myGroupNames(sp).contains(group)) {
            say(sp, "[蓝图库] 你不在组「" + group + "」中，不能存入它的蓝图库");
            return;
        }
        // 权限：可写组员才行（只读成员不行）——沿用画板 canWrite 规则
        if (!bm.canWrite(group, sp.getName().getString())) {
            say(sp, "[蓝图库] 你在组「" + group + "」内为只读，不能保存蓝图");
            return;
        }
        // 名字：清洗（防穿越）；空 = 用蓝图现有名；还空 = 拒绝
        String name = cleanName(payload.name());
        if (name.isEmpty()) {
            overlay(sp, "[蓝图库] 蓝图名不能为空");
            return;
        }
        // 蓝图槽守卫：菜单开着的 BE 上必须有已锁定蓝图（payload 不带坐标——
        // 玩家正开着哪个模型制作器的菜单，就从他当前打开的菜单里拿 BE）
        ModelMakerBlockEntity be = openedMaker(sp);
        if (be == null) {
            overlay(sp, "[蓝图库] 请先打开模型制作器界面");
            return;
        }
        ItemStack bp = lockedBlueprintOf(sp, be);
        if (bp == null) {
            return;
        }
        lib().save(group, name, bp, sp.level().registryAccess());
        say(sp, "[蓝图库] 已保存到组「" + group + "」蓝图库：" + name
                + (lib().exists(group, name) ? "" : "（写入失败，见日志）"));
    }

    /**
     * 玩家当前打开的模型制作器 BE：容器菜单还开着且就是模型制作器 → 按其坐标取服务端 BE。
     * 「GUI 操作不带坐标」的包都从这里拿上下文（菜单关了/换菜单 = null，天然防伪造）。
     */
    private static ModelMakerBlockEntity openedMaker(ServerPlayer sp) {
        if (sp.containerMenu instanceof ModelMakerMenu menu) {
            BlockPos pos = menu.getWorldPos();
            if (sp.level().getBlockEntity(pos) instanceof ModelMakerBlockEntity be
                    && sp.containerMenu.stillValid(sp)) {
                return be;
            }
        }
        return null;
    }

    private static void handleExport(ExportBlueprintPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        // 权限：组员即可（只读也能导出 = 拿副本），但得真的在某个组里（永远真——单人组隐式）
        ModelMakerBlockEntity be = openedMaker(sp);
        if (be == null) {
            overlay(sp, "[蓝图库] 请先打开模型制作器界面");
            return;
        }
        ItemStack bp = lockedBlueprintOf(sp, be);
        if (bp == null) {
            return;
        }
        String name = cleanName(bp.getHoverName().getString());
        if (name.isEmpty()) {
            overlay(sp, "[蓝图库] 蓝图名不能为空");
            return;
        }
        lib().export(name, bp, sp.level().registryAccess());
        say(sp, "[蓝图库] 已导出：tablegame/export/pieces/" + name + ".json");
    }

    private static void handleImport(ImportBlueprintPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        // ⚠ 先拆「组/名」再分别清洗，不能整串清洗——safeName 会把分隔符 / 也换掉，
        // 导致后面 indexOf('/') 永远找不到
        String raw = payload.name() == null ? "" : payload.name().trim();
        if (raw.isEmpty()) {
            say(sp, "[蓝图库] 导入名不能为空");
            return;
        }
        // 导入源判定：fromExport=false 时必须带组前缀「组/名」——组必须是我所在组，
        // 防伪造包用「组/名」读别人私有组的文件
        ItemStack src;
        if (payload.fromExport()) {
            String name = cleanName(raw);
            if (name.isEmpty()) {
                say(sp, "[蓝图库] 导入名不合法");
                return;
            }
            src = lib().loadExport(name, sp.level().registryAccess());
        } else {
            int slash = raw.indexOf('/');
            if (slash <= 0 || slash == raw.length() - 1) {
                say(sp, "[蓝图库] 导入格式应为「组/蓝图名」: " + raw);
                return;
            }
            String group = cleanName(raw.substring(0, slash));
            String file = cleanName(raw.substring(slash + 1));
            if (group.isEmpty() || file.isEmpty()) {
                say(sp, "[蓝图库] 导入名不合法: " + raw);
                return;
            }
            if (!BoardManager.get().myGroupNames(sp).contains(group)) {
                say(sp, "[蓝图库] 你不在组「" + group + "」中，不能导入它的蓝图");
                return;
            }
            src = lib().load(group, file, sp.level().registryAccess());
        }
        if (src == null) {
            overlay(sp, "[蓝图库] 蓝图不存在或已损坏");
            return;
        }
        // 落点守卫：菜单开着、方块在、槽里必须是「空白蓝图」（有数据的蓝图会被覆盖 = 刷物品）
        ModelMakerBlockEntity be = openedMaker(sp);
        if (be == null) {
            overlay(sp, "[蓝图库] 请先打开模型制作器界面");
            return;
        }
        ItemStack slot = be.getBlueprint();
        if (slot.isEmpty() || !(slot.getItem() instanceof BlueprintItem) || !PieceData.get(slot).isBlank()) {
            overlay(sp, "[蓝图库] 蓝图槽里要放一张空白蓝图才能导入");
            return;
        }
        // 服务端写数据：PieceData + CUSTOM_NAME 全部从库存文件里的 stack 拷来；
        // 原版容器同步自动把改好的蓝图刷回客户端槽位
        slot.applyComponents(src.getComponentsPatch());
        be.setChanged();
        overlay(sp, "[蓝图库] 已导入：" + src.getHoverName().getString());
        sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 0.5f, 1.0f);
    }


    // ===== 服务端处理 =====

    private static void handleSetScale(SetScalePayload payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            if (sp.level().getBlockEntity(payload.pos()) instanceof ModelMakerBlockEntity be) {
                be.setScale(payload.scale()); // setScale 内部校验值合法性
            }
        }
    }

    private static void handleCraft(CraftPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) {
            return;
        }
        if (!(sp.level().getBlockEntity(payload.pos()) instanceof ModelMakerBlockEntity be)) {
            return; // 方块/BE 不在了（被挖了）→ 直接忽略
        }

        // 校验 1：蓝图槽里必须是已锁定的蓝图（空白/选角中拒绝，规格 2）
        PieceData data = be.getBlueprintData();
        if (data == null || !data.isLocked()) {
            sp.sendOverlayMessage(Component.translatable("message.tablegame.model_maker.need_blueprint"));
            return;
        }

        // 校验 2：制作器粘土槽至少 1 个粘土球（规格选了「制作器自带粘土槽」，不扫背包）
        ItemStack clay = be.getClay();
        if (clay.isEmpty() || !clay.is(Items.CLAY_BALL)) {
            sp.sendOverlayMessage(Component.translatable("message.tablegame.model_maker.need_clay"));
            return;
        }

        // 扣 1 个粘土球（BE 内部，随存档持久化）
        clay.shrink(1);
        be.setChanged();

        // 产出棋子：把蓝图的全部数据组件（PieceData + 绿名 CUSTOM_NAME）拷给棋子，
        // 再把制作器当前规格写进体素数据——scale 必须随棋子走（bug 根因修复：
        // 之前 scale 只存在制作器方块上，产出物全部一样大导致堆叠）。
        // 蓝图本体不消耗（规格）——反复可产。
        ItemStack piece = new ItemStack(TableGame.GAME_PIECE.get());
        piece.applyComponents(be.getBlueprint().getComponentsPatch());
        PieceData src = PieceData.get(be.getBlueprint());
        if (src.voxels() != null) {
            PieceData.set(piece, new PieceData(src.selection(), new PieceData.VoxelData(
                    src.voxels().sizeX(), src.voxels().sizeY(), src.voxels().sizeZ(),
                    be.getScale(), src.voxels().palette(), src.voxels().paletteIds()),
                    src.meta()));
        }

        // 进背包：先尝试 add（占用堆叠/空位），失败（满）→ 掉在玩家脚下
        if (!sp.getInventory().add(piece)) {
            sp.level().addFreshEntity(new ItemEntity(sp.level(), sp.getX(), sp.getY(), sp.getZ(), piece));
        }

        // 反馈：音效 + 名字提示（绿名 = 蓝图玩家命名；未命名 = 默认名「棋子」）
        Component name = be.getBlueprint().getHoverName();
        sp.sendOverlayMessage(Component.translatable("message.tablegame.model_maker.crafted", name));
        sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 0.5f, 1.2f);
    }
}