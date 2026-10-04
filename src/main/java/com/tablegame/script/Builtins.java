package com.tablegame.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 内建函数与「值的工具箱」。
 *
 * <p>内建函数只有 8 个：{@code len push insert del random num text rec}。
 * 聚合（max/min/sum/avg/count/argmax/argmin/sorted）与 {@code pick}、{@code team} 一律不内建 ——
 * 那是「打包好的模块」，用 {@code for}/{@code while} 自己拼。
 *
 * <p>值只有四种加记录：数（Double）· 文本（String）· 列表（List&lt;Object&gt）· 真假（Boolean）·
 * 记录（LinkedHashMap，有名字段）。席位就是文本。下半部分是这些值的转换/容错工具箱。
 */
public final class Builtins {
    private Builtins() { }

    /** 内建函数名（{@code rec} = 记录构造器；记录 = 有名字段的容器）。 */
    public static final List<String> FUNCS = List.of("len", "push", "insert", "del", "random", "num", "text", "rec");

    /** 内建值名：不用 {@code var} 声明就能读，由事件参数喂进来（{@code pick} = 被点那一份的身份值，-1 = 没被点过）。 */
    public static final List<String> VALUES = List.of("actor", "input", "stage_left", "pick",
            "bx", "by", "bz", "block",
            // 视线射线命中的那一格（on look），与 bx/by/bz/block 对称
            "look_x", "look_y", "look_z", "look_block",
            // 手势与圈边：两只手各拿的 / 潜行 / 刚跨过圈边
            "hand", "offhand", "sneak", "edge",
            // 跨过的是哪条区域（匿名那条 = 空串）
            "edge_area",
            // 这一下是不是右键方块（踩格与右键共用 on world，靠它分）
            "rclick",
            // 实体事件：哪个对象 / 有没有被打死
            "eid", "edead",
            // 1 = 左键（攻击）/ 0 = 右键（卡牌 / 实体画面组件也认）
            "hit",
            // 手里那条项目资产的名字（两条基底相同的自定义物品靠它分）
            "hand_asset", "off_asset");

    public static boolean isFunc(String name) {
        return FUNCS.contains(name);
    }

    /** 调内建函数（参数已求好值）。{@code push/insert/del} 就地改那张列表（列表是引用类型），所以 `hand[actor]` 能直接改到本人手上那一张。 */
    public static Object call(String fn, List<Object> a, int line, Random rnd) {
        switch (fn) {
            case "len": {
                Object v = arg(fn, a, line, 0);
                if (v instanceof List<?> l) return (double) l.size();
                if (v instanceof java.util.LinkedHashMap<?, ?> m) return (double) m.size();   // 记录 = 字段数
                return (double) text(v).length();
            }
            case "rec": {
                // rec = 空记录；rec(键, 值, …) = 带初值（键值必须成对，键转文本）。
                // LinkedHashMap 保插入序 ⇒ text(r) 的字段顺序 = 声明顺序。
                java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
                if (a.size() % 2 != 0) {
                    throw new Ast.ScriptError(line, "rec 的键值对要成对（rec(键1, 值1, 键2, 值2, …)），这里是 " + a.size() + " 个参数");
                }
                for (int i = 0; i < a.size(); i += 2) {
                    String key = text(a.get(i));
                    if (key.isEmpty()) throw new Ast.ScriptError(line, "rec 的字段名不能是空文本");
                    r.put(key, a.get(i + 1));
                }
                return r;
            }
            case "push": {
                List<Object> l = listOf(arg(fn, a, line, 0), line, "push 第一个参数要是一张列表");
                l.add(arg(fn, a, line, 1));
                return l;
            }
            case "insert": {
                List<Object> l = listOf(arg(fn, a, line, 0), line, "insert 第一个参数要是一张列表");
                int i = clamp(toInt(arg(fn, a, line, 1), line, "插入位置"), 0, l.size());
                l.add(i, arg(fn, a, line, 2));
                return l;
            }
            case "del": {
                List<Object> l = listOf(arg(fn, a, line, 0), line, "del 第一个参数要是一张列表");
                int i = toInt(arg(fn, a, line, 1), line, "删除位置");
                if (i < 0 || i >= l.size()) {
                    throw new Ast.ScriptError(line, "del 的下标越界：" + i + "（这张列表只有 " + l.size() + " 项）");
                }
                l.remove(i);
                return l;
            }
            case "random": {
                int n = clamp(toInt(arg(fn, a, line, 0), line, "random 的范围"), 0, Integer.MAX_VALUE);
                return (double) (n <= 0 ? 0 : rnd.nextInt(n));      // random(n) → 0 .. n-1
            }
            case "num": {
                // num(x) 转不成数字就报错（带行号，不静默变 0）；num(x, 默认) 转不了给默认值、不掐局（输入框那条路用）。
                Double d = numOf(arg(fn, a, line, 0));
                if (d != null) return d;
                if (a.size() >= 2) return number(arg(fn, a, line, 1), line);
                return number(arg(fn, a, line, 0), line);
            }
            case "text":
                return text(arg(fn, a, line, 0));
            default:
                throw new Ast.ScriptError(line, "没有这个内建函数：" + fn + "（只有 " + String.join("/", FUNCS) + "）");
        }
    }

    private static Object arg(String fn, List<Object> a, int line, int i) {
        if (i >= a.size()) throw new Ast.ScriptError(line, fn + " 少了参数（需要 " + (i + 1) + " 个）");
        return a.get(i);
    }

    // ============================================================ 值的工具箱

    /** 任何值 → 文本（拼接 / 消息 / say 都走它）。 */
    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return fmt(d);
        if (v instanceof String s) return s;
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object it : l) parts.add(text(it));
            return String.join(",", parts);
        }
        if (v instanceof java.util.LinkedHashMap<?, ?> m) {
            // 记录渲染：字段=值,字段=值（插入序）。
            // ponytail: 无环检测 —— 自引用记录（r.self = r）打印会栈溢出；要支持时在这里加深度计数。
            List<String> parts = new ArrayList<>();
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) parts.add(text(e.getKey()) + "=" + text(e.getValue()));
            return String.join(",", parts);
        }
        return String.valueOf(v);
    }

    /** 文本 → 数字；算不了返回 null（「是不是数字」的判断全靠它）。 */
    public static Double numOf(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof String s) {
            if (s.isBlank()) return null;
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** {@code num(x)}：转不成数字就报错（带行号），不静默变 0。 */
    public static Double number(Object v, int line) {
        Double d = numOf(v);
        if (d == null) throw new Ast.ScriptError(line, "num() 转不了：" + text(v) + " 不是数字");
        return d;
    }

    /** 真假：数 0 / 空文本 / 空列表 / 空记录 / false = 假。 */
    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0;
        if (v instanceof String s) return !s.isEmpty() && !s.equals("0") && !s.equals("false");
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof java.util.LinkedHashMap<?, ?> m) return !m.isEmpty();   // 记录：没有字段 = 假
        return true;
    }

    /** 取列表本体（不是列表就报错，消息带行号）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> listOf(Object v, int line, String what) {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new Ast.ScriptError(line, what + "不是列表（实际是" + (text(v).isEmpty() ? "空值" : "文本/数字「" + text(v) + "」") + "）");
    }

    /** 取整数下标（小数向 0 取整）。 */
    public static int toInt(Object v, int line, String what) {
        Double d = numOf(v);
        if (d == null) throw new Ast.ScriptError(line, what + "要是数字，这里是" + text(v));
        return (int) (double) d;
    }

    /** 数字转文本：整的不带 .0。 */
    public static String fmt(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
