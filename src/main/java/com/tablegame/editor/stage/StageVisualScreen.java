package com.tablegame.editor.stage;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Interp;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.script.ScriptEditScreen;
import com.tablegame.stage.StageRenderer;

/**
 * 「界面」页（左栏五件之一）：**舞台的可视化视图** —— 画的就是脚本里 {@code screen} 块的真实展开结果。
 *
 * **画布没有自己的数据**：屏上每个框都是 {@code Parser.parse(def.script())} → {@code Interp.expand(画布, 预览身份)} 算出来的，本地不存布局、档里也不多一个字段。
 * 改也只改脚下那一行文本（{@link ScriptEdit}），写完走脚本那套「先真源解析 → 通过才上传」。
 * **预览引擎是个沙盒**：一对假席位 + 丢弃消息的 sink，只跑一次 {@code start()}（不跑的话 {@code hand[viewer]} 之类全空），**不喂 tick**；展开报错（语法 / 运行 / 步数预算）一律抓成底栏红字，不崩。
 *
 * **值位只摆一行**：编辑器自己造的那段（模板 / 源码）超出框宽就截断加「…」；字面量不截（那才是游戏里真会出现的字）。完整来源在选中时走底栏。
 * **锚点 = (行号, 行内序号, 实参序号)**：只有 {@code Ast.Num} / {@code Ast.Str} 那样算出来的字面量框能拖 —— 表达式驱动的框只读 + 底栏指路「位置由第 N 行算出来 → 去脚本改」。
 *
 * 渲染不是本屏的活：框全交给 {@link StageRenderer}（与 HUD / 全屏承载同一份实现），本屏只管画布摆在哪、选中描边、命中判定与鼠标手势。
 * 右键：框上 = **编辑** / 跳脚本行 / 删这一条（破坏性的最后）；空白 = **只有「＋新建框」**（类型与功能都在组件编辑页里选）。
 * ⚠ 行号 / 行内序号是**这一帧 hits 里的下标空间**：脚本文本一变（{@link #applyText}）就清选区。
 */
public class StageVisualScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }
    /**
     * 预览用的<b>一对假玩家</b>（不是这一局的人）：只决定「哪些分支走到」（{@code len(players) > 0}、
     * {@code viewer == players[0]} 之类），<b>名字不上屏</b> —— 屏上的文字一律显示脚本原文（结构视图）。
     *
     */
    private static final String[] FAKE_SEATS = { "玩家1", "玩家2" };
    /** 画布恒 320×180（脚本里那些坐标就是按它写的）。 */
    private static final int CANVAS_W = 320, CANVAS_H = 180;

    /** 看板那块没写 {@code place(…)} 时的缺省摆位（与线上同一组值，见 {@link GameDefinition.StageView}）。 */
    private static final double[] DEFAULT_PLATE = {
            GameDefinition.StageView.DEF_HUD_X, GameDefinition.StageView.DEF_HUD_Y,
            GameDefinition.StageView.DEF_HUD_W, GameDefinition.StageView.DEF_HUD_H };

    /** 拖板右下角的把手命中区（屏幕像素）。 */
    private static final int PLATE_HANDLE = 8;
    /** 右键菜单：宽与每行高（屏幕像素）。 */
    private static final int MENU_W = 220, MENU_ROW = 16;
    /** 右下角「拖角改大小」的命中方块边长。 */
    private static final int CORNER = 6;

    // ===== 右侧工具栏（照「世界」页那个 3D 视窗的样板）=====
    /** 工具栏：宽 / 按钮边长 / 按钮间距 / 顶部留白（与世界视窗同一组数：30 / 24）。 */
    private static final int TOOLBAR_W = 30, TOOL_BTN = 24, TOOL_GAP = 6, TOOL_TOP = 40;
    /** 弹框：宽 / 行高 / 内边距（贴在工具栏左边）。 */
    private static final int POP_W = 176, POP_ROW = 16, POP_PAD = 5;
    /** 工具栏上那三颗：0 = 选（选区）· 1 = 编（左键进编辑页 · 右键出菜单）· 2 = 网（参考网格开关）。 */
    private static final String[] TOOLS = { "选", "编", "网" };
    /** 「编」在 {@link #TOOLS} 里的下标：左键 = 单独编辑页（资产名 / 显示名）· 右键 = 这块舞台那几个动作。 */
    private static final int TOOL_EDIT = 1;
    /** 「网」在 {@link #TOOLS} 里的下标 —— 它是**开关**（网格显隐），不走弹框，见 drawToolbar / mouseClicked。 */
    private static final int TOOL_GRID = 2;

    /** 玩法编辑器（包私有：同包工具屏直接读写 def）。 */
    final GameEditorScreen parent;

    /** 解析结果（null = 脚本解析不了，画布画不出来）。 */
    private Ast.Script sc;
    /** 红字：语法错 / 展开错 / 预算掐断（底栏显示）。 */
    private String badScript;
    /** 当前画块：{@code main}（全屏）/ {@code hud}（看板）。 */
    private String canvas = "main";
    /** 要自动选中的源行号（0 = 不选）—— 可视化里双击**组件节点**跳过来时带的值。 */
    private final Integer wantLine;
    /** 那个选中只做一次（后面每次 init 是切舞台，别把选中抢回去）。 */
    private boolean didSelect;
    /** 这一版展开出来的框（预览结果；每帧再按缩放换算屏幕矩形）。 */
    private List<Ast.Box> boxes = List.of();
    /** 底栏那一行（红 = 没改成 / 只读，绿 = 改成了）。 */
    private String status = "";
    private boolean statusBad;
    /**
     * 当前选区（命中下标，升序去重）—— 单选就是只有一个；框选 / Ctrl 点可以好几个。
     *
     * <p>⚠ 它住在**这一帧的 hits 下标空间**里：脚本一变（{@link #applyText} → rebuild）/ 重展开都要清
     * （{@link #selClear}），不然会指到别的框上。
     */
    private final java.util.LinkedHashSet<Integer> sel = new java.util.LinkedHashSet<>();
    /** 选工具的模式：0 = 单选（Ctrl+点加 / 减）· 1 = 框选（空白处拉一个框，框住都算）。 */
    private int selMode;
    /** 框选：正拉着的那个矩形（屏幕像素 x0, y0, x1, y1）；null = 没在拉。 */
    private int[] band;
    /** 整组拖动：起手时每个被拖框的原始 (x, y) + 鼠标起手点（画布坐标）；null = 没在拖。 */
    private GroupDrag groupDrag;
    /** 开着的弹框（-1 = 没开）—— 同一时刻只开一个（与世界视窗同一套）。 */
    private int popupTool = -1;
    /**
     * 展开用的引擎实例（只跑一次 {@code start()}，不喂 tick / 不喂 pick）。
     *
     */
    private Interp sim;
    /**
     * 上一次 {@link #rebuild()} 时的脚本文本 —— init 靠它判「脚本在别处改过 ⇒ 回来必须重来」
     *。
     */
    private String builtFrom = "";
    /** 画布在屏幕上的左上角与缩放（每帧算一次，绘制与命中判定共用）。 */
    private int ox, oy;
    private double sx = 1, sy = 1;
    /** 每框的屏幕矩形 + 锚点（给命中和底栏用）。 */
    private final List<Hit> hits = new ArrayList<>();
    /** 交给 {@link StageRenderer} 的那份（与本屏的矩形同一套取整）。 */
    private GameDefinition.StageUi ui = GameDefinition.StageUi.empty();
    /** 看板那块「板」的摆位（屏幕比例 x / y / 宽 / 高）：来自脚本的 {@code place(…)}，没写 = 缺省。 */
    private double[] plate = DEFAULT_PLATE.clone();
    /** 板在本窗口里的像素矩形（{x, y, 宽, 高}）；非 hud 画布 = null。 */
    private int[] plateRect;
    /** 正在拖的板（null = 没在拖）。 */
    private PlateDrag plateDrag;
    /** 右键菜单：开着吗 + 屏幕位置 + 每行的字与动作。 */
    private boolean menuOpen;
    private double menuX, menuY;
    private final List<String> menuLabels = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    /** 正在拖的那个框（null = 没在拖）。 */
    private Drag drag;

    // 组件编辑走「右键 / 双击 → 编辑」进它自己的编辑页（StageCompScreen）。

    /** 画布上的参考网格开着吗（工具栏「网」那颗钮切；纯编辑辅助，不写进脚本）。 */
    private boolean gridOn = true;

    public StageVisualScreen(GameEditorScreen parent) {
        this(parent, "main");
    }

    /**
     * 指定一开始看哪块舞台（任意 {@code screen} 名字）。
     *
     * <p>给「可视化」里节点用：舞台节点跳过来看那块；组件节点跳过来还要**选中那一条**
     * （见三参构造）。名字不在脚本里时 {@link #rebuild()} 会回落到 main。
     */
    public StageVisualScreen(GameEditorScreen parent, String canvas) {
        this(parent, canvas, 0);
    }

    /**
     * 三参版：额外指定「要选中的源行号」（可视化里双击组件节点带进来的）。
     *
     * @param wantLine 源行号（0 = 不选）
     */
    public StageVisualScreen(GameEditorScreen parent, String canvas, int wantLine) {
        super(Component.literal("界面"));
        this.parent = parent;
        this.canvas = canvas == null || canvas.isEmpty() ? "main" : canvas;
        this.wantLine = wantLine;
    }

    // ===== 构建 =====

    @Override
    protected void init() {
        clearWidgets();
        // 只第一次进来建引擎；**脚本变了就重来** —— 否则从组件编辑页 / 脚本页
        // 改完脚本回来，画布还冻在旧的那一份上。
        if (sim == null || !parent.def.script().equals(builtFrom)) rebuild();
        // 本屏布局：
        //   ① **不再有「看板 X / 全屏 X」那排切换钮** —— 进哪一块由目录（舞台页）那行决定（构造参数 canvas）；
        //   ② **不搭左栏** —— 本屏是**真·全屏编辑页**（内容铺满 + 右侧一列工具栏，同 AreaViewScreen）。
        //      保存靠每次编辑的静默上传（applyText），返回走 Esc（下栏提示里写着）。
        // 双击「组件节点」跳过来：把那一条**选中**（只选一次 —— 之后 init 再跑是重展开，别把选中抢回去）
        if (wantLine > 0 && !didSelect && sim != null) {
            didSelect = true;
            selectLine(wantLine);
        }
    }

    /**
     * 按**源行号**选中第一个框（可视化里双击组件节点带过来的行号）。
     *
     * <p>找不到就说一句 —— 那条语句可能没产生框（`if` 没走到、或它本来就不画框，比如 `place`）。
     */
    private void selectLine(int line) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).line() == line) {
                sel.clear();                                 // 选区 = 就这一个（hits 与 boxes 同序，见 layout()）
                sel.add(i);
                say("已选中第 " + line + " 行那一条：拖框改位置、拉右下角改大小", false);
                return;
            }
        }
        say("第 " + line + " 行没画出框（可能这个分支没走到，或它本来不画框）", true);
    }

    // 本屏是「舞台」目录页下面那一层：看哪块由目录那行决定；不放舞台切换 / 新建舞台 / 身份下拉。

    /**
     * 现在这块画布是**看板类**吗 —— 看**声明**不看名字（之后看板的定义方式是
     * {@code screen 名字 hud { … }}；老档里名字就叫 hud 的那块，{@link Parser} 建 AST 时已标成 hud）。
     *
     */
    private boolean isHudCanvas() {
        return sc != null && sc.screens().containsKey(canvas) && sc.screens().get(canvas).hud();
    }

    /**
     * 进这一页先看哪块：给的名字不在脚本里（新档没有 {@code main}）就落到**第一块全屏**
     * （没有全屏才落第一块看板）—— 以前写死回落 {@code main}，档里没 main 时整页空着，
     * 非得点一下舞台按钮才出画面。
     */
    private String fallbackScreen() {
        if (sc == null) return "";
        String first = "";
        for (String name : sc.screens().keySet()) {          // screens() 保源码顺序（Parser 用保序 Map）
            if (!sc.screens().get(name).hud()) return name;
            if (first.isEmpty()) first = name;
        }
        return first;
    }

     /**
      * 解析 + 沙盒展开（屏上画的东西全从这里来）。
     *
     * <p>报错一律抓成 {@link #badScript}（底栏红字）：语法错 / 展开运行错 / 画块死循环被预算掐断，
     * 三种都是作者写错了脚本，不该把编辑器一起带走。
     */
    private void rebuild() {
        boxes = List.of();
        hits.clear();
        badScript = null;
        sim = null;
        builtFrom = parent.def.script();
        try {
            sc = Parser.parse(parent.def.script());
        } catch (Ast.ScriptError e) {
            sc = null;
            badScript = e.getMessage();
            return;
        }
        if (!hasScreen(canvas)) canvas = fallbackScreen();  // 那块没了（刚被删）/ 进来给的名字不在档里 → 落第一块全屏
        if (!hasScreen(canvas)) return;                     // 一块舞台都没有：还没写 screen 块
        try {
            sim = new Interp(sc, List.of(FAKE_SEATS), SILENT);
            // 预览也替这两个名字各发一次进局事件：脚本自己的名单是 on join 攒出来的（B 批起名单归脚本）
            for (String who2 : FAKE_SEATS) sim.acceptJoin(who2);
            sim.start();                                    // 只跑一次开局：编辑态看到的就是「开局那一屏」
            expandNow();
        } catch (Ast.ScriptError e) {
            badScript = e.getMessage();
        }
    }

    /**
     * 展开当前那一屏（数据全从 {@link #sim} 来，不重跑 start ⇒ 状态接得上）。
     *
     */
    private void expandNow() {
        boxes = List.of();
        hits.clear();
        selClear();                                        // 重展开后旧下标已经指不到原来的框了
        groupDrag = null;
        band = null;
        if (sim == null) return;
        try {
            boxes = sim.expand(canvas, FAKE_SEATS[0]);
            if (isHudCanvas()) {                            // 看板那块：摆位来自脚本的 place(…)，没写 = 缺省
                double[] pl = sim.lastPlace();
                plate = pl != null ? pl.clone() : DEFAULT_PLATE.clone();
            }
        } catch (Ast.ScriptError e) {
            badScript = e.getMessage();
        }
    }

    /** 预览用的 sink：消息全丢（预览不往聊天栏发东西、也不碰真局状态）。 */
    private static final Interp.Sink SILENT = new Interp.Sink() {
        @Override public void message(String text) { }
        @Override public void messageTo(String who, String text) { }
        @Override public void stateChanged() { }
    };

    private boolean hasScreen(String name) {
        return sc != null && sc.screens().containsKey(name);
    }

    // ===== 布局与命中 =====
    // ⚠ 换一块看只是换「展开哪一块」，脚本文本没变 ⇒ 不能 rebuild（会回 start），但必须 expandNow（否则画面停在上一块的框上）。

    /**
     * 每帧算一次：画布摆在哪 + 缩放 + 每框的屏幕矩形。
     *
     * <p>取整公式与 {@link StageRenderer#draw} 里那句逐字相同 —— 命中判定和画出来的像素必须对得上。
     */
    private void layout() {
        hits.clear();
        plateRect = null;
        if (isHudCanvas()) {
            // 看板那块按脚本里 place(x, y, 宽, 高) 的真实比例摆（与客户端同一套公式：先算板、画面在板里等比居中）
            // —— 编辑器里拖/拉的，就是真跑时它待的地方与大小
            int pw = Math.max(24, (int) Math.round(plate[2] * width));
            int ph = Math.max(24, (int) Math.round(plate[3] * height));
            int px = clamp((int) Math.round(plate[0] * width), 0, Math.max(0, width - pw));
            int py = clamp((int) Math.round(plate[1] * height), 0, Math.max(0, height - ph));
            plateRect = new int[] { px, py, pw, ph };
            // 两轴各算各的（画面填满整块板）：板改宽只横向变、改高只纵向变 —— 与客户端同一套
            sx = pw / (double) CANVAS_W;
            sy = ph / (double) CANVAS_H;
            ox = px;
            oy = py;
        } else {
            // 全屏那块：320×180 等比铺在**可用区**里（上边两行标题、下边两行底栏）。
            // 等比不可改：运行时全屏那块也走 StageRenderer.fitScale（等比）。
            int x0 = 8, y0 = 42, x1 = width - TOOLBAR_W - 8, y1 = height - 40;
            sx = sy = Math.min((x1 - x0) / (double) CANVAS_W, (y1 - y0) / (double) CANVAS_H);
            ox = (int) Math.round((x0 + x1) / 2.0 - CANVAS_W * sx / 2);
            oy = (int) Math.round((y0 + y1) / 2.0 - CANVAS_H * sy / 2);
        }
        List<GameDefinition.BoxDef> defs = new ArrayList<>();
        if (hasScreen(canvas)) {
            for (int i = 0; i < boxes.size(); i++) {
                Ast.Box b = boxes.get(i);
                double bx = b.x(), by = b.y(), bw = b.w(), bh = b.h();
                if (drag != null && drag.hit == i) {        // 拖动中：画预览位置（松手才写回脚本）
                    bx = drag.x;
                    by = drag.y;
                    bw = drag.w;
                    bh = drag.h;
                }
                int idx = indexOf(b);                       // 行内序号：命中 / 写回 / 结构视图都要它
                hits.add(new Hit(b, b.line(), idx,
                        ox + (int) Math.round(bx * sx), oy + (int) Math.round(by * sy),
                        (int) Math.round(bw * sx), (int) Math.round(bh * sy)));
                defs.add(boxDef(b, i, idx, bx, by, bw, bh));
            }
        }
        // 舞台铺底（脚本 bg("…")）传空串：编辑器画布铺自己的深色底（背景透明度在编辑界面不用可见），
        // 背景值只在底栏用文字报，不模拟效果。
        ui = new GameDefinition.StageUi(CANVAS_W, CANVAS_H, defs, "");
        // ⚠ 凡是拿主选中去 hits 里取框的地方，只许喂 primary() 校验过的本地量
        //（它自己夹下标；hits 每帧重建，选区下标可能已失效，否则会 IndexOutOfBounds）。
    }

    /**
     * 展开出来的一框 → 渲染器的框。内容与缺省样式照宿主那条线（{@code HostManager.expandComponents} + {@link StageRenderer#styleOf}）：
     * 色块 / 文本框无底色 = 全透明、输入框深底、绘画区走画板、字色缺省白。**文字走结构视图**：内容实参是表达式时换成**脚本原文**（见 {@link #srcText}）。
     */
    GameDefinition.BoxDef boxDef(Ast.Box b, int i, int index, double x, double y, double w, double h) {
        GameDefinition.BoxContent c = switch (b.kind()) {
            case "text" -> new GameDefinition.BoxContent("text", fit(srcText(b, index, 4), w), "");
            case "input" -> new GameDefinition.BoxContent("input", fit(srcText(b, index, 4), w), "");
            case "paint" -> new GameDefinition.BoxContent("image", "", "");
            case "img" -> new GameDefinition.BoxContent("img", b.text(), "");   // 图片框（期12）：b.text() = 资产名
            default -> new GameDefinition.BoxContent("none", "", "");
        };
        String bg = !b.bg().isEmpty() ? b.bg() : (b.kind().equals("input") ? "#202028" : "#00000000");
        String color = b.color().isEmpty() ? "#FFFFFF" : b.color();
        return new GameDefinition.BoxDef("v" + i, x, y, w, h, c, new GameDefinition.BoxStyle(bg, color));
    }

    /** 结构视图：这个框要显示的文字 —— **值的位置摆它的来源**。定位不到那条画语句（脚本刚被手改过）→ 保守回落实值。
     *  ⚠ 模板长了按框宽折行、放不下的溢出框（渲染器既定口径：不省略、不缩字）。 */
    private String srcText(Ast.Box b, int index, int arg) {
        String src = parent.def.script();
        if (index < 0 || ScriptEdit.literal(src, b.line(), index, arg)) return b.text();
        ScriptEdit.DrawAt d = ScriptEdit.find(src, b.line(), index);
        if (d == null || arg >= d.args().size()) return b.text();
        String t = ScriptEdit.template(d.args().get(arg), ScriptEdit.partParamsAt(src, b.line()));
        return t == null ? b.text() : t;
    }

    /**
     * 值位那一段最多占**一行**：超出框宽就截断加「…」（否则糊掉整块）。只截**编辑器自己造的那段**（模板 / 源码）——
     * 字面量照旧（那是真会在游戏里出现的字，长了就该挤，作者要看得见后果）。值位显示的本来是**值**不是来源 ⇒ 截断不丢信息，选中走底栏看全。
     */
    private String fit(String text, double boxW) {
        int max = (int) Math.round(boxW * sx) - 6;          // 渲染器折行用的正是「框宽 - 6」
        if (max < 8 || font.width(text) <= max) return text;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "…") > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    /**
     * 展开出来的框 → 它那条画语句的<b>行内序号</b>（-1 = 定位不到）。
     *
     * <p>AST 只带行号、不带列位置，所以按「这一行里第几条同种画语句」对上：{@code paint} 那条是它升级的
     * {@code box}；同一行写了两条同种时取第一条（罕见，且拖哪条都是改同一行，效果一样）。
     */
    private int indexOf(Ast.Box b) {
        // 两边的 kind 口径不一样（脚本里带文字的框也叫 box，运行时叫 text）⇒ 走对表，
        // 不许直接比字符串：不然带文字的框行内序号成 -1，编辑页「定位不到」、也拖不动
        for (int i = 0; ; i++) {
            ScriptEdit.DrawAt d = ScriptEdit.find(parent.def.script(), b.line(), i);
            if (d == null) return -1;
            if (ScriptEdit.sameDrawKind(d.kind(), b.kind())) return i;
        }
    }

    /** 这几个实参都算得出字面量才可拖（{@code Ast.Num} / {@code Ast.Str}）—— 表达式驱动 = 只读。 */
    private boolean literal(int line, int index, int... args) {
        if (index < 0) return false;
        String src = parent.def.script();
        for (int a : args) if (!ScriptEdit.literal(src, line, index, a)) return false;
        return true;
    }

    /** 点到了哪个框（后画的在上，所以从后往前找）。 */
    private int hitAt(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) return i;
        }
        return -1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }

    /** 屏上的一个框：展开结果 + 锚点（行 / 行内序号）+ 屏幕矩形。 */
    private static final class Hit {
        final Ast.Box b;
        final int line, index;
        final int x, y, w, h;

        Hit(Ast.Box b, int line, int index, int x, int y, int w, int h) {
            this.b = b;
            this.line = line;
            this.index = index;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** 正在拖的字面量框：记下起手时的框与鼠标位置（都在画布坐标里），松手换成实参文本。 */
    private static final class Drag {
        final int hit;
        final boolean corner;                               // true = 拖右下角改大小
        final double fx, fy, fw, fh;
        final double sx, sy;
        double x, y, w, h;

        Drag(int hit, boolean corner, double fx, double fy, double fw, double fh, double sx, double sy) {
            this.hit = hit;
            this.corner = corner;
            this.fx = fx;
            this.fy = fy;
            this.fw = fw;
            this.fh = fh;
            this.sx = sx;
            this.sy = sy;
            this.x = fx;
            this.y = fy;
            this.w = fw;
            this.h = fh;
        }
    }

    /**
     * 整组拖动：选区里多于一个时，拖任意一个选中的框 = 整组挪。
     *
     * <p>只拖**位置是字面量**的那些 —— 表达式驱动的框留在原地（把它算出来的坐标写回脚本 = 把
     * 「位置随数据跑」锁死，与单选那条口径一模一样），「几个没动」在回执里说清楚。
     */
    private static final class GroupDrag {
        /** 被拖的框下标 → 它的起手 (x, y)（画布坐标）。 */
        final java.util.Map<Integer, double[]> from = new java.util.LinkedHashMap<>();
        /** 起手时鼠标在画布坐标里的位置（算整组位移的基准）。 */
        final double sx, sy;
        /** 表达式驱动、这一轮拖不动的框数（松手时报告）。 */
        int skipped;
        double dx, dy;

        GroupDrag(double sx, double sy) {
            this.sx = sx;
            this.sy = sy;
        }
    }

    // ===== 鼠标 =====

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        boolean ctrl = (event.modifiers() & 2) != 0;         // GLFW_MOD_CONTROL（与世界视窗同一套）
        // ---- 右侧工具栏 / 它的弹框在最上层：先给它们吃（同世界视窗那个顺序）----
        int tool = toolbarHit(mx, my);
        if (tool >= 0) {
            if (tool == TOOL_GRID) {                         // 「网」= 开关（2026-09-27）：不进弹框那套
                gridOn = !gridOn;
                say(gridOn ? "网格：开（细线 4px = 拖框吸附同格 · 主格 20px）" : "网格：关", false);
                return true;
            }
            if (tool == TOOL_EDIT && event.button() == 1) {   // 「编」右键 = 这块舞台那几个动作（2026-09-27 用户要）
                openEditMenu(mx, my);
                return true;
            }
            if (tool == TOOL_EDIT) {                         // 「编」左键 = 单独编辑页（资产名 / 显示名）
                popupTool = -1;
                openEditorPage();
                return true;
            }
            popupTool = popupTool == tool ? -1 : tool;       // 再点同一颗 = 收起
            return true;
        }
        int pop = popRowAt(mx, my);
        if (pop >= 0) {
            popPick(pop);
            return true;
        }
        if (popupTool >= 0) popupTool = -1;                  // 点别处 = 收弹框（右键菜单照旧往下走）
        if (super.mouseClicked(event, doubled)) return true;
        if (menuOpen) {
            int row = menuRowAt(mx, my);
            menuOpen = false;
            if (row >= 0) menuActions.get(row).run();
            return true;
        }
        layout();
        if (event.button() == 1) {
            // 右键三种：多选 → 只有「删除选中的 N 条」· 框上 → 编辑 / 跳脚本 / 删 ·
            // 空白 → 只有「＋新建框」（类型与功能都搬进组件编辑页了，这里不再摆一堆预设）
            if (sel.size() > 1) openMultiMenu(mx, my);
            else {
                int hit = hitAt(mx, my);
                if (hit >= 0) openBoxMenu(hits.get(hit), mx, my);
                else openMenu(mx, my);
            }
            return true;
        }
        if (event.button() != 0) return false;
        // ---- 左键：选区（单选 / Ctrl 加选 / 框选）----
        int hit = hitAt(mx, my);
        if (hit >= 0) {
            if (ctrl) {
                if (!sel.remove(hit)) sel.add(hit);              // Ctrl = 加 / 减
            } else if (!sel.contains(hit)) {
                sel.clear();                                     // 普通点一个 = 单选（替掉原选区）
                sel.add(hit);
            }
        } else {
            if (!ctrl) selClear();                               // 点空白：不按 Ctrl = 清空
            if (selMode == 1) {                                  // 框选模式：起手拉那个矩形
                band = new int[] { (int) mx, (int) my, (int) mx, (int) my };
                drag = null;
                plateDrag = null;
                return true;
            }
        }
        // 双击一块框 = 进它的编辑页（两下比右键快）
        if (doubled && hit >= 0) {
            sel.clear();
            sel.add(hit);
            openCompPage(hits.get(hit));
            return true;
        }
        drag = null;
        int p = primary();
        if (plateRect != null) {                             // 看板那块：先看是不是在动「板」自己
            boolean inPlate = mx >= plateRect[0] && mx < plateRect[0] + plateRect[2]
                    && my >= plateRect[1] && my < plateRect[1] + plateRect[3];
            boolean corner = mx >= plateRect[0] + plateRect[2] - PLATE_HANDLE
                    && my >= plateRect[1] + plateRect[3] - PLATE_HANDLE;
            if (inPlate && (corner || p < 0)) {              // 拉角优先；板里的框照旧归框
                if (!plateEditable()) return true;
                plateDrag = new PlateDrag(corner, plate.clone(), mx, my);
                say(corner ? "拉板：改 place 的宽 / 高（松手写回脚本）" : "拖板：改 place 的 x / y（松手写回脚本）", false);
                return true;
            }
        }
        if (p < 0) return true;
        Hit h = hits.get(p);
        // 整组拖动：选区里多于一个 → 整组挪（不做拉角改大小：一组框各自改大小没有意义）
        if (sel.size() > 1) {
            GroupDrag g = new GroupDrag((mx - ox) / sx, (my - oy) / sy);
            for (int i : sel) {
                Hit hi = hits.get(i);
                if (literal(hi.line, hi.index, 0, 1)) g.from.put(i, new double[] { hi.b.x(), hi.b.y() });
                else g.skipped++;
            }
            groupDrag = g;
            say("整组拖动 " + sel.size() + " 个" + (g.skipped > 0
                    ? "（其中 " + g.skipped + " 个是表达式驱动，不动）" : "") + "（松手写回脚本）", false);
            return true;
        }
        boolean move = literal(h.line, h.index, 0, 1);       // 位置两个实参都是字面量？
        boolean size = literal(h.line, h.index, 2, 3);       // 尺寸两个实参都是字面量？
        boolean corner = size && mx >= h.x + h.w - CORNER && my >= h.y + h.h - CORNER;
        if (!corner && !move) {                              // 表达式驱动 / 定位不到 → 只读 + 指路
            say("只读：位置由第 " + h.line + " 行算出来 → 去脚本改那一行", true);
            return true;
        }
        drag = new Drag(p, corner, h.b.x(), h.b.y(), h.b.w(), h.b.h(),
                (mx - ox) / sx, (my - oy) / sy);
        say(corner ? "拖角改大小（松手写回脚本）" : "拖动（松手写回脚本）", false);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (super.mouseDragged(event, dx, dy)) return true;
        if (band != null) {                                  // 框选：矩形跟着鼠标长
            band[2] = (int) event.x();
            band[3] = (int) event.y();
            return true;
        }
        if (groupDrag != null) {                             // 整组拖动：整组的位移 = 鼠标走了多少
            double gx = (event.x() - ox) / sx, gy = (event.y() - oy) / sy;
            groupDrag.dx = Math.round(gx - groupDrag.sx);
            groupDrag.dy = Math.round(gy - groupDrag.sy);
            say("整组挪 " + (int) groupDrag.dx + " / " + (int) groupDrag.dy + "（松手写回脚本）", false);
            return true;
        }
        if (plateDrag != null) {
            if (plateDrag.corner) {                          // 拉右下角：角跟着鼠标，宽高夹在「不出屏」里
                plate[2] = clampRatio(event.x() / (double) width - plate[0], 0.05, 1 - plate[0]);
                plate[3] = clampRatio(event.y() / (double) height - plate[1], 0.05, 1 - plate[1]);
                say("板占屏幕 " + pct(plate[2]) + " × " + pct(plate[3]) + "（松手写回脚本）", false);
            } else {                                         // 挪板：整个板跟着鼠标走，压着屏幕边就不动
                plate[0] = clampRatio(plateDrag.from[0] + (event.x() - plateDrag.mx) / (double) width,
                        0, 1 - plate[2]);
                plate[1] = clampRatio(plateDrag.from[1] + (event.y() - plateDrag.my) / (double) height,
                        0, 1 - plate[3]);
                say("板在屏幕 " + pct(plate[0]) + " / " + pct(plate[1]) + "（松手写回脚本）", false);
            }
            return true;
        }
        if (drag == null) return false;
        double cx = (event.x() - ox) / sx, cy = (event.y() - oy) / sy;
        if (drag.corner) {
            drag.w = Math.max(4, Math.round(drag.fw + (cx - drag.sx)));
            drag.h = Math.max(4, Math.round(drag.fh + (cy - drag.sy)));
            say("宽 " + Math.round(drag.w) + " 高 " + Math.round(drag.h) + "（松手写回脚本）", false);
        } else {
            // 拖框时**至少留 4 px 在画布里** —— 别整块拖到画布外看不见（看不见就点不着，
            // 只能去脚本页改回来）。负坐标本身合法（HUD 里做「从屏外滑进来」要用负数），
            // 所以只禁止「整块跑出画布」，不禁止越界一部分。
            drag.x = clamp((int) Math.round(drag.fx + (cx - drag.sx)), 4 - (int) Math.round(drag.fw), CANVAS_W - 4);
            drag.y = clamp((int) Math.round(drag.fy + (cy - drag.sy)), 4 - (int) Math.round(drag.fh), CANVAS_H - 4);
            say("x " + Math.round(drag.x) + " y " + Math.round(drag.y) + "（松手写回脚本）", false);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (band != null) {                                  // 框选：松手就定选区（按着 Ctrl = 加进原选区）
            commitBand((event.modifiers() & 2) != 0);
        }
        GroupDrag g = groupDrag;
        groupDrag = null;
        if (g != null) commitGroup(g);                       // 整组拖动：松手写回
        Drag d = drag;
        drag = null;
        if (d != null) commit(d);
        if (plateDrag != null) {
            boolean corner = plateDrag.corner;
            plateDrag = null;
            commitPlate(corner);
        }
        return super.mouseReleased(event);
    }

    /**
     * 松手：板的四个比例 → 脚本里那一行 {@code place(…)} → {@link #applyText}（先解析验证，通过才上传）。
     *
     * <p>脚本里还没写过 {@code place} 时**直接补一行**（写当前板值）—— 新档第一次拖板就能用，不必先去脚本页手写。
     *
     */
    private void commitPlate(boolean corner) {
        String src = parent.def.script();
        double[] p = plateOk();                                   // 先夹进允许范围（板最多占满整屏）
        ScriptEdit.At at = ScriptEdit.findPlace(src, canvas);
        if (at == null) {
            // 补一行 place 也要补在**当前看板那块**里（不是写死的 hud —— 舞台名放开后看板可以叫 score / tips）
            ScriptEdit.Result r = ScriptEdit.addDraw(src, canvas, "place(" + ratio(p[0]) + ", " + ratio(p[1])
                    + ", " + ratio(p[2]) + ", " + ratio(p[3]) + ")");
            if (r.text().equals(src)) {
                say(r.note(), true);
                return;
            }
            applyText(r.text(), r.note());
            return;
        }
        // 一次写**全四个比例**（不只写手势碰的那两个）：① 夹过范围后四个永远合法；② 手改坏过的 place
        // （例如被上一版的像素吸附写成 0, 0, 0, 0）拖一下板就自己补全了 —— 不用先跑脚本页改。
        ScriptEdit.DrawAt cur = ScriptEdit.find(src, at.line(), at.index());
        List<String> now = cur == null ? List.of() : cur.args();
        String text = src;
        int changed = 0;
        for (int i = 0; i < 4 && i < now.size(); i++) {
            String want = ratio(p[i]);
            if (now.get(i).strip().equals(want)) continue;         // 这一格本来就对，不动它
            ScriptEdit.Result r = ScriptEdit.setArg(text, at.line(), at.index(), i, want);
            if (r.text().equals(text)) {
                say(r.note(), true);
                return;                                          // 被拒收 = 整次不动（不留半改文本）
            }
            text = r.text();
            changed++;
        }
        if (changed == 0) {
            say("板没变，没动", false);
            return;
        }
        applyText(text, "已改脚本第 " + at.line() + " 行的 place（板 = 屏幕 " + pct(p[0]) + " / " + pct(p[1])
                + "，占 " + pct(p[2]) + " × " + pct(p[3]) + "）");
    }

    /**
     * 把当前板值夹进**允许的最大范围**：宽 / 高 ∈ [0.05, 1]，x / y ∈ [0, 1-宽/高] ——
     * 即「板最多占满整个屏幕」（限制框要尽可能最大），写回脚本的四个比例永远合法。
     */
    private double[] plateOk() {
        double[] v = new double[4];
        v[2] = clampRatio(plate[2], 0.05, 1);
        v[3] = clampRatio(plate[3], 0.05, 1);
        v[0] = clampRatio(plate[0], 0, 1 - v[2]);
        v[1] = clampRatio(plate[1], 0, 1 - v[3]);
        return v;
    }

    /** HUD 那块「板」的摆位人话（底栏上那一行）—— 带上「最多占满整屏」这条限制口径。 */
    private String plateInfo() {
        return "板 = 屏幕 " + pct(plate[0]) + " / " + pct(plate[1]) + "，占 " + pct(plate[2]) + " × " + pct(plate[3])
                + "（脚本里的 place(…)：拖板 / 拉右下角可改；最多占满整屏，不会越界）";
    }

    /** place 的四个参数都能改吗（字面量才行；还没写过 place = 可以先补一行，也算可改）。 */
    private boolean plateEditable() {
        ScriptEdit.At at = ScriptEdit.findPlace(parent.def.script(), canvas);
        if (at == null) return true;
        for (int a = 0; a < 4; a++) {
            if (!ScriptEdit.literal(parent.def.script(), at.line(), at.index(), a)) {
                say("只读：place 的第 " + (a + 1) + " 个参数是表达式 → 去脚本第 " + at.line() + " 行改", true);
                return false;
            }
        }
        return true;
    }

    /** 比例 → 脚本里写的数字：最多 3 位小数，末尾的 0 去掉（0.66 / 0.05 / 0.3）。 */
    private static String ratio(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.3f", v);
        while (s.contains(".") && (s.endsWith("0"))) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static double clampRatio(double v, double lo, double hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }

    /** 正在拖的那块「板」：起手时的板值 + 鼠标起手点（拖板平移用）。 */
    private static final class PlateDrag {
        final boolean corner;
        final double[] from;
        final double mx, my;
        PlateDrag(boolean corner, double[] from, double mx, double my) {
            this.corner = corner;
            this.from = from;
            this.mx = mx;
            this.my = my;
        }
    }

    /**
     * 松手：新坐标 → 那一行的实参文本 → 走 {@link #applyText}（先本地解析验证，通过才上传）。
     *
     * <p>两次 {@code setArg} 连改（x / y 或 宽 / 高）：任一次被拒就整次不动 —— 不上传半改的文本。
     */
    private void commit(Drag d) {
        if (d.hit < 0 || d.hit >= hits.size()) return;
        Hit h = hits.get(d.hit);
        boolean same = d.corner
                ? Math.round(d.w) == Math.round(h.b.w()) && Math.round(d.h) == Math.round(h.b.h())
                : Math.round(d.x) == Math.round(h.b.x()) && Math.round(d.y) == Math.round(h.b.y());
        if (same) {
            say("位置没变，没动", false);
            return;
        }
        String src = parent.def.script();
        ScriptEdit.Result r1 = ScriptEdit.setArg(src, h.line, h.index, d.corner ? 2 : 0,
                num(snap(d.corner ? d.w : d.x)));
        if (r1.text().equals(src)) {
            say(r1.note(), true);
            return;
        }
        ScriptEdit.Result r2 = ScriptEdit.setArg(r1.text(), h.line, h.index, d.corner ? 3 : 1,
                num(snap(d.corner ? d.h : d.y)));
        if (r2.text().equals(r1.text())) {
            say(r2.note(), true);
            return;
        }
        applyText(r2.text(), r2.note() + "（脚本第 " + h.line + " 行）");
    }

    /** 空白右键 = **只有「＋新建框」**（类型与功能都在组件编辑页里选）。插的是**字面量**代码（追加到当前画块末尾，走 {@link ScriptEdit#addDraw}）；
     *  默认坐标靠中心、按已有框数逐次下移（不要一新建就叠在同一点上）。 */
    private void openMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        if (!hasScreen(canvas)) {
            say("还没有「screen " + canvas + "」这块画布 —— 先新建它", true);
            return;
        }
        addItem("＋ 新建框", "box(120, " + row(boxes.size()) + ", 80, 24, \"#3A3A52\")");
        menuOpen = true;
        menuX = Math.min(mx, Math.max(4, width - TOOLBAR_W - MENU_W - 4));
        menuY = Math.min(my, Math.max(4, height - MENU_ROW * menuLabels.size() - 6));
    }

    /** 新建框的默认行 y：靠中心往下排，第 n 个再低一点（不叠在一起）。 */
    private static int row(int n) {
        return 30 + (n % 10) * 12;
    }

    /** 框上右键：**编辑** / 跳到脚本那一行 / 删除这一条（破坏性的最后）。「编辑」= 进这块组件自己的编辑页（类型 / 几何 / 样式 / 内容 / 属性都在那儿）。
     *  画语句来自 {@code part} 体时删的是**部件体那一行** → 所有调用处都不再画它（标签里写明）；定位不到时 {@code removeDraw} 原样返回 + 一句说明，不会误删别的行。 */
    private void openBoxMenu(Hit h, double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        addAction("编辑", () -> openCompPage(h));
        addAction("跳到脚本第 " + h.line + " 行", () ->
                Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, h.line)));
        boolean inPart = ScriptEdit.blockAt(parent.def.script(), h.line).startsWith("part ");
        String scope = inPart ? " · 影响所有调用处" : "";
        addAction("删除这一条（第 " + h.line + " 行" + scope + "）", () -> {
            ScriptEdit.Result r = ScriptEdit.removeDraw(parent.def.script(), h.line, h.index);
            if (r.text().equals(parent.def.script())) {
                say(r.note(), true);
                return;
            }
            applyText(r.text(), r.note());
        });
        menuOpen = true;
        menuX = Math.min(mx, Math.max(4, width - TOOLBAR_W - MENU_W - 4));
        menuY = Math.min(my, Math.max(4, height - MENU_ROW * menuLabels.size() - 6));
    }

    /**
     * 多选之后右键：**只给一项「删除选中的 N 条」** —— 编辑页一次编一个，
     * 整组编辑没有意义；整组挪 / 一起删仍然是画布上的动作。
     */
    private void openMultiMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        addAction("删除选中的 " + sel.size() + " 条", this::deleteSelected);
        menuOpen = true;
        menuX = Math.min(mx, Math.max(4, width - TOOLBAR_W - MENU_W - 4));
        menuY = Math.min(my, Math.max(4, height - MENU_ROW * menuLabels.size() - 6));
    }

    private void addItem(String label, String code) {
        addAction(label, () -> {
            ScriptEdit.Result r = ScriptEdit.addDraw(parent.def.script(), canvas, code);
            if (r.text().equals(parent.def.script())) {
                say(r.note(), true);
                return;
            }
            applyText(r.text(), r.note());
        });
    }

    private void addAction(String label, Runnable run) {
        menuLabels.add(label);
        menuActions.add(run);
    }

    private int menuRowAt(double mx, double my) {
        if (mx < menuX || mx >= menuX + MENU_W || my < menuY) return -1;
        int row = (int) ((my - menuY) / MENU_ROW);
        return row >= 0 && row < menuLabels.size() ? row : -1;
    }

    /**
     * 吸附（段6）：拖框 / 拉角 / 拖板的落点对齐到 **4 的倍数** —— 阶段的坐标单位就是像素，
     * 4 一格肉眼看不出来、又能让框整整齐齐对上线。
     *
     * <p>ponytail: 只对齐网格，不做「吸附到别的框的边」—— 真需要再加（那时得先算候选边、再挑最近的一条）。
     */
    private static double snap(double v) {
        return Math.round(v / 4.0) * 4;
    }

    /** 新坐标写成实参文本（整数就写整数，别在脚本里撒一堆 .0）。 */
    private static String num(double v) {
        long l = Math.round(v);
        return Math.abs(v - l) < 1e-6 ? String.valueOf(l) : String.valueOf(v);
    }

    /**
     * 写回统一出口：先过真源解析（错就拒收、文本原样留着），通过才换进内存 def 并<b>静默</b>上传
     * （每拖一下就存一次是对的，但屏上不该刷回执 —— 同脚本画布那套）。
     */
    private void applyText(String text, String note) {
        String err = parent.applyScript(text);            // 写回唯一的出口（解析 / 拒收 / 静默保存都在它里面）
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        selClear();                                        // 脚本文本都换了，旧选区指到的框已经不是那些了
        say(note, false);
        clearWidgets();
        rebuild();                                          // 脚本文本换了 ⇒ 必须重新解析（不 rebuild 画布会停在旧 AST 上）
        init();
    }

    /** 底栏那一行。 */
    private void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 段6 手感：Ctrl+Z 撤销 · Ctrl+Y（或 Ctrl+Shift+Z）重做
        if (parent.handleHistoryKey(event.key(), event.hasControlDownWithQuirk(), event.hasShiftDown(), 90, 89)) {
            clearWidgets();
            init();
            return true;
        }
        // Esc：本屏现在是**目录下面那一层**（从「舞台」页点一块进来）—— Esc 回目录，
        // 而不是直接关掉整个编辑器（那是「一进去就是画布」那个年代的手感）。
        if (event.key() == 256 && popupTool < 0) {
            Minecraft.getInstance().setScreen(new StageDirScreen(parent));
            return true;
        }
        if (event.key() == 256) {                            // 弹框开着：先收弹框（再按一下才回目录）
            popupTool = -1;
            return true;
        }
        // Delete / Backspace = 把选中的那些画语句删掉（⚠ 属性栏的输入框正被编辑时不抢键，
        // 不然在格子里删字会变成删框 —— 那一下完全出乎意料）
        boolean typing = getFocused() instanceof EditBox;
        if ((event.key() == 261 || event.key() == 259) && !typing) {
            deleteSelected();
            return true;
        }
        return super.keyPressed(event);
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

    // ===== 画 =====

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 真·全屏页（照 AreaViewScreen）：不画左栏底板也不搭栏 —— 内容铺满整个窗口，
        // 右侧留一条工具栏。左边界从 10 起排（与工具栏那套 30/24 配合）。
        int cx = (width - TOOLBAR_W) / 2;
        // 标题：本屏是目录（舞台页）点一行进来的那一层，标题要说清「哪一类、哪一块」；
        // 写了 bg("…") 就把那个颜色串带出来（**不模拟**它的透明度效果 —— 编辑界面不用可见）
        String bgNow = ScriptEdit.screenBgOf(parent.def.script(), canvas);
        g.centeredText(font, Component.literal("舞台 · " + (isHudCanvas() ? "HUD（看板）" : "全屏界面")
                + titleName() + " · " + boxes.size() + " 个框"
                + (bgNow.isEmpty() ? "" : " · 背景 " + bgNow)), cx, 14, 0xFFFFFFFF);
        // 顶栏一行写短点：长句会被两边裁掉（左边压工具栏）—— 详细口径见类 javadoc 与 ScriptEdit.template
        g.centeredText(font, Component.literal("预览 · screen " + canvas + " 的真实展开 · 值的位置显示它的来源"),
                cx, 27, 0xFF909090);

        layout();
        int cw = (int) Math.round(CANVAS_W * sx), ch = (int) Math.round(CANVAS_H * sy);
        if (plateRect != null) {
            // 「屏幕边界」= 板能占的最大范围（板最多占满它）：板拖到哪、拉多大都在这个框里（拖拽时已经夹过）
            g.outline(1, 1, width - TOOLBAR_W - 2, height - 2, 0x50FFFFFF);
            // 板自己：描边 + 右下角把手（拖板 = 挪位置，拉角 = 改大小）—— 真跑时常驻的就是这个矩形
            g.outline(plateRect[0], plateRect[1], plateRect[2], plateRect[3], 0x6080C0FF);
            g.fill(plateRect[0] + plateRect[2] - 5, plateRect[1] + plateRect[3] - 5,
                    plateRect[0] + plateRect[2], plateRect[1] + plateRect[3], 0xC0FFD400);
        }
        g.fill(ox, oy, ox + cw, oy + ch, 0xFF0B0D12);
        if (gridOn && !isHudCanvas()) drawGrid(g, ox, oy, cw, ch, sx, sy);   // 参考网格：只在全屏画布上（HUD 里不要，用户 2026-09-27 定）；纯编辑辅助，不写进脚本
        if (badScript == null && !hits.isEmpty()) StageRenderer.draw(g, ui, ox, oy, sx, sy);
        g.outline(ox, oy, cw, ch, 0xFF3A3E4A);

        // 「运行时看不见」的框补一圈轮廓（给完全透明的框加框线、**越透明越显眼**）——
        // 只看作者在颜色串里**明确写了** 8 位那种（不能拿展开后的底色判：文本框的底本来就是全透明，
        // 那会把每个文本框都描一圈）。运行时一点不加，纯粹是编辑期的可见性辅助。
        for (Hit h : hits) {
            int a = writtenAlpha(h);
            if (a >= 255) continue;
            int strength = 0x30 + (255 - a) * 0x60 / 255;    // 0x30（几乎不透明）~ 0x90（全透明）
            g.outline(h.x - 1, h.y - 1, h.w + 2, h.h + 2, (strength << 24) | 0xFFE0A0);
        }

        // 选区：一个（黄，属性栏管它） / 多个（青，可整组拖 / 一起删）
        for (int i : sel) {
            if (i < 0 || i >= hits.size()) continue;
            Hit s = hits.get(i);
            g.outline(s.x - 1, s.y - 1, s.w + 2, s.h + 2, sel.size() == 1 ? 0xFFFFD400 : 0xFF9CE0FF);
        }
        if (band != null) {                                   // 框选：正拉着的那一个矩形
            int bx0 = Math.min(band[0], band[2]), by0 = Math.min(band[1], band[3]);
            int bx1 = Math.max(band[0], band[2]), by1 = Math.max(band[1], band[3]);
            g.fill(bx0, by0, bx1, by1, 0x2830A0FF);
            g.outline(bx0, by0, bx1 - bx0, by1 - by0, 0xC0A0D0FF);
        }
        if (badScript != null) {
            g.centeredText(font, Component.literal("画不出来：" + badScript), cx, (76 + height - 60) / 2, 0xFFFF9090);
        } else if (sc != null && sc.screens().isEmpty()) {
            // 一块舞台都没有（界面页不再放「＋新舞台」—— 新建在可视化右键或脚本里）
            g.centeredText(font, Component.literal("还没有任何舞台 —— 去「舞台」目录页新建，或脚本里写 screen 名字 { }"),
                    cx, (76 + height - 60) / 2, 0xFF909090);
        } else if (boxes.isEmpty()) {
            g.centeredText(font, Component.literal("这块画布还是空的 —— 右键空白处：框 / 文本 / 按钮 / 输入框 / 绘画区"),
                    cx, (76 + height - 60) / 2, 0xFF909090);
        }
        if (menuOpen) {
            int h = MENU_ROW * menuLabels.size() + 4;
            g.fill((int) menuX, (int) menuY, (int) menuX + MENU_W, (int) menuY + h, 0xF0181C24);
            g.fill((int) menuX, (int) menuY, (int) menuX + MENU_W, (int) menuY + 1, 0xFF606878);
            for (int i = 0; i < menuLabels.size(); i++) {
                g.text(font, DrawBoardMenuUi.ellipsis(menuLabels.get(i), 32), (int) menuX + 6,
                        (int) menuY + 4 + i * MENU_ROW, i == menuRowAt(mouseX, mouseY) ? 0xFFFFE080 : 0xFFD0D0D0);
            }
        }
        int one = primary();                                  // 恰好一个**合法**选中：底栏那一行用它（-1 = 没有）
        drawToolbar(g, mouseX, mouseY);                       // 右侧工具栏（画在控件上一层：先画它再 super）
        drawPopup(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // 底栏紧凑成**两行**（画布尽量占满每个分辨率）：
        //   上（居中）：选中描述 / 框提示 / HUD 的板信息
        //   下：左 = 状态回执（绿 / 红）· 右 = 退路（Esc 回目录）
        String info = one >= 0
                ? describe(hits.get(one))
                : (sel.size() > 1
                    ? "选中 " + sel.size() + " 个 —— 拖任意一个可整组挪 · Delete 一起删"
                    : (isHudCanvas()
                        ? plateInfo()
                        : ("框 " + boxes.size() + " 个 —— 空白右键新建 · " + (selMode == 1
                            ? "框选：在空白处拉一个框罩住要选的框（Ctrl+点 = 加 / 减）"
                            : "右键 / 双击一块框 → 编辑它（类型 · 几何 · 样式 · 属性）"))));
        g.centeredText(font, Component.literal(DrawBoardMenuUi.ellipsis(info, 118)), cx, height - 30, 0xFFA0C0A0);
        if (!status.isEmpty()) {
            g.text(font, DrawBoardMenuUi.ellipsis(status, 84), 8, height - 16,
                    statusBad ? 0xFFFF9090 : 0xFF90D090);
        }
        // 本屏是**真·全屏页**（没搭左栏）⇒ 右下角常驻一句退路：怎么回去（Esc）。
        // 保存不用按钮：每次编辑都跟一次静默上传（applyText），回目录即已存。
        String back = "Esc 返回「舞台」目录（编辑已在每次改动后自动存）";
        g.text(font, Component.literal(back), width - TOOLBAR_W - 8 - font.width(back), height - 16, 0xFF707070);
    }

    /** 右侧工具栏（与世界 3D 视窗同一套画法：一列方钮，字用首字，选中 / 悬停换底色）。 */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = width - TOOLBAR_W;
        g.fill(x, 0, width, height, 0xFF14161C);
        g.fill(x, 0, x + 1, height, 0xFF3A3E4A);
        int hover = toolbarHit(mouseX, mouseY);
        for (int i = 0; i < toolCount(); i++) {          // HUD 里没有「网」那颗（见 toolCount）
            int y = toolBtnY(i);
            // 「网」是**开关**（不经弹框）：亮着 = 网格开着；其余是弹框钮（选中 = 弹框开着）
            boolean on = i == TOOL_GRID ? gridOn : popupTool == i;
            g.fill(x + 3, y, x + 3 + TOOL_BTN, y + TOOL_BTN,
                    on ? 0xFF37402E : (i == hover ? 0xFF2E3138 : 0xFF21242B));
            g.outline(x + 3, y, TOOL_BTN, TOOL_BTN, on ? 0xFF6A7A4A : 0xFF3A3E4A);
            g.centeredText(font, Component.literal(TOOLS[i]), x + 3 + TOOL_BTN / 2, y + (TOOL_BTN - 9) / 2,
                    on ? 0xFFFFFFFF : 0xFFC8C8D0);
        }
    }

    /** 弹框（贴在工具栏左边，行文字来自 {@link #popRows}）。 */
    private void drawPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (popupTool < 0) return;
        int x = popX(), y = toolBtnY(popupTool), h = popH(popupTool);
        java.util.List<String> rows = popRows(popupTool);
        g.fill(x, y, x + POP_W, y + h, 0xF0181C24);
        g.outline(x, y, POP_W, h, 0xFF606878);
        int hover = popRowAt(mouseX, mouseY);
        for (int i = 0; i < rows.size(); i++) {
            int ry = y + POP_PAD + i * POP_ROW;
            if (i == hover) g.fill(x + 1, ry, x + POP_W - 1, ry + POP_ROW, 0xFF2E3138);
            g.text(font, DrawBoardMenuUi.ellipsis(rows.get(i), 20), x + POP_PAD, ry + 3,
                    i == hover ? 0xFFFFE080 : 0xFFD0D0D0);
        }
    }

    /** 颜色实参在实参表里的下标：{@code box} = 4 · {@code text} = 5 · 没有颜色 = -1（属性栏「透」那格用）。 */
    private static int colorArgOf(String kind) {
        return switch (kind) {
            case "box" -> 4;
            case "text" -> 5;
            default -> -1;                                   // 输入框 / 绘画区 / 部件调用：没有颜色实参
        };
    }

    /** 画布参考网格 —— 「方便规整」的**纯视觉**辅助：只在编辑器里画，不进脚本。细线 4px = **拖框吸附的格**（吸附过的框永远压在细线上，一眼看出对齐了）；
     *  主格 20px（5 个细格）在 320×180 上整除（16×9 格），不会画到半格。 */
    private void drawGrid(GuiGraphicsExtractor g, int ox, int oy, int cw, int ch, double sx, double sy) {
        for (int i = 0; i * 4 <= CANVAS_W; i++) {
            int px = ox + (int) Math.round(i * 4 * sx);
            if (px > ox + cw) break;
            g.fill(px, oy, px + 1, oy + ch, i % 5 == 0 ? 0x24FFFFFF : 0x0EFFFFFF);
        }
        for (int i = 0; i * 4 <= CANVAS_H; i++) {
            int py = oy + (int) Math.round(i * 4 * sy);
            if (py > oy + ch) break;
            g.fill(ox, py, ox + cw, py + 1, i % 5 == 0 ? 0x24FFFFFF : 0x0EFFFFFF);
        }
    }

    /**
     * 这条画语句的颜色实参里**作者明确写的**透明度（0~255）；没写 8 位 / 认不出 = 255。
     *
     * <p>给「越透明框线越显眼」那圈轮廓判用 —— 只认作者写死的 8 位色串，**不**拿展开后的底色猜
     * （文本框的底色本来就是全透明 {@code #00000000}，拿它判会把每个文本框都描一圈）。
     */
    private int writtenAlpha(Hit h) {
        ScriptEdit.DrawAt d = ScriptEdit.find(parent.def.script(), h.line, h.index);
        if (d == null) return 255;
        int ci = colorArgOf(d.kind());
        if (ci < 0 || ci >= d.args().size()) return 255;
        String t = ScriptEdit.inner(d.args().get(ci));
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 8) return 255;
        try {
            return Integer.parseInt(t.substring(6), 16);
        } catch (NumberFormatException e) {
            return 255;                                    // 乱写的颜色串：当没写（运行时会回兜底色）
        }
    }

    /** 底栏：选中的这个框是谁（哪一行第几条）+ 它来自画布还是部件体（部件体 = 改一处全体变）。 */
    private String describe(Hit h) {
        String block = ScriptEdit.blockAt(parent.def.script(), h.line);
        String where = block.startsWith("part ")
                ? "来自 " + block + " → 改动会作用到所有调用处"
                : (block.isEmpty() ? "" : "在 " + block + " 里");
        String ro = literal(h.line, h.index, 0, 1) ? "" : " · 只读：位置由第 " + h.line + " 行算出来 → 去脚本改";
        String fx = "";
        if (("text".equals(h.b.kind()) || "input".equals(h.b.kind())) && h.index >= 0
                && !ScriptEdit.literal(parent.def.script(), h.line, h.index, 4)) {
            ScriptEdit.DrawAt d = ScriptEdit.find(parent.def.script(), h.line, h.index);
            String full = d == null || d.args().size() <= 4 ? "" : d.args().get(4).trim();
            if (full.length() > 160) full = full.substring(0, 160) + "…";
            fx = " · 值位（屏上只摆一行，完整来源：" + full + "）";
        }
        return h.b.kind() + " · 脚本第 " + h.line + " 行第 " + (h.index + 1) + " 条"
                + (where.isEmpty() ? "" : " · " + where) + ro + fx;
    }

    // ================= 右侧工具栏 =================
    // 两块结构：**目录**（{@link StageDirScreen}）挑一块舞台 → **本屏**预览它。本屏不列「看板 X / 全屏 X」切换钮（进哪块由目录定），
    // 位子让给右侧工具栏：上面「选」管选区（单选 / Ctrl 加选 / 框选），下面「编」管这块舞台自己的动作（改名 / 改显示名 / 复制 / 跳行 / 删除）。
    // 不做「点一下循环切模式」：参数这类一律开弹框点选（参数控件要下拉，不要点击循环）。

    /** 工具栏按钮的 y（第 i 颗）。 */
    private static int toolBtnY(int i) {
        return TOOL_TOP + i * (TOOL_BTN + TOOL_GAP);
    }

    /**
     * 这块画布上工具栏有几颗 —— **HUD 里不给「网」**（板本来就小，网格只会糊成一片；
     * 网格本身也只画全屏画布）。「网」是 {@link #TOOLS} 的最后一颗，所以砍尾巴就行。
     */
    private int toolCount() {
        return isHudCanvas() ? TOOLS.length - 1 : TOOLS.length;
    }

    /** 点到了工具栏哪一颗（-1 = 没点中工具栏）。 */
    private int toolbarHit(double mx, double my) {
        if (mx < width - TOOLBAR_W) return -1;
        for (int i = 0; i < toolCount(); i++) {
            int y = toolBtnY(i);
            if (my >= y && my < y + TOOL_BTN) return i;
        }
        return -1;
    }

    /** 弹框左上角（贴在工具栏左边，与开了弹框的那颗齐头）。 */
    private int popX() {
        return width - TOOLBAR_W - POP_W - 2;
    }

    /** 弹框里那几行字（下标 = 行号）—— 只有「选」有弹框（「编」进编辑页 / 出右键菜单）。 */
    private java.util.List<String> popRows(int tool) {
        return java.util.List.of(
                (selMode == 0 ? "✔ " : "　") + "单选（点一个 · Ctrl+点加 / 减）",
                (selMode == 1 ? "✔ " : "　") + "框选（空白处拉一个框，框住都算）",
                "　清空选区（现有 " + sel.size() + " 个）");
    }

    private int popH(int tool) {
        return POP_PAD * 2 + popRows(tool).size() * POP_ROW;
    }

    /** 点到了弹框哪一行（-1 = 没点中）。 */
    private int popRowAt(double mx, double my) {
        if (popupTool < 0) return -1;
        int x = popX(), y = toolBtnY(popupTool), h = popH(popupTool);
        if (mx < x || mx >= x + POP_W || my < y || my >= y + h) return -1;
        int row = (int) ((my - y - POP_PAD) / POP_ROW);
        return row >= 0 && row < popRows(popupTool).size() ? row : -1;
    }

    /** 弹框里点了一行：「选」= 换模式 / 清空选区（「编」那几行在右键菜单）。 */
    private void popPick(int row) {
        if (row == 0) {
            selMode = 0;
            say("选择：单选（Ctrl+点 = 加 / 减）", false);
        } else if (row == 1) {
            selMode = 1;
            say("选择：框选（在空白处拉一个框罩住要选的框）", false);
        } else {
            selClear();
            say("已清空选区", false);
        }
    }

    /**
     * 「编」左键 = 进**单独编辑页**（资产名 / 显示名 / 背景同屏改）。
     *
     */
    private void openEditorPage() {
        Minecraft.getInstance().setScreen(new StageEditScreen(this, canvas,
                ScriptEdit.screenLabelOf(parent.def.script(), canvas),
                ScriptEdit.screenBgOf(parent.def.script(), canvas), this::applyFields));
    }

    /**
     * 编辑页交回三样：**改得动的都写**，三步串在**同一份文本**上（改名先 —— 后面两步要按**新名**找块）；
     * 哪一步被拒收（非英文名 / 重名 / 颜色串乱写）就**只跳过那一步**，原因攒进回执一起报。
     * ⚠ 别写成「任一步被拒就整次不动」：那样前一步已改好的内容会跟着丢（看到报错，却不知道改名其实没落）。
     * @param newAsset 资产名（空 / 没变 = 不动）；改了走全链改名（声明 + 所有 {@code show("旧名")}）
     * @param newLabel 显示名（留空 = 去掉那条 {@code name(…)}）
     * @param newBg 背景串（{@code #RRGGBB}/{@code #RRGGBBAA}，空 = 去掉那条 {@code bg(…)} = 回缺省 40% 黑）
     */
    private void applyFields(String newAsset, String newLabel, String newBg) {
        String src = parent.def.script();
        String text = src;
        String name = canvas;
        java.util.List<String> blocked = new ArrayList<>();
        String na = newAsset == null ? "" : newAsset.strip();
        if (!na.isEmpty() && !na.equals(name)) {
            ScriptEdit.Result r = ScriptEdit.renameScreen(text, name, na);
            if (r.text().equals(text)) blocked.add(r.note());
            else {
                text = r.text();
                name = na;
            }
        }
        String lb = newLabel == null ? "" : newLabel.strip();
        if (!lb.equals(ScriptEdit.screenLabelOf(text, name))) {
            ScriptEdit.Result r = ScriptEdit.setScreenLabel(text, name, lb);
            if (r.text().equals(text)) blocked.add(r.note());
            else text = r.text();
        }
        String bgs = newBg == null ? "" : newBg.strip();
        if (!bgs.equals(ScriptEdit.screenBgOf(text, name))) {
            ScriptEdit.Result r = ScriptEdit.setScreenBg(text, name, bgs);
            if (r.text().equals(text)) blocked.add(r.note());
            else text = r.text();
        }
        if (text.equals(src)) {
            say(blocked.isEmpty() ? "没改（资产名 / 显示名 / 背景都没动）" : String.join(" · ", blocked), !blocked.isEmpty());
            return;
        }
        canvas = name;                                       // 改名了：这块还看着它（构造参数换成新名）
        applyText(text, "已改「" + name + "」（资产名 / 显示名 / 背景）"
                + (blocked.isEmpty() ? "" : "；没动的：" + String.join(" / ", blocked)));
    }

    /**
     * 进这块组件自己的编辑页：**类型 / 几何 / 样式 / 内容 / 属性**都在那一页 ——
     * 底栏属性栏删掉之后，这里是改一个组件的唯一入口（右键 → 编辑，或双击）。
     */
    private void openCompPage(Hit h) {
        Minecraft.getInstance().setScreen(new StageCompScreen(parent, canvas, h.line, h.index, this));
    }

    /**
     * 「编」右键 = 这块舞台那几个动作。
     *
     * <p>走屏自带的那套右键菜单（与画布空白 / 框上右键同一份实现）—— 不开控件、画在最上层。
     */
    private void openEditMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        addAction("编辑", this::openEditorPage);
        addAction("复制一块", this::duplicateScreen);
        addAction("跳到脚本那一行", this::gotoDeclLine);
        addAction("删除舞台", this::deleteThisScreen);
        menuOpen = true;
        menuX = Math.min(mx, Math.max(4, width - TOOLBAR_W - MENU_W - 4));
        menuY = Math.min(my, Math.max(4, height - MENU_ROW * menuLabels.size() - 6));
    }

    /** 「编 → 复制一块」：整块复制一份（名字自动查重），复制完**进副本**接着改。 */
    private void duplicateScreen() {
        java.util.List<String> before = sc == null ? java.util.List.of() : new ArrayList<>(sc.screens().keySet());
        ScriptEdit.Result r = ScriptEdit.duplicateScreen(parent.def.script(), canvas);
        if (r.text().equals(parent.def.script())) {
            say(r.note(), true);
            return;
        }
        String err = parent.applyScript(r.text());
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        String nw = "";
        try {                                                        // 新名字 = 前后两套块名一比就出来了
            for (String nm : Parser.parse(parent.def.script()).screens().keySet()) {
                if (!before.contains(nm)) nw = nm;
            }
        } catch (Ast.ScriptError e) {
            nw = "";
        }
        if (nw.isEmpty()) {
            say(r.note(), false);
            return;
        }
        Minecraft.getInstance().setScreen(new StageVisualScreen(parent, nw));
    }

    /** 「编 → 跳到脚本那一行」：脚本页停在声明那一行（改文本的活都在那儿）。 */
    private void gotoDeclLine() {
        int line = declLine();
        if (line <= 0) {
            say("找不到这块的声明行 —— 脚本刚被改过？", true);
            return;
        }
        Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, line));
    }

    /** 「编 → 删除这块舞台」：删整块（还被 {@code show} 指到会被拒收），删完回目录页。 */
    private void deleteThisScreen() {
        ScriptEdit.Result r = ScriptEdit.removeScreen(parent.def.script(), canvas);
        if (r.text().equals(parent.def.script())) {
            say(r.note(), true);
            return;
        }
        String err = parent.applyScript(r.text());
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        Minecraft.getInstance().setScreen(new StageDirScreen(parent));
    }

    /** 这块舞台声明所在的源行（1 起；0 = 找不到）。 */
    private int declLine() {
        if (sc == null) return 0;
        Ast.Screen s = sc.screens().get(canvas);
        return s == null ? 0 : s.line();
    }

    /** 这块舞台的**显示名**（块里那条 {@code name("…")}；没写 = 空串）。 */
    private String slotLabel() {
        if (sc == null) return "";
        Ast.Screen s = sc.screens().get(canvas);
        return s == null ? "" : s.label();
    }

    /** 标题里那块的名字：显示名（资产名）；没显示名就只写资产名。 */
    private String titleName() {
        String lab = slotLabel();
        return lab.isEmpty() ? "「" + canvas + "」" : "「" + lab + "（" + canvas + "）」";
    }

    // ---------- 选区----------

    /** 选区里那一个（属性栏要它）；选了好几个 = -1（属性栏一次只编一个框）。 */
    private int primary() {
        if (sel.size() != 1) return -1;
        int i = sel.iterator().next();
        return i >= 0 && i < hits.size() ? i : -1;   // ⚠ hits 每帧重建（layout）—— 下标可能已经失效
    }

    private void selClear() {
        sel.clear();
    }

    /**
     * 框选松手：矩形**碰到**的框全选上（按 Ctrl 拉 = 加进现有选区）。
     *
     * <p>判据用「交叠」而不是「完全包住」：舞台上框常常比鼠标拉的那一下大，要求全包住就永远选不中。
     */
    private void commitBand(boolean add) {
        int x0 = Math.min(band[0], band[2]), x1 = Math.max(band[0], band[2]);
        int y0 = Math.min(band[1], band[3]), y1 = Math.max(band[1], band[3]);
        band = null;
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            say("框选：拉得太小 —— 在空白处拖一个框把要选的罩进去", true);
            return;
        }
        if (!add) sel.clear();
        int n = 0;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            if (h.x < x1 && h.x + h.w > x0 && h.y < y1 && h.y + h.h > y0 && sel.add(i)) n++;
        }
        say(n == 0 ? "框选：框里没有框" : "框选：新增 " + n + " 个（共 " + sel.size() + " 个）", n == 0);
    }

    /**
     * 整组松手：每个被拖的框各改两个实参（x / y），一条链改到底 —— 中途任一次被拒就整次不动
     * （不上传半改的文本，与单框那条口径一样）。
     */
    private void commitGroup(GroupDrag g) {
        if (g.from.isEmpty() || (g.dx == 0 && g.dy == 0)) {
            say("位置没变，没动", false);
            return;
        }
        String text = parent.def.script();
        for (java.util.Map.Entry<Integer, double[]> e : g.from.entrySet()) {
            Hit h = hits.get(e.getKey());
            ScriptEdit.Result r1 = ScriptEdit.setArg(text, h.line, h.index, 0, num(snap(e.getValue()[0] + g.dx)));
            if (r1.text().equals(text)) {
                say("整组没动：" + r1.note(), true);
                return;
            }
            ScriptEdit.Result r2 = ScriptEdit.setArg(r1.text(), h.line, h.index, 1, num(snap(e.getValue()[1] + g.dy)));
            if (r2.text().equals(r1.text())) {
                say("整组没动：" + r2.note(), true);
                return;
            }
            text = r2.text();
        }
        applyText(text, "整组挪了 " + g.from.size() + " 个框"
                + (g.skipped > 0 ? "（另有 " + g.skipped + " 个是表达式驱动，没动）" : ""));
    }

    /**
     * Delete / Backspace：把选中的画语句全删掉。
     *
     * <p>⚠ **从后往前删**（行号大的先、同行里序号大的先）：删一条会顶掉它后面的行号 / 行内序号，
     * 反过来删就会删错条目（与「世界」页那两套索引空间搞混那个 bug 同一类）。
     */
    private void deleteSelected() {
        if (sel.isEmpty()) {
            say("没选中东西 —— 先点一个框", true);
            return;
        }
        java.util.List<Hit> gone = new ArrayList<>();
        for (int i : sel) {
            if (i >= 0 && i < hits.size()) gone.add(hits.get(i));
        }
        gone.sort((a, b) -> a.line != b.line ? Integer.compare(b.line, a.line) : Integer.compare(b.index, a.index));
        String text = parent.def.script();
        int n = 0;
        for (Hit h : gone) {
            ScriptEdit.Result r = ScriptEdit.removeDraw(text, h.line, h.index);
            if (r.text().equals(text)) {
                say(r.note(), true);
                return;                       // 定位不到（脚本刚被改过）= 整次不动，不删一半
            }
            text = r.text();
            n++;
        }
        selClear();
        applyText(text, "已删掉 " + n + " 条画语句");
    }
}

