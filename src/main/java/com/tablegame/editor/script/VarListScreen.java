package com.tablegame.editor.script;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameStore;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.ScrollBar;

/**
 * 变量工具屏（左栏「变量」）：变量表，可写。
 * 变量（分数、手牌、题面池…）的真源是脚本里那几行 {@code var 名 = 初值}；本页只是它的编辑视图
 * （名字 / 初值 / 新建 / 删），每个动作翻成对脚本的一小处改写，经 {@link GameEditorScreen#applyScript} 落盘。
 * 「出现在 N 行」是只读对账（只做列表，不画引用线）。
 */
public class VarListScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    private final GameEditorScreen parent;
    private final ListScroll scroll = new ListScroll(7);
    /** 滚动条：轨道贴行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            clearWidgets(); init();
        }
        return true;
    }

    /** 从变量节点跳过来时要选中/滚到的那一条（空 = 不选）。 */
    private final String focus;
    /** 等二次确认的那个变量名（删是破坏性的，同画布那套「再点一次」）。 */
    private String arm = "";
    private String status = "";
    private boolean statusBad;
    /** 值框（回车 / 点别处提交）：控件 → 它属于哪个变量、哪一格。 */
    private final List<Object[]> boxes = new ArrayList<>();

    public VarListScreen(GameEditorScreen parent) {
        this(parent, "");
    }

    public VarListScreen(GameEditorScreen parent, String focus) {
        super(Component.literal("变量"));
        this.parent = parent;
        this.focus = focus == null ? "" : focus;
    }

    private String src() {
        return parent.def.script();
    }

    /** 这一页列的变量 —— 只列脚本里真有的那几个（{@code var …} 那几行）；老档 vars 段残留不进列表。 */
    private List<String> slots() {
        List<String> out = new ArrayList<>();
        for (String id : GameStore.slotNames(parent.def)) {
            if (scriptVar(id) != null) out.add(id);              // 脚本里没有 = 老档残留，不列
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 这一页的列位置（表头与每行共用）：名字 · 初值。 */
    private int[] cols() {
        int x = EditorRail.LEFT2;
        int w = width - x - 12;
        int name = 96;
        int init = Math.max(120, w - name - 4 - 44 - 8 - 150);
        return new int[]{x, name, init};
    }

    @Override
    protected void init() {
        clearWidgets();
        boxes.clear();
        EditorRail.build(parent, "变量", this::addRenderableWidget);
        int[] c = cols();
        List<String> all = slots();
        scroll.setTotal(all.size());
        if (!focus.isEmpty()) {                                  // 从变量节点跳来：滚到它那一行
            int idx = all.indexOf(focus);
            if (idx >= 0) scroll.scroll(scroll.top() - idx);
        }
        int y = 74;
        for (int row = 0; row < scroll.rows(); row++) {
            String id = all.get(scroll.index(row));
            ScriptEdit.VarAt v = scriptVar(id);

        }
        addRenderableWidget(DrawBoardMenuUi.button(c[0], height - 40, 110, 22, "＋ 新建变量", this::addOne));
        addRenderableWidget(DrawBoardMenuUi.button(c[0] + 116, height - 40, 100, 22, "返回总览", this::back));
    }

    /** 脚本里那条顶层声明（老档遗留 = null）。 */
    private ScriptEdit.VarAt scriptVar(String name) {
        for (ScriptEdit.VarAt v : ScriptEdit.varsOf(src())) {
            if (v.name().equals(name)) return v;
        }
        return null;
    }

    /** 「谁在用」：这个名字读了几行、写了几行 —— 只读列表。 */
    private String useLabel(String name, int declLine) {
        var r = ScriptEdit.varReads(src(), name);
        var w = ScriptEdit.varWrites(src(), name);
        if (declLine > 0) w.remove(Integer.valueOf(declLine));      // 声明那一行不算「用」
        if (r.isEmpty() && w.isEmpty()) return "没在用";
        return "读 " + r.size() + " 行 · 写 " + w.size() + " 行";
    }

    // ================= 动作：都经 applyScript 一条出口 =================

    private void apply(ScriptEdit.Result r) {
        if (r.text().equals(src())) {
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
        if (bad) DrawBoardMenuUi.msg("[变量] " + s);
    }

    private void rename(ScriptEdit.VarAt v) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "改变量名", "英文标识符（用在脚本各处）",
                v.name(), nm -> {
            if (nm == null || nm.isBlank() || nm.equals(v.name())) return;
            apply(ScriptEdit.renameVar(src(), v.name(), nm.strip()));
        }));
    }

    private void remove(ScriptEdit.VarAt v) {
        if (!arm.equals(v.name())) {
            arm = v.name();
            setStatus("再点一次「确认删」就删掉 var " + v.name() + "（还在别处用着的话会被拒收）", true);
            clearWidgets();
            init();
            return;
        }
        arm = "";
        apply(ScriptEdit.removeVar(src(), v.name()));
    }

    private void addOne() {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "新建变量", "英文标识符（如 score / hand）",
                "", nm -> {
            if (nm == null || nm.isBlank()) return;
            apply(ScriptEdit.addVar(src(), nm.strip(), "", "0"));
        }));
    }

    /** 提交某一格（初值）：值框 → 那个变量的初值。 */
    private void commit(Object[] b) {
        EditBox box = (EditBox) b[0];
        String id = (String) b[1];
        int which = (Integer) b[2];
        ScriptEdit.VarAt v = scriptVar(id);
        if (v == null) return;
        if (box.getValue().strip().equals(v.init())) return;
        apply(ScriptEdit.setVarInit(src(), id, box.getValue()));
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
        parent.rebuildFromDef();
    }

    // ================= 绘制与交互 =================

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+Z 撤销 · Ctrl+Y（Ctrl+Shift+Z）重做 —— 一行转发，逻辑在 GameEditorScreen
        if (parent.handleHistoryKey(event.key(), event.hasControlDownWithQuirk(), event.hasShiftDown(), 90, 89)) {
            clearWidgets();
            init();
            return true;
        }
        if (event.key() == 257 || event.key() == 335) {              // 回车 = 提交正编辑的那一格
            for (Object[] b : boxes) {
                if (((EditBox) b[0]).isFocused()) {
                    commit(b);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        for (Object[] b : boxes) {                                    // 点别处 = 先提交（别丢字）
            EditBox box = (EditBox) b[0];
            if (!box.isFocused()) continue;
            ScriptEdit.VarAt v = scriptVar((String) b[1]);
            String cur = v == null ? "" : ((Integer) b[2] == 0 ? v.init() : v.vis());
            if (!box.getValue().strip().equals(cur)) {
                commit(b);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (scroll.scroll(dy)) {
            clearWidgets();
            init();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
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
        listY = 74;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 74 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        int[] c = cols();
        g.text(font, Component.literal("变量（脚本里的 var 那几行 · 可写）"
                + (scroll.label().isEmpty() ? "" : "　" + scroll.label())), c[0], 30, 0xFFFFFFFF);
        g.text(font, Component.literal("真源 = 脚本里的 var 声明；这一页改的就是那几行"), c[0], 46, 0xFF909090);
        g.text(font, Component.literal("名字"), c[0] + 24, 62, 0xFF909090);
        g.text(font, Component.literal("初值"), c[0] + c[1] + c[2] / 2 - 10, 62, 0xFF909090);
        // 每行左边自绘：变量名 + 出现在几行（控件盖不住这块 —— 名字列是自绘的）
        List<String> all = slots();
        int y = 74;
        for (int row = 0; row < scroll.rows(); row++) {
            String id = all.get(scroll.index(row));
            ScriptEdit.VarAt v = scriptVar(id);
            boolean sel = id.equals(focus);
            g.text(font, Component.literal(id), c[0] + c[1] + 6 + 2, y + 6, sel ? 0xFFFFD400 : 0xFFFFFFFF);
            // 行尾灰字：脚本里带 @(…) 就在这里标一句（只读）。@own = 只发本人那一项，别跟「带条件」混成一句。
            String mark = v.vis().isEmpty() ? ""
                    : (v.vis().equals("own") ? "只发本人那一项" : "带条件") + "（在脚本里改）";
            g.text(font, Component.literal((mark.isEmpty() ? "" : mark + "　") + useLabel(id, v.line())),
                    c[0] + c[1] + 4 + c[2] + 4 + 48, y + 6, 0xFF7A8090);
            y += 24;
        }
        if (!status.isEmpty()) {
            g.text(font, Component.literal(status), c[0], height - 60, statusBad ? 0xFFFF7070 : 0xFFA0E080);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }


    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
}
