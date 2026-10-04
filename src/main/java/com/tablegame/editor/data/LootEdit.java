package com.tablegame.editor.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.pack.AssetStore;

/**
 * 原版战利品表（{@code loot_table} JSON）的增删改算子，只懂 JSON 不懂界面（对应脚本的 ScriptEdit）。
 *
 * <p>节点用路径指名（画布节点 key 即它）：{@code pools[0]} · {@code pools[0].entries[2]} ·
 * {@code pools[0].entries[2].functions[0]} · {@code pools[0].entries[2].conditions[1]} ·
 * 复合条目的子里 {@code pools[0].entries[1].children[0]}（可再嵌，路径一路点下去）。
 *
 * <p>规矩：不认识的东西一律原样留着 —— 表里有没做 UI 的修饰器 / 谓词（原版各约 40 / 30 种）时，
 * 编辑别的格子不许把它弄丢；{@link Kind} 表只列「能新建」的几种，其余原样透传。
 */
public final class LootEdit {
    private LootEdit() {}

    /** 物品身份标签的键（`custom_data` 里）：{@link HostManager#customItemStack} 写它，战利品表这边见 {@link #toVanilla}。 */
    public static final String ASSET_TAG = "tg_asset";

    /** 一种可新建的节点：原版 id（无命名空间）· 菜单文案 · 新节点默认 JSON。 */
    public record Kind(String id, String label, String json) { }

    /** 池里能新建的条目（原版 8 种抽取项取常用的 7 种）。 */
    public static final Kind[] ENTRY_KINDS = {
            new Kind("item", "物品", "{\"type\":\"minecraft:item\",\"name\":\"minecraft:stone\",\"weight\":1}"),
            new Kind("tag", "标签", "{\"type\":\"minecraft:tag\",\"name\":\"minecraft:stone_bricks\",\"weight\":1}"),
            new Kind("loot_table", "套表", "{\"type\":\"minecraft:loot_table\",\"value\":\"minecraft:empty\"}"),
            new Kind("empty", "空", "{\"type\":\"minecraft:empty\"}"),
            new Kind("group", "组·全都要", "{\"type\":\"minecraft:group\",\"children\":[]}"),
            new Kind("alternatives", "分支·第一个过的", "{\"type\":\"minecraft:alternatives\",\"children\":[]}"),
            new Kind("sequence", "顺序·依次", "{\"type\":\"minecraft:sequence\",\"children\":[]}"),
    };

    /** 能新建的修饰器（原版 ~40 种的常用这批；其余出现时原样保留 + 只读）。 */
    public static final Kind[] FUNC_KINDS = {
            new Kind("set_count", "设数量", "{\"function\":\"minecraft:set_count\",\"count\":1}"),
            new Kind("set_damage", "设耐久", "{\"function\":\"minecraft:set_damage\",\"damage\":1.0}"),
            new Kind("set_name", "设名字", "{\"function\":\"minecraft:set_name\",\"name\":\"\"}"),
            new Kind("set_lore", "设描述", "{\"function\":\"minecraft:set_lore\",\"lore\":[]}"),
            new Kind("set_components", "设组件", "{\"function\":\"minecraft:set_components\",\"components\":{}}"),
            new Kind("enchant_randomly", "随机附魔", "{\"function\":\"minecraft:enchant_randomly\"}"),
            new Kind("enchant_with_levels", "按等级附魔", "{\"function\":\"minecraft:enchant_with_levels\",\"levels\":30}"),
            new Kind("apply_bonus", "时运加成", "{\"function\":\"minecraft:apply_bonus\",\"enchantment\":\"minecraft:fortune\",\"formula\":\"minecraft:ore_drops\"}"),
            new Kind("looting_enchant", "抢夺加成", "{\"function\":\"minecraft:looting_enchant\",\"count\":{\"min\":0,\"max\":1}}"),
            new Kind("limit_count", "压数量", "{\"function\":\"minecraft:limit_count\",\"limit\":{\"min\":0,\"max\":1}}"),
            new Kind("furnace_smelt", "烧炼产物", "{\"function\":\"minecraft:furnace_smelt\"}"),
            new Kind("explosion_decay", "爆炸衰减", "{\"function\":\"minecraft:explosion_decay\"}"),
    };

    /** 能新建的谓词（原版 ~30 种里的常用这批）。 */
    public static final Kind[] COND_KINDS = {
            new Kind("random_chance", "概率", "{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}"),
            new Kind("random_chance_with_looting", "概率·受抢夺影响", "{\"condition\":\"minecraft:random_chance_with_looting\",\"chance\":0.5,\"looting_multiplier\":0.1}"),
            new Kind("survives_explosion", "抗爆炸销毁", "{\"condition\":\"minecraft:survives_explosion\"}"),
            new Kind("match_tool", "工具匹配", "{\"condition\":\"minecraft:match_tool\",\"predicate\":{}}"),
            new Kind("entity_properties", "实体属性", "{\"condition\":\"minecraft:entity_properties\",\"entity\":\"this\",\"predicate\":{}}"),
            new Kind("block_state_property", "方块状态", "{\"condition\":\"minecraft:block_state_property\",\"block\":\"minecraft:stone\",\"properties\":{}}"),
            new Kind("killed_by_player", "玩家击杀", "{\"condition\":\"minecraft:killed_by_player\"}"),
            new Kind("table_bonus", "附魔等级概率", "{\"condition\":\"minecraft:table_bonus\",\"enchantment\":\"minecraft:fortune\",\"chances\":[0.1]}"),
            new Kind("inverted", "取反", "{\"condition\":\"minecraft:inverted\",\"term\":{\"condition\":\"minecraft:survives_explosion\"}}"),
            new Kind("all_of", "全部满足", "{\"condition\":\"minecraft:all_of\",\"terms\":[]}"),
            new Kind("any_of", "任一满足", "{\"condition\":\"minecraft:any_of\",\"terms\":[]}"),
    };

    /** 节点的「是什么」原版 id：修饰器看 {@code function} · 谓词看 {@code condition} · 条目看 {@code type}。 */
    public static String idOf(JsonObject n) {
        for (String k : new String[] { "function", "condition", "type" }) {
            if (n.has(k) && n.get(k).isJsonPrimitive()) return n.get(k).getAsString();
        }
        return "";
    }

    /** id 的中文标签（菜单 / 节点行用）；不认识的 id 给短名。 */
    public static String labelOf(String id) {
        for (Kind[] all : new Kind[][] { ENTRY_KINDS, FUNC_KINDS, COND_KINDS }) {
            for (Kind k : all) if (id.endsWith(":" + k.id()) || id.equals(k.id())) return k.label();
        }
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    /** 复合条目（有 children 的那三种）。 */
    public static boolean isComposite(JsonObject n) {
        String id = idOf(n);
        return id.endsWith("group") || id.endsWith("alternatives") || id.endsWith("sequence");
    }

    // ---------- 读 ----------

    /** 路径解出的「一格」：容器（对象或数组）+ 格名（对象按 key、数组按下标）。 */
    private record Hit(JsonElement holder, String key, int idx) { }

    /** 解路径；解不到 = null。 */
    private static Hit hit(JsonObject root, String path) {
        if (path == null || path.isEmpty()) return null;
        String[] segs = path.split("\\.");
        JsonElement cur = root;
        for (int s = 0; s < segs.length; s++) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject o = cur.getAsJsonObject();
            String seg = segs[s];
            int br = seg.indexOf('[');
            String key = br < 0 ? seg : seg.substring(0, br);
            int idx = br < 0 ? -1 : Integer.parseInt(seg.substring(br + 1, seg.length() - 1));
            if (s == segs.length - 1) return new Hit(o, key, idx);
            JsonElement nxt = o.get(key);
            if (idx < 0) {
                cur = nxt;
            } else {
                if (nxt == null || !nxt.isJsonArray() || idx >= nxt.getAsJsonArray().size()) return null;
                cur = nxt.getAsJsonArray().get(idx);
            }
        }
        return null;
    }

    /** 路径指到的东西（读：参数卡 / 只读卡用）。 */
    public static JsonElement at(JsonObject root, String path) {
        Hit h = hit(root, path);
        if (h == null) return null;
        if (h.idx() < 0) return h.holder().getAsJsonObject().get(h.key());
        JsonElement c = h.holder().getAsJsonObject().get(h.key());   // holder 是父对象，先取它才是数组
        if (c == null || !c.isJsonArray()) return null;
        JsonArray a = c.getAsJsonArray();
        return h.idx() < a.size() ? a.get(h.idx()) : null;
    }

    /** 路径指到的节点（必须是对象）；不是 = null。 */
    public static JsonObject node(JsonObject root, String path) {
        JsonElement e = at(root, path);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** 路径的父路径（删完节点后 UI 要重新选中）。 */
    public static String parentPath(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(0, i);
    }

    // ---------- 改 ----------

    /** 新建一个池（{@code rolls: 1} + 空 {@code entries}）。返回新节点路径。 */
    public static String addPool(JsonObject table) {
        JsonArray pools = arr(table, "pools");
        JsonObject p = JsonParser.parseString("{\"rolls\":1,\"entries\":[]}").getAsJsonObject();
        pools.add(p);
        return "pools[" + (pools.size() - 1) + "]";
    }

    /** 在池 / 复合条目下加一个条目（id 用 {@link #ENTRY_KINDS}）。返回新节点路径。 */
    public static String addEntry(JsonObject table, String parentPath, String id) {
        JsonObject parent = node(table, parentPath);
        if (parent == null) return null;
        return append(table, parentPath, containerKey(parent), kindJson(ENTRY_KINDS, id));
    }

    /** 给某个节点（池 / 条目）加一个修饰器。返回新节点路径。 */
    public static String addFunction(JsonObject table, String nodePath, String id) {
        return append(table, nodePath, "functions", kindJson(FUNC_KINDS, id));
    }

    /** 给某个节点（池 / 条目）加一个谓词。返回新节点路径。 */
    public static String addCondition(JsonObject table, String nodePath, String id) {
        return append(table, nodePath, "conditions", kindJson(COND_KINDS, id));
    }

    /** 往 {@code parentPath} 的 {@code key} 数组尾部塞一个节点。返回新节点路径。 */
    private static String append(JsonObject table, String parentPath, String key, JsonObject fresh) {
        JsonObject parent = node(table, parentPath);              // 家长可以是数组元素（pools[1]）
        if (parent == null) return null;
        JsonArray a = arr(parent, key);
        a.add(fresh);
        return parentPath + "." + key + "[" + (a.size() - 1) + "]";
    }

    /** 删掉路径指到的那一格（池 / 条目 / 修饰器 / 谓词；也用于删字段）。返回父路径。 */
    public static String remove(JsonObject table, String path) {
        Hit h = hit(table, path);
        if (h == null) return path;
        if (h.idx() < 0) {
            h.holder().getAsJsonObject().remove(h.key());
        } else {
            JsonElement c = h.holder().getAsJsonObject().get(h.key());
            if (c != null && c.isJsonArray()) c.getAsJsonArray().remove(h.idx());
        }
        return parentPath(path);
    }

    /** 给路径指到的节点写一格字段（节点可以是数组元素，如 {@code pools[0]}）。 */
    public static void set(JsonObject table, String path, String key, JsonElement v) {
        JsonObject n = node(table, path);
        if (n != null) n.add(key, v);
    }

    /** 去掉一格格子（不存在就算了）。 */
    public static void unset(JsonObject table, String path, String key) {
        JsonObject n = node(table, path);
        if (n != null) n.remove(key);
    }

    /** 不改路径的一条：在原数组里交换两格（顺序编辑用）。 */
    public static void swap(JsonObject table, String path, int a, int b) {
        Hit h = hit(table, path);
        if (h == null || h.idx() >= 0) return;
        JsonElement e = h.holder().getAsJsonObject().get(h.key());
        if (e == null || !e.isJsonArray()) return;
        JsonArray arr = e.getAsJsonArray();
        if (a < 0 || b < 0 || a >= arr.size() || b >= arr.size()) return;
        JsonElement tmp = arr.get(a);
        arr.set(a, arr.get(b));
        arr.set(b, tmp);
    }

    /**
     * 资产名（段头那个）→ 我们的对象：物品 / 方块 / 实体都算（文本对象没有基底，不算）。
     * 只认资产名不认显示名 —— 写错（如显示名 `&f&l铁锭`）原版 codec 当场解析不过、保存报错，不会静默掉成原版物品。
     */
    public static GameDefinition.AssetDef assetByName(java.util.List<GameDefinition.AssetDef> assets, String name) {
        if (assets == null || name == null || name.isEmpty()) return null;
        for (GameDefinition.AssetDef a : assets) {
            if (a.base() == null || a.base().isEmpty()) continue;            // 文本对象没有基底
            if (!AssetStore.KIND_ITEM.equals(a.kind()) && !AssetStore.KIND_BLOCK.equals(a.kind())
                    && !AssetStore.KIND_ENTITY.equals(a.kind())) continue;
            if (name.equals(a.ref())) return a;
        }
        return null;
    }

    /**
     * 表 → 原版能解的表（编译期改写；返回新对象，不动原表 —— 编辑器里那份还是作者写的资产名）。
     * 物品额外注入身份标记（set_components + custom_data.tg_asset）：掉落时按它重建；方块/实体只是被匹配，改名就够。
     */
    public static JsonObject toVanilla(JsonObject table, java.util.List<GameDefinition.AssetDef> assets) {
        JsonObject out = table.deepCopy();
        rewrite(out, assets);
        return out;
    }

    private static void rewrite(JsonElement e, java.util.List<GameDefinition.AssetDef> assets) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) rewrite(c, assets);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        String id = idOf(o);
        if (o.has("name") && id.endsWith("item")) {                          // ① 掉什么东西
            JsonElement nm = o.get("name");
            if (nm.isJsonPrimitive()) {
                GameDefinition.AssetDef a = assetByName(assets, nm.getAsString());
                if (a != null) {
                    o.addProperty("name", a.base());
                    markAsAsset(o, a.ref());
                }
            }
        }
        if (o.has("condition")) {                                            // ② 谓词里的方块 / 实体 / 工具
            if (id.endsWith("block_state_property")) swap(o, "block", assets);
            if (id.endsWith("entity_properties")) {
                JsonElement pr = o.get("predicate");
                if (pr != null && pr.isJsonObject()) swap(pr.getAsJsonObject(), "type", assets);
            }
            if (id.endsWith("match_tool")) {
                JsonElement pr = o.get("predicate");
                if (pr != null && pr.isJsonObject()) {
                    String ref = swapList(pr.getAsJsonObject(), "items", assets);    // ① 资产名 → base
                    if (ref != null) markPredicate(pr.getAsJsonObject(), ref);      // ② 再钉上身份谓词
                }
            }
        }
        for (String k : new java.util.ArrayList<>(o.keySet())) rewrite(o.get(k), assets);
    }

    /** 一格里写的是资产名 → 换成 base；**顺带回那个资产名**（调用方要拿它钉身份谓词）。 */
    private static String swap(JsonObject o, String key, java.util.List<GameDefinition.AssetDef> assets) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return null;
        GameDefinition.AssetDef a = assetByName(assets, v.getAsString());
        if (a == null) return null;
        o.addProperty(key, a.base());
        return a.ref();
    }

    /** 一格清单里写的是资产名 → 换成 base（`match_tool.predicate.items`，字符串或数组都认），顺带回第一个命中的资产名。 */
    private static String swapList(JsonObject o, String key, java.util.List<GameDefinition.AssetDef> assets) {
        JsonElement v = o.get(key);
        if (v == null) return null;
        if (v.isJsonPrimitive()) {
            return swap(o, key, assets);
        } else if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            String first = null;
            for (int i = 0; i < a.size(); i++) {
                JsonElement c = a.get(i);
                if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString()) continue;
                GameDefinition.AssetDef hit = assetByName(assets, c.getAsString());
                if (hit != null) {
                    a.set(i, new com.google.gson.JsonPrimitive(hit.base()));
                    if (first == null) first = hit.ref();
                }
            }
            return first;
        }
        return null;
    }

    /**
     * 给 `match_tool` 的谓词钉上身份标记：光换成 base 会匹配「任何同 base 的物品」（原版钻石也满足），
     * 这里再补 predicate.components.custom_data.tg_asset = 资产名 ⇒ 原版只认我们那一条。
     * ⚠ 只有物品有身份标记（方块/实体只是改名）⇒ 只有 `match_tool` 写谓词；配方材料不支持组件谓词，别在这儿找。
     */
    private static void markPredicate(JsonObject predicate, String ref) {
        JsonObject comps = predicate.has("components") && predicate.get("components").isJsonObject()
                ? predicate.getAsJsonObject("components") : new JsonObject();
        JsonObject cd = comps.has("custom_data") && comps.get("custom_data").isJsonObject()
                ? comps.getAsJsonObject("custom_data") : new JsonObject();
        cd.addProperty(ASSET_TAG, ref);
        comps.add("custom_data", cd);
        predicate.add("components", comps);
    }

    /** 给这条 item 注入身份标记：掉出来之后按它回填成我们的物品。 */
    private static void markAsAsset(JsonObject entry, String ref) {
        JsonObject fn = JsonParser.parseString(
                "{\"function\":\"minecraft:set_components\",\"components\":{\"custom_data\":{}}}").getAsJsonObject();
        fn.getAsJsonObject("components").getAsJsonObject("custom_data")
                .addProperty(ASSET_TAG, ref);
        arr(entry, "functions").add(fn);
    }

    /** 同层上移/下移（池条目、sequence 子条目都适用）：换的就是数组顺序 = 原版先后。 */
    public static boolean move(JsonObject table, String nodePath, int delta) {
        Hit h = hit(table, nodePath);
        if (h == null || h.idx() < 0) return false;
        JsonElement arrEl = h.holder().getAsJsonObject().get(h.key());
        if (arrEl == null || !arrEl.isJsonArray()) return false;
        JsonArray a = arrEl.getAsJsonArray();
        int to = h.idx() + delta;
        if (to < 0 || to >= a.size()) return false;
        JsonElement tmp = a.get(h.idx());
        a.set(h.idx(), a.get(to));
        a.set(to, tmp);
        return true;
    }

    /** 容器装子条目用的键：池 = {@code entries} · 复合条目 = {@code children}。 */
    public static String containerKey(JsonObject container) {
        return isComposite(container) ? "children" : "entries";
    }

    /** 把一个条目搬进另一只容器（池或复合条目；搬副本，原位删掉）。返回新路径；搬不了 = null。 */
    public static String moveTo(JsonObject table, String entryPath, String targetPath) {
        JsonObject entry = node(table, entryPath);
        JsonObject target = node(table, targetPath);
        if (entry == null || target == null || entry == target) return null;
        if (targetPath.startsWith(entryPath + ".")) return null;
        if (!isComposite(target) && !target.has("entries")) return null;
        String np = append(table, targetPath, containerKey(target), entry.deepCopy());
        if (np != null) remove(table, entryPath);
        return np;
    }

    /** 这个节点有兄弟吗（同层数组里多于一个）—— 决定菜单里「上移 / 下移」摆不摆。 */
    public static boolean hasSibling(JsonObject table, String nodePath) {
        Hit h = hit(table, nodePath);
        if (h == null || h.idx() < 0) return false;
        JsonElement arrEl = h.holder().getAsJsonObject().get(h.key());
        return arrEl != null && arrEl.isJsonArray() && arrEl.getAsJsonArray().size() > 1;
    }

    /** 一个**条目**节点（不是修饰器 / 谓词）—— 只有它能在池之间搬。 */
    public static boolean isEntry(JsonObject node) {
        return node != null && node.has("type") && !node.has("function") && !node.has("condition");
    }

    // ---------- 小工具 ----------

    private static JsonObject kindJson(Kind[] all, String id) {
        for (Kind k : all) if (k.id().equals(id)) return JsonParser.parseString(k.json()).getAsJsonObject();
        return JsonParser.parseString("{}").getAsJsonObject();
    }

    private static JsonArray arr(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        JsonArray a = new JsonArray();
        parent.add(key, a);
        return a;
    }
}
