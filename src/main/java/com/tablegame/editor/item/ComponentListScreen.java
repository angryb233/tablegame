package com.tablegame.editor.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.ComponentGroup;
import com.tablegame.core.ComponentModel;
import com.tablegame.editor.IdPickScreen;

/**
 * 「附加类组件的条目」小窗。
 *
 * <p><b>形态</b>：属性段里那一行（例「属性修饰符 · 1 条 ▸」）点开就是这一屏，两个视图：
 * <ul>
 *   <li><b>条目列表</b>（view 0）：一条一行（`1. attack_damage · 6 · add_value`），点条目进字段视图，
 *       行尾 ✕ 删一条，底下【＋ 加一条】【完成】【取消】；条目多了滚轮翻页（{@link ListScroll}）。</li>
 *   <li><b>条目字段</b>（view 1）：一个字段一行 —— 控件由 {@link ComponentGroup.Field#kind()} 定
 *       （数字 / 引用 / 清单 / 文本 = 输入框，真假 = 三态按钮，枚举 = 循环按钮），提示写在框里。</li>
 * </ul>
 *
 * <p><b>值 ⇄ 条目的换算全在 {@link ComponentGroup}</b>（纯逻辑、进自检）：这一屏只管摆控件、收输入，
 * 拿不出来（值是变量 / 语法错）就红字说一声、那个值一字不动。
 *
 * <p>点【完成】把新值交回属性段那一行；真正落盘还是编辑页【完成】那次（{@code ScriptEdit.setDeclBlock}，唯一出口）。
 */
public class ComponentListScreen extends Screen {

    private static final int ROW_H = 22;

    private final Screen parent;
    private final ComponentGroup.Spec spec;
    private final String original;
    private final Consumer<String> onOk;

    /** 现在的条目（认不出原文时为 null ⇒ 只给提示、不给改）。 */
    private List<List<String>> entries;
    /** 正在改的那一条的工作副本（【完成】才写回条目）。 */
    private List<String> editCells = new ArrayList<>();

    private ListScroll scroll = new ListScroll(8);
    private int view;
    private int editIdx = -1;
    private final List<EditBox> boxes = new ArrayList<>();
    /** 字段视图里的下拉（ENUM 那几格）；展开的选项列表要在 super 之后单独画。 */
    private final List<DrawBoardMenuUi.Dropdown> drops = new ArrayList<>();

    private int winW;
    private int winH;
    private int x0;
    private int y0;
    private int rows;

    /**
     * @param parent  完成 / 取消后回哪一屏（那一条属性所在的编辑页）
     * @param componentId 组件 id（要在 {@link ComponentGroup} 表里）
     * @param valueText   那一行现在的值原文
     * @param onOk        完成回调（新值原文）
     */
    public ComponentListScreen(Screen parent, String componentId, String valueText, Consumer<String> onOk) {
        super(Component.literal(ComponentModel.cnOf(componentId)));
        this.parent = parent;
        this.spec = ComponentGroup.specOf(componentId);
        this.original = valueText == null ? "" : valueText;
        this.onOk = onOk;
        this.entries = spec == null ? null : ComponentGroup.parse(spec, original);
    }

    // ---------- 两个视图 ----------

    @Override
    protected void init() {
        boxes.clear();
        drops.clear();
        winW = Math.min(470, width - 24);
        int maxRows = Math.max(2, Math.min(11, (height - 150) / ROW_H));
        if (view == 0 && spec != null && spec.single()) {
            view = 1;                                   // 单对象组件：没有条目那一层，直进字段视图
            editIdx = 0;
            editCells = entries == null || entries.isEmpty() ? blank() : new ArrayList<>(entries.get(0));
        }
        rows = view == 1 ? Math.max(1, spec.fields().size()) : maxRows;
        winH = Math.min(46 + rows * ROW_H + 52, height - 16);
        x0 = width / 2 - winW / 2;
        y0 = height / 2 - winH / 2;
        if (view == 1) {
            buildFields();
        } else {
            buildList();
        }
    }

    private void buildList() {
        int keep = scroll.top();
        scroll = new ListScroll(rows);
        scroll.setTotal(entries == null ? 0 : entries.size());
        scroll.setTop(keep);
        if (entries == null) {
            addRenderableWidget(DrawBoardMenuUi.label(x0 + 10, y0 + 42, winW - 20,
                    "这个值认不出（不是字面量 / 语法错）—— 请用【多行小窗】手改，这里没动它", 0xFFFF9090));
        } else if (entries.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(x0 + 10, y0 + 42, winW - 20,
                    "还没有条目 —— 点下面【＋ 加一条】", 0xFF909090));
        }
        for (int r = 0; r < scroll.rows(); r++) {
            int i = scroll.index(r);
            int y = y0 + 40 + r * ROW_H;
            int bw = winW - 40 - 24;
            addRenderableWidget(DrawBoardMenuUi.button(x0 + 10, y, bw, 18,
                    (i + 1) + ". " + DrawBoardMenuUi.ellipsis(ComponentGroup.summary(entries.get(i)),
                            Math.max(8, (bw - 16) / 6)),
                    () -> openEntry(i)));
            addRenderableWidget(DrawBoardMenuUi.button(x0 + 10 + bw + 4, y, 20, 18, "✕", () -> delEntry(i)));
        }
        int by = y0 + winH - 28;
        if (entries != null) {
            addRenderableWidget(DrawBoardMenuUi.button(x0 + 10, by, 92, 20, "＋ 加一条", this::addEntry));
            addRenderableWidget(DrawBoardMenuUi.button(x0 + winW - 158, by, 70, 20, "完成", this::ok));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x0 + winW - 82, by, 70, 20,
                entries == null ? "关闭" : "取消", this::cancel));
    }

    private void buildFields() {
        int cw = Math.max(90, winW - 148);
        for (int i = 0; i < spec.fields().size(); i++) {
            ComponentGroup.Field f = spec.fields().get(i);
            int y = y0 + 40 + i * 24;
            final int fi = i;
            addRenderableWidget(DrawBoardMenuUi.label(x0 + 10, y + 4, 120,
                    DrawBoardMenuUi.ellipsis(f.name(), 16), 0xFFC8C8D0));
            switch (f.kind()) {
                case BOOL -> addRenderableWidget(DrawBoardMenuUi.button(x0 + 136, y, 80, 18,
                        boolLabel(cellOf(i)), () -> cycleBool(fi)));
                case ENUM -> {                                        // 下拉（点开选，不循环）
                    String[] opts = dropOptions(f);
                    DrawBoardMenuUi.Dropdown d = new DrawBoardMenuUi.Dropdown(x0 + 136, y,
                            Math.max(120, cw), 18, opts, dropIndex(f, cellOf(i)),
                            pick -> setCell(fi, pick == 0 ? "" : opts[pick]));
                    drops.add(d);
                    addRenderableWidget(d);
                }
                default -> {
                    boolean canPick = f.kind() == ComponentGroup.Kind.REF && !f.pick().isEmpty();
                    int bw = canPick ? Math.max(60, cw - 24) : cw;
                    EditBox b = new EditBox(font, x0 + 136, y, bw, 18, Component.literal(f.name()));
                    b.setMaxLength(f.kind() == ComponentGroup.Kind.NUM ? 12 : 220);
                    b.setValue(cellOf(i));
                    int idx = i;
                    b.setResponder(s -> setCell(idx, s));
                    boxes.add(b);
                    addRenderableWidget(b);
                    if (canPick) {                                   // 引用格子：右边一枚 ▾（挑注册表 id）
                        addRenderableWidget(DrawBoardMenuUi.button(x0 + 136 + bw + 2, y, 20, 18, "▾",
                                () -> pickFor(fi, f.pick())));
                    }
                }
            }
        }
        if (!boxes.isEmpty()) {
            setFocused(boxes.get(0));
        }
        int by = y0 + winH - 28;
        boolean single = spec.single();
        addRenderableWidget(DrawBoardMenuUi.button(x0 + winW - 158, by, 70, 20, "完成",
                single ? this::ok : this::doneEntry));       // 单对象：完成即写回（没有列表可回）
        addRenderableWidget(DrawBoardMenuUi.button(x0 + winW - 82, by, 70, 20, "取消",
                single ? this::cancel : this::backToList));
    }

    // ---------- 条目操作 ----------

    /** 点一条：进字段视图（拿一份工作副本改）。 */
    private void openEntry(int i) {
        editIdx = i;
        editCells = new ArrayList<>(entries.get(i));
        view = 1;
        rebuild();
    }

    /** 【＋ 加一条】：加一条空的并**直接进字段视图**（少点一下）。 */
    private void addEntry() {
        if (entries == null) {
            return;
        }
        entries.add(blank());
        openEntry(entries.size() - 1);
    }

    private void delEntry(int i) {
        if (entries != null && i >= 0 && i < entries.size()) {
            entries.remove(i);
            rebuild();
        }
    }

    /** 字段视图【完成】：存回这一条（整条都空着就丢掉 —— 空条目没用）。 */
    private void doneEntry() {
        if (blank(editCells)) {
            entries.remove(editIdx);
        } else {
            entries.set(editIdx, new ArrayList<>(editCells));
        }
        backToList();
    }

    private void backToList() {
        view = 0;
        rebuild();
    }

    private List<String> blank() {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < spec.fields().size(); i++) {
            r.add("");
        }
        return r;
    }

    private static boolean blank(List<String> row) {
        for (String c : row) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cellOf(int i) {
        return i < editCells.size() && editCells.get(i) != null ? editCells.get(i) : "";
    }

    private void setCell(int i, String v) {
        while (editCells.size() <= i) {
            editCells.add("");
        }
        editCells.set(i, v);
    }

    /** 真假那格的**三态**：空（不写这个字段）→ 开 → 关 → 空。 */
    private void cycleBool(int i) {
        String c = cellOf(i).strip();
        setCell(i, c.isEmpty() ? "true" : (c.equalsIgnoreCase("true") ? "false" : ""));
        rebuild();
    }


    private static String boolLabel(String cur) {
        String c = cur == null ? "" : cur.strip();
        return c.isEmpty() ? "(不写)" : (c.equalsIgnoreCase("true") ? "开" : "关");
    }

    /** 下拉的选项：第 0 项固定是「(不写)」（空着就不写这个字段），后面是枚举值。 */
    private static String[] dropOptions(ComponentGroup.Field f) {
        String[] out = new String[f.opts().size() + 1];
        out[0] = "(不写)";
        for (int k = 0; k < f.opts().size(); k++) {
            out[k + 1] = f.opts().get(k);
        }
        return out;
    }

    /** 当前值在下拉里是第几项（空 / 认不出 ⇒ 第 0 项「(不写)」）。 */
    private static int dropIndex(ComponentGroup.Field f, String cur) {
        String c = cur == null ? "" : cur.strip();
        int k = f.opts().indexOf(c);
        return k < 0 ? 0 : k + 1;
    }

    /**
     * 引用格子右边那枚 ▾：开**注册表拾取屏**（{@link IdPickScreen}，候选现读），选中就填进这一格。
     *
     * <p>回来还停在**字段视图**这一条上（那屏的返回目标就是本屏），不用从列表再点一遍。
     */
    private void pickFor(int i, String kind) {
        Minecraft.getInstance().setScreen(IdPickScreen.of(this, kind, spec.cn(), v -> {
            setCell(i, v);
            rebuild();
        }));
    }

    // ---------- 开关 / 返回 ----------

    /** 【完成】：把条目换算成新值交回那一行（值没变时 ComponentGroup 会把原文一字不动交回）。 */
    private void ok() {
        Minecraft.getInstance().setScreen(parent);
        if (entries != null) {
            onOk.accept(ComponentGroup.write(spec, entries, original));
        }
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 就地重建这一屏的控件（条目增删 / 枚举与真假变值之后）。 */
    private void rebuild() {
        clearWidgets();
        init();
    }

    public int view() {
        return view;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                 // Esc：字段视图退回列表；列表视图关窗
            if (view == 1) {
                backToList();
            } else {
                cancel();
            }
            return true;
        }
        if (event.key() == 257 && view == 0) {    // 回车 = 完成
            ok();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 下拉是自绘控件：点它自己（和展开的选项列表）要先给它吃（同 StageVisualScreen 那套口径）
        for (DrawBoardMenuUi.Dropdown d : drops) {
            if (d.handleClick(event.x(), event.y())) {
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (view == 0 && scroll.scroll(dy)) {
            rebuild();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    // ---------- 画 ----------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // ⚠ 面板一定画在 widget 之前（全仓统一写法：先画窗口底 / 标题 / 说明文字，最后才 super
        //    —— super 那一下才把控件画上去）。窗口底画在 super 之后会盖住控件文字。
        g.fill(x0 - 4, y0 - 4, x0 + winW + 4, y0 + winH + 4, 0xE0181828);
        g.centeredText(font, Component.literal(titleText()), width / 2, y0 + 8, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);      // ← 控件（画在最上层）
        for (DrawBoardMenuUi.Dropdown d : drops) {
            d.renderOverlay(g, mouseX, mouseY);                        // 展开的选项列表：在控件之上
        }
    }

    private String titleText() {
        if (spec == null) {
            return "组件";
        }
        if (view == 1) {
            return "条目 " + (editIdx + 1) + " / " + (entries == null ? 1 : entries.size())
                    + " · " + spec.cn() + "（" + spec.id() + "）";
        }
        return spec.cn() + "（" + spec.id() + "）"
                + (entries == null ? " · 认不出" : " · " + entries.size() + " 条");
    }
}
