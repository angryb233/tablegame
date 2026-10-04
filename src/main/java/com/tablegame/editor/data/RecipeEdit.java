package com.tablegame.editor.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.pack.AssetItem;

/**
 * 原版配方（{@code recipe} JSON）的增删改算子 + 编译 —— 只懂 JSON，不懂界面
 * （对应 {@link LootEdit} / {@code ScriptEdit}）。
 *
 * <p>节点用路径指名（画布节点的 key）：{@code result} · {@code key[#]} · {@code ingredients[0]}。
 *
 * <p>编译（{@link #toVanilla}）：指向原版 id 处写本项目的资产名，就换成基底 id —— 产物还
 * 贴上我们的组件（{@link AssetItem}），用料按基底认；写原版 id 则原样不动
 * （{@code minecraft:iron_ingot} 用原版、{@code iron_ingot} 才轮到同名资产）。
 */
public final class RecipeEdit {
    private RecipeEdit() {
    }

    /** 一种能新建的配方形状：原版 id · 菜单文案 · 新配方的默认 JSON。 */
    public record Kind(String id, String label, String json) {
    }

    /** 能新建的配方形状（只列工作台两条；其它形状手写 JSON 照样能跑，只是菜单里不列）。 */
    public static final Kind[] TYPES = {
            new Kind("minecraft:crafting_shaped", "工作台·有序",
                    "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"#\"],\"key\":{\"#\":\"minecraft:iron_ingot\"},\"result\":{\"id\":\"minecraft:iron_ingot\",\"count\":1}}"),
            new Kind("minecraft:crafting_shapeless", "工作台·无序",
                    "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:iron_ingot\"],\"result\":{\"id\":\"minecraft:iron_ingot\",\"count\":1}}"),
    };

    /** 新建时的默认形状。 */
    public static final String DEFAULT_TYPE = "minecraft:crafting_shaped";

    /** 一个符号池（有序配方用；先 {@code #} 再字母）—— 加一格时按顺序取下一个没用的。 */
    private static final String SYMBOLS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // ---------- 读 ----------

    /** 配方的 type（全 id）；没写 = 空串。 */
    public static String typeId(JsonObject r) {
        JsonElement e = r == null ? null : r.get("type");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** type 的短名（去 {@code minecraft:}）；认得的给中文标签。 */
    public static String typeLabel(JsonObject r) {
        String id = typeId(r);
        for (Kind k : TYPES) {
            if (k.id().equals(id)) return k.label();
        }
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    /** 有序配方？（决定材料住在 {@code key} 还是 {@code ingredients}）。 */
    public static boolean isShaped(JsonObject r) {
        return typeId(r).endsWith("crafting_shaped");
    }

    /** 产物那一格（写的是什么就报什么，可能是资产名）。 */
    public static String resultText(JsonObject r) {
        JsonObject res = result(r);
        if (res == null || !res.has("id") || !res.get("id").isJsonPrimitive()) return "（没写产物）";
        String id = res.get("id").getAsString();
        String n = res.has("count") && res.get("count").isJsonPrimitive()
                ? " ×" + res.get("count").getAsString() : "";
        return id + n;
    }

    /** 产物节点。 */
    public static JsonObject result(JsonObject r) {
        JsonElement e = r == null ? null : r.get("result");
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** 有序配方的图样（多行文本；没写 = 空串）。 */
    public static String patternText(JsonObject r) {
        JsonElement p = r == null ? null : r.get("pattern");
        if (p == null || !p.isJsonArray()) return "";
        StringBuilder b = new StringBuilder();
        for (JsonElement row : p.getAsJsonArray()) {
            if (b.length() > 0) b.append('\n');
            b.append(row.isJsonPrimitive() ? row.getAsString() : "");
        }
        return b.toString();
    }

    /** 所有材料格的路径与显示名（有序 = 每符号一条；无序 = 每项一条），画布节点按它出。 */
    public static List<String[]> ingredientPaths(JsonObject r) {
        List<String[]> out = new ArrayList<>();
        if (isShaped(r)) {
            JsonElement k = r.get("key");
            if (k != null && k.isJsonObject()) {
                for (String sym : k.getAsJsonObject().keySet()) {
                    out.add(new String[] { "key[" + sym + "]", "「" + sym + "」这个符号的用料" });
                }
            }
        } else {
            JsonElement ing = r.get("ingredients");
            if (ing != null && ing.isJsonArray()) {
                JsonArray a = ing.getAsJsonArray();
                for (int i = 0; i < a.size(); i++) {
                    out.add(new String[] { "ingredients[" + i + "]", "第 " + (i + 1) + " 格" });
                }
            }
        }
        return out;
    }

    /** 一格用料的内容（数组 → 逗号拼起来，读给作者看）。 */
    public static String ingredientText(JsonObject r, String path) {
        JsonObject n = node(r, path);
        if (n == null) return "";
        return describe(n.get("value"));
    }

    /** 路径 → 那一格（画布用）：{@code key[x]} / {@code ingredients[n]} / {@code result}。 */
    public static JsonObject node(JsonObject r, String path) {
        if (r == null || path == null || path.isEmpty()) return r;
        if (path.equals("result")) return result(r);
        if (path.startsWith("key[")) {
            String sym = path.substring(4, path.length() - 1);
            JsonElement k = r.get("key");
            if (k == null || !k.isJsonObject() || !k.getAsJsonObject().has(sym)) return null;
            return wrap(k.getAsJsonObject().get(sym));
        }
        if (path.startsWith("ingredients[")) {
            int i = Integer.parseInt(path.substring(12, path.length() - 1));
            JsonElement ing = r.get("ingredients");
            if (ing == null || !ing.isJsonArray() || i >= ing.getAsJsonArray().size()) return null;
            return wrap(ing.getAsJsonArray().get(i));
        }
        return null;
    }

    /** 把一格用料包成对象（画布只读 {@code value}，不用管它原是字符串还是数组）。 */
    private static JsonObject wrap(JsonElement e) {
        JsonObject o = new JsonObject();
        o.add("value", e);
        return o;
    }

    private static String describe(JsonElement e) {
        if (e == null) return "";
        if (e.isJsonArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonElement c : e.getAsJsonArray()) {
                if (b.length() > 0) b.append(", ");
                b.append(describe(c));
            }
            return b.toString();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (String k : new String[] { "item", "tag" }) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) return e.toString();
            }
            return e.toString();
        }
        return e.getAsString();
    }

    // ---------- 改 ----------

    /** 加一格用料：有序 = 图样里找第一个空格塞新符号；无序 = 末尾追加一项。返回新路径（加不成 = 空串）。 */
    public static String addIngredient(JsonObject r) {
        if (r == null) return "";
        if (!isShaped(r)) {
            JsonArray ing = arr(r, "ingredients");
            ing.add("minecraft:iron_ingot");
            return "ingredients[" + (ing.size() - 1) + "]";
        }
        String sym = freeSymbol(r);
        if (sym.isEmpty()) return "";                        // 符号用光了（26 格材料，现实里不会）
        if (!addToPattern(r, sym)) return "";               // 图样满了（3×3）
        keyObj(r).addProperty(sym, "minecraft:iron_ingot");
        return "key[" + sym + "]";
    }

    /** 删一格用料（图样上该符号的格子变空 + key 里删掉）；**最后一格不许删**（要清空就删整张配方）。 */
    public static boolean removeIngredient(JsonObject r, String path) {
        if (isShaped(r)) {
            if (ingredientPaths(r).size() <= 1) return false;
            if (!path.startsWith("key[")) return false;
            String sym = path.substring(4, path.length() - 1);
            if (!keyObj(r).has(sym)) return false;
            keyObj(r).remove(sym);
            eraseFromPattern(r, sym);
            return true;
        }
        if (!path.startsWith("ingredients[")) return false;
        JsonArray ing = arr(r, "ingredients");
        if (ing.size() <= 1) return false;
        int i = Integer.parseInt(path.substring(12, path.length() - 1));
        if (i < 0 || i >= ing.size()) return false;
        ing.remove(i);
        return true;
    }

    /**
     * 改一格用料：单件 id / 资产名 / {@code #tag}；逗号分隔 = 任选其一（落成数组）。
     */
    public static boolean setIngredient(JsonObject r, String path, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return false;
        JsonElement val = v.contains(",") ? listOf(v) : new com.google.gson.JsonPrimitive(v);
        if (isShaped(r)) {
            if (!path.startsWith("key[")) return false;
            String sym = path.substring(4, path.length() - 1);
            if (!keyObj(r).has(sym)) return false;
            keyObj(r).add(sym, val);
            return true;
        }
        if (!path.startsWith("ingredients[")) return false;
        JsonArray ing = arr(r, "ingredients");
        int i = Integer.parseInt(path.substring(12, path.length() - 1));
        if (i < 0 || i >= ing.size()) return false;
        ing.set(i, val);
        return true;
    }

    /** 改图样（多行文本；空格 = 空位）。校验：≤3 行 · 每行 ≤3 且等宽 · 每个符号都在 key 里。 */
    public static String writePattern(JsonObject r, String text) {
        if (!isShaped(r)) return "无序配方没有图样（材料按 ingredients 列就行）";
        String[] rows = (text == null ? "" : text).strip().split("\\R");
        if (rows.length == 0 || rows.length > 3) return "图样最多 3 行";
        int w = rows[0].strip().length();
        if (w == 0) return "图样不能是空的";
        for (String row : rows) {
            if (row.strip().length() != w) return "每一行的长度要一样（现在有 " + w + " 和 " + row.strip().length() + "）";
            if (row.strip().length() > 3) return "图样最多 3 列";
            for (char c : row.strip().toCharArray()) {
                if (c != ' ' && !keyObj(r).has(String.valueOf(c))) {
                    return "图样里用了没定义过的符号 «" + c + "»（先在菜单里加一格材料）";
                }
            }
        }
        JsonArray p = new JsonArray();
        for (String row : rows) p.add(row.strip());
        r.add("pattern", p);
        return "";
    }

    /** 改产物（写资产名 = 我那条物品）。 */
    public static boolean setResultId(JsonObject r, String id) {
        JsonObject res = result(r);
        String v = id == null ? "" : id.trim();
        if (res == null || v.isEmpty()) return false;
        res.addProperty("id", v);
        return true;
    }

    /** 改产物数量（1~99 —— 原版 {@code ItemStackTemplate} 的上限）。 */
    public static boolean setResultCount(JsonObject r, String raw) {
        JsonObject res = result(r);
        if (res == null) return false;
        try {
            int n = Integer.parseInt(raw == null ? "" : raw.trim());
            if (n < 1 || n > 99) return false;
            res.addProperty("count", n);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------- 编译（项目档 → 原版认得的）----------

    /**
     * 配方名 → 合法的原版 id 路径（数据包里一个文件 = 一条配方，文件名即 id）。
     * ⚠ 原版 id 只认 {@code [a-z0-9/._-]}：中文名直接当文件名会加载不了 ⇒ 非法就压成 ASCII + 短哈希。
     */
    public static String recipeId(String name) {
        String s = name == null ? "" : name.trim();
        if (isIdPath(s)) return s;
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') b.append(c);
            else b.append('_');
        }
        String ascii = b.toString();
        if (ascii.isEmpty()) ascii = "r";
        return ascii + "-" + Integer.toHexString(s.hashCode());
    }

    /** 这串能不能直接当原版 id 的路径（小写字母 / 数字 / {@code _ - . /}）。 */
    private static boolean isIdPath(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/') continue;
            return false;
        }
        return true;
    }

    /**
     * 配方 → 原版能解的配方（返回新对象，不动原配方）：只改 result（换基底 + 贴组件）与用料
     * （换基底），其余字段原样透传。
     */
    public static JsonObject toVanilla(JsonObject recipe, List<GameDefinition.AssetDef> assets) {
        JsonObject out = recipe.deepCopy();
        JsonObject res = result(out);
        if (res != null && res.has("id") && res.get("id").isJsonPrimitive()) {
            GameDefinition.AssetDef a = LootEdit.assetByName(assets, res.get("id").getAsString());
            if (a != null) {
                res.addProperty("id", a.base());
                JsonObject comps = res.has("components") && res.get("components").isJsonObject()
                        ? res.getAsJsonObject("components") : new JsonObject();
                for (var e : AssetItem.componentsOf(a).entrySet()) comps.add(e.getKey(), e.getValue());
                res.add("components", comps);
            }
        }
        if (isShaped(out)) {
            JsonElement k = out.get("key");
            if (k != null && k.isJsonObject()) {
                for (String sym : new ArrayList<>(k.getAsJsonObject().keySet())) {
                    k.getAsJsonObject().add(sym, swap(k.getAsJsonObject().get(sym), assets));
                }
            }
        } else {
            JsonElement ing = out.get("ingredients");
            if (ing != null && ing.isJsonArray()) {
                JsonArray a2 = ing.getAsJsonArray();
                for (int i = 0; i < a2.size(); i++) a2.set(i, swap(a2.get(i), assets));
            }
            if (out.has("ingredient")) out.add("ingredient", swap(out.get("ingredient"), assets));
        }
        return out;
    }

    /** 一格用料里的资产名 → 基底 id（字符串 / 数组 / `{item|tag}` 对象都认；认不出的原样透传）。 */
    private static JsonElement swap(JsonElement e, List<GameDefinition.AssetDef> assets) {
        if (e == null) return null;
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement c : e.getAsJsonArray()) a.add(swap(c, assets));
            return a;
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject().deepCopy();
            if (o.has("item") && o.get("item").isJsonPrimitive()) {
                GameDefinition.AssetDef a = LootEdit.assetByName(assets, o.get("item").getAsString());
                if (a != null) o.addProperty("item", a.base());
            }
            return o;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return e;
        GameDefinition.AssetDef a = LootEdit.assetByName(assets, e.getAsString());
        return a == null ? e : new com.google.gson.JsonPrimitive(a.base());
    }

    // ---------- 小工具 ----------

    private static JsonObject keyObj(JsonObject r) {
        JsonElement k = r.get("key");
        if (k != null && k.isJsonObject()) return k.getAsJsonObject();
        JsonObject o = new JsonObject();
        r.add("key", o);
        return o;
    }

    private static JsonArray arr(JsonObject r, String key) {
        JsonElement e = r.get(key);
        if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        JsonArray a = new JsonArray();
        r.add(key, a);
        return a;
    }

    private static JsonArray listOf(String csv) {
        JsonArray a = new JsonArray();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) a.add(t);
        }
        return a;
    }

    /** 还没用过的符号。 */
    private static String freeSymbol(JsonObject r) {
        for (char c : SYMBOLS.toCharArray()) {
            if (!keyObj(r).has(String.valueOf(c))) return String.valueOf(c);
        }
        return "";
    }

    /** 把符号放进图样里第一个空位（图样满了 / 塞不下 = false）。 */
    private static boolean addToPattern(JsonObject r, String sym) {
        char[][] g = grid(r);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (g[i][j] != ' ') continue;
                g[i][j] = sym.charAt(0);
                writeGrid(r, trimGrid(g));
                return true;
            }
        }
        return false;
    }

    /** 把符号从图样里擦掉（变空格），再去掉四周的空行空列。 */
    private static void eraseFromPattern(JsonObject r, String sym) {
        char[][] g = grid(r);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (g[i][j] == sym.charAt(0)) g[i][j] = ' ';
            }
        }
        writeGrid(r, trimGrid(g));
    }

    /** 3×3 字符矩阵（缺的补空格）。 */
    private static char[][] grid(JsonObject r) {
        char[][] g = new char[3][3];
        for (char[] row : g) java.util.Arrays.fill(row, ' ');
        String[] rows = patternText(r).split("\\R");
        for (int i = 0; i < Math.min(3, rows.length); i++) {
            String s = rows[i].strip();
            for (int j = 0; j < Math.min(3, s.length()); j++) g[i][j] = s.charAt(j);
        }
        return g;
    }

    /** 去掉四周的空行空列（里面的空位保留）。 */
    private static char[][] trimGrid(char[][] g) {
        int top = 0, bottom = 2, left = 0, right = 2;
        while (top < bottom && rowEmpty(g, top)) top++;
        while (bottom > top && rowEmpty(g, bottom)) bottom--;
        while (left < right && colEmpty(g, left)) left++;
        while (right > left && colEmpty(g, right)) right--;
        char[][] out = new char[bottom - top + 1][right - left + 1];
        for (int i = 0; i <= bottom - top; i++) {
            for (int j = 0; j <= right - left; j++) out[i][j] = g[top + i][left + j];
        }
        return out;
    }

    private static boolean rowEmpty(char[][] g, int i) {
        for (int j = 0; j < 3; j++) if (g[i][j] != ' ') return false;
        return true;
    }

    private static boolean colEmpty(char[][] g, int j) {
        for (int i = 0; i < 3; i++) if (g[i][j] != ' ') return false;
        return true;
    }

    /** 矩阵 → pattern 数组（每行 strip 后等宽）。 */
    private static void writeGrid(JsonObject r, char[][] g) {
        JsonArray p = new JsonArray();
        for (char[] row : g) p.add(new String(row));
        r.add("pattern", p);
    }
}
