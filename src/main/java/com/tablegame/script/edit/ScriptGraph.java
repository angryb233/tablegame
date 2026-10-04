package com.tablegame.script.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.tablegame.script.Ast;

/**
 * 脚本 → 节点图（纯逻辑，零 MC 依赖，可自检）—— 屏只负责画与拖，图的形状在这里算。
 * 它只是 {@link Ast.Script} 的**只读投影**：文本唯一真源，这里不存东西、也不往回写。
 * 节点 = 入口 / 阶段 / 函数 / 部件 / 界面 / 组件 / 变量；连线 = body 里的 {@code goto}（{@code cond}=true = 写在 if / while / for 里，画虚线）。
 * ⚠ 「只有 goto、没有 on stage 段」的阶段名（解析器会先拒收）：照样建 0 行节点，免得放宽规则时图悄悄少点东西。
 */
public final class ScriptGraph {
    /** 节点七类（可视化屏按这个分列）：入口 / 阶段 / 函数 / 界面 / 组件 / **部件** / **变量**。 */
    public static final int KIND_ON = 0, KIND_STAGE = 1, KIND_FUNC = 2, KIND_SCREEN = 3, KIND_COMPONENT = 4,
            KIND_PART = 5, KIND_VAR = 6;

    /** 图上的一个节点。{@code line} = 对应脚本第几行（1 起；0 = 没有自己的一段）。 */
    public record Node(String key, String label, int line, int kind) { }

    /** 一条连线。{@code cond}=true = 这个 goto 写在 if / while / for 里（画虚线）· {@code condText} = 那层容器的条件原文（嵌套取最内层；无条件 = 空串）· {@code line} = goto 自己那一行（拖端点重接的锚点，见 {@code ScriptEdit.setGoto}）。 */
    public record Edge(String from, String to, boolean cond, String condText, int line) { }

    /** 一张图。{@link #byKey} 给屏用（找连线的两端）。 */
    public record Graph(List<Node> nodes, List<Edge> edges) {
        public Node byKey(String key) {
            for (Node n : nodes) if (n.key().equals(key)) return n;
            return null;
        }
    }

    private ScriptGraph() { }

    /**
     * 收集舞台里所有画语句，每条一个**组件节点**（if / while / for 里递归进去）。
     * ⚠ 循环里**一条语句 = 一个节点**（不展开成 N 个 —— 展开是运行时的事，否则一个 for 能炸出几十个节点）。
     * @param perLine 这一行已出过几条画语句（行内序号，与 {@code ScriptEdit.find} 对齐）
     */
    private static void collectDraws(List<Ast.Stmt> body, String screen, Map<Integer, Integer> perLine, List<Node> out) {
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Draw d) {
                int idx = perLine.merge(d.line(), 1, Integer::sum) - 1;
                out.add(new Node("comp:" + screen + ":" + d.line() + ":" + idx, brief(d), d.line(), KIND_COMPONENT));
            } else if (s instanceof Ast.Place p) {
                int idx = perLine.merge(p.line(), 1, Integer::sum) - 1;
                out.add(new Node("comp:" + screen + ":" + p.line() + ":" + idx, "place(…)", p.line(), KIND_COMPONENT));
            } else if (s instanceof Ast.If f) {
                collectDraws(f.then(), screen, perLine, out);
                collectDraws(f.els(), screen, perLine, out);
            } else if (s instanceof Ast.While w) {
                collectDraws(w.body(), screen, perLine, out);
            } else if (s instanceof Ast.For f) {
                collectDraws(f.body(), screen, perLine, out);
            }
        }
    }

    /** 画语句的短摘要（字面量照写、别的都写「…」—— 节点上只要认出是哪一条）。 */
    private static String brief(Ast.Draw d) {
        StringBuilder sb = new StringBuilder(d.kind()).append("(");
        for (int i = 0; i < d.args().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(brief(d.args().get(i)));
        }
        return sb.append(")").toString();
    }

    private static String brief(Ast.Expr e) {
        if (e instanceof Ast.Num n) {
            double v = n.v();
            return v == Math.floor(v) && !Double.isInfinite(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
        if (e instanceof Ast.Str s) return "\"" + s.v() + "\"";
        if (e instanceof Ast.Bool b) return String.valueOf(b.v());
        if (e instanceof Ast.Obj) return "{ … }";          // 对象字面量（声明里的 components { … }）
        return "…";
    }

    /** 算出一张图。同一个脚本算两次结果一样（无状态）。 */
    public static Graph build(Ast.Script sc) {
        // ① 阶段名单：on stage 段带行号；goto 的目标也算（见类注释）
        Map<String, Integer> stages = new LinkedHashMap<>();
        for (Ast.Handler h : sc.handlers()) {
            if (h instanceof Ast.OnStage os) stages.putIfAbsent(os.stage(), os.line());
        }
        String src = sc.source() == null ? "" : sc.source();     // 条件原文与 goto 行号都从源码文本取
        for (Ast.Handler h : sc.handlers()) collect(hbody(h), null, null, stages, src);
        for (Ast.Func f : sc.funcs().values()) collect(f.body(), null, null, stages, src);

        // ② 节点：入口 → 阶段 → 函数（顺序就是屏上的列序）
        List<Node> nodes = new ArrayList<>();
        for (Ast.Handler h : sc.handlers()) {
            if (h instanceof Ast.OnStage) continue;          // on stage 就是「阶段」节点，不另算一个入口
            nodes.add(new Node("on:" + kind(h), label(h), h.line(), KIND_ON));
        }
        for (Map.Entry<String, Integer> e : stages.entrySet()) {
            nodes.add(new Node("stage:" + e.getKey(), "阶段 " + e.getKey(), e.getValue(), KIND_STAGE));
        }
        for (Ast.Func f : sc.funcs().values()) {
            nodes.add(new Node("func:" + f.name(), "func " + f.name() + "()", f.line(), KIND_FUNC));
        }
        // 部件与函数同列：部件的地位就是「可复用的写法」。
        for (Ast.Part pt : sc.parts().values()) {
            nodes.add(new Node("part:" + pt.name(),
                    "部件 " + pt.name() + "(" + String.join(", ", pt.params()) + ")", pt.line(), KIND_PART));
        }
        // 变量节点：只列顶层声明（函数体里的局部变量不上图）。
        // 重名的（语法上允许）在 key 上带行号，免得两个节点挤在同一格、布局互相覆盖。
        Map<String, Integer> varDup = new HashMap<>();
        for (Ast.Decl d : sc.globals()) varDup.merge(d.name(), 1, Integer::sum);
        for (Ast.Decl d : sc.globals()) {
            nodes.add(new Node("var:" + d.name() + (varDup.get(d.name()) > 1 ? ":" + d.line() : ""),
                    "变量 " + d.name(), d.line(), KIND_VAR));
        }
        // 界面节点：`screen main { … }` / `screen hud { … }` 也是**声明**（和 part / card 同级），
        // 一屏一个节点 —— 右键能跳到编辑器里那块界面（作者看图改界面，比跑一遍看运行时直观）。
        for (Map.Entry<String, Ast.Screen> e : sc.screens().entrySet()) {
            nodes.add(new Node("screen:" + e.getKey(), "界面 " + e.getKey(), e.getValue().line(), KIND_SCREEN));
        }
        // 组件节点：每个舞台里**每条画语句**一个（`if` / `while` / `for` 里递归进去）——
        //  「舞台 → 组件」的层级这样才上得了图，双击/右键才有东西可点。
        //  key = comp:<舞台名>:<行号>:<行内序号>：舞台名带在里面（布局与归属引线据此分组），
        //  行号+行内序号与 ScriptEdit.find(行, 序号) 对齐（双击跳过去就能精确定位到那一条）。
        for (Map.Entry<String, Ast.Screen> e : sc.screens().entrySet()) {
            collectDraws(e.getValue().body(), e.getKey(), new HashMap<>(), nodes);
        }

        // ③ 连线：每个节点自己 body 里的 goto（from = 它自己）
        List<Edge> edges = new ArrayList<>();
        for (Ast.Handler h : sc.handlers()) {
            String from = h instanceof Ast.OnStage os ? "stage:" + os.stage() : "on:" + kind(h);
            final String f0 = from;
            collect(hbody(h), null, t -> edges.add(new Edge(f0, "stage:" + t.stage(),
                    t.cond() != null, t.cond() == null ? "" : t.cond(), t.line())), stages, src);
        }
        for (Ast.Func f : sc.funcs().values()) {
            collect(f.body(), null, t -> edges.add(new Edge("func:" + f.name(), "stage:" + t.stage(),
                    t.cond() != null, t.cond() == null ? "" : t.cond(), t.line())), stages, src);
        }
        return new Graph(nodes, edges);
    }

    /** 一条 goto 的原始信息（收线时逐条回调）。{@code cond} 非 null = 写在那一层容器里（值 = 它的条件原文）。 */
    private record Hit(String stage, String cond, int line) { }

    /**
     * 递归收 goto（if / while / for 里面的算「条件里」，顺手记下那层条件原文）。
     *
     * @param cond 当前所在的容器条件原文（null = 不在容器里）
     * @param sink 非 null 时逐条回调；stages 非 null 时顺手把阶段名并进名单（行号不覆盖）
     * @param src 源码文本（取条件原文用；空串时条件原文退化成空，图照样画得出来）
     */
    private static void collect(List<Ast.Stmt> body, String cond, java.util.function.Consumer<Hit> sink,
                               Map<String, Integer> stages, String src) {
        if (body == null) return;
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Goto g) {
                if (stages != null) stages.putIfAbsent(g.stage(), 0);     // 0 行 = 脚本里没写这段
                if (sink != null) sink.accept(new Hit(g.stage(), cond, g.line()));
            } else if (s instanceof Ast.If v) {
                String c = condOf(src, v.line());                         // 嵌套时内层覆盖外层 = 取最内层
                collect(v.then(), c, sink, stages, src);
                // else 分支要标出来：`if (x) {goto a} else {goto b}` 两条线的条件不是同一个（写一样会让作者看反）
                collect(v.els(), c == null ? null : "否则：" + c, sink, stages, src);
            } else if (s instanceof Ast.While v) {
                collect(v.body(), condOf(src, v.line()), sink, stages, src);
            } else if (s instanceof Ast.For v) {
                collect(v.body(), condOf(src, v.line()), sink, stages, src);
            }
        }
    }

    /**
     * 容器那一行的**条件原文**：{@code if (x > 3) { }} → {@code x > 3}；{@code for (…)} → 括号里那三段。
     *
     * <p>取**源码文本**、不从 AST 重打印（重打印要还原空格/括号/引号，多一层翻译多一种对不上的可能）。
     * 行号对不上就退化成整行原文 —— 宁可多摆几个字，也别把线画成「没有条件」。
     */
    private static String condOf(String src, int line) {
        String[] lines = src.split("\n", -1);
        if (line < 1 || line > lines.length) return "";
        String t = lines[line - 1];
        int b = t.indexOf('(');
        if (b < 0) return t.strip();
        int e = ScriptEdit.matchParen(t, b);
        return (e < 0 ? t.substring(b + 1) : t.substring(b + 1, e)).strip();
    }

    /** 事件入口体（{@link Ast.Handler} 接口没声明 body，这里按类型取）。 */
    public static List<Ast.Stmt> hbody(Ast.Handler h) {
        if (h instanceof Ast.OnStart x) return x.body();
        if (h instanceof Ast.OnInput x) return x.body();
        if (h instanceof Ast.OnStage x) return x.body();
        if (h instanceof Ast.OnEvery x) return x.body();
        if (h instanceof Ast.OnTimeout x) return x.body();
        if (h instanceof Ast.OnPick x) return x.body();
        if (h instanceof Ast.OnWorld x) return x.body();
        // ⚠ 新加入口时 hbody / kind / label 三处都要登记（漏了：图上没节点、跳不过去、多个入口撞同一个 key）
        if (h instanceof Ast.OnJoin x) return x.body();
        if (h instanceof Ast.OnLeave x) return x.body();
        if (h instanceof Ast.OnReload x) return x.body();
        if (h instanceof Ast.OnBreak x) return x.body();
        if (h instanceof Ast.OnPlace x) return x.body();
        if (h instanceof Ast.OnEntity x) return x.body();
        if (h instanceof Ast.OnLook x) return x.body();
        return List.of();
    }

    /** 节点键里用的入口名（start / input / every / timeout / pick）。 */
    public static String kind(Ast.Handler h) {
        if (h instanceof Ast.OnStart) return "start";
        if (h instanceof Ast.OnInput) return "input";
        if (h instanceof Ast.OnTimeout) return "timeout";
        if (h instanceof Ast.OnEvery) return "every";
        if (h instanceof Ast.OnPick) return "pick";
        if (h instanceof Ast.OnWorld) return "world";
        if (h instanceof Ast.OnJoin) return "join";
        if (h instanceof Ast.OnLeave) return "leave";
        if (h instanceof Ast.OnReload) return "reload";
        if (h instanceof Ast.OnBreak) return "break";
        if (h instanceof Ast.OnPlace) return "place";
        if (h instanceof Ast.OnEntity) return "entity";
        if (h instanceof Ast.OnLook) return "look";
        if (h instanceof Ast.OnStage os) return "stage:" + os.stage();
        return "unknown";
    }

    /** 节点上显示的那行字。 */
    public static String label(Ast.Handler h) {
        if (h instanceof Ast.OnStart) return "开局 on start";
        if (h instanceof Ast.OnInput) return "有人输入 on input";
        if (h instanceof Ast.OnTimeout) return "到点 on timeout";
        if (h instanceof Ast.OnPick) return "点选 on pick";
        if (h instanceof Ast.OnWorld) return "世界 on world";
        if (h instanceof Ast.OnJoin) return "进局 on join";
        if (h instanceof Ast.OnLeave) return "掉线 on leave";
        if (h instanceof Ast.OnReload) return "脚本生效 on reload";
        if (h instanceof Ast.OnBreak) return "破坏 on break";
        if (h instanceof Ast.OnPlace) return "放置 on place";
        if (h instanceof Ast.OnEntity) return "右键实体 on entity";
        if (h instanceof Ast.OnLook) return "看向 on look";
        if (h instanceof Ast.OnEvery e) return "每 " + trim(e.sec()) + " 秒 on every";
        // ⚠ hbody / kind / label 三处一个都不能漏（自检有完整性闸门盯着这 14 种事件）
        return "入口";
    }

    static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
