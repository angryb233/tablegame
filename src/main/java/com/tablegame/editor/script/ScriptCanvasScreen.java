package com.tablegame.editor.script;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptEdit;
import com.tablegame.script.edit.ScriptGraph;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.GraphCanvas;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.stage.StageVisualScreen;

/**
 * 脚本可视化屏：把脚本的 AST 画成节点图。**文本永远是唯一真源，这块是它的视图 + 入口之一**（第五期定调）——
 * 进来先解析 {@code def.script()}，解析不了就把错显在画布上（去文本屏修）；画布上每个改动都经 {@link ScriptEdit} 写回文本、先过解析再存盘。
 * **交互**：左键拖节点（松手存盘）· 点节点选中（底栏显示行号，**选中后按 Delete = 删掉它**）· **拖右侧端口到另一个节点 = 接一条新线** ·
 * **拖线的末端圆点 = 换接到别的阶段**（重接）· **右键**：空白 = 新建阶段 / 新建舞台、节点上 = 删这一段、连线上 = 删这条线（破坏性动作要再点一次确认）· 中键平移 · 滚轮以鼠标为锚缩放。
 * 改动全经 {@link ScriptEdit}（按行改文本，保留你的注释 / 缩进）换成新脚本，先过真源解析（错就拒收、文本原样留着）。
 * **坐标存哪里**：{@code <游戏目录>/tablegame/layout/<档名>.json} —— 布局是「编辑器视角的个人偏好」不是玩法数据：档里只留 {@code script} 文本一份真源，双端各看各的布局。
 */
public class ScriptCanvasScreen extends GraphCanvas {
    /** 界面卡片的留白（世界坐标）：把界面节点与它名下组件都框进来后往外扩这么多。 */
    private static final double CARD_PAD = 12;
    /** 组件节点相对界面节点的缩进（布局第二遍用）—— 一眼看出「谁挂在谁名下」。 */
    private static final double COMP_INDENT = 20;

    // ---- 段6 手感：空白**双击** = 搜索新建（全清单 + 输入筛选；右键仍是那个短菜单）----
    /** 搜索框（双击空白才建出来）。 */
    private EditBox pickBox;
    /** 全清单：{标签, 关键字（给筛选用）, 动作类型}。 */
    private final java.util.List<String[]> pickAll = new java.util.ArrayList<>();
    /** 现在是「搜索新建」模式（菜单行来自筛选结果，不是右键那套）。 */
    private boolean pickOpen;
    /** 这一轮筛出几项（标题里报，行数最多显示 9 行）。 */
    private int pickTotal;
    /** 脚本解析不了时的错误（画布画不出来，去文本屏修）。 */
    private String badScript;
    /** 上一次操作的结论（画布底部一行；成功不再往聊天栏刷，失败才两边都报）。 */
    private String status = "";
    private boolean statusBad;

    /** 从别处（对象编辑页的【事件】栏）跳进来时**预先选中**的节点键（null = 不选；用一次就清）。 */
    private String enterPick;

    public ScriptCanvasScreen(GameEditorScreen parent) {
        this(parent, null);
    }

    /**
     * 带选中项进入——
     * 顺手把视口挪过去（不然选中的节点可能在屏幕外，看着像没选中）。
     */
    public ScriptCanvasScreen(GameEditorScreen parent, String pickKey) {
        super(Component.literal("可视化"), parent, "脚本");
        this.enterPick = pickKey;
    }

    // ===== 构建 =====

    @Override
    protected void init() {
        super.init();                                             // 清控件 / 清图 / 重建 / 读布局（机件在基类）
        if (enterPick != null) {                                  // 带着选中项进来的（对象页事件栏那条入口）
            picked = enterPick;
            for (Node n : nodes) {
                if (n.key.equals(enterPick)) {
                    offX = EditorRail.LEFT + 40 - n.px * zoom;    // 把它挪到画布左上角附近
                    offY = 90 - n.py * zoom;
                    break;
                }
            }
            enterPick = null;                                     // 只认第一次（之后重排 / 缩放不再跳）
        }
        int cx = EditorRail.cx(this);

        // 顶部两态：现阶段就是「可视化」，所以它画成禁用的（null = 点不动的按钮）
        addRenderableWidget(DrawBoardMenuUi.button(cx - 150, 42, 92, 22, "文本",
                () -> Minecraft.getInstance().setScreen(new ScriptEditScreen(parent))));
        addRenderableWidget(DrawBoardMenuUi.button(cx - 50, 42, 92, 22, "可视化 ✔", null));

        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 40, 132, 24, "重排节点", this::relayout));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, height - 40, 132, 24, "返回总览", this::back));
        EditorRail.build(parent, "脚本", this::addRenderableWidget);
    }

    @Override
    protected void rebuildGraph() {
        buildGraph();
    }

    /**
     * 脚本 → 节点 + 连线：形状全交给纯逻辑的 {@link ScriptGraph} 算，本屏只负责画与拖。
     */
    private void buildGraph() {
        Ast.Script sc;
        try {
            sc = Parser.parse(parent.def.script());
        } catch (Ast.ScriptError e) {
            badScript = e.getMessage();          // 文本有错：去「文本」页修（本屏只是视图）
            return;
        }
        badScript = null;
        ScriptGraph.Graph gr = ScriptGraph.build(sc);
        int[] col = new int[7];                                          // 七类各自的下一行位置
        Map<String, Double> screenY = new HashMap<>();                   // 界面节点落在哪一行（组件要挂它下面）
        for (ScriptGraph.Node n : gr.nodes()) {
            if (n.kind() == ScriptGraph.KIND_COMPONENT) continue;         // 组件第二遍排（要等界面先落位）
            // 列序：**变量最左** → 入口 → 阶段 → 函数与**部件同列** → 界面。
            double x = switch (n.kind()) {
                case ScriptGraph.KIND_VAR -> 60;
                case ScriptGraph.KIND_ON -> 430;
                case ScriptGraph.KIND_STAGE -> 800;
                case ScriptGraph.KIND_SCREEN -> 1540;      // 界面：最后一列（画布能平移，放得下）
                default -> 1170;                           // 函数 / 部件（同一列）
            };
            double y = 60 + col[n.kind()]++ * 64;
            if (n.kind() == ScriptGraph.KIND_SCREEN) screenY.put(screenKeyOf(n.key()), y);
            nodes.add(new Node(n.key(), n.label(), n.line(), n.kind(), x, y));
        }
        // 第二遍：组件节点挂在**它所属舞台**的正下方（归属靠位置表达，不画箭头 —— 它不是 goto）
        Map<String, Integer> compK = new HashMap<>();
        for (ScriptGraph.Node n : gr.nodes()) {
            if (n.kind() != ScriptGraph.KIND_COMPONENT) continue;
            String screen = compScreenOf(n.key());
            Double sy = screenY.get(screen);
            double y = (sy == null ? 60 : sy) + 64 + compK.merge(screen, 1, Integer::sum) * 64;
            nodes.add(new Node(n.key(), n.label(), n.line(), n.kind(), 1540 + COMP_INDENT, y));
        }
        for (ScriptGraph.Edge e : gr.edges()) edges.add(new Edge(e.from(), e.to(), e.cond(), e.condText(), e.line()));
    }

    // ===== 布局存盘（本地文件，不进档）=====

    @Override
    protected Path layoutFile() {
        String name = parent.def.name().replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return Minecraft.getInstance().gameDirectory.toPath().resolve("tablegame").resolve("layout")
                .resolve(name + ".json");
    }

    // ===== 交互：脚本专属的那几处 =====

    /** 双击空白 = 搜索新建；双击节点 = 编辑它自己（见 editNode）。 */
    @Override
    protected boolean onDoubleClick(double mx, double my) {
        Node dn = nodeAt(mx, my);
        if (dn != null) {                                        // 双击节点 = **编辑它自己**（2026-09-18 拍板的思路）
            editNode(dn);
            return true;
        }
        if (edgeAt(mx, my) == null) {                            // 空白双击 = 搜索新建（段6）
            openCreatePalette();
            return true;
        }
        return false;
    }

    /** 拉线落地（基类在松手时喊）。 */
    @Override
    protected void droppedLink(String fromKey, Node target) {
        finishLink(fromKey, target);
    }

    /** 重接落地（基类在松手时喊）。 */
    @Override
    protected void droppedRelink(Edge e, Node target) {
        finishRelink(e, target);
    }

    /** 菜单上方那行小字：搜索新建的「匹配 N 项」。 */
    @Override
    protected String menuHeader() {
        return pickOpen ? "新建 · 匹配 " + pickTotal + " 项（最多显示 9 行，输入收窄）" : null;
    }

    /** 只在 goto 里出现过的节点：它没有自己的一段。 */
    @Override
    protected String noLineLabel() {
        return "（只在 goto 里出现）";
    }

    /**
     * 拖节点：世界坐标允许为负 —— 画布本来就能缩放/平移，原点不是墙。
     * （曾夹在 0 以上 → 节点拖不到左上，看着像有个看不见的「限制框」卡住。）
     */
    @Override
    protected void dragNode(Node n, double mx, double my) {
        double nx = toWorldX(mx) - dragDX, ny = toWorldY(my) - dragDY;
        double ddx = nx - n.px, ddy = ny - n.py;
        n.px = nx;
        n.py = ny;
        // 拖**界面** = 整块走（界面与它挂的组件是一体的）—— 按位移平移组件，
        // 相对位置不变。拖单个组件仍只动那一个（搬出去也随它，卡片包围盒会跟着重算）。
        if (n.kind == ScriptGraph.KIND_SCREEN) {
            String name = screenKeyOf(n.key);
            for (Node c : nodes) {
                if (c.kind == ScriptGraph.KIND_COMPONENT && compScreenOf(c.key).equals(name)) {
                    c.px += ddx;
                    c.py += ddy;
                }
            }
        }
    }

    // ===== 改（画布上的操作 → 写回文本）=====

    /** 右键菜单：空白 = 新建阶段；节点上 = 删这一段；连线上 = 删这条线。破坏性动作要再点一次。 */
    @Override
    protected void fillMenu(double mx, double my) {
        Node n = nodeAt(mx, my);
        Edge e = n == null ? edgeAt(mx, my) : null;
        if (n != null) {
            String key = n.key;
            // 定位类动作（非破坏性）—— 放在破坏性项之前，省得点错。
            // ① 界面节点（screen 声明）→ 直接跳编辑器里那块界面（作者看图改界面）
            String sn = n.key.startsWith("screen:") ? n.key.substring("screen:".length()) : "";
            if (!sn.isEmpty()) {
                menuLabels.add("跳到这个舞台界面（" + sn + "）");
                menuActions.add(() -> Minecraft.getInstance().setScreen(new StageVisualScreen(parent, sn)));
            }
            // ② 组件：跳到界面上**看**这一条（“改”归双击，这里只管定位/看一眼）
            if (n.key.startsWith("comp:")) {
                menuLabels.add("跳到界面上看这一条（" + compScreenOf(n.key) + "）");
                menuActions.add(() -> Minecraft.getInstance().setScreen(
                        new StageVisualScreen(parent, compScreenOf(n.key), compLineOf(n.key))));
            }
            // ③ 跳到指令行
            menuLabels.add("跳到指令行（第 " + n.line + " 行）");
            menuActions.add(() -> Minecraft.getInstance().setScreen(new ScriptEditScreen(parent, n.line)));
            String label = "删除这一段：" + n.label;
            menuLabels.add(arm.equals(key) ? "再点一次确认 —— " + label : label);
            menuActions.add(() -> {
                if (!arm.equals(key)) {                     // 第一次点只把话说清楚
                    arm = key;
                    openMenu(menuX, menuY);
                    return;
                }
                arm = "";
                deleteNode(n);                              // 与 Delete 键共用一份实现（见 deleteNode）
            });
        } else if (e != null) {
            Node from = byKey(e.from());
            String to = e.to().substring("stage:".length());
            String key = e.from() + "->" + e.to();
            String label = "删除这条线：" + (from == null ? e.from() : from.label) + " → " + to;
            menuLabels.add(arm.equals(key) ? "再点一次确认 —— " + label : label);
            menuActions.add(() -> {
                if (!arm.equals(key)) {
                    arm = key;
                    openMenu(menuX, menuY);
                    return;
                }
                arm = "";
                if (from == null) return;
                ScriptEdit.Result r = ScriptEdit.removeGoto(parent.def.script(), from.line - 1, to);
                applyText(r.text(), r.note());
            });
        } else {
            menuLabels.add("+ 新建阶段（追加到脚本末尾）");
            menuActions.add(() -> {
                String name = ScriptEdit.nextStageName(parent.def.script());
                ScriptEdit.Result r = ScriptEdit.addStage(parent.def.script(), name);
                applyText(r.text(), r.note());
            });
            // 新建舞台：界面页不放「＋新舞台」—— 新建统一走这里，
            //   或者直接在脚本里写 `screen 名字 { }`。两种入口，可视化这边省得手打。
            menuLabels.add("+ 新建舞台（screen stageN · 全屏类）");
            menuActions.add(() -> {
                ScriptEdit.Result r = ScriptEdit.addScreen(parent.def.script(), nextScreenName());
                applyText(r.text(), r.note());
            });
        }
    }

    // ================= 段6 手感：空白双击 = 搜索新建 =================
    //
    // 与右键那个短菜单的分工：**右键**给最常用的两件（新建阶段 / 新建舞台）—— 手熟的人两步搞定；
    // **双击**给全清单 + 输入筛选（要建函数 / 部件 / 变量 / 声明 / 各类入口时不用记在哪儿）。
    // 行沿用基类那套菜单（menuLabels / menuActions / menuRowAt），只是内容换成筛选结果 + 上面多一个搜索框。

    /** 全清单：{标签, 关键字, 动作类型}。 */
    private void openCreatePalette() {
        pickAll.clear();
        pickAll.add(new String[]{"阶段（on stage 名字）", "阶段 stage 流程", "阶段"});
        pickAll.add(new String[]{"舞台（screen 名字）", "舞台 screen 界面", "舞台"});
        pickAll.add(new String[]{"函数（func 名字）", "函数 func", "函数"});
        pickAll.add(new String[]{"部件（part 名字）", "部件 part 组件", "部件"});
        pickAll.add(new String[]{"变量（var 名字 = 0）", "变量 var 数值", "变量"});
        pickAll.add(new String[]{"入口：on start（开局）", "入口 start 开局", "on start"});
        pickAll.add(new String[]{"入口：on input（有人打字）", "入口 input 输入", "on input"});
        pickAll.add(new String[]{"入口：on pick（有人点了一份）", "入口 pick 点选", "on pick"});
        // 进局 / 退局（走进圈 = 进局、走出圈 = 退局 —— 名单就靠这两条自己攒）
        pickAll.add(new String[]{"入口：on join（有人进局 —— 进存档 / 走进区域 / 被点名）", "入口 join 进局 进档 点名", "on join"});
        pickAll.add(new String[]{"入口：on leave（有人退局 —— 退出存档 / 走出区域）", "入口 leave 退局 退档 出圈", "on leave"});
        pickAll.add(new String[]{"入口：on world（世界手势 / 进出圈）", "入口 world 世界 圈", "on world"});
        pickAll.add(new String[]{"入口：on timeout（时间到）", "入口 timeout 超时", "on timeout"});
        pickAll.add(new String[]{"入口：on look（看向哪格）", "入口 look 看向", "on look"});
        pickAll.add(new String[]{"入口：on entity（右键实体）", "入口 entity 实体", "on entity"});
        pickAll.add(new String[]{"声明：resident 1（常驻玩法）", "声明 resident 常驻", "resident"});
        pickAll.add(new String[]{"声明：dim \"…\"（棋盘维度）", "声明 dim 维度", "dim"});
        pickAll.add(new String[]{"声明：allow_replace 1（允许改方块）", "声明 allow_replace 方块", "allow_replace"});
        pickOpen = true;
        menuOpen = true;
        menuX = Math.max(4, EditorRail.cx(this) - 130);
        menuY = 56;
        pickBox = new EditBox(font, (int) menuX, (int) menuY - 22, MENU_W, 18, Component.literal("搜索"));
        pickBox.setHint(Component.literal("输入筛选：阶段 / 函数 / 入口 / 声明…"));
        pickBox.setResponder(this::filterPalette);
        clearWidgets();
        init();
        addRenderableWidget(pickBox);
        setFocused(pickBox);
        filterPalette("");
    }

    /** 按关键字筛（空 = 全列；一屏最多 9 行，多的靠输入收窄）。 */
    private void filterPalette(String q) {
        String k = q == null ? "" : q.strip().toLowerCase();
        menuLabels.clear();
        menuActions.clear();
        pickTotal = 0;
        for (String[] e : pickAll) {
            if (!k.isEmpty() && !(e[0] + " " + e[1]).toLowerCase().contains(k)) continue;
            pickTotal++;
            if (menuLabels.size() >= 9) continue;
            String type = e[2];
            menuLabels.add(e[0]);
            menuActions.add(() -> createFromPalette(type));
        }
    }

    /** 建一件：需要名字的走 NamePromptScreen（名字要英文标识符 —— 语言规则），其余直接追加。 */
    private void createFromPalette(String type) {
        String src = parent.def.script();
        closePalette();
        switch (type) {
            case "阶段" -> applyText(ScriptEdit.addStage(src, ScriptEdit.nextStageName(src)).text(), "已新建阶段");
            case "舞台" -> applyText(ScriptEdit.addScreen(src, nextScreenName()).text(), "已新建舞台");
            case "变量" -> askName("新建变量", "英文标识符（脚本里 var 名只能用英文）", "score",
                    nm -> applyText(ScriptEdit.addVar(src, nm, "", "0").text(), "已新建变量 " + nm));
            case "函数" -> askName("新建函数", "英文标识符", "myFunc", nm -> applyText(
                    ScriptEdit.appendTop(src, "func " + nm + "(a) {\n  return a\n}", "已新建函数 " + nm).text(),
                    "已新建函数 " + nm));
            case "部件" -> askName("新建部件", "英文标识符（第一段文字是这一份的身份）", "myPart", nm -> applyText(
                    ScriptEdit.appendTop(src, "part " + nm + "(id) {\n  box(0, 0, 20, 20, \"#3A3A52\")\n}",
                            "已新建部件 " + nm).text(), "已新建部件 " + nm));
            default -> {
                String code = switch (type) {
                    case "on start" -> "on start {\n}";
                    case "on input" -> "on input {\n}";        // ⚠ 原来写成 on input(谁, 内容)：那不是合法语法（on input 不带参数），生成出来就解析错
                    case "on pick" -> "on pick {\n}";
                    case "on world" -> "on world {\n}";
                    case "on join" -> "on join {\n}";
                    case "on leave" -> "on leave {\n}";
                    case "on timeout" -> "on timeout {\n}";
                    case "on look" -> "on look {\n}";
                    case "on entity" -> "on entity {\n}";
                    case "resident" -> "resident 1";
                    case "dim" -> "dim \"tablegame:board\"";
                    case "allow_replace" -> "allow_replace 1";
                    default -> "";
                };
                if (code.isEmpty()) return;
                String head = code.split("\\n")[0];
                applyText(ScriptEdit.appendTop(src, code, "已追加：" + head).text(), "已追加：" + head);
            }
        }
    }

    private void askName(String title, String hint, String prefill, java.util.function.Consumer<String> cb) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, title, hint, prefill, nm -> {
            if (nm == null || nm.isBlank()) return;
            cb.accept(nm.strip());
        }));
    }

    private void closePalette() {
        pickOpen = false;
        pickBox = null;
        menuOpen = false;
    }

    /**
     * 双击节点 = **编辑它自己**。
     *
     * <p>路由：舞台 → 打开这块舞台；组件 → 打开它所属舞台**并选中那一条**；
     * 其余（入口 / 阶段 / 函数）→ 跳到它那一行（它们的编辑本来就在脚本里）。
     */
    private void editNode(Node n) {
        // 变量节点没有「自己的一段语句」，它就是那一行声明 —— 直接跳到数值页并选中它（段4，与
        // 「组件节点跳界面页并选中」同一套路：一个件两个入口，不做两套 UI）。
        if (n.kind == ScriptGraph.KIND_VAR) {
            Minecraft.getInstance().setScreen(new VarListScreen(parent, varNameOf(n.key)));
            return;
        }
        // 舞台 / 组件节点（段5）：双击 = 跳到**界面页** —— 舞台 = 那块画布；组件 = 那块 + 选中那一条。
        // 界面页本来就支持「跳过来并选中」（三参构造 + selectLine），这里只是把双击接上（右键菜单里
        // 「跳到这个舞台界面 / 跳到界面上看这一条」早就有同一件事了 —— 一个动作两个入口，不做两套 UI）。
        if (n.kind == ScriptGraph.KIND_SCREEN) {
            Minecraft.getInstance().setScreen(new StageVisualScreen(parent, screenKeyOf(n.key)));
            return;
        }
        if (n.kind == ScriptGraph.KIND_COMPONENT) {
            Minecraft.getInstance().setScreen(new StageVisualScreen(parent, compScreenOf(n.key), compLineOf(n.key)));
            return;
        }
        // 其余节点：双击进**它自己的编辑页**（阶段 / 入口 / 函数 / 部件都有真编辑器）
        Minecraft.getInstance().setScreen(NodeEditScreen.of(parent, n.key, n.label, n.kind, n.line));
    }

    /** {@code screen:<名字>} → 名字。 */
    private static String screenKeyOf(String key) {
        return key.startsWith("screen:") ? key.substring("screen:".length()) : key;
    }

    /** {@code comp:<舞台名>:<行>:<序号>} → 舞台名。 */
    private static String compScreenOf(String key) {
        String s = key.startsWith("comp:") ? key.substring("comp:".length()) : key;
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(0, i);
    }

    /** {@code comp:<舞台名>:<行>:<序号>} → 源行号（认不出 = 0）。 */
    private static int compLineOf(String key) {
        String s = key.startsWith("comp:") ? key.substring("comp:".length()) : key;
        String[] p = s.split(":");
        try {
            return p.length >= 2 ? Integer.parseInt(p[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 找一个没被占用的舞台名（{@code stage1 / stage2 / …}）。
     *
     * <p>脚本本身有语法错时随便给一个 —— {@link ScriptEdit#addScreen} 走的是「先真源解析、
     * 通过才上传」，把关在那边。
     */
    private String nextScreenName() {
        Set<String> used = new HashSet<>();
        try {
            used.addAll(Parser.parse(parent.def.script()).screens().keySet());
        } catch (Ast.ScriptError ignored) {
            // 留给 ScriptEdit 报错
        }
        int n = 1;
        while (used.contains("stage" + n)) n++;
        return "stage" + n;
    }

    /**
     * 拉线落地：源块末尾接一行 {@code goto 目标}。
     *
     * <p>源块本来就有别的 goto 时先问一句（第一次拉只提示、第二次才追加）—— 免得默默把流程改了。
     */
    private void finishLink(String fromKey, Node target) {
        Node from = byKey(fromKey);
        if (from == null) return;
        if (target == null || !target.key.startsWith("stage:")) {
            DrawBoardMenuUi.msg("[脚本] 线只能接到「阶段」节点上（goto 的目标必须是阶段）");
            return;
        }
        if (from.key.equals(target.key)) return;
        String to = target.key.substring("stage:".length());
        int line = from.line - 1;
        if (line < 0) return;
        List<String> has = ScriptEdit.gotosOf(parent.def.script(), line);
        if (has.contains(to)) {                       // 已经连过了：不动（不插第二行 goto）
            DrawBoardMenuUi.msg("[脚本] 「" + from.label + "」已经连着「" + to + "」了，没动");
            return;
        }
        String key = fromKey + "->" + target.key;
        if (!has.isEmpty() && !arm.equals(key)) {
            arm = key;
            DrawBoardMenuUi.msg("[脚本] 「" + from.label + "」本来还有 goto " + String.join(" / ", has)
                    + " —— 再拉一次就追加；要替换就先删那条线");
            return;
        }
        arm = "";
        ScriptEdit.Result r = ScriptEdit.addGoto(parent.def.script(), line, to);
        applyText(r.text(), r.note());
    }

    /**
     * 写回统一出口：先过真源解析（错就拒收、文本原样留着），通过才换进 def 并上传保存。
     * 保存走的是和文本屏同一条路（服务端落盘前还会用同一套校验再过一遍）。
     */
    private void applyText(String text, String note) {
        String err = parent.applyScript(text);            // 写回唯一的出口（解析 / 拒收 / 静默保存都在它里面）
        status = err.isEmpty() ? note : err;
        statusBad = !err.isEmpty();
        if (statusBad) DrawBoardMenuUi.msg("[脚本] " + status);
        clearWidgets();
        init();                                   // 重建：图跟着新文本走
    }

    /**
     * 重接落地：把这条线的目标换成拖到的那个阶段。
     *
     * <p>落地走段0 的 {@link ScriptEdit#setGoto}（锚点 = **这条 goto 自己的行号** + 旧目标）——
     * 画布上的是快照，作者可能刚在文本页改过；对不上就拒收并说清原因，不会改坏别的线。
     */
    private void finishRelink(Edge e, Node target) {
        if (target == null || !target.key.startsWith("stage:")) {
            DrawBoardMenuUi.msg("[脚本] 只能接到「阶段」节点上（goto 的目标必须是阶段）");
            return;
        }
        String oldTo = stageNameOf(e.to()), newTo = stageNameOf(target.key);
        if (oldTo.equals(newTo)) return;
        ScriptEdit.Result r = ScriptEdit.setGoto(parent.def.script(), e.line(), oldTo, newTo);
        applyText(r.text(), r.note());
    }

    /** {@code stage:名字} → 名字。 */
    private static String stageNameOf(String key) {
        return key.startsWith("stage:") ? key.substring("stage:".length()) : key;
    }

    /** 删一个节点 —— **右键菜单的「删除这一段」与 Delete 键共用这一份**。按类分派：**组件** = 删那条画语句（{@link ScriptEdit#removeDraw}）·
     *  **变量** = 删顶层声明（{@link ScriptEdit#removeVar}，还被别处用着会拒收）· 其余（入口 / 阶段 / 函数 / 部件 / 界面）= 整段删（{@link ScriptEdit#removeStage}，它本来就是「删这一块」的通名）。
     *  **阶段另有闸门**：还有别的线指着它就先拦下（线是流程图上的东西，不该跟着节点一起悄悄消失）。 */
    private void deleteNode(Node n) {
        if (n == null) return;
        String before = parent.def.script();
        ScriptEdit.Result r;
        if (n.key.startsWith("comp:")) {
            r = ScriptEdit.removeDraw(before, n.line, compIndex(n.key));
        } else if (n.key.startsWith("var:")) {
            r = ScriptEdit.removeVar(before, varNameOf(n.key));
        } else {
            List<String> blockers = new ArrayList<>();
            for (Edge x : edges) if (x.to().equals(n.key) && !x.from().equals(n.key)) blockers.add(x.from());
            if (!blockers.isEmpty()) {
                DrawBoardMenuUi.msg("[脚本] 「" + n.label + "」还有 " + blockers.size()
                        + " 条线指着它 —— 先把那些线删掉（或拖它们的末端换目标）");
                return;
            }
            r = ScriptEdit.removeStage(before, n.line - 1, n.label);
        }
        applyText(r.text(), r.note());
        if (!r.text().equals(before)) picked = null;                  // 真删掉了才清选中（被拒收时留着）
    }

    /** {@code comp:<界面>:<行>:<序号>} → 序号（本条画语句在这一行里排第几，0 起）。 */
    private static int compIndex(String key) {
        String[] p = key.split(":");
        try {
            return p.length >= 4 ? Integer.parseInt(p[3]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code var:名字} / {@code var:名字:行} → 名字（只有重名变量的 key 才带行号）。 */
    private static String varNameOf(String key) {
        String s = key.startsWith("var:") ? key.substring("var:".length()) : key;
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(0, i);
    }

    /**
     * 删掉选中的那个节点（Delete 键）。没选中就先说一句 —— 键盘上没有「点到哪个节点」这回事，
     * 乱删比删不掉更糟。右键菜单那条路仍要两下确认（菜单容易点错），键这条一下就删。
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        // 段6 手感：Ctrl+Z 撤销 · Ctrl+Y（或 Ctrl+Shift+Z）重做 —— 一行转发，逻辑在 GameEditorScreen
        if (parent.handleHistoryKey(event.key(), event.hasControlDownWithQuirk(), event.hasShiftDown(), 90, 89)) {
            clearWidgets();
            init();
            return true;
        }
        if (event.key() == 261) {                                     // 261 = GLFW_KEY_DELETE
            Node n = byKey(picked);
            if (n == null) {
                DrawBoardMenuUi.msg("[脚本] 先点一下要删的节点（选中后它的框会变亮）");
                return true;
            }
            deleteNode(n);
            return true;
        }
        return super.keyPressed(event);
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
        parent.rebuildFromDef();
    }

    /** 重排：丢掉已存的坐标，回到自动布局（存盘）+ 说一句。 */
    private void relayout() {
        resetLayout();
        DrawBoardMenuUi.msg("[脚本] 节点已重排（你拖过的位置被覆盖）");
    }

    // ===== 画 =====

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 画布机件全在基类（见 drawCanvasBody）：连线（cond=true 走虚线 + 线上写 condText() 小字）/
        // 节点卡片 / 拉线·重接预览 / 右键菜单。这里只补脚本那几行字与脚本专属的界面卡片。
        EditorRail.drawBackdrop(g, height);
        int cx = EditorRail.cx(this);
        int x0 = EditorRail.LEFT + 6, y0 = 70, x1 = width - 6, y1 = height - 44;
        g.fill(x0, y0, x1, y1, 0xFF0E1014);

        g.centeredText(font, Component.literal("脚本可视化 · " + parent.def.name() + "（文本是真源，这里只是它的视图）"),
                cx, 16, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("节点 = 变量 / 入口 / 阶段 / 函数与部件 / 界面　实线 = 无条件　虚线 = 写在 if·while·for 里（线上写着条件）"),
                cx, 28, 0xFF909090);

        if (badScript != null) {
            g.centeredText(font, Component.literal("脚本解析不了，画布画不出来：" + badScript), cx, (y0 + y1) / 2, 0xFFFF9090);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }

        drawScreenCards(g);                        // ① 界面卡片（画在最底层 —— 连线与节点都压在它上面）
        drawCanvasBody(g, mouseX, mouseY);         // ①b–④ 连线 / 节点 / 拉线·重接预览 / 右键菜单

        // ⑤ 上一次操作的结论（成功只在这行显示，不刷聊天栏）
        if (!status.isEmpty()) {
            g.centeredText(font, Component.literal(status), cx, height - 68, statusBad ? 0xFFFF9090 : 0xFF90D090);
        }

        // ⑥ 底栏：选中的是哪个节点 + 操作提示
        Node p = byKey(picked);
        String info = p == null
                ? "节点 " + nodes.size() + " 个 · 连线 " + edges.size()
                        + " 条 —— 拖节点右端小方块 = 接一条线 · 拖线的末端圆点 = 换接目标 · 选中后按 Delete = 删掉它 · 右键有菜单"
                : p.label + (p.line > 0 ? " —— 脚本第 " + p.line + " 行（按 Delete 删掉它）" : " —— 只在 goto 里出现过");
        g.centeredText(font, Component.literal(info), cx, height - 56, 0xFFA0C0A0);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** 界面卡片：给每块界面铺一张卡（底 + 边 + 左侧树线；组件的**缩进**在布局那边做 {@link #COMP_INDENT}），画在最底层（连线与节点压在它上面）。
     *  卡片是**派生态**：包围盒按当前坐标现算，不存任何东西 —— 布局档里仍只有每个节点自己的坐标，没有「容器」这层新数据。
     *  归属线只画「界面 → 它的每个组件」这一层、不画箭头 —— 它不是 {@code goto}（带条件的箭头留给阶段跳转）；「谁是它的孩子」由 {@code comp:<舞台>:<行>:<序号>} 这个 key 里的舞台名决定。 */
    private void drawScreenCards(GuiGraphicsExtractor g) {
        for (Node s : nodes) {
            if (s.kind != ScriptGraph.KIND_SCREEN) continue;
            java.util.List<Node> kids = new ArrayList<>();
            for (Node c : nodes) {
                if (c.kind == ScriptGraph.KIND_COMPONENT && compScreenOf(c.key).equals(screenKeyOf(s.key))) kids.add(c);
            }
            double wx0 = s.px, wy0 = s.py, wx1 = s.px + NW, wy1 = s.py + NH;
            for (Node c : kids) {
                wx0 = Math.min(wx0, c.px);
                wy0 = Math.min(wy0, c.py);
                wx1 = Math.max(wx1, c.px + NW);
                wy1 = Math.max(wy1, c.py + NH);
            }
            int cx0 = (int) toScreenX(wx0 - CARD_PAD), cy0 = (int) toScreenY(wy0 - CARD_PAD);
            int cx1 = (int) toScreenX(wx1 + CARD_PAD), cy1 = (int) toScreenY(wy1 + CARD_PAD);
            g.fill(cx0, cy0, cx1, cy1, 0xFF14171E);                              // 比画布亮一层 = 一张纸
            g.outline(cx0, cy0, cx1 - cx0, cy1 - cy0,                             // 边：界面那一类的色压暗
                    (0x70 << 24) | (colorOf(ScriptGraph.KIND_SCREEN) & 0xFFFFFF));
            if (kids.isEmpty()) continue;                                        // 空界面就一张卡，没有树线可画
            double kidMinX = s.px + COMP_INDENT;
            double botY = s.py + NH;
            for (Node c : kids) {
                kidMinX = Math.min(kidMinX, c.px);
                botY = Math.max(botY, c.py + NH / 2);
            }
            // 树线：界面节点下沿起一条竖线，每个组件从它那儿伸一小横线接上（文件树那种画法）
            double spine = toScreenX(kidMinX - COMP_INDENT / 2);
            double top = toScreenY(s.py + NH) + 4;
            g.fill((int) spine, (int) top, (int) spine + 1, (int) toScreenY(botY), 0xFF39415A);
            for (Node c : kids) {
                double cy = toScreenY(c.py + NH / 2);
                g.fill((int) spine, (int) cy, (int) toScreenX(c.px), (int) cy + 1, 0xFF39415A);
            }
            g.text(font, kids.size() + " 个组件", cx1 - 58, cy1 - 14, 0xFF6E7690); // 角标：几个组件
        }
    }

    /** 节点顶边那条色带（按 kind 分）。 */
    @Override
    protected int colorOf(int kind) {
        if (kind == ScriptGraph.KIND_ON) return 0xFFC06060;
        if (kind == ScriptGraph.KIND_STAGE) return 0xFF60A0C0;
        if (kind == ScriptGraph.KIND_VAR) return 0xFFB09050;      // 变量：暖黄（最左那一列）
        if (kind == ScriptGraph.KIND_PART) return 0xFF70B080;      // 部件：偏绿（「给别人用的」那一族）
        return 0xFFA0A060;
    }
}
