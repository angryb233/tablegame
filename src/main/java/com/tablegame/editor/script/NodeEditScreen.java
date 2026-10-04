package com.tablegame.editor.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;
import com.tablegame.script.edit.ScriptCard;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.ItemText;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.IdPickScreen;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.pack.AssetStore;
import com.tablegame.script.edit.ScriptGraph;

/**
 * **节点编辑页** —— 可视化里双击一个节点进来，改它那一段脚本。
 * 形态：**有序卡列表**（一条语句一张卡，卡上直接改参数（↑↓ / ✕ / ＋），容器卡可展开到「嵌套到底」）·
 * **三页 + 出口区 + 改名**（阶段（改名 + 出口）/ 入口 / 函数（改名）；部件走同一套 —— 它就是「函数那一页」同构，零新逻辑）·
 * **＋新增动作卡**（搜索清单打字即筛，按动词表的默认值插进块里）· **撤销/重做**走工具条（全局，不做屏内单一撤销位）。
 * **真源永远是文本**：这一页只把卡上的改动翻成一小段文本，落盘一率走 {@link GameEditorScreen#applyScript}（先真源解析 → 通过才换进 def 并静默保存）——
 * 页面自己**不是**第二份真源（卡是文本的投影，文本改完这张卡就重建）。
 */
public class NodeEditScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    /** 一行的高度（卡与子块标题**统一**高：翻页算术简单，视觉也整齐）。 */
    private static final int ROW_H = 38;
    /** 内容区左起（左栏之后）。 */
    private static final int PAD = 8;
    /** 卡片区上沿。 */
    private static final int LIST_TOP = 62;
    /** 缩进宽度 / 最多这么深（防脚本有怪嵌套把递归拉爆）。 */
    private static final int IND = 14, MAX_IND = 6;

    private final GameEditorScreen parent;
    /** 返回到哪：默认老口径（脚本图）；从对象编辑页的事件行进来时 = 那条对象的编辑页。 */
    private Screen backTo;
    /** 只显示这一条事件（null = 整块都显示）—— 见 {@code of(…, ScriptEdit.Event)}。 */
    private ScriptEdit.Event only;
    /**
     * 只显示**这几段**里的顶层卡（空 = 不过滤）—— 区域属性行跳进来用它（「只看这一条属性」）。
     *
     */
    private java.util.List<int[]> onlySpans = java.util.List.of();

    /** 节点类型（{@link com.tablegame.script.edit.ScriptGraph} 的 KIND_*）。 */
    private final int kind;
    private final String key;
    /** 节点块首行（1 起）——本页所有语句锚点都从它算。 */
    private final int line;
    private String name;

    // ---- 状态（init 重建不丢）----
    /** 0 = 卡片列表；1 = 候选清单（段3 选动词 / 出口选阶段）。 */
    private int view;
    /** 候选清单要干嘛：0 = ＋动作卡；1 = ＋一条出口。 */
    private int pickUse;
    /** ＋ 落在哪个块（行 + 行内第几个花括号）。 */
    private int pickBlock, pickNth;
    private String query = "";
    private String status = "";
    private boolean statusBad;
    /** 收起的容器卡（key = 卡的行.序号）。 */
    private final Set<String> collapsed = new HashSet<>();
    private ListScroll scroll = new ListScroll(6);
    /** 参数 ▾ 的下拉菜单（这种字段一律下拉选，不再点击循环）。 */
    private final AssetMenu paramMenu = new AssetMenu();

    // ---- 条件小窗（方案 A）----
    /** 小窗开着时：目标容器卡（null = 没开）+ 各段 [左值, 运算符, 右值]。 */
    private ScriptCard.Card condCard;
    private final java.util.List<String[]> condParts = new java.util.ArrayList<>();
    /** 「条件 ▸ 编辑」的点击区（drawRow 每帧重建）：[y, x, w, blockLine, nth, index]。 */
    private final java.util.List<int[]> condClicks = new java.util.ArrayList<>();
    /** 正在给第几段挑左值（-1 = 没在挑；当前其实没单独用，菜单回调直接带段号 —— 留作以后多级挑选）。 */
    private int condPickPart = -1;
    /** 条件小窗里的下拉（左值 / 运算符）—— 与 paramMenu 分开，免得互相顶掉。 */
    private final AssetMenu condMenu = new AssetMenu();
    /** 本页要摆/要画的行（每次 init 重算）。 */
    private final List<Row> rows = new ArrayList<>();
    /** 子块 → 它的父块（「移出本层」用）。key = "行.nth"。 */
    private final Map<String, int[]> parents = new HashMap<>();
    /** 本页的控件：参数框（回车提交时要知道它属于哪张卡的哪个参数）。 */
    private final List<Box> boxes = new ArrayList<>();
    /** 画语句的实参框（自绘行里的只读文字点击区：第几行 → 跳过去）。 */
    private final List<int[]> jumps = new ArrayList<>();
    private EditBox pickBox;
    /** 当前每页几行（变了才换 ListScroll，换掉会丢掉翻到第几页）。 */
    private int pageRows = -1;

    /** 一行：一张卡，或一个「子块标题」。 */
    private record Row(boolean head, String label, ScriptCard.Card card, int indent, int blockLine, int nth) { }

    /** 一个参数框：控件 + 它属于哪张卡的哪个参数。 */
    private record Box(EditBox box, ScriptCard.Card card, int argIdx) { }

    private NodeEditScreen(GameEditorScreen parent, int kind, String key, String name, int line) {
        super(Component.literal(name));
        this.parent = parent;
        this.kind = kind;
        this.key = key;
        this.name = strip(name);
        this.line = line;
    }

    /** 图上的标签带类型前缀（「阶段 play」这类）——页头只要名字那截。 */
    private static String strip(String label) {
        if (label == null) return "";
        for (String p : new String[]{"阶段 ", "函数 ", "部件 ", "入口 ", "事件 ", "变量 ", "舞台 ", "组件 "}) {
            if (label.startsWith(p)) return label.substring(p.length());
        }
        return label;
    }

    /** 按节点类型造一屏（占位页仍留给段4/段5 的那几类）。 */
    public static NodeEditScreen of(GameEditorScreen parent, String key, String label, int kind, int line) {
        String name = label == null || label.isEmpty() ? key : label;
        return new NodeEditScreen(parent, kind, key, name, line);
    }

    /**
     * 带**返回目标**进入（从对象编辑页的事件行走这条路 —— 返回要回到**那条对象的
     * 编辑页**，而不是老口径的脚本图）。
     */
    public static NodeEditScreen of(GameEditorScreen parent, Screen backTo, String key, String label,
            int kind, int line) {
        NodeEditScreen s = of(parent, key, label, kind, line);
        s.backTo = backTo;
        return s;
    }

    /**
     * 带上「只看这几段」进入（区域属性行的【编辑】走它）—— 回来的目标仍是**属性页**。
     *
     * <p>与 {@link #of(GameEditorScreen, Screen, String, String, int, int, ScriptEdit.Event)} 同款，
     * 只是过滤判据换成行区间（同一条属性可能占两段：顶层的槽位 + 事件块里的分支）。
     */
    public static NodeEditScreen ofSpans(GameEditorScreen parent, Screen backTo, String key, String label,
            int kind, int line, java.util.List<int[]> spans) {
        NodeEditScreen s = of(parent, backTo, key, label, kind, line);
        s.onlySpans = spans == null ? java.util.List.of() : spans;
        return s;
    }

    /**
     * 带**事件过滤**进入——
     * 顶层的卡只留条件正好等于那条事件守卫的 {@code if}（同一块里别的方块的事件不显示）；页面上给【看整块】。
     */
    public static NodeEditScreen of(GameEditorScreen parent, Screen backTo, String key, String label,
            int kind, int line, ScriptEdit.Event only) {
        NodeEditScreen s = of(parent, backTo, key, label, kind, line);
        s.only = only;
        return s;
    }

    /** 这一页能不能编（阶段 / 入口 / 函数 / 部件）——其余仍是占位（段4 变量入口 · 段5 舞台节点页）。 */
    private boolean editable() {
        return kind == com.tablegame.script.edit.ScriptGraph.KIND_STAGE || kind == com.tablegame.script.edit.ScriptGraph.KIND_ON
                || kind == com.tablegame.script.edit.ScriptGraph.KIND_FUNC || kind == com.tablegame.script.edit.ScriptGraph.KIND_PART;
    }

    private String src() {
        return parent.def.script();
    }

    /** 页头那一行写的字。 */
    private String title() {
        return switch (kind) {
            case com.tablegame.script.edit.ScriptGraph.KIND_STAGE -> "阶段编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_ON -> "事件编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_FUNC -> "函数编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_PART -> "部件编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_SCREEN -> "舞台编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_COMPONENT -> "组件编辑";
            case com.tablegame.script.edit.ScriptGraph.KIND_VAR -> "变量编辑";
            default -> "节点编辑";
        };
    }

    /** 这个节点负责什么（占位页那句人话；可编的页写在页头下面一行）。 */
    private String duty() {
        return switch (kind) {
            case com.tablegame.script.edit.ScriptGraph.KIND_SCREEN -> "一块界面（画布）：这台机器上「要显示什么」的声明。";
            case com.tablegame.script.edit.ScriptGraph.KIND_COMPONENT -> "舞台里的一个元素：画什么、画在哪儿。";
            case com.tablegame.script.edit.ScriptGraph.KIND_VAR -> "一块数据的声明：改名 / 初值 / 每人一份。";
            default -> "";
        };
    }

    // ================= 布局：把脚本读成行清单 =================

    private String ckey(ScriptCard.Card c) {
        return c.line() + "." + c.index();
    }

    /** 从节点的块出发，深度优先摊成一份行清单（收起的容器卡不展开）。 */
    private void buildRows() {
        rows.clear();
        parents.clear();
        walk(line, 0, 0);
        if ((only != null || !onlySpans.isEmpty()) && rows.isEmpty()) {   // 对不上（脚本可能手改过）：别摆一个空屏
            walk(line, 0, 0, true);
            status = only != null
                    ? "没找到「" + only.label() + "」那条分支（脚本可能手改过）—— 先把整块列出来"
                    : "没找到那一段（脚本可能手改过或标记被删）—— 先把整块列出来";
            statusBad = true;
        }
    }

    /** 顶层这张卡要不要留（事件行进来 = 按守卫；属性行进来 = 按行区间；都没设 = 全留）。 */
    private boolean keepTop(ScriptCard.Card c) {
        if (only != null) return ScriptCard.isGuardCard(c, only.guard());
        if (onlySpans.isEmpty()) return true;
        for (int[] sp : onlySpans) {
            if (c.line() >= sp[0] && c.line() <= sp[1]) return true;
        }
        return false;
    }

    private void walk(int blockLine, int nth, int indent) {
        walk(blockLine, nth, indent, false);
    }

    /**
     * @param all 不看事件过滤（只有兜底那一次传 true）
     */
    private void walk(int blockLine, int nth, int indent, boolean all) {
        if (indent > MAX_IND) return;
        boolean top = blockLine == line && nth == 0;         // 过滤只在本屏**顶层**生效（那条分支里的卡全留）
        for (ScriptCard.Card c : ScriptCard.cardsOf(src(), blockLine, nth)) {
            if (top && !all && !keepTop(c)) {
                continue;
            }
            rows.add(new Row(false, "", c, indent, blockLine, nth));
            if (!ScriptCard.container(c) || collapsed.contains(ckey(c))) continue;
            for (ScriptCard.Block b : c.blocks()) {
                rows.add(new Row(true, b.label(), null, indent, b.line(), b.nth()));
                parents.put(b.line() + "." + b.nth(), new int[]{blockLine, nth});
                walk(b.line(), b.nth(), indent + 1, all);
            }
        }
    }

    private int contentX() {
        return EditorRail.LEFT + PAD;
    }

    private int contentW() {
        return width - contentX() - PAD;
    }

    private int listH() {
        return height - LIST_TOP - 42;
    }

    // ================= init：摆控件 =================

    @Override
    protected void init() {
        clearWidgets();
        boxes.clear();
        jumps.clear();
        EditorRail.build(parent, "可视化", this::addRenderableWidget);

        if (!editable()) {                                   // 占位页（段4 / 段5 接手）
            addRenderableWidget(DrawBoardMenuUi.button(width / 2 - 70, height - 40, 140, 24, "返回可视化", this::back));
            return;
        }
        buildRows();                                         // 先有行清单，才谈得上给本页的行摆控件
        int x = contentX(), w = contentW();
        int ty = 24;

        if (view == 1) {                                     // 段3：候选清单（搜索框 + 自绘清单行）
            pickBox = new EditBox(font, x, ty, Math.min(240, w), 18, Component.literal("搜索"));
            pickBox.setMaxLength(32);
            pickBox.setValue(query);
            pickBox.setHint(Component.literal(kindHint()));
            pickBox.setResponder(s -> {
                query = s;
                refreshPick();
            });
            addRenderableWidget(pickBox);
            setFocused(pickBox);
            addRenderableWidget(DrawBoardMenuUi.button(x + Math.min(240, w) + 8, ty, 60, 18, "取消",
                    () -> switchView(0)));
            return;
        }

        // 工具条：改名（阶段 / 函数）· ＋加一张卡 · 撤销
        int bx = x;
        if (kind == com.tablegame.script.edit.ScriptGraph.KIND_STAGE || kind == com.tablegame.script.edit.ScriptGraph.KIND_FUNC) {
            addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 60, 18, "改名", this::rename));
            bx += 66;
        }
        addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 96, 18, "＋ 加一张卡", () -> openPick(0, line, 0)));
        bx += 102;
        // 段6：撤销 / 重做走**全局历史**（栈挂在 GameEditorScreen.applyScript 那个唯一出口上）——
        // 没得撤 / 没得重做时置灰（null 动作 = 不可点），两个键随时看得见。
        addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 60, 18, "↶ 撤销",
                parent.canUndo() ? parent::undo : null));
        bx += 66;
        addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 60, 18, "↷ 重做",
                parent.canRedo() ? parent::redo : null));
        bx += 66;
        if (only != null || !onlySpans.isEmpty()) {          // 过滤着进来的：明示「只看这一条」+ 给一条退路
            bx += 4;
            addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 80, 18,
                    only != null ? "只看本方块 ✔" : "只看这一条 ✔", null));
            bx += 86;
            addRenderableWidget(DrawBoardMenuUi.button(bx, ty, 60, 18, "看整块", this::showWhole));
            bx += 66;
        }
        // 阶段页的出口区：一条出口一个小按钮（点 = 删掉那条 goto），末尾一个【＋ 出口】
        if (kind == com.tablegame.script.edit.ScriptGraph.KIND_STAGE) {
            int ex = bx + 6;
            for (String t : exits()) {
                if (ex > width - 130) break;
                final String to = t;
                addRenderableWidget(DrawBoardMenuUi.button(ex, ty, font.width(t) + 22, 18, "✕ " + t,
                        () -> removeExit(to)));
                ex += font.width(t) + 26;
            }
            addRenderableWidget(DrawBoardMenuUi.button(ex, ty, 74, 18, "＋ 出口", () -> openPick(1, line, 0)));
        }

        // 本页的卡：只给「本页看得见」的行摆控件（行高统一，翻页见 ListScroll）
        int want = Math.max(1, listH() / ROW_H);
        if (want != pageRows) {                              // 只有在窗口尺寸变了时才换滚轮对象，
            scroll = new ListScroll(want);                   // 否则每次重建都会跳回第一页
            pageRows = want;
        }
        scroll.setTotal(rows.size());
        for (int i = 0; i < scroll.rows(); i++) {
            Row r = rows.get(scroll.index(i));
            int ry = LIST_TOP + i * ROW_H;
            if (r.head()) {                                        // 容器子块：往它里面加卡（Q5 嵌套到底）
                addRenderableWidget(DrawBoardMenuUi.button(contentX() + r.indent() * IND + 150, ry + 1, 70, 14,
                        "＋ 加卡", () -> openPick(0, r.blockLine(), r.nth())));
                continue;
            }
            if (r.card() == null) continue;
            buildCard(r, ry);
        }
    }

    private String kindHint() {
        return pickUse == 1 ? "搜阶段名" : "搜动词或名字（如 说话 / give / 传送）";
    }

    /**
     * 给一张卡摆控件：右侧是 ↑↓✕（＋「移出」「展开」，从右往左**顺序**摆，不重叠），
     * 参数在标题下一行（按格数等分宽度，不折行 —— 折行会把行高算法搞乱）。
     */
    private void buildCard(Row r, int y) {
        ScriptCard.Card c = r.card();
        int x = contentX() + r.indent() * IND, w = contentW() - r.indent() * IND;
        int by = y + 18;
        boolean known = c.known();
        if (known) {
            int bx = x + w - 22;                                  // 从右边缘往左摆
            addRenderableWidget(DrawBoardMenuUi.button(bx, y + 2, 20, 14, "✕", () -> remove(c)));
            int n = ScriptCard.cardsOf(src(), c.blockLine(), c.nth()).size();
            if (c.index() < n - 1) {
                addRenderableWidget(DrawBoardMenuUi.button(bx - 24, y + 2, 20, 14, "↓", () -> move(c, 1)));
                bx -= 24;
            }
            if (c.index() > 0) {
                addRenderableWidget(DrawBoardMenuUi.button(bx - 24, y + 2, 20, 14, "↑", () -> move(c, -1)));
                bx -= 24;
            }
            int[] par = parents.get(c.blockLine() + "." + c.nth());
            if (par != null) {                                   // 嵌在容器里的卡：能挪出去（A6 的跨层那半）
                addRenderableWidget(DrawBoardMenuUi.button(bx - 40, y + 2, 38, 14, "移出", () -> moveTo(c, par)));
                bx -= 42;
            }
            if (ScriptCard.container(c)) {
                boolean col = collapsed.contains(ckey(c));
                addRenderableWidget(DrawBoardMenuUi.button(bx - 34, y + 2, 32, 14, col ? "展开" : "收起",
                        () -> {
                            if (col) collapsed.remove(ckey(c));
                            else collapsed.add(ckey(c));
                            rebuild();
                        }));
                bx -= 36;
            }
        }
        jumps.add(new int[]{y + 2, x, Math.max(30, w - 110), c.line(), -1});   // 点卡头 = 跳脚本那一行（A7）
        List<ScriptCard.Arg> as = ScriptCard.args(c);
        int n = Math.max(1, as.size());
        // ① 参数行占满整行（↑↓✕ 在上一行），按「槽内开销」摆 —— 结构上不可能与邻居重叠。
        // 算路与「只读视图」共用同一处（{@link #slotOverhead} / {@link #minRowWidth}），两份必然分叉。
        int budget = Math.max(w - 4, minRowWidth(as));
        int ax = x + 2;
        for (int i = 0; i < as.size(); i++) {
            ScriptCard.Arg a = as.get(i);
            final int ai = i;                                        // lambda 里要用它（循环变量本身不是 final）
            int slot = Math.max(28 + slotOverhead(a), budget / n);
            int aw;
            switch (a.control()) {
                case ScriptEdit.CTL_READ -> {
                    aw = slot - slotOverhead(a);
                    jumps.add(new int[]{by, ax, aw, c.line(), i});   // 只读：点它跳脚本行
                }
                case ScriptEdit.CTL_NUM -> {
                    aw = slot - slotOverhead(a);
                    EditBox b = textBox(ax + 16, by, aw, a.text(), c, i);
                    addRenderableWidget(b);
                    addRenderableWidget(DrawBoardMenuUi.button(ax, by, 16, 16, "−", () -> bump(b, -1)));
                    addRenderableWidget(DrawBoardMenuUi.button(ax + 16 + aw + 6, by, 16, 16, "＋", () -> bump(b, 1)));
                }
                case ScriptEdit.CTL_PICK -> {
                    aw = slot - slotOverhead(a);
                    EditBox b = textBox(ax, by, aw, a.text(), c, i);
                    addRenderableWidget(b);
                    // ▾ = **下拉一列候选让你挑**（这种字段一律下拉，不要「点一下换个值」的循环）。
                    final double mdx = ax + aw + 2, mdy = by + 18;   // ax 后面还会挪，lambda 只能捕 final
                    addRenderableWidget(DrawBoardMenuUi.button(ax + aw + 2, by, 20, 16, "▾",
                            () -> pickMenu(c, ai, b, mdx, mdy)));
                }
                default -> {
                    aw = slot - slotOverhead(a);
                    addRenderableWidget(textBox(ax, by, aw, a.text(), c, i));
                }
            }
            ax += slot;
        }
    }

    /**
     * 一个参数位的**固定开销**（不含输入框本身，①）：数字位 = −16 + 缝 2 + 缝 2 + ＋16 = 38；
     * 下拉位 = ▾20 + 缝 8 = 28；只读 / 文本位 = 6（行末缝）。
     */
    private static int slotOverhead(ScriptCard.Arg a) {
        return ScriptEdit.CTL_NUM.equals(a.control()) ? 38
                : (ScriptEdit.CTL_PICK.equals(a.control()) ? 28 : 6);
    }

    /** 这些参数位排下来**至少**要多少宽（每格框保底 28）—— 极端多参数时按它放开一行，别把框缩到看不见。 */
    private static int minRowWidth(List<ScriptCard.Arg> as) {
        int need = 0;
        for (ScriptCard.Arg a : as) {
            need += 28 + slotOverhead(a);
        }
        return need;
    }

    private EditBox textBox(int x, int y, int w, String val, ScriptCard.Card c, int argIdx) {
        EditBox b = new EditBox(font, x, y, Math.max(28, w), 16, Component.literal("参数"));
        b.setMaxLength(96);
        b.setValue(val);
        b.setResponder(s -> { });                                  // 值在回车/点别处时提交（见 keyPressed）
        boxes.add(new Box(b, c, argIdx));
        return b;
    }

    /** 【＋/－】按钮：把**这个框**的数字加减 1（空/非数字当 0），改完立刻提交。 */
    private void bump(EditBox b, int d) {
        double v = 0;
        try {
            v = Double.parseDouble(b.getValue().strip());
        } catch (Exception ignored) { }
        double nv = v + d;
        String t = Math.abs(nv - Math.rint(nv)) < 1e-9 ? String.valueOf((long) nv) : String.valueOf(nv);
        b.setValue(t);
        for (Box bx : boxes) {
            if (bx.box() == b) {
                commit(bx, t);
                return;
            }
        }
    }

    /**
     * 候选▾ 的清单：先用 {@link ScriptEdit#candidates}（从脚本文本能算出来的：阶段 / 画布 / 部件 / 函数 / 变量 / npc…），
     * 再补**外部来源**那两类 —— 它们的候选来自**本项目档里的对象**，根本不在脚本文本里：
     * {@code SRC_BLOCK} → 方块对象的基底原版方块 id（{@code set_block} / {@code fill} 认 id；⚠ 方块**还不支持** {@code @名字} 引用 —— {@code blockKeyId} 只剥属性、不认 @）。
     * {@code SRC_ITEM} → {@code @游戏名/资产名} 那种**引用写法**（自定义物品的名字与描述只有这条引用带得过去）· {@code SRC_ENTITY} → 同一种 @ 写法（候选 = 本项目实体对象；{@code spawn_mob} 认它）。
     */
    private List<String> candidatesFor(String source) {
        List<String> cs = new ArrayList<>(ScriptEdit.candidates(src(), source));
        boolean block = ScriptEdit.SRC_BLOCK.equals(source);
        boolean ref = ScriptEdit.SRC_ITEM.equals(source) || ScriptEdit.SRC_ENTITY.equals(source);   // 走 @ 引用那两类
        if (!block && !ref) {
            return cs;
        }
        String want = block ? AssetStore.KIND_BLOCK
                : (ScriptEdit.SRC_ENTITY.equals(source) ? AssetStore.KIND_ENTITY : AssetStore.KIND_ITEM);
        var as = parent.def.assets();
        if (as == null) {
            return cs;                                     // 老档没有 assets 段：照旧手打
        }
        Set<String> decl = block ? Set.of() : ScriptEdit.declaredNames(src());   // 物品/实体候选：只认声明过的资产名
        for (GameDefinition.AssetDef a : as) {
            String v = "";
            if (want.equals(a.kind()) && a.base() != null && !a.base().isEmpty()) {
                if (block) {
                    v = a.base();                          // 方块位给的是**原版方块 id**（与资产名无关，老条目也能用）
                } else if (decl.contains(a.ref())) {
                    v = a.ref();                           // 期7：引用一律给**资产名**（不再拼 @游戏名/）
                }
            }
            if (!v.isEmpty() && !cs.contains(v)) {
                cs.add(v);
            }
        }
        return cs;
    }

    /**
     * 候选 ▾ → **下拉一列候选让你挑**。
     *
     * <p>挑中的值要过一遍 {@link ScriptEdit#asLiteral}：字面量类候选（画布名 / 游戏模式 / 方块 id …）
     * 带引号，人名与变量名不带 —— 否则写进去就成了变量名（引擎报「没有这个名字」）。
     */
    private void pickMenu(ScriptCard.Card c, int argIdx, EditBox b, double anchorX, double anchorY) {
        List<ScriptCard.Arg> as = ScriptCard.args(c);
        if (argIdx >= as.size()) return;
        String source = as.get(argIdx).source();
        // 音效 id 的候选 = **原版音效注册表**（可搜清单）—— 这类候选在客户端注册表里，既不在脚本
        // 文本里、也不是档里的资产 ⇒ 走 IdPickScreen 那一档（它的 `sound_event` 早就支持）。
        if (ScriptEdit.SRC_SOUND.equals(source)) {
            Minecraft.getInstance().setScreen(IdPickScreen.of(this, "sound_event", "音效", v -> {
                Box own = boxOf(b);
                if (own == null) return;
                b.setValue(ScriptEdit.asLiteral(source, v));
                commit(own, b.getValue());
            }));
            return;
        }
        List<String> cs = candidatesFor(source);
        if (cs.isEmpty()) {
            setStatus("这个参数现在没有候选 —— 直接手打也行", true);
            return;
        }
        // 检索提示串（③）：候选是资产名时把**显示名**并排给菜单 —— 打「镐」也能筛出 pickaxe*。
        List<String> hs = new ArrayList<>();
        var asDefs = parent.def.assets();
        for (String v : cs) {
            String h = v;
            if (asDefs != null) {
                for (GameDefinition.AssetDef a : asDefs) {
                    if (v.equals(a.ref())) {
                        h = a.name();
                        break;
                    }
                }
            }
            hs.add(h);
        }
        List<Runnable> acts = new ArrayList<>();
        for (String v : cs) {
            acts.add(() -> {
                Box own = boxOf(b);
                if (own == null) return;
                b.setValue(ScriptEdit.asLiteral(source, v));    // 写入只走这一处（含引号规则）
                commit(own, b.getValue());
            });
        }
        paramMenu.open(width, height, anchorX, anchorY, cs, acts, hs);
    }

    // ================= 条件小窗 =================
    // 点容器卡行尾的「条件 ▸ 编辑」→ 小窗：每个 && 段一行「左值 ▾ 运算符 ▾ 右值」，＋加一段 / ✕ 删一段；提交 = 用 && 拼回去、走 ScriptCard.setArg 改 if 卡的第 0 个实参。
    // in_area(...) 这类**函数调用形态**表达不了 ⇒ 小窗底部一行「复杂的去文本页改」指路（留逃生口）。

    private static final String[] OPS = {"==", "!=", ">", "<", ">=", "<="};
    /** 左值候选：常用内建值（能数的都在这；手打栏照旧能填任意表达式）。 */
    private static final String[] LEFTS = {"actor", "block", "edge", "edge_area", "hand", "hand_asset",
            "sneak", "rclick", "bx", "by", "bz", "etype", "eid", "pick", "input"};

    /** 打开小窗：把这条 if 的条件按 && 切成段（每段「左值 运算符 右值」，切不开就整段当左值、空运算符）。 */
    private void openCond(int blockLine, int nth, int index) {
        java.util.List<ScriptCard.Card> cs = ScriptCard.cardsOf(src(), blockLine, nth);
        if (index < 0 || index >= cs.size()) return;
        ScriptCard.Card c = cs.get(index);
        List<ScriptCard.Arg> as = ScriptCard.args(c);
        String cond = as.isEmpty() ? "" : as.get(0).text();
        condCard = c;
        condParts.clear();
        condPickPart = -1;
        for (String seg : cond.split("&&")) {
            String t = seg.strip();
            String[] p3 = new String[]{t, "", ""};
            for (String op : OPS) {
                int k = t.indexOf(op);
                if (k > 0 && (p3[1].isEmpty() || op.length() > p3[1].length())) {
                    p3[0] = t.substring(0, k).strip();
                    p3[1] = op;
                    p3[2] = t.substring(k + op.length()).strip();
                }
            }
            condParts.add(p3);
        }
        if (condParts.isEmpty()) condParts.add(new String[]{"", "==", ""});
        rebuild();
    }

    private void closeCond() {
        condCard = null;
        condParts.clear();
        condPickPart = -1;
        rebuild();
    }

    /**
     * 小窗的点击（鼠标坐标）：返回 true = 吃掉了。
     * 布局（每段一行 y = 70 + i*26）：[左值框][▾][运算符▾][右值框][✕]；末行「＋ 再加一条」「完成」「去文本页」。
     */
    private boolean condClick(double mx, double my) {
        int x = contentX(), w = contentW();
        if (mx < x || mx >= x + w) return false;
        // 段行
        for (int i = 0; i < condParts.size(); i++) {
            final int seg = i;                                       // lambda 捕段号（i 是循环变量）
            int y = 70 + i * 26;
            if (my < y || my >= y + 22) continue;
            String[] p3 = condParts.get(i);
            if (mx >= x + 8 && mx < x + 8 + 26) {                       // 左值 ▾
                condPickPart = i;
                List<String> ls = new java.util.ArrayList<>();
                for (String lv : LEFTS) ls.add(lv);
                condMenu.open(width, height, x + 8, y + 22, ls,
                        ls.stream().map(v -> (Runnable) () -> setPart(seg, 0, v)).collect(java.util.stream.Collectors.toList()));
                return true;
            }
            if (mx >= x + 150 && mx < x + 186) {                        // 运算符 ▾
                List<String> ls = List.of(OPS);
                condMenu.open(width, height, x + 150, y + 22, ls,
                        ls.stream().map(v -> (Runnable) () -> setPart(seg, 1, v)).collect(java.util.stream.Collectors.toList()));
                return true;
            }
            if (mx >= x + w - 30 && mx < x + w - 8) {                   // ✕ 删段
                if (condParts.size() > 1) condParts.remove(i);
                rebuild();
                return true;
            }
        }
        int by = 70 + condParts.size() * 26 + 6;
        if (my >= by && my < by + 20) {
            if (mx >= x + 8 && mx < x + 108) {                          // ＋ 再加一条
                condParts.add(new String[]{"", "==", ""});
                rebuild();
                return true;
            }
            if (mx >= x + 118 && mx < x + 198) {                        // 完成：拼回去提交
                StringBuilder sb = new StringBuilder();
                for (String[] p3 : condParts) {
                    String seg = (p3[0].strip() + (p3[1].isEmpty() ? "" : " " + p3[1] + " " + p3[2].strip())).strip();
                    if (seg.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" && ");
                    sb.append(seg);
                }
                if (sb.length() == 0) {
                    setStatus("条件不能是空的（删条件请去文本页删这一句）", true);
                    return true;
                }
                ScriptEdit.Result r = ScriptCard.setArg(src(), condCard.blockLine(), condCard.nth(),
                        condCard.index(), 0, sb.toString());
                parent.applyScript(r.text());               // 唯一出口（真源解析 → 换 def → 静默保存 → 记撤销）
                closeCond();
                return true;
            }
            if (mx >= x + 208 && mx < x + 308) {                        // 去文本页（逃生口）
                Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, condCard.line()));
                condCard = null;
                return true;
            }
        }
        closeCond();                                                    // 点小窗外 = 关掉（不提交）
        return true;
    }

    private void setPart(int i, int which, String v) {
        condParts.get(i)[which] = v;
        rebuild();
    }

    /** 条件小窗的绘制（extractRenderState 末尾调 —— 画在最上层）。 */
    private void drawCond(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (condCard == null) return;
        int x = contentX(), w = contentW();
        g.fill(x, 46, x + w, 70 + condParts.size() * 26 + 34, 0xF0141820);
        g.outline(x, 46, w, 70 + condParts.size() * 26 + 34 - 46 + 34, 0xFF606878);
        g.text(font, Component.literal("条件（用 && 组合多段）"), x + 8, 50, 0xFFE0E0E0);
        for (int i = 0; i < condParts.size(); i++) {
            int y = 70 + i * 26;
            String[] p3 = condParts.get(i);
            g.fill(x + 8, y, x + 34, y + 20, 0xFF2E3138);               // 左值 ▾
            g.text(font, Component.literal("▾"), x + 16, y + 6, 0xFFC0C0C0);
            g.text(font, Component.literal(DrawBoardMenuUi.ellipsis(p3[0], 16)), x + 40, y + 6, 0xFFD0C080);
            g.fill(x + 150, y, x + 186, y + 20, 0xFF2E3138);            // 运算符 ▾
            g.text(font, Component.literal(p3[1].isEmpty() ? "▾" : p3[1]), x + 156, y + 6, 0xFFC0C0C0);
            g.text(font, Component.literal(DrawBoardMenuUi.ellipsis(p3[2], 22)), x + 192, y + 6, 0xFFD0C080);
            g.fill(x + w - 30, y, x + w - 8, y + 20, 0xFF3A2226);       // ✕
            g.text(font, Component.literal("✕"), x + w - 24, y + 6, 0xFFFF9090);
            g.text(font, Component.literal("第 " + (i + 1) + " 段"), x + 8, y - 1 < 0 ? y : y, 0xFF606878);
        }
        int by = 70 + condParts.size() * 26 + 6;
        g.fill(x + 8, by, x + 108, by + 20, 0xFF2A3A2A);                // ＋ 再加一条
        g.text(font, Component.literal("＋ 再加一条"), x + 16, by + 6, 0xFF90E090);
        g.fill(x + 118, by, x + 198, by + 20, 0xFF2A3444);              // 完成
        g.text(font, Component.literal("完成"), x + 146, by + 6, 0xFFB0D0FF);
        g.text(font, Component.literal("去文本页改（复杂条件）"), x + 208, by + 6, 0xFF909090);
    }

    /** 这个控件属于哪张卡的哪个参数（控件列表是我们的，反查安全）。 */
    private Box boxOf(EditBox e) {
        for (Box b : boxes) {
            if (b.box() == e) return b;
        }
        return null;
    }

    // ================= 动作：都经 applyScript 一条出口 =================

    private void back() {
        Minecraft.getInstance().setScreen(backTo != null ? backTo : new ScriptCanvasScreen(parent));
    }

    /** 【看整块】：去掉事件过滤，重开本页（返回目标照旧）。 */
    private void showWhole() {
        Minecraft.getInstance().setScreen(NodeEditScreen.of(parent, backTo, key, name, kind, line));
    }

    private void switchView(int v) {
        view = v;
        query = "";
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    /** 真源写回的唯一出口（先解析验证；拒收 = 原文原样 + 底栏红字）。 */
    private void apply(ScriptEdit.Result r, String extra) {
        if (r.text().equals(src())) {
            setStatus(r.note(), true);
            return;
        }
        String err = parent.applyScript(r.text());
        if (!err.isEmpty()) {
            setStatus(err, true);
            return;
        }
        // 段6：不再自己记撤销位 —— GameEditorScreen.applyScript 一处记账，全局可撤
        setStatus(r.note() + (extra == null ? "" : extra), false);
    }

    private void setStatus(String s, boolean bad) {
        status = s;
        statusBad = bad;
        if (bad) {
            DrawBoardMenuUi.msg("[脚本] " + s);
        }
    }

    /** 改一个参数（回车提交 / 候选▾ / ± 都走它）。 */
    private void commit(Box b, String text) {
        ScriptCard.Card c = b.card();
        apply(ScriptCard.setArg(src(), c.blockLine(), c.nth(), c.index(), b.argIdx(), text), null);
    }

    private void commit(Box b) {
        commit(b, b.box().getValue());
    }

    private void remove(ScriptCard.Card c) {
        apply(ScriptEdit.removeStmt(src(), c.blockLine(), c.nth(), c.index()), "（可撤销）");
        rebuild();
    }

    private void move(ScriptCard.Card c, int d) {
        apply(ScriptEdit.moveStmt(src(), c.blockLine(), c.nth(), c.index(), d), null);
        rebuild();
    }

    private void moveTo(ScriptCard.Card c, int[] target) {
        apply(ScriptEdit.moveStmtTo(src(), c.blockLine(), c.nth(), c.index(), target[0], target[1]), "（挪出本层）");
        rebuild();
    }



    /** 改名（Q7：全链直接改，底栏报「已连带改 N 处」）。 */
    private void rename() {
        boolean stage = kind == com.tablegame.script.edit.ScriptGraph.KIND_STAGE;
        String old = oldName();
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, stage ? "改阶段名" : "改函数名",
                stage ? "阶段名（英文标识符）" : "函数名（英文标识符）", old, nm -> {
            if (nm == null || nm.isBlank() || nm.equals(old)) return;
            ScriptEdit.Result r = stage ? ScriptEdit.renameStage(src(), old, nm.strip())
                    : ScriptEdit.renameFunc(src(), old, nm.strip());
            if (r.text().equals(src())) {
                setStatus(r.note(), true);
                return;
            }
            String err = parent.applyScript(r.text());
            if (!err.isEmpty()) {
                setStatus(err, true);
                return;
            }
            name = nm.strip();                                  // 页头跟着换
            setStatus(r.note(), false);
            rebuild();
        }));
    }

    /** 这个节点在脚本里叫什么（改名要用原名去全链改）。 */
    private String oldName() {
        if (kind == com.tablegame.script.edit.ScriptGraph.KIND_FUNC || kind == com.tablegame.script.edit.ScriptGraph.KIND_PART) {
            int i = name.indexOf('(');
            return i > 0 ? name.substring(0, i) : name;
        }
        // 阶段：标签可能与真名不同（图上带前缀），从 on stage 那一行读
        String s = src();
        int[] lines = null;
        String[] L = s.split("\n", -1);
        if (line >= 1 && line <= L.length) {
            String t = L[line - 1].strip();
            int k = t.indexOf("stage");
            if (k >= 0) {
                String rest = t.substring(k + 5).strip();
                int end = 0;
                while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '_')) end++;
                if (end > 0) return rest.substring(0, end);
            }
        }
        return name;
    }

    /** 这个阶段现有的出口（goto 目标）—— 用的是段0 之前就有的 gotosOf（⚠ 它的 fromLine 是 **0 起**）。 */
    private List<String> exits() {
        return ScriptEdit.gotosOf(src(), line - 1);
    }

    private void removeExit(String to) {
        ScriptEdit.Result r = ScriptEdit.removeGoto(src(), line - 1, to);
        apply(r, "（可撤销）");
        rebuild();
    }

    /** 出口：从阶段候选里挑一个（复用候选清单那套）。 */
    private void addExitTo(String to) {
        ScriptEdit.Result r = ScriptEdit.addGoto(src(), line - 1, to);
        apply(r, null);
        view = 0;
        rebuild();
    }

    // ================= 候选清单（段3 的 ＋动作卡 / 出口）=================

    private List<String> pickItems() {
        List<String> out = new ArrayList<>();
        if (pickUse == 1) return ScriptEdit.candidates(src(), ScriptEdit.SRC_STAGE);
        for (ScriptEdit.Card c : ScriptEdit.CARDS) {
            String row = c.verb() + "  " + c.label();
            if (query.isBlank() || ItemText.matches(query, c.verb(), c.label())) out.add(c.verb());
        }
        return out;
    }

    private void refreshPick() {
        refreshScroll();
    }

    private void refreshScroll() {
        // 清单行自绘 → 只需重设总数（不重建控件，搜索框里的字不会丢）
        pickScroll.setTotal(pickItems().size());
    }

    private final ListScroll pickScroll = new ListScroll(10);

    private void openPick(int use, int blockLine, int nth) {
        pickUse = use;
        pickBlock = blockLine;
        pickNth = nth;
        view = 1;
        query = "";
        refreshScroll();
        rebuild();
    }

    private void doPick(String verb) {
        if (pickUse == 1) {
            addExitTo(verb);
            return;
        }
        apply(ScriptCard.insert(src(), pickBlock, pickNth, 999, verb), null);
        view = 0;
        rebuild();
    }

    // ================= 绘制 =================

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        condClicks.clear();                                          // 「条件 ▸ 编辑」点击区每帧重建
        EditorRail.drawBackdrop(g, height);
        int x = contentX(), w = contentW();
        // 侧栏对账（登记 #13）：部件「被哪些舞台调用」· 函数「谁调用」—— 只读列表，不做图上的引用线
        String calls = "";
        if (kind == com.tablegame.script.edit.ScriptGraph.KIND_PART
                || kind == com.tablegame.script.edit.ScriptGraph.KIND_FUNC) {
            var ls = com.tablegame.script.edit.ScriptEdit.callLines(parent.def.script(), name);
            ls.remove(Integer.valueOf(line));                       // 自己那一行（声明）不算调用
            calls = ls.isEmpty() ? "　· 还没人调用它"
                    : "　· 被 " + ls.size() + " 处调用：第 " + ls.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")) + " 行";
        }
        g.text(font, Component.literal(title() + " · " + name
                + (line > 0 ? "（脚本第 " + line + " 行）" : "") + calls), x, 10, 0xFFFFFFFF);

        if (!editable()) {
            g.centeredText(font, Component.literal(duty()), width / 2, 80, 0xFFE0E0E0);
            g.centeredText(font, Component.literal("（这一页还在排队 —— 变量入口是段4，舞台节点页是段5）"),
                    width / 2, height - 66, 0xFF808080);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }
        if (view == 1) {
            drawPicker(g, mouseX, mouseY);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }
        if (rows.isEmpty()) {
            g.text(font, Component.literal("（这块是空的 —— 点上面「＋ 加一张卡」）"), x, LIST_TOP + 6, 0xFF909090);
        }
        for (int i = 0; i < scroll.rows(); i++) {
            Row r = rows.get(scroll.index(i));
            drawRow(g, r, LIST_TOP + i * ROW_H, mouseX, mouseY);
        }
        String lab = scroll.label();
        if (!lab.isEmpty()) {
            g.text(font, Component.literal("滚轮翻页　" + lab), x, LIST_TOP + scroll.rows() * ROW_H + 4, 0xFF909090);
        }
        if (!status.isEmpty()) {
            g.text(font, Component.literal(status), x, height - 34, statusBad ? 0xFFFF7070 : 0xFFA0E080);
        }
        g.text(font, Component.literal("改完按回车生效 · ↑↓ 挪 · ✕ 删 · 容器卡可展开 · 空块也能「＋」"), 
                x, height - 20, 0xFF909090);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        paramMenu.draw(g, font, mouseX, mouseY);                     // 参数下拉画在最上层
        condMenu.draw(g, font, mouseX, mouseY);                      // 条件小窗的下拉
        drawCond(g, mouseX, mouseY);                                 // 条件小窗最上层
    }

    private void drawRow(GuiGraphicsExtractor g, Row r, int y, int mouseX, int mouseY) {
        int x = contentX() + r.indent() * IND, w = contentW() - r.indent() * IND;
        if (r.head()) {
            g.fill(x, y + 2, x + w - 6, y + 16, 0xFF262A33);
            g.text(font, Component.literal("▸ " + r.label() + " 里："), x + 4, y + 4, 0xFFB0B8C8);
            return;
        }
        ScriptCard.Card c = r.card();
        g.fill(x, y, x + w, y + 34, c.known() ? 0xFF21242B : 0xFF2A2226);
        g.outline(x, y, w, 34, c.known() ? 0xFF3A3E4A : 0xFF5A3A3A);
        // 容器卡：行尾画「条件 ▸ 编辑」（方案 A）—— 点它开条件小窗，不再只能跳文本页
        if (ScriptCard.container(c) && c.lineIndex() == 0) {
            g.text(font, Component.literal("条件 ▸ 编辑"), x + w - 76, y + 6, 0xFF90C0E0);
            condClicks.add(new int[]{y + 2, x + w - 80, 80, c.blockLine(), c.nth(), c.index()});
        }
        String head = "第 " + c.line() + " 行"
                + (c.lineIndex() > 0 ? " · 第 " + (c.lineIndex() + 1) + " 条" : "")
                + "   " + (c.known() ? (c.verb() + "  " + ScriptCard.labelOf(c.verb())) : "（这条暂不支持在卡上编 —— 点它去文本页）");
        g.text(font, Component.literal(head), x + 4, y + 6, c.known() ? 0xFFE0E0E0 : 0xFFFFB0B0);
        List<ScriptCard.Arg> as = ScriptCard.args(c);
        int n = Math.max(1, as.size());
        // 与 buildCard 同一处算路（①）：只读视图的名字要跟卡上的控件对齐
        int budget = Math.max(w - 4, minRowWidth(as));
        int ax = x + 2;
        for (int i = 0; i < as.size(); i++) {
            ScriptCard.Arg a = as.get(i);
            g.text(font, Component.literal(a.name()), ax, y + 19, 0xFF909090);
            if (ScriptEdit.CTL_READ.equals(a.control())) {          // 只读位：值画在名字右边（算式在文本页改）
                g.text(font, Component.literal(a.text()), ax + font.width(a.name()) + 6, y + 19, 0xFFD0C080);
            }
            ax += Math.max(28 + slotOverhead(a), budget / n);
        }
    }

    private void drawPicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<String> items = pickItems();
        pickScroll.setTotal(items.size());
        int x = contentX(), w = contentW();
        g.text(font, Component.literal(pickUse == 1 ? "加到哪条出口（挑一个阶段）" : "挑一张动作卡（打字即筛）"),
                x, 46, 0xFFE0E0E0);
        int top = 88;
        if (items.isEmpty()) {
            g.text(font, Component.literal("（没有匹配的）"), x, top, 0xFF909090);
            return;
        }
        for (int row = 0; row < pickScroll.rows(); row++) {
            int idx = pickScroll.index(row);
            if (idx >= items.size()) break;
            String v = items.get(idx);
            int ry = top + row * 20;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 20;
            g.fill(x, ry, x + w, ry + 20, hover ? 0xFF2E3138 : 0xFF21242B);
            g.outline(x, ry, w, 20, hover ? 0xFF8A9A6A : 0xFF3A3E4A);
            g.text(font, Component.literal(v), x + 6, ry + 6, 0xFFFFFFFF);
            String lab = pickUse == 1 ? "阶段" : ScriptCard.labelOf(v);
            g.text(font, Component.literal(lab), x + 150, ry + 6, 0xFFA0C0A0);
        }
        String lb = pickScroll.label();
        if (!lb.isEmpty()) {
            g.text(font, Component.literal("滚轮翻页　" + lb), x, top + pickScroll.rows() * 20 + 4, 0xFF909090);
        }
    }

    // 交互：滚轮 / 点击（自绘行：只读值点击跳脚本行 · 候选清单点击即选）

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (paramMenu.scrollBy(dy) || condMenu.scrollBy(dy)) {
            return true;                              // 菜单开着：先滚菜单（含到头也吃掉）
        }
        ListScroll sc = view == 1 ? pickScroll : scroll;
        if (sc.scroll(dy)) {
            if (view == 1) return true;
            rebuild();                                        // 卡片列表翻页要重摆控件
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (paramMenu.click(event.x(), event.y())) return true;      // 下拉开着：先让它吃掉这一下
        if (condCard != null) {                                      // 条件小窗开着：先让它吃
            if (condClick(event.x(), event.y())) return true;
        }
        if (editable() && view == 0) {
            for (Box b : boxes) {                                    // 点了别处：先把正编辑的框提交掉（别丢字）
                if (b.box().isFocused() && !b.box().getValue().strip().equals(argTextOf(b))) {
                    commit(b);
                    return true;
                }
            }
            if (getChildAt(event.x(), event.y()).isEmpty()) {         // 不在控件上：自绘区的点击
                for (int[] cc : condClicks) {                        // 「条件 ▸ 编辑」→ 开条件小窗
                    if (event.y() >= cc[0] && event.y() < cc[0] + 16 && event.x() >= cc[1] && event.x() < cc[1] + cc[2]) {
                        openCond(cc[3], cc[4], cc[5]);
                        return true;
                    }
                }
                for (int[] j : jumps) {                              // 卡头 / 只读值 → 跳脚本那一行（A7）
                    if (event.y() >= j[0] && event.y() < j[0] + 16 && event.x() >= j[1] && event.x() < j[1] + j[2]) {
                        Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, j[3]));
                        return true;
                    }
                }
            }
        }
        if (view == 1) {
            int x = contentX(), w = contentW(), top = 88;
            double mx = event.x(), my = event.y();
            if (mx >= x && mx < x + w && my >= top) {
                int row = (int) ((my - top) / 20);
                if (row >= 0 && row < pickScroll.rows()) {
                    List<String> items = pickItems();
                    int idx = pickScroll.index(row);
                    if (idx < items.size()) {
                        doPick(items.get(idx));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** 这个框现在对应的参数文本（用来判「敲进去的值跟文本里的一样不一样」）。 */
    private String argTextOf(Box b) {
        List<ScriptCard.Arg> as = ScriptCard.args(b.card());
        return b.argIdx() < as.size() ? as.get(b.argIdx()).text() : "";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 菜单检索行：退格喂它；菜单开着时回车不落到底下的控件（AssetMenu）
        for (AssetMenu m : new AssetMenu[]{paramMenu, condMenu}) {
            if (m.isOpen()) {
                if (event.key() == 259 && m.backspace()) {
                    return true;
                }
                if (event.key() == 257 || event.key() == 335) {
                    return true;
                }
            }
        }
        // 段6 手感：Ctrl+Z 撤销 · Ctrl+Y（或 Ctrl+Shift+Z）重做 —— 一行转发，逻辑在 GameEditorScreen
        if (parent.handleHistoryKey(event.key(), event.hasControlDownWithQuirk(), event.hasShiftDown(), 90, 89)) {
            clearWidgets();
            init();
            return true;
        }
        if (view == 0 && (event.key() == 257 || event.key() == 335)) {   // 回车 = 提交悬停那张卡的改动
            for (Box b : boxes) {
                if (b.box().isFocused()) {
                    commit(b);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    // ===== 菜单检索/拖动转发（AssetMenu）=====

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (paramMenu.type(event.codepointAsString()) || condMenu.type(event.codepointAsString())) {
            return true;
        }
        if (view == 1 && pickBox != null && pickBox.isFocused()) {
            return super.charTyped(event);              // 候选清单搜索框自己收（焦点链）
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (paramMenu.drag(event.x(), event.y()) || condMenu.drag(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (paramMenu.release(event.x(), event.y()) || condMenu.release(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }
}
