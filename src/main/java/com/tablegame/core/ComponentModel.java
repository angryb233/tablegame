package com.tablegame.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 属性段（对象声明的 {@code components { … }} 层）的**行模型 + 组件规格表**（零 MC 依赖，纯字符串逻辑 ⇒ 进自检）。
 * {@link Row} / {@link #parse} / {@link #toText}：块内原文 ⇄ 一行一个组件 —— 值的原文（含多行续行与缩进）**逐字留着**，「读出来 → 写回去」不丢东西也不重排。
 * {@link Spec} / {@link #specOf}：组件 id → 中文名 + 控件（数字 / 开关 / 枚举 / 原文）+ 初值；表里只收**高频那几十个**，其余一律「原文」控件 + 显示 id 本身（覆盖 100%，不用维护 110 条）。
 * {@link #defaultSource}：新加一个组件时写进块里的初值（照它的控件类型给）。
 * ⚠ 组件清单本身**不在这张表里**（拾取器的候选从注册表现读 {@code BuiltInRegistries.DATA_COMPONENT_TYPE}）—— 这里只管「显示成什么、怎么编」。
 */
public final class ComponentModel {

    private ComponentModel() { }

    /** 值的编辑方式（决定行里摆什么控件）。 */
    public enum Kind { NUM, BOOL, ENUM, TEXT }

    /** 一个组件的规格。@param cn 中文名 · @param kind 控件类型 · @param values ENUM 的候选值（写全名，如 {@code "minecraft:rare"}）；其它类型 = null · @param dflt 新加这个组件时写进块里的初值原文 */
    public record Spec(String cn, Kind kind, List<String> values, String dflt) { }

    /** 一行：组件 id + 值原文（可能是多行 —— 逐字留着）。 */
    public record Row(String id, String value) { }

    /**
     * **保留键**：这几个已经是声明的结构键，不许再从属性段里加一遍（同一件事两个入口）。
     * （`name` = 显示名、`base` = 基底、`lore` / `body` = 描述行、`mark` = 文本标记。）
     */
    public static final Set<String> RESERVED = Set.of("name", "base", "lore", "body", "mark");

    /**
     * **锁死型组件**（原版 Unit 型：值只能是个空对象 `{ }`，没有值可填）：`unbreakable` / `glider` /
     * `intangible_projectile`。
     *
     */
    private static final Set<String> FLAGS = new LinkedHashSet<>();

    /** 组件 id（短名）→ 规格表。只收高频项，顺序 = 拾取器里排在前面的顺序。 */
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        num("max_stack_size", "最大堆叠", "1");
        num("max_damage", "耐久上限", "1");
        num("damage", "已损耗", "0");
        num("repair_cost", "修复惩罚", "0");
        // ⚠ 值是个**对象**（数值写 `{ floats [ 1 ] }`）—— 不是数字（：写 `1` 报 Not a JSON object）
        txt("custom_model_data", "自定义模型数据", "{ }");
        num("map_id", "地图编号", "0");
        num("ominous_bottle_amplifier", "不祥之瓶等级", "1");
        // 锁死型组件（原版 Unit 型）：**没有值可填**，值只能是空对象 `{ }`——界面上照旧一个开关
        // （开 = 这条加上、关 = 这条不加）；脚本里手写 `true` 引擎也认（收 components 时归一）。
        flag("unbreakable", "不会损坏");
        bool("enchantment_glint_override", "强制附魔光效");
        flag("intangible_projectile", "穿透性弹射物");
        flag("glider", "可滑翔");
        // ⚠ 值是**裸名**（原版 `Rarity.getSerializedName()` 给的就是 `common` / `uncommon` / `rare` / `epic`，
        // 不带命名空间）—— 写 `minecraft:rare` 报 Unknown element name（）
        enu("rarity", "稀有度", List.of("common", "uncommon", "rare", "epic"));
        txt("enchantments", "附魔", "{ }");
        txt("stored_enchantments", "附魔书附魔", "{ }");
        txt("attribute_modifiers", "属性修饰符", "[ ]");   // ⚠ 整值是**裸数组**（不是 `{ modifiers [ … ] }`）
        txt("food", "食物", "{\n  nutrition 1\n  saturation 0\n}");            // nutrition / saturation 都必填
        txt("tool", "工具", "{\n  rules [\n    { blocks \"#minecraft:mineable/pickaxe\" correct_for_drops true }\n  ]\n}");  // rules 必填（空 rules = 挖什么都不掉！）
        txt("weapon", "武器", "{ }");
        txt("item_name", "物品名（平时用显示名）", "\"名称\"");
        txt("custom_name", "自定义名", "\"名称\"");
        txt("item_model", "物品模型", "\"minecraft:stone\"");
        txt("dyed_color", "染色", "{ rgb 16777215 }");
        txt("base_color", "基础色", "white");
        txt("container", "容器内容", "[ ]");
        txt("bundle_contents", "收纳袋内容", "[ ]");
        txt("potion_contents", "药水内容", "{ potion \"minecraft:water\" }");
        txt("charged_projectiles", "装填的弹射物", "[ ]");
        txt("trim", "盔甲纹饰", "{ material \"minecraft:iron\" pattern \"minecraft:sentry\" }");   // 两格都必填
        txt("recipes", "合成配方", "[ ]");
        txt("writable_book_content", "书与笔内容", "{ pages [ ] }");
        txt("written_book_content", "成书内容", "{ title \"标题\" author \"作者\" pages [ ] }");
        txt("jukebox_playable", "唱片机曲目", "\"minecraft:cat\"");
        txt("note_block_sound", "音符盒音效", "\"minecraft:block.note_block.harp\"");
        txt("block_entity_data", "方块实体数据", "{ id \"minecraft:chest\" }");      // `id` 必填
        txt("entity_data", "实体数据", "{ id \"minecraft:pig\" }");                  // `id` 必填
        txt("bucket_entity_data", "桶装实体数据", "{ }");
        txt("equippable", "可穿戴", "{ slot \"head\" }");                            // `slot` 必填
        txt("consumable", "可食用", "{ }");
        txt("use_remainder", "用后留下", "\"minecraft:bowl\"");
        txt("use_cooldown", "使用冷却", "{ seconds 1 }");
        txt("blocks_attacks", "格挡", "{ }");
        txt("death_protection", "死亡保护", "{ }");
        txt("break_sound", "破坏音效", "\"minecraft:block.stone.break\"");
        txt("tooltip_display", "提示显示", "{ }");
        txt("tooltip_style", "提示样式", "\"minecraft:default\"");
        txt("enchantable", "可附魔", "{ value 1 }");
        txt("repairable", "可修复", "{ items [ ] }");                                 // `items` 必填
        txt("can_break", "可破坏", "{ }");            // 空着写 `{ }`：写成 `[ ]` 原版报 List must have contents
        txt("can_place_on", "可放置于", "{ }");
        txt("map_decorations", "地图标记", "{ }");
        txt("lock", "锁", "{ }");
        txt("profile", "玩家档案", "{ }");
    }

    private static void num(String id, String cn, String dflt) {
        SPECS.put(id, new Spec(cn, Kind.NUM, null, dflt));
    }

    private static void bool(String id, String cn) {
        SPECS.put(id, new Spec(cn, Kind.BOOL, null, "true"));
    }

    /** 锁死型组件：界面上仍是个开关（开 = 加上、关 = 这条不加），但值只有一个 `{ }`。 */
    private static void flag(String id, String cn) {
        SPECS.put(id, new Spec(cn, Kind.BOOL, null, "true"));
        FLAGS.add(id);
    }

    private static void enu(String id, String cn, List<String> values) {
        SPECS.put(id, new Spec(cn, Kind.ENUM, List.copyOf(values), "\"" + values.get(0) + "\""));
    }

    private static void txt(String id, String cn, String dflt) {
        SPECS.put(id, new Spec(cn, Kind.TEXT, null, dflt));
    }

    // ==================== 查表 ====================

    /** 归一组件 id：去掉两侧引号与 {@code minecraft:} 前缀（表里的键都是短名）。 */
    public static String norm(String id) {
        String t = id == null ? "" : id.strip();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }

    /** 组件 id → 规格（表里没有 = 原文控件 + 显示 id 本身）。 */
    public static Spec specOf(String id) {
        Spec s = SPECS.get(norm(id));
        return s == null ? new Spec(norm(id), Kind.TEXT, null, "{ }") : s;
    }

    /** 中文名（表里没有就退回短名 / 全名）。 */
    public static String cnOf(String id) {
        Spec s = SPECS.get(norm(id));
        return s == null ? (id == null ? "" : id.strip()) : s.cn();
    }

    /** 表里有规格（拾取器把这些排在前面）。 */
    public static boolean known(String id) {
        return SPECS.containsKey(norm(id));
    }

    /**
     * **锁死型组件**（原版 Unit 型：值只能是个空对象 `{ }`，没有值可填）—— 收 components 时归一：
     * `true` / `{ }` → `{ }`（加上）、`false` → 这条不加。
     */
    public static boolean isFlag(String id) {
        return FLAGS.contains(norm(id));
    }

    /** 能不能加进属性段：保留键不许（它们已经是声明结构键）。 */
    public static boolean addable(String id) {
        String n = norm(id);
        return !n.isEmpty() && !RESERVED.contains(n);
    }

    /** 新加一个组件时写进块里的初值原文。 */
    public static String defaultSource(String id) {
        return specOf(id).dflt();
    }

    // ==================== 块内原文 ⇄ 行 ====================

    /**
     * 块内原文 → 行表：**一行一个组件**，值的续行（嵌套对象的那些行）原样带上。
     *
     * <p>认不出的行**跳过**（不猜）：键只能是标识符或引号字符串，其余行不是属性。
     */
    public static List<Row> parse(String inner) {
        List<Row> out = new ArrayList<>();
        if (inner == null || inner.isBlank()) {
            return out;
        }
        String[] lines = inner.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].strip();
            i++;
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            int keyEnd = keyEndOf(line);
            if (keyEnd <= 0) {
                continue;
            }
            String key = unquote(line.substring(0, keyEnd));
            String first = line.substring(keyEnd).strip();
            StringBuilder val = new StringBuilder(first);
            int depth = depthOf(first);
            while (depth > 0 && i < lines.length) {          // 值跨行：吃到括号配平（缩进原样留着）
                String more = lines[i].stripTrailing();
                val.append('\n').append(more.stripTrailing());
                depth += depthOf(more);
                i++;
            }
            out.add(new Row(key, val.toString()));
        }
        return out;
    }

    /**
     * 行表 → 块内原文（一行一个组件）。
     *
     * <p>空行与空值行不写；多行值的**相对缩进**由 {@link #parse} 原样带回来，这里照发 ——
     * 块的基准缩进由 {@code ScriptEdit.setDeclBlock} 统一加（所以「读 → 写」往返是幂等的）。
     */
    public static String toText(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            if (r == null || r.id() == null || r.id().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(idCode(r.id()));
            String v = r.value() == null ? "" : r.value().strip();
            if (!v.isEmpty()) {
                sb.append(' ').append(v);
            }
        }
        return sb.toString();
    }

    /** 键的写法：英文标识符裸写，其余加引号（与声明属性同一套）。 */
    public static String idCode(String id) {
        String t = id == null ? "" : id.strip();
        boolean ok = !t.isEmpty() && !Character.isDigit(t.charAt(0));
        for (int i = 0; ok && i < t.length(); i++) {
            char c = t.charAt(i);
            ok = c < 128 && (Character.isLetterOrDigit(c) || c == '_');
        }
        return ok ? t : "\"" + t.replace("\"", "\\\"") + "\"";
    }

    /** 一行开头那个键占到哪儿（标识符 / 引号字符串；认不出 = -1）。 */
    private static int keyEndOf(String line) {
        if (line.isEmpty()) {
            return -1;
        }
        if (line.charAt(0) == '"') {
            int e = 1;
            while (e < line.length() && line.charAt(e) != '"') {
                e += line.charAt(e) == '\\' ? 2 : 1;
            }
            return e >= line.length() ? -1 : e + 1;
        }
        int e = 0;
        while (e < line.length() && (Character.isLetterOrDigit(line.charAt(e)) || line.charAt(e) == '_')) {
            e++;
        }
        return e == 0 ? -1 : e;
    }

    private static String unquote(String s) {
        String t = s.strip();
        return t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")
                ? t.substring(1, t.length() - 1) : t;
    }

    /** 这一行的括号净深度（引号里的不算）—— 判值有没有跨行。 */
    private static int depthOf(String s) {
        int d = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                d++;
            } else if (c == '}' || c == ']') {
                d--;
            }
        }
        return d;
    }
}
