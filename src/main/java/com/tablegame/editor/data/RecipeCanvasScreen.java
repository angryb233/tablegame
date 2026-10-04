package com.tablegame.editor.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.GraphCanvas;
import com.tablegame.editor.NamePromptScreen;

/**
 * 配方画布：一张原版 recipe 的节点图（配方 → 每格用料 + 产物）。画布机件全在 {@link GraphCanvas}，
 * 这里只给：取图 / 配色 / 右键菜单 / 双击 / 坐标存哪。
 *
 * <p>编辑规矩同战利品表画布：每步改完立刻用原版 codec 试解析（{@link GameEditorScreen#applyRecipe}
 * 既校验又落盘），解不过就整张回滚 + 红字。⚠ 图样（3×3）是二维的，节点框塞不下 ⇒ 底栏显示原文，
 * 改它走菜单里的「✎ 改图样」。
 */
public class RecipeCanvasScreen extends GraphCanvas {
    private final Screen backTo;
    private final String name;
    private final JsonObject r;            // 配方本体 = def 里那一份的**引用**（就地改）
    private String sel = "";
    private String status = "";
    private String bad = "";

    RecipeCanvasScreen(Screen backTo, GameEditorScreen parent, String name) {
        super(Component.literal("配方 · " + name), parent, "配方");
        this.backTo = backTo;
        this.name = name;
        JsonElement e = parent.def.recipes().get(name);
        this.r = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    // ---------- GraphCanvas 要的五个钩子 ----------

    @Override
    protected void rebuildGraph() {
        nodes.clear();
        edges.clear();
        ScriptGraph.Graph g = RecipeGraph.build(r);
        Map<String, Integer> depth = new HashMap<>();
        depth.put("recipe", 0);
        int i = 0;
        for (ScriptGraph.Node n : g.nodes()) {
            int d = depth.getOrDefault(n.key(), 0);
            nodes.add(new Node(n.key(), n.label(), n.line(), n.kind(), 40 + d * 235, 34 + (i % 11) * 46));
            for (ScriptGraph.Edge e : g.edges()) {
                if (e.from().equals(n.key())) depth.put(e.to(), d + 1);
            }
            i++;
        }
        for (ScriptGraph.Edge e : g.edges()) {
            edges.add(new Edge(e.from(), e.to(), e.cond(), e.condText(), e.line()));
        }
    }

    @Override
    protected Path layoutFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("tablegame/layout/recipe-" + safe(name) + ".json");
    }

    private static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        return b.toString();
    }

    @Override
    protected int colorOf(int kind) {
        return switch (kind) {
            case RecipeGraph.KIND_RECIPE -> 0xFFE0C060;
            case RecipeGraph.KIND_ING -> 0xFF6FA8DC;
            default -> 0xFF8CCF6F;
        };
    }

    // ---------- 右键菜单 ----------

    @Override
    protected void fillMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        Node n = nodeAt(mx, my);
        sel = n == null ? "" : n.key;
        if (isIngredient(sel)) {                                             // 一格用料
            menuLabels.add("✎ 改这一格的物品（写资产名 = 我那条物品）");
            menuActions.add(() -> promptIngredient(sel));
            menuLabels.add("✕ 删这一格");
            menuActions.add(() -> edit(() -> RecipeEdit.removeIngredient(r, sel), "已删掉一格用料"));
            return;
        }
        if (sel.equals("result")) {                                          // 产物
            menuLabels.add("✎ 改产物（写资产名 = 我那条物品）");
            menuActions.add(this::promptResult);
            menuLabels.add("✎ 改产物数量");
            menuActions.add(this::promptCount);
            return;
        }
        // 空白 / 配方自己
        if (RecipeEdit.isShaped(r)) {
            menuLabels.add("✎ 改图样（多行文本，每行一格）");
            menuActions.add(this::promptPattern);
        }
        menuLabels.add("＋ 加一格材料");
        menuActions.add(this::addIngredient);
        menuLabels.add("✎ 改产物");
        menuActions.add(this::promptResult);
        menuLabels.add("✎ 改产物数量");
        menuActions.add(this::promptCount);
    }

    private static boolean isIngredient(String key) {
        return key.startsWith("key[") || key.startsWith("ingredients[");
    }

    @Override
    protected boolean onDoubleClick(double mx, double my) {
        Node n = nodeAt(mx, my);
        if (n == null) return false;
        sel = n.key;
        if (sel.equals("result")) {
            promptResult();
        } else if (isIngredient(sel)) {
            promptIngredient(sel);
        } else if (RecipeEdit.isShaped(r)) {
            promptPattern();
        } else {
            return false;
        }
        return true;
    }

    // ---------- 改字段（都走 commit：校验 + 落盘 + 不过就回滚）----------

    private void promptIngredient(String path) {
        String cur = RecipeEdit.ingredientText(r, path);
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "这一格放什么",
                "物品 id / 本项目的资产名；逗号分隔 = 任选其一（如 钻石,金錠）· #标签 认标签", cur,
                text -> {
            String v = text == null ? "" : text.trim();
            if (v.isEmpty()) return;
            edit(() -> RecipeEdit.setIngredient(r, path, v), "已写 " + path + " = " + v);
        }));
    }

    private void promptPattern() {
        String cur = RecipeEdit.patternText(r).replace("\n", "\\n");
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "图样",
                "多行：每行一格（最多 3 行 / 3 列）；符号要与「材料」里的一致，用 \\n 分行（例：##\\n#X）", cur,
                text -> {
            String v = (text == null ? "" : text).replace("\\n", "\n");
            String before = r.toString();
            String err = RecipeEdit.writePattern(r, v);
            if (!err.isEmpty()) {
                commit(before, null, err);
                return;
            }
            commit(before, "图样已改", "");
        }));
    }

    private void promptResult() {
        JsonObject res = RecipeEdit.result(r);
        String cur = res != null && res.has("id") ? res.get("id").getAsString() : "";
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "产物",
                "写本项目的资产名（如 催化剂）= 产出我那条物品；写 minecraft:xxx = 出原版东西", cur,
                text -> {
            String v = text == null ? "" : text.trim();
            if (v.isEmpty()) return;
            edit(() -> RecipeEdit.setResultId(r, v), "产物 = " + v);
        }));
    }

    private void promptCount() {
        JsonObject res = RecipeEdit.result(r);
        String cur = res != null && res.has("count") ? res.get("count").getAsString() : "1";
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "产物数量",
                "1 ~ 99（一次合成出几个）", cur,
                text -> {
            String before = r.toString();
            if (!RecipeEdit.setResultCount(r, text)) {
                commit(before, null, "数量要写 1 ~ 99");
                return;
            }
            commit(before, "产物数量已改", "");
        }));
    }

    private void addIngredient() {
        String before = r.toString();
        String p = RecipeEdit.addIngredient(r);
        if (p.isEmpty()) {
            commit(before, null, RecipeEdit.isShaped(r) ? "图样满了（最多 3×3）" : "加不了");
            return;
        }
        commit(before, "已加一格材料（默认铁錠）", "");
    }

    /** 改一步：跑 op → 校验 + 落盘；解不过就整张回滚 + 红字。 */
    private void edit(Runnable op, String note) {
        String before = r.toString();
        op.run();
        if (r.toString().equals(before)) {                        // 什么也没动（如最后一格不让删）——
            commit(before, null, "这一步没有可改的（比如只剩一格材料）");
            return;
        }
        commit(before, note, "");
    }

    /** 收尾：确定要写什么（err 非空 = 拒收并回滚）。 */
    private void commit(String before, String note, String err) {
        String why = err == null ? "" : err;
        if (why.isEmpty() && note != null) why = parent.applyRecipe(name, r.toString());
        if (!why.isEmpty()) {
            JsonObject back = JsonParser.parseString(before).getAsJsonObject();
            for (String k : new ArrayList<>(r.keySet())) r.remove(k);
            for (Map.Entry<String, JsonElement> e : back.entrySet()) r.add(e.getKey(), e.getValue());
            bad = why;
            status = "";
        } else {
            bad = "";
            status = note == null ? "" : note;
        }
        Minecraft.getInstance().setScreen(new RecipeCanvasScreen(backTo, parent, name));
    }

    // ---------- 画面 ----------

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(DrawBoardMenuUi.button(4, 6, 78, 18, "‹ 配方清单", this::back));
    }

    private void back() {
        Minecraft.getInstance().setScreen(backTo != null ? backTo
                : new VanillaDataScreen(parent, VanillaDataScreen.TAB_RECIPE));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        g.centeredText(font, Component.literal("配方 · " + name + "　（原版 recipe 的节点图）"),
                EditorRail.cx(this), 10, 0xFFFFFFFF);
        if (!bad.isEmpty()) {
            g.text(font, bad.length() > 150 ? bad.substring(0, 150) + "…" : bad, 8, 24, 0xFFE08080);
        } else if (!status.isEmpty()) {
            g.text(font, status, 8, 24, 0xFF90C090);
        }
        drawCanvasBody(g, mouseX, mouseY);       // 关键：节点/连线/菜单都靠这一句画出来
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        String pat = RecipeEdit.patternText(r).replace("\n", " / ");
        g.text(font, "图样（每行一格、空格 = 空位、/ 是换行）：" + (pat.isEmpty() ? "（无序配方没有图样）" : pat),
                8, height - 28, 0xFF909090);
        g.text(font, "右键 = 加/删/改 · 双击节点 = 改它 · 拖节点 / 滚轮缩放", 8, height - 14, 0xFF909090);
    }
}
