package com.tablegame.editor.stage;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;

/**
 * 舞台组件编辑页：改一条画语句的类型 · 几何 · 样式 · 内容 · 属性。
 * 脚本是真源：进来读，按【完成】才经 {@link GameEditorScreen#applyScript} 一次写回，【取消】不写；写回按表单重拼整条画语句。
 * 属性 = 往脚本里生成一段代码（click / paint），界面只是生成器 + 反查视图。
 * ponytail: 只支持 box / input / value / img + click / paint；卡面与部件实例只给「跳脚本 / 删除」。
 */
public class StageCompScreen extends Screen implements EditorToolScreen {

/**
 * 这页能改的四类。
 * 「文本」不是单独类型：`text(x,y,宽,高,内容,字色)` 是等价老写法（透明底带字框），打开照样列在这页，按【完成】统一写成 `box(…)`。
 */
    private static final String[] TYPES = { "box", "input", "value", "img" };
    private static final String[] TYPE_NAMES = { "框（可带文字）", "输入框", "值框（表达式）", "图片框" };
    /** 「文本」多行框高度（够三行）。 */
    private static final int TEXT_H = 44;

    /** 换类型时补的缺省值；DEF_CLEAR = 全透明底（文本归成框时用）。 */
    private static final String DEF_FILL = "#3A3A52", DEF_COLOR = "#FFFFFF",
            DEF_HINT = "在这里输入", DEF_CLEAR = "#00000000";

    /** 画布（screen 名）与这条画语句的锚点（行号 + 行内序号）。 */
    private final String canvas;
    private final int line, index;
    /** 玩法编辑器（脚本唯一数据源）。 */
    private final GameEditorScreen editor;
    /** 返回回哪一屏（舞台预览屏，按脚本变化重展开）。 */
    private final StageVisualScreen parent;

    /** 【＋属性】的菜单。 */
    private final AssetMenu menu = new AssetMenu();

    /** 进来那一刻那条画语句的种类；不在 {@link #TYPES} 里 = 这页不管它。 */
    private String type = "box";
    /** 不支持的类型的原样名字（{@code card_face} / 部件名…）—— 非空 = 本页只给「跳脚本 / 删除」。 */
    private String unsupported = "";
    /** 脚本里定位不到这一条（脚本刚被手改 / 行内序号算不出来）—— 与「这页不管这类组件」分开报。 */
    private boolean lost;
    /** 画语句跨了多行（锚点会落到续行）—— 拒绝编辑，要求合成一行。 */
    private boolean crossLine;
    /** 那条语句的实参原文快照（每格初值、以及「哪些是带引号的字符串」都看它）。 */
    private List<String> args = List.of();
    /** 它是不是来自 {@code part} 体（改一处全体变 —— 页面顶上要写明）。 */
    private boolean fromPart;
    /** 原来是 `text(…)` 老写法（等价透明底带字框）—— 按【完成】统一写成 `box(…)`。 */
    private boolean wasText;

    // ---- 表单现场值（EditBox 不持久，值随输入进这些字段）----
    private String vx, vy, vw, vh;                 // 位置四个（原文——可能不是字面量）
    private String vFill;                          // 框：底色
    private String vAlpha;                         // 框 / 文本：透明度 0~255
    private String vText, vTextColor;              // 文本：内容 / 字色
    private String vHint;                          // 输入框：提示
    private String vExpr = "";                     // 值框：表达式（原文，不掐引号）
    private String vArt = "";                      // 图片框：资产名（画板项目 / @游戏名/资产名）
    private List<String> attrs = new ArrayList<>();  // 已经挂着的属性（click / paint）

    private String vAsset = "", vLabel = "";               // 资产名 / 显示名（写的是那条 comp(…)）
    private EditBox boxAsset, boxLabel;
    /** 文本 = 多行框（回车能换行）。 */
    private net.minecraft.client.gui.components.MultiLineEditBox boxText;
    private EditBox boxX, boxY, boxW, boxH, boxFill, boxAlpha, boxTextColor, boxHint, boxAttr;
    private EditBox boxExpr;
    private EditBox boxArt;                                // 图片框：资产名那格
    /** 颜色实参在实参表里的下标（{@code box} = 4 · {@code text} = 5 · 没有 = -1）。 */
    private int colorArg = -1;
    private String status = "";
    private boolean statusBad;
    /** 这条画语句是这页认得的类型（{@code box} / {@code text} / {@code input}）；false = 只给去脚本改。 */
    private boolean supported;

    public StageCompScreen(GameEditorScreen editor, String canvas, int line, int index, StageVisualScreen parent) {
        super(Component.literal("组件"));
        this.editor = editor;
        this.canvas = canvas;
        this.line = line;
        this.index = index;
        this.parent = parent;
        load();
    }

    @Override
    public GameEditorScreen editor() {
        return editor;
    }

    /** 从脚本现读这一条（脚本是真源：界面这一层只是生成器 + 视图）。 */
    private void load() {
        // ⚠ 画语句必须一条一行（find 只在一行内扫）：跨行的语句锚点会落到续行、参数串成碎片 —— 宁可拒绝编辑。
        if (!ScriptEdit.drawLineOk(editor.def.script(), line)) {
            crossLine = true;
            return;
        }
        ScriptEdit.DrawAt d = ScriptEdit.find(editor.def.script(), line, index);
        if (d == null) {
            lost = true;                                     // 定位不到（脚本被手改 / 序号错），与「不支持的类型」分开报
            return;
        }
        type = d.kind();
        args = d.args();
        fromPart = ScriptEdit.blockAt(editor.def.script(), line).startsWith("part ");
        wasText = type.equals("text");                        // 老写法：等价于「透明底的带字框」
        if (wasText) type = "box";                            // 归到「框」这一类（按【完成】统一写法）
        boolean known = false;
        for (String t : TYPES) if (t.equals(type)) known = true;
        if (!known) {
            unsupported = type;
            return;
        }
        supported = true;
        vx = arg(0);
        vy = arg(1);
        vw = arg(2);
        vh = arg(3);
        if (wasText) {                                        // text(…) 写法：内容 / 字色在第 5、6 个
            vText = arg(4);
            vTextColor = arg(5);
            vFill = DEF_CLEAR;                                // 文本不带上色 —— 归成框时用「全透明底」
            vAlpha = "255";
            colorArg = 4;
        } else if (type.equals("value")) {
            // 值框：第 5 个实参是**表达式**，取原文（不掐引号 —— "金币：" + text(…) 整段要原样留着）
            vExpr = args.size() > 4 ? args.get(4) : "";
        } else if (type.equals("input")) {
            vHint = arg(4);
        } else if (type.equals("img")) {
            vArt = arg(4);                               // 图片框：第 5 个实参 = 资产名（画板项目 / @游戏名/资产名）
            colorArg = -1;                               // 它没有底色
        } else {
            vFill = arg(4);
            colorArg = 4;
            vAlpha = String.valueOf(GameDefinition.BoxStyle.alphaOf(vFill));
            vFill = GameDefinition.BoxStyle.withAlpha(vFill, 255);
            if (args.size() >= 7) {                           // 带文字的框（7 个实参）
                vText = arg(5);
                vTextColor = arg(6);
            } else {
                vText = "";
                vTextColor = DEF_COLOR;
            }
        }
        attrs = new ArrayList<>(ScriptEdit.drawAttrsOf(editor.def.script(), line, index));
        ScriptEdit.CompAt cp = ScriptEdit.compOf(editor.def.script(), line, index);
        vAsset = cp == null ? "" : cp.id();
        vLabel = cp == null ? "" : cp.label();
    }

    /** 第 i 个实参**掐掉引号**的原文（越界 = 空串）；数字 / 表达式原样。 */
    private String arg(int i) {
        if (i < 0 || i >= args.size()) return "";
        return ScriptEdit.isQuoted(args.get(i)) ? ScriptEdit.inner(args.get(i)) : args.get(i);
    }

    /** 「文本」那格的现场值（多行框没有 responder，读的时候现取）。 */
    private String liveText() {
        return boxText == null ? vText : boxText.getValue();
    }

    /** 某个实参是不是带引号的字符串（写回时决定要不要再包引号）。 */
    private boolean quoted(int i) {
        return i >= 0 && i < args.size() && ScriptEdit.isQuoted(args.get(i));
    }

    @Override
    protected void init() {
        clearWidgets();
        // 左栏：【返回】【＋属性】靠下 ·【完成】【取消】常驻栏底（内容区不摆跳转 / 删除，那两件在外层右键里）。
        int railBottom = EditorRail.buildGeneric(new String[0], null, t -> { }, this::addRenderableWidget);
        addRenderableWidget(DrawBoardMenuUi.button(4, railBottom + 6, EditorRail.W, 18, "返回", this::back));
        if (supported) {
            int byAttr = Math.min(height * 3 / 5, height - 88);
            addRenderableWidget(DrawBoardMenuUi.button(4, byAttr, EditorRail.W, 18, "＋ 属性", this::openAttrMenu));
        }
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 46, EditorRail.W, 18, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(4, height - 24, EditorRail.W, 18, "取消", this::back));

        // 版式：文本摆右列（与「类型」齐头），字色 / 透明在它下面。
        int x = EditorRail.LEFT;
        int x2 = x + 320;
        int fw = Math.min(560, Math.max(220, width - x - 24));
        int y = 16;
        final int g = 24;

        addRenderableWidget(DrawBoardMenuUi.label(x, y, fw, "组件 · 脚本第 " + line + " 行第 " + (index + 1) + " 条"
                + " · 在 screen " + canvas + " 里" + (fromPart ? " · 来自 part 体（改一处全体变）" : ""), 0xFFFFFFFF));
        y += 18;
        if (!supported) {
            addRenderableWidget(DrawBoardMenuUi.label(x, y, fw, crossLine
                    ? "这条画语句**跨了多行** —— 编辑页按行定位（第 " + line + " 行只是一半），改它会改错地方。"
                      + "去脚本页把它合成一行再来（一条画语句写一行）"
                    : lost
                    ? "这一条在脚本里定位不到（脚本可能刚被手改过）—— 回上一页重开一次，或去脚本页看第 " + line + " 行"
                    : "这页不管「" + unsupported + "」这类组件，去脚本改或删掉它", 0xFFE0A060));
            return;
        }

        // ---------- 资产名 / 显示名（写的是那条 comp(…)）----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 170, "资产名", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x2, y, 170, "显示名", 0xFFB0B0C0));
        y += 11;
        boxAsset = new EditBox(font, x, y, 170, 16, Component.literal("资产名"));
        boxAsset.setMaxLength(32);
        boxAsset.setValue(vAsset);
        boxAsset.setHint(Component.literal("点它时 on pick 收到的名字"));
        boxAsset.setResponder(s -> vAsset = s);
        addRenderableWidget(boxAsset);
        boxLabel = new EditBox(font, x2, y, 170, 16, Component.literal("显示名"));
        boxLabel.setMaxLength(32);
        boxLabel.setValue(vLabel);
        boxLabel.setHint(Component.literal("只给人看"));
        boxLabel.setResponder(s -> vLabel = s);
        addRenderableWidget(boxLabel);
        y += g;

        // 左右两列各用自己的下一个 y：右列多行框高，共用游标会把左列顶低。
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 170, "类型", 0xFF909090));
        int bw = Math.min(96, (fw - 12) / TYPES.length);   // 四档之后要按可用宽回缩，免得盖到屏外
        int by = y + 11;
        for (int i = 0; i < TYPES.length; i++) {
            String t = TYPES[i];
            boolean on = t.equals(type);
            addRenderableWidget(DrawBoardMenuUi.button(x + i * (bw + 4), by, bw, 18,
                    (on ? "● " : "") + TYPE_NAMES[i], () -> switchType(t)));
        }
        if (type.equals("input")) {
            // 输入框：左列紧跟着摆「提示」（它没有底色 / 字色 / 透明）
            addRenderableWidget(DrawBoardMenuUi.label(x, y + 40, 260, "提示", 0xFFB0B0C0));
            boxHint = field(x, y + 51, 260, "提示", vHint, -1);
            y += 51 + 16 + 10;
        } else if (type.equals("value")) {
            // 值框：左列摆「表达式」+ 候选（没有底色 / 字色 / 透明）。
            // 表达式在展开期求值 —— 那时只有 viewer 与变量有值，底下那条护栏就为这个。
            int ew = Math.min(440, fw - 8);
            addRenderableWidget(DrawBoardMenuUi.label(x, y + 40, ew, "表达式（每次展开按观看者求值）", 0xFFB0B0C0));
            boxExpr = field(x, y + 51, ew, "表达式", vExpr, -2);
            boxExpr.setResponder(t -> vExpr = t);                 // 原文进出：填什么就是什么
            int cy = y + 51 + 16 + 4;
            addRenderableWidget(DrawBoardMenuUi.label(x, cy, ew, "候选（点一下追加到表达式末尾）", 0xFF909090));
            cy += 11;
            int cx = x;
            for (String cand : exprCandidates()) {
                int cbw = Math.max(40, font.width(cand) + 10);
                if (cx + cbw > x + ew) { cx = x; cy += 20; }
                String add = cand;
                addRenderableWidget(DrawBoardMenuUi.button(cx, cy, cbw, 18, add, () -> appendExpr(add)));
                cx += cbw + 4;
            }
            cy += 20;
            String warn = exprWarn();
            if (!warn.isEmpty()) {
                addRenderableWidget(DrawBoardMenuUi.label(x, cy, Math.max(220, width - x - 12), warn, 0xFFE0A060));
                cy += 11;
            }
            y += 51 + 16 + 10;
        } else if (type.equals("img")) {
            // 图片框：一栏资产名（画板项目 "组/项目名"，或 "@游戏名/资产名"）
            addRenderableWidget(DrawBoardMenuUi.label(x, y + 11, 300, "资产名（画板项目 / @游戏名/资产名）", 0xFFB0B0C0));
            boxArt = field(x, y + 22, 300, "资产名", vArt, 4);
            y += 22 + 16 + 10;                    // 左列推进：给「资产名」那行留位
        } else {
            addRenderableWidget(DrawBoardMenuUi.label(x2, y, 200, "文本", 0xFF909090));
            boxText = net.minecraft.client.gui.components.MultiLineEditBox.builder()
                    .setX(x2)
                    .setY(y + 11)
                    .setShowBackground(true)
                    .setShowDecorations(true)
                    .build(font, 200, TEXT_H, Component.literal("文本"));
            boxText.setCharacterLimit(200);
            boxText.setValue(vText);
            addRenderableWidget(boxText);
            int yc = y + 11 + TEXT_H + 3;
            addRenderableWidget(DrawBoardMenuUi.label(x2, yc, 78, "字色", 0xFFB0B0C0));
            boxTextColor = field(x2, yc + 11, 78, "字色", vTextColor, 5);
            y += 11 + 18 + 10;                    // 左列只推进「类型」那一行
        }

        // ---------- 位置（四个小格）----------
        addRenderableWidget(DrawBoardMenuUi.label(x, y, 56, "x", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x + 60, y, 56, "y", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x + 120, y, 56, "宽", 0xFFB0B0C0));
        addRenderableWidget(DrawBoardMenuUi.label(x + 180, y, 56, "高", 0xFFB0B0C0));
        y += 11;
        boxX = field(x, y, 56, "x", vx, 0);
        boxY = field(x + 60, y, 56, "y", vy, 1);
        boxW = field(x + 120, y, 56, "宽", vw, 2);
        boxH = field(x + 180, y, 56, "高", vh, 3);
        y += 16 + 10;

        // ---------- 底色与它的透明度（摆在一起 —— 框的透明不该离开底色）----------
        if (type.equals("box")) {                    // 图片框 / 值框 / 输入框都没底色
            addRenderableWidget(DrawBoardMenuUi.label(x, y, 78, "颜色", 0xFFB0B0C0));
            addRenderableWidget(DrawBoardMenuUi.label(x + 86, y, 60, "透明", 0xFFB0B0C0));
            y += 11;
            boxFill = field(x, y, 78, "颜色", vFill, 4);
            boxAttr = field(x + 86, y, 60, "透明", vAlpha, -1);
            y += 16 + 10;
        }

        // ---------- 属性（一行一个；【＋属性】在左栏靠下）----------
        if (!attrs.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(x, y, fw, "属性", 0xFF909090));
            y += 11;
            for (String a : attrs) {
                addRenderableWidget(DrawBoardMenuUi.button(x, y, 220, 18, attrLabel(a), () -> { }));
                addRenderableWidget(DrawBoardMenuUi.button(x + 226, y, 18, 18, "✕", () -> toggleAttr(a, false)));
                y += 22;
            }
        }
    }

    /** 一行「标签 + 输入框」，表达式驱动的实参钉成只读（口径同画布拖几何）。 */
    private EditBox field(int x, int y, int w, String label, String value, int argIdx) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.literal(label));
        b.setMaxLength(200);
        b.setValue(value == null ? "" : value);
        // 只读判据：只对这条真有的实参判（5 实参的框里「文字 / 字色」还不存在，得能填）。
        boolean exists = argIdx >= 0 && argIdx < args.size();
        if (exists && !ScriptEdit.literal(editor.def.script(), line, index, argIdx)) {
            b.setEditable(false);
            b.setHint(Component.literal("表达式 · 只读"));
        }
        if (argIdx == 0) b.setResponder(s -> vx = s);
        if (argIdx == 1) b.setResponder(s -> vy = s);
        if (argIdx == 2) b.setResponder(s -> vw = s);
        if (argIdx == 3) b.setResponder(s -> vh = s);
        if (argIdx == 4 && type.equals("box")) b.setResponder(s -> vFill = s);
        if (argIdx == 4 && type.equals("img")) b.setResponder(s -> vArt = s);   // 图片框：资产名
        if (argIdx == 5 && type.equals("box")) b.setResponder(s -> vTextColor = s);
        if (argIdx == -1) {                                       // 「透明」那格：颜色实参是表达式时也没得改
            b.setResponder(s -> vAlpha = s);
            if (colorArg >= 0 && !ScriptEdit.literal(editor.def.script(), line, index, colorArg)) {
                b.setEditable(false);
                b.setHint(Component.literal("表达式 · 只读"));
            }
        }
        addRenderableWidget(b);
        return b;
    }

    /**
     * 换类型：位置四个原样搬，「文字 / 提示」字符串跟着搬，样式按新类型补缺省（框给底色 + 白字）。
     */
    private void switchType(String nt) {
        if (nt.equals(type)) return;
        vText = liveText();                                    // 多行框的值先收回来（切类型要保住它）
        vExpr = liveExpr();                                    // 值框那格也先收回来
        String word = type.equals("input") ? vHint : (type.equals("value") ? "" : vText);
        if (nt.equals("value")) {
            colorArg = -1;                                     // 值框没有底色（表达式那格自己留着）
        } else if (nt.equals("input")) {
            vHint = word == null || word.isBlank() ? DEF_HINT : word;
            colorArg = -1;
        } else if (nt.equals("img")) {
            colorArg = -1;                                     // 图片框没有底色（资产名那格自己留着）
        } else {
            vText = word == null ? "" : word;
            if (vFill == null || vFill.isBlank()) vFill = DEF_FILL;
            if (vTextColor == null || vTextColor.isBlank()) vTextColor = DEF_COLOR;
            colorArg = 4;
        }
        type = nt;
        wasText = false;
        clearWidgets();
        init();
    }

    /**
     * 值框表达式的候选：{@code viewer} · 脚本里所有变量名 · 两个常用拼法。
     * ⚠ 不给事件内建值（actor / input / edge…）：值框在展开期求值，那时只有 viewer 与变量有值。
     */
    private List<String> exprCandidates() {
        List<String> out = new ArrayList<>();
        out.add("viewer");
        for (ScriptEdit.VarAt v : ScriptEdit.varsOf(editor.def.script())) out.add(v.name());
        out.add("text(");
        out.add("num(");
        return out;
    }

    /** 点候选：追加到表达式末尾（现场值进出，不猜作者想插在哪）。 */
    private void appendExpr(String add) {
        vExpr = (liveExpr() == null ? "" : liveExpr()) + add;
        clearWidgets();
        init();
    }

    /** 「表达式」那格的现场值。 */
    private String liveExpr() {
        return boxExpr == null ? vExpr : boxExpr.getValue();
    }

    /** 展开期没有值的那些内建值（写了 = 值框永远空/0）—— 提示用，不拦保存。 */
    private static final List<String> EVENT_ONLY = List.of("actor", "input", "input_box", "pick", "edge",
            "edge_area", "block", "hand", "offhand", "bx", "by", "bz", "sneak", "rclick", "look_x", "look_y",
            "look_z", "look_block", "etype", "drops");

    /** 值框里写了事件内建值 ⇒ 记一句人话（不拦保存）。 */
    private String exprWarn() {
        String e = liveExpr();
        if (e == null || e.isBlank()) return "";
        for (String w : EVENT_ONLY) {
            if (e.matches("(?s).*\\b" + w + "\\b.*")) {
                return "⚠ 值框里「" + w + "」是空的 —— 它在事件里有值、在展开期没有。要显示" 
                        + ("actor".equals(w) ? "谁在看就用 viewer" : "自己那一份就用 viewer");
            }
        }
        return "";
    }

    private static String attrLabel(String a) {
        return a.equals("click") ? "可点（click → on pick 收身份值）" : "绘画板（paint）";
    }

    /** 左栏那颗【＋属性】：开一层菜单（可点 / 绘画板）。 */
    private void openAttrMenu() {
        int[] r = attrMenuPos();
        menu.open(width, height, r[0], r[1], attrChoices(), attrActions());
    }

    /** 【＋属性】那颗钮的位置（菜单贴着它开）。 */
    private int[] attrMenuPos() {
        return new int[] { 4 + EditorRail.W, Math.min(height * 3 / 5, height - 88) };
    }

    /** 【＋属性】菜单里能加的那些（已挂上的不列）。 */
    private List<String> attrChoices() {
        List<String> out = new ArrayList<>();
        for (String a : new String[] { "click", "paint" }) if (!attrs.contains(a)) out.add(attrLabel(a));
        if (out.isEmpty()) out.add("（两个属性都挂上了）");
        return out;
    }

    private List<Runnable> attrActions() {
        List<Runnable> out = new ArrayList<>();
        for (String a : new String[] { "click", "paint" }) if (!attrs.contains(a)) out.add(() -> toggleAttr(a, true));
        if (out.isEmpty()) out.add(() -> { });
        return out;
    }

    /**
     * 挂上 / 摘掉一个属性：立刻写回（属性是一行独立代码，攒到【完成】会出现自相矛盾的中间态）。
     */
    private void toggleAttr(String attr, boolean on) {
        ScriptEdit.Result r = ScriptEdit.setDrawAttr(editor.def.script(), line, index, attr, on);
        if (r.text().equals(editor.def.script())) {
            status(r.note(), true);
            return;
        }
        String err = editor.applyScript(r.text());
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        attrs = new ArrayList<>(ScriptEdit.drawAttrsOf(editor.def.script(), line, index));
        status(r.note(), false);
        clearWidgets();
        init();
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 【完成】：按表单**重拼那一条画语句**（类型 + 实参一次写完），走唯一写回出口。 */
    private void commit() {
        if (!unsupported.isEmpty()) {
            status("这页不管这类组件，去脚本改", true);
            return;
        }
        String code = compose();
        ScriptEdit.Result r = ScriptEdit.replaceDraw(editor.def.script(), line, index, code);
        if (r.text().equals(editor.def.script())) {
            status(r.note(), true);
            return;
        }
        String text = r.text();
        String note = r.note();
        // 资产名 / 显示名：写的是那条 comp(…)（改名先不动「这一条画语句」的行号 ⇒ 还能用同一个锚点）
        ScriptEdit.CompAt now = ScriptEdit.compOf(editor.def.script(), line, index);
        String na = vAsset == null ? "" : vAsset.strip();
        String nl = vLabel == null ? "" : vLabel.strip();
        boolean had = now != null;
        boolean same = had && now.id().equals(na) && now.label().equals(nl);
        if (!same && !(na.isEmpty() && !had)) {
            ScriptEdit.Result rc = ScriptEdit.setComp(text, line, index, na, nl);
            if (rc.text().equals(text)) {
                status(rc.note(), true);
                return;
            }
            text = rc.text();
            note += "；" + rc.note();
        }
        String err = editor.applyScript(text);
        if (!err.isEmpty()) {
            status(err, true);
            return;
        }
        DrawBoardMenuUi.msg("[组件] " + note
                + (wasText ? "（原 text(…) 老写法已统一成 box(…)，效果一样）" : ""));
        back();
    }

    /**
     * 按当前类型与表单值拼出那条画语句的源码：位置四个原样（含表达式），其余按类型给，带引号的实参包回引号。
     */
    private String compose() {
        List<String> out = new ArrayList<>();
        out.add(vx);
        out.add(vy);
        out.add(vw);
        out.add(vh);
        if (type.equals("value")) {
            // 表达式原文进出（不包引号）；空着 = 写空串，免得生成解析不过的语句。
            String e = liveExpr();
            out.add(e == null || e.isBlank() ? "\"\"" : e);
        } else if (type.equals("input")) {
            out.add(ScriptEdit.strCode(vHint == null ? "" : vHint));
        } else if (type.equals("img")) {
            // 图片框：第 5 个实参就是资产名 —— 包成字符串字面量（空着也写空串，免得生成一条解析不过的语句）
            out.add(ScriptEdit.strCode(vArt == null ? "" : vArt));
        } else {
            out.add(ScriptEdit.strCode(GameDefinition.BoxStyle.withAlpha(vFill, alphaOr255())));
            String txt = liveText();
            if (txt != null && !txt.isBlank()) {               // 带文字 = 7 个实参的写法
                out.add(ScriptEdit.strCode(txt));
                out.add(ScriptEdit.strCode(vTextColor == null || vTextColor.isBlank() ? DEF_COLOR : vTextColor));
            }
        }
        return type + "(" + String.join(", ", out) + ")";
    }

    /** 「透明」那格 → 0~255（填得不对 = 255 并记一句）。 */
    private int alphaOr255() {
        if (vAlpha == null || vAlpha.isBlank()) return 255;
        try {
            int a = Integer.parseInt(vAlpha.strip());
            if (a < 0 || a > 255) {
                status("「透明」只能填 0~255", true);
                return 255;
            }
            return a;
        } catch (NumberFormatException e) {
            status("「透明」要填 0~255 的整数", true);
            return 255;
        }
    }

    private void status(String s, boolean bad) {
        status = s;
        statusBad = bad;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (menu.click(event.x(), event.y())) return true;      // 属性菜单先吃这一下
        return super.mouseClicked(event, doubled);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            g.centeredText(font, Component.literal(status), width / 2, height - 34,
                    statusBad ? 0xFFFF9090 : 0xFF90D090);
        }
        g.centeredText(font, Component.literal("Enter 完成 · Esc 取消（改完这一页，画布那边会自动重展开）"),
                width / 2, height - 20, 0xFF909090);
        menu.draw(g, font, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) {
            return true;                              // 菜单检索行在退格
        }
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) {
            return true;                              // 菜单开着：回车不落到底下的控件
        }
        if (event.key() == 257) {                    // Enter = 完成（**多行文本框里 = 换行**，交给它自己吃）
            if (getFocused() == boxText) return super.keyPressed(event);
            commit();
            return true;
        }
        if (event.key() == 256) {                    // Esc = 取消
            back();
            return true;
        }
        return super.keyPressed(event);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
