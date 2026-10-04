package com.tablegame.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * **附加类组件**（值里套着一串材料的那些：属性修饰符 / 工具规则 / 附魔 / 药水效果…）的「元素字段表 + 值原文 ⇄ 条目」。
 * 解决的：{@code attribute_modifiers} 这类组件的值是「列表套对象」，属性段那一行以前只能开多行小窗手写 SNBT 树（容易错）。
 * 这里把那一串材料拆成**条目**，界面按 {@link Field#kind()} 给控件（数字 / 真假 / 枚举 / 引用 / 内联清单 / 文本）—— 加一类新组件只在这张表里**加一行数据**。
 * 值的真源仍是原版形状：解析走 {@link GameStore#componentValueTree}（借 {@code Parser}，不另写 SNBT），写回同一套字面量；
 * **别的字段一个不动**（改的是值里那一个集合字段，其余原样带回），树没变就把原文一字不动交回。纯逻辑、零 MC 依赖 ⇒ 进自检跑。
 * <pre>
 *   Spec s = ComponentGroup.specOf("attribute_modifiers");
 *   List&lt;List&lt;String&gt;&gt; rows = ComponentGroup.parse(s, "{ modifiers [ … ] }");
 *   String back = ComponentGroup.write(s, rows, 原值);
 * </pre>
 */
public final class ComponentGroup {

    /** 染料 16 色（旗帜图案 / 染料那种枚举字段用）。 */
    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
        "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static final Map<String, Spec> TABLE = new LinkedHashMap<>();

    /**
     * 字段表 {@code pick} 里许写的**短名**（= 哪几张注册表可当候选）—— 唯一真源：{@code IdPickScreen} 按它给可选项，自检拿它盯住表里不写错名。
     * 静态注册表直接读 {@code BuiltInRegistries}；附魔 / 伤害类型 / 旗帜图案是**动态**的（从存档 {@code registryAccess} 取）——取不到就不给候选，界面写「没有候选」，不崩也不给假的。
     */
    public static final List<String> PICK_KINDS = List.of("attribute", "item", "block", "enchantment",
            "mob_effect", "damage_type", "banner_pattern", "map_decoration_type", "sound_event",
            "data_component_type");

    static {
        // ① 最常改的那条：攻击伤害 / 攻击速度 / 护甲…都在这里
        //    攻击伤害 = `minecraft:attack_damage`；`amount` 是**加值**（玩家基础 1 ⇒ 6 = 面板 7 点）
        //    攻击速度 = `minecraft:attack_speed`，`amount` 是「倍率的倒数减 4」那个老口径（原版剑 -2.4）
        //  —— 单对象组件（值本身就是一个对象，没有集合那一层；屏上直进字段视图）——
        //    形状 = 原版 CustomModelData.CODEC（RecordCodecBuilder，四格都可省）
        addSingle("custom_model_data", "自定义模型数据",
                lstOpt("floats"),          // Codec.FLOAT.listOf()：0.5, 1.0
                lstBOpt("flags"),          // Codec.BOOL.listOf()：true, false
                lstOpt("strings"),         // Codec.STRING.listOf()：a, b（写数字会被当数字，别拿它存数字）
                lstOpt("colors"));         // RGB_COLOR_CODEC.listOf()：整数色 16711680（也认 [r,g,b] 三浮点）
        //    形状 = 原版 FireworkExplosion.CODEC（shape 必填，其余可省）
        addSingle("firework_explosion", "烟花爆炸",
                en("shape", "small_ball", "large_ball", "star", "creeper", "burst"),
                lstOpt("colors"),
                lstOpt("fade_colors"),
                blOff("has_trail"),
                blOff("has_twinkle"));
        add("attribute_modifiers", "属性修饰符", "", false,
                refP("type", "attribute"),
                ref("id"),
                num("amount"),
                en("operation", "add_value", "add_multiplied_base", "add_multiplied_total"),
                enOpt("slot", "any", "mainhand", "offhand", "hand", "armor", "head", "chest", "legs", "feet", "body"));
        add("tool", "工具", "rules", false,
                lst("blocks"),
                numOpt("speed"),
                blOff("correct_for_drops"));
        add("blocks_attacks", "格挡", "damage_reductions", false,
                numOpt("horizontal_blocking_angle"),
                lstOpt("type"),
                num("base"),
                num("factor"));
        add("potion_contents", "药水内容", "custom_effects", false,
                refP("id", "mob_effect"),
                numOpt("amplifier"),
                num("duration"),
                blOff("ambient"),
                blOff("show_particles"),
                blOff("show_icon"));
        add("suspicious_stew_effects", "迷之炖菜效果", "", false,
                refP("id", "mob_effect"),
                num("duration"));
        add("banner_patterns", "旗帜图案", "", false,
                refP("pattern", "banner_pattern"),
                en("color", DYES));
        add("fireworks", "烟花", "explosions", false,
                en("shape", "small_ball", "large_ball", "star", "creeper", "burst"),
                lst("colors"),
                lstOpt("fade_colors"),
                blOff("has_trail"),
                blOff("has_twinkle"));
        add("bees", "蜂巢", "", false,
                txt("entity_data"),
                numOpt("ticks_in_hive"),
                numOpt("min_ticks_in_hive"));
        add("container", "容器内容", "", false,
                num("slot"),
                txt("item"));
        add("charged_projectiles", "装填的弹射物", "", false,
                refP("id", "item"),
                numOpt("count"));
        add("bundle_contents", "收纳袋内容", "", false,
                refP("id", "item"),
                numOpt("count"));
        addMap("map_decorations", "地图标记", "",
                keyField("键"),
                refP("type", "map_decoration_type"),
                num("x"),
                num("z"),
                numOpt("rotation"));
        add("death_protection", "死亡保护", "death_effects", false,
                en("type", "apply_effects", "remove_effects", "clear_all_effects", "teleport_randomly", "play_sound"),
                lstOpt("effects"),
                numOpt("probability"),
                numOpt("diameter"),
                refOptP("sound", "sound_event"));
        // ⚠ 属性那一格的原版字段名是 `state`（不是 `properties` —— 写成别的字段原版**静默忽略**）；
        //   空着要写 `{ }`（`[ ]` 报 List must have contents）
        addEmptyObj("can_place_on", "可放置于", "",
                lst("blocks"),
                named("方块属性", "state", Kind.TEXT, true),
                txtOpt("nbt"));
        addEmptyObj("can_break", "可破坏", "",
                lst("blocks"),
                named("方块属性", "state", Kind.TEXT, true),
                txtOpt("nbt"));
        addMap("enchantments", "附魔", "",
                keyRef("附魔 id"),
                num("等级"));
        addMap("stored_enchantments", "附魔书附魔", "",
                keyRef("附魔 id"),
                num("等级"));
        add("recipes", "合成配方", "", false,
                ref("配方 id"));
        add("pot_decorations", "花盆装饰", "", false,
                refP("物品 id", "item"));
        add("tooltip_display", "提示显示", "hidden_components", false,
                refP("组件 id", "data_component_type"));
        addMap("block_state", "方块属性", "",
                keyField("属性名"),
                txt("值"));
        add("repairable", "可修复", "items", false,
                refP("物品 id", "item"));
        // ⚠ `types` 是**数组**（原版的列表分支）：单写一个 id 字符串（不带 `#` 的）报 `Not a json array`
        //   —单个 `#标签` 照 LIST 口径写成裸串（原版认那种写法）
        add("damage_resistant", "抗性", "types", false,
                named("伤害类型 id", "types", Kind.LIST, false));
    }

    private ComponentGroup() {
    }

    /** 一格用哪种控件。 */
    public enum Kind {
        /** 数字 */
        NUM,
        /** 真假（**三态**：空 = 这个字段不写 —— 原版的可省字段就靠这个） */
        BOOL,
        /** 有限几个值，点一下循环 */
        ENUM,
        /** 引用一个注册表 id（本版手打 id；候选拾取以后再加） */
        REF,
        /** 内联清单（逗号分隔：`1, 2` 或 `minecraft:a, minecraft:b`；`#标签` 也认） */
        LIST,
        /** 真假清单（逗号分隔 `true, false`；`custom_model_data` 的 `flags` 那种） */
        LIST_BOOL,
        /** 自由文本 / 嵌套对象原文 */
        TEXT
    }

    /**
     * 一条目里的一格。@param name 界面上这一格叫什么（也当字段名用）· @param path 落在条目对象里的**字段路径**（点分，{@code item.id} 这种嵌套用）
     * @param kind 控件 · @param key 真 = 这一格是**映射的键**（写的时候当字段名使）· @param opt 真 = 空着就**不写**这个字段（原版 codec 里的可省字段）
     * @param opts 枚举可选值（{@code kind == ENUM} 时非空）
     */
    public record Field(String name, String path, Kind kind, boolean key, boolean opt,
                        List<String> opts, String pick) { }

    /**
     * 一类附加组件。
     * @param id 组件 id · @param cn 中文名（属性段那一行显示的名字）
     * @param attach 集合在值里的字段名；**空串 = 整个值就是那个集合**（{@code charged_projectiles} 那种）
     * @param map 真 = 集合是**映射**（键 → 条目，{@code enchantments} 那种），假 = 列表
     * @param single 真 = 值本身就是**一个对象**（没有集合那一层）⇒ 屏上直接进字段视图、不摆「＋加一条 / ✕删」；{@code attach} 无意义
     * @param emptyObj 真 = **空集合要写成 {@code { }}**（原版列表分支要求非空的那两个：{@code can_break} / {@code can_place_on}）
     * @param fields 条目的字段表（顺序 = 界面上从上到下；映射的第一个是键格）
     */
    public record Spec(String id, String cn, String attach, boolean map, boolean single, boolean emptyObj,
                       List<Field> fields) { }

    // ── 表格：一类附加组件一行数据 ──────────────────────────────────────────

    private static void add(String id, String cn, String attach, boolean map, Field... fields) {
        TABLE.put(id, new Spec(id, cn, attach, map, false, false, List.of(fields)));
    }

    private static void addMap(String id, String cn, String attach, Field... fields) {
        TABLE.put(id, new Spec(id, cn, attach, true, false, false, List.of(fields)));
    }

    /** **单对象**组件：值就是一个对象（`custom_model_data` / `firework_explosion`），没有集合那一层。 */
    private static void addSingle(String id, String cn, Field... fields) {
        TABLE.put(id, new Spec(id, cn, "", false, true, false, List.of(fields)));
    }

    /**
     * **空集合要写成 `{ }`** 的那类（`can_break` / `can_place_on`）：原版这两个组件的列表分支要求**非空**
     * ⇒ 一条条目都没有时写 `[ ]` 报 `List must have contents`；空着给 `{ }` 才对。
     */
    private static void addEmptyObj(String id, String cn, String attach, Field... fields) {
        TABLE.put(id, new Spec(id, cn, attach, false, false, true, List.of(fields)));
    }

    private static Field f(String n, Kind k, boolean opt) {
        return new Field(n, n, k, false, opt, null, "");
    }

    private static Field num(String n) {
        return f(n, Kind.NUM, false);
    }

    private static Field numOpt(String n) {
        return f(n, Kind.NUM, true);
    }

    /** 真假：默认不写（空）—— 需要它才拨开。 */
    private static Field blOff(String n) {
        return f(n, Kind.BOOL, true);
    }

    private static Field en(String n, String... opts) {
        return new Field(n, n, Kind.ENUM, false, false, List.of(opts), "");
    }

    /** 枚举但**可省**（空着就不写这个字段，例 `slot` 不写 = any；界面上是下拉里那一项「(不写)」）。 */
    private static Field enOpt(String n, String... opts) {
        return new Field(n, n, Kind.ENUM, false, true, List.of(opts), "");
    }

    private static Field ref(String n) {
        return f(n, Kind.REF, false);
    }

    /**
     * 引用一格**且右边给一枚 ▾**（开 {@code IdPickScreen} 挑）。
     *
     * @param pick 注册表短名 —— 合法值 = {@link #PICK_KINDS}（那边是唯一真源，自检盯着这张表不许写错）
     */
    private static Field refP(String n, String pick) {
        return new Field(n, n, Kind.REF, false, false, null, pick);
    }

    private static Field refOptP(String n, String pick) {
        return new Field(n, n, Kind.REF, true, false, null, pick);
    }

    private static Field lst(String n) {
        return f(n, Kind.LIST, false);
    }

    private static Field lstOpt(String n) {
        return f(n, Kind.LIST, true);
    }

    private static Field lstBOpt(String n) {
        return f(n, Kind.LIST_BOOL, true);
    }

    /** 格名与字段名不同的一格（界面写中文、值里要写原版的字段名 —— 例：`state` / `types`）。 */
    private static Field named(String disp, String path, Kind k, boolean opt) {
        return new Field(disp, path, k, false, opt, null, "");
    }

    private static Field txt(String n) {
        return f(n, Kind.TEXT, false);
    }

    private static Field txtOpt(String n) {
        return f(n, Kind.TEXT, true);
    }

    /** 映射的键格（文本）。 */
    private static Field keyField(String n) {
        return new Field(n, n, Kind.TEXT, true, false, null, "");
    }

    private static Field keyRef(String n) {
        return new Field(n, n, Kind.REF, true, false, null, "enchantment");
    }

    // ── 对外查询 ────────────────────────────────────────────────────────────

    /** 这一类组件有没有条目编辑器（没有 ⇒ 属性段照旧走行内控件 / 多行小窗）。 */
    public static boolean isGroup(String componentId) {
        return TABLE.containsKey(componentId);
    }

    /** 表（认不出返回 null；调用方先问 {@link #isGroup}）。 */
    public static Spec specOf(String componentId) {
        return TABLE.get(componentId);
    }

    /** 表里全部组件 id（顺序 = 表的顺序；自检与文档用）。 */
    public static List<String> ids() {
        return List.copyOf(TABLE.keySet());
    }

    /** 条目在列表里那一行显示的摘要（空格子不显示）。 */
    public static String summary(List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (String c : row) {
            if (c == null || c.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(c.strip());
        }
        return sb.toString();
    }

    // ── 值原文 → 条目 ──────────────────────────────────────────────────────

    /**
     * 值原文 → 条目（每条的格子按 {@link Spec#fields()} 顺序；空串 = 该格没写）。
     *
     * @return 认不出原文（值是变量 / 语法错）返回 {@code null} —— 界面给红字提示，**不硬改**
     */
    public static List<List<String>> parse(Spec s, String valueText) {
        JsonElement tree = GameStore.componentValueTree(valueText);
        if (tree == null) {
            return null;
        }
        return entriesOf(s, tree);
    }

    /** 树 → 条目（`attach` 空则整棵树就是集合；**single** 则整棵树就是那唯一一条；认不出是集合 ⇒ 空表）。 */
    private static List<List<String>> entriesOf(Spec s, JsonElement tree) {
        List<List<String>> out = new ArrayList<>();
        if (s.single()) {
            if (tree != null && !tree.isJsonNull()) {
                out.add(cells(s, null, tree));
            }
            return out;
        }
        JsonElement coll = s.attach().isEmpty() ? tree : member(tree, s.attach());
        if (coll == null || coll.isJsonNull()) {
            return out;
        }
        if (s.map()) {
            if (coll.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : coll.getAsJsonObject().entrySet()) {
                    out.add(cells(s, e.getKey(), e.getValue()));
                }
            }
        } else if (coll.isJsonArray()) {
            for (JsonElement el : coll.getAsJsonArray()) {
                out.add(cells(s, null, el));
            }
        } else if (coll.isJsonObject()) {
            out.add(cells(s, null, coll));      // 手写时忘了套列表也认（当一条）
        }
        return out;
    }

    private static List<String> cells(Spec s, String key, JsonElement el) {
        List<String> out = new ArrayList<>();
        for (Field f : s.fields()) {
            out.add(f.key() ? (key == null ? "" : key) : cellText(f, valueOfField(s, f, el)));
        }
        return out;
    }

    /** 条目里那一格对应的 JSON 值（条目就是标量时 = 那个标量：`recipes` 一条 = 一个 id、附魔一条 = 一个等级）。 */
    private static JsonElement valueOfField(Spec s, Field f, JsonElement el) {
        if (el == null) {
            return null;
        }
        if (!el.isJsonObject()) {
            return el;
        }
        return member(el, f.path());
    }

    private static String cellText(Field f, JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "";
        }
        switch (f.kind()) {
            case NUM -> {
                return v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? numText(v.getAsDouble()) : raw(v);
            }
            case BOOL -> {
                return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()
                        ? (v.getAsBoolean() ? "true" : "false") : raw(v);
            }
            case LIST -> {
                if (v.isJsonArray()) {
                    List<String> parts = new ArrayList<>();
                    for (JsonElement x : v.getAsJsonArray()) {
                        parts.add(x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()
                                ? numText(x.getAsDouble()) : raw(x));
                    }
                    return String.join(", ", parts);
                }
                return raw(v);
            }
            case LIST_BOOL -> {
                if (v.isJsonArray()) {
                    List<String> parts = new ArrayList<>();
                    for (JsonElement x : v.getAsJsonArray()) {
                        parts.add(x.isJsonPrimitive() && x.getAsJsonPrimitive().isBoolean()
                                ? (x.getAsBoolean() ? "true" : "false") : raw(x));
                    }
                    return String.join(", ", parts);
                }
                return raw(v);
            }
            case TEXT -> {
                return v.isJsonPrimitive() ? raw(v) : textOf(v, 0).strip();   // 嵌套对象 → 原文，人能看着改
            }
            default -> {
                return raw(v);      // ENUM / REF
            }
        }
    }

    // ── 条目 → 值原文 ──────────────────────────────────────────────────────

    /**
     * 条目 → 值原文。
     *
     * <p>**只动那一个集合字段**：原值里别的字段原样带回去；**树没变就把原文一字不动交回**
     * （⇒ 没改就绝不碰人家写的东西）。
     */
    public static String write(Spec s, List<List<String>> entries, String original) {
        JsonElement old = GameStore.componentValueTree(original);
        JsonElement coll = collection(s, entries);
        if (s.attach().isEmpty()) {
            // ⚠ 空集合要写成 `{ }` 的那两种（`can_break` / `can_place_on`：原版的列表分支要求非空，
            //   `[ ]` 会被拒 —— 空着时给个合法的空对象，别把作者的值改成一个原版不认的）
            if (s.emptyObj() && coll.isJsonArray() && coll.getAsJsonArray().isEmpty()) {
                coll = new JsonObject();
            }
            String out = textOf(coll, 0);
            return old != null && old.equals(coll) ? keep(original, out) : out;
        }
        JsonObject root = old != null && old.isJsonObject() ? old.getAsJsonObject().deepCopy() : new JsonObject();
        root.add(s.attach(), coll);
        String out = textOf(root, 0);
        return old != null && old.equals(root) ? keep(original, out) : out;
    }

    /** 没变就别改（原文空着才用新写的）。 */
    private static String keep(String original, String fallback) {
        return original == null || original.isBlank() ? fallback : original;
    }

    /** 条目 → 集合（**单对象** = 那一条本身；映射 = 对象；列表 = 数组；条目只剩一格值 ⇒ 那个标量）。 */
    private static JsonElement collection(Spec s, List<List<String>> entries) {
        if (s.single()) {
            return entries.isEmpty() ? new JsonObject() : entryObj(s, entries.get(0), 0);
        }
        if (s.map()) {
            JsonObject o = new JsonObject();
            for (List<String> row : entries) {
                String k = cell(row, 0).strip();
                if (k.isEmpty()) {
                    continue;
                }
                o.add(stripQuotes(k), entryVal(s, row, 1));
            }
            return o;
        }
        JsonArray arr = new JsonArray();
        for (List<String> row : entries) {
            arr.add(entryVal(s, row, 0));
        }
        return arr;
    }

    /**
     * 一条 → JSON：**只剩一格值就写那个标量**（`recipes` 一条 = 一个 id、附魔一条 = 一个等级），
     * 多格才写对象。
     */
    private static JsonElement entryVal(Spec s, List<String> row, int from) {
        if (s.fields().size() - from == 1) {
            return scalar(s.fields().get(from), cell(row, from));
        }
        return entryObj(s, row, from);
    }

    private static JsonObject entryObj(Spec s, List<String> row, int from) {
        JsonObject o = new JsonObject();
        for (int i = from; i < s.fields().size(); i++) {
            Field f = s.fields().get(i);
            String t = cell(row, i).strip();
            if (t.isEmpty() && (f.opt() || f.kind() != Kind.TEXT)) {
                continue;   // 空格子 = 这个字段不写（可省字段靠它；该填没填的由原版 codec 把关，不硬塞空串）
            }
            put(o, f.path(), scalar(f, t));
        }
        return o;
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() && row.get(i) != null ? row.get(i) : "";
    }

    /** 一格 → JSON 值（按控件类型定类型）。 */
    private static JsonElement scalar(Field f, String txt) {
        String t = txt == null ? "" : txt.strip();
        switch (f.kind()) {
            case NUM -> {
                try {
                    return new JsonPrimitive(Double.valueOf(t));
                } catch (NumberFormatException e) {
                    return new JsonPrimitive(t);            // 认不出数字就照原文写（合法与否交给原版 codec 把关）
                }
            }
            case BOOL -> {
                return new JsonPrimitive(t.isEmpty() || !t.equalsIgnoreCase("false"));
            }
            case LIST -> {
                if (t.startsWith("#") && !t.contains(",")) {
                    return new JsonPrimitive(t);            // `#minecraft:mineable/pickaxe` 这种标签整串写
                }
                JsonArray a = new JsonArray();
                for (String one : t.split(",")) {
                    String x = stripQuotes(one.strip());
                    if (x.isEmpty()) {
                        continue;
                    }
                    try {
                        a.add(new JsonPrimitive(Double.valueOf(x)));
                    } catch (NumberFormatException e) {
                        a.add(new JsonPrimitive(x));
                    }
                }
                return a;
            }
            case LIST_BOOL -> {
                JsonArray a = new JsonArray();
                for (String one : t.split(",")) {
                    String x = one.strip();
                    if (x.isEmpty()) {
                        continue;
                    }
                    a.add(new JsonPrimitive(Boolean.parseBoolean(x)));   // 认不出布尔就当 false（原版 codec 只看真布尔）
                }
                return a;
            }
            case TEXT -> {
                if (t.startsWith("{") || t.startsWith("[")) {
                    JsonElement nested = GameStore.componentValueTree(t);
                    return nested == null ? new JsonPrimitive(t) : nested;
                }
                return new JsonPrimitive(t);
            }
            default -> {
                return new JsonPrimitive(stripQuotes(t));    // ENUM / REF
            }
        }
    }

    /** 点分路径落到嵌套对象上（`item.id`）。 */
    private static void put(JsonObject o, String path, JsonElement v) {
        String[] seg = path.split("\\.");
        JsonObject cur = o;
        for (int i = 0; i < seg.length - 1; i++) {
            JsonElement next = cur.get(seg[i]);
            if (next == null || !next.isJsonObject()) {
                next = new JsonObject();
                cur.add(seg[i], next);
            }
            cur = next.getAsJsonObject();
        }
        cur.add(seg[seg.length - 1], v);
    }

    private static JsonElement member(JsonElement el, String path) {
        JsonElement cur = el;
        for (String seg : path.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            JsonObject o = cur.getAsJsonObject();
            if (!o.has(seg)) {
                return null;
            }
            cur = o.get(seg);
        }
        return cur;
    }

    // ── 文本小工具 ─────────────────────────────────────────────────────────

    private static String raw(JsonElement v) {
        return v.isJsonPrimitive() ? v.getAsString() : textOf(v, 0).strip();
    }

    /** `"minecraft:x"` → `minecraft:x`（人可能顺手带引号）。 */
    private static String stripQuotes(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** 整数就不带 `.0`（写进脚本里好看，也少一次格式化差异）。 */
    private static String numText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** JSON 树 → 脚本字面量原文（键值相邻、清单带逗号 —— 与手写的一模一样）。 */
    public static String textOf(JsonElement el, int indent) {
        if (el == null || el.isJsonNull()) {
            return "{ }";
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.isEmpty()) {
                return "{ }";
            }
            StringBuilder sb = new StringBuilder("{\n");
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                sb.append(" ".repeat(indent + 2)).append(keyText(e.getKey()))
                        .append(' ').append(textOf(e.getValue(), indent + 2)).append('\n');
            }
            return sb.append(" ".repeat(indent)).append('}').toString();
        }
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.isEmpty()) {
                return "[ ]";
            }
            boolean flat = true;
            for (JsonElement x : a) {
                if (!x.isJsonPrimitive()) {
                    flat = false;
                    break;
                }
            }
            if (flat) {
                StringBuilder sb = new StringBuilder("[ ");
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(textOf(a.get(i), indent));
                }
                return sb.append(" ]").toString();
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < a.size(); i++) {
                sb.append(" ".repeat(indent + 2)).append(textOf(a.get(i), indent + 2));
                if (i < a.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            return sb.append(" ".repeat(indent)).append(']').toString();
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean() ? "true" : "false";
        }
        if (p.isNumber()) {
            return numText(p.getAsDouble());
        }
        return "\"" + p.getAsString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 对象的键：是标识符就裸写，否则加引号（`"minecraft:sharpness"` —— 本语言的名字位认字符串字面量）。 */
    private static String keyText(String k) {
        if (k.isEmpty()) {
            return "zz";
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (i > 0 && c >= '0' && c <= '9');
            if (!ok) {
                return "\"" + k + "\"";
            }
        }
        return k;
    }
}
