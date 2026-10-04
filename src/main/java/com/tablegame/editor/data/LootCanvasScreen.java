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
 * 战利品表画布：一张原版 loot_table 的节点图 —— 表 → 池 → 条目 → 修饰器 / 谓词，复合条目再往下嵌。
 * 画布机件全在 {@link GraphCanvas}，这里只给「取图 / 配色 / 右键菜单 / 双击 / 坐标存哪」。
 *
 * <p>编辑规矩：每步改完立刻用原版 codec 试解析（{@link GameEditorScreen#applyLoot} 既校验又落盘），
 * 不过就整张表就地回滚 + 红字说明 ⇒ 坏表进不了档。不认识的修饰器 / 谓词原样留着（只读，能删不能瞎改）。
 */
public class LootCanvasScreen extends GraphCanvas {
    private final Screen backTo;
    private final String table;
    private final JsonObject t;              // 表本体 = def 里那一份的**引用**（就地改）
    private String sel = "";                 // 当前操作的节点路径
    private String sub = "";                 // "" = 一级菜单；其它 = 展开的子菜单（条目/修饰器/谓词/字段）
    private boolean subPending;             // openSub 置位：这一次 fillMenu 是「展开二级」，其余一律从一级起
    private String status = "";
    private String bad = "";

    public LootCanvasScreen(Screen backTo, GameEditorScreen parent, String table) {
        super(Component.literal("战利品表 · " + table), parent, "掉落表");
        this.backTo = backTo;
        this.table = table;
        JsonElement e = parent.def.lootTables().get(table);
        this.t = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    // ---------- GraphCanvas 要的五个钩子 ----------

    @Override
    protected void rebuildGraph() {
        nodes.clear();
        edges.clear();
        ScriptGraph.Graph g = LootGraph.build(t);
        Map<String, Integer> depth = new HashMap<>();
        depth.put("table", 0);
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
                .resolve("tablegame/layout/loot-" + safe(table) + ".json");
    }

    private static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        return b.toString();
    }

    @Override
    protected int colorOf(int kind) {
        return switch (kind) {
            case LootGraph.KIND_TABLE -> 0xFFE0C060;
            case LootGraph.KIND_POOL -> 0xFF6FA8DC;
            case LootGraph.KIND_COMP -> 0xFFC58CE0;
            case LootGraph.KIND_FUNC -> 0xFFE08A5A;
            case LootGraph.KIND_COND -> 0xFF7FC7C7;
            case LootGraph.KIND_REF -> 0xFFC0C0C0;
            default -> 0xFF8CCF6F;
        };
    }

    /** 二级菜单的头（基类的菜单头**不可点**，所以只说实话：点别处退回一级）。 */
    @Override
    protected String menuHeader() {
        return sub.isEmpty() ? null : "二级：" + sub + "（右键别处退回一级）";
    }

    // ---------- 右键菜单：一级 = 这一格能加什么 / 删 / 改 ----------

    @Override
    protected void fillMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        if (subPending) {                                        // 展开二级（openSub 刚调的这一次）
            subPending = false;
            fillSub();
            return;
        }
        sub = "";                                                // ⚠ 右键新开的一级菜单：二级状态必须清掉（否则粘住）
        Node n = nodeAt(mx, my);
        sel = n == null ? "" : n.key;
        JsonObject node = sel.isEmpty() ? null : LootEdit.node(t, sel);
        if (node == null) {                                      // 空白：只有「新建池」与「改表的 type」
            menuLabels.add("＋ 新建池");
            menuActions.add(() -> edit(() -> LootEdit.addPool(t), "已加一个池"));
            menuLabels.add("改这张表的 type（block / entity / …）");
            menuActions.add(this::promptTableType);
            return;
        }
        if (node.has("entries") || LootEdit.isComposite(node)) {  // 池 / 复合条目：能加子条目
            menuLabels.add("＋ 条目 ▸");
            menuActions.add(() -> openSub("条目", mx, my));
        }
        // 顺序 / 搬池：原版表的「先后」就是数组顺序，故用 ↑↓ 改顺序、「搬进别的容器」搬条目 —— 不造拖线机制。
        JsonObject parentNode = LootEdit.node(t, LootEdit.parentPath(sel));
        boolean seqParent = parentNode != null && LootEdit.idOf(parentNode).endsWith("sequence");
        boolean ordered = sel.contains(".entries[") || (seqParent && sel.contains(".children["));
        if (ordered && LootEdit.hasSibling(t, sel)) {
            menuLabels.add("↑ 上移");
            menuActions.add(() -> edit(() -> LootEdit.move(t, sel, -1), "已上移"));
            menuLabels.add("↓ 下移");
            menuActions.add(() -> edit(() -> LootEdit.move(t, sel, +1), "已下移"));
        }
        if (LootEdit.isEntry(node) && containers().size() > 1) {
            menuLabels.add("搬进别的容器 ▸");                       // 池 或 复合条目（分支/组/顺序）都行
            menuActions.add(() -> openSub("搬进", mx, my));
        }

        menuLabels.add("＋ 修饰器 ▸");
        menuActions.add(() -> openSub("修饰器", mx, my));
        menuLabels.add("＋ 谓词 ▸");
        menuActions.add(() -> openSub("谓词", mx, my));
        menuLabels.add("✎ 改这一格的字段");
        menuActions.add(() -> openSub("字段", mx, my));
        menuLabels.add("✕ 删掉这一格");
        menuActions.add(() -> edit(() -> LootEdit.remove(t, sel), "已删掉一格"));
    }

    /**
     * 二级菜单：把一长串选项收进一层（一级菜单一行 16px，二十多种修饰器/谓词平铺会顶出屏幕）。
     * 「返回上一级」＝ 基类画菜单头（{@link #menuHeader()}）的位置就是它。
     */
    private void fillSub() {
        if ("条目".equals(sub)) {
            for (LootEdit.Kind k : LootEdit.ENTRY_KINDS) {
                String id = k.id();
                menuLabels.add("＋ " + k.label());
                menuActions.add(() -> edit(() -> LootEdit.addEntry(t, sel, id), "已加条目：" + k.label()));
            }
        } else if ("修饰器".equals(sub)) {
            for (LootEdit.Kind k : LootEdit.FUNC_KINDS) {
                String id = k.id();
                menuLabels.add("＋ " + k.label() + "（" + id + "）");
                menuActions.add(() -> edit(() -> LootEdit.addFunction(t, sel, id), "已加修饰器：" + k.label()));
            }
        } else if ("谓词".equals(sub)) {
            for (LootEdit.Kind k : LootEdit.COND_KINDS) {
                String id = k.id();
                menuLabels.add("＋ " + k.label() + "（" + id + "）");
                menuActions.add(() -> edit(() -> LootEdit.addCondition(t, sel, id), "已加谓词：" + k.label()));
            }
        } else if ("搬进".equals(sub)) {
            for (String[] c : containers()) {
                if (c[0].equals(LootEdit.parentPath(sel))) continue;  // 现在待的那个不列
                if (c[0].startsWith(sel + ".")) continue;             // 自己肚子里不列（会成环）
                menuLabels.add("→ " + c[1]);
                String to = c[0];
                menuActions.add(() -> edit(() -> LootEdit.moveTo(t, sel, to), "已搬进 " + c[1]));
            }
        } else if ("字段".equals(sub)) {
            JsonObject node = LootEdit.node(t, sel);
            if (node == null) return;
            for (String key : new ArrayList<>(node.keySet())) {
                String cur = shortOf(node.get(key));
                menuLabels.add(key + " = " + cur);
                menuActions.add(() -> promptField(key, node.get(key)));
            }
            menuLabels.add("＋ 加一格字段 …");
            menuActions.add(() -> promptField("", null));
        }
    }

    /**
     * 能装条目的容器：池 + 复合条目（分支 / 组 / 顺序）—— 搬条目时当目标；显示名取 {@link LootGraph} 的节点行。
     */
    private List<String[]> containers() {
        List<String[]> out = new ArrayList<>();
        for (com.tablegame.script.edit.ScriptGraph.Node n : LootGraph.build(t).nodes()) {
            if (n.kind() == LootGraph.KIND_POOL || n.kind() == LootGraph.KIND_COMP) {
                out.add(new String[] { n.key(), n.label() });
            }
        }
        return out;
    }

    private void openSub(String which, double mx, double my) {
        sub = which;
        subPending = true;
        openMenu(mx, my);                                        // 复用基类菜单框架（点第二次也走它）
    }

    @Override
    protected boolean onDoubleClick(double mx, double my) {
        Node n = nodeAt(mx, my);
        if (n == null) return false;
        sel = n.key;
        openSub("字段", mx, my);
        return true;
    }

    // ---------- 改字段 ----------

    /** 点一行字段 → 短输入框 → 写回。数字 / true|false / JSON 原样解，其它当字符串（`minecraft:iron_ingot` 这种就是字符串）。 */
    private void promptField(String key, JsonElement cur) {
        String init = cur == null ? "" : (cur.isJsonPrimitive() ? cur.getAsString() : cur.toString());
        String title = key.isEmpty() ? "加一格字段" : "改字段 " + key;
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, title,
                key.isEmpty() ? "写成 键=值（例：weight=3 / name=stone）" : "值（留空 = 删掉这格；物品/方块/实体可以写本项目的资产名）",
                key.isEmpty() ? "" : init, text -> {
            String v = text == null ? "" : text.trim();
            if (key.isEmpty()) {                                  // 「键=值」
                int eq = v.indexOf('=');
                if (eq <= 0) return;
                writeField(v.substring(0, eq).trim(), v.substring(eq + 1).trim());
            } else if (v.isEmpty()) {
                edit(() -> LootEdit.unset(t, sel, key), "已删掉字段 " + key);
            } else {
                writeField(key, v);
            }
        }));
    }

    private void writeField(String key, String raw) {
        JsonElement v;
        try {
            v = JsonParser.parseString(raw);
        } catch (Exception e) {
            v = new com.google.gson.JsonPrimitive(raw);           // 不是 JSON（物品 id 这类）= 就当字符串
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && raw.startsWith("\"") && raw.endsWith("\"")) {
            v = new com.google.gson.JsonPrimitive(raw.substring(1, raw.length() - 1));
        }
        JsonElement val = v;
        edit(() -> LootEdit.set(t, sel, key, val), "已写 " + key + " = " + raw);
    }

    private void promptTableType() {
        String cur = t.has("type") ? t.get("type").getAsString() : "";
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "表的最外层 type",
                "minecraft:block（方块）/ minecraft:entity（生物）/ minecraft:generic",
                cur, text -> {
            String v = text == null ? "" : text.trim();
            if (v.isEmpty()) return;
            String full = v.contains(":") ? v : "minecraft:" + v;
            edit(() -> t.addProperty("type", full), "表 type = " + full);
        }));
    }

    // ---------- 改一步：校验 → 不过就整张回滚 ----------

    private void edit(Runnable op, String note) {
        String before = t.toString();
        op.run();
        if (t.toString().equals(before)) {                        // 什么也没动（如已在最前还点「上移」）——
            bad = "";                                            // 不许撒谎说「已上移」
            status = "这一步没有可改的（例如它已经在最前 / 最后了）";
            Minecraft.getInstance().setScreen(new LootCanvasScreen(backTo, parent, table));
            return;
        }
        String err = parent.applyLoot(table, t.toString());       // 校验 + 落盘（不过就不改盘）
        if (!err.isEmpty()) {
            JsonObject back = JsonParser.parseString(before).getAsJsonObject();
            for (String k : new ArrayList<>(t.keySet())) t.remove(k);
            for (Map.Entry<String, JsonElement> e : back.entrySet()) t.add(e.getKey(), e.getValue());
            bad = err;
            status = "";
        } else {
            bad = "";
            status = note;
        }
        Minecraft.getInstance().setScreen(new LootCanvasScreen(backTo, parent, table));  // 重画面布（坐标走旁路文件）
    }

    private static String shortOf(JsonElement e) {
        if (e == null) return "";
        String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
        return s.length() > 22 ? s.substring(0, 22) + "…" : s;
    }

    // ---------- 画面 ----------

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(DrawBoardMenuUi.button(4, 6, 78, 18, "‹ 表清单", this::back));
    }

    private void back() {
        Minecraft.getInstance().setScreen(backTo != null ? backTo : new VanillaDataScreen(parent));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        g.centeredText(font, Component.literal("战利品表 · " + table + "　（原版 loot_table 的节点图）"),
                EditorRail.cx(this), 10, 0xFFFFFFFF);
        if (!bad.isEmpty()) {
            g.text(font, bad.length() > 150 ? bad.substring(0, 150) + "…" : bad, 8, 24, 0xFFE08080);
        } else if (!status.isEmpty()) {
            g.text(font, status, 8, 24, 0xFF90C090);
        }
        drawCanvasBody(g, mouseX, mouseY);       // ← 少了这一句就「进去什么都没有」（节点/连线/菜单都在这）
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, "右键 = 加/删/改（表 / 池 / 条目 / 修饰器 / 谓词）· 双击节点 = 改它的字段 · 拖节点 / 滚轮缩放",
                8, height - 14, 0xFF909090);
    }
}
