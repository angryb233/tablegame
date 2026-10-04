package com.tablegame.editor.pack;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.ScrollBar;
import com.tablegame.editor.item.ItemEditScreen;
import com.tablegame.net.AssetPackets;

    /**
     * 编辑器「组件」页 —— 游戏项目自己那份组件资源。
     *
     * <p>两级左栏：第一列 = 编辑器左栏，第二列 = 组件自己的页签 组件库 / 卡牌 / 模型。
     * 「组件库」页列全局库（双击 = 一键导入整库进本项目，不建库不删库）；卡牌 / 模型页列本项目自己那份
     * （单击选中 · 双击编辑改名 · 右下「+」新增 · 右键菜单「编辑 / 删除」）。
     * 缩略图与组件库屏完全一致（共用 {@link AssetGrid}）；项目自己那份的虚拟库名写成 {@code @游戏名}，
     * 服务端按前缀分流到游戏档（{@code GameManager.projGame}），故「库里的」与「项目里的」共用同一套屏代码。
     */
public class AssetPickerScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    private static final String TAB_LIB = "组件库";
    private static final String TAB_CARDS = "卡牌";
    private static final String TAB_MODELS = "模型";
    /** 这页只留美术（卡牌 / 模型）；物品 / 方块 / 实体 / 文本对象的唯一家 = 【对象】页。 */
    private static final String[] TABS = {TAB_LIB, TAB_CARDS, TAB_MODELS};

    /** 格子尺寸与组件库屏一致：卡牌 30:42（60×84）· 模型 60 见方。 */
    private static final int CARD_W = 60, CARD_H = 84, MODEL_W = 60, MODEL_H = 60, GAP = 8, NAME_H = 11;

    /** 玩法编辑器（同包：直接读 def / gameName）。 */
    final GameEditorScreen parent;

    private String tab = TAB_LIB;
    /** 全局库里有哪些库（「组件库」页；双击 = 导入整库）。 */
    private List<String> packs = List.of();
    /** 「组件库」页里选中的库。 */
    private String selectedPack = "";
    /** 「新增」的来源列表（卡牌 = 画板项目 · 模型 = 蓝图）+ 是否在「选来源」子视图。 */
    private List<String> boards = List.of();
    private boolean pickingSource;
    /** 网格里选中的项（{@code 虚拟库/id}）。 */
    private String selected = "";
    /** 网格第一格下标（滚轮按行跳）。 */
    private int top;
    /** 网格滚动条（单位 = 一个物品）。 */
    private final ListScroll gridScroll = new ListScroll(1);
    private String status = "";

    private final AssetGrid grid = new AssetGrid();
    private final AssetMenu menu = new AssetMenu();
    private final ListScroll scrollRows = new ListScroll(8);
    private boolean askedPacks;

    public AssetPickerScreen(GameEditorScreen parent) {
        super(Component.literal("组件"));
        this.parent = parent;
    }

    // ==================== 回执入口 ====================

    /** 全局库列表到了。 */
    public void applyPacks(List<String> p) {
        this.packs = p == null ? List.of() : p;
        rebuild();
    }

    /** 来源列表到了（画板项目 / 蓝图两用）。 */
    public void applyBoards(List<String> keys) {
        this.boards = keys == null ? List.of() : keys;
        rebuild();
    }

    /** {@code art/} 里的外部图片文件名：选来源时与画板项目并列，点一条 = 服务端解码后收进本项目。 */
    private List<String> artFiles = List.of();

    /** art/ 里的图片清单到了。 */
    public void applyArtFiles(List<String> files) {
        this.artFiles = files == null ? List.of() : files;
        rebuild();
    }

    public void applyPixels(String pack, String id, int w, int h, byte[] argb) {
        grid.onPixels(pack, id, w, h, argb);
        rebuild();
    }

    public void applyBlueprintData(String pack, String id, String json) {
        grid.onBlueprintData(pack, id, json);
        rebuild();
    }

    // ==================== 数据源（本项目自己那份） ====================

    /**
     * 项目自己那份的**虚拟库名** = {@code @游戏名}。
     * 服务端认这个前缀：读像素 / 读蓝图 / 改名 / 删除 / 新增 全落在游戏档里，与全局库脱钩。
     */
    private String projPack() {
        return "@" + parent.gameName();
    }

    /** 本项目自己的组件资源 → 网格条目（id 用显示名：档里那条没有单独的 id）。 */
    private List<AssetPackets.Entry> projectEntries() {
        List<AssetPackets.Entry> out = new ArrayList<>();
        List<GameDefinition.AssetDef> as = parent.def.assets();
        if (as != null) {
            for (GameDefinition.AssetDef a : as) {
                out.add(new AssetPackets.Entry(projPack(), a.name(), a.kind(), a.name(), a.w(), a.h(),
                        a.base(), a.lore()));
            }
        }
        return out;
    }

    /** 本项目里这一类（卡牌页兼容老值「美术资源」与缺 kind 的）。 */
    private List<AssetPackets.Entry> filtered() {
        String k = switch (tab) {
            case TAB_MODELS -> AssetStore.KIND_MODEL;
            default -> AssetStore.KIND_CARD;
        };
        return projectEntries().stream().filter(e -> {
            String kk = e.kind() == null ? "" : e.kind();           // 档里 kind 缺省 = 当卡牌（老档兼容）
            return switch (tab) {
                case TAB_CARDS -> kk.equals(AssetStore.KIND_CARD) || kk.equals(AssetStore.KIND_ART) || kk.isEmpty();
                default -> kk.equals(k);
            };
        }).toList();
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        clearWidgets();
        if (!askedPacks) {
            askedPacks = true;
            ClientPacketDistributor.sendToServer(new AssetPackets.RequestPacksPayload());
        }
        // 第一列：编辑器左栏（当前页「组件」高亮）；第二列：组件自己的四页签
        EditorRail.build(parent, "组件", this::addRenderableWidget);
        EditorRail.buildGenericAt(EditorRail.LEFT, TABS, tab, t -> {
            tab = t;
            selected = "";
            top = 0;
            pickingSource = false;
            clearWidgets();
            init();
        }, this::addRenderableWidget);

        if (pickingSource) {
            buildSourceList();
        } else if (tab.equals(TAB_LIB)) {
            buildPacks();
        } else {
            buildGrid();                                   // 卡牌 / 模型 / **物品** 都是缩略图网格
        }
    }

    /** 「组件库」页：全局库列表（带条数）+ 提示「双击 = 导入整库」。 */
    private void buildPacks() {
        int x = EditorRail.LEFT2;
        int w = contentW();
        int y = 44;
        scrollRows.setTotal(packs.size());
        for (int i = 0; i < scrollRows.rows(); i++) {
            String pk = packs.get(scrollRows.index(i));
            addRenderableWidget(new Row(x, y, w, ColorText.mask(pk), pk.equals(selectedPack),
                    () -> selectedPack = pk, () -> doImportPack(pk)));
            y += 22;
        }
        if (packs.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y, w, 20, "（全局库是空的 —— 去总览页的「组件库」新建/打包）", null));
        }
    }

    /** 卡牌 / 模型页：**本项目自己那份**的缩略图网格 + 右下「+」新增。 */
    private void buildGrid() {
        int x0 = EditorRail.LEFT2;
        int cw = cellW(), ch = cellH();
        int availW = Math.max(cw, width - x0 - 12);
        int cols = Math.max(1, (availW + GAP) / (cw + GAP));
        int visible = Math.max(0, cols * gridRows() - 1);      // 最后一格留给「+」
        List<AssetPackets.Entry> shown = filtered();
        top = Math.max(0, Math.min(Math.max(0, shown.size() - visible), top));

        grid.place(shown, tab.equals(TAB_MODELS), top, visible, x0, gridTop(), cw, ch, cols,
                selected, e -> selected = e.pack() + "/" + e.id(),
                this::edit,                                   // 双击 = 编辑（物品 = 编辑页；其余 = 命名小窗）
                this::openCellMenu,                           // 右键 = 菜单（编辑 / 删除）
                this::addRenderableWidget);

        // 「+」新增格：放在已有缩略图的下一个位置
        int plusIndex = Math.min(visible, shown.size() - top);
        int px = x0 + (plusIndex % cols) * (cw + GAP);
        int py = gridTop() + (plusIndex / cols) * (ch + NAME_H + GAP);
        addRenderableWidget(new AssetGrid.AddCell(px, py, cw, ch, this::pickSource));

        if (shown.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x0, gridTop(), Math.min(availW, 320), 20,
                    "（本项目这一类还是空的 —— 点右下「+」从画板/蓝图加一条，或去「组件库」页导入整库）",
                    null));
        }
    }

    /** 「选来源」子视图：卡牌页列画板项目 + {@code art/} 里的外部图片、模型页列蓝图；点一个 = 直接加进本项目。 */
    private void buildSourceList() {
        int x = EditorRail.LEFT2;
        int w = contentW();
        int y = 44;
        // 两张表拼成一条清单：画板项目（模型页 = 蓝图）在前，art/ 里的 png 挂在尾部。
        // 判据用后缀 —— 画板 key 一定含「/」、art 文件名一定以 .png 收尾，两者不会撞。
        List<String> items = new ArrayList<>(boards);
        boolean withArt = !tab.equals(TAB_MODELS);            // 模型页的来源是蓝图，一张 png 顶不了它
        if (withArt) {
            items.addAll(artFiles);
        }
        scrollRows.setTotal(items.size());
        for (int i = 0; i < scrollRows.rows(); i++) {
            String key = items.get(scrollRows.index(i));
            String label = key.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "🖼 art/" + key : key;
            addRenderableWidget(new Row(x, y, w, label, false, () -> addFrom(key), null));
            y += 22;
        }
        if (items.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y, w, 20,
                    tab.equals(TAB_MODELS) ? "（没有可用的蓝图 —— 先去模型作坊存一个）"
                            : "（没有可用的画板项目 —— 先画一张；外部图片放进 art/ 文件夹也会列在这儿）", null));
        } else if (withArt && !artFiles.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(x, y, w,
                    "🖼 = art 文件夹里的图片（点它 = 服务端解码后收进本项目）", 0xFF909090));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, w, 22, "取消（回网格）", () -> {
            pickingSource = false;
            rebuild();
        }));
    }

    private int contentW() {
        return Math.max(80, width - EditorRail.LEFT2 - 12);
    }

    private boolean isTabModels() {
        return tab.equals(TAB_MODELS);
    }

    private int cellW() {
        return isTabModels() ? MODEL_W : CARD_W;
    }

    private int cellH() {
        return isTabModels() ? MODEL_H : CARD_H;
    }

    /** 同步滚动条的「一屏几格 / 总共几格 / 现在第几格」（网格：一格 = 一个物品）。 */
    private void syncGridScroll() {
        int cw = cellW();
        int cols = Math.max(1, (Math.max(cw, width - EditorRail.LEFT2 - 12) + GAP) / (cw + GAP));
        gridScroll.setRows(Math.max(1, cols * gridRows() - 1));
        gridScroll.setTotal(filtered().size());
        gridScroll.setTop(top);
    }

    /** 滚动条轨道的下端（到列表底栏上面一点）。 */
    private int gridBarH() {
        return Math.max(ScrollBar.MIN_H, height - gridTop() - 96);
    }

    /** 点在滚动条上 / 拖滑块：把 top 换成滑块位置再重建（网格单位 = 一个物品）。 */
    private boolean gridBarDrag(double mx, double my) {
        syncGridScroll();
        if (!ScrollBar.hit(mx, my, width - 12, gridTop(), gridBarH(), gridScroll)) return false;
        if (gridScroll.dragTo(gridTop(), gridBarH(), ScrollBar.MIN_H, my)) {
            top = gridScroll.top();
            rebuild();
        }
        return true;
    }
    private int gridTop() {
        return 42;
    }

    private int gridRows() {
        int ch = cellH();
        int usable = Math.max(ch, height - gridTop() - 96);
        return Math.max(1, (usable + NAME_H + GAP) / (ch + NAME_H + GAP));
    }

    // ==================== 动作 ====================

    private void rebuild() {
        clearWidgets();
        init();
    }

    /** 双击库行 = **一键导入整个库进本项目**（= 在项目下生成自己那份组件资源）。 */
    private void doImportPack(String pack) {
        selectedPack = pack;
        ClientPacketDistributor.sendToServer(new AssetPackets.ImportPackPayload(parent.gameName(), pack));
        status = "已发送导入库「" + pack + "」…（聊天栏有回执）";
        rebuild();
    }

    /**
     * 双击格子 / 菜单「编辑」= **改名**（改的是本项目自己那份那条）。
     * 服务端改完会把最新定义推回客户端，本屏据此重建。
     */
    private void rename(AssetPackets.Entry e) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "给" + tab + "命名",
                "显示名（可用 & 上色，如 &c红桃A）", e.name(), newName -> {
                    if (!newName.isBlank()) {
                        ClientPacketDistributor.sendToServer(
                                new AssetPackets.RenameAssetPayload(e.pack(), e.id(), newName));
                    }
                }));
    }

    /**
     * 右键一条 → 菜单：模型类多一个「预览」（整屏 3D 大图，可拖拽旋转）；
     * 物品类的「编辑」= 进 {@link ItemEditScreen}（名字 + 描述一页改）；其余 = 编辑（改名）/ 删除。
     */
    private void openCellMenu(AssetPackets.Entry e) {
        boolean item = e.base() != null && !e.base().isEmpty();     // 物品类：名字与描述在同一页改
        boolean model = tab.equals(TAB_MODELS);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (model) {
            labels.add("预览");
            acts.add(() -> Minecraft.getInstance().setScreen(new AssetPreviewScreen(this,
                    e.pack(), e.id(), e.name(), grid.rsCache(), grid.rsFit(),
                    AssetGrid.rsYaw, AssetGrid.rsPitch, AssetGrid.rsZoom, grid.rsWorldH())));
        }
        labels.add(item ? "编辑" : "编辑（改名）");
        acts.add(() -> edit(e));
        labels.add("删除");
        acts.add(() -> {
            status = "已从本项目删掉：" + e.name();
            ClientPacketDistributor.sendToServer(new AssetPackets.DeleteAssetPayload(e.pack(), e.id()));
            rebuild();
        });
        menu.open(width, height, grid.rightX(), grid.rightY(), labels, acts);
    }


    /**
     * 双击格子 / 菜单「编辑」= 物品进编辑页（显示名 + 描述一页改完，与组件库屏共用同一份屏代码）；
     * 其余种类仍是命名小窗。
     */
    private void edit(AssetPackets.Entry e) {
        if (e.base() != null && !e.base().isEmpty()) {
            Minecraft.getInstance().setScreen(new ItemEditScreen(this, e.pack(), e.id(), e.base(), e.name(), e.lore(),
                    null));
            return;
        }
        rename(e);
    }

    /** 点「+」= 去选来源（卡牌页 = 画板项目 + {@code art/} 里的外部图片 · 模型页 = 蓝图）。 */
    private void pickSource() {
        pickingSource = true;
        if (tab.equals(TAB_MODELS)) {
            ClientPacketDistributor.sendToServer(new AssetPackets.RequestBlueprintsPayload());
        } else {
            ClientPacketDistributor.sendToServer(new AssetPackets.RequestBoardsPayload());
            // art/ 里的外部图片清单：与画板项目并列，点一条 = 服务端解码后收进本项目
            ClientPacketDistributor.sendToServer(new AssetPackets.RequestArtFilesPayload(parent.gameName()));
        }
        rebuild();
    }

    /** 在来源列表里点了一个 → **直接加进本项目自己那份**（不必为一条美术建库）。 */
    private void addFrom(String key) {
        if (key.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {   // art/ 里的外部图片：交给服务端解码
            ClientPacketDistributor.sendToServer(new AssetPackets.ImportArtPayload(parent.gameName(), key));
            status = "已导入 " + key + "（服务端解码，聊天栏有回执）";
            pickingSource = false;
            rebuild();
            return;
        }
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        if (tab.equals(TAB_MODELS)) {
            ClientPacketDistributor.sendToServer(new AssetPackets.PackBlueprintPayload(key, projPack(), name));
        } else {
            ClientPacketDistributor.sendToServer(
                    new AssetPackets.PackFromBoardPayload(key, projPack(), name, AssetStore.KIND_CARD));
        }
        status = "已加进本项目：" + name + "（聊天栏有回执）";
        pickingSource = false;
        rebuild();
    }

    /** 菜单开着就吃掉这一下（点菜单项 = 干那一项；点别处 = 关掉）。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (gridBarDrag(event.x(), event.y())) return true;
        if (menu.click(event.x(), event.y())) {
            return true;
        }
        if (super.mouseClicked(event, doubled)) {              // 搜索框等控件优先
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;                              // 菜单开着：先滚菜单（含到头也吃掉）
        }
        if (pickingSource || tab.equals(TAB_LIB)) {
            if (scrollRows.scroll(dy)) {
                rebuild();
                return true;
            }
            return false;
        }
        if (tab.equals(TAB_CARDS) || tab.equals(TAB_MODELS)) {
            int cw = cellW();
            int availW = Math.max(cw, width - EditorRail.LEFT2 - 12);
            int cols = Math.max(1, (availW + GAP) / (cw + GAP));
            int visible = Math.max(0, cols * gridRows() - 1);
            int next = Math.max(0, Math.min(Math.max(0, filtered().size() - visible),
                    top - (int) Math.signum(dy) * cols));
            if (next != top) {
                top = next;
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        syncGridScroll();
        ScrollBar.draw(g, width - 12, gridTop(), gridBarH(), gridScroll, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        // 第二列底板：从第一列右侧开始（两者之间留一条亮分割线）
        g.fill(EditorRail.W + 10, 0, EditorRail.LEFT2 - 8, height, 0xFF14161C);
        g.fill(EditorRail.W + 9, 0, EditorRail.W + 10, height, 0xFF5A6070);          // ← 分割线（2px 亮）
        g.fill(EditorRail.W + 10, 0, EditorRail.W + 11, height, 0xFF5A6070);
        g.fill(EditorRail.LEFT2 - 8, 0, EditorRail.LEFT2 - 7, height, 0xFF3A3E4A);   // 第二列右边界

        int cx = (EditorRail.LEFT2 + width) / 2;
        String head = tab.equals(TAB_LIB)
                ? "全局库 " + packs.size() + " 个（双击 = 导入整库进本项目）"
                : "本项目自己的" + tab + " " + filtered().size() + " 条";
        g.centeredText(font, Component.literal(tab + "（" + head + "）"), cx, 30, 0xFFFFFFFF);
        String hint = switch (tab) {
            case TAB_LIB -> "单击选中 · 双击 = 一键导入整个库进本项目（= 在项目下生成自己那份）";
            default -> "单击选中 · 双击 = 编辑（改名）· 右键 = 编辑 / 删除 · 右下「+」= 新增"
                    + (tab.equals(TAB_MODELS) ? " · 按住模型格拖动 = 旋转预览" : "");
        };
        g.text(font, Component.literal(hint), EditorRail.LEFT2, height - 62, 0xFF909090);
        g.text(font, Component.literal(pickingSource
                        ? "选一个来源（单击即加进本项目；不必为一条美术建库）"
                        : "导入即拷贝：库以后再改不影响这个项目；项目里的这份也不会反过来改库"),
                EditorRail.LEFT2, height - 50, 0xFF909090);
        if (!status.isEmpty()) {
            g.text(font, Component.literal(ColorText.mask(status)), EditorRail.LEFT2, height - 34, 0xFFE0E080);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);                  // 菜单画在最上层
    }

    /** 一行：单击 / 双击（样式照编辑器左栏页签）。 */
    private static class Row extends AbstractWidget {
        private final Runnable onSingle;
        private final Runnable onDouble;

        Row(int x, int y, int w, String label, boolean active, Runnable onSingle, Runnable onDouble) {
            super(x, y, w, 20, Component.literal(label));
            this.onSingle = onSingle;
            this.onDouble = onDouble;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            int bg = isHovered() ? 0xFF2E3138 : 0xFF21242B;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            g.text(font, getMessage().getString(), getX() + 8, getY() + (getHeight() - 9) / 2, 0xFFC8C8D0);
            g.outline(getX(), getY(), getWidth(), getHeight(), 0xFF3A3E4A);
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (doubled && onDouble != null) {
                onDouble.run();
            } else if (onSingle != null) {
                onSingle.run();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    // ===== 菜单检索/拖动转发（AssetMenu）=====

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menu.type(event.codepointAsString())) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) {
            return true;                              // 菜单检索行在退格
        }
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) {
            return true;                              // 菜单开着：回车不落到底下的控件
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (gridBarDrag(event.x(), event.y())) return true;
        if (menu.drag(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (menu.release(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }
}
