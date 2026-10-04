package com.tablegame.editor.data;

import com.google.gson.JsonObject;
import com.tablegame.script.edit.ScriptGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * 原版配方 → 画布图（{@code recipe} JSON → {@link ScriptGraph.Graph}）。
 *
 * <p>节点 key = {@link RecipeEdit} 的路径（改配方就按它定位）：{@code recipe}（配方自己）·
 * {@code key[#]} / {@code ingredients[0]}（一格用料）· {@code result}（产物）。
 * 连线 = 「这条配方要用什么、出什么」—— 配方是根，用料与产物都是子节点。
 *
 * <p>⚠ 图样（3×3 那几行）是二维的，节点框塞不下 ⇒ 节点上只给尺寸（{@code 2×1}），
 * 图样原文在画布底栏一行显示（{@link RecipeCanvasScreen}）。
 */
public final class RecipeGraph {
    public static final int KIND_RECIPE = 0, KIND_ING = 1, KIND_RESULT = 2;

    public static ScriptGraph.Graph build(JsonObject r) {
        List<ScriptGraph.Node> ns = new ArrayList<>();
        List<ScriptGraph.Edge> es = new ArrayList<>();
        String root = "recipe";
        String shape = RecipeEdit.patternText(r).isEmpty() ? ""
                : "　图样 " + sizeOf(RecipeEdit.patternText(r));
        ns.add(new ScriptGraph.Node(root, "配方 · " + RecipeEdit.typeLabel(r) + shape, 0, KIND_RECIPE));
        for (String[] p : RecipeEdit.ingredientPaths(r)) {
            ns.add(new ScriptGraph.Node(p[0], "◻ " + p[1] + " = " + shortOf(RecipeEdit.ingredientText(r, p[0])),
                    0, KIND_ING));
            es.add(new ScriptGraph.Edge(root, p[0], false, "", 0));
        }
        ns.add(new ScriptGraph.Node("result", "★ 产出 · " + shortOf(RecipeEdit.resultText(r)), 0, KIND_RESULT));
        es.add(new ScriptGraph.Edge(root, "result", false, "", 0));
        return new ScriptGraph.Graph(ns, es);
    }

    /** 图样尺寸（{@code "##\\n#X"} → {@code 2×2}）。 */
    private static String sizeOf(String pattern) {
        String[] rows = pattern.split("\\R");
        int w = rows.length == 0 ? 0 : rows[0].strip().length();
        return w + "×" + rows.length;
    }

    private static String shortOf(String s) {
        String t = s == null ? "" : s;
        return t.length() > 26 ? t.substring(0, 26) + "…" : t;
    }
}
