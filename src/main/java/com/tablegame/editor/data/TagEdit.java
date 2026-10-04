package com.tablegame.editor.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import com.tablegame.core.GameLayout;

/**
 * 标签：项目档里的一个标签 → 原版数据包认得的那份文件。
 *
 * <p>真源 = 26.1 jar 的 {@code data/minecraft/tags/**}：
 *
 * <pre>
 *   data/&lt;ns&gt;/tags/&lt;类型&gt;/&lt;路径&gt;.json   {"values": ["minecraft:acacia_log", "#minecraft:logs"], "replace": false}
 * </pre>
 *
 * 类型 = 原版注册表目录名（item / block / entity_type / damage_type / enchantment / villager_trade）；
 * 标签 id = {@code <ns>:<路径>}（不含 {@code tags/} 与类型两段：原版 recipe 写 {@code #minecraft:logs}，
 * 文件在 {@code tags/item/logs.json}）。
 *
 * <p>纯逻辑零 MC 依赖（类型表 / 路径 / 值文本 ⇄ 数组都在这里）⇒ 自检能真跑。
 */
public final class TagEdit {
    private TagEdit() {
    }

    /**
     * 能挂的六类（= 原版 {@code data/minecraft/tags/} 下的目录名；其余类别以后要用再加一行）。
     *
     * <p>只列**用得上的**六个：别的类别（fluid / game_event / potion…）引擎与编辑器都没有引用它们的口子，
     * 列全了只是给人多几个选错的机会。
     */
    public static final String[] TYPES = {
            "item", "block", "entity_type", "damage_type", "enchantment", "villager_trade",
    };

    /** 类型的中文名（界面用；顺序与 {@link #TYPES} 一一对应）。 */
    private static final String[] TYPES_CN = {
            "物品", "方块", "实体", "伤害类型", "魔咒", "村民交易",
    };

    /** 没写 {@code type} 时按「物品」办（原版九成标签都是 item；写了认不出的才跳过）。 */
    public static final String DEFAULT_TYPE = "item";

    /** 这个类型名原版认得吗（= 有没有那个注册表目录）。 */
    public static boolean isType(String t) {
        for (String s : TYPES) {
            if (s.equals(t)) return true;
        }
        return false;
    }

    /** 类型的中文名（认不出 → 原样回英文）。 */
    public static String typeCn(String t) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equals(t)) return TYPES_CN[i];
        }
        return t == null ? "" : t;
    }

    // ---------- 项目档里的那个条目（挂载信息 + 原版 TagFile）----------
    //
    // 形状 = {"type":"item", "values":[…], "replace":false}
    //   type = 我们加的**挂载信息**（写进哪个注册表目录）；values / replace = **原版 TagFile 原样**。
    //   拆 / 合两侧都是**笨透传**，真正的「原版布局」由 GamePack 写包时才拼（tags/<类型>/<路径>.json）。

    /** 拼一个项目档条目（不动传进来的那些值）。 */
    public static JsonObject entry(String type, List<String> values) {
        JsonObject o = new JsonObject();
        o.addProperty("type", isType(type) ? type : DEFAULT_TYPE);
        o.add("values", toArray(values));
        return o;
    }

    /** 条目里的类型（没写 / 空 = {@link #DEFAULT_TYPE}；写了但认不出 → 原样返回，让上层去跳过 + 记日志）。 */
    public static String typeOf(JsonObject entry) {
        if (entry != null && entry.has("type") && entry.get("type").isJsonPrimitive()) {
            String t = entry.get("type").getAsString().trim();
            if (!t.isEmpty()) return t;
        }
        return DEFAULT_TYPE;
    }

    /** 条目里的值（字符串数组；不是数组 / 不认识的行 → 空表，别让界面炸）。 */
    public static List<String> valuesOf(JsonObject entry) {
        List<String> out = new ArrayList<>();
        if (entry == null || !entry.has("values") || !entry.get("values").isJsonArray()) return out;
        for (JsonElement e : entry.getAsJsonArray("values")) {
            if (e != null && e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    /** 是不是「覆盖原版同名标签」（手写的 {@code replace:true} 原样透传；编辑器不做这个开关）。 */
    public static boolean isReplace(JsonObject entry) {
        return entry != null && entry.has("replace") && entry.get("replace").isJsonPrimitive()
                && entry.get("replace").getAsBoolean();
    }

    /**
     * 一个标签 → 原版能解的那一份：{@code {values, replace}}（去掉我们那个 {@code type}）。
     *
     * <p>{@code replace} 只在手写过（或为 true）时才写出去：缺省不写 = 与原版同名标签合并（原版那条不动）。
     */
    public static JsonObject toVanilla(JsonObject entry) {
        JsonObject out = new JsonObject();
        out.add("values", toArray(valuesOf(entry)));
        if (isReplace(entry)) out.addProperty("replace", true);
        return out;
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray a = new JsonArray();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) a.add(v.trim());
            }
        }
        return a;
    }

    // ---------- 界面：值的一列文本 ⇄ 数组 ----------

    /** 值 → 一列文本（一行一个；给多行编辑框用）。 */
    public static String valuesText(List<String> values) {
        StringBuilder sb = new StringBuilder();
        if (values != null) {
            for (String v : values) {
                if (v == null || v.isBlank()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(v.trim());
            }
        }
        return sb.toString();
    }

    /** 条目 → 一列文本（同上，从条目里取）。 */
    public static String valuesText(JsonObject entry) {
        return valuesText(valuesOf(entry));
    }

    /**
     * 一列文本 → 值（多行编辑框的逆）：按**换行与逗号**切（贴一串 {@code a, b, c} 也认），
     * 去空白、丢空行。顺序原样保住（标签的 values 顺序无所谓，但别手签一份给改了）。
     */
    public static List<String> valuesFromText(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.replace(',', '\n').split("\n")) {
            String v = line.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    // ---------- 名字 = 原版 id（**不改写**）----------
    //
    // ⚠ 标签名字本身就是原版 id（配方 / 交易 / `execute` 里写 `#tablegame:<名>`）⇒ 只能原样用；
    //   压成哈希 = 作者写不出来（原版 `Identifier` 只认 `[a-z0-9/._-]`，见 isName）。

    /**
     * 这个名字能不能当原版 id（原版 id 只认 {@code [a-z0-9/._-]}，{@code /} 放行做子分组）。
     *
     * <p>名字非法时不许落盘：编辑器报错不让保存；手写档由 {@code GamePack} 跳过 + 记日志 ——
     * 生成一个原版解不开的 id 会让引用它的配方 / 交易整条报废。
     */
    public static boolean isName(String name) {
        return VanillaJson.isName(name);          // 名字规则唯一一份住 VanillaJson
    }

    /** 这个标签在数据包里的文件（相对数据包根）：{@code data/tablegame/tags/<类型>/<名字>.json}（**名字原样**）。 */
    public static String packFile(String type, String name) {
        return "data/" + GameLayout.PACK_ID + "/tags/" + (isType(type) ? type : DEFAULT_TYPE)
                + "/" + name + ".json";
    }

    /**
     * 这个标签在注册表里的 id：{@code tablegame:<名字>}，别处引用时写 {@code #tablegame:<名字>}。
     *
     * <p>⚠ 不含 {@code tags/} 与类型目录（文件路径里剥，不手拼第二处）。
     */
    public static String entryId(String type, String name) {
        return GameLayout.PACK_ID + ":" + name;
    }

    /** 清单里那一行：标签名 · 类型 · 几个值（+ 覆盖警告）。 */
    public static String rowLabel(String name, JsonObject entry) {
        String t = typeOf(entry);
        String s = name + "\u3000· " + typeCn(t) + "　· " + valuesOf(entry).size() + " 个值";
        if (isReplace(entry)) s += "　⚠ replace（覆盖原版同名标签）";
        return s;
    }

    /** 界面提示用：引用这个标签时该写什么（{@code #tablegame:<路径>}）。 */
    public static String refText(String type, String name) {
        return "#" + entryId(type, name);
    }
}
