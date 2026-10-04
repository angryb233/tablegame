package com.tablegame.editor.script;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;

/**
 * 脚本工具屏（左栏「脚本」）：直接写规则。
 * 规则真源就是这段文本（档里的 {@code script} 段，schema/4），本屏是它唯一的编辑入口。
 * 文本域用原版 {@link MultiLineEditBox}（打字 / 光标 / 选择 / 换行 / 滚动现成）。
 * 保存 = 先校验再上传：{@link Parser} 跑一遍，错就报「第 N 行」且不通过；通过才装进内存模型并整包上传。
 * 服务端落盘前用同一套校验再过一遍（引用/槽位那些），失败原因走聊天栏。
 */
public class ScriptEditScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }
    /** 玩法编辑器（包私有：同包工具屏直接读写 def）。 */
    final GameEditorScreen parent;

    /** 多行文本域（原版控件）。 */
    private MultiLineEditBox box;
    /** 上次校验的错误（null = 没出错）；底部一行显示。 */
    private String error;
    /** 上次校验通过的结论（行数 / 事件入口）。 */
    private String ok;
    /** 行号栏宽度（屏幕像素）：四位数够用。 */
    private static final int GUTTER = 34;
    /**
     * 原版 {@link MultiLineEditBox} 的行高与内边距（抄原版源码常量：innerPadding=4、行高 9）。
     * 行号栏必须用同一套公式才对得齐 —— 原版若改这两个数，这里要跟着改。
     */
    private static final int LINE_H = 9, INNER_PAD = 4;
    /** 「跳到指令行」带来的行号（0 = 不定位）；只用于滚 + 顶部标注。 */
    private final int gotoLine;
    /** 跳过去之后顶/底栏那行提示（与 error / ok 分开，语义不同）。 */
    private String jumpNote;

    public ScriptEditScreen(GameEditorScreen parent) {
        this(parent, 0);
    }

    /**
     * 带行号的构造：可视化页右键「跳到指令行」用它。
     * ⚠ 原版 {@link MultiLineEditBox} 不开放光标定位，这里能做到的是「把那一行滚到看得见」+ 标出行号。
     */
    public ScriptEditScreen(GameEditorScreen parent, int line) {
        super(Component.literal("脚本"));
        this.parent = parent;
        this.error = parent.scriptError;      // 从「保存」跳过来时把服务端/本地挑出的错直接显出来
        this.ok = null;
        this.gotoLine = line;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = EditorRail.cx(this);
        // 顶部两态：文本 / 可视化（同一份 def）。现在是「文本」，画成禁用（null = 点不动）。
        addRenderableWidget(DrawBoardMenuUi.button(cx - 150, 42, 92, 22, "文本 ✔", null));
        addRenderableWidget(DrawBoardMenuUi.button(cx - 50, 42, 92, 22, "可视化",
                () -> Minecraft.getInstance().setScreen(new ScriptCanvasScreen(parent))));
        int top = 72;                                     // 给上面那排按钮让位
        int w = Math.min(600, width - EditorRail.LEFT - 40 - GUTTER);
        int h = Math.max(80, height - top - 78);
        int bx = cx - (w + GUTTER) / 2;                   // 「行号栏 + 文本域」整块居中
        box = MultiLineEditBox.builder()
                .setX(bx + GUTTER)
                .setY(top)
                .setPlaceholder(Component.literal("// 例：var answer = \"苹果\"  /  on start { say(all, \"开局\") }"))
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(font, w, h, Component.literal("规则脚本"));
        // 上限给足：100 万字符 ≈ 2.5 万行；真源与线协议本无上限，这里只是输入框自己的护栏。
        box.setCharacterLimit(1_000_000);
        box.setValue(parent.def.script());
        addRenderableWidget(box);
        box.setFocused(true);                 // 进来就能打字（字符输入走容器焦点）

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 40, 136, 24, "校验并保存", this::save));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, height - 40, 136, 24, "返回总览", this::back));
        EditorRail.build(parent, "脚本", this::addRenderableWidget);

        // 「跳到指令行」：把那一行滚到视口顶部（原版控件不给光标定位）。
        if (gotoLine > 0) {
            box.setScrollAmount(Math.max(0, (gotoLine - 1) * font.lineHeight));
            jumpNote = "跳到第 " + gotoLine + " 行（可视化里右键节点来的）";
        }
    }

    /** 校验并保存：解析通过 → 换进内存模型 → 整包上传；不通过 → 只报错不动档。 */
    private void save() {
        String text = box.getValue();
        try {
            Ast.Script sc = Parser.parse(text);
            ok = sc.lines() + " 行 · " + sc.handlers().size() + " 个事件入口 · " + sc.funcs().size() + " 个函数";
            error = null;
            parent.def = parent.def.withScript(text);     // record 不可变 → 复制一份换脚本
            parent.scriptError = null;
            parent.saveToServer();
            DrawBoardMenuUi.msg("[脚本] 校验通过（" + ok + "），已保存");
        } catch (Ast.ScriptError e) {
            error = e.getMessage();                       // 已含「第 N 行：…」
            // 坏草稿也写回也上传（保存不拦语法），错误留着给下一屏看
            parent.def = parent.def.withScript(text);
            parent.scriptError = error;
            parent.saveToServer();
            DrawBoardMenuUi.msg("[脚本] 已保存（有语法错）：" + error);
        }
        clearWidgets();
        init();                                           // 重建：底部那行结论要刷新
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
        parent.rebuildFromDef();
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
        int cx = EditorRail.cx(this);
        g.centeredText(font, Component.literal("规则脚本 · " + parent.def.name()), cx, 16, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("文本 = 规则真源（var/if/while/for/func/goto 阶段/on 事件）；存盘前校验，报错指数行号"),
                cx, 31, 0xFF909090);
        String line = error != null ? error
                : (ok != null ? "校验通过：" + ok
                : (jumpNote != null ? jumpNote : "改完点「校验并保存」"));
        g.centeredText(font, Component.literal(line), cx, height - 52, error != null ? 0xFFFF9090 : 0xFF90D090);
        drawLineNumbers(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /**
     * 行号栏：画在文本域左边，与正文同一套行距与滚动量（公式与常量抄原版，见 {@link #LINE_H}）。
     * 行数取自当前文本（边打字边长）；原版控件没有行号 API，只能自己画。
     */
    private void drawLineNumbers(GuiGraphicsExtractor g) {
        if (box == null) return;
        int gx = box.getX() - GUTTER;
        int top = box.getY(), bottom = box.getY() + box.getHeight();
        g.fill(gx, top, gx + GUTTER, bottom, 0xC00A0C12);              // 栏底
        g.fill(gx + GUTTER - 1, top, gx + GUTTER, bottom, 0xFF3A3E4A); // 与正文的分隔线
        String value = box.getValue();
        int lines = 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        int sc = (int) Math.round(box.scrollAmount());                 // ponytail: 滚动量取整（原版按 9 的整数倍滚，正常对得上）
        g.enableScissor(gx + 1, top + 1, gx + GUTTER, bottom - 1);
        for (int i = 0; i < lines; i++) {
            int y = top + INNER_PAD - sc + i * LINE_H;
            if (y + LINE_H < top + 1) continue;                        // 滚到上面去了
            if (y > bottom - 1) break;                                 // 下面的还看不见，不用画
            String n = String.valueOf(i + 1);
            g.text(font, n, gx + GUTTER - 6 - font.width(n), y, 0xFF808890);
        }
        g.disableScissor();
    }
}
