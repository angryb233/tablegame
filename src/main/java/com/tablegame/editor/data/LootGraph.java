package com.tablegame.editor.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tablegame.script.edit.ScriptGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * 原版战利品表 → 画布图（{@code loot_table} JSON → {@link ScriptGraph.Graph}）。
 * 节点 key = {@link LootEdit} 的路径，连线 = 嵌套：表→池 · 池→条目 · 复合条目→子条目 · 节点→修饰器/谓词 · 谓词树再往下；
 * 虚线（{@code cond}）= 谓词边 + 分支条目（alternatives）的出边。
 * ⚠ 多个池并列（各自都掷）⇒ 池间不连线；顺序/分支只在 sequence / alternatives 上。
 */
public final class LootGraph {
    public static final int KIND_TABLE = 0, KIND_POOL = 1, KIND_ENTRY = 2,
            KIND_COMP = 3, KIND_FUNC = 4, KIND_COND = 5, KIND_REF = 6;

    /** 嵌套上限：防病态表把界面拖死（超了给一个「…」节点，不递归）。 */
    private static final int MAX_DEPTH = 8;   // ponytail: 固定上限；真要无限嵌套再说

    public static ScriptGraph.Graph build(JsonObject table) {
        List<ScriptGraph.Node> ns = new ArrayList<>();
        List<ScriptGraph.Edge> es = new ArrayList<>();
        JsonArray pools = arrOf(table, "pools");
        String root = "table";
        ns.add(new ScriptGraph.Node(root, "表 · " + shortId(LootEdit.idOf(table))
                + "（" + pools.size() + " 个池）", 0, KIND_TABLE));
        for (int i = 0; i < pools.size(); i++) {
            JsonObject pool = obj(pools.get(i));
            if (pool == null) continue;
            String pp = "pools[" + i + "]";
            ns.add(new ScriptGraph.Node(pp, poolLabel(pool, i), 0, KIND_POOL));
            es.add(new ScriptGraph.Edge(root, pp, false, "", 0));
            walk(ns, es, pp, pool, 1);
        }
        return new ScriptGraph.Graph(ns, es);
    }

    /** 一个节点的子节点：数组的（entries / children / functions / conditions / terms）+ 单对象的（term）。 */
    private record Kid(String seg, JsonObject node, int kind, boolean cond, String condText) { }

    private static List<Kid> kidsOf(JsonObject n) {
        List<Kid> out = new ArrayList<>();
        if (n.has("entries")) {
            collect(out, n, "entries", false, null);
        } else if (LootEdit.isComposite(n)) {
            // alternatives 的每条子条目 = 一个分支 ⇒ 画虚线
            boolean branch = LootEdit.idOf(n).endsWith("alternatives");
            collect(out, n, "children", branch, branch ? "分支" : null);
        }
        collect(out, n, "functions", false, null);
        collect(out, n, "conditions", true, null);
        collect(out, n, "terms", true, null);
        JsonElement term = n.get("term");
        if (term != null && term.isJsonObject()) out.add(new Kid("term", term.getAsJsonObject(), KIND_COND, true, "取反"));
        return out;
    }

    private static void collect(List<Kid> out, JsonObject n, String key, boolean cond, String condText) {
        JsonElement e = n.get(key);
        if (e == null || !e.isJsonArray()) return;
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            JsonObject c = obj(a.get(i));
            if (c == null) continue;
            out.add(new Kid(key + "[" + i + "]", c, kindOf(c), cond, condText));
        }
    }

    private static void walk(List<ScriptGraph.Node> ns, List<ScriptGraph.Edge> es,
            String path, JsonObject n, int depth) {
        if (depth > MAX_DEPTH) {
            String ep = path + ".…";
            ns.add(new ScriptGraph.Node(ep, "…（嵌套超过 " + MAX_DEPTH + " 层）", 0, KIND_ENTRY));
            es.add(new ScriptGraph.Edge(path, ep, false, "", 0));
            return;
        }
        boolean seq = LootEdit.idOf(n).endsWith("sequence");     // 只有 sequence 的子里「先后」有意义
        int ord = 0;
        for (Kid k : kidsOf(n)) {
            String cp = path + "." + k.seg();
            // 序号前缀：池里的条目、以及 sequence 的子条目 —— 顺序 = 数组顺序，画布上看得见
            String pre = k.seg().startsWith("entries[") || (seq && k.seg().startsWith("children["))
                    ? (++ord) + ". " : "";
            ns.add(new ScriptGraph.Node(cp, pre + label(k.node(), k.kind()), 0, k.kind()));
            es.add(new ScriptGraph.Edge(path, cp, k.cond(), k.condText() == null ? "" : k.condText(), 0));
            walk(ns, es, cp, k.node(), depth + 1);
        }
    }

    private static int kindOf(JsonObject c) {
        if (c.has("condition")) return KIND_COND;
        if (c.has("function")) return KIND_FUNC;
        if (LootEdit.isComposite(c)) return KIND_COMP;
        if (LootEdit.idOf(c).endsWith("loot_table")) return KIND_REF;
        return KIND_ENTRY;
    }

    // ---------- 节点行文案 ----------

    private static String label(JsonObject n, int kind) {
        String id = LootEdit.idOf(n);
        if (kind == KIND_FUNC) return "⚙ " + LootEdit.labelOf(id) + extra(n, "count", "limit", "enchantment", "damage");
        if (kind == KIND_COND) return "? " + LootEdit.labelOf(id) + extra(n, "chance", "enchantment");
        if (kind == KIND_REF) return "套表 " + shortId(str(n, "value"));
        String nm = str(n, "name");
        if (nm.isEmpty()) nm = str(n, "value");
        StringBuilder b = new StringBuilder(LootEdit.labelOf(id));
        if (!nm.isEmpty()) b.append(" · ").append(shortId(nm));
        if (n.has("weight")) b.append(" · 权重").append(str(n, "weight"));
        return b.toString();
    }

    private static String poolLabel(JsonObject pool, int i) {
        return "池 " + (i + 1) + "（掷 " + decl(pool, "rolls") + " 次）" + extra(pool, "bonus_rolls");
    }

    /** {@code rolls} / {@code count} 这类「数字或 {min,max}」的短写法。 */
    private static String decl(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null) return "1";
        if (v.isJsonPrimitive()) return v.getAsString();
        if (v.isJsonObject()) {
            JsonObject r = v.getAsJsonObject();
            return str(r, "min") + "~" + str(r, "max");
        }
        return "?";
    }

    private static String extra(JsonObject n, String... keys) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            JsonElement v = n.get(k);
            if (v == null || v.isJsonNull()) continue;
            String t = v.isJsonPrimitive() ? v.getAsString() : v.toString();
            if (t.length() > 18) t = t.substring(0, 18) + "…";
            b.append(" · ").append(t);
        }
        return b.toString();
    }

    // ---------- 小工具 ----------

    private static String str(JsonObject o, String k) {
        JsonElement v = o.get(k);
        return v == null || !v.isJsonPrimitive() ? "" : v.getAsString();
    }

    private static String shortId(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    private static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arrOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
