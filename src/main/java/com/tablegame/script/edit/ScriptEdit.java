package com.tablegame.script.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.tablegame.script.Ast;
import com.tablegame.script.Builtins;
import com.tablegame.script.Interp;
import com.tablegame.script.Lexer;
import com.tablegame.script.Parser;

    /**
     * 画布改动 → 写回脚本文本（纯逻辑，零 MC 依赖，可自检）。
     *
     * <p>**文本是唯一真源**：每个动作只动该动的那几行、其余字节原样，改完一律过真源解析（过不了就拒收）。
     * 四个基本动作：{@link #addStage} 新建阶段（末尾追加 {@code on stage 名 { }}）· {@link #addGoto} 拉线
     * （源块末尾插一行 {@code goto 目标}，单行块自动拆成三行）· {@link #removeStage} 删节点（整段删）·
     * {@link #removeGoto} 删连线（删那一行）。
     */
public final class ScriptEdit {
    /** 改过的文本 + 一句给人看的话（改了没有、改了什么）。 */
    public record Result(String text, String note) { }

    private ScriptEdit() { }

    /** 新建阶段：末尾追加一段空的 {@code on stage 名 {}}。 */
    public static Result addStage(String src, String name) {
        String base = src.stripTrailing();
        String out = base + (base.isEmpty() ? "" : "\n\n") + "on stage " + name + " {\n}\n";
        return new Result(out, "已在末尾新建阶段「" + name + "」（空的，去「文本」页往里写逻辑）");
    }

    /**
     * 拉线：在 {@code fromLine}（0 起）那个块的末尾插一行 {@code goto toStage}。
     *
     * <p>用途上它是「从 A 拉一条线到 B」的落盘方式：不需要新增语法，{@code goto} 就是线本身。
     */
    public static Result addGoto(String src, int fromLine, String toStage) {
        String[] lines = splitLines(src);
        if (fromLine < 0 || fromLine >= lines.length) return new Result(src, "找不到这个块，没动");
        int end = blockEnd(lines, fromLine);
        if (end < 0) return new Result(src, "这个块的花括号不配对，没动");
        String indent = indentOf(lines[fromLine]) + "  ";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i != end) {
                out.add(lines[i]);
                continue;
            }
            if (end == fromLine) {
                // 单行块 `on stage a { }`：拆成三行再插（不然没地方插）
                String l = lines[i];
                int b = l.lastIndexOf('}');
                out.add(l.substring(0, b).stripTrailing());
                out.add(indent + "goto " + toStage);
                out.add(indentOf(l) + "}");
            } else {
                out.add(indent + "goto " + toStage);
                out.add(lines[i]);
            }
        }
        return new Result(join(out), "已在「" + lines[fromLine].strip() + "」末尾接上 goto " + toStage);
    }

    /** 删节点：把 {@code fromLine} 那个块整段删掉（连它上面的空行）。 */
    public static Result removeStage(String src, int fromLine, String label) {
        String[] lines = splitLines(src);
        int end = blockEnd(lines, fromLine);
        if (end < 0) return new Result(src, "这个块的花括号不配对，没动");
        int from = fromLine;
        while (from > 0 && lines[from - 1].isBlank()) from--;      // 顺手吃掉上面的空行，不留双空行
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (i < from || i > end) out.add(lines[i]);
        return new Result(stripTrailingBlank(join(out)), "已删掉「" + label + "」整段");
    }

    /** 删连线：在 {@code fromLine} 那个块里删掉最后一行 {@code goto toStage}。 */
    public static Result removeGoto(String src, int fromLine, String toStage) {
        String[] lines = splitLines(src);
        int end = blockEnd(lines, fromLine);
        if (end < 0) return new Result(src, "这个块的花括号不配对，没动");
        int hit = -1;
        for (int i = end; i >= fromLine; i--) {
            if (lines[i].strip().equals("goto " + toStage)) { hit = i; break; }
        }
        if (hit < 0) return new Result(src, "这个块里没找到 goto " + toStage + "，没动");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (i != hit) out.add(lines[i]);
        return new Result(join(out), "已删掉这一行：goto " + toStage);
    }

    /** 一个块里有没有 goto（拉线时用来决定要不要先问一句“追加还是替换”）。 */
    public static List<String> gotosOf(String src, int fromLine) {
        List<String> out = new ArrayList<>();
        String[] lines = splitLines(src);
        if (fromLine < 0 || fromLine >= lines.length) return out;
        int end = blockEnd(lines, fromLine);
        if (end < 0) return out;
        for (int i = fromLine; i <= end; i++) {
            String s = lines[i].strip();
            if (s.startsWith("goto ")) out.add(s.substring(5).strip());
        }
        return out;
    }

    /**
     * 下一个没被占用的阶段名：{@code s1}、{@code s2}…（源码里出现过的都算占用）。
     * ponytail: 线性找名字，够用；真到上千阶段再说。
     */
    public static String nextStageName(String src) {
        for (int n = 1; ; n++) {
            String name = "s" + n;
            if (!src.contains("goto " + name) && !src.contains("on stage " + name)) return name;
        }
    }

    // ===== 画块（screen / part）的定位与回写（「界面」页用）=====

    /**
     * 一条画语句在源码里的位置：{@code line}（1 起）+ {@code index}（<b>行内</b>序号，0 起）。
     *
     */
    public record At(int line, int index) { }

    /** 定位到的一条画语句：种类 + 每个实参的源文本。 */
    public record DrawAt(int line, int index, String kind, List<String> args) { }

    /**
     * 这一行自己是不是**一条完整的画语句**（括号配平，字符串字面量里的括号不算）。
     *
     */
    public static boolean drawLineOk(String src, int line) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return false;
        String t = lines[line - 1];
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '(') depth++;
            else if (ch == ')') { depth--; if (depth < 0) return false; }
        }
        return depth == 0 && !inStr;
    }

    /**
     * 按「行号 + 行内序号」找那条画语句（越界 / 定位不到 = null）。
     *
     * <p>扫描不走 {@code split}：字符串里的括号、{@code // 注释}、以及嵌套在实参里的
     * {@code text(…)}（那是内建函数调用，不是画语句）都得区分开。
     */
    public static DrawAt find(String src, int line, int index) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return null;
        List<Span> sp = spansIn(lines[line - 1]);
        if (index < 0 || index >= sp.size()) return null;
        List<String> args = new ArrayList<>();
        for (ArgText a : argsOf(lines[line - 1], sp.get(index))) args.add(a.text());
        return new DrawAt(line, index, sp.get(index).kind(), List.copyOf(args));
    }

    /**
     * 找 {@code block}（{@code main} / {@code hud} / 部件名）里那条 {@code place} 语句；没有 = null。
     *
     * <p>用途：看板那块「板」的位置/大小就是这一行 —— 编辑器拖板时按它回写实参。
     */
    public static At findPlace(String src, String block) {
        String[] lines = splitLines(src);
        int start = blockStart(lines, block);
        if (start < 0) return null;
        int end = blockEnd(lines, start);
        int last = end < 0 ? lines.length : end;
        for (int l = start + 1; l <= last; l++) {
            if (l < 1 || l > lines.length) break;
            List<Span> sp = spansIn(lines[l - 1]);
            for (int i = 0; i < sp.size(); i++) {
                if ("place".equals(sp.get(i).kind())) return new At(l, i);
            }
        }
        return null;
    }

    /**
     * 这个实参是<b>字面量</b>吗（{@code Ast.Num} / {@code Ast.Str}）—— 画布上能拖的只有字面量，
     * 表达式驱动的框一律只读（{@code 10 + slot * 20} 拖第 2 张牌，没有任何一个数能代表新位置）。
     *
     */
    public static boolean literal(String src, int line, int index, int arg) {
        Ast.Expr e = exprAt(src, line, index, arg);
        return e instanceof Ast.Num || e instanceof Ast.Str
                || (e instanceof Ast.Unary u && "-".equals(u.op()) && u.e() instanceof Ast.Num);
    }

    /**
     * 这一段源码所在**部件**的形参名（不在 {@code part} 体里 → 空表）。
     *
     * <p>用途：结构视图要分清「这个实参是运行时才算出来的」还是「作者在**调用处**写的字」——
     * {@code part btn(id, label, x…) { text(…, label, …) }} 里的 {@code label} 属于后者。
     */
    public static List<String> partParamsAt(String src, int line) {
        String block = blockAt(src, line);                       // "part btn(id, label, x, y, w, can)"
        if (!block.startsWith("part ")) return List.of();
        int lp = block.indexOf('('), rp = block.lastIndexOf(')');
        if (lp < 0 || rp < lp) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : block.substring(lp + 1, rp).split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * 结构视图：一段**文字实参的源码** → 屏上该摆什么字（摆它的**来源**）；整段只由字面量与部件形参组成时返回 {@code null}
     * = 照实值画（那些字是作者在调用处写的，屏上不该变成形参名）。
     * @param partParams 见 {@link #partParamsAt}（不在部件体里 = 空表）· @return 屏上要摆的字；{@code null} = 调用方照实值画
     */
    public static String template(String code, List<String> partParams) {
        List<String> parts = splitPlus(code);
        boolean concat = false;                                  // 顶层有字符串字面量才算「拼接」
        for (String p : parts) {
            if (isLiteralText(p)) {
                concat = true;
                break;
            }
        }
        if (!concat) {                                           // 不是拼接（一个变量 / 算式 / 三目）：整段处理
            if (partParams.contains(code.trim())) return null;   // 作者在调用处写的字：照实值画
            return stub(code);                                   // ⚠ 别在这儿拆 + —— `bx + 1` 是算术不是拼接
        }
        boolean authorWritten = true;
        for (String p : parts) {
            if (!isLiteralText(p) && !partParams.contains(p.trim())) {
                authorWritten = false;
                break;
            }
        }
        if (authorWritten) return null;                          // 作者在调用处写的字：照实值画
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(isLiteralText(p) ? unquote(p) : stub(p));
        return sb.toString();
    }

    /** 按顶层 {@code +} 拆拼接（引号里、括号里的不算）。 */
    private static List<String> splitPlus(String code) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') { i = skipStr(code, i); continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '+' && depth == 0) { out.add(code.substring(start, i)); start = i + 1; }
        }
        out.add(code.substring(start));
        return out;
    }

    /** 跳过一段字符串字面量，返回收尾引号的下标（没有收尾 = 原样返回）。 */
    private static int skipStr(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '"') return j;
        }
        return i;
    }

    /** 整段就是一条字符串字面量吗。 */
    private static boolean isLiteralText(String p) {
        String t = p.trim();
        return t.length() >= 2 && t.charAt(0) == '"' && skipStr(t, 0) == t.length() - 1;
    }

    /** 字符串字面量 → 屏上的字（去引号 + 还原常见转义）。 */
    private static String unquote(String p) {
        String t = p.trim();
        if (!isLiteralText(t)) return t;
        return t.substring(1, t.length() - 1)
                .replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * 值那一段摆它的来源：{@code text(x)} 去掉转换壳、最外层多余的括号去掉，
     * 再把「变量 + 下标 / 字段访问」**化简成根变量名**——
     * {@code total["dealer"]} → {@code total}（「庄家点数：total」比「庄家点数：total["dealer"]」简练）。
     * 函数调用（{@code len(deck)}）、算式、三目**不动**：那些不是一个变量说了算的。
     */
    private static String stub(String p) {
        String c = p.trim();
        if (c.startsWith("text(") && c.endsWith(")") && balanced(c.substring(5, c.length() - 1))) {
            c = c.substring(5, c.length() - 1).trim();
        }
        if (c.startsWith("(") && c.endsWith(")") && balanced(c.substring(1, c.length() - 1))) {
            c = c.substring(1, c.length() - 1).trim();
        }
        String root = rootVar(c);
        return root != null ? root : c;
    }

    /**
     * 「变量 + 一串下标 / 字段访问」的**根变量名**（{@code total["dealer"]} → {@code total} · {@code hand[who][i]} → {@code hand} · {@code r.字段} → {@code r}）。
     * 不是这种形状就返回 {@code null}（照原样摆）：函数调用、算式、三目都得看全了才明白。
     */
    private static String rootVar(String c) {
        int i = 0;
        if (c.isEmpty() || !(Character.isLetter(c.charAt(0)) || c.charAt(0) == '_')) return null;
        while (i < c.length() && (Character.isLetterOrDigit(c.charAt(i)) || c.charAt(i) == '_')) i++;
        if (i == c.length()) return null;                     // 光一个名字：本来就没得化简
        String name = c.substring(0, i);
        for (int j = i; j < c.length(); ) {
            char ch = c.charAt(j);
            if (ch == '.') {                                  // r.字段
                j++;
                while (j < c.length() && (Character.isLetterOrDigit(c.charAt(j)) || c.charAt(j) == '_')) j++;
            } else if (ch == '[') {                           // a[表达式]
                int close = matchBracket(c, j);
                if (close < 0) return null;
                j = close + 1;
            } else {
                return null;                                  // 后面还有别的（调用 / 算式 / 三目）→ 不化简
            }
        }
        return name;
    }

    /** 从 {@code [} 处找配对的 {@code ]}（引号里的不算）；找不到 = -1。 */
    private static int matchBracket(String s, int open) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { i = skipStr(s, i); continue; }
            if (c == '[') d++;
            else if (c == ']' && --d == 0) return i;
        }
        return -1;
    }

    /** 括号 / 引号配平吗（判断最外层那对能不能去掉）。 */
    private static boolean balanced(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { i = skipStr(s, i); continue; }
            if (c == '(') d++;
            else if (c == ')' && --d < 0) return false;
        }
        return d == 0;
    }

    /**
     * 这一行落在哪个块里（{@code screen main} / {@code part card(slot, face)} …；不在块里 = 空串）。
     *
     * <p>用途：底栏告诉作者「你动的是画布自己那一行，还是部件体那一行」—— 改部件体 = 所有调用处一起变。
     */
    public static String blockAt(String src, int line) {
        String[] lines = splitLines(src);
        int idx = line - 1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            boolean top = t.startsWith("screen ") || t.startsWith("part ")
                    || t.startsWith("func ") || t.startsWith("on ");
            if (!top) continue;
            int end = blockEnd(lines, i);
            if (end < 0) continue;
            if (idx >= i && idx <= end) {
                int b = t.indexOf('{');
                return b > 0 ? t.substring(0, b).strip() : t;
            }
            i = end;                                     // 这个块的内部不必再看
        }
        return "";
    }

    /**
     * 新建画语句：插在 {@code block}（{@code main} / {@code hud} / 部件名）那一块的收尾 {@code }} 之前。
     *
     * <p>单行块 {@code screen hud { }} 先拆成三行再插（照 {@link #addGoto} 的先例）。
     * {@code code} 可以多行（按钮 = {@code box} + {@code text} + {@code click}），逐行缩进对齐块体。
     */
    public static Result addDraw(String src, String block, String code) {
        String[] lines = splitLines(src);
        int start = blockStart(lines, block);
        if (start < 0) return new Result(src, "还没「" + block + "」这一块，没动");
        int end = blockEnd(lines, start);
        if (end < 0) return new Result(src, "「" + block + "」的花括号不配对，没动");
        String indent = indentOf(lines[start]) + "  ";
        List<String> add = new ArrayList<>();
        for (String c : code.split("\n", -1)) if (!c.isBlank()) add.add(indent + c.strip());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i != end) {
                out.add(lines[i]);
                continue;
            }
            if (end == start) {                          // 单行块：拆成「头 / 体 / 尾」三行再插
                String l = lines[i];
                int b = l.lastIndexOf('}');
                out.add(l.substring(0, b).stripTrailing());
                out.addAll(add);
                out.add(indentOf(l) + "}");
            } else {
                out.addAll(add);
                out.add(lines[i]);
            }
        }
        return checked(src, join(out), "已在「" + block + "」末尾插进 " + add.size() + " 行");
    }

    /** 新建画布：末尾追加一块空的 {@code screen 名 { }}（脚本里还没有 {@code screen hud} 时那个「新建看板」按钮走它）。 */
    public static Result addScreen(String src, String name) {
        String base = src.stripTrailing();
        String out = base + (base.isEmpty() ? "" : "\n\n") + "screen " + name + " {\n}\n";
        return checked(src, out, "已在末尾新建画布「" + name + "」（空的，右键空白就能往里加框）");
    }

    /**
     * 新建一块画布（「舞台」页三个新建入口走它）：{@code hud=false} → 全屏 · {@code hud=true} → 看板。
     * ⚠ **不写缺省 place(…)**（照「新建区域不带默认盒」的先例）：没写摆位时编辑器与宿主都回落 {@code StageView.DEF_HUD_*}，
     * 作者第一次拖它才把 {@code place(…)} 写进脚本 —— 真源里只留他真定过的东西。
     */
    public static Result addScreen(String src, String name, boolean hud) {
        String base = src.stripTrailing();
        String out = base + (base.isEmpty() ? "" : "\n\n")
                + "screen " + name + (hud ? " hud" : "") + " {\n}\n";
        return checked(src, out, "已在末尾新建" + (hud ? "看板「" : "全屏界面「") + name
                + "」（空的，右键空白就能往里加框）");
    }

    /**
     * 新建一块**实体画面**。
     *
     * <p>与另两类不同：实体画面没有可视化编辑屏（它没有「框」）⇒ 新建时写一份**带注释的骨架**，
     * 作者照着改（注释不进 AST，随便写）。骨架里的参数就是缺省值那组，删掉也一样跑。
     */
    public static Result addEntityScreen(String src, String name) {
        String base = src.stripTrailing();
        String out = base + (base.isEmpty() ? "" : "\n\n")
                + "screen " + name + " entity {\n"
                + "  // 锚点参数（不写就是这组）：follow 1 跟着锚点走 · face 1 按锚点朝向算偏移 · away 8 超距收起\n"
                + "  //   dx / dy / dz = 相对锚点的偏移（格：dx 沿锚点右手、dz 沿锚点前方、dy 向上）\n"
                + "  follow 1\n  face 1\n  dx 0\n  dy 1.2\n  dz 2.5\n"
                + "  // 组件：text（文字，原版 text_display）· card（卡牌实体）· item（原版 item_display）\n"
                + "  //   at x y = 画面内位置（可负数）；mark \"…\" = 右键它时回投 on pick 的身份值\n"
                + "  text t1 { name \"文字\"  body \"改我\"  at 0 0 }\n"
                + "}\n";
        return checked(src, out, "已在末尾新建实体画面「" + name
                + "」（挂上它用 show(谁, \"" + name + "\")：谁 = 玩家名或本局实体名）");
    }

    /**
     * 删一整块画布（连它上面的空行）。⚠ 还被 {@code show("名")} 指到 → **拒收**（回执报哪几行）：删了那一屏再也亮不起来，
     * 而脚本照样解析得过（错要到运行时才显形）—— 与「删阶段时还有线指着它」同一套口径。计数走 {@link #toks}。
     */
    public static Result removeScreen(String src, String name) {
        String[] lines = splitLines(src);
        int at = screenBlockLine(lines, name);
        if (at < 0) return new Result(src, "脚本里没有 screen " + name + " 这一块，没动");
        List<Integer> uses = showLines(src, name);
        if (!uses.isEmpty()) {
            return new Result(src, "「" + name + "」还被 show(\"…\") 指到（第 " + linesText(uses)
                    + " 行）—— 先把那些行改成别的画布（或删掉）再删它");
        }
        return removeStage(src, at, "screen " + name);
    }

    /** 读一块画布的**显示名**（块里那条 {@code name("主界面")}；没写 / 脚本拓不开 = 空串）。 */
    public static String screenLabelOf(String src, String name) {
        try {
            Ast.Screen s = Parser.parse(src == null ? "" : src).screens().get(name);
            return s == null ? "" : s.label();
        } catch (Ast.ScriptError e) {
            return "";                                 // 草稿态（有语法错）也要能打开编辑器：不崩、当没写
        }
    }

    /**
     * 写一块画布的**显示名**（{@code name("主界面")}）。三条出路：已有那条 → 就地换那一行（缩进保持）；没有 → 插在块首；
     * {@code label} 空串 → 删掉那条（取消显示名）。单行块 {@code screen x { }} 先拆成三行再插。
     */
    public static Result setScreenLabel(String src, String name, String label) {
        String text = label == null ? "" : label.strip();
        String[] lines = splitLines(src);
        int start = screenBlockLine(lines, name);
        if (start < 0) return new Result(src, "脚本里没有 screen " + name + " 这一块，没动");
        int end = blockEnd(lines, start);
        if (end < 0) return new Result(src, "「" + name + "」的花括号不配对，没动");
        int had = -1;
        for (int i = start + 1; i <= end; i++) {
            if (isNameStmt(lines[i])) { had = i; break; }
        }
        if (had < 0 && text.isEmpty()) return new Result(src, "「" + name + "」本来就没有显示名，没动");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i == had) {                                              // 已有那条：换掉 / 删掉
                if (!text.isEmpty()) out.add(indentOf(lines[i]) + labelStmt(text));
                continue;
            }
            if (i == start && had < 0 && end == start && !text.isEmpty()) {
                String l = lines[i];                                     // 单行块：拆成头 / 体 / 尾
                int b = l.lastIndexOf('}');
                out.add(l.substring(0, b).stripTrailing());
                out.add(indentOf(l) + "  " + labelStmt(text));
                out.add(indentOf(l) + "}");
                continue;
            }
            out.add(lines[i]);
            if (i == start && had < 0 && end > start && !text.isEmpty()) {
                out.add(indentOf(lines[i]) + "  " + labelStmt(text));    // 插在块首（声明行下面那一行）
            }
        }
        return checked(src, join(out), text.isEmpty() ? "已去掉「" + name + "」的显示名"
                : "已把「" + name + "」的显示名写成「" + text + "」");
    }

    /** 一块画布写的**舞台背景** {@code bg("…")}；没这块画布 / 没写 = 空串。 */
    public static String screenBgOf(String src, String name) {
        try {
            Ast.Screen s = Parser.parse(src == null ? "" : src).screens().get(name);
            return s == null || s.bg() == null ? "" : s.bg();
        } catch (Ast.ScriptError e) {
            return "";                                 // 草稿态（有语法错）也要能打开编辑器：不崩、当没写
        }
    }

    /**
     * 写一块画布的**舞台背景**（{@code bg("#00000080")}）。三条出路同 {@link #setScreenLabel}：
     * 已有那条 → 就地换那一行；没有 → 插在 {@code name(…)} 后面（没显示名就插块首）；{@code color} 空串 → 删掉那条（回到缺省 40% 黑）。
     * ⚠ 颜色串在这里**把关**（来自人的输入框）：只收 {@code #RRGGBB} / {@code #RRGGBBAA}，不收就拒掉并回一句人话（乱写运行时只会静默回兜底色）。
     */
    public static Result setScreenBg(String src, String name, String color) {
        String text = color == null ? "" : color.strip();
        if (!text.isEmpty() && !isColorString(text)) {
            return new Result(src, "颜色串要写成 #RRGGBB 或 #RRGGBBAA（后两位是透明度），没动：" + text);
        }
        String[] lines = splitLines(src);
        int start = screenBlockLine(lines, name);
        if (start < 0) return new Result(src, "脚本里没有 screen " + name + " 这一块，没动");
        int end = blockEnd(lines, start);
        if (end < 0) return new Result(src, "「" + name + "」的花括号不配对，没动");
        int had = -1, afterName = -1;
        for (int i = start + 1; i <= end; i++) {
            if (isBgStmt(lines[i])) had = i;
            else if (isNameStmt(lines[i])) afterName = i;
        }
        if (had < 0 && text.isEmpty()) return new Result(src, "「" + name + "」本来就没写背景，没动");
        String bodyIndent = indentOf(lines[start]) + "  ";              // 块体一级缩进（从**声明行**数，不是从 name 那行）
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i == had) {                                              // 已有那条：换掉 / 删掉
                if (!text.isEmpty()) out.add(indentOf(lines[i]) + bgStmt(text));
                continue;
            }
            if (i == start && had < 0 && end == start && !text.isEmpty()) {
                String l = lines[i];                                     // 单行块：拆成头 / 体 / 尾
                int b = l.lastIndexOf('}');
                out.add(l.substring(0, b).stripTrailing());
                out.add(bodyIndent + bgStmt(text));
                out.add(indentOf(l) + "}");
                continue;
            }
            out.add(lines[i]);
            if (had < 0 && !text.isEmpty() && end > start) {
                if (i == afterName || (afterName < 0 && i == start)) {
                    out.add(bodyIndent + bgStmt(text));
                }
            }
        }
        return checked(src, join(out), text.isEmpty() ? "已去掉「" + name + "」的背景"
                : "已把「" + name + "」的背景写成 " + text);
    }

    /** {@code #RRGGBB} / {@code #RRGGBBAA}（长度与十六进制都对）才收 —— 背景输入框的把关。 */
    private static boolean isColorString(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        if (t.length() != 6 && t.length() != 8) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** 这一行是不是那条背景语句 {@code bg("…")}（注释行 / 别的语句不算）。 */
    private static boolean isBgStmt(String line) {
        String t = line.strip();
        return !t.startsWith("//") && t.startsWith("bg(\"");
    }

    /** 背景那一行的正文（缩进由调用方加）；引号与反斜杠先剔掉（同 {@link #labelStmt} 的老口径）。 */
    private static String bgStmt(String color) {
        return "bg(\"" + color.replace("\\", "").replace("\"", "") + "\")";
    }

    /** {@code screen 名} 那一块的头一行（**0 起**；-1 = 没有）。只认 screen，不认同名部件。 */
    private static int screenBlockLine(String[] lines, String name) {
        for (int i = 0; i < lines.length; i++) {
            if (opener(lines[i].strip(), "screen", name)) return i;
        }
        return -1;
    }

    /** 脚本里所有 {@code show("名")} 出现的**行号**（1 起、按出现顺序）—— 删画布前查它。 */
    private static List<Integer> showLines(String src, String name) {
        List<Integer> out = new ArrayList<>();
        List<Tok> ts = toks(src);
        for (int i = 2; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (!t.str() || !t.text().equals(name)) continue;
            if (!ts.get(i - 1).text().equals("(") || !ts.get(i - 2).text().equals("show")) continue;
            out.add(lineOf(src, t.from()));
        }
        return out;
    }

    /** 这一行是不是那条显示名语句 {@code name("…")}（注释行 / 别的语句不算）。 */
    private static boolean isNameStmt(String line) {
        String t = line.strip();
        return !t.startsWith("//") && t.startsWith("name(\"");
    }

    /** 显示名语句的文本（引号与反斜杠洗掉 —— 与立牌那条 {@code label(x, y, z, "名字")} 同一套口径）。 */
    private static String labelStmt(String text) {
        return "name(\"" + text.replace("\\", "").replace("\"", "") + "\")";
    }

    /** 行号列表 → 「3、7」这种人话（拒收回执里用）。 */
    private static String linesText(List<Integer> ls) {
        StringBuilder sb = new StringBuilder();
        for (int l : ls) sb.append(sb.isEmpty() ? "" : "、").append(l);
        return sb.toString();
    }

    /**
     * 复制一整块画布（{@code screen 源 … { … }} → 末尾追加一份，名字自动查重：源名 + 数字）。
     *
     * <p>⚠ 副本**不带显示名那条**：显示名是给人区分用的，两份顶同一个显示名反而认不出谁是谁。
     * 要的话在预览屏「编 → 改显示名」起一个（那时它会出现在目录里）。
     */
    public static Result duplicateScreen(String src, String name) {
        String[] lines = splitLines(src);
        int start = screenBlockLine(lines, name);
        if (start < 0) return new Result(src, "脚本里没有 screen " + name + " 这一块，没动");
        int end = blockEnd(lines, start);
        if (end < 0) return new Result(src, "「" + name + "」的花括号不配对，没动");
        String nw = "";
        for (int k = 2; k <= 99; k++) {                          // 名字查重（重名 = 解析期当场报错）
            if (screenBlockLine(lines, name + k) < 0) { nw = name + k; break; }
        }
        if (nw.isEmpty()) return new Result(src, "「" + name + "」已经复制得太多了（2~99 都占着），没动");
        List<String> copy = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            String l = lines[i];
            if (i == start) {
                int b = l.indexOf(name);                         // 声明行上的名字位
                copy.add(b < 0 ? l : l.substring(0, b) + nw + l.substring(b + name.length()));
            } else if (!isNameStmt(l)) {
                copy.add(l);
            }
        }
        String out = src.stripTrailing() + "\n\n" + String.join("\n", copy) + "\n";
        return checked(src, out, "已复制出「" + nw + "」（同一份内容；副本没带显示名，要的话在预览屏「编」里起一个）");
    }

    /**
     * 脚本里**被 {@code show("名")} 指到**的画布名（按出现顺序、去重）。
     *
     * <p>用途：「舞台」页目录行标一个「开局显示」—— 一眼看出哪块是开局真会亮出来的那块
     * （一个档常常写好儿块，只有被 show 的那块开局能看见）。走 {@link #toks}：注释里的同名文本不算。
     */
    public static java.util.List<String> shownScreens(String src) {
        java.util.List<String> out = new ArrayList<>();
        List<Tok> ts = toks(src);
        for (int i = 2; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (!t.str()) continue;
            if (!ts.get(i - 1).text().equals("(") || !ts.get(i - 2).text().equals("show")) continue;
            if (!out.contains(t.text())) out.add(t.text());
        }
        return out;
    }

    /**
     * 删一条画语句：同一行还有别的语句时只去掉那一条（其余字节原样），整行就这一条则整行删。
     * ⚠ 顺手处理「绘画区」那一对：{@code paint} 只作用于紧挨它前面的那个框，框删了它就成了孤儿（展开期报错）。
     * 判据按种类：删的是 box / text / input 且紧跟其后是 paint → 一并删。
     */
    public static Result removeDraw(String src, int line, int index) {
        ScriptEdit.DrawAt at = find(src, line, index);
        String one = removeOne(src, line, index);
        if (one == null || at == null) return new Result(src, "第 " + line + " 行上没有这一条画语句，没动");
        String note = "已删掉第 " + line + " 行的第 " + (index + 1) + " 条画语句";
        if (isFrame(at.kind())) {
            String two = removeFollowingPaint(one, line, index);
            if (two != null) note += " · 连带把它后面那条 paint 一并删了（绘画区要成对）";
            else two = one;
            return checked(src, two, note);
        }
        return checked(src, one, note);
    }

    /** 画框的三种画语句（{@code paint} 只认它们画的框）。 */
    private static boolean isFrame(String kind) {
        return "box".equals(kind) || "text".equals(kind) || "input".equals(kind);
    }

    /** 删掉 (line, index) 那一条画语句，返回新文本；定位不到 = null。不校验合法性。 */
    private static String removeOne(String src, int line, int index) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return null;
        String l = lines[line - 1];
        List<Span> sp = spansIn(l);
        if (index < 0 || index >= sp.size()) return null;
        Span s = sp.get(index);
        int from = s.from();
        while (from > 0 && Character.isWhitespace(l.charAt(from - 1))) from--;   // 顺手吃掉前面那段空白
        String rest = l.substring(0, from) + l.substring(s.to());
        List<String> out = new ArrayList<>();
        if (rest.isBlank()) {
            for (int i = 0; i < lines.length; i++) if (i != line - 1) out.add(lines[i]);
        } else {
            lines[line - 1] = indentOf(l) + rest.strip();
            out.addAll(List.of(lines));
        }
        return join(out);
    }

    /**
     * 删掉 (line, index) 之后，紧跟其后的那条语句若是 {@code paint} → 把它也删掉，返回新文本；不是 = null。
     *
     * <p>位置怎么找：同一行后面还有语句 → 就在同行的 {@code index}（删掉一条后后面那条顶上来）；
     * 整行都删了 → 看下一非空行的第一条。
     */
    private static String removeFollowingPaint(String src, int line, int index) {
        String[] lines = splitLines(src);
        int l = line, i = index;
        if (l < 1 || l > lines.length || i >= spansIn(lines[l - 1]).size()) {
            l = line + 1;
            while (l <= lines.length && lines[l - 1].isBlank()) l++;
            if (l > lines.length) return null;
            i = 0;
        }
        List<Span> sp = spansIn(lines[l - 1]);
        if (i >= sp.size() || !"paint".equals(sp.get(i).kind())) return null;
        return removeOne(src, l, i);
    }

    /**
     * 改一条画语句里的第 {@code arg} 个实参（只换那一段源文本，行内其余字节原样）。
     *
     * <p>实参序号（0 起）：{@code box} 0=x 1=y 2=宽 3=高 4=底色 · {@code text} 0..3 几何 4=内容 5=字色 ·
     * {@code input} 0..3 几何 4=提示。
     */
    public static Result setArg(String src, int line, int index, int arg, String text) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return new Result(src, "找不到第 " + line + " 行，没动");
        String l = lines[line - 1];
        List<Span> sp = spansIn(l);
        if (index < 0 || index >= sp.size()) return new Result(src, "第 " + line + " 行上没有这一条画语句，没动");
        List<ArgText> as = argsOf(l, sp.get(index));
        if (arg < 0 || arg >= as.size()) return new Result(src, "这条画语句没有第 " + (arg + 1) + " 个实参，没动");
        ArgText a = as.get(arg);
        lines[line - 1] = l.substring(0, a.from()) + text.strip() + l.substring(a.to());
        return checked(src, String.join("\n", lines), "已改第 " + line + " 行的第 " + (arg + 1) + " 个实参");
    }

    /**
     * 给那条画语句**换一个种类**（{@code box} → {@code input} 这种）—— 「舞台组件编辑页」换类型走它。
     *
     *
     * <p>⚠ 跨行的语句（{@code box(…} 换行写实参）定位不到 ⇒ 拒收并指路脚本页（与 {@link #setArg} 同一口径）。
     */
    public static Result replaceDraw(String src, int line, int index, String code) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return new Result(src, "找不到第 " + line + " 行，没动");
        String l = lines[line - 1];
        List<Span> sp = spansIn(l);
        if (index < 0 || index >= sp.size()) {
            return new Result(src, "第 " + line + " 行上没有这一条画语句（跨行的语句在这儿换不了，去脚本页），没动");
        }
        Span s = sp.get(index);
        lines[line - 1] = l.substring(0, s.from()) + code.strip() + l.substring(s.to());
        return checked(src, String.join("\n", lines),
                "已把第 " + line + " 行的第 " + (index + 1) + " 条画语句换成 " + code.strip());
    }

    /** 舞台上能挂在一条画语句后面的**属性语句**：可点（{@code click}）· 绘画板（{@code paint}）。 */
    public static boolean isAttr(String word) {
        return "click".equals(word) || "paint".equals(word);
    }

    /**
     * 脚本语句的 kind 与运行时 {@code Ast.Box} 的 kind 算不算**同一类** —— 编辑器靠这张对表把「点到的框」对回哪条语句。
     * ⚠ 两边口径**故意**不同，不许比字符串：脚本里 {@code box(5 参)} 与 {@code box(7 参)} 都叫 {@code box}；运行时把带文字的
     * 叫 {@code text}（渲染层用它表示透明底）、画笔画出来的叫 {@code paint}。比字符串 ⇒ 带文字的框对不上脚本 ⇒ 行内序号成 -1
     * （点【编】只报「这一条定位不到」、框也拖不动）。
     */
    public static boolean sameDrawKind(String stmtKind, String boxKind) {
        if (stmtKind == null || boxKind == null) return false;
        if (stmtKind.equals(boxKind)) return true;
        // img 走的就是上面这条同相等判据 —— 它**不归一化**（宿主 / 渲染器 / 编辑器认的都是 img）
        // 运行时这几种都对应脚本里的 box 语句
        if (stmtKind.equals("box")) return boxKind.equals("text") || boxKind.equals("paint");
        // 值框（脚本里 `value`）在渲染层**归一成 text**（{@code Interp.boxOf}：宿主 / 渲染器 / 编辑器都不认新种类）
        // ⇒ 少了这一条，值框的「行内序号」永远是 -1 ⇒ 编辑页「定位不到」。
        if (stmtKind.equals("value")) return boxKind.equals("text");
        return false;
    }

    /**
     * 这条画语句**挂着哪些属性**（{@code click} / {@code paint}）—— 属性 = 往脚本里生成一段代码，界面只是生成器 + 反查视图。
     * 「挂着」两种（都算）：① 同一行紧跟它后面（{@code box(…) click}）② 紧跟其后那一行（空行跳过）只写了 click / paint；中间夹了别的语句不算。
     */
    public static List<String> drawAttrsOf(String src, int line, int index) {
        List<String> out = new ArrayList<>();
        for (String a : new String[] { "click", "paint" }) {
            if (followStmtAt(src, line, index, a) != null) out.add(a);
        }
        return out;
    }

    /**
     * 紧跟在这条画语句后面的**附加语句**（{@code click} / {@code paint} / {@code comp(…)}）在块里的锚点 ——
     * 没有 = {@code null}。判据两种（都算「属于它」）：① 同一行上紧跟它后面写的（{@code box(…) click}）；
     * ② 紧跟其后的那一行（空行跳过）只有那一条语句。中间夹了别的语句就不算。
     */
    public static At followStmtAt(String src, int line, int index, String keyword) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return null;
        List<Span> sp = spansIn(lines[line - 1]);
        if (index < 0 || index >= sp.size()) return null;
        if (index + 1 < sp.size() && sp.get(index + 1).kind().equals(keyword)) {
            return new At(line, index + 1);                        // ① 同一行、紧跟它后面
        }
        if (index != sp.size() - 1) return null;                    // 它后面同一行还有别的画语句 ⇒ 后面的行不归它
        for (int i = line; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("//")) continue;
            boolean hit = isAttr(keyword) ? t.equals(keyword) : t.startsWith(keyword + "(");
            return hit ? new At(i + 1, 0) : null;                   // ② 后面的行：只认独立一条
        }
        return null;
    }

    /**
     * 给这条画语句**加上 / 去掉**一个属性（{@code click} / {@code paint}）：加 = 它那一行下面插一行（缩进照它）；
     * 去 = 删掉独占一行的那条属性语句。两种情况都过 {@link #checked}。
     * ⚠ 单行画块（{@code screen main { box(…) }}）里加不了 —— 会落到块外，当场拒收并指路脚本页。
     */
    public static Result setDrawAttr(String src, int line, int index, String attr, boolean on) {
        if (!isAttr(attr)) return new Result(src, "不认识的属性：" + attr + "，没动");
        List<String> has = drawAttrsOf(src, line, index);
        if (on) {
            if (has.contains(attr)) return new Result(src, "这条已经挂了「" + attr + "」，没动");
            return setFollowLine(src, line, attr, attr, true);
        }
        if (!has.contains(attr)) return new Result(src, "这条没挂「" + attr + "」，没动");
        return setFollowLine(src, line, attr, attr, false);
    }

    /** 组件起名那一条 {@code comp("资产名"〔, "显示名"〕)} 的锚点 + 两个名字；没有 = {@code null}。 */
    public record CompAt(int line, int index, String id, String label) { }

    /** 读这条画语句的**资产名 / 显示名**（{@code comp(…)} 那一条）；没写过 = {@code null}。 */
    public static CompAt compOf(String src, int line, int index) {
        At a = followStmtAt(src, line, index, "comp");
        if (a == null) return null;
        DrawAt d = find(src, a.line(), a.index());
        if (d == null || d.args().isEmpty()) return new CompAt(a.line(), a.index(), "", "");
        String id = inner(d.args().get(0));
        String label = d.args().size() > 1 ? inner(d.args().get(1)) : "";
        return new CompAt(a.line(), a.index(), id, label);
    }

    /**
     * 给这条画语句挂 / 改 / 摘 {@code comp("资产名"〔, "显示名"〕)}（组件编辑页的两个名字走它）。
     *
     * <p>{@code id} 空 = **摘掉**那一条（回到「没有名字」）；已有那一条就**就地换**（行号不变），
     * 没有就在它下面插一行（同 {@link #setDrawAttr} 那套手术）。显示名留空 = 只写资产名。
     */
    public static Result setComp(String src, int line, int index, String id, String label) {
        String name = id == null ? "" : id.strip();
        CompAt now = compOf(src, line, index);
        if (name.isEmpty()) {
            if (now == null) return new Result(src, "这条本来就没有资产名，没动");
            return setFollowLine(src, line, "comp", "comp", false);
        }
        String code = "comp(" + strCode(name)
                + (label == null || label.isBlank() ? "" : ", " + strCode(label.strip())) + ")";
        if (now != null) return replaceDraw(src, now.line(), now.index(), code);
        return setFollowLine(src, line, "comp", code, true);
    }

    /**
     * 在「第 {@code line} 行那条画语句」**下面插一行** / **删掉它后面那一行**（附加语句共用的那套手术）。
     *
     * <p>插：新行缩进照那条画语句；那一行上有 {@code }} 就拒收（单行画块，插进去会落到块外）。
     * 删：只认**独占一行**的那一条（挤在同一行的拒收并指路脚本页）。
     */
    private static Result setFollowLine(String src, int line, String keyword, String code, boolean on) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return new Result(src, "找不到第 " + line + " 行，没动");
        if (on) {
            if (lines[line - 1].indexOf('}') >= 0) {
                return new Result(src, "这一块是单行写法（screen x { box(…) }）—— 去脚本页把它拆成多行再加，没动");
            }
            List<String> out = new ArrayList<>(List.of(lines));
            out.add(line, indentOf(lines[line - 1]) + code);
            return checked(src, String.join("\n", out), "已加「" + code + "」（紧跟第 " + line + " 行那条画语句）");
        }
        for (int i = line; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("//")) continue;
            boolean hit = isAttr(keyword) ? t.equals(keyword) : t.startsWith(keyword + "(");
            if (!hit) {
                return new Result(src, "那条「" + keyword + "」跟别的语句挤在同一行 —— 去脚本页删，没动");
            }
            List<String> out = new ArrayList<>(List.of(lines));
            out.remove(i);
            return checked(src, String.join("\n", out), "已去掉第 " + (i + 1) + " 行的「" + keyword + "」");
        }
        return new Result(src, "找不到那条「" + keyword + "」，没动");
    }

    /**
     * 改完必须还能过真源解析 —— 过不了就拒收（原文原样返回，记下原因）。
     *
     */
    static Result checked(String before, String after, String note) {
        try {
            Parser.parse(after);
        } catch (Ast.ScriptError e) {
            return new Result(before, "改不了（会让脚本不合法）：" + e.getMessage());
        }
        return new Result(after, note);
    }

    /**
     * 把那个实参的源文本喂给真源解析，取回它的 AST 节点（没有这个实参 / 解析不了 = null）。
     *
     * <p>手法：把表达式包成一条只含它的画语句再 {@link Parser#parse} —— 「是不是字面量」由<b>语法树</b>判
     * （{@code Ast.Num} / {@code Ast.Str}），不拿文本猜（{@code -3} 与 {@code 1 + 2} 靠字符串看必然出错）。
     */
    private static Ast.Expr exprAt(String src, int line, int index, int arg) {
        String[] lines = splitLines(src);
        if (line < 1 || line > lines.length) return null;
        List<Span> sp = spansIn(lines[line - 1]);
        if (index < 0 || index >= sp.size()) return null;
        List<ArgText> as = argsOf(lines[line - 1], sp.get(index));
        if (arg < 0 || arg >= as.size()) return null;
        try {
            Ast.Script sc = Parser.parse("screen main {\n  box(0, 0, 1, 1, " + as.get(arg).text() + ")\n}\non start { }\n");
            Ast.Stmt st = sc.screens().get("main").body().get(0);
            return ((Ast.Draw) st).args().get(4);
        } catch (RuntimeException e) {
            return null;                                     // 解析不了 = 当成不可拖
        }
    }

    /** 一行里的一条画语句（{@code to} 不含）。 */
    private record Span(int from, int to, String kind) { }

    /** 一段实参：源文本 + 它在行内的字符区间。 */
    private record ArgText(int from, int to, String text) { }

    /**
     * 这一行里**语句级**的画语句（锚点 = 「第 N 行第 M 条」里的 M）。
     * ⚠ 只认**括号深度 0** 的地方：表达式里面的同名调用（{@code text(…)} 嵌在 {@code value(…)} 里）不是画语句；不按深度筛行内序号会错位。
     * ⚠ 新加画语句时**必须**加进下面 {@code withParen} 名单，漏了这页就认不出那条语句。
     */
    private static List<Span> spansIn(String line) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        int depth = 0;                                        // 括号深度：> 0 = 在某个调用/表达式的**里面**
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') {
                i = skipString(line, i);
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;   // 注释到行尾
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_')) j++;
                String w = line.substring(i, j);
                boolean withParen = w.equals("box") || w.equals("text") || w.equals("input") || w.equals("value")
                        || w.equals("img") || w.equals("card_face") || w.equals("card_back")   // img（期12）· card_* 原来是漏的（牌语句锚不到）
                        || w.equals("place") || w.equals("comp");   // 组件起名（2026-09-27）：带括号，跟画语句一样定位/替换
                boolean bare = w.equals("click") || w.equals("paint");
                int p = nextNonSpace(line, j);
                if (withParen && depth == 0 && p >= 0 && line.charAt(p) == '(') {
                    int e = matchParen(line, p);
                    if (e < 0) return out;                   // 语句跨到下一行：本行上定位不了，到此为止
                    out.add(new Span(i, e + 1, w));
                    i = e + 1;
                    continue;
                }
                if (bare && depth == 0 && (p < 0 || line.charAt(p) != '(')) {
                    out.add(new Span(i, j, w));
                    i = j;
                    continue;
                }
                i = j;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            i++;
        }
        return out;
    }

    /**
     * 顶层逗号切实参：给一对 {@code (…)} 的字符区间，返回每段实参的**原始区间**（两端空白由
     * {@link #addArg} 收窄）。<b>包内可见</b>：{@code ScriptCard} 拿它切**任意语句**的实参 ——
     * 「实参怎么切」这条规则只该有一份（列表字面量与括号里的逗号都不算分隔）。
     */
    static List<int[]> argRanges(String text, int open, int close) {
        List<int[]> out = new ArrayList<>();
        int depth = 0, from = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = text.charAt(i);
            if (c == '"') { i = skipString(text, i) - 1; continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(new int[]{from, i});
                from = i + 1;
            }
        }
        out.add(new int[]{from, close});
        return out;
    }

    /** 一条画语句的实参（按顶层逗号切；列表字面量 / 括号里的逗号不算）。{@code click} / {@code paint} 没有实参。 */
    private static List<ArgText> argsOf(String line, Span s) {
        List<ArgText> out = new ArrayList<>();
        if (s.kind().equals("click") || s.kind().equals("paint")) return out;
        int open = line.indexOf('(', s.from());
        int close = s.to() - 1;                              // ')' 的位置
        for (int[] r : argRanges(line, open, close)) addArg(line, out, r[0], r[1]);
        return out;
    }

    /** 收一段实参：掐掉两端空白，记下区间（改的时候要原样换掉这一段）。 */
    private static void addArg(String line, List<ArgText> out, int from, int to) {
        int a = from, b = to;
        while (a < b && Character.isWhitespace(line.charAt(a))) a++;
        while (b > a && Character.isWhitespace(line.charAt(b - 1))) b--;
        out.add(new ArgText(a, b, line.substring(a, b)));
    }

    /** 从引号处跳到字符串收尾之后（转义 {@code \"} 不算收尾）。 */
    private static int skipString(String line, int quote) {
        for (int i = quote + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') return i + 1;
        }
        return line.length();
    }

    private static int nextNonSpace(String line, int from) {
        for (int i = from; i < line.length(); i++) if (!Character.isWhitespace(line.charAt(i))) return i;
        return -1;
    }

    /** {@code open} 处那个 {@code (} 的配对 {@code）} 在哪（字符串里的括号不算；-1 = 行内不配对）。
     *  包内可见：{@code ScriptGraph.condOf} 取容器条件原文时也用它（虚线上要写的字）。 */
    static int matchParen(String line, int open) {
        int depth = 0;
        for (int i = open; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                i = skipString(line, i) - 1;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** {@code screen 名} / {@code part 名} 那一块的头一行（-1 = 没有）。 */
    private static int blockStart(String[] lines, String name) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (opener(t, "screen", name) || opener(t, "part", name)) return i;
        }
        return -1;
    }

    /** 头一行是不是 {@code 关键字 名}（名后面只能是空白 / {@code {} / {@code (} —— 免得 {@code main2} 匹配上 {@code main}）。 */
    private static boolean opener(String t, String kw, String name) {
        String head = kw + " " + name;
        if (!t.startsWith(head)) return false;
        char c = t.length() > head.length() ? t.charAt(head.length()) : '{';
        return c == '{' || c == '(' || Character.isWhitespace(c);
    }


    // ===== 段0：编辑动词（改名全链 · 重接 · 任意语句增删移 · 顶层声明）=====
    // 三条纪律：① 按行改文本（只动该动的字节，其余逐字不变）② 改完必须过真源解析（过不了 = 原文返回 + note 说原因）
    // ③ 定位不到 / 重名 / 保留字 → 一律拒收返回原文。
    // ⚠ 行号口径：本节 **1 起**（与 find / setArg / 节点图 / {@link Ast} 对齐）；addGoto / removeStage / gotosOf 的 fromLine 是 **0 起**（块首行），别混。
    // 自带一个宽松扫描器（不用 Lexer：它的 Token 只有行号、没有字符偏移，做不了「只换一个名字」）—— 只服务「找名字 / 找括号」。

    /** 一个块里的一条语句：{@code line}（1 起）+ 它在块里的序号 + 原文。 */
    public record StmtAt(int line, int index, String text) { }

    // ---------- 甲、词法级扫描（改名靠它：字符串与注释里的字不算）----------

    /** 一个 token 的原文与它在源码里的字符区间。{@code str} = 字符串字面量（区间含两端引号）。 */
    private record Tok(int from, int to, String text, boolean str) { }

    /** 一处替换：把源码的 {@code [from, to)} 换成 {@code text}。 */
    private record Fix(int from, int to, String text) { }

    /** 把若干处替换一次落地（**从后往前**，前面的区间下标不会被打乱）。 */
    private static String apply(String src, List<Fix> fixes) {
        List<Fix> fs = new ArrayList<>(fixes);
        fs.sort((a, b) -> b.from() - a.from());
        StringBuilder sb = new StringBuilder(src);
        for (Fix f : fs) sb.replace(f.from(), f.to(), f.text());
        return sb.toString();
    }

    /**
     * 切 token（跳过 {@code // 注释} · 块注释 · **空白**；字符串自己成一个 token）。
     *
     */
    private static List<Tok> toks(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {                       // 空白不成 token：相邻判定看的是「上一个真 token」
                i++;
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? src.length() : e + 2;
            } else if (c == '"') {
                int close = skipStr(src, i);                       // 收尾引号下标（没有 = 原样返回 i）
                int to = close <= i ? src.length() : close + 1;
                out.add(new Tok(i, to, src.substring(i + 1, to - 1), true));
                i = to;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < src.length() && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                out.add(new Tok(i, j, src.substring(i, j), false));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) j++;
                out.add(new Tok(i, j, src.substring(i, j), false));
                i = j;
            } else {
                out.add(new Tok(i, i + 1, String.valueOf(c), false));
                i++;
            }
        }
        return out;
    }

    /** 这个偏移量在第几行（1 起）。 */
    static int lineOf(String src, int off) {
        int line = 1;
        for (int k = 0; k < off && k < src.length(); k++) if (src.charAt(k) == '\n') line++;
        return line;
    }

    /** 第 {@code line} 行（1 起）首字符的偏移量（没有这一行 = -1）。 */
    static int lineStart(String src, int line) {
        if (line < 1) return -1;
        int n = 1, i = 0;
        while (i <= src.length()) {
            if (n == line) return i;
            int e = src.indexOf('\n', i);
            if (e < 0) return -1;
            i = e + 1;
            n++;
        }
        return -1;
    }

    /** 从**偏移** {@code from} 起的行尾偏移量（行尾 = 那个换行的位置，或文本末尾）。 */
    static int lineEnd(String src, int from) {
        int i = src.indexOf('\n', from);
        return i < 0 ? src.length() : i;
    }

    /**
     * 第 {@code line} 行（1 起）的**行尾偏移**（没有这一行 = -1）。⚠ 与 {@link #lineEnd} 分两个名字是故意的：
     * 那个收偏移、这个收行号；混用不报错，只会取到行首那个换行（段0 首轮就是这么炸的：StringIndexOutOfBounds）。
     */
    private static int lineEndAt(String src, int line) {
        int ls = lineStart(src, line);
        return ls < 0 ? -1 : lineEnd(src, ls);
    }

    // ---------- 乙、改名全链（直接改 + 报「已连带改 N 处」）----------

    /**
     * 改阶段名（全链）：声明处 {@code on stage 旧} + 所有线 {@code goto 旧} 一起改。
     * ⚠ 只认这两种位置（变量 / 部件 / 函数重名也不动；字符串与注释里的旧名同样不动 —— {@code say(all, "goto 旧")} 是文本）。
     */
    public static Result renameStage(String src, String old, String nw) {
        if (old.equals(nw)) return new Result(src, "新名字和旧名字一样，没动");
        List<Tok> ts = toks(src);
        List<Fix> fx = new ArrayList<>();
        boolean decl = false;
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(old)) continue;
            Tok p = i > 0 ? ts.get(i - 1) : null;
            Tok p2 = i > 1 ? ts.get(i - 2) : null;
            if (p != null && p.text().equals("goto")) {
                fx.add(new Fix(t.from(), t.to(), nw));
            } else if (p != null && p2 != null && p.text().equals("stage") && p2.text().equals("on")) {
                decl = true;
                fx.add(new Fix(t.from(), t.to(), nw));
            }
        }
        if (!decl) return new Result(src, "脚本里没有 on stage " + old + " 这一段，没动");
        return checked(src, apply(src, fx), "已把阶段「" + old + "」改成「" + nw + "」，已连带改 "
                + fx.size() + " 处（声明 1 + 线 " + (fx.size() - 1) + "）");
    }

    /** 改函数名（全链）：声明 {@code func 旧(…)} + 所有调用点 {@code 旧(…)}。 */
    public static Result renameFunc(String src, String old, String nw) {
        if (old.equals(nw)) return new Result(src, "新名字和旧名字一样，没动");
        List<Tok> ts = toks(src);
        List<Fix> fx = new ArrayList<>();
        boolean decl = false;
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(old)) continue;
            Tok p = i > 0 ? ts.get(i - 1) : null;
            if (p != null && (p.text().equals(".") || p.text().equals("@"))) continue;
            if (p != null && p.text().equals("func")) decl = true;
            if (i + 1 >= ts.size() || ts.get(i + 1).str() || !ts.get(i + 1).text().equals("(")) continue;
            fx.add(new Fix(t.from(), t.to(), nw));              // 声明与调用点是同一种形状：名字后紧跟 (
        }
        if (!decl) return new Result(src, "脚本里没有 func " + old + " 这个函数，没动");
        return checked(src, apply(src, fx),
                "已把函数「" + old + "」改成「" + nw + "」，已连带改 " + fx.size() + " 处（含声明）");
    }

    /** 改画布名（全链）：声明 {@code screen 旧} + 所有 {@code show("旧")}。 */
    public static Result renameScreen(String src, String old, String nw) {
        if (old.equals(nw)) return new Result(src, "新名字和旧名字一样，没动");
        List<Tok> ts = toks(src);
        List<Fix> fx = new ArrayList<>();
        boolean decl = false;
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            Tok p = i > 0 ? ts.get(i - 1) : null;
            if (t.str()) {                                      // show("旧名") 里的那个字符串
                if (t.text().equals(old) && p != null && p.text().equals("(") && i > 1
                        && ts.get(i - 2).text().equals("show")) {
                    fx.add(new Fix(t.from(), t.to(), "\"" + nw + "\""));
                }
                continue;
            }
            if (!t.text().equals(old)) continue;
            if (p != null && p.text().equals("screen")) {       // screen 旧名 { / screen 旧名 hud {
                decl = true;
                fx.add(new Fix(t.from(), t.to(), nw));
            }
        }
        if (!decl) return new Result(src, "脚本里没有 screen " + old + " 这一块，没动");
        return checked(src, apply(src, fx),
                "已把画布「" + old + "」改成「" + nw + "」，已连带改 " + fx.size() + " 处（含声明）");
    }

    /**
     * 改变量名（全链）：声明 {@code var 旧} + 所有引用（含下标 / 字段链的根名）。
     * ⚠ 四处**不动**：① {@code r.旧} 的字段位 ② {@code @旧}（组件资产引用）③ 别的命名空间的**名字位**
     * （{@code goto 旧} / {@code on stage 旧} / {@code screen 旧} / {@code part 旧} / {@code func 旧}）④ 字符串里的 {@code "旧"}。
     */
    public static Result renameVar(String src, String old, String nw) {
        if (old.equals(nw)) return new Result(src, "新名字和旧名字一样，没动");
        List<Tok> ts = toks(src);
        List<Fix> fx = new ArrayList<>();
        boolean decl = false;
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(old)) continue;
            Tok p = i > 0 ? ts.get(i - 1) : null;
            Tok p2 = i > 1 ? ts.get(i - 2) : null;
            String pt = p == null ? "" : p.text();
            if (pt.equals(".") || pt.equals("@")) continue;
            if (pt.equals("goto") || pt.equals("screen") || pt.equals("part")
                    || pt.equals("func") || (pt.equals("stage") && p2 != null && p2.text().equals("on"))) continue;
            if (pt.equals("var")) decl = true;
            fx.add(new Fix(t.from(), t.to(), nw));
        }
        if (!decl) return new Result(src, "脚本里没有 var " + old + " 声明，没动");
        return checked(src, apply(src, fx),
                "已把变量「" + old + "」改成「" + nw + "」，已连带改 " + fx.size() + " 处（含声明）");
    }

    /** 声明动词 → 人话（报错 / 回执里用）。 */
    private static String kindLabel(String kind) {
        return switch (kind) {
            case "card" -> "卡牌";
            case "piece" -> "棋子";
            case "block" -> "方块";
            case "item" -> "物品";
            case "entity" -> "实体";
            case "text" -> "文本";
            case "area" -> "区域";
            default -> kind;
        };
    }

    /**
     * 改一条声明的**段头名**（资产名 = 脚本里引用它的名字）：段头 + 全脚本里**内容正好等于旧名**的字符串与裸标识符
     * 都换掉，回执里报改了 N 处。
     */
    public static Result renameDecl(String src, String kind, String old, String nw) {
        if (nw == null || nw.isBlank()) return new Result(src, "新名字不能空着，没动");
        if (old == null || old.equals(nw)) return new Result(src, "新名字和旧名字一样，没动");
        if (declOf(src, kind, old) == null) return new Result(src, "脚本里没有 " + kind + " " + old + " 声明，没动");
        if (declOf(src, kind, nw) != null) return new Result(src, "已经有一个 " + kind + " " + nw + " 了，没动");
        if (Lexer.KEYWORDS.contains(nw) || Builtins.VALUES.contains(nw)) {
            return new Result(src, "「" + nw + "」是保留字 / 内建值名，读起来会混，没动");
        }
        List<Fix> fx = new ArrayList<>();
        for (Tok t : toks(src)) {
            if (!t.text().equals(old)) continue;                 // 字符串 token 的 text() = 内容（去了引号）
            boolean quoted = src.charAt(t.from()) == '"';
            fx.add(new Fix(t.from(), t.to(), quoted ? "\"" + nw + "\"" : nw));
        }
        return checked(src, apply(src, fx), "已把 " + kindLabel(kind) + "「" + old + "」改成「" + nw
                + "」，连带改了 " + fx.size() + " 处（含声明）");
    }

    /**
     * 重接一条线：把第 {@code line} 行（1 起）上那条 {@code goto 旧} 的目标换成 {@code nw}。
     *
     * <p>锚点里带旧目标是为了**防过期**：画布上存的行号与目标名一旦对不上（作者刚在文本页改过），
     * 这里拒收并说清原因，而不是把别的线改坏。
     */
    public static Result setGoto(String src, int line, String old, String nw) {
        if (line < 1 || line > lineOf(src, src.length())) return new Result(src, "第 " + line + " 行不存在，没动");
        List<Tok> ts = toks(src);
        for (int i = 0; i + 1 < ts.size(); i++) {
            Tok g = ts.get(i);
            if (g.str() || !g.text().equals("goto")) continue;
            Tok t = ts.get(i + 1);
            if (t.str() || !t.text().equals(old)) continue;
            if (lineOf(src, g.from()) != line) continue;
            return checked(src, src.substring(0, t.from()) + nw + src.substring(t.to()),
                    "已把第 " + line + " 行的线从「" + old + "」接到「" + nw + "」");
        }
        return new Result(src, "第 " + line + " 行上没有 goto " + old + "（锚点过期了？），没动");
    }

    // ---------- 丙、任意语句：定位 / 增 / 删 / 移 ----------

    /**
     * 列一个块里的**直接子语句**（{@code blockLine} = 块首行，1 起）：序号（0 起）就是 addStmt / removeStmt / moveStmt 的 index。
     * ⚠ 只数直接子语句 —— 容器里的属于容器那一层（要它们就传容器自己那一行）。
     */
    public static List<StmtAt> stmtsOf(String src, int blockLine) {
        return stmtsOf(src, blockLine, 0);
    }

    /**
     * 同 {@link #stmtsOf(String, int)}，块锚点带**行内第几个花括号**（{@code nth}，0 起）——
     * 一行里两个块（{@code if (…) {…} else {…}}）时靠它区分「那么」与「否则」。
     */
    public static List<StmtAt> stmtsOf(String src, int blockLine, int nth) {
        List<StmtAt> out = new ArrayList<>();
        int[] r = bodyRange(src, blockLine, nth);
        if (r == null) return out;
        for (int[] s : splitStmts(src, r[0], r[1])) {
            out.add(new StmtAt(lineOf(src, s[0]), out.size(), src.substring(s[0], s[1])));
        }
        return out;
    }

    /**
     * 插一条语句：落在 {@code blockLine} 那块的**第 {@code index} 条**之前
     * （{@code index} ≥ 语句数 = 追加到块末尾）。
     * {@code code} 可多行（容器卡 = {@code if (x) { }} 加几行），逐行按块体缩进对齐。
     */
    public static Result addStmt(String src, int blockLine, int index, String code) {
        return addStmt(src, blockLine, 0, index, code);
    }

    /** 同 {@link #addStmt(String, int, int, String)}，块锚点带**行内第几个花括号**（{@code nth}，0 起）。 */
    public static Result addStmt(String src, int blockLine, int nth, int index, String code) {
        if (code == null || code.isBlank()) return new Result(src, "要插的语句是空的，没动");
        int[] r = bodyRange(src, blockLine, nth);
        if (r == null) return new Result(src, "第 " + blockLine + " 行不是块的开始（或花括号不配对），没动");
        if (lineOf(src, r[0]) == lineOf(src, r[1])) {           // 单行块：先拆成两行才插得进去
            return addStmt(expandBlock(src, blockLine), blockLine, 0, index, code);
        }
        if (index < 0) return new Result(src, "序号不能是负数，没动");
        List<int[]> sp = splitStmts(src, r[0], r[1]);
        if (index >= sp.size()) index = sp.size();            // 超出 = 追加到块末尾（段2 的「＋加到末尾」直接传大数）
        String head = src.substring(lineStart(src, blockLine), lineEnd(src, lineStart(src, blockLine)));
        String bi = indentOf(head) + "  ";
        List<Fix> fx = new ArrayList<>();
        String note;
        if (index < sp.size()) {                                // 插在某一条之前
            int s0 = sp.get(index)[0];
            int ls = lineStart(src, lineOf(src, s0));
            String li = indentOf(src.substring(ls, lineEnd(src, ls)));
            if (src.substring(ls, s0).isBlank()) {              // 那一条独占行：整行插在它上面
                fx.add(new Fix(ls, ls, linesIndent(code, li) + "\n"));
            } else {                                            // 同一行上还有别的语句：就地插在它前面
                fx.add(new Fix(s0, s0, linesIndent(code, li) + " "));
            }
            note = "已在第 " + blockLine + " 行那块里插进 " + countLines(code) + " 行（落在第 " + (index + 1) + " 条语句之前）";
        } else {                                                // 追加到块末尾
            int at = sp.isEmpty() ? r[0] : sp.get(sp.size() - 1)[1];
            fx.add(new Fix(at, at, "\n" + linesIndent(code, bi)));
            note = "已插到第 " + blockLine + " 行那块的末尾（这块本来 " + sp.size() + " 条语句）";
        }
        return checked(src, apply(src, fx), note);
    }

    /**
     * 删一条语句：它独占行（或跨行）就整段删掉；同一行上还有别的语句就只抠掉那一条。
     * 删容器（{@code if (…) {…}}）= 连它肚子里的一起走（那是它的一部分）。
     */
    public static Result removeStmt(String src, int blockLine, int index) {
        return removeStmt(src, blockLine, 0, index);
    }

    /** 同 {@link #removeStmt(String, int, int)}，块锚点带**行内第几个花括号**（{@code nth}）。 */
    public static Result removeStmt(String src, int blockLine, int nth, int index) {
        int[] r = bodyRange(src, blockLine, nth);
        if (r == null) return new Result(src, "第 " + blockLine + " 行不是块的开始（或花括号不配对），没动");
        List<int[]> sp = splitStmts(src, r[0], r[1]);
        if (index < 0 || index >= sp.size()) {
            return new Result(src, "这个块只有 " + sp.size() + " 条语句，没有第 " + (index + 1) + " 条，没动");
        }
        int[] s = sp.get(index);
        String gone = src.substring(s[0], s[1]);
        return checked(src, apply(src, List.of(deleteFix(src, s))),
                "已删掉第 " + lineOf(src, s[0]) + " 行的第 " + (index + 1) + " 条语句：" + brief(gone));
    }

    /**
     * 同层挪一条语句：{@code delta} = +1 下移 / -1 上移（越界返回原文 + 说明）。
     * 两处之间的空行原地不动，逐字只换了那两段的位置。
     */
    public static Result moveStmt(String src, int blockLine, int index, int delta) {
        return moveStmt(src, blockLine, 0, index, delta);
    }

    /** 同 {@link #moveStmt(String, int, int, int)}，块锚点带**行内第几个花括号**（{@code nth}）。 */
    public static Result moveStmt(String src, int blockLine, int nth, int index, int delta) {
        int[] r = bodyRange(src, blockLine, nth);
        if (r == null) return new Result(src, "第 " + blockLine + " 行不是块的开始（或花括号不配对），没动");
        List<int[]> sp = splitStmts(src, r[0], r[1]);
        if (index < 0 || index >= sp.size()) {
            return new Result(src, "这个块只有 " + sp.size() + " 条语句，没有第 " + (index + 1) + " 条，没动");
        }
        int j = index + (delta < 0 ? -1 : 1);
        if (delta == 0 || j < 0 || j >= sp.size()) {
            return new Result(src, "已经在这一层的" + (delta < 0 ? "最上面" : "最下面") + "了，没动");
        }
        int[] ra = lineRange(src, sp.get(index)), rb = lineRange(src, sp.get(j));
        if (!ownLines(src, sp.get(index)) || !ownLines(src, sp.get(j))) {
            return new Result(src, "这两条里有跟别的语句挤在同一行的，先在文本页把它们拆开再挪，没动");
        }
        int[] first = delta > 0 ? ra : rb, second = delta > 0 ? rb : ra;
        String[] L = splitLines(src);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < first[0]; i++) out.add(L[i]);                    // 之前
        for (int i = second[0]; i <= second[1]; i++) out.add(L[i]);          // 后一条整体搬到前面
        for (int i = first[1] + 1; i < second[0]; i++) out.add(L[i]);        // 中间的空行原地不动
        for (int i = first[0]; i <= first[1]; i++) out.add(L[i]);            // 前一条挪到后面
        for (int i = second[1] + 1; i < L.length; i++) out.add(L[i]);
        return checked(src, join(out), "已把第 " + (index + 1) + " 条语句" + (delta < 0 ? "上移" : "下移") + "一位："
                + brief(src.substring(sp.get(index)[0], sp.get(index)[1])));
    }

    /**
     * 跨层挪一条语句：从 {@code blockLine} 那块的 {@code index}，挪到 {@code targetBlockLine} 那块的**末尾**
     * （「移出本层 / 移入上一层」—— 落盘 = 删那一行 + 在目标块末尾插同一行）。
     * 两处偏移都在原文本上算好一次落地 —— 先插后删会让行号漂、挪错地方。
     */
    public static Result moveStmtTo(String src, int blockLine, int index, int targetBlockLine) {
        return moveStmtTo(src, blockLine, 0, index, targetBlockLine, 0);
    }

    /** 同 {@link #moveStmtTo(String, int, int, int)}，两端块锚点都带**行内第几个花括号**。 */
    public static Result moveStmtTo(String src, int blockLine, int nth, int index, int targetBlockLine, int targetNth) {
        int[] r = bodyRange(src, blockLine, nth);
        if (r == null) return new Result(src, "第 " + blockLine + " 行不是块的开始（或花括号不配对），没动");
        List<int[]> sp = splitStmts(src, r[0], r[1]);
        if (index < 0 || index >= sp.size()) {
            return new Result(src, "这个块只有 " + sp.size() + " 条语句，没有第 " + (index + 1) + " 条，没动");
        }
        if (blockLine == targetBlockLine) return new Result(src, "源块和目标块是同一块，没动（同层挪位置用 moveStmt）");
        int[] tr = bodyRange(src, targetBlockLine, targetNth);
        if (tr == null) return new Result(src, "第 " + targetBlockLine + " 行不是块的开始（或花括号不配对），没动");
        int[] s = sp.get(index);
        String code = src.substring(s[0], s[1]);
        List<Fix> fx = new ArrayList<>();
        fx.add(deleteFix(src, s));
        String head = src.substring(lineStart(src, targetBlockLine), lineEnd(src, lineStart(src, targetBlockLine)));
        String bi = indentOf(head) + "  ";
        List<int[]> tsp = splitStmts(src, tr[0], tr[1]);
        if (lineOf(src, tr[0]) == lineOf(src, tr[1])) {                  // 目标块是单行块：就地插在它的收尾括号之前
            fx.add(new Fix(tr[1], tr[1], " " + linesIndent(code, "").strip() + " "));
        } else if (tsp.isEmpty()) {
            fx.add(new Fix(tr[0], tr[0], "\n" + linesIndent(code, bi)));
        } else {
            int at = tsp.get(tsp.size() - 1)[1];
            fx.add(new Fix(at, at, "\n" + linesIndent(code, bi)));
        }
        return checked(src, apply(src, fx), "已把第 " + lineOf(src, s[0]) + " 行的语句挪到第 " + targetBlockLine
                + " 行那块的末尾：" + brief(code));
    }

    // ---------- 丁、顶层声明（窟窿 A：dim / area / resident / allow_replace / offline）----------
    // ⚠ B 批：players / npc 两条声明随「席位表降级成脚本自己的数组」一起去掉
    // （解析器直接报错）—— 编辑器的工具表里也不能留，否则界面能生成一份解析不过的脚本。

    /** 单值顶层声明（一条只能写一次、就一个值）。 */
    public static final List<String> DECL_KINDS = List.of("dim", "area", "resident", "allow_replace", "offline");

    /**
     * 写一条顶层声明（窟窿 A）：有那一条 → 就地改；一条都没有 → 插在文件开头的注释块之后。
     * {@code value} = 关键字后面那一截原文（dim 带引号 · area 六个数字 · resident / allow_replace 是 0 或 1）；值写坏由 {@link #checked} 拒收。
     */
    public static Result setDecl(String src, String kind, String value) {
        if (!DECL_KINDS.contains(kind)) return new Result(src, "「" + kind + "」不是单值顶层声明，没动");
        if (value == null || value.isBlank()) return new Result(src, "值不能空着（要删这条用 removeDecl），没动");
        int at = declLine(src, kind);
        if (at >= 0) {                                                   // 已有：只换那一行
            int ls = lineStart(src, at), le = lineEnd(src, ls);
            String line = indentOf(src.substring(ls, le)) + kind + " " + value.strip();
            return checked(src, apply(src, List.of(new Fix(ls, le, line))), "已改顶层声明：" + line.strip());
        }
        int ins = leadEnd(src);                                          // 一条都没有：插在开头的注释块之后
        String line = kind + " " + value.strip() + "\n";
        return checked(src, apply(src, List.of(new Fix(ins, ins, line))), "已加顶层声明：" + line.strip());
    }

    /** 删一条顶层声明（没有那一条 = 原文 + 说明）。 */
    public static Result removeDecl(String src, String kind) {
        int at = declLine(src, kind);
        if (at < 0) return new Result(src, "脚本里没有 " + kind + " 这一条，没动");
        int ls = lineStart(src, at), le = lineEnd(src, ls);
        int cut = le < src.length() ? le + 1 : le;                       // 连行尾换行一起删
        return checked(src, apply(src, List.of(new Fix(ls, cut, ""))), "已删掉顶层声明 " + kind);
    }

    // ---------- 子、区域声明 ----------

    // ===== 丑、掉落表与掉落池 =====






    /**
     * 脚本里所有**区域**（顶层 {@code area}，顺序 = 源码顺序）—— 解析好的形态（世界 + 已排序的盒 + 内容引用）。
     * 世界页「区域」栏与服务端「按声明盒捕获 / 盖章」都调它（盒与世界只解析一处）。
     * ⚠ 脚本有语法错时给**空表**：草稿照旧存得下，只是这一栏列不出来，不因为一处笔误把整个「世界」页堵死。
     */
    public static List<Interp.Region> regionsOf(String src) {
        try {
            return Interp.regionsOf(Parser.parse(src == null ? "" : src));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 一条**具名区域**声明在第几行（1 起；没有 = -1）。块式与平铺都认，只认名字完全对上的那条。
     *
     * <p>名字的判据 = {@code area} 之后第一截「字母开头 + 字母数字下划线」—— 平铺的老形态
     * （{@code area 1 2 3 4 5 6} / {@code area -1 …}）第一截是数字或 {@code -}，天然不算名字。
     */
    private static int areaLineOf(String src, String name) {
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (!t.startsWith("area")) continue;
            String rest = t.substring("area".length()).strip();
            if (rest.isEmpty() || !Character.isLetter(rest.charAt(0))) continue;
            int e = 0;
            while (e < rest.length() && (Character.isLetterOrDigit(rest.charAt(e)) || rest.charAt(e) == '_')) e++;
            if (rest.substring(0, e).equals(name)) return i + 1;
        }
        return -1;
    }

    /** 这个区域名在脚本里被引用了几次（{@code tp(…, "名", …)} / {@code in_area("名", …)} 的字符串实参）。 */
    private static int areaRefs(String src, String name) {
        int n = 0;
        for (Tok t : toks(src)) {
            if (!t.str() || !t.text().equals(name)) continue;
            String lt = lineText(src, lineOf(src, t.from()));
            if (lt.contains("tp(") || lt.contains("in_area(")) n++;
        }
        return n;
    }

    /**
     * 删一条**具名区域**声明：块式连整段花括号体一起删，平铺删那一行。
     *
     * <p>两道闸（与删变量 {@link #removeVar} 同一条口径）：① 还在脚本里被 {@code tp} / {@code in_area}
     * 引用 → 拒收（先改那些地方 —— 删了它脚本照样解析得过，错要到开局运行时才炸）；② 名字对不上 → 原文 + 说明。
     * <b>匿名那条（老形态）不在这里删</b>：它是「活动范围」，走 {@link #setDecl} 覆盖 / {@link #removeDecl} 删。
     */
    public static Result removeArea(String src, String name) {
        if (name == null || name.isBlank()) {
            return new Result(src, "匿名区域不是具名区域，没动（要改它用「设为活动范围」）");
        }
        int at = areaLineOf(src, name);
        if (at < 0) return new Result(src, "脚本里没有区域 " + name + " 这一条，没动");
        int uses = areaRefs(src, name);
        if (uses > 0) {
            return new Result(src, "区域「" + name + "」还在脚本里被引用 " + uses + " 次（tp / in_area），先改掉那些地方，没动");
        }
        int ls = lineStart(src, at);
        int cut;
        if (lineText(src, at).strip().endsWith("{")) {                  // 块式：连体一起删
            int[] br = bodyRange(src, at, 0);
            if (br == null) return new Result(src, "区域「" + name + "」的花括号没关上，先补上再删，没动");
            int after = br[1] + 1;                                      // 收尾 } 之后
            if (after < src.length() && src.charAt(after) == '\n') after++;
            cut = after;
        } else {
            int le = lineEndAt(src, at);
            cut = le < src.length() ? le + 1 : le;
        }
        return checked(src, apply(src, List.of(new Fix(ls, cut, ""))), "已删掉区域声明 " + name);
    }

    /**
     * 表达式 → **它写的字面量**（不是字面量 = 空串）。
     *
     * <p>给编辑器用：{@code base} 现在是表达式（可以写 `手牌[1]`），但输入框要预填的是「人写的那串」——
     * 字面量取出它的内容；变量之类的一律空着（编辑页里显示为空，改一下就变回字面量）。
     */
    public static String literalOf(Ast.Expr e) {
        return e instanceof Ast.Str s ? s.v() : "";
    }

    public static Result removeEntityComp(String src, String screen, String asset) {
        Ast.Screen sc;
        try {
            sc = Parser.parse(src).screens().get(screen);
        } catch (Ast.ScriptError e) {
            return new Result(src, "脚本现在有语法错（" + e.getMessage() + "），没动");
        }
        if (sc == null || !sc.entityView()) {
            return new Result(src, "脚本里没有实体画面「" + screen + "」这一块，没动");
        }
        int at = -1;
        for (Ast.EntityComp c : sc.comps()) {
            if (c.asset().equals(asset)) {
                at = c.line();
                break;
            }
        }
        if (at < 0) {
            return new Result(src, "这块画面里没有组件「" + asset + "」，没动");
        }
        int[] br = bodyRange(src, at, 0);
        if (br == null) {
            return new Result(src, "组件「" + asset + "」的花括号没关上，先补上再删，没动");
        }
        int ls = lineStart(src, at);
        int cut = br[1] + 1;                                            // 收尾 } 之后
        while (cut < src.length() && (src.charAt(cut) == ' ' || src.charAt(cut) == '\t')) {
            cut++;                                                     // 同一行剩下的空白一起收
        }
        if (cut < src.length() && src.charAt(cut) == '\n') {
            cut++;                                                     // 连那一行的换行一起删（不留下空行）
        }
        return checked(src, apply(src, List.of(new Fix(ls, cut, ""))), "已删掉组件「" + asset + "」");
    }

    /**
     * 改一个**实体画面组件**的数字键：{@code key} = {@code at}（2~3 个数）/ {@code rot}（3 个）/ {@code scale}（1 个）。
     * 块里有这个键 → 就地换那几个数字（其余字节不动）；没有 → 在块内最后一个非空白字符后追加（单行 / 多行同一个写法）。
     * ⚠ 找数字时跳过字符串与注释（{@code body "at 0 0"} 里的字样不算键）；写完过一遍真源解析。
     */
    public static Result setEntityCompNums(String src, String screen, String asset, String key,
            double... vals) {
        return setEntityCompValue(src, screen, asset, key, numListOf(vals));
    }

    /**
     * 改一个实体画面组件的**一个键**。
     * {@code raw} = 要写进去的原文（数字串 / {@code "带引号的文本"}）。
     */
    public static Result setEntityCompValue(String src, String screen, String asset, String key,
            String raw) {
        int[] blk = entityCompRange(src, screen, asset);
        if (blk == null) {
            return new Result(src, "找不到组件「" + asset + "」（画面 " + screen + "），没动");
        }
        int i0 = blk[0], i1 = blk[1];                                  // 块内区间（不含花括号）
        String inner = src.substring(i0, i1);
        int keyAt = keyPos(inner, key);
        String nums = raw;                                             // 兼容老变量名（下面直接拼它）
        if (keyAt < 0) {                                              // 追加
            int last = inner.length() - 1;
            while (last >= 0 && Character.isWhitespace(inner.charAt(last))) {
                last--;
            }
            int at = i0 + last + 1;
            return checked(src, src.substring(0, at) + " " + key + " " + nums + src.substring(at),
                    "已给组件「" + asset + "」加上 " + key + " " + nums);
        }
        int s = keyAt + key.length();
        while (s < inner.length() && Character.isWhitespace(inner.charAt(s))) {
            s++;                                                       // 跳过键与第一个数之间的空白
        }
        int e = endOfNumbers(inner, s, key.equals("scale") ? 1 : 3);
        if (e < 0) {
            return new Result(src, "组件「" + asset + "」的 " + key + " 后面不是数字（手改过？），没动");
        }
        return checked(src, src.substring(0, i0 + s) + nums + src.substring(i0 + e),
                "组件「" + asset + "」的 " + key + " → " + nums);
    }

    /**
     * 往一块实体画面里**加一个组件** ——骨架写在块末尾。
     *
     * <p>位置默认 **盔甲架顶**（局部系 {@code at 0 1.9 0}）：新建出来的组件就立在被 show 的目标头顶，
     * 作者拖着手柄往下摆。卡牌 / 物品的 {@code base} 由调用方给（没有可用候选就别建，见屏里那句人话）。
     */
    public static Result addEntityComp(String src, String screen, String kind, String base) {
        Ast.Screen sc;
        try {
            sc = Parser.parse(src).screens().get(screen);
        } catch (Ast.ScriptError e) {
            return new Result(src, "脚本现在有语法错（" + e.getMessage() + "），没动");
        }
        if (sc == null || !sc.entityView()) {
            return new Result(src, "脚本里没有实体画面「" + screen + "」这一块，没动");
        }
        int[] br = bodyRange(src, sc.line(), 0);
        if (br == null) {
            return new Result(src, "画面「" + screen + "」的花括号没关上，先补上再加，没动");
        }
        String asset = freeCompName(sc, kind.equals("card") ? "c" : kind.equals("item") ? "i" : "t");
        String decl = switch (kind) {
            case "card" -> "card " + asset + " { name \"卡牌\" base \"" + base
                    + "\" scale 1 at 0 1.9 0 }";
            case "item" -> "item " + asset + " { name \"物品\" base \"" + base
                    + "\" scale 1 at 0 1.9 0 }";
            default -> "text " + asset + " { name \"文字\" body \"改我\" at 0 1.9 0 }";
        };
        int at = br[1];                                                // 收尾 } 之前
        String head = src.substring(0, at);
        int cut = head.length();
        while (cut > 0 && Character.isWhitespace(head.charAt(cut - 1))) {
            cut--;
        }
        String ind = "\n  ";
        return checked(src, src.substring(0, cut) + ind + decl + src.substring(cut),
                "已加上 " + kind + " 组件 " + asset + "（在盔甲架顶部，拖它摆位置）");
    }

    /**
     * 换一个实体画面组件的**类型**（= 声明动词 `text` / `card` / `item`；组件编辑页那三颗按钮）。
     *
     * <p>只做两件：把**那一行行首的动词**换掉 + 给新类型补缺省字段（`card`/`item` 要 `base`；
     * `text` 要 `body`）。旧类型留下的字段（如 `card` 上的 `body`）**不删** —— 引擎对多余的键不报错，
     * 删反而容易动坏手写块。行首不是旧动词（手改过）就拒收。
     */
    public static Result setEntityCompKind(String src, String screen, String asset, String kind,
            String base) {
        Ast.EntityComp c = entityCompOf(src, screen, asset);
        if (c == null) {
            return new Result(src, "找不到组件「" + asset + "」（画面 " + screen + "），没动");
        }
        if (c.kind().equals(kind)) {
            return new Result(src, "本来就是「" + kind + "」，没动");
        }
        int ls = lineStart(src, c.line());
        int k = ls;
        while (k < src.length() && (src.charAt(k) == ' ' || src.charAt(k) == '\t')) {
            k++;
        }
        if (!src.startsWith(c.kind(), k)) {
            return new Result(src, "组件「" + asset + "」那一行不是以 " + c.kind() + " 开头（手改过？），没动");
        }
        // ⚠ **先补字段、再换动词**（本轮自己踩的坑）：换成 card 但还没写 base 的那一瞬间脚本解析不过去，
        //  后续任何「按组件名找块」的写回都会失败（表现为「找不到组件」）。反过来先加 base/body 两种类型的
        //  Parser 都收，再换动词全程合法。
        String work = src;
        if (kind.equals("text")) {
            if (c.body() == null) {
                Result r = setEntityCompValue(work, screen, asset, "body", "\"\"");
                if (r.text().equals(work)) {
                    return r;
                }
                work = r.text();
            }
        } else if (c.base() == null) {                      // 没写才补；写的是变量（手牌[i]）就别动它
            Result r = setEntityCompValue(work, screen, asset, "base", strCode(base == null ? "" : base));
            if (r.text().equals(work)) {
                return r;
            }
            work = r.text();
        }
        int k2 = k;                                                // 补字段没动这一行之前的内容，位置照旧
        String out = work.substring(0, k2) + kind + work.substring(k2 + c.kind().length());
        return checked(src, out, "组件「" + asset + "」的类型 → " + kind);
    }

    /**
     * 删一个实体画面组件块里的**一个键**（连它后面那个值一起；组件编辑页「三格都空 = 清掉角度」用它）。
     * 键不存在 = 原文不动 + 一句说明。
     */
    public static Result removeEntityCompKey(String src, String screen, String asset, String key) {
        int[] blk = entityCompRange(src, screen, asset);
        if (blk == null) {
            return new Result(src, "找不到组件「" + asset + "」（画面 " + screen + "），没动");
        }
        int i0 = blk[0], i1 = blk[1];
        String inner = src.substring(i0, i1);
        int keyAt = keyPos(inner, key);
        if (keyAt < 0) {
            return new Result(src, "组件「" + asset + "」里没写 " + key + " 这个键，没动");
        }
        int v0 = keyAt + key.length();
        while (v0 < inner.length() && Character.isWhitespace(inner.charAt(v0))) {
            v0++;
        }
        int v1;
        if (v0 < inner.length() && inner.charAt(v0) == '"') {
            int close = inner.indexOf('"', v0 + 1);
            v1 = close < 0 ? -1 : close + 1;
        } else {
            v1 = endOfNumbers(inner, v0, key.equals("scale") ? 1 : 3);
        }
        if (v1 < 0) {
            return new Result(src, "组件「" + asset + "」的 " + key + " 后面认不出值，没动");
        }
        int cut0 = keyAt, cut1 = v1;
        if (cut0 > 0 && inner.charAt(cut0 - 1) == ' ' && cut1 < inner.length() && inner.charAt(cut1) == ' ') {
            cut0--;                                                // 顺手吃掉一个空格，别留双空格
        }
        return checked(src, src.substring(0, i0 + cut0) + src.substring(i0 + cut1),
                "组件「" + asset + "」已去掉 " + key);
    }

    /** 按「画面名 + 组件资产名」拿组件声明；找不到 = null。 */
    private static Ast.EntityComp entityCompOf(String src, String screen, String asset) {
        try {
            Ast.Screen sc = Parser.parse(src).screens().get(screen);
            if (sc == null || !sc.entityView()) {
                return null;
            }
            for (Ast.EntityComp c : sc.comps()) {
                if (c.asset().equals(asset)) {
                    return c;
                }
            }
        } catch (Ast.ScriptError e) {
            return null;
        }
        return null;
    }

    /** 组件块内的区间 {@code [起, 止)}（不含花括号）；找不到 = null。 */
    private static int[] entityCompRange(String src, String screen, String asset) {
        Ast.Screen sc;
        try {
            sc = Parser.parse(src).screens().get(screen);
        } catch (Ast.ScriptError e) {
            return null;
        }
        if (sc == null || !sc.entityView()) {
            return null;
        }
        for (Ast.EntityComp c : sc.comps()) {
            if (c.asset().equals(asset)) {
                return bodyRange(src, c.line(), 0);
            }
        }
        return null;
    }

    /** 块内文本里 {@code key} 的位置（**跳过字符串与注释**；前后都要是词边界）；没有 = -1。 */
    private static int keyPos(String inner, String key) {
        boolean inStr = false, inLine = false;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (inLine) {
                if (ch == '\n') inLine = false;
                continue;
            }
            if (inStr) {
                if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') {
                inStr = true;
                continue;
            }
            if (ch == '/' && i + 1 < inner.length() && inner.charAt(i + 1) == '/') {
                inLine = true;
                continue;
            }
            if (inner.startsWith(key, i)) {
                boolean pre = i == 0 || !isWordChar(inner.charAt(i - 1));
                int after = i + key.length();
                boolean post = after >= inner.length() || !isWordChar(inner.charAt(after));
                if (pre && post) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@';
    }

    /** 从 {@code from} 起连着读**最多 max 个数字**（含负号），返回结束位置；一个都没读到 = -1。 */
    private static int endOfNumbers(String s, int from, int max) {
        int i = from;
        for (int n = 0; n < max; n++) {
            int j = i;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                j++;
            }
            int k = j, digits = 0;
            while (k < s.length()) {
                char c = s.charAt(k);
                if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                    if (Character.isDigit(c)) {
                        digits++;
                    }
                    k++;
                } else {
                    break;
                }
            }
            if (digits == 0) {
                break;
            }
            i = k;
        }
        return i > from ? i : -1;
    }

    /** 数字们 → 脚本里那串（整数不写 .0，其余最多 4 位小数）。 */
    private static String numListOf(double... vals) {
        StringBuilder b = new StringBuilder();
        for (double v : vals) {
            b.append(b.length() == 0 ? "" : " ").append(numText(v));
        }
        return b.toString();
    }

    /** 一个数字的脚本写法（拖手柄拖出来的值别写成 0.30000000000000004）。 */
    public static String numText(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        if (r == Math.rint(r) && Math.abs(r) < 1e9) {
            return String.valueOf((long) r);
        }
        return String.valueOf(r);
    }

    /** 块里没被占用的组件资产名（t1 / t2 …；撞了就往后找）。 */
    private static String freeCompName(Ast.Screen sc, String prefix) {
        for (int i = 1; i < 1000; i++) {
            String cand = prefix + i;
            boolean used = false;
            for (Ast.EntityComp c : sc.comps()) {
                if (c.asset().equals(cand)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return cand;
            }
        }
        return prefix + "999";
    }

    /**
     * 删一条**顶层变量**声明（{@code var 名 = …}）。两条闸门：① 还在别处被引用（算式 / 下标 / 字段链根名都算）→ 拒收
     * （删了脚本照样解析得过，错要到开局运行时才炸）② 只在**顶层**找（函数体里的同名局部变量不动它）。计数走 {@link #toks}。
     */
    public static Result removeVar(String src, String name) {
        String[] lines = splitLines(src);
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!isVarDecl(lines[i], name)) continue;
            if (!blockAt(src, i + 1).isEmpty()) continue;             // 落在某个块里 = 局部变量，不是它
            at = i + 1;
            break;
        }
        if (at < 0) return new Result(src, "脚本顶层没有 var " + name + " 这一条，没动");
        int uses = 0;
        for (Tok t : toks(src)) {
            if (!t.str() && t.text().equals(name) && lineOf(src, t.from()) != at) uses++;
        }
        if (uses > 0) return new Result(src, "「" + name + "」还在别处被用了 " + uses + " 次，先改掉那些地方，没动");
        int ls = lineStart(src, at), le = lineEndAt(src, at);
        int cut = le < src.length() ? le + 1 : le;
        return checked(src, apply(src, List.of(new Fix(ls, cut, ""))), "已删掉顶层变量 var " + name);
    }

    /** 这一行是不是 {@code var 名} 的声明（名字后面只能是空白 / {@code [} / {@code @} / {@code =}）。 */
    private static boolean isVarDecl(String line, String name) {
        String t = line.strip();
        String head = "var " + name;
        if (!t.startsWith(head)) return false;
        if (t.length() == head.length()) return true;
        char c = t.charAt(head.length());
        return c == '[' || c == '@' || c == '=' || Character.isWhitespace(c);
    }

    // ---------- 己、顶层变量：读 / 新建 / 改初值 / 切归属 / 改可见性（段4 数值页可写）----------
    //
    // 真源永远是脚本里那几行 `var 名 @条件 = 初值`：变量页只是它的编辑视图，
    // 所以这里只做「按行改文本」，一个字都不重打印（注释 / 缩进 / 空行全留）。

    /**
     * 一条**顶层**变量声明读出来的样子：名字 · 可见性原文（{@code @(…)}，没写 = 空串）·
     * 初值原文 · 行号（1 起）。（归属那一维 B 批随 {@code [seat]} 一起去掉。）
     */
    public record VarAt(String name, String vis, String init, int line) { }

    /** 列出脚本里所有**顶层** {@code var} 声明（块里的同名局部变量不算 —— 那不是同一条）。 */
    public static List<VarAt> varsOf(String src) {
        List<VarAt> out = new ArrayList<>();
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (!t.startsWith("var ") && !t.equals("var")) continue;
            if (!blockAt(src, i + 1).isEmpty()) continue;               // 落在块里 = 局部变量
            VarAt v = parseVar(t, i + 1);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** 解析一行 {@code var 名 @可见 = 初值}（认不出 = null）。 */
    private static VarAt parseVar(String t, int line) {
        int i = 4;                                                       // "var " 之后
        int st = i;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) i++;
        if (i == st) return null;
        String name = t.substring(st, i);
        int eq = declEq(t, i);
        if (eq < 0) return null;
        String mid = t.substring(i, eq);
        int at = mid.indexOf('@');
        return new VarAt(name, at < 0 ? "" : mid.substring(at + 1).strip(),
                stripComment(t.substring(eq + 1)), line);
    }

    /** 掐掉行尾 {@code // 注释}（字符串里的 {@code //} 不算 —— 初值可能是 `"http://x"`）。 */
    private static String stripComment(String t) {
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '"') { k = Math.max(k, skipStr(t, k) - 1); continue; }
            if (c == '/' && k + 1 < t.length() && t.charAt(k + 1) == '/') return t.substring(0, k).strip();
        }
        return t.strip();
    }

    /**
     * 声明里那个 {@code =} 在哪（-1 = 没有）。
     * ⚠ 不能直接 {@code indexOf("=")}：可见性条件里太常见 {@code ==}（{@code @(viewer == painter)}）
     * —— 会把它当成初值起点，改初值就砍在条件上。所以只在**括号层深 0**、且不是 {@code ==}/{@code !=}
     * /{@code <=}/{@code >=} 的那个 {@code =} 才算。
     */
    private static int declEq(String t, int from) {
        int depth = 0;
        for (int k = from; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '"') { k = Math.max(k, skipStr(t, k) - 1); continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '=' && depth == 0) {
                if (k + 1 < t.length() && t.charAt(k + 1) == '=') { k++; continue; }
                if (k > 0 && "+-*/<>!".indexOf(t.charAt(k - 1)) >= 0) continue;
                return k;
            }
        }
        return -1;
    }

    /**
     * 新建一个顶层变量（插在**最后一条 var 的下一行**；一条都没有就插在档头注释之后）。
     * {@code vis} 非空就写 {@code @(条件)}（可见性）。
     */
    public static Result addVar(String src, String name, String vis, String init) {
        if (name == null || name.isBlank() || !Character.isLetter(name.charAt(0))) {
            return new Result(src, "变量名要是英文标识符，没动");
        }
        for (VarAt v : varsOf(src)) {
            if (v.name().equals(name)) return new Result(src, "已经有一个 var " + name + " 了，没动");
        }
        String code = "var " + name + visPart(vis)
                + " = " + (init == null || init.isBlank() ? "0" : init.strip());
        List<VarAt> all = varsOf(src);
        int ins = leadEnd(src);
        String line = code + "\n";
        if (!all.isEmpty()) {
            int le = lineEndAt(src, all.get(all.size() - 1).line());
            if (le < 0) le = src.length();
            boolean eof = le >= src.length();
            ins = eof ? le : le + 1;
            line = eof ? "\n" + code : line;
        }
        return checked(src, apply(src, List.of(new Fix(ins, ins, line))), "已新建变量：" + code);
    }

    /** 可见性那一段的写法（空 = 不写；不带头尾括号就补上 —— 括号里那种写法最不容易歧义）。 */
    private static String visPart(String vis) {
        if (vis == null || vis.isBlank()) return "";
        String v = vis.strip();
        return " @" + (v.startsWith("(") ? v : "(" + v + ")");
    }

    /** 改一条顶层变量的初值（只换那个初值片段的字符，两端空白与行尾注释都留着）。 */
    public static Result setVarInit(String src, String name, String init) {
        int[] r = varInitSpan(src, name);
        if (r == null) return new Result(src, "脚本顶层没有 var " + name + " 这一条，没动");
        String v = init == null ? "" : init.strip();
        if (v.isEmpty()) return new Result(src, "初值不能是空的，没动");
        return checked(src, apply(src, List.of(new Fix(r[0], r[1], v))),
                "已把 var " + name + " 的初值改成 " + brief(v));
    }

    /** 改一条顶层变量的可见性条件（空串 = 删掉那一段 = 人人可见）。 */
    public static Result setVarVis(String src, String name, String vis) {
        int[] r = varVisSpan(src, name);
        if (r == null) return new Result(src, "脚本顶层没有 var " + name + " 这一条，没动");
        String cond = vis == null ? "" : vis.strip();
        String part = cond.isEmpty() ? " "
                : " @" + (cond.startsWith("(") ? cond : "(" + cond + ")") + " ";
        return checked(src, apply(src, List.of(new Fix(r[0], r[1], part))),
                cond.isEmpty() ? "已把 var " + name + " 改成人人可见" : "已把 var " + name + " 的可见性改成 " + part.strip());
    }

    /** 变量名在源码里的字符区间（-1 找不到 = null）。 */
    private static int[] varNameSpan(String src, String name) {
        for (VarAt v : varsOf(src)) {
            if (!v.name().equals(name)) continue;
            int ls = lineStart(src, v.line());
            int at = src.indexOf(name, ls + 4);
            if (at >= 0) return new int[]{at, at + name.length()};
        }
        return null;
    }

    /** 那条变量声明的 {@code = } 到初值收尾之间的区间（只掐初值那几个字符）。 */
    private static int[] varInitSpan(String src, String name) {
        int[] nr = varNameSpan(src, name);
        if (nr == null) return null;
        int ln = lineOf(src, nr[0]);
        int ls = lineStart(src, ln);                                 // ⚠ lineStart 收**行号**（不是偏移）
        int le = lineEndAt(src, ln);
        String line = src.substring(ls, le);
        int eq = declEq(line, nr[0] - ls + name.length());
        if (eq < 0) return null;
        int a = ls + eq + 1, b = le;
        int cm = line.indexOf("//");
        if (cm >= 0 && ls + cm > a) b = ls + cm;
        while (a < b && Character.isWhitespace(src.charAt(a))) a++;
        while (b > a && Character.isWhitespace(src.charAt(b - 1))) b--;
        return new int[]{a, b};
    }

    /**
     * 那条变量声明的**可见性那一段**的区间：{@code [名字之后, 声明里那个 = 之前)}。
     *
     */
    private static int[] varVisSpan(String src, String name) {
        int[] nr = varNameSpan(src, name);
        if (nr == null) return null;
        int ln = lineOf(src, nr[0]);
        int ls = lineStart(src, ln);                                 // ⚠ lineStart 收**行号**（不是偏移）
        int le = lineEndAt(src, ln);
        String line = src.substring(ls, le);
        int eq = declEq(line, nr[1] - ls);
        if (eq < 0) return null;
        return new int[]{nr[1], ls + eq};
    }

    // ---------- 庚、世界侧生成器（段7：活动范围 / 标记点 → 脚本）----------
    //
    // 口径：**规则 = 脚本，编辑器只做生成器** —— 这里只负责把「选好的世界坐标」
    // 翻成一小段脚本文本（`area` 声明 / 进-出圈骨架 / 一格牌子），**不另立「规则」真源**。

    /** 某条顶层声明的值原文（如 {@code area} → {@code "96 60 96 160 120 160"}）；没有 = 空串。 */
    public static String declValue(String src, String kind) {
        int at = declLine(src, kind);
        if (at < 0) return "";
        String ls = src.substring(lineStart(src, at), lineEndAt(src, at)).strip();
        return ls.length() > kind.length() ? ls.substring(kind.length()).strip() : "";
    }

    /** 顶层事件块（{@code on world} / {@code on start} …）的头一行（1 起；没有 = -1）。 */
    public static int handlerLine(String src, String on) {
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (!t.startsWith("on " + on)) continue;
            char c = t.length() > on.length() + 3 ? t.charAt(on.length() + 3) : '{';
            if (!(c == '{' || Character.isWhitespace(c))) continue;       // 免得 on worldX 也中
            if (bodyRange(src, i + 1, 0) != null) return i + 1;
        }
        return -1;
    }

    /**
     * <b>段7：生成「进 / 出活动范围」的规则段</b> —— 已有 {@code on world} 就往它里面追加两个分支，
     * 没有就在脚本末尾新建一个。
     *
     */
    public static Result genAreaRule(String src) {
        return genAreaRule(src, "");
    }

    /**
     * 同上，但**按某一条区域**生成。
     *
     * <p>守卫多一半：{@code edge_area == "r1"} —— 内建值，报「跨过的是哪条区域」。
     * 不写这个守卫时，脚本在多条区域共存时会分不清是哪条的边沿（老的无参版保留给匿名活动范围）。
     */
    public static Result genAreaRule(String src, String region) {
        String guard = region == null || region.isEmpty() ? "" : " && edge_area == \"" + region + "\"";
        String body = "if (edge == 1" + guard + ") {" + "\n" + "}" + "\n" + "if (edge == -1" + guard + ") {" + "\n" + "}";
        int h = handlerLine(src, "world");
        if (h >= 0) {
            if (src.contains(region == null || region.isEmpty() ? "edge == 1" : "edge_area == \"" + region + "\"")) {
                return new Result(src, "脚本里已经有「进出范围」的分支了（on world 里），没动");
            }
            Result r = addStmt(src, h, 0, 999, body);
            return r.text().equals(src) ? r : new Result(r.text(),
                    "已在现有的 on world 里追加「进 / 出范围」两个分支（往里加卡即可）");
        }
        String head = "// 进出" + (region == null || region.isEmpty() ? "「活动范围」" : "区域「" + region + "」")
                + "的规则（区域编辑页生成）：要办事就在下面的分支里加卡" + "\n"
                + "on world {\n  if (edge == 1) {\n  }\n  if (edge == -1) {\n  }\n}";
        return appendTop(src, head, "已新建 on world 与「进 / 出范围」两个分支（往里加卡即可）");
    }

    /**
     * 这个**入口**里有没有「{@code if (守卫) {」这条分支。
     *
     *
     * <p>实现取巧：从入口那行的第一个 {@code {} 起切到源码尾再找子串 —— 够用（体后面只可能是
     * 别的顶层声明，不会有同守卫分支；会撞的只有同族候选，而它们各自在自己入口里）。
     */
    public static boolean guardIn(String src, String on, String guard) {
        int h = handlerLine(src, on);
        if (h < 0) return false;
        int[] body = bodyRange(src, h, 0);                               // 入口块的**真实区间**（配对花括号）
        // ⚠ 必须用区间而不是「切到源码尾」：入口后面还有别的顶层块（on break / on place / area…），
        //  切到尾 ⇒ 别块里的同守卫分支也算「有了」。
        return body != null
                && src.substring(body[0], body[1]).contains("if (" + guard + ") {");
    }

    public static Result genBlockTrigger(String src, String on, String guard, String label) {
        // 幂等判据：**在这个入口里**找 —— 方块类候选守卫天生会撞（踩到/破坏都是 block == …），
        // 全脚本找子串 ⇒ 加「破坏」时「踩到」被误判成已存在 ⇒「脚本已有…」加不出来。
        if (guardIn(src, on, guard)) {
            return new Result(src, "on " + on + " 里已经有「" + guard + "」这条了，没动");
        }
        String body = "if (" + guard + ") {" + "\n" + "}";
        int h = handlerLine(src, on);
        if (h >= 0) {
            Result r = addStmt(src, h, 0, 999, body);
            return r.text().equals(src) ? r : new Result(r.text(),
                    "已在现有的 on " + on + " 里追加一条「" + label + "」的分支（往里加卡即可）");
        }
        String head = "// " + label + "的规则（对象页生成）：要办事就在下面的分支里加卡" + "\n"
                + "on " + on + " {\n  if (" + guard + ") {\n  }\n}";
        return appendTop(src, head, "已新建 on " + on + " 与「" + label + "」的分支（往里加卡即可）");
    }

    /** 一条事件：入口（{@code on} 写哪个）· 守卫原文 · 给人看的标签。 */
    public record Event(String on, String guard, String label) { }

    /**
     * 这一类的**候选事件**（顺序 = 菜单顺序；编辑页【＋事件】取第 0 条当默认）—— 「对象专属触发」的**唯一真源**：
     * {@link #eventOf}（认已有）· 编辑页【＋事件】· 停用的那套菜单都吃它 ⇒ 加一种触发只改这一处。
     * @param kind 对象类别（「实体」/「方块」按各自那套；其它按物品那套）
     */
    public static java.util.List<Event> eventCandidates(String kind, String base, String id) {
        java.util.List<Event> out = new java.util.ArrayList<>();
        if ("画面组件".equals(kind)) {
            // 实体画面的组件：点它 = 引擎走现成的**点选通道**，守卫就是它自己那个 mark/名
            out.add(new Event("pick", "pick == " + strCode(id), "点它（右键世界里的这个组件）"));
        } else if ("实体".equals(kind)) {
            String g = "eid == " + strCode(id);
            out.add(new Event("entity", g + " && edead == 0", "右键「" + id + "」"));
            out.add(new Event("entity", g + " && edead == 1", "打死「" + id + "」"));
        } else if ("方块".equals(kind)) {
            String g = "block == " + strCode(base);
            out.add(new Event("world", g, "踩到「" + id + "」"));
            out.add(new Event("look", "look_block == " + strCode(base), "看向「" + id + "」"));
            out.add(new Event("world", g + " && rclick == 1", "右键「" + id + "」"));
            //  新入口：破坏 / 放置（on break / on place 各自是独立入口，守卫里不用再写 block —— 内建值就是它）
            out.add(new Event("break", "block == " + strCode(base), "破坏「" + id + "」"));
            out.add(new Event("place", "block == " + strCode(base), "放置「" + id + "」"));
        } else {
            String g = "hand_asset == " + strCode(id);
            out.add(new Event("world", g + " && rclick == 1", "用「" + id + "」"));
            out.add(new Event("world", g + " && rclick == 1 && sneak == 1", "潜行用「" + id + "」"));
        }
        return out;
    }

    /**
     * 这一条对象**已经有的事件**（同一类触发只能有一个 {@code on X} 块，多个事件 = 块里并列的多条 {@code if (守卫) { }}）。
     * 口径与 {@link #genBlockTrigger} 的幂等判据同一套（带壳找，所以「踩上」不会撞到「右键」那条的子串）。空表 = 还没有事件。
     */
    public static java.util.List<Event> eventsOf(String src, String kind, String base, String id) {
        java.util.List<Event> out = new java.util.ArrayList<>();
        if (src == null) {
            return out;
        }
        for (Event e : eventCandidates(kind, base, id)) {
            // **守卫匹配一律「对入口」**（guardIn 一处口径）——
            // 方块类候选守卫天生会撞（踩到/破坏都是 `block == …`），全脚本找 ⇒ 加破坏后「踩到」被误显示已配。
            if (guardIn(src, e.on(), e.guard())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 刷怪蛋的物品 id → 它对应的**原版实体 id**（{@code minecraft:zombie_spawn_egg} → {@code minecraft:zombie}）。
     * 认不出给**空串**（没这个后缀 / 不是带命名空间的标准 id）—— 调用方提示换一个，不静默建假对象。
     */
    public static String entityIdOfEgg(String eggId) {
        String tail = "_spawn_egg";
        if (eggId == null || !eggId.endsWith(tail)) {
            return "";
        }
        String id = eggId.substring(0, eggId.length() - tail.length());
        return id.indexOf(':') < 0 ? "" : id;
    }

    /**
     * <b>段7：标记点</b> —— 往 {@code on start} 里追加一行牌子（{@code label(x, y, z, "名字")}），
     * 开局就把这格的记号立起来（世界里看得见）。没有 {@code on start} 就在末尾新建一个。
     *
     */
    public static Result addAreaMark(String src, int x, int y, int z, String name) {
        String nm = name == null || name.isBlank() ? "标记点" : name.strip();
        String code = "label(" + x + ", " + y + ", " + z + ", \"" + nm.replace("\\", "").replace("\"", "") + "\")";
        int h = handlerLine(src, "start");
        if (h >= 0) {
            Result r = addStmt(src, h, 0, 999, code);
            return r.text().equals(src) ? r : new Result(r.text(), "已在 on start 末尾加一行牌子：" + nm);
        }
        return appendTop(src, "on start {\n  " + code + "\n}", "已新建 on start 并加一行牌子：" + nm);
    }

    // ---------- 丙、区域属性：一条属性 = **生成一段脚本代码** + 一行注释标记 ----------
    // 口径：区域 = 坐标盒、规则写在脚本里 —— 界面只是「生成器 + 另一层视图」，不另存属性真源。
    // 生成：写普通代码（作者随时能手改），开头一行注释标记（属性名 / 区域名 / 值都在标记里）；反查**只认标记**，不猜代码。
    // 改值 = 删旧段 + 建新段；标记丢了 = 界面不再列它（那段代码照样跑 —— 界面丢了它，能力没丢）。

    /** 区域属性的键 → 人话名（生成标记与界面列行都用它；一条属性只有这一处定义）。 */
    private static final java.util.Map<String, String> ATTR_CN = java.util.Map.ofEntries(
            java.util.Map.entry("protect", "区域保护"),
            java.util.Map.entry("enter_rule", "进圈规则"),
            java.util.Map.entry("exit_rule", "出圈规则"),
            // ⚠ 下面四条**只留在表里、不在 attrKeys 里**（切界面 / 改模式这些**事件**
            // 都在**节点编辑页**里加，属性只提供「条件」）。留着是为了**老档里已经生成的那几段仍认得出、删得掉**。
            java.util.Map.entry("enter_show", "进圈切界面"),
            java.util.Map.entry("enter_mode", "进圈切模式"),
            java.util.Map.entry("exit_show", "出圈切界面"),
            java.util.Map.entry("exit_mode", "出圈切模式"));

    /**
     * 区域属性可选的键（界面按这个顺序列候选）。
     *
     */
    public static java.util.List<String> attrKeys() {
        return java.util.List.of("protect", "enter_rule", "exit_rule");
    }

    /** 属性的中文名（界面显示用）。 */
    public static String attrCn(String key) {
        return ATTR_CN.getOrDefault(key, key);
    }

    /** 一条区域属性（反查出来的）：键 / 人话名 / 值（没值 = 空串）/ 标记所在行（1 起）。 */
    public record AreaAttr(String key, String cn, String value, int line) { }

    /** 标记行的正文（值写在同一行 —— 反查只读它）。 */
    private static String attrMark(String key, String region, String value) {
        return "// 区域属性：" + attrCn(key) + "（" + region + "）"
                + (value == null || value.isEmpty() ? "" : " = " + value);
    }

    /**
     * 反查：这条区域在脚本里配了哪些属性（按源码顺序）。
     *
     * <p>只认 {@link #attrMark} 那种注释标记 —— 手改过的段只要标记还在就仍列得出来；
     * 标记被删掉就不再列（**界面不再认它，代码照样跑**）。
     */
    public static java.util.List<AreaAttr> areaAttrsOf(String src, String region) {
        java.util.List<AreaAttr> out = new java.util.ArrayList<>();
        if (region == null || region.isEmpty()) return out;
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (!t.startsWith("// 区域属性：")) continue;
            String body = t.substring("// 区域属性：".length());
            int p = body.lastIndexOf("（" + region + "）");          // 区域名对不上 = 别人的属性
            if (p < 0) continue;
            String cn = body.substring(0, p);
            String tail = body.substring(p + region.length() + 2).strip();
            String value = tail.startsWith("=") ? tail.substring(1).strip() : "";
            for (String key : ATTR_CN.keySet()) {
                if (!ATTR_CN.get(key).equals(cn)) continue;
                boolean dup = false;                    // 一条属性可能有**两处**标记（如切模式的顶层槽位 + 分支）
                for (AreaAttr a : out) {
                    if (a.key().equals(key)) { dup = true; break; }     // 界面按「一条属性一行」列 ⇒ 这里就去重
                }
                if (!dup) out.add(new AreaAttr(key, cn, value, i + 1));
                break;
            }
        }
        // 第二遍：**按代码形状认** ——
        // 手写的 / 老档留下来的 / 标记被删掉的段，照样列得出来；标记那一路先扫（值写在标记里更准），
        // 这一路只补它没列过的键。盒要跟声明对上（差一格 = 别的区域的保护）。
        java.util.List<String> order = new java.util.ArrayList<>(attrKeys());   // 顺序固定：保护 → 进圈 → 出圈
        order.addAll(ATTR_CN.keySet());                     // 退场的 key 只会来自标记（第二遍对它们恒空）
        for (String key : order) {
            java.util.List<Integer> hitLn = attrHitLines(src, region, key);
            if (hitLn.isEmpty()) continue;
            String v = "";
            if (key.equals("protect")) {
                long[] box = protectCall(lines[hitLn.get(0) - 1].strip());
                v = box != null && box[6] >= 1 && box[6] <= 3 ? String.valueOf(box[6]) : "";
            }
            putAttrIfNew(out, key, v, hitLn.get(0));
        }
        return out;
    }

    /**
     * 这条属性在脚本里占的**行区间**（每个段一个 {@code {起行, 止行}}，1 起、闭区间）——
     * 区域属性行点【编辑】跳节点页时用它做「只看这一条」的过滤。
     */
    public static java.util.List<int[]> attrSpans(String src, String region, String key) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int mark : attrHitLines(src, region, key)) {      // 标记行 + 代码形状扫到的行
            int[] sp = attrSpanOf(src, region, key, mark);
            if (sp == null) continue;
            int lastLine = lineOf(src, Math.max(sp[0], sp[1] - 1));          // 段末所在行（段的右端是行首）
            out.add(new int[] { mark, Math.max(mark, lastLine) });
        }
        return out;
    }

    /**
     * 这条属性的**全部行**：标记行（界面生成的）**加**按代码形状扫到的行 ——
     * 标记只是「这段是界面生成的」的记号，**真源永远是代码**。
     *
     * <p>删/跳转都用这一份 ⇒ 手写的段也删得掉、也跳得进。
     */
    private static java.util.List<Integer> attrHitLines(String src, String region, String key) {
        java.util.List<Integer> hits = new java.util.ArrayList<>(attrMarkLines(src, region, key));
        String[] lines = splitLines(src);
        Interp.Region reg = regionDecl(src, region);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            boolean hit = switch (key) {
                case "protect" -> boxMatches(protectCall(t), reg);
                case "enter_rule" -> edgeGuard(t, region, 1);
                case "exit_rule" -> edgeGuard(t, region, -1);
                default -> false;                  // 退场的 key（切界面 / 切模式）：只认标记
            };
            if (hit && !hits.contains(i + 1) && !insideAttrMark(src, lines, i + 1)) hits.add(i + 1);
        }
        java.util.Collections.sort(hits);
        return hits;
    }

    /**
     * 这一行是不是**已经落在某条属性标记的段里**（那种段归标记那一路认，别再按代码形状认一遍）：
     * 老档的「进圈切模式」段里恰好也有 {@code edge == 1 && edge_area == "…"} 那个守卫，不排掉就会
     * **同一条段被列成两行**（进圈切模式 + 进圈规则），而且删一个会把另一个一起带走。
     */
    private static boolean insideAttrMark(String src, String[] lines, int line) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].strip().startsWith("// 区域属性：")) continue;
            int[] sp = attrSpan(src, i + 1);
            if (sp == null) continue;
            int from = lineOf(src, sp[0]);
            int to = lineOf(src, Math.max(sp[0], sp[1] - 1));
            if (line >= from && line <= to) return true;
        }
        return false;
    }

    /** 这一行是不是「本区域的进 / 出圈守卫」：{@code if (edge == ±1 && edge_area == "区域")}。 */
    private static boolean edgeGuard(String line, String region, int edge) {
        return line.startsWith("if (") && line.contains("edge_area == \"" + region + "\"")
                && line.contains("edge == " + edge);
    }

    /** 这一行是不是 {@code protect(盒[, 级别])}：是就给 7 个数（盒 6 位 + 级别；没写级别 = 0）。 */
    private static long[] protectCall(String line) {
        if (line == null || !line.startsWith("protect(")) return null;
        int e = line.indexOf(')');
        if (e < 0) return null;
        String[] p = line.substring("protect(".length(), e).split(",");
        if (p.length != 6 && p.length != 7) return null;
        long[] v = new long[7];
        for (int i = 0; i < p.length; i++) {
            try {
                v[i] = Long.parseLong(p[i].strip());
            } catch (NumberFormatException ex) {
                return null;                       // 变量 / 算式算的盒：认不出就不列（不猜）
            }
        }
        return v;
    }

    /** 这个 protect 的盒是不是这条区域声明的盒（差一格就不算 —— 那是别的区域的保护）。 */
    private static boolean boxMatches(long[] box, Interp.Region reg) {
        return box != null && reg != null
                && box[0] == (long) reg.minX() && box[1] == (long) reg.minY() && box[2] == (long) reg.minZ()
                && box[3] == (long) reg.maxX() && box[4] == (long) reg.maxY() && box[5] == (long) reg.maxZ();
    }

    /** 这条区域在脚本里的声明（解析不过 / 没这条 = null）。 */
    private static Interp.Region regionDecl(String src, String region) {
        for (Interp.Region r : regionsOf(src)) {
            if (r.name().equals(region)) return r;
        }
        return null;
    }

    /** 没列过这个键才加（同一条属性可能既带标记又在代码里被扫到 ⇒ 界面只该有一行）。 */
    private static void putAttrIfNew(java.util.List<AreaAttr> out, String key, String value, int line) {
        for (AreaAttr a : out) {
            if (a.key().equals(key)) return;               // 标记那一路先扫 ⇒ 值（级别）以它为准
        }
        out.add(new AreaAttr(key, attrCn(key), value, line));
    }

    /** 这条属性在脚本里的**全部**标记行（可能不止一处 —— 切模式那种要一个槽位加一个分支，两处都得删）。 */
    private static java.util.List<Integer> attrMarkLines(String src, String region, String key) {
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        String head = "// 区域属性：" + attrCn(key) + "（" + region + "）";
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().startsWith(head)) hits.add(i + 1);
        }
        return hits;
    }

    /** 一行属性的区间：**标记段**走「标记 + 缩进」（生成物那种），**手写的**走括号配平。 */
    private static int[] attrSpanOf(String src, String region, String key, int line) {
        return attrMarkLines(src, region, key).contains(line) ? attrSpan(src, line) : codeSpan(src, line);
    }

    /**
     * 手写那一行占的区间（字符偏移 { 起, 止 }）：这一行有块（{@code if (…) { … }}）就**按括号配平**到它
     * 那个 {@code }}；没块（{@code protect(…)}）就是这一行。
     *
     * <p>⚠ **别按缩进**（那是标记段那套口径）：手写的两个 {@code if} 之间可能没有空行、缩进又一样 ⇒
     * 按缩进会把下一个块一起吃掉（删「进圈规则」把「出圈规则」一起删了）。
     */
    private static int[] codeSpan(String src, int line) {
        int start = lineStart(src, line);
        if (start < 0) return null;
        int[] body = bodyRange(src, line);                     // 有 `{` 就配平到它那个 `}`（无块 / 不配平 = null）
        int nl = src.indexOf('\n', body != null ? body[1] + 1 : start);
        return new int[] { start, nl < 0 ? src.length() : nl + 1 };
    }

    private static int[] attrSpan(String src, int markLine) {
        int start = lineStart(src, markLine);
        if (start < 0) return null;
        String[] lines = splitLines(src);
        int markIndent = indentOf(lines[markLine - 1]).length();   // 现成的 indentOf 给的是缩进串
        int last = markLine;
        for (int L = markLine + 1; L <= lines.length; L++) {
            String t = lines[L - 1];
            String st = t.strip();
            if (st.isEmpty() || st.startsWith("// 区域属性：")) break;      // 空行 / 下一个属性段
            if (indentOf(t).length() < markIndent) break;                    // 缩进更浅 = 段外（事件块的收尾 }）
            last = L;
        }
        int end = last < lines.length ? lineStart(src, last + 1) : src.length();
        return new int[] { start, end };
    }


    /**
     * 删一条区域属性（连带它那一段代码；可能有两段 —— 进圈切模式那种要一个顶层 var 加一个分支）。
     *
     * <p>从后往前删，免得前面的偏移把后面的算错。
     */
    public static Result removeAreaAttr(String src, String region, String key) {
        java.util.List<Integer> hits = attrHitLines(src, region, key);   // ⚠ 要**全部**行：标记 + 代码形状都算
        if (hits.isEmpty()) return new Result(src, "这条区域没有配「" + attrCn(key) + "」，没动");
        java.util.Collections.sort(hits);
        String t = src;
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] sp = attrSpanOf(t, region, key, hits.get(i));
            if (sp == null) continue;
            t = apply(t, List.of(new Fix(sp[0], sp[1], "")));
        }
        t = t.replaceAll("\n{3,}", "\n\n");                       // 删完不留一坨空行
        return checked(src, t, "已删掉区域「" + region + "」的「" + attrCn(key) + "」");
    }

    /**
     * 生成一条区域属性（改值 = 先删旧的再建，所以段会挪位置）。
     *
     * @param value 属性的值：{@code protect} = 级别（"1"/"2"/"3"，三级）· {@code enter_show} = 画布名 ·
     *  {@code enter_mode} = 模式名（survival/creative/adventure/spectator）· {@code exit_rule} 不要值
     */
    public static Result genAreaAttr(String src, String region, String key, String value) {
        if (region == null || region.isEmpty()) return new Result(src, "匿名区域配不了属性（先在 3D 视窗里框选一次给它起个名字）");
        String v = value == null ? "" : value.strip();
        if (!ATTR_CN.containsKey(key)) return new Result(src, "认不出的属性：" + key + "，没动");
        Interp.Region reg = null;
        for (Interp.Region r : regionsOf(src)) {          // 现成的：解析不过 = 空表
            if (r.name().equals(region)) reg = r;
        }
        if (reg == null) return new Result(src, "脚本里没有区域 " + region + " 的声明，没动");

        String t = src;
        java.util.List<AreaAttr> had = areaAttrsOf(src, region);
        for (AreaAttr a : had) {
            if (a.key().equals(key)) t = removeAreaAttr(t, region, key).text();
        }
        String mark = attrMark(key, region, v);
        Result r;
        switch (key) {
            case "protect" -> {
                long x1 = (long) reg.minX(), y1 = (long) reg.minY(), z1 = (long) reg.minZ();
                long x2 = (long) reg.maxX(), y2 = (long) reg.maxY(), z2 = (long) reg.maxZ();
                // 级别：值放在**标记**里（界面读标记显示「区域保护 · 三级」），代码里
                // **总是写出来**（手写的缺省是二级，但生成物写明白更省心 —— 一眼能看出这行是几级）。
                String lv = switch (v) {
                    case "1", "2", "3" -> v;
                    default -> "2";                       // 没选 / 老值 = 二级
                };
                String cn = lv.equals("1") ? "一级·只拦生存"
                        : lv.equals("3") ? "三级·连创造也挡" : "二级·拦生存+冒险";
                String code = mark + "\n" + "  protect(" + x1 + ", " + y1 + ", " + z1 + ", "
                        + x2 + ", " + y2 + ", " + z2 + ", " + lv + ")\n";
                // ⚠ 放 **on reload** 而不是 on start：保护登记在**宿主**，
                // 而热替换与「读存档接回来」都不重跑 on start ⇒ 放 on start 的会「重启/热替换后就没了」。
                r = insertRunner(t, "reload", code, "脚本生效时登记保护（" + cn + "）");
            }
            case "enter_rule", "exit_rule" -> {
                // 属性 = **条件**：只写那个空壳条件块，里面的事件在**节点编辑页**里加。
                // 空壳本身也是有用的东西 —— 作者知道自己要在哪写，界面也把这一段的范围记得住。
                String edge = key.equals("enter_rule") ? "1" : "-1";
                String code = mark + "\n"
                        + "  if (edge == " + edge + " && edge_area == \"" + region + "\") {\n  }\n";
                r = insertRunner(t, "world", code, attrCn(key) + "（空壳 —— 点【编辑】进节点页往里加事件）");
            }
            default -> {
                return new Result(src, "认不出的属性：" + key + "，没动");
            }
        }
        return r;
    }

    /** 插进某个事件入口（{@code on start} / {@code on world}）：有就加到它末尾，没有就在脚本末尾新建。 */
    private static Result insertRunner(String src, String on, String code, String what) {
        int h = handlerLine(src, on);
        if (h >= 0) {
            String block = code.endsWith("\n") ? code : code + "\n";     // 段末留一个空行 ⇒ 反查/删除有硬边界
            Result r = addStmt(src, h, 0, 999, block);
            return r.text().equals(src) ? r : new Result(r.text(), "已在 on " + on + " 里加一段：" + what);
        }
        return appendTop(src, "on " + on + " {\n" + code + "}", "已新建 on " + on + " 并加一段：" + what);
    }

    /**
     * 往脚本**末尾**追加一段顶层文本（前面补一个空行分隔）。
     * 顶层的东西只有：声明 / 事件块 —— 所以调用方给的 {@code code} 必须是完整的一块。
     */
    public static Result appendTop(String src, String code, String note) {
        if (code == null || code.isBlank()) return new Result(src, "要追加的内容是空的，没动");
        int at = src.length();
        String tail = src.endsWith("\n") || src.isEmpty() ? "" : "\n";
        return checked(src, apply(src, List.of(new Fix(at, at, tail + "\n" + code + "\n"))), note);
    }

    /** 某条单值顶层声明在第几行（1 起；没有 = -1）。 */
    private static int declLine(String src, String kind) {
        String[] lines = splitLines(src);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (!(t.startsWith(kind) && t.length() > kind.length()
                    && Character.isWhitespace(t.charAt(kind.length())))) continue;
            // ⚠ `area` 现在还能写成**具名区域**（`area r1 { … }` / `area r1 1 2 3 4 5 6`）。
            // 这一节（declValue / setDecl / removeDecl）认的是**那条匿名的「活动范围」**（编辑器【世界】页写的就是它），
            // 具名 / 块式的行一律跳过 —— 不然 setDecl 会把 `area r1 {` 整行换成六个数字，
            // 区域名与它整段花括号体一起被毁掉（脚本当场坏掉，而且是静默的）。
            if (kind.equals("area")) {
                char c = t.charAt(kind.length() + 1);            // `area` 之后的第一个非空白字符
                if (Character.isLetter(c) || c == '_' || t.endsWith("{")) continue;
            }
            return i + 1;
        }
        return -1;
    }

    /** 文件开头「注释 + 空行」那一段之后的偏移量（新声明插这儿，不会插到档头注释上面去）。 */
    private static int leadEnd(String src) {
        int i = 0;
        while (i < src.length()) {
            int e = lineEnd(src, i);
            String t = src.substring(i, e).strip();
            if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
                    && !t.endsWith("*/")) break;
            if (e >= src.length()) return src.length();
            i = e + 1;
        }
        return i;
    }

    // ---------- 戊、动词表（段3 的「＋新增动作卡」搜索清单：动词 → 参数位 → 控件 → 候选来源）----------

    /** 控件类型（三态 + 容器头部的只读）。 */
    public static final String CTL_NUM = "数字", CTL_TEXT = "文本", CTL_PICK = "候选", CTL_READ = "只读";

    /** 候选来源（{@link #candidates} 只认前四种能从**脚本文本**里算出来的；后四种的候选来自别处）。 */
    public static final String SRC_NONE = "", SRC_STAGE = "stage", SRC_SCREEN = "screen", SRC_PART = "part",
            SRC_FUNC = "func", SRC_VAR = "var",
            SRC_SEAT = "seat",          // 谁：名单是脚本自己的数组 → 候选只有内建值名 + all/others（其余手打）
            SRC_BLOCK = "block",        // 方块 id：候选来自组件库 / 原版注册表（不在脚本里）
            SRC_ITEM = "item",          // 物品 id：同上
            SRC_PIECE = "piece",        // 棋子 id：候选来自游戏档 pieces 段
            SRC_ENTITY = "entity",      // 实体对象：候选来自项目档 assets 段（kind = 自定义实体，期4）
            SRC_GAMEMODE = "gamemode",  // 游戏模式名：原版固定那几个
            SRC_SOUND = "sound_event";  // 音效 id（期13）：候选 = 原版音效注册表（IdPickScreen 已有这一档）

    /** 动作卡的一个参数位：名字 · 控件类型 · 候选来源。 */
    public record Param(String name, String control, String source) { }

    /** 一张动作卡：动词 · 清单上显示的名字 · 参数位（顺序 = 卡上的排列）。 */
    public record Card(String verb, String label, List<Param> params) { }

    private static Param num(String n) { return new Param(n, CTL_NUM, SRC_NONE); }

    private static Param text(String n) { return new Param(n, CTL_TEXT, SRC_NONE); }

    /**
     * 这个参数位的候选值要写成**字符串字面量**吗（带引号）。
     *
     */
    public static boolean quoted(String source) {
        return !(SRC_SEAT.equals(source) || SRC_VAR.equals(source)
                || SRC_STAGE.equals(source) || SRC_FUNC.equals(source));
    }

    /** 按上面那条规则把候选值包成**源码片段**；已经带引号的原样返回（编辑已有卡时读回的就是成品）。
     *
     * <p>⚠ 名字叫 {@code asLiteral} 而不是 {@code literal} —— 那边已经有个 {@code literal(String,int,int,int)}
     * （文本页那套），同名重载看调用点容易看错。 */
    public static String asLiteral(String source, String value) {
        if (value == null) return "";
        String v = value.strip();
        if (v.isEmpty() || !quoted(source) || v.startsWith("\"")) return v;
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    private static Param pick(String n, String src) { return new Param(n, CTL_PICK, src); }

    private static Param read(String n) { return new Param(n, CTL_READ, SRC_NONE); }

    /**
     * 动词表（纯数据，零 UI —— 段3 的搜索清单与卡上控件照它摆）。
     *
     * <p>只列**能当一张卡插进块里的语句**：赋值 / 控制流 / 舞台与世界的原语。
     * 画语句（{@code box} / {@code text} …）属「界面」页那条线，不在这里；
     * 读类内建（{@code len} / {@code random} …）是参数位里的表达式，也不是卡。
     */
    public static final List<Card> CARDS = List.of(
            new Card("=", "赋值：把值存进变量", List.of(pick("变量", SRC_VAR), text("值"))),
            new Card("say", "说话：发给谁看", List.of(pick("谁", SRC_SEAT), text("文本"))),
            new Card("sound", "放音效（原版音效库；只给这个人听）",
                    List.of(pick("谁", SRC_SEAT), pick("音效", SRC_SOUND), num("音量"), num("音高"))),
            new Card("goto", "换阶段：跳到某个阶段", List.of(pick("阶段", SRC_STAGE))),
            new Card("wait", "等几秒再往下", List.of(num("秒数"))),
            new Card("timer", "阶段倒计时（到点走 on timeout）", List.of(num("秒数"))),
            new Card("show", "切舞台：现在显示哪一块", List.of(pick("画布", SRC_SCREEN))),
            new Card("hide", "收起舞台", List.of()),
            new Card("end", "结束这一局", List.of()),
            new Card("return", "函数返回", List.of(text("值"))),
            new Card("break", "跳出循环", List.of()),
            new Card("continue", "下一轮循环", List.of()),
            new Card("place_piece", "摆棋子（生成或挪过去）", List.of(pick("棋子 id", SRC_PIECE), num("x"), num("y"), num("z"))),
            new Card("spawn_mob", "放原版生物（生成或挪过去）", List.of(pick("实体对象", SRC_ENTITY), num("x"), num("y"), num("z"))),
            new Card("set_block", "改一格方块", List.of(num("x"), num("y"), num("z"), pick("方块 id", SRC_BLOCK))),
            new Card("fill", "铺一片方块", List.of(num("x1"), num("y1"), num("z1"), num("x2"), num("y2"), num("z2"),
                    pick("方块 id", SRC_BLOCK))),
            new Card("label", "立个牌子写字", List.of(num("x"), num("y"), num("z"), text("文字"))),
            new Card("chest_set", "设置容器第几格", List.of(num("x"), num("y"), num("z"), num("槽"), pick("物品 id", SRC_ITEM), num("数量"))),
            new Card("bag_set", "设置背包第几格", List.of(pick("谁", SRC_SEAT), num("槽"), pick("物品 id", SRC_ITEM), num("数量"))),
            // give（补进表 —— 原语 就有，卡片表当时**漏收**了，界面上加不出来）：
            // 与 bag_set 的分工是**加 vs 设成**：bag_set 管「第几格放什么」，give 只管「给他这些东西」
            //（并进同款堆 → 找空格 → 塞不完掉脚下，原版 /give 的路子）。物品参数同一套候选（项目里的物品对象）。
            new Card("give", "给东西（并进背包，塞不下掉脚下）",
                    List.of(pick("谁", SRC_SEAT), pick("物品 id", SRC_ITEM), num("数量"))),
            new Card("protect", "保护这片方块（挖不掉）", List.of(num("x1"), num("y1"), num("z1"), num("x2"), num("y2"), num("z2"))),
            new Card("unprotect", "解除保护", List.of(num("x1"), num("y1"), num("z1"), num("x2"), num("y2"), num("z2"))),
            new Card("gamemode", "改游戏模式", List.of(pick("谁", SRC_SEAT), pick("模式", SRC_GAMEMODE))),
            new Card("enter", "拉他进局（点名，不看位置）", List.of(pick("谁", SRC_SEAT))),
            new Card("leave", "让他退局（点名；掉线不退局，要退就显式 leave）", List.of(pick("谁", SRC_SEAT))),
            new Card("tp", "传送", List.of(pick("谁", SRC_SEAT), num("x"), num("y"), num("z"))),
            new Card("profile_set", "记一份跨局数据", List.of(text("名字"), text("键"), text("值"))),
            new Card("clear_board", "清空画板", List.of()),
            new Card("may_draw", "谁能画（画板）", List.of(pick("谁", SRC_SEAT))),
            new Card("if", "如果…（容器卡：条件只在文本里改）", List.of(read("条件"))),
            new Card("while", "只要…就重复（容器卡）", List.of(read("条件"))),
            new Card("for", "重复 N 次（容器卡）", List.of(read("起始"), read("条件"), read("每轮"))));

    /**
     * 这份脚本里**声明过的对象名**（资产名 —— 候选只出资产名）。
     *
     */
    public static java.util.Set<String> declaredNames(String src) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (Ast.AssetDecl d : Parser.parse(src == null ? "" : src).assetDecls()) {
                out.add(d.name());
            }
        } catch (RuntimeException ignored) {
            // 脚本半成品：当没有声明（候选就只剩从脚本文本能算出来的那几种）—— 别炸屏
        }
        return out;
    }

    /**
     * 候选表：从**脚本文本**里现算已知对象（段2 / 段3 的「候选▾」填它）。
     * 认不出 / 算不出来的来源返回空表（{@link #SRC_BLOCK} 这些本来就不在脚本里 —— 候选来自组件库 / 游戏档）。
     */
    public static List<String> candidates(String src, String source) {
        List<String> out = new ArrayList<>();
        if (SRC_GAMEMODE.equals(source)) {
            // 游戏模式名是**原版固定的四个**（不在脚本里找对象）。不特判的话候选恒空 ——
            // 表现就是卡片上加不出「改游戏模式」。
            return List.of("survival", "creative", "adventure", "spectator");
        }
        if (source == null || source.isEmpty() || src == null) return out;
        List<Tok> ts = toks(src);
        for (int i = 0; i + 1 < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str()) continue;
            int ni = i + 1;
            String kw = t.text();
            if (kw.equals("on")) {                                        // on stage 名
                if (ni + 1 >= ts.size() || !ts.get(ni).text().equals("stage")) continue;
                ni = ni + 1;
                if (!SRC_STAGE.equals(source)) continue;
            } else if (kw.equals("goto")) {
                if (!SRC_STAGE.equals(source)) continue;
            } else if (kw.equals("screen")) {
                if (!SRC_SCREEN.equals(source)) continue;
            } else if (kw.equals("part")) {
                if (!SRC_PART.equals(source)) continue;
            } else if (kw.equals("func")) {
                if (!SRC_FUNC.equals(source)) continue;
            } else if (kw.equals("var")) {
                if (!SRC_VAR.equals(source)) continue;
            } else {
                continue;
            }
            Tok n = ts.get(ni);
            if (n.str() || n.text().isEmpty() || !Character.isLetter(n.text().charAt(0))) continue;
            if (Lexer.KEYWORDS.contains(n.text())) continue;
            if (!out.contains(n.text())) out.add(n.text());
        }
        if (SRC_SEAT.equals(source)) {                                    // 「谁」还能写这几样（手打别的也行）
            for (String v : Builtins.VALUES) if (!out.contains(v)) out.add(v);
            for (String v : List.of("all", "others")) if (!out.contains(v)) out.add(v);
        }
        return List.copyOf(out);
    }

    // ---------- 己、段0 的小工具 ----------

    /** 一个块（头行 1 起）的**块体字符区间** {@code {体起, 体止}}：{@code { 之后} 到配对的 {@code } }（定位不到 = null）。 */
    private static int[] bodyRange(String src, int blockLine) {
        return bodyRange(src, blockLine, 0);
    }

    /**
     * 那一行上**第 {@code nth} 个** {@code {} 的配对区间（0 起；找不到 = null）。
     *
     */
    static int[] bodyRange(String src, int blockLine, int nth) {
        int ls = lineStart(src, blockLine);
        if (ls < 0) return null;
        int le = lineEnd(src, ls);
        int open = -1;
        int seen = 0;
        for (int i = ls; i < le; i++) {
            char c = src.charAt(i);
            if (c == '"') { i = Math.max(i, skipStr(src, i)); continue; }
            if (c == '/' && i + 1 < le && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) break;
            if (c == '{') {
                if (seen == Math.max(0, nth)) { open = i; break; }
                seen++;
            }
        }
        if (open < 0) return null;
        int close = matchBrace(src, open);
        return close < 0 ? null : new int[]{open + 1, close};
    }

    /** {@code open} 处那个 {@code {} 的配对 {@code }} 在哪（字符串 / 注释里的括号不算；-1 = 不配对）。 */
    private static int matchBrace(String src, int open) {
        int d = 0, i = open;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"') {
                int e = skipStr(src, i);
                i = e <= i ? src.length() : e + 1;
                continue;
            }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '/') {
                    int e = src.indexOf('\n', i);
                    i = e < 0 ? src.length() : e;
                    continue;
                }
                if (n == '*') {
                    int e = src.indexOf("*/", i + 2);
                    i = e < 0 ? src.length() : e + 2;
                    continue;
                }
            }
            if (c == '{') d++;
            else if (c == '}') {
                d--;
                if (d == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * 把块体 {@code [from, to)} 切成**直接子语句**的字符区间（不含行尾空白）。
     *
     * <p>语句的结束：括号 / 方括号 / 花括号都配平之后遇到行尾、顶层 {@code ;}（含在区间里）、或块体末尾；
     * 行尾注释不算语句的一部分（它留在原地）。这一层看不见「一行几条」，那是 {@code index} 的事。
     */
    static List<int[]> splitStmts(String src, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c) || c == ';') { i++; continue; }
            if (c == '/' && i + 1 < to && src.charAt(i + 1) == '/') {
                int e = src.indexOf('\n', i);
                i = (e < 0 || e > to) ? to : e;
                continue;
            }
            if (c == '/' && i + 1 < to && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                i = (e < 0 || e + 2 > to) ? to : e + 2;
                continue;
            }
            int end = stmtEnd(src, i, to);
            int e = end;
            while (e > i && Character.isWhitespace(src.charAt(e - 1))) e--;
            out.add(new int[]{i, e});
            i = Math.max(end, i + 1);
        }
        return out;
    }

    /** 从 {@code start} 起、在 {@code to} 之内，这条语句到哪里结束（返回值可含末尾的 {@code ;}）。 */
    private static int stmtEnd(String src, int start, int to) {
        int p = 0, b = 0;                                       // p = 圆/方括号深度，b = 花括号深度
        for (int i = start; i < to; i++) {
            char c = src.charAt(i);
            if (c == '"') {
                int e = skipStr(src, i);
                i = e <= i ? to : e;
                continue;
            }
            if (c == '/' && i + 1 < to && src.charAt(i + 1) == '/') {
                if (p == 0 && b == 0) return i;                  // 行尾注释：语句到此为止
                int e = src.indexOf('\n', i);
                i = (e < 0 || e > to) ? to : e - 1;
                continue;
            }
            if (c == '/' && i + 1 < to && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                if (e < 0) return to;
                i = e + 1;
                continue;
            }
            if (c == '(' || c == '[') p++;
            else if (c == ')' || c == ']') p--;
            else if (c == '{') b++;
            else if (c == '}') b--;
            else if (c == ';' && p == 0 && b == 0) return i + 1;
            else if (c == '\n' && p == 0 && b == 0) return i;
        }
        return to;
    }

    /** 这条语句占的是**整行**吗（行上除了它只有空白）—— 挪位置要靠它。 */
    private static boolean ownLines(String src, int[] s) {
        int l1 = lineOf(src, s[0]), l2 = lineOf(src, s[1] - 1);
        int ls = lineStart(src, l1);
        return src.substring(ls, s[0]).isBlank() && src.substring(s[1], lineEndAt(src, l2)).isBlank();
    }

    /** 这条语句占的行区间（0 起的行下标，闭区间）—— 挪位置用。 */
    private static int[] lineRange(String src, int[] s) {
        return new int[]{lineOf(src, s[0]) - 1, lineOf(src, s[1] - 1) - 1};
    }

    /** 删一条语句的那处替换：独占行（或跨行）整段删（连行尾换行），同一行上还有别的语句就只抠那一条。 */
    private static Fix deleteFix(String src, int[] s) {
        int l1 = lineOf(src, s[0]), l2 = lineOf(src, s[1] - 1);
        int ls = lineStart(src, l1), le = lineEndAt(src, l2);
        if (le < 0) le = src.length();
        boolean own = src.substring(ls, s[0]).isBlank();
        boolean end = src.substring(s[1], le).isBlank();
        if (l1 != l2 || (own && end)) {
            int cut = le < src.length() ? le + 1 : le;
            return new Fix(ls, cut, "");
        }
        String line = own ? indentOf(src.substring(ls, le)) + src.substring(s[1], le).strip()
                : end ? src.substring(ls, s[0]).stripTrailing()
                : src.substring(ls, s[0]).stripTrailing() + " " + src.substring(s[1], le).stripLeading();
        return new Fix(ls, le, line);
    }

    /** 把单行块 {@code xxx { 体 }} 拆成多行（体是空的就拆成两行）—— 单行块里插不进新语句。 */
    private static String expandBlock(String src, int blockLine) {
        int ls = lineStart(src, blockLine);
        int le = lineEnd(src, ls);
        String head = src.substring(ls, le);
        int o = head.indexOf('{'), b = head.lastIndexOf('}');
        if (o < 0 || b < o) return src;
        String body = head.substring(o + 1, b).strip();
        String indent = indentOf(head);
        String rebuilt = head.substring(0, o + 1).stripTrailing() + "\n"
                + (body.isEmpty() ? "" : indent + "  " + body + "\n") + indent + "}";
        return src.substring(0, ls) + rebuilt + src.substring(le);
    }

    /** 多行代码逐行缩进对齐（空行不缩进）。 */
    private static String linesIndent(String code, String indent) {
        StringBuilder sb = new StringBuilder();
        String[] cl = code.split("\n", -1);
        for (int i = 0; i < cl.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(cl[i].isBlank() ? "" : indent + cl[i].strip());
        }
        return sb.toString();
    }

    /** 这段文本有几行。 */
    private static int countLines(String s) {
        return s.split("\n", -1).length;
    }

    /** 一句话摘要（note 里带原文用：多行压成一行、超 30 字截断）。 */
    private static String brief(String s) {
        String t = s.replace("\n", " ").strip();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }

    // ===== 辛、棋牌声明（真源 = 脚本里的顶层声明；档里的 cards / pieces 段读到即迁过来）=====
    // 规范形状：`card 名 { art "…"  back "…"  点数 1  花色 "红桃"  "花色选项" ["红桃","黑桃"] }`
    //           `piece 名 { blueprint "…"  name "…"  scale 1 }` —— 属性一段一个；枚举的可选值另起一段（键 + 「选项」整串）。
    // 读侧比写侧松：`{` 与第一个属性不同行也认得出来。

    /** 一份棋牌声明：{@code kind}（"card" / "piece"）· 名字 · 头行 / 末行行号（1 起）· 属性。 */
    public record Decl(String kind, String name, int line, int endLine, List<Field> fields) { }

    /** 声明里的一个属性：键 · 值源码原文（含引号）· 枚举可选值（null = 不是枚举）。 */
    public record Field(String key, String value, List<String> options) { }

    /** 顶层 {@code card …} / {@code piece …} 声明，按源码顺序（编辑器照这个列）。 */
    public static List<Decl> declsOf(String src, String kind) {
        List<Decl> out = new ArrayList<>();
        List<Tok> ts = toks(src);
        for (int i = 0; i + 2 < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(kind)) continue;
            int line = lineOf(src, t.from());
            if (!src.substring(lineStart(src, line), t.from()).isBlank()) continue;   // 不是行首 = 块里的语句，不是声明
            Tok nm = ts.get(i + 1);
            if (!ts.get(i + 2).text().equals("{")) continue;                           // 名字后不是 { = 不是声明（名字可以是字符串字面量）
            int close = matchBrace(src, ts.get(i + 2).from());
            if (close < 0) continue;                                                   // 花括号没关上（真源解析会给报错）
            out.add(new Decl(kind, nm.text(), line, lineOf(src, close),
                    fieldsIn(src, ts.get(i + 2).from(), close)));
        }
        return out;
    }

    /** 一份声明（找不到 = null）。 */
    public static Decl declOf(String src, String kind, String name) {
        for (Decl d : declsOf(src, kind)) {
            if (d.name().equals(name)) return d;
        }
        return null;
    }

    /** 花括号体里的属性：一段一个 `键 值`（以「选项」结尾的键并到基础键上，与 Parser 同一约定）。 */
    private static List<Field> fieldsIn(String src, int open, int close) {
        Map<String, String> vals = new LinkedHashMap<>();
        Map<String, List<String>> opts = new LinkedHashMap<>();
        int i = open + 1;
        while (i < close) {
            int ls = i;                                            // 这一行的起点（块要按**字符下标**配对花括号）
            int le = lineEnd(src, i);                              // 这一行的末尾（不含 \n）
            String raw = stripLineComment(src.substring(i, le)).strip();
            i = le + 1;
            if (raw.isEmpty()) continue;
            int keyEnd = keyEndOf(raw);                            // 键可以是 `名`，也可以是 `"中文名"`
            String key = keyOf(raw);
            String val = raw.substring(keyEnd).strip();
            if (val.isEmpty()) continue;                           // 只有键 = 半行，不是属性
            if (val.startsWith("{") && !val.endsWith("}")) {        // 多行块（components { … }）：整块跳过，
                int c = matchBrace(src, src.indexOf('{', ls));       // 不算一格字段 —— 块由 blockText / setDeclBlock 管
                if (c < 0) continue;                                // 没关上：真源解析会给报错，这里不猜
                i = lineEnd(src, c) + 1;
                continue;
            }
            if (key.endsWith("选项") && val.startsWith("[")) {
                opts.put(key.substring(0, key.length() - 2), listItems(val));
                continue;
            }
            vals.put(key, val);
        }
        List<Field> out = new ArrayList<>();
        for (String k : opts.keySet()) vals.putIfAbsent(k, "\"\"");     // 只有可选值的键：值按空串算
        for (Map.Entry<String, String> en : vals.entrySet()) {
            List<String> o = opts.get(en.getKey());
            out.add(new Field(en.getKey(), en.getValue(), o == null ? null : List.copyOf(o)));
        }
        return List.copyOf(out);
    }

    /** 加一份声明（空的 → 编辑器再往里加属性）。重名 / 名字是保留字 → 拒收。 */
    public static Result addDecl(String src, String kind, String name) {
        String what = kindLabel(kind);
        if (name == null || name.isBlank()) return new Result(src, what + "名不能是空的");
        if (Lexer.KEYWORDS.contains(name)) return new Result(src, "「" + name + "」是保留字，不能当" + what + "名");
        if (Builtins.VALUES.contains(name)) return new Result(src, "「" + name + "」是内建值的名字，读起来会混");
        if (declOf(src, kind, name) != null) return new Result(src, "已经有一个 " + kind + " " + name + " 了");
        String tail = src.endsWith("\n") || src.isEmpty() ? "" : "\n";
        String text = src + tail + (src.isBlank() ? "" : "\n") + declCode(kind, name, List.of());
        return checked(src, text, "新建" + what + "「" + name + "」");
    }

    /**
     * 改一个属性（{@code value} 给源码原文，如 {@code "卡牌/W"} / {@code 1} / {@code ["甲", "乙"]}）：
     * 有这一段就原地换、没有就插在 {@code } 前面；{@code value} 是空串 = **删掉这一段**。
     * 枚举的可选值用 {@code key + "选项"} 当期键名（插在基础键的下一行）。
     */
    public static Result setDeclField(String src, String kind, String name, String key, String value) {
        Decl d = declOf(src, kind, name);
        if (d == null) return new Result(src, "找不到 " + kind + " " + name);
        if (key == null || key.isBlank()) return new Result(src, "属性名不能是空的");
        String v = value == null ? "" : value.strip();
        List<Fix> fixes = new ArrayList<>();
        int fl = fieldLine(src, d, key);
        // 多行块（components { … }）不能走这条单行改写：已经是块 → 指路 setDeclBlock；值是多行 → 也拦下
        if (fl > 0 && lineText(src, fl).contains("{") && !lineText(src, fl).strip().endsWith("}")) {
            return new Result(src, "「" + key + "」是多行块（" + key + " { … }），要改它得走 setDeclBlock");
        }
        if (v.contains("\n")) {
            return new Result(src, "「" + key + "」的值是多行的：这种要走 setDeclBlock");
        }
        // ⚠ 单行块（`area r1 { box […] art "r1" }`）单独走一条路：按**行**那套只在多行声明上成立 ——
        // 单行块的「收尾 } 的上一行」是声明**上面**那一行（插字段就插到块外面 ⇒ 解析当场报「顶层只能写…」），
        // 而按行找已有字段会永远空转（再改一次就重复插一个同名字段）。
        if (oneLineBlock(src, d)) {
            return setInlineField(src, d, key, v, kind, name);
        }
        if (v.isEmpty()) {                                          // 删这一段
            if (fl < 0) return new Result(src, kind + " " + name + " 里没有「" + key + "」这一段");
            fixes.add(new Fix(lineStart(src, fl), lineEndAt(src, fl) + 1, ""));
            int ol = fieldLine(src, d, key + "选项");                // 连带删它的可选值那一段
            if (ol > 0) fixes.add(new Fix(lineStart(src, ol), lineEndAt(src, ol) + 1, ""));
        } else if (fl > 0) {                                        // 原地换（缩进照旧）
            int ls = lineStart(src, fl);
            String ind = src.substring(ls, ls + (lineText(src, fl).length() - lineText(src, fl).stripLeading().length()));
            fixes.add(new Fix(ls, lineEndAt(src, fl) + 1, ind + nameCode(key) + " " + v + "\n"));
        } else {                                                    // 新插一段（枚举插在基础键后面）
            String base = key.endsWith("选项") ? key.substring(0, key.length() - 2) : key;
            int anchorLine = key.endsWith("选项") ? fieldLine(src, d, base) : d.endLine() - 1;
            if (anchorLine < 0) return new Result(src, "先写「" + base + "」的值，再写 " + base + "选项 […]");
            int at = lineEndAt(src, anchorLine) + 1;
            fixes.add(new Fix(at, at, "  " + nameCode(key) + " " + v + "\n"));
        }
        return checked(src, apply(src, fixes), "把 " + kind + " " + name + " 的「" + key + "」"
                + (v.isEmpty() ? "删掉" : "改成 " + v));
    }

    // ---------- 多行块（对象声明的 components { … }）----------

    /**
     * 一份声明里 {@code 键 { … }} 那一段的**块内原文**（逐字；没有这一段 / 键不是块 = null）。
     *
     */
    public static String blockText(String src, String kind, String name, String key) {
        Decl d = declOf(src, kind, name);
        if (d == null) return null;
        int[] r = blockRange(src, d, key);
        if (r == null) return null;
        int open = src.indexOf('{', r[0]);
        int close = matchBrace(src, open);
        return close < 0 ? null : trimBlockInner(src.substring(open + 1, close));
    }

    /**
     * 写一段多行块 {@code 键 { … }}（对象声明的 {@code components} 层用）：
     * 没有这一段 → 在声明块末尾（{@code }} 之前）新插一段；已有 → **整段换**（只换块内原文，键与前后行不动）；
     * {@code inner} 空 = 把这一段删掉（连行）。写完过一遍 {@link #checked}（结果必须还能解析）。
     */
    public static Result setDeclBlock(String src, String kind, String name, String key, String inner) {
        Decl d = declOf(src, kind, name);
        if (d == null) return new Result(src, "找不到 " + kind + " " + name);
        if (key == null || key.isBlank()) return new Result(src, "属性名不能是空的");
        String body = inner == null ? "" : trimBlockInner(inner);
        int[] r = blockRange(src, d, key);
        if (r == null) {
            if (body.isEmpty()) return new Result(src, kind + " " + name + " 里没有「" + key + "」这一段");
            int at = lineStart(src, d.endLine());                    // 声明块 } 那一行之前
            String add = "  " + nameCode(key) + " {\n" + indentBlock(body) + "\n  }\n";
            return checked(src, apply(src, List.of(new Fix(at, at, add))),
                    "给 " + kind + " " + name + " 加了「" + key + "」一段（" + blockFields(body) + " 个属性）");
        }
        if (body.isEmpty()) {
            int from = lineStart(src, r[2]);
            int to = lineEndAt(src, r[3]) + 1;
            return checked(src, apply(src, List.of(new Fix(from, to, ""))),
                    "把 " + kind + " " + name + " 的「" + key + "」整段删掉");
        }
        String old = src.substring(r[0], r[1]);
        String nw = "  " + nameCode(key) + " {\n" + indentBlock(body) + "\n  }";
        if (old.equals(nw)) return new Result(src, "「" + key + "」没变，没动");
        return checked(src, apply(src, List.of(new Fix(r[0], r[1], nw))),
                "换了 " + kind + " " + name + " 的「" + key + "」（" + blockFields(body) + " 个属性）");
    }

    /**
     * 声明里 {@code 键 { … }} 那一段的位置：{@code [块起字符下标, 块终字符下标(不含), 起行, 终行]}
     * （从 {@code 键 \{} 那一行到 {@code }} 那一行）。没有这一段 / 这个键不是块（单行属性）= null；
     * 行号只在**删整段**时用（要连行一起删干净）。
     */
    private static int[] blockRange(String src, Decl d, String key) {
        for (int L = d.line() + 1; L < d.endLine(); L++) {
            String t = stripLineComment(lineText(src, L)).strip();
            if (t.isEmpty() || !keyOf(t).equals(key)) continue;
            int brace = lineText(src, L).indexOf('{');
            if (brace < 0) return null;
            int open = lineStart(src, L) + brace;
            int close = matchBrace(src, open);
            if (close < 0) return null;
            return new int[] {lineStart(src, L), lineEndAt(src, lineOf(src, close)),
                    L, lineOf(src, close)};
        }
        return null;
    }

    /**
     * 块内原文的两头收拾：去掉首尾空行 + 去掉每行**公共的那几个空格**（相对缩进留着，嵌套往里缩）。
     * 写回时再由 {@link #indentBlock} 统一加缩进 —— 这样「读出来 → 原样写回去」是幂等的。
     */
    private static String trimBlockInner(String inner) {
        List<String> ls = new ArrayList<>();
        for (String one : (inner == null ? "" : inner).split("\n", -1)) ls.add(one.stripTrailing());
        while (!ls.isEmpty() && ls.get(0).isBlank()) ls.remove(0);
        while (!ls.isEmpty() && ls.get(ls.size() - 1).isBlank()) ls.remove(ls.size() - 1);
        if (ls.isEmpty()) return "";
        int cut = Integer.MAX_VALUE;
        for (String one : ls) {
            if (!one.isBlank()) cut = Math.min(cut, one.length() - one.stripLeading().length());
        }
        StringBuilder sb = new StringBuilder();
        for (String one : ls) {
            sb.append(sb.length() == 0 ? "" : "\n").append(one.length() >= cut ? one.substring(cut) : one.strip());
        }
        return sb.toString();
    }

    /** 块内原文 → 加统一缩进（写回用）。空行不留尾随空格。 */
    private static String indentBlock(String body) {
        StringBuilder sb = new StringBuilder();
        for (String one : body.split("\n", -1)) {
            sb.append(sb.length() == 0 ? "" : "\n").append(one.isBlank() ? "" : "    " + one);
        }
        return sb.toString();
    }

    /** 块里有几行**有内容**（回执里说人话用；空行不算一个属性）。 */
    private static int blockFields(String body) {
        int n = 0;
        for (String one : body.split("\n")) if (!one.isBlank()) n++;
        return n;
    }

    /** 删一份声明（整块，连它的空行）。 */
    public static Result removeDecl(String src, String kind, String name) {
        Decl d = declOf(src, kind, name);
        if (d == null) return new Result(src, "找不到 " + kind + " " + name);
        int from = lineStart(src, d.line());
        int to = lineEndAt(src, d.endLine());
        if (to < src.length() && src.charAt(to) == '\n') {
            if (to + 1 < src.length() && src.charAt(to + 1) == '\n') to++;   // 顺手收掉一个空行
        }
        return checked(src, apply(src, List.of(new Fix(from, to + 1, ""))),
                "删掉 " + (kind.equals("card") ? "卡牌" : "棋子") + "「" + name + "」");
    }

    /** 脚本里还在引用这个名字的行号（{@code card("名" …)} / {@code place_piece("名" …)}）—— 删之前对账。 */
    public static List<Integer> declRefs(String src, String kind, String name) {
        String call = kind.equals("card") ? "card(" : "place_piece(";
        List<Integer> out = new ArrayList<>();
        for (Tok t : toks(src)) {
            if (t.str() || !t.text().equals(call.substring(0, call.length() - 1))) continue;
            int line = lineOf(src, t.from());
            if (!lineText(src, line).contains(call)) continue;         // 只认函数调用，不认别处的同名标识符
            int le = lineEndAt(src, line);
            String tail = src.substring(t.from(), Math.min(le, src.length()));
            if (tail.contains("\"" + name + "\"")) out.add(line);
        }
        return out;
    }

    // ---------- 癸、侧栏对账（登记 #13：只做**只读列表**，不做图上的引用线）----------

    /**
     * 「谁在用」：这个名字被**调用**的行号（部件被哪些舞台调用 · 函数谁调用）。
     *
     * <p>判据 = 扫到叫这个名字的标识符、且它紧跟一个 {@code (}（跳过字符串与注释 —— 走 {@link #toks}）。
     * 「只是提到这个名字」的行不算（那是声明 / 注释里说一说）。**自己的声明那一行也在结果里**（形如
     * {@code part btn(id) { / func myFunc(a) {}）—— 调用方知道自己是哪一行，自己去掉。
     */
    public static List<Integer> callLines(String src, String name) {
        List<Integer> out = new ArrayList<>();
        if (name == null || name.isBlank()) return out;
        List<Tok> ts = toks(src);
        for (int i = 0; i + 1 < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(name)) continue;
            if (!ts.get(i + 1).text().equals("(")) continue;
            int line = lineOf(src, t.from());
            if (!out.contains(line)) out.add(line);
        }
        return out;
    }

    /**
     * 「谁在用」（变量）：这个名字在哪几行被**写**（赋值 / {@code +=} 这类）· 在哪几行被**读**。
     *
     * <p>判据：扫到名字 → 跳过紧跟的下标链（{@code name[i]} / {@code name[i][j]}）→ 看下一个符号是不是赋值号。
     * 是 = 写，否则 = 读；同名的一行里出现两次（{@code score = score + 1}）会**两个列表都进**（同行既写又读，是实话）。
     * ⚠ {@code ==} 不算写（它是比较）；**声明那一行**（{@code var name = 0}）也会被算成「写」——
     * 调用方知道声明行号，自己去掉（{@link #varsOf} 的 {@code VarAt.line}）。
     */
    public static List<Integer> varWrites(String src, String name) {
        return varUses(src, name, true);
    }

    /** 变量在哪几行被读（同上，不含赋值号那种）。 */
    public static List<Integer> varReads(String src, String name) {
        return varUses(src, name, false);
    }

    /** 变量用法的行号（{@code wantWrite} = 要写的那种）。 */
    private static List<Integer> varUses(String src, String name, boolean wantWrite) {
        List<Integer> out = new ArrayList<>();
        if (name == null || name.isBlank()) return out;
        List<Tok> ts = toks(src);
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            if (t.str() || !t.text().equals(name)) continue;
            int j = i + 1;
            while (j < ts.size() && ts.get(j).text().equals("[")) {      // 跳过下标链 name[i][j]
                int depth = 1;
                j++;
                while (j < ts.size() && depth > 0) {
                    String x = ts.get(j).text();
                    if (x.equals("[")) depth++;
                    else if (x.equals("]")) depth--;
                    j++;
                }
            }
            String next = j < ts.size() ? ts.get(j).text() : "";
            boolean write = next.equals("=") || next.equals("+=") || next.equals("-=")
                    || next.equals("*=") || next.equals("/=");
            if (write != wantWrite) continue;
            int line = lineOf(src, t.from());
            if (!out.contains(line)) out.add(line);
        }
        return out;
    }

    /** 拼一段声明文本（迁移 / 新建都用它；值给源码原文）。 */
    public static String declCode(String kind, String name, List<Field> fields) {
        StringBuilder sb = new StringBuilder(kind).append(' ').append(nameCode(name)).append(" {\n");
        for (Field f : fields) {
            sb.append("  ").append(nameCode(f.key())).append(' ').append(f.value()).append('\n');
            if (f.options() != null) {
                // ⚠ 可选值那一段的**名字是「键 + 选项」整串**，要过 nameCode：中文键就写成 "可选选项"
                //  （挨着写成 `"可选"选项` 会让 Lexer 撞上中文字符 —— 标识符只能用英文）
                sb.append("  ").append(nameCode(f.key() + "选项")).append(' ').append(listCode(f.options())).append('\n');
            }
        }
        return sb.append("}\n").toString();
    }

    // ---------- 壬、生成器物（段8：#17 批量声明 · #20 脚本片段）----------

    /**
     * #17 生成器：解析「花色: 红桃, 黑桃」这种列表输入 → {名字, 值…}。
     *
     * <p>冒号前是**列表名**（占位符就用它：{@code {花色}}）；没有冒号 = 名字空（占位符用 {@code {A}} / {@code {B}}）。
     * 空白项丢掉（`红桃,黑桃` 只认两个），首尾空白掐掉。
     */
    public static String[] parseListLine(String line) {
        String t = line == null ? "" : line.strip();
        int c = t.indexOf(':');
        String nm = c < 0 ? "" : t.substring(0, c).strip();
        String vals = c < 0 ? t : t.substring(c + 1);
        return new String[]{nm, String.join(",", splitValues(vals))};
    }

    /** 「a, b,c」→ [a, b, c]（逗号分隔；空项丢掉；首尾空白掐掉）。 */
    public static List<String> splitValues(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String one : s.split(",")) {
            String v = one.strip();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /**
     * #17 生成器：解析「花色={花色};点数={点数}」→ {键, 值模板} 串。
     *
     * <p>分号分隔多段（值里本来就可能带逗号，所以不用逗号分隔）；没有 `=` 的段丢掉（空键也丢）。
     */
    public static List<String[]> parseFieldTemplates(String line) {
        List<String[]> out = new ArrayList<>();
        if (line == null) return out;
        for (String one : line.split(";")) {
            int e = one.indexOf('=');
            if (e < 0) continue;
            String k = one.substring(0, e).strip();
            String v = one.substring(e + 1).strip();
            if (!k.isEmpty() && !v.isEmpty()) out.add(new String[]{k, v});
        }
        return out;
    }

    /**
     * #17：模板替换 —— {@code {花色}} 换成对应值。
     *
     * <p>没给的占位符**原样留着**：作者一眼就能看出名字拼错了（比悄悄换空串好）。
     */
    public static String template(String pattern, Map<String, String> vars) {
        String out = pattern == null ? "" : pattern;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /**
     * #17：**一次**把 N 段声明追加到脚本末尾（生成器专用）。
     *
     */
    public static Result addDeclCodes(String src, List<String> codes, String note) {
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (c == null || c.isBlank()) continue;
            sb.append('\n').append(c);
        }
        if (sb.length() == 0) return new Result(src, "没有要生成的声明");
        String text = src + (src.isEmpty() || src.endsWith("\n") ? "" : "\n") + sb;
        return checked(src, text, note);
    }

    /** #20：一批卡 → 顶层 {@code var 牌堆 = ["甲", "乙"]}（生进脚本，不留第二份真源）。 */
    public static Result addDeckVar(String src, String varName, List<String> cardIds) {
        return addVar(src, varName, "", listCode(cardIds));
    }

    /** #20：一枚棋子 → 往 {@code on start} 里加一行 {@code place_piece("p1", x, y, z)}（没有 on start 就新建）。 */
    public static Result addPlacePiece(String src, String pieceId, int x, int y, int z) {
        String id = pieceId == null ? "" : pieceId.replace("\\", "").replace("\"", "");
        String code = "place_piece(\"" + id + "\", " + x + ", " + y + ", " + z + ")";
        int h = handlerLine(src, "start");
        if (h >= 0) {
            Result r = addStmt(src, h, 0, 999, code);
            return r.text().equals(src) ? r : new Result(r.text(), "已在 on start 末尾加一行：" + code);
        }
        return appendTop(src, "on start {\n  " + code + "\n}", "已新建 on start 并加一行：" + code);
    }

    /** 名字 → 源码写法：能当标识符就裸写，否则包引号（中文卡名 / 属性键走这条）。 */
    public static String nameCode(String s) {
        String t = s == null ? "" : s;
        if (!t.isEmpty() && !Character.isDigit(t.charAt(0)) && t.chars().allMatch(c -> c < 128
                && (Character.isLetterOrDigit(c) || c == '_'))) {
            return t;
        }
        return strCode(t);
    }

    /** 一行（声明体内）的键原文：`名` 或 `"名字"` → 去掉引号的那个名字。 */
    private static String keyOf(String line) {
        String t = line.strip();
        if (t.startsWith("\"")) {
            int e = 1;
            while (e < t.length() && t.charAt(e) != '"') e += t.charAt(e) == '\\' ? 2 : 1;
            return t.substring(1, Math.min(e, t.length()));
        }
        return t.isEmpty() ? "" : t.split("\\s+", 2)[0];
    }

    /** 一行里键结束的位置（键之后就是值）。 */
    private static int keyEndOf(String line) {
        String t = line.strip();
        int lead = line.length() - line.stripLeading().length();
        if (t.startsWith("\"")) {
            int e = 1;
            while (e < t.length() && t.charAt(e) != '"') e += t.charAt(e) == '\\' ? 2 : 1;
            return lead + Math.min(e + 1, t.length());
        }
        return lead + (t.isEmpty() ? 0 : t.split("\\s+", 2)[0].length());
    }

    /** 这个实参原文是不是**字符串字面量**（两端引号，且里面没有裸引号）—— 画块属性栏改它时要再包回引号。 */
    public static boolean isQuoted(String arg) {
        String t = arg == null ? "" : arg.strip();
        if (t.length() < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return false;
        for (int i = 1; i < t.length() - 1; i++) {
            if (t.charAt(i) == '"') return false;                  // `"a" + "b"` 这种拼接不是字面量
            if (t.charAt(i) == '\\') i++;
        }
        return true;
    }

    /**
     * 实参原文的「里子」：字符串字面量给内文（改的时候不用自己打引号），别的原样返回。
     *
     */
    public static String inner(String arg) {
        if (!isQuoted(arg)) return arg == null ? "" : arg;
        String t = arg.strip();
        t = t.substring(1, t.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '\\' || i + 1 >= t.length()) {
                sb.append(c);
                continue;
            }
            char n = t.charAt(++i);
            sb.append(switch (n) {
                case 'n' -> '\n';
                case 't' -> '\t';
                default -> n;
            });
        }
        return sb.toString();
    }

    /** 字符串 → 源码字面量（转义 {@code \} {@code "} 与换行 / 制表 —— 与 {@link #inner} 严格互逆）。 */
    public static String strCode(String s) {
        String t = s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
        return "\"" + t + "\"";
    }

    public static String listCode(List<String> vs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(strCode(vs.get(i)));
        }
        return sb.append(']').toString();
    }

    /**
     * 可选值那一段的名字（键 + 「选项」）：整串当一个名字 —— 中文键写出来是 {@code "可选选项"}，
     * ASCII 键也是带引号的 {@code "hp选项"}（挨着写裸的会让 Lexer 撞中文字符）。
     */
    public static String optionsKey(String key) {
        return key.endsWith("选项") ? key : key + "选项";
    }

    /** 声明**是不是单行块**（`{` 与配对的 `}` 在同一行）—— 编辑器生成的区域声明就是这种。 */
    private static boolean oneLineBlock(String src, Decl d) {
        int[] body = bodySpan(src, d);
        return body != null && lineOf(src, body[0]) == lineOf(src, body[1]);
    }

    /** 声明块体的偏移区间 {@code [开括号后, 收尾 } 前)}；没有块 / 括号没关上 = null。 */
    private static int[] bodySpan(String src, Decl d) {
        int ls = lineStart(src, d.line());
        if (ls < 0) return null;
        int open = src.indexOf('{', ls);
        if (open < 0) return null;
        int close = matchBrace(src, open);
        return close <= open ? null : new int[] { open + 1, close };
    }

    /**
     * 单行块里 {@code key} 那一段的偏移区间 {@code [键起点, 值终点)}（没有这一段 = null）。
     *
     */
    private static int[] fieldSpan(String src, Decl d, String key) {
        int[] body = bodySpan(src, d);
        if (body == null) return null;
        List<Tok> ts = toks(src);
        int i = 0;
        while (i < ts.size() && ts.get(i).from() < body[0]) i++;
        while (i < ts.size() && ts.get(i).from() < body[1]) {
            Tok k = ts.get(i);
            if (!k.str() && k.text().equals("}")) break;
            int e = skipValueEnd(ts, i + 1, body[1]);            // 值后面那个 token 的下标
            if (e < 0) return null;
            if (k.text().equals(key)) return new int[] { k.from(), ts.get(e - 1).to() };
            i = e;
        }
        return null;
    }

    /** 从 {@code j}（值的第一个 token）跳过一个值，返回它**后面**那个 token 的下标；出块体 = -1。 */
    private static int skipValueEnd(List<Tok> ts, int j, int limit) {
        if (j >= ts.size() || ts.get(j).from() >= limit) return -1;
        if (!ts.get(j).str() && ts.get(j).text().equals("-")) j++;              // 负数
        if (j >= ts.size() || ts.get(j).from() >= limit) return -1;
        if (!ts.get(j).str() && ts.get(j).text().equals("[")) {                 // 列表：配到 ]
            int depth = 0;
            for (int k = j; k < ts.size() && ts.get(k).from() < limit; k++) {
                if (ts.get(k).str()) continue;                                  // 字符串里的 [ ] 不算
                String t = ts.get(k).text();
                if (t.equals("[")) depth++;
                else if (t.equals("]") && --depth == 0) return k + 1;
            }
            return -1;
        }
        return j + 1;
    }

    /**
     * 单行块里设 / 删一个字段（{@code setDeclField} 的单行支路）。
     *
     * <p>三条路：有这一段就**原地换**（{@code [键起点, 值终点)} 整段替换）、{@code v} 空就**删**这一段的区间
     * （连尾随空格一起吃掉，免得留双空格）、都没有就在**收尾 } 之前**插一段（同行，绝不插到块外面）。
     */
    private static Result setInlineField(String src, Decl d, String key, String v, String kind, String name) {
        int[] body = bodySpan(src, d);
        if (body == null) return new Result(src, kind + " " + name + " 的花括号没关上，没动");
        int[] span = fieldSpan(src, d, key);
        String what = "把 " + kind + " " + name + " 的「" + key + "」" + (v.isEmpty() ? "删掉" : "改成 " + v);
        if (v.isEmpty()) {
            if (span == null) return new Result(src, kind + " " + name + " 里没有「" + key + "」这一段");
            int ws = span[0];
            while (ws > body[0] && (src.charAt(ws - 1) == ' ' || src.charAt(ws - 1) == '\t')) ws--;
            int from = ws > body[0] ? ws : span[0];         // 连带吃掉**段前**那段空白（别留双空格）
            return checked(src, apply(src, List.of(new Fix(from, span[1], ""))), what);
        }
        if (span != null) {
            return checked(src, apply(src, List.of(new Fix(span[0], span[1], nameCode(key) + " " + v))), what);
        }
        // 新插一段：**替换收尾 } 前那段空白**成一个规范空格 ⇒ `… art "r1" name "x" }`（不会出现两个空格）
        // ⚠ body[1] 就是 `}` 的位置（身体的右端开区间），所以空白是 [ws, body[1])
        int ws = body[1];
        while (ws > body[0] && (src.charAt(ws - 1) == ' ' || src.charAt(ws - 1) == '\t')) ws--;
        return checked(src, apply(src, List.of(new Fix(ws, body[1],
                " " + nameCode(key) + " " + v + " "))), what);
    }


    private static int fieldLine(String src, Decl d, String key) {
        for (int L = d.line() + 1; L < d.endLine(); L++) {
            String t = stripLineComment(lineText(src, L)).strip();
            if (t.isEmpty()) continue;
            String k = t.split("\\s+", 2)[0];
            if (keyOf(t).equals(key)) return L;
            int brace = lineText(src, L).indexOf('{');
            if (brace >= 0 && !t.endsWith("}")) {
                int c = matchBrace(src, lineStart(src, L) + brace);
                if (c > 0) L = lineOf(src, c);                    // 跳到块的 } 那一行（下次循环自然跳过）
            }
        }
        return -1;
    }

    /** 第 {@code line} 行的原文（不含换行；没有这一行 = 空串）。 */
    private static String lineText(String src, int line) {
        int ls = lineStart(src, line);
        if (ls < 0) return "";
        int le = lineEnd(src, ls);
        return src.substring(ls, Math.min(le, src.length()));
    }

    /** 掐掉行尾 {@code // 注释}（字符串里的不算）。 */
    private static String stripLineComment(String line) {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 列表字面量原文 → 元素原文（{@code ["甲", "乙"]} → 甲 / 乙）。 */
    private static List<String> listItems(String list) {
        List<String> out = new ArrayList<>();
        for (Tok t : toks(list)) {
            if (t.str()) out.add(t.text());
        }
        return out;
    }

    // ===== 文本小工具 =====

    /** 按 \n 切且保留行尾（join 回去逐字相同）。 */
    private static String[] splitLines(String src) {
        return src.split("\n", -1);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** 去掉末尾多出来的空行（删块时用）。 */
    private static String stripTrailingBlank(String s) {
        return s.isBlank() ? "" : s.replaceAll("\\n+$", "") + "\n";
    }

    /** 一行的缩进（空格 / Tab 原样）。 */
    private static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /**
     * 从 {@code startLine}（0 起）那行往下找与首个 {@code {} 配对的 {@code }} 在哪一行（-1 = 不配对）。
     *
     * <p>扫的时候跳过 {@code // 注释} 与 {@code "字符串"} —— 否则注释里一个 {@code }} 就能把块提前截断
     * （自检里有一条专门拿「注释/字符串里带括号」试它）。
     */
    private static int blockEnd(String[] lines, int startLine) {
        int depth = 0;
        boolean started = false;
        for (int i = startLine; i < lines.length; i++) {
            String l = lines[i];
            boolean inStr = false;
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                if (inStr) {
                    if (c == '\\') { k++; continue; }
                    if (c == '"') inStr = false;
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '/' && k + 1 < l.length() && l.charAt(k + 1) == '/') {
                    break;                                            // 注释到行尾，剩下的不看
                } else if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                    if (started && depth == 0) return i;
                }
            }
        }
        return -1;
    }

}
