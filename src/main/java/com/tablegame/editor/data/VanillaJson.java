package com.tablegame.editor.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tablegame.core.GameLayout;

/**
 * 【原版数据】页的四条原版 JSON 数据线 —— 进度 / 魔咒 / 伤害类型 / 对话框。
 *
 * <p>四件同一形状：项目档 {@code <段名>/<名字>.json}（原版 JSON 原样）→ 数据包
 * {@code data/tablegame/<段名>/<名字>.json}，零编译（原样进包）。段名就是原版的注册表目录名。
 *
 * <p>纯逻辑零 MC 依赖（表 / 名字规则 / 包内路径 / 模板 / 一行摘要）⇒ 自检能真跑。
 */
public final class VanillaJson {
    private VanillaJson() {
    }

    /** 新建模板（都能过原版 codec：一建出来就能存、能进包）。 */
    private static final String TEMPLATE_ADVANCEMENT = """
            {
              "criteria": { "goal": { "trigger": "minecraft:impossible" } },
              "display": {
                "icon": { "id": "minecraft:paper" },
                "title": { "text": "新进度" },
                "description": { "text": "在编辑器里改这一份 JSON" },
                "frame": "task",
                "background": "minecraft:gui/advancements/backgrounds/stone",
                "show_toast": true,
                "announce_to_chat": false,
                "hidden": false
              }
            }
            """;

    private static final String TEMPLATE_ENCHANTMENT = """
            {
              "description": { "text": "新魔咒" },
              "supported_items": "#minecraft:enchantable/durability",
              "slots": [ "mainhand" ],
              "max_level": 3,
              "weight": 10,
              "anvil_cost": 0,
              "min_cost": { "base": 1, "per_level_above_first": 0 },
              "max_cost": { "base": 51, "per_level_above_first": 0 }
            }
            """;

    private static final String TEMPLATE_DAMAGE = """
            {
              "message_id": "tg_custom",
              "exhaustion": 0.0,
              "scaling": "when_caused_by_living_non_player"
            }
            """;

    /**
     * 对话框：一个 notice 框（标题 + 一行正文）。
     * 想要按钮 / 输入框，自己往后面加 {@code action} / {@code inputs}（原版格式，引擎原样进包）。
     */
    private static final String TEMPLATE_DIALOG = """
            {
              "type": "minecraft:notice",
              "title": { "text": "新对话框" },
              "body": [
                { "type": "minecraft:plain_message", "contents": { "text": "在编辑器里改这一份 JSON" } }
              ],
              "can_close_with_escape": true,
              "pause": false
            }
            """;

    /**
     * 数据线：段键（= 项目档目录名 = 包内目录名 = 原版注册表目录名）· 中文名 · 新建模板。
     *
     * <p>进度 / 魔咒 / 伤害类型三条同形（原版 JSON 原样进包）；对话框也挂这儿 ——
     * 它的原语 {@code dialog(谁, "框名")} 另在 {@code Interp} / 宿主那两处。
     */
    public static final String[][] SECTIONS = {
            {"advancement", "进度", TEMPLATE_ADVANCEMENT},
            {"enchantment", "魔咒", TEMPLATE_ENCHANTMENT},
            {"damage_type", "伤害类型", TEMPLATE_DAMAGE},
            {"dialog", "对话框", TEMPLATE_DIALOG},
    };

    private static String col(String key, int i) {
        for (String[] s : SECTIONS) {
            if (s[0].equals(key)) return s[i];
        }
        return null;
    }

    /** 这是不是三条里的一条。 */
    public static boolean isSection(String key) {
        return col(key, 1) != null;
    }

    /** 中文名（界面用）。 */
    public static String cnOf(String key) {
        String s = col(key, 1);
        return s == null ? (key == null ? "" : key) : s;
    }

    /** 新建时给的模板（原版格式，能过 codec）。 */
    public static String templateOf(String key) {
        String s = col(key, 2);
        return s == null ? "{}" : s;
    }

    /**
     * 这个名字能不能当原版 id（名字规则唯一一份 —— 标签与原版数据三条共用）。
     *
     * <p>原版 id 只认 `[a-z0-9/._-]`；这个名字会被别的文件 / 指令 / 组件引用
     * （`#tablegame:<名>` · `enchantments` 里 · `damage(谁,量,"名")`）⇒ 不许转写、中文当场拦。
     */
    public static boolean isName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    /** 项目档里这一份的位置：{@code <段名>/<名字>.json}（一物一文件）。 */
    public static String fileRef(String key, String name) {
        return key + "/" + name + ".json";
    }

    /** 数据包里这一份的位置：{@code data/tablegame/<段名>/<名字>.json}（**名字原样**）。 */
    public static String packFile(String key, String name) {
        return "data/" + GameLayout.PACK_ID + "/" + key + "/" + name + ".json";
    }

    /** 注册表里的 id：{@code tablegame:<名字>}（不含段名那一段）。 */
    public static String entryId(String key, String name) {
        return GameLayout.PACK_ID + ":" + name;
    }

    /** 一行摘要：名字 · 中文名 · 这一份里那个人话字段（进度的标题 / 魔咒的描述 / 伤害类型的 message_id）。 */
    public static String rowLabel(String key, String name, JsonObject o) {
        return name + "\u3000· " + cnOf(key) + "　· " + hintOf(o);
    }

    /** 从那一份 JSON 里挑一句可读的话（挑不到 → 空串）。 */
    public static String hintOf(JsonObject o) {
        String s = textOf(o, "display", "title");
        if (s.isEmpty()) s = textOf(o, "description");
        if (s.isEmpty()) s = textOf(o, "message_id");
        if (s.isEmpty()) s = textOf(o, "title");      // 对话框：标题就是它那句人话
        return s;
    }

    /** 取 {@code o[a][b]} 那一小段人话（文本原文，或 {@code translate} / {@code text} 字段）。 */
    private static String textOf(JsonObject o, String... path) {
        if (o == null) return "";
        JsonElement e = o;
        for (String k : path) {
            if (e == null || !e.isJsonObject()) return "";
            e = e.getAsJsonObject().get(k);
        }
        if (e == null) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonObject()) {
            JsonObject p = e.getAsJsonObject();
            for (String k : new String[]{"text", "translate"}) {
                if (p.has(k) && p.get(k).isJsonPrimitive()) return p.get(k).getAsString();
            }
        }
        return "";
    }
}
