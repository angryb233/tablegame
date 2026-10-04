package com.tablegame.editor.stage;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.script.ScriptEditScreen;

/**
 * 「舞台」页 —— 一行 = 一块舞台。
 *
 * <pre>
 *   [左栏：总览/脚本/舞台/对象/世界/数值/组件/保存/返回]  [二级页签：HUD/全屏界面/实体画面]
 *                                         内容区：这一类下的舞台列表 + ＋新建
 * </pre>
 *
 * <p>左键一行 = 进全屏编辑页 {@link StageVisualScreen}；右键 = 编辑 / 删除（删整块，还被 {@code show} 指到会拒收并报行号）。
 * 真源永远是脚本：每行都由 {@code Parser.parse(脚本)} 现算；新建 / 删除经 {@link ScriptEdit} 改文本，
 * 再走 {@link GameEditorScreen#applyScript} 那一个出口。
 */
public class StageDirScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    /** 玩法编辑器（同包工具屏约定：共享 def）。 */
    final GameEditorScreen parent;
    /** 右键菜单（屏层状态机，与「世界」页同一个件）。 */
    private final AssetMenu menu = new AssetMenu();

    // ===== 二级页签：三种类型 =====
    private static final String TAB_HUD = "HUD";
    private static final String TAB_FULL = "全屏界面";
    private static final String TAB_ENTITY = "实体画面";
    /** 页签文字（顺序 = 从上到下）——与脚本里的类别一一对应（前两类）。 */
    private static final String[] TABS = { TAB_HUD, TAB_FULL, TAB_ENTITY };
    /** 现在看哪一类（字段住在本屏实例里：换页签是原地重建，不新开屏）。 */
    private String tab = TAB_HUD;

    /**
     * 目录一行。
     *
     * @param entity 这一块是实体画面：没有框，既无可视化预览屏、也不谈「开局显示」——
     *               行上写「N 个组件」，左键进它的可视化编辑页
     */
    private record Row(String name, String label, int stmts, boolean shown, boolean entity) {

        Row(String name, String label, int stmts, boolean shown) {
            this(name, label, stmts, shown, false);
        }
    }
    /** 两类各自己的行（顺序 = 源码顺序，作者好找）。 */
    private final List<Row> hudRows = new ArrayList<>();
    private final List<Row> fullRows = new ArrayList<>();
    /** 实体画面那一栏的行。 */
    private final List<Row> entityRows = new ArrayList<>();
    /** 解析不了时的红字（列表画不出来，但新建 / 返回照旧可用）。 */
    private String badScript;
    private String status = "";
    private boolean statusBad;

    /** 行高 / 行间距（屏幕像素）。 */
    private static final int ROW_H = 22, ROW_GAP = 26;

    public StageDirScreen(GameEditorScreen parent) {
        super(Component.literal("舞台"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        hudRows.clear();
        fullRows.clear();
        entityRows.clear();
        badScript = null;
        // ① 先搭两列页签（init 里任一处抛异常 ⇒ 后面控件全不建；页签放最前，作者仍能切页 / 保存 / 返回）
        EditorRail.build(parent, "舞台", this::addRenderableWidget);                  // 第一列：编辑器左栏
        EditorRail.buildGenericAt(EditorRail.LEFT, TABS, tab, t -> {                   // 第二列：三种类型
            tab = t;
            clearWidgets();
            init();
        }, this::addRenderableWidget);
        // ② 脚本是真源：现解析一遍（错也照样画页 —— 至少新建 / 返回能用，不把编辑器一起带走）
        Ast.Script sc = null;
        try {
            sc = Parser.parse(parent.def.script());
        } catch (Ast.ScriptError e) {
            badScript = e.getMessage();
        }
        if (sc != null) {
            List<String> shown = ScriptEdit.shownScreens(parent.def.script());
            for (String nm : sc.screens().keySet()) {                                  // screens() 保源码顺序
                Ast.Screen s = sc.screens().get(nm);
                if (s.entityView()) {                                                  // 第三类：实体画面
                    entityRows.add(new Row(nm, s.label() == null ? "" : s.label(),
                            s.comps().size(), true, true));
                    continue;
                }
                Row r = new Row(nm, s.label() == null ? "" : s.label(), s.body().size(), shown.contains(nm));
                (s.hud() ? hudRows : fullRows).add(r);
            }
        }
        // ③ 内容区：当前这一类的行 + 底部一颗「＋新建」
        int x = EditorRail.LEFT2;
        int w = contentW();
        int y = 58;
        List<Row> list = currentRows();
        for (Row r : list) {
            addRenderableWidget(new RowWidget(x, y, w, ROW_H, r));
            y += ROW_GAP;
        }
        if (list.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(x + 2, y + 4, w,
                    "（这一类还没有舞台 —— 点下面的「＋新建」）",
                    0xFF707070));
            y += 18;
        }
        if (TAB_ENTITY.equals(tab)) {
            addRenderableWidget(DrawBoardMenuUi.button(x, y + 4, 220, ROW_H, "＋ 新建 实体画面",
                    this::newEntityBlock));
        } else {
            boolean hud = TAB_HUD.equals(tab);
            addRenderableWidget(DrawBoardMenuUi.button(x, y + 4, 220, ROW_H, hud ? "＋ 新建 HUD" : "＋ 新建 界面",
                    () -> newBlock(hud)));
        }
    }

    /** 当前页签那一类的行。 */
    private List<Row> currentRows() {
        if (TAB_ENTITY.equals(tab)) return entityRows;
        return TAB_HUD.equals(tab) ? hudRows : fullRows;
    }

    /** 内容区宽（从第二列右边到窗口右边留一点白）—— 照「组件」页那套。 */
    private int contentW() {
        return Math.max(120, width - EditorRail.LEFT2 - 14);
    }

    // ===== 动作 =====

    /** 左键 / 右键「编辑」：进这块舞台的**全屏编辑页**（照「世界」点区域的 3D 视窗那套）。 */
    private void openPreview(Row r) {
        Minecraft.getInstance().setScreen(new StageVisualScreen(parent, r.name()));
    }

    /**
     * 实体画面：进它的可视化编辑页（{@link EntityVisualScreen}）—— 3D 视口里中心一个盔甲架（= 锚点替身），
     * 组件按 `at` / `rot` 摆着。「跳到脚本那一行」退到右键菜单里（改锚点参数 / 正文还得去文本）。
     */
    private void openEntityEdit(Row r) {
        Minecraft.getInstance().setScreen(new EntityVisualScreen(this, r.name()));
    }

    /**
     * 实体画面的「跳到脚本那一行」：改声明文本（锚点参数、组件的表达式正文）时走它。
     */
    private void openScript(Row r) {
        int line = 0;
        try {                                          // sc 是 init 里的局部量；这里现解析一次拿声明行
            Ast.Screen s = Parser.parse(parent.def.script()).screens().get(r.name());
            if (s != null) line = s.line();
        } catch (Ast.ScriptError e) {
            line = 0;
        }
        if (line <= 0) {
            setStatus("找不到「" + r.name() + "」的声明行 —— 脚本刚被改过？", true);
            rebuild();
            return;
        }
        Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, line));
    }

    /** 新建一块实体画面：问名字 → {@link ScriptEdit#addEntityScreen}（写带注释的骨架）→ 存盘 → 回目录。 */
    private void newEntityBlock() {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this,
                "新建实体画面",
                "英文标识符（挂上它用 show(谁, \"名字\")）",
                "e1", nm -> {
                    if (nm == null || nm.isBlank()) return;
                    String name = nm.strip();
                    ScriptEdit.Result r = ScriptEdit.addEntityScreen(parent.def.script(), name);
                    if (r.text().equals(parent.def.script())) {
                        setStatus(r.note(), true);
                        rebuild();
                        return;
                    }
                    String err = parent.applyScript(r.text());
                    if (!err.isEmpty()) {
                        setStatus(err, true);
                        rebuild();
                        return;
                    }
                    setStatus(r.note(), false);
                    rebuild();
                }));
    }

    /**
     * 新建一块（hud=true 看板 / false 全屏）：问名字 → {@link ScriptEdit#addScreen} → 存盘 → 直接进它的编辑页。
     *
     * <p>名字规则不在这里写：脚本那边的 {@link ScriptEdit#checked} 会真跑一遍解析，中文名 / 重名一律当场拒收
     * 并把原因回在底栏（同其它命名入口的口径）。
     */
    private void newBlock(boolean hud) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this,
                hud ? "新建 HUD（看板）" : "新建全屏界面",
                "英文标识符（脚本里用它：show(\"名字\")）",
                hud ? "board" : "main", nm -> {
                    if (nm == null || nm.isBlank()) return;
                    String name = nm.strip();
                    ScriptEdit.Result r = ScriptEdit.addScreen(parent.def.script(), name, hud);
                    if (r.text().equals(parent.def.script())) {
                        setStatus(r.note(), true);
                        rebuild();
                        return;
                    }
                    String err = parent.applyScript(r.text());
                    if (!err.isEmpty()) {
                        setStatus(err, true);
                        rebuild();
                        return;
                    }
                    Minecraft.getInstance().setScreen(new StageVisualScreen(parent, name));
                }));
    }

    /** 右键「删除」：删整块（还被 {@code show("名")} 指到会被 {@link ScriptEdit} 拒收并报出是哪几行）。 */
    private void deleteRow(Row r) {
        ScriptEdit.Result res = ScriptEdit.removeScreen(parent.def.script(), r.name());
        if (res.text().equals(parent.def.script())) {
            setStatus(res.note(), true);
            rebuild();
            return;
        }
        String err = parent.applyScript(res.text());
        setStatus(err.isEmpty() ? res.note() : err, !err.isEmpty());
        rebuild();
    }

    private void setStatus(String s, boolean bad) {
        status = s;
        statusBad = bad;
        if (bad) DrawBoardMenuUi.msg("[舞台] " + s);
    }

    /** 写回脚本后重算列表（模型变了 → 行也跟着变）。 */
    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (menu.click(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
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
        EditorRail.drawBackdrop(g, height);
        // 第二列的底板 + 右边界（照「组件」/「对象」页那套两级页签的视觉）
        g.fill(EditorRail.W + 10, 0, EditorRail.LEFT2 - 8, height, 0xFF14161C);
        g.fill(EditorRail.LEFT2 - 8, 0, EditorRail.LEFT2 - 7, height, 0xFF3A3E4A);
        int cx = (EditorRail.LEFT2 + width) / 2;
        g.centeredText(font, Component.literal("舞台 · " + parent.def.name()), cx, 16, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(tabHint()), cx, 32, 0xFF909090);
        String foot = status.isEmpty()
                ? "HUD " + hudRows.size() + " 块 · 全屏界面 " + fullRows.size() + " 块 · 实体画面 " + entityRows.size() + " 块"
                : status;
        g.text(font, DrawBoardMenuUi.ellipsis(foot, 100), EditorRail.LEFT2, height - 30,
                statusBad ? 0xFFFF8080 : (status.isEmpty() ? 0xFF808080 : 0xFF90D090));
        if (badScript != null) {
            g.text(font, DrawBoardMenuUi.ellipsis("脚本解析不了：" + badScript + "（去「脚本」页修；新建会拒收）", 100),
                    EditorRail.LEFT2, height - 46, 0xFFFF9090);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);          // 菜单画在最上层
    }

    /** 顶栏第二行：当前这一类在脚本里长什么样 + 这一页怎么用。 */
    private String tabHint() {
        return switch (tab) {
            case TAB_FULL -> "screen 名 { … }：要玩家输入时打开的那一块 · 左键进全屏编辑页 · 右键编辑 / 删除";
            case TAB_ENTITY -> "screen 名 entity { … }：画面 = 一组挂在锚点上的实体 · 左键进可视化编辑页 · "
                    + "右键编辑 / 跳到脚本那一行 / 删除";
            default -> "screen 名 hud { … }：常驻屏幕上那一小块 · 左键进全屏编辑页 · 右键编辑 / 删除";
        };
    }

    /** 目录一行：左键 = 进全屏编辑页 · 右键 = 编辑 / 删除（同「世界」页：一行只留这两件）。 */
    private class RowWidget extends AbstractWidget {
        private final Row row;

        RowWidget(int x, int y, int w, int h, Row row) {
            super(x, y, w, h, Component.literal(rowText(row)));
            this.row = row;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, isHoveredOrFocused() ? 0xFF2E3138 : 0xFF21242B);
            // ⚠ outline 的签名是 (x, y, 宽, 高) —— 写成 x + w / y + h 会画出巨框（右下两条边跑到屏外）；
            //   自检有通用闸门钉它。
            g.outline(x, y, w, h, 0xFF3A3E4A);
            g.text(Minecraft.getInstance().font, getMessage().getString(), x + 8, y + (h - 9) / 2, 0xFFC8C8D0);
        }

        /** 右键也算有效点击（原版只认左键，不覆写 = 右键静默失效）。 */
        @Override
        protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || buttonInfo.button() == 1;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (event.button() == 1) {
                // ⚠ 控件类里裸写 width / height 取到的是这一行控件自己的宽高（AbstractWidget 字段），
                //   菜单夹取会按行高算 ⇒ 压在顶边。必须写 StageDirScreen.this.xxx 才是屏的宽高。
                //   （自检有全仓闸门扫这一类。）
                if (row.entity()) {
                    menu.open(StageDirScreen.this.width, StageDirScreen.this.height, event.x(), event.y(),
                            List.of("编辑", "跳到脚本那一行", "删除"),
                            List.of(() -> openEntityEdit(row), () -> openScript(row), () -> deleteRow(row)));
                    return;
                }
                menu.open(StageDirScreen.this.width, StageDirScreen.this.height, event.x(), event.y(),
                        List.of("编辑", "删除"),
                        List.of(() -> openPreview(row), () -> deleteRow(row)));
                return;
            }
            if (row.entity()) {
                openEntityEdit(row);                  // 实体画面：左键进它的可视化编辑页（与 HUD / 全屏同一手感）
                return;
            }
            openPreview(row);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** 一行的文字：显示名（资产名）· 几条画语句 · 开局显示。 */
    private static String rowText(Row r) {
        String show = r.label().isEmpty() ? r.name() : r.label() + "（" + r.name() + "）";
        if (r.entity()) return show + "　· " + r.stmts() + " 个组件　· 左键进编辑页";
        return show + "　· " + r.stmts() + " 条画语句" + (r.shown() ? "　· 开局显示" : "");
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
