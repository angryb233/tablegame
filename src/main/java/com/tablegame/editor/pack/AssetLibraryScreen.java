package com.tablegame.editor.pack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.ColorText;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorMenuScreen;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.ScrollBar;
import com.tablegame.editor.item.ItemBasePicker;
import com.tablegame.editor.item.ItemEditScreen;
import com.tablegame.net.AssetPackets;

    /**
     * 组件库工作台 —— 编辑器式左栏四页签：组件库 / 卡牌 / 模型 / 物品。
     *
     * <ul>
     *   <li>「组件库」页：新建库 → 单击选中</li>
     *   <li>「卡牌」/「模型」/「物品」页：缩略图网格，最后一格「+」= 添加；
     *       单击选中 · 双击命名 · 右键 = 编辑 / 删除</li>
     * </ul>
     *
     * <p>卡牌格 60×84（=30:42）；非卡片尺寸按比例 contain 缩放，不变形。
     */
public class AssetLibraryScreen extends Screen {

    private static final String TAB_PACKS = "组件库";
    private static final String TAB_CARDS = "卡牌";
    private static final String TAB_MODELS = "模型";
    private static final String TAB_ITEMS = "物品";
    private static final String[] TABS = {TAB_PACKS, TAB_CARDS, TAB_MODELS, TAB_ITEMS};

    /**
     * 缩略格尺寸按页签定：卡牌 60×84（=30:42）；模型 60×60 方形（棋子缩略图走实体渲染快照 +
     * {@code g.entity}，size 参数 = 每格像素，给大点看细节）。
     */
    private static final int CARD_W = 60, CARD_H = 84, MODEL_W = 60, MODEL_H = 60;
    /** 物品类格子：方形小格（原版图标恒 16×16，居中），外观用原版，不做贴图。 */
    private static final int ITEM_W = 40, ITEM_H = 40;
    private static final int GAP = 8, NAME_H = 11;

    private String tab = TAB_PACKS;
    /** 当前选中的库（null = 还没选）。 */
    private String pack;
    private List<String> packs = List.of();
    /** 当前库的全部资产（按页签过滤成 {@link #shown}）。 */
    private List<AssetPackets.AssetInfo> all = List.of();
    private List<AssetPackets.AssetInfo> shown = List.of();
    /** 已经拉到（或正在拉）像素的资产 id，避免重建时重复发包。 */
    private final Set<String> requested = new HashSet<>();
    /** 缩略图网格（与编辑器「组件」页共用，两处形式必须一致）。 */
    private final AssetGrid grid = new AssetGrid();
    /** 网格第一格的下标（滚轮按行跳）。 */
    private int top;
    /** 「添加美术」的来源列表 + 是否在选来源子视图。 */
    private List<String> boards = List.of();
    private boolean pickingSource;
    /** 选中的资产 id（单击）。 */
    private String selectedId = "";
    /** 条目上的右键菜单（编辑 / 删除），与编辑器「组件」页共用。 */
    private final AssetMenu menu = new AssetMenu();
    /** 「选基底物品」视图（物品类「+」用）：可滚动 + 可搜索的原版物品清单。 */
    private final ItemBasePicker basePicker = new ItemBasePicker();
    private boolean pickingBase;
    private String status = "";

    private final ListScroll scrollRows = new ListScroll(6);   // 「组件库」页的库列表
    /** 滚动条：轨道贴行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scrollRows)) return false;
        if (scrollRows.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            rebuild();
        }
        return true;
    }

    private boolean askedPacks;

    public AssetLibraryScreen() {
        super(Component.literal("组件库"));
    }

    // ==================== 回执入口 ====================

    public void applyPacks(List<String> p) {
        this.packs = p == null ? List.of() : p;
        if (pack != null && !packs.contains(pack)) {      // 这个库被删了 / 改名了 → 别留个幽灵选中
            pack = null;
            all = List.of();
            shown = List.of();
            selectedId = "";
            refilter();
        }
        rebuild();
    }

    public void applyContent(String pack, String desc, List<AssetPackets.AssetInfo> assets) {
        this.pack = pack;
        this.all = assets == null ? List.of() : assets;
        this.pickingSource = false;
        this.top = 0;
        this.selectedId = "";
        this.requested.clear();
        this.grid.reset();
        refilter();
        rebuild();
    }

    public void applyPixels(String pack, String id, int w, int h, byte[] argb) {
        if (!pack.equals(this.pack)) {
            return;                                    // 库换了：这条像素过期
        }
        grid.onPixels(pack, id, w, h, argb);
        rebuild();                                     // 像素到了就地重画（控件少，重建最省事）
    }

    public void applyBoards(List<String> keys) {
        this.boards = keys == null ? List.of() : keys;
        rebuild();
    }

    /** 蓝图原文到了 → 交给公用网格造棋子快照（渲染缩略图）。 */
    public void applyBlueprintData(String pack, String id, String json) {
        if (!pack.equals(this.pack)) {
            return;                                     // 库换了：过期包
        }
        grid.onBlueprintData(pack, id, json);
        rebuild();
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        clearWidgets();
        if (!askedPacks) {
            askedPacks = true;
            ClientPacketDistributor.sendToServer(new AssetPackets.RequestPacksPayload());
        }
        int railBottom = EditorRail.buildGeneric(TABS, tab, t -> {
            tab = t;
            pickingSource = false;
            pickingBase = false;
            selectedId = "";
            top = 0;
            refilter();
            rebuild();
        }, this::addRenderableWidget);
        addRenderableWidget(DrawBoardMenuUi.button(4, railBottom + 6, EditorRail.W, 18, "返回总览", () ->
                Minecraft.getInstance().setScreen(new EditorMenuScreen())));

        buildContent();
    }

    private void buildContent() {
        if (pickingBase) {
            buildBasePicker();
        } else if (pickingSource) {
            buildSourceList();
        } else if (tab.equals(TAB_PACKS)) {
            buildPacks();
        } else {
            buildGrid();                                   // 卡牌 / 模型 / 物品 都是缩略图网格
        }
    }

    /** 「组件库」页：库列表（行）+ 新建库。 */
    private void buildPacks() {
        int x = EditorRail.LEFT;
        int w = Math.max(80, width - x - 12);
        int y = 44;
        scrollRows.setTotal(packs.size());
        for (int i = 0; i < scrollRows.rows(); i++) {
            String name = packs.get(scrollRows.index(i));
            addRenderableWidget(new Row(x, y, w, name, name.equals(pack), true,
                    () -> selectPack(name), null, ev -> openPackMenu(name, ev.x(), ev.y())));
            y += 22;
        }
        if (packs.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y, w, 20, "（还没有库 —— 先在下面新建一个）", null));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, w, 22, "+ 新建库…", () ->
                Minecraft.getInstance().setScreen(new NamePromptScreen(this, "新建库", "库名（≤32 字符）", "", name -> {
                    if (!name.isEmpty()) {
                        ClientPacketDistributor.sendToServer(new AssetPackets.CreatePackPayload(name));
                    }
                }))));
    }

    /** 「卡牌」/「模型」页：缩略图网格（30:42 比例），最后一格 =「+」添加。 */
    private void buildGrid() {
        if (pack == null) {
            addRenderableWidget(DrawBoardMenuUi.button(EditorRail.LEFT, 44,
                    Math.max(80, width - EditorRail.LEFT - 12), 20,
                    "（先在「组件库」页签里选一个库）", null));
            return;
        }
        int cw = cellW(), ch = cellH();
        int x0 = EditorRail.LEFT;
        int availW = Math.max(cw, width - x0 - 8);
        int cols = Math.max(1, (availW + GAP) / (cw + GAP));
        int cellSlots = cols * Math.max(1, gridRows(cols));
        // 「+」永远占最后一格：可见项数 = 格位 - 1
        int visible = Math.max(0, cellSlots - 1);
        top = Math.max(0, Math.min(Math.max(0, shown.size() - visible), top));

        int first = top;
        // 摆格交给公用网格（与编辑器「组件」页同一份实现）
        java.util.List<AssetPackets.Entry> entries = new java.util.ArrayList<>(shown.size());
        for (AssetPackets.AssetInfo a : shown) {
            entries.add(new AssetPackets.Entry(pack, a.id(), a.kind(), a.name(), a.w(), a.h(),
                    a.base(), a.lore()));
        }
        grid.place(entries, tab.equals(TAB_MODELS), first, visible, x0, gridTop(), cw, ch, cols,
                pack + "/" + selectedId,
                a -> selectedId = a.id(),
                this::edit,                              // 双击 = 编辑（物品 = 编辑页；其余 = 命名小窗）
                this::openCellMenu,                      // 右键 = 菜单（编辑 / 删除）
                this::addRenderableWidget);
        // 「+」格：放在已有缩略图的下一个位置（不够整行时也在行尾）
        int plusIndex = Math.min(visible, shown.size() - first);
        int px = x0 + (plusIndex % cols) * (cw + GAP);
        int py = gridTop() + (plusIndex / cols) * (ch + NAME_H + GAP);
        addRenderableWidget(new AssetGrid.AddCell(px, py, cw, ch, () -> {
            if (tab.equals(TAB_ITEMS)) {                    // 物品页：先挑一个**基底原版物品**
                pickingBase = true;
                basePicker.reset();
                rebuild();
                return;
            }
            pickingSource = true;
            scrollRows.setTop(0);                          // 与「组件库」页的库列表共用同一个 ListScroll：进来先归零
            // 来源：卡牌页 = 画板项目；模型页 = 蓝图
            ClientPacketDistributor.sendToServer(tab.equals(TAB_MODELS)
                    ? new AssetPackets.RequestBlueprintsPayload()
                    : new AssetPackets.RequestBoardsPayload());
            rebuild();
        }));

        if (!scrollLabel(visible).isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(EditorRail.LEFT, height - 46,
                    Math.max(80, width - EditorRail.LEFT - 8), "滚轮翻页　" + scrollLabel(visible), 0xFF909090));
        }
    }

    /** 网格可用行数（底部留 52 给状态/提示）。 */
    private int gridRows(int cols) {
        int ch = cellH();
        int usable = Math.max(ch, height - gridTop() - 52);
        return Math.max(1, (usable + NAME_H + GAP) / (ch + NAME_H + GAP));
    }

    private int cellW() {
        return tab.equals(TAB_ITEMS) ? ITEM_W : (tab.equals(TAB_MODELS) ? MODEL_W : CARD_W);
    }

    private int cellH() {
        return tab.equals(TAB_ITEMS) ? ITEM_H : (tab.equals(TAB_MODELS) ? MODEL_H : CARD_H);
    }

    private int gridTop() {
        return 38;
    }

    private String scrollLabel(int visible) {
        if (shown.size() <= visible) {
            return "";
        }
        return (top + 1) + "-" + Math.min(shown.size(), top + visible) + " / " + shown.size();
    }

    /** 「选来源」子视图：列画板项目，点一个就加进当前库（复用行样式）。 */
    private void buildSourceList() {
        int x = EditorRail.LEFT;
        int w = Math.max(80, width - x - 12);
        scrollRows.setTotal(boards.size());
        int y = 44;
        for (int i = 0; i < scrollRows.rows(); i++) {
            String key = boards.get(scrollRows.index(i));
            addRenderableWidget(new Row(x, y, w, key, false, false, () -> addFrom(key), null, null));
            y += 22;
        }
        if (boards.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y, w, 20, "（没有可用的画板项目 —— 先画一张）", null));
        } else if (sourceScrollLabel().length() > 0) {
            // 提示下面还有：读 scrollRows 的偏移，不是网格的 top
            addRenderableWidget(DrawBoardMenuUi.label(x, y + 2, w, sourceScrollLabel() + "　（滚轮翻页）", 0xFF909090));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, w, 22, "取消（回网格）", () -> {
            pickingSource = false;
            rebuild();
        }));
    }

    /** 「选来源」那行小标：{@code 3-10 / 12}（不超一屏 = 空串，不占位）。 */
    private String sourceScrollLabel() {
        int rows = scrollRows.rows();
        if (boards.size() <= rows) {
            return "";
        }
        return (scrollRows.top() + 1) + "-" + Math.min(boards.size(), scrollRows.top() + rows)
                + " / " + boards.size();
    }

    // ==================== 动作 ====================

    private void refilter() {
        shown = all.stream().filter(a -> {
            String k = a.kind() == null ? "" : a.kind();
            return switch (tab) {
                case TAB_CARDS -> k.equals(AssetStore.KIND_CARD) || k.equals(AssetStore.KIND_ART) || k.isEmpty();
                case TAB_MODELS -> k.equals(AssetStore.KIND_MODEL);
                case TAB_ITEMS -> k.equals(AssetStore.KIND_ITEM);
                default -> true;
            };
        }).toList();
        scrollRows.setTotal(shown.size());
    }

    /**
     * 右键一条资产 → 菜单：编辑 / 删除。物品的「编辑」= 进 {@link ItemEditScreen} 改名字 + 描述，
     * 其余种类 = 命名小窗。删的是库里那条资产；已导入项目的副本不受影响（导入即拷贝）。
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
            status = "已删除：" + e.name();
            ClientPacketDistributor.sendToServer(new AssetPackets.DeleteAssetPayload(e.pack(), e.id()));
            rebuild();
        });
        menu.open(width, height, grid.rightX(), grid.rightY(), labels, acts);
    }

    /** 右键一个库 → 菜单：编辑（改库名）/ 删除（整个库连资产）。 */
    private void openPackMenu(String name, double mx, double my) {
        menu.open(width, height, mx, my,
                List.of("编辑（改库名）", "删除整个库"),
                List.of(() -> Minecraft.getInstance().setScreen(new NamePromptScreen(this, "改库名",
                                "新库名（≤32 字符）", name, nn -> {
                                    if (!nn.isBlank()) {
                                        ClientPacketDistributor.sendToServer(new AssetPackets.RenamePackPayload(name, nn));
                                    }
                                })),
                        () -> {
                            status = "已删除库：" + name;
                            ClientPacketDistributor.sendToServer(new AssetPackets.DeletePackPayload(name));
                            rebuild();
                        }));
    }

    /** 菜单开着就吃掉这一下（点菜单项 = 干那一项；点别处 = 关掉），别连带触发菜单背后的条目。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        if (menu.click(event.x(), event.y())) {
            return true;
        }
        if (super.mouseClicked(event, doubled)) {              // 搜索框等控件优先
            return true;
        }
        if (pickingBase && basePicker.click(event.x(), event.y(), this::addItem)) {
            return true;                                       // 清单行是自绘的，命中归这里
        }
        return false;
    }

    /**
     * 「选基底物品」视图：搜索框 + 可滚动清单（行自绘，见 {@link ItemBasePicker}）。
     * 建自定义物品的第一步 —— 先挑一个原版物品当基底。
     */
    private void buildBasePicker() {
        int x = EditorRail.LEFT;
        int w = Math.max(120, width - x - 12);
        basePicker.build(x, 40, w, height - 40 - 44 - 20, this::addRenderableWidget, null);
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, w, 22, "取消（回网格）", () -> {
            pickingBase = false;
            rebuild();
        }));
    }

    /** 挑好基底 → 不弹命名框：按基底名直接建，再开编辑页改显示名 / 描述。 */
    private void addItem(String baseId) {
        if (pack == null) {
            status = "先在「组件库」页签里选一个库";
            return;
        }
        String name = baseId.substring(baseId.indexOf(':') + 1);
        ClientPacketDistributor.sendToServer(new AssetPackets.PackItemPayload(
                AssetStore.KIND_ITEM, baseId, pack, name, List.of()));
        status = "已建物品：" + name + "（进编辑页改显示名与描述）";
        pickingBase = false;
        rebuild();
        Minecraft.getInstance().setScreen(new ItemEditScreen(this, pack, name, baseId, name, List.of(), null));
    }

    /**
     * 双击格子 / 菜单「编辑」= 物品进编辑页（显示名 + 描述一页改完），
     * 其余种类 = 命名小窗（卡牌 / 模型只有名字可改）。
     */
    private void edit(AssetPackets.Entry e) {
        if (e.base() != null && !e.base().isEmpty()) {
            Minecraft.getInstance().setScreen(new ItemEditScreen(this, e.pack(), e.id(), e.base(), e.name(), e.lore(),
                    null));
            return;
        }
        rename(e);
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void selectPack(String name) {
        pack = name;
        all = List.of();
        shown = List.of();
        selectedId = "";
        top = 0;
        requested.clear();
        grid.reset();
        ClientPacketDistributor.sendToServer(new AssetPackets.OpenPackPayload(name));
        rebuild();
    }

    /** 双击 = 命名（通用命名小窗；确认后服务端改 json 的 name 并回包刷新）。 */
    private void rename(AssetPackets.Entry a) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "给" + tab + "命名",
                "显示名（可用 & 上色，如 &c红桃A）", a.name(), newName -> {
                    if (!newName.isBlank()) {
                        ClientPacketDistributor.sendToServer(new AssetPackets.RenameAssetPayload(pack, a.id(), newName));
                    }
                }));
    }

    private void addFrom(String boardKey) {
        if (pack == null) {
            status = "先在「组件库」页签里选一个库";
            return;
        }
        int slash = boardKey.lastIndexOf('/');
        String name = slash >= 0 ? boardKey.substring(slash + 1) : boardKey;
        if (tab.equals(TAB_MODELS)) {
            ClientPacketDistributor.sendToServer(new AssetPackets.PackBlueprintPayload(boardKey, pack, name));
        } else {
            ClientPacketDistributor.sendToServer(new AssetPackets.PackFromBoardPayload(
                    boardKey, pack, name, AssetStore.KIND_CARD));
        }
        status = "已添加：" + name;
        pickingSource = false;
        rebuild();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;                              // 菜单开着：先滚菜单（含到头也吃掉）
        }
        if (pickingBase) {
            basePicker.scroll(dy);                             // 行是自绘的：滚完不用重建控件
            return true;
        }
        // ⚠ 按当前视图判，别按页签判：卡牌 / 模型页签下按页签判会让「选来源」永远轮不到。
        // 顺序 = 挑基底 → 选来源 → 网格 → 行列表。
        if (pickingSource) {
            if (scrollRows.scroll(dy)) {
                rebuild();
                return true;
            }
            return false;
        }
        if (tab.equals(TAB_CARDS) || tab.equals(TAB_MODELS) || tab.equals(TAB_ITEMS)) {
            int availW = Math.max(cellW(), width - EditorRail.LEFT - 8);
            int cols = Math.max(1, (availW + GAP) / (cellW() + GAP));
            int visible = Math.max(0, cols * gridRows(cols) - 1);
            int next = Math.max(0, Math.min(Math.max(0, shown.size() - visible),
                    top - (int) Math.signum(dy) * cols));          // 网格按**行**翻
            if (next != top) {
                top = next;
                rebuild();
                return true;
            }
            return false;
        }
        if (scrollRows.scroll(dy)) {
            rebuild();
            return true;
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
        barX = Math.max(8, width - 12);
        listY = 44;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 44 - 92, Math.max(1, scrollRows.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scrollRows, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        int x = EditorRail.LEFT;
        g.text(font, Component.literal(tab + " · " + (pack == null ? "（未选库）" : "库：" + pack)), x, 8, 0xFFFFFFFF);
        g.text(font, Component.literal(hintLine()), x, 22, 0xFF909090);
        if (!status.isEmpty()) {
            g.text(font, Component.literal(ColorText.mask(status)), x, height - 34, 0xFFE0E080);
        }
        if (pickingBase) {
            basePicker.draw(g, font, mouseX, mouseY);      // 清单自绘（搜索框由 super 画在上面）
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);                // 菜单画在最上层
    }

    private String hintLine() {
        if (pickingSource) {
            return (tab.equals(TAB_MODELS) ? "选一条蓝图作为来源" : "选一个画板项目作为来源")
                    + "（单击即加进「" + (pack == null ? "?" : pack) + "」）";
        }
        return switch (tab) {
            case TAB_PACKS -> "单击一个库 = 选中它 · 下面可新建库 · 右键库行 =「编辑（改库名）/ 删除整个库」";
            case TAB_CARDS -> "点右下「+」添加（来源 = 画板项目）· 单击选中 · 双击命名 · 右键 =「编辑 / 删除」";
            case TAB_MODELS -> "点右下「+」添加（来源 = **蓝图**）· 单击选中 · 双击命名 · 右键 =「编辑 / 删除」· 按住模型格拖动 = 旋转预览";
            default -> "自定义物品 = 原版物品 + 名字/描述（外观用原版）· 点「+」挑基底 · 双击 / 右键「编辑」= 进同一个编辑页";
        };
    }

    // ==================== 控件 ====================

    /** 「组件库」页 / 来源列表用的一行（样式照编辑器左栏页签）。 */
    private static class Row extends AbstractWidget {
        private final Runnable onSingle;
        private final Runnable onDouble;
        /** 右键：把事件交给宿主（宿主拿它的坐标开菜单）；null = 这一行没有右键动作。 */
        private final Consumer<MouseButtonEvent> onRight;
        private final boolean active;
        private final boolean strong;

        Row(int x, int y, int w, String label, boolean active, boolean strong, Runnable onSingle,
                Runnable onDouble, Consumer<MouseButtonEvent> onRight) {
            super(x, y, w, 20, Component.literal(label));
            this.active = active;
            this.strong = strong;
            this.onSingle = onSingle;
            this.onDouble = onDouble;
            this.onRight = onRight;
        }

        /** 右键也算有效点击（原版只吃左键，不覆写的话右键根本进不到 onClick）。 */
        @Override
        protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || (buttonInfo.button() == 1 && onRight != null);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            int bg = active ? 0xFF37402E : (isHovered() ? 0xFF2E3138 : 0xFF21242B);
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            if (active) {
                g.fill(getX(), getY(), getX() + 3, getY() + getHeight(), strong ? 0xFFE0C060 : 0xFF7AC06A);
            }
            g.text(font, getMessage().getString(), getX() + 8, getY() + (getHeight() - 9) / 2,
                    active ? 0xFFFFFFFF : 0xFFC8C8D0);
            g.outline(getX(), getY(), getWidth(), getHeight(), active ? 0xFF6A7A4A : 0xFF3A3E4A);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (event.button() == 1) {                        // 右键 = 交给宿主开菜单
                if (onRight != null) {
                    onRight.accept(event);
                }
            } else if (doubled && onDouble != null) {
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
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
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
