package com.tablegame.script.edit;

import java.util.ArrayList;
import java.util.List;

    /**
     * **动作卡的纯逻辑模型**（节点编辑页「有序卡列表」+「＋新增动作卡」的底座，零 MC 依赖、可自检）。
     * 三件事：{@link #cardsOf} 语句 → 卡 · {@link #setArg} 改一个参数（只动那一段字节）· {@link #insert} 按动词表拼一条新语句插进块里。
     * 三条口径：① 按语句排卡（一行三条 = 三张卡）② 认不出的语句**也进列表**（标「暂不支持在卡上编」，不许静默漏）
     * ③ 容器卡的条件只读指路，但子块要能继续排卡（锚点 = 块首行 + 该行第几个花括号）。
     */
public final class ScriptCard {

    /**
     * 一张卡。
     * @param blockLine 所在块块首行（1 起）· @param nth 块锚点：那行第几个花括号（0 起）· @param index 块里的序号（0 起）
     * @param line 它自己第几行（1 起）· @param lineIndex 在**那一行**里是第几条（0 起；显示「第 N 行 · 第 M 条」用）
     * @param verb 动词（赋值 = 运算符本身）· @param args 实参**源文本**（改它 = 改那一段字节）
     * @param blocks 子块（容器卡才有）· @param raw 原文 · @param known 动词认不认识（不认识 = 标「暂不支持在卡上编」）
     */
    public record Card(int blockLine, int nth, int index, int line, int lineIndex, String verb,
                       List<String> args, List<Block> blocks, String raw, boolean known) { }

    /** 容器卡的子块：显示名（那么 / 否则 / 循环体）+ 锚点（块首行 + 那行第几个花括号）。 */
    public record Block(String label, int line, int nth) { }

    /** 卡片上的一个参数格：名字 · 控件 · 候选来源 · 当前原文。 */
    public record Arg(String name, String control, String source, String text) { }

    /** 一行里最多找几个块（{@code if/else} 两个就到顶，留点余量）。 */
    private static final int MAX_BLOCKS = 4;

    private ScriptCard() { }

    // ================= 甲、读：语句 → 卡 =================

    /** 列出 {@code (blockLine, nth)} 那个块里的**直接子卡**（空块 = 空表）。 */
    public static List<Card> cardsOf(String src, int blockLine, int nth) {
        List<Card> out = new ArrayList<>();
        int[] r = ScriptEdit.bodyRange(src, blockLine, nth);
        if (r == null) return out;
        List<int[]> sts = ScriptEdit.splitStmts(src, r[0], r[1]);
        for (int i = 0; i < sts.size(); i++) {
            int[] st = sts.get(i);
            int line = ScriptEdit.lineOf(src, st[0]);
            int lineIdx = 0;
            for (int k = 0; k < i; k++) {
                if (ScriptEdit.lineOf(src, sts.get(k)[0]) == line) lineIdx++;
            }
            out.add(analyze(src, blockLine, nth, i, line, lineIdx, st[0], st[1]));
        }
        return out;
    }

    /**
     * 这张卡是不是**某一条事件**的那张 {@code if}（条件原文 == 那条事件的守卫，两端空白不算差别）。
     *
     */
    public static boolean isGuardCard(Card c, String guard) {
        return c != null && guard != null && "if".equals(c.verb()) && !c.args().isEmpty()
                && c.args().get(0).strip().equals(guard.strip());
    }

    /**
     * 删掉**某一条事件**：{@code (blockLine, nth)} 那个块里条件正好等于 {@code guard} 的那张 {@code if}
     * —— **连它肚子里的卡一起**（那是它的一部分），块里别的语句、别的方块的事件一个不动。
     *
     */
    public static ScriptEdit.Result removeGuard(String src, int blockLine, int nth, String guard) {
        for (Card c : cardsOf(src, blockLine, nth)) {
            if (isGuardCard(c, guard)) {
                return ScriptEdit.removeStmt(src, blockLine, nth, c.index());
            }
        }
        return new ScriptEdit.Result(src, "没找到条件正好是「" + guard + "」的那张 if，没动");
    }

    /** 一条语句 → 一张卡。 */
    private static Card analyze(String src, int blockLine, int nth, int index, int line, int lineIdx,
                                int start, int end) {
        String raw = src.substring(start, end).strip();
        List<Block> blocks = blocksOf(src, start, end);
        String verb = verbOf(raw);
        boolean known = known(verb);
        List<String> args = known ? argsOf(raw, verb) : List.of();
        return new Card(blockLine, nth, index, line, lineIdx, verb, args, blocks, raw, known);
    }

    /**
     * 这条语句**自己**带哪几个块（容器卡）。做法：在它跨的每一行上依次问
     * {@link ScriptEdit#bodyRange}（0、1、2…），只收**落在本语句区间内**且**不被别的块包住**的
     * —— 后者是关键：{@code if (a) { if (b) { c } }} 里那个内层 if 的块也落在本语句区间内，
     * 不排掉就会被当成「否则」。
     */
    private static List<Block> blocksOf(String src, int start, int end) {
        List<int[]> found = new ArrayList<>();                       // {openOff, closeOff, line, nth}
        int fromLine = ScriptEdit.lineOf(src, start), toLine = ScriptEdit.lineOf(src, end);
        for (int l = fromLine; l <= toLine; l++) {
            for (int k = 0; k < MAX_BLOCKS; k++) {
                int[] r = ScriptEdit.bodyRange(src, l, k);
                if (r == null) break;
                int open = r[0] - 1;
                if (open < start || r[1] > end) break;               // 这个块不是本语句的（或已越界）
                found.add(new int[]{open, r[1], l, k});
            }
        }
        List<int[]> top = new ArrayList<>();                          // 只留最外层
        for (int[] b : found) {
            boolean nested = false;
            for (int[] o : found) {
                if (o != b && b[0] > o[0] && b[1] < o[1]) { nested = true; break; }
            }
            if (!nested) top.add(b);
        }
        top.sort((a, b) -> a[0] - b[0]);
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            int[] b = top.get(i);
            out.add(new Block(blockLabel(src, b[0], i), b[2], b[3]));
        }
        return out;
    }

    /** 子块显示名：{@code if} 的第一个 = 那么、第二个 = 否则；{@code while} / {@code for} = 循环体。 */
    private static String blockLabel(String src, int openOff, int order) {
        if (order > 0) return "否则";
        int ls = ScriptEdit.lineStart(src, ScriptEdit.lineOf(src, openOff));
        String head = src.substring(ls, openOff);
        if (head.contains("while")) return "循环体";
        if (head.contains("for")) return "循环体";
        return "那么";
    }

    /** 语句的动词：赋值 = 运算符本身；调用 = 名字；无括号的（{@code goto} / {@code break}…）= 头一个词。 */
    public static String verbOf(String raw) {
        String t = raw.strip();
        for (String op : new String[]{"+=", "-=", "*=", "="}) {           // 复合赋值排前面，别被 = 先咬到
            int at = topLevelOp(t, op);
            if (at > 0) return op;
        }
        int i = 0;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) i++;
        return i == 0 ? "" : t.substring(0, i);
    }

    /** 顶层（括号 / 花括号 / 字符串之外）出现某个赋值运算符的位置（-1 = 没有）。 */
    private static int topLevelOp(String t, String op) {
        int depth = 0;
        for (int i = 0; i + op.length() <= t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') { i = skipStr(t, i) - 1; continue; }
            // ⚠ 花括号**也算层深**：容器体里的 `score += 1` 不是这条语句的动词
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (depth == 0 && t.startsWith(op, i)) {
                if (op.equals("=") && i + 1 < t.length() && t.charAt(i + 1) == '=') { i++; continue; }
                if (i > 0 && "+-*/<>!=".indexOf(t.charAt(i - 1)) >= 0) continue;
                return i;
            }
        }
        return -1;
    }

    /** 从引号跳到字符串收尾之后（转义不算收尾）。 */
    private static int skipStr(String t, int q) {
        for (int i = q + 1; i < t.length(); i++) {
            if (t.charAt(i) == '\\') { i++; continue; }
            if (t.charAt(i) == '"') return i + 1;
        }
        return t.length();
    }

    /** 这条语句的实参源文本（同一套顶层逗号规则，见 {@link ScriptEdit#argRanges}）。 */
    private static List<String> argsOf(String raw, String verb) {
        List<String> out = new ArrayList<>();
        String t = raw.strip();
        int open = t.indexOf('(');
        if (open >= 0) {
            int close = ScriptEdit.matchParen(t, open);
            if (close < 0) return out;
            for (int[] r : ScriptEdit.argRanges(t, open, close)) {
                String a = t.substring(r[0], r[1]).strip();
                if (!a.isEmpty() || r[0] != r[1]) out.add(a);
            }
            return out;
        }
        // 无括号：`goto 阶段名` / `return 值` 这类 —— 余下的整段算**一个**实参；
        // 表里声明 0 个参数（break / continue / end / hide）的就不给实参。
        String rest = t.substring(verb.length()).strip();
        if (!rest.isEmpty() && paramCount(verb) == 1) out.add(rest);
        return out;
    }

    /** {@code for} 的三段（起始 / 条件 / 每轮）——单独切一次，改条件时按段落。 */
    private static List<String> forParts(String raw) {
        List<String> out = new ArrayList<>();
        String t = raw.strip();
        int open = t.indexOf('(');
        int close = open < 0 ? -1 : ScriptEdit.matchParen(t, open);
        if (close < 0) return out;
        String in = t.substring(open + 1, close);
        int depth = 0, from = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '"') { i = skipStr(in, i) - 1; continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ';' && depth == 0) { out.add(in.substring(from, i).strip()); from = i + 1; }
        }
        out.add(in.substring(from).strip());
        return out;
    }

    /**
     * 卡上的参数格：把**原文**与动词表对起来（名字 / 控件 / 候选来源来自表，值来自文本）。
     *
     * <p>容器卡一律「只读」（边界 1：算式留在文本里改）；赋值卡的两格是「变量」（候选 = 脚本里的槽位）+「值」。
     * 实参个数与表对不上时：多出来的按**文本格**给（不丢内容），少的留着不补。
     */
    public static List<Arg> args(Card c) {
        List<Arg> out = new ArrayList<>();
        ScriptEdit.Card t = table(c.verb);
        List<String> vals = "for".equals(c.verb) ? forParts(c.raw) : c.args;
        for (int i = 0; i < vals.size(); i++) {
            if (t != null && i < t.params().size()) {
                ScriptEdit.Param p = t.params().get(i);
                out.add(new Arg(p.name(), p.control(), p.source(), vals.get(i)));
            } else {
                out.add(new Arg("参数" + (i + 1), ScriptEdit.CTL_TEXT, ScriptEdit.SRC_NONE, vals.get(i)));
            }
        }
        return out;
    }

    /** 动词表里那张卡（认不出 = null）。赋值运算符都归 {@code =} 那张。 */
    public static ScriptEdit.Card table(String verb) {
        if (verb == null) return null;
        for (ScriptEdit.Card c : ScriptEdit.CARDS) {
            if (c.verb().equals(verb)) return c;
        }
        if (isAssign(verb)) {
            for (ScriptEdit.Card c : ScriptEdit.CARDS) {
                if (c.verb().equals("=")) return c;
            }
        }
        return null;
    }

    /** 是不是赋值运算符（{@code =} / {@code +=} / {@code -=} / {@code *=}）。 */
    public static boolean isAssign(String verb) {
        return verb != null && List.of("=", "+=", "-=", "*=").contains(verb);
    }

    /** 认不认识这个动词（表里有 / 是赋值）。 */
    public static boolean known(String verb) {
        return table(verb) != null;
    }

    /** 动词表里那张卡的显示名（认不出 = 空串）。 */
    public static String labelOf(String verb) {
        ScriptEdit.Card c = table(verb);
        return c == null ? "" : c.label();
    }

    /** 表里这个动词有几位参数（认不出 = -1）。 */
    public static int paramCount(String verb) {
        ScriptEdit.Card c = table(verb);
        return c == null ? -1 : c.params().size();
    }

    /** 是不是容器卡（有子块要排卡）。 */
    public static boolean container(Card c) {
        return !c.blocks().isEmpty();
    }

    // ================= 乙、写：改一个参数 / 插一张新卡 =================

    /**
     * 改卡上的第 {@code argIdx} 个参数（值 = **源码片段**，如 {@code score + 1} 或 {@code "文本"}）。
     * 只换那一段字节，其余逐字不变；改完过真源解析，过不了就原文原样退回。
     */
    public static ScriptEdit.Result setArg(String src, int blockLine, int nth, int index, int argIdx, String text) {
        List<Card> cs = cardsOf(src, blockLine, nth);
        if (index < 0 || index >= cs.size()) {
            return new ScriptEdit.Result(src, "这个块只有 " + cs.size() + " 条语句，没有第 " + (index + 1) + " 条，没动");
        }
        Card c = cs.get(index);
        if (!c.known()) return new ScriptEdit.Result(src, "第 " + c.line() + " 行这条暂不支持在卡上编，没动");
        List<int[]> rs = argRangesOf(src, c);
        if (argIdx < 0 || argIdx >= rs.size()) {
            return new ScriptEdit.Result(src, "这条只有 " + rs.size() + " 个参数，没有第 " + (argIdx + 1) + " 个，没动");
        }
        String v = text == null ? "" : text.strip();
        if (v.isEmpty()) return new ScriptEdit.Result(src, "参数不能填成空的（要清空请到文本页删这一句），没动");
        int[] r = rs.get(argIdx);
        return ScriptEdit.checked(src, src.substring(0, r[0]) + v + src.substring(r[1]),
                "已把第 " + c.line() + " 行的「" + (argName(c, argIdx)) + "」改成 " + brief(v));
    }

    /** 某个实参在**源码**里的字符区间（改一个参数就只换这一段）。 */
    private static List<int[]> argRangesOf(String src, Card c) {
        List<int[]> out = new ArrayList<>();
        int st = stmtStart(src, c);
        if (st < 0) return out;
        String raw = c.raw();
        int open = raw.indexOf('(');
        if (open >= 0) {
            int close = ScriptEdit.matchParen(raw, open);
            if (close < 0) return out;
            for (int[] r : ScriptEdit.argRanges(raw, open, close)) {
                // ⚠ 只换**这个参数自己那几个字符**：两端空白留在原地（不然 `say(a, "x")` 会变成
                // `say(a,"x")` —— 只动该动的字节那条纪律，连一个空格也算）
                int a = r[0], b = r[1];
                while (a < b && Character.isWhitespace(raw.charAt(a))) a++;
                while (b > a && Character.isWhitespace(raw.charAt(b - 1))) b--;
                out.add(new int[]{st + a, st + b});
            }
            return out;
        }
        // 无括号语句（goto / return…）：余下那一段就是它唯一的实参
        int lead = raw.length() - raw.stripLeading().length();
        String rest = raw.strip().substring(c.verb().length());
        int at = raw.indexOf(rest.strip(), lead + c.verb().length());
        if (at >= 0 && paramCount(c.verb()) == 1) out.add(new int[]{st + at, st + at + rest.strip().length()});
        return out;
    }

    /** 这条语句在源码里的起点偏移（靠「块内序号」反查 —— 与 {@link #cardsOf} 同一套切分）。 */
    private static int stmtStart(String src, Card c) {
        int[] r = ScriptEdit.bodyRange(src, c.blockLine(), c.nth());
        if (r == null) return -1;
        List<int[]> sts = ScriptEdit.splitStmts(src, r[0], r[1]);
        return c.index() < sts.size() ? sts.get(c.index())[0] : -1;
    }

    /** 卡上第 idx 个参数叫什么（给底栏用）。 */
    public static String argName(Card c, int idx) {
        List<Arg> as = args(c);
        return idx >= 0 && idx < as.size() ? as.get(idx).name() : ("参数" + (idx + 1));
    }

    /**
     * **段3：＋新增动作卡** —— 按动词表拼一条新语句插进块里，每个参数位取默认值（见 {@link #defaultOf}）。
     * ⚠ 候选一个都没有时**拒收**并说明去哪儿先建 —— 插一条空 {@code goto} 会让整个脚本解析不过（真源即文本，插坏的那一瞬档就是坏的）。
     */
    public static ScriptEdit.Result insert(String src, int blockLine, int nth, int index, String verb) {
        ScriptEdit.Card t = table(verb);
        if (t == null) return new ScriptEdit.Result(src, "认不出的动词：" + verb + "，没动");
        List<String> vals = new ArrayList<>();
        for (ScriptEdit.Param p : t.params()) {
            String v = defaultOf(src, p);
            if (v == null) {
                return new ScriptEdit.Result(src, "「" + t.label() + "」的「" + p.name()
                        + "」现在没有可选项（脚本里还没有对应的对象）—— 先在文本页建一个，再加这张卡，没动");
            }
            vals.add(v);
        }
        String code = code(verb, vals);
        ScriptEdit.Result r = ScriptEdit.addStmt(src, blockLine, nth, index, code);
        return r.text().equals(src) ? r : new ScriptEdit.Result(r.text(),
                "已加一张卡：" + t.label() + "（" + brief(code.strip()) + "）");
    }

    /**
     * 一个参数位的默认值（源码片段）；{@code null} = 这个位现在填不出来（调用方据此拒收）。
     * 候选位取**第一个候选**，没有候选就 null。
     */
    public static String defaultOf(String src, ScriptEdit.Param p) {
        return switch (p.control()) {
            case ScriptEdit.CTL_NUM -> "0";
            case ScriptEdit.CTL_PICK -> {
                List<String> cs = ScriptEdit.candidates(src, p.source());
                // 候选空分两种：**可手打**的那几类（物品 id / 方块 id / 实体引用）→ 给空字面量占位（{@code give(actor, "", 0)} 是合法空转）；
                // 必须从脚本里挑的（阶段 / 画布 / 棋子 / 部件 / 函数 / 变量 / 模式…）→ 仍**拒收**（塞个空值只会让人以为加上了）。
                if (cs.isEmpty()) {
                    yield switch (p.source()) {
                        case ScriptEdit.SRC_ITEM, ScriptEdit.SRC_BLOCK, ScriptEdit.SRC_ENTITY -> "\"\"";
                        default -> null;
                    };
                }
                // ⚠ 过一遍 asLiteral：字面量类候选（画布名 / 游戏模式 / 方块 id …）要带引号，
                // 人名与变量名不带 —— 原样拼进去的话 `gamemode(actor, survival)` 会被当成变量名报错。
                yield ScriptEdit.asLiteral(p.source(), cs.get(0));
            }
            case ScriptEdit.CTL_TEXT -> "\"\"";          // 空文本字面量：合法、看得见、随手可改
            default -> "0";                              // 只读位（容器条件）不会走这里
        };
    }

    /**
     * 把动词 + 实参拼成**一段文本**（容器卡拼成多行骨架）。
     *
     * <p>实参原样拼（它们本来就是源码片段）；容器卡给一个**能解析**的骨架：
     * {@code if (1 == 1) { }} / {@code while (1 == 0) { }}（永不进入）/ {@code for (i = 0;i < 1;i += 1) { }}
     * —— 条件是只读位，作者在文本页改成真条件。
     */
    public static String code(String verb, List<String> args) {
        if ("hide".equals(verb) || "end".equals(verb) || "break".equals(verb) || "continue".equals(verb)) {
            return verb;
        }
        if (isAssign(verb)) {
            String var = args.isEmpty() ? "v" : args.get(0);
            String val = args.size() > 1 ? args.get(1) : "0";
            return var + " " + verb + " " + val;
        }
        if ("goto".equals(verb) || "return".equals(verb)) {
            String a = args.isEmpty() ? "" : args.get(0);
            return a.isEmpty() ? verb : verb + " " + a;
        }
        if ("if".equals(verb)) return "if (1 == 1) {\n}";
        if ("while".equals(verb)) return "while (1 == 0) {\n}";
        if ("for".equals(verb)) return "for (i = 0; i < 1; i += 1) {\n}";
        return verb + "(" + String.join(", ", args) + ")";
    }

    private static String brief(String s) {
        String t = s.replace('\n', ' ').strip();
        return t.length() <= 28 ? t : t.substring(0, 28) + "…";
    }
}
