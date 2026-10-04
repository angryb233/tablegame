package com.tablegame.editor.world;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptEdit;
import com.tablegame.script.Interp;
import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.area.AreaCaptureClient;
import com.tablegame.area.AreaPlaceClient;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.ScrollBar;
import com.tablegame.net.AreaCapturePackets;

    /**
     * 「世界」页：一行 = 一个「地方」（区域）。
     *
     * <p>区域 = 世界里的一块地方 + 它的内容。盒住在脚本顶层的 {@code area} 声明里
     * （脚本要拿它当坐标基准：{@code tp} / {@code in_area} / {@code edge}）；
     * 内容住档里那份方块快照（几万格，塞不进文本）。列表分两栏：区域行（显示名 · 资产名 + 盒 + 内容）
     * 与未绑定行（没被任何区域引用的快照）。与其它工具屏同款：直接改编辑器共享的内存 {@code def}，
     * 点左栏「保存」才上传落盘。
     */
public class WorldTabScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }
    /** 玩法编辑器（同包工具屏约定：共享 def）。 */
    final GameEditorScreen parent;
    /** 列表滚动（同棋子列表）。 */
    private final ListScroll scroll = new ListScroll(8);
    /** 滚动条：轨道贴在行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            rebuild();
        }
        return true;
    }

    /** 右键菜单（屏层状态机，AssetMenu 同款——不开控件）。 */
    private final AssetMenu menu = new AssetMenu();
    /** 右键动作指向的 areas() 下标（-1 = 没有）。 */
    private int menuIndex = -1;
    /** 脚本声明的区域（每次 init 现算：脚本是真源）。 */
    private java.util.List<Interp.Region> regions = java.util.List.of();
    /** 没被任何区域引用的场景（列在区域下面，免得「能力凭空消失」）。 */
    private final java.util.List<GameDefinition.AreaDef> orphans = new java.util.ArrayList<>();
    /** 右键动作指向的 regions 下标（-1 = 这一下是未绑定行）。 */
    private int menuRegion = -1;
    private String status = "";
    private boolean statusBad;

    public WorldTabScreen(GameEditorScreen parent) {
        super(Component.literal("世界"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = EditorRail.cx(this);
        int y = 52;

        // 一栏：区域（盒写在脚本里）+ 它的内容（档里那份快照）；没被任何区域引用的快照列在下面
        var areas = parent.def.areas();
        regions = ScriptEdit.regionsOf(parent.def.script());
        orphans.clear();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (var r : regions) used.add(snapshotId(r));
        for (var a : areas) if (!used.contains(a.id())) orphans.add(a);

        scroll.setTotal(regions.size() + orphans.size());
        for (int row = 0; row < scroll.rows(); row++) {
            int i = scroll.index(row);
            String label;
            int kind, idx;
            if (i < regions.size()) {
                var r = regions.get(i);
                kind = 0;
                idx = i;
                String snap = snapshotId(r);
                boolean has = areas.stream().anyMatch(a -> a.id().equals(snap) && a.captured());
                // 文案：不写「区域」二字 —— 先资产名、再显示名（与对象页顺序相反）。⚠ 坐标不写（编辑界面看得到）。
                String show = r.name().isEmpty() ? "（匿名）"
                        : r.label().isEmpty() || r.label().equals(r.name()) ? r.name()
                        : r.name() + " · " + r.label();
                label = show + "　内容 " + (snap.isEmpty() ? "—"
                        : has ? "已捕获" : "未捕获");
            } else {
                GameDefinition.AreaDef a = orphans.get(i - regions.size());
                kind = 1;
                idx = i - regions.size();
                label = a.id() + (a.captured() ? "（未绑定声明）" : "（未绑定声明 · 未捕获）");
            }
            // 行 = 自定义按钮：左键无动作，右键弹菜单。
            // ⚠ 原版按钮 isValidClickButton 只认左键，右键必须覆写放行（26.x）。
            addRenderableWidget(new AreaRow(cx - 140, y, 280, 22,
                    ColorText.mask(DrawBoardMenuUi.ellipsis(label, 44)), idx, kind));
            y += 26;
        }
        // 新建入口：快照是「区域的内容」，不单独建 —— 要内容就【按声明捕获】
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y + 6, 280, 24, "+ 新建区域", this::addRegion));

        EditorRail.build(parent, "世界", this::addRenderableWidget);
    }

    /** 写回出口（同其它屏：先解析验证 → 通过才换 def 并静默保存）。 */
    private void apply(ScriptEdit.Result r) {
        if (r.text().equals(parent.def.script())) {
            setStatus(r.note(), true);
            return;
        }
        String err = parent.applyScript(r.text());
        setStatus(err.isEmpty() ? r.note() : err, !err.isEmpty());
        clearWidgets();
        init();
    }

    private void setStatus(String s, boolean bad) {
        status = s;
        statusBad = bad;
        if (bad) DrawBoardMenuUi.msg("[世界] " + s);
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    /** 「编辑」：开该区域的 3D 离屏视口（只读；没捕获过的先提示去捕获）。 */
    private void openView(int idx) {
        if (idx < 0 || idx >= parent.def.areas().size()) {
            return;
        }
        var a = parent.def.areas().get(idx);
        // ⚠ 未捕获也放进来（视口是空的）：里面【框】能手动框选、【收】能按声明盒捕获
        // —— 世界页只剩「编辑 / 删除」，再拦一道这条快照就没入口了。
        Minecraft.getInstance().setScreen(new AreaViewScreen(this, parent.gameName(), a, na -> {
            // 视口里改完了：换掉 areas 段里同 id 的那条（单一数据源 = 编辑器内存模型）
            var list = new ArrayList<GameDefinition.AreaDef>();
            for (var x : parent.def.areas()) {
                list.add(x.id().equals(na.id()) ? na : x);
            }
            parent.def = parent.def.withAreas(list);
        }));
    }


    /**
     * 用放置棒放一条区域：关屏回世界 → 幽灵跟准星走 → 右键钉住 → Shift+Enter 落地。
     *
     * @param mode {@code "all"} = 复制（原处留着）· {@code "move"} = 移动（落完清空源盒，重叠处不动）
     */
    void startPlaceByName(String areaId, String mode) {
        GameDefinition.AreaDef a = null;
        for (var d : parent.def.areas()) {
            if (d.id().equals(areaId)) a = d;
        }
        if (a == null || !a.captured()) {
            setStatus("这条还没有内容 —— 先框选一次，或在 3D 视窗里按【收】", true);
            return;
        }
        parent.saveToServer(true);          // 服务端落的是**盘上那份**内容，先存
        AreaPlaceClient.begin(parent.gameName(), a, mode);
    }

    /**
     * 从 3D 视窗进区域属性页（视窗那颗【编】调用）。
     * 按名字找区域声明：视窗手上内容的名字 = 区域名，对不上说明挂的是别的区域（视窗就不摆【编】）。
     */
    void openAreaEdit(String regionName) {
        if (regionName == null || regionName.isEmpty()) return;
        for (var r : regions) {
            if (r.name().equals(regionName)) {
                Minecraft.getInstance().setScreen(new AreaEditScreen(this, parent, r));
                return;
            }
        }
        setStatus("脚本里没有区域「" + regionName + "」这一条 —— 先框选一次把它建出来", true);
    }

    /** 删除一个区域（右键菜单项）。 */
    private void deleteArea() {
        if (menuIndex < 0 || menuIndex >= parent.def.areas().size()) return;
        var list = new ArrayList<>(parent.def.areas());
        list.remove(menuIndex);
        parent.def = parent.def.withAreas(list);
        menuIndex = -1;
        rebuild();
    }

    // ---------- 区域（声明）那一栏 ----------

    /** 那条区域绑的场景名（声明里的 art；空 = 用区域名当场景名）。 */
    private String snapshotId(Interp.Region r) {
        return r.art().isEmpty() ? r.name() : r.art();
    }

    /**
     * 【＋新建区域】= 先建一个空条目，再直接进选取模式：拿选取棒框一片，Shift+Enter 时服务端
     * 一次性把内容收进这份条目并写上 / 更新脚本里的 {@code area} 声明（{@code box} = 框出的范围、
     * {@code art} = 这份内容）。
     *
     * <p>⚠ 不带默认盒：盒来自你真框过的那一片。中途退出也没关系 —— 空条目留在「未绑定」栏，
     * 右键它【选择区域】接着框。
     */
    private void addRegion() {
        var list = new ArrayList<>(parent.def.areas());
        String nm = null;
        for (int n = 1; n <= 999; n++) {                   // 内容与声明两边都没占用的 rN
            String cand = "r" + n;
            if (list.stream().anyMatch(a -> a.id().equals(cand))) continue;
            if (regions.stream().anyMatch(r -> r.name().equals(cand))) continue;
            nm = cand;
            break;
        }
        if (nm == null) {
            setStatus("区域太多了（r1~r999 都占着）", true);
            return;
        }
        list.add(new GameDefinition.AreaDef(nm, 0, 0, 0, List.of(), List.of()));   // 空条目：还没有内容
        parent.def = parent.def.withAreas(list);
        startCaptureByName(nm);                            // 存盘 + 请求进选取模式 + 关屏回世界
    }

    /**
     * 进选取模式：**先存盘**（服务端 {@code startAreaCapture} 读的是盘上那份）→ 请求服务端 → 关屏回世界。
     *
     * <p>三个入口共用这一处：区域行的【框选】· 未绑定快照行的【选择区域】· 3D 视窗那颗【选】按钮。
     */
    void startCaptureByName(String areaId) {
        if (areaId == null || areaId.isEmpty()) {
            setStatus("这一条还没有名字 —— 先给它起个名字再框选", true);
            return;
        }
        parent.saveToServer(true);
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new AreaCapturePackets.RequestPayload(parent.gameName(), areaId));
        AreaCaptureClient.begin(parent.gameName(), areaId);
    }

    /**
     * 【编辑区域】开 3D 视窗看 / 改它绑的那份场景。
     * 还没这份场景 → 给一份**盒大小**的空壳（视窗里那两颗按钮就是为它准备的：先「更新进编辑器」收进来）。
     */
    private void openRegionView(int idx) {
        if (idx < 0 || idx >= regions.size()) return;
        var r = regions.get(idx);
        String id = snapshotId(r);
        if (id.isEmpty()) {
            setStatus("这条区域没有名字（匿名）—— 给它起个名字才能绑场景", true);
            return;
        }
        GameDefinition.AreaDef snap = null;
        for (var a : parent.def.areas()) if (a.id().equals(id)) snap = a;
        if (snap == null) {
            snap = new GameDefinition.AreaDef(id,
                    (int) (r.maxX() - r.minX()) + 1, (int) (r.maxY() - r.minY()) + 1,
                    (int) (r.maxZ() - r.minZ()) + 1, java.util.List.of(), java.util.List.of());
        }
        Minecraft.getInstance().setScreen(new AreaViewScreen(this, parent.gameName(), snap, r.name(), na -> {
            // 视口里改完了：换掉 areas 段里同 id 的那条（单一数据源 = 编辑器内存模型）
            var list = new ArrayList<GameDefinition.AreaDef>();
            boolean had = false;
            for (var x : parent.def.areas()) {
                if (x.id().equals(na.id())) { list.add(na); had = true; } else list.add(x);
            }
            if (!had) list.add(na);
            parent.def = parent.def.withAreas(list);
        }));
    }



    /** 删一条区域声明（还被 {@code tp} / {@code in_area} 引用 → 拒收，先改那些地方）。 */
    private void deleteRegion() {
        if (menuRegion < 0 || menuRegion >= regions.size()) return;
        apply(ScriptEdit.removeArea(parent.def.script(), regions.get(menuRegion).name()));
    }

    /**
     * 右键一条区域 → 菜单两项（编辑 / 删除）。
     * 框选 / 按声明盒捕获 / 应用到世界都搬进了 3D 视窗工具栏 —— 那才是编辑一条区域的家。
     */
    private void openRegionMenu(int idx, double mx, double my) {
        menuRegion = idx;
        menuIndex = -1;
        menu.open(width, height, mx, my,
                List.of("编辑", "删除"),
                List.of(() -> openRegionView(menuRegion), this::deleteRegion));
    }

    /** 右键一条**未绑定的快照** → 同样两项（编辑进 3D 视口，里面【框】能继续框选它、【收】能按盒捕获）。 */
    private void openAreaMenu(int idx, double mx, double my) {
        menuRegion = -1;
        // ⚠ 索引空间要换算：这一栏行索引是 orphans 的，而 openView / deleteArea 按 areas() 索引干活。
        // 在这里按名字换算一次，两个消费者就不用记「手上是哪套索引」。
        menuIndex = indexInAreas(idx >= 0 && idx < orphans.size() ? orphans.get(idx).id() : "");
        if (menuIndex < 0) {
            setStatus("这条未绑定内容找不到了（列表可能刚刷新过）—— 再右键一次", true);
            return;
        }
        menu.open(width, height, mx, my,
                List.of("编辑", "删除"),
                List.of(() -> openView(menuIndex), this::deleteArea));
    }

    /** 那条内容在 {@code areas()} 里的下标（-1 = 没有）。菜单行索引换算的**唯一**出口。 */
    private int indexInAreas(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < parent.def.areas().size(); i++) {
            if (parent.def.areas().get(i).id().equals(id)) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        if (menu.click(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    // ===== 菜单检索/滚动/拖动转发（AssetMenu）=====

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
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        barX = Math.max(8, width - 12);
        listY = 52;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 52 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        g.centeredText(font, Component.literal("区域 " + regions.size() + " 条 · 未绑定 " + orphans.size()
                        + " 条 · 右键 = 编辑 / 删除（动作都进了 3D 视窗的工具栏）"
                        + (scroll.label().isEmpty() ? "" : "　" + scroll.label())),
                EditorRail.cx(this), 30, 0xFFFFFFFF);
        // 状态行：好回执也要看得见
        g.text(font, status.isEmpty()
                        ? "区域 = 世界里的一块地方（盒写在脚本里）+ 它的内容（档里那份方块快照，跨存档可搬）"
                        : status,
                EditorRail.LEFT, height - 34, statusBad ? 0xFFFF8080 : 0xFF90C090);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);          // 菜单画在最上层
    }

    /** 区域列表行：右键 = 弹菜单（编辑 / 选择区域 / 删除）。 */
    private class AreaRow extends net.minecraft.client.gui.components.AbstractWidget {
        private final int areaIndex;
        /** 0 = 区域行（盒写在脚本里）· 1 = 未使用的快照行（档里有、没被任何区域引用）。 */
        private final int kind;

        AreaRow(int x, int y, int w, int h, String label, int areaIndex, int kind) {
            super(x, y, w, h, Component.literal(label));
            this.areaIndex = areaIndex;
            this.kind = kind;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, isHoveredOrFocused() ? 0xFF2E3138 : 0xFF21242B);
            g.outline(x, y, w, h, 0xFF3A3E4A);
            g.text(Minecraft.getInstance().font, getMessage().getString(),
                    x + 8, y + (h - 9) / 2, 0xFFC8C8D0);
        }

        /** 右键也算有效点击（原版只认左键，不覆写 = 右键静默失效）。 */
        @Override
        protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || buttonInfo.button() == 1;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (event.button() != 1) return;
            if (kind == 0) {
                openRegionMenu(areaIndex, event.x(), event.y());
            } else {
                openAreaMenu(areaIndex, event.x(), event.y());
            }
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
