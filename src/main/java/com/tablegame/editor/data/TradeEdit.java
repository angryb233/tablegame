package com.tablegame.editor.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GameLayout;
import com.tablegame.editor.pack.AssetItem;

/**
 * 村民交易：项目档里的一条自定义交易 → 原版数据包认得的那两份文件。
 *
 * <p>原版这一套是三层（真源 = 26.1 jar 里的 {@code data/minecraft/}）：
 * <pre>
 *   villager_trade/<职业>/<等级>/xxx.json   一条交易（wants / gives / max_uses / xp / reputation_discount）
 *   tags/villager_trade/<职业>/level_N.json   这一职业这一级的候选池（交易 id 列表）
 *   trade_set/<职业>/level_N.json            原版那条写的是 {@code "trades": "#minecraft:<职业>/level_N"}
 * </pre>
 *
 * <p>⇒ 加一条交易 = 写一条 {@code villager_trade} + 往那个 tag 里追加（tag 默认 merge），不动 {@code trade_set}；
 * 职业→等级映射注册期写死（{@code VillagerProfession.tradeSetsByLevel}），加交易只有「往那一级池子追加」一条路。
 *
 * <p>⚠ 天花板：原版按 {@code trade_set} 的 {@code amount} 从整池随机抽不重复的几条 ⇒ 我们这条不保证出现。
 * 要「必出 / 逐只不同」用 {@code entity_data} 直接写村民的 {@code Offers}。
 *
 * <p>纯逻辑零 MC 依赖（职业名 / 路径 / 改名 / 资产名换基底都在这里），自检能真跑。
 */
public final class TradeEdit {
    private TradeEdit() {
    }

    /** 能做「挂到某一级」的职业 = 原版有 {@code trade_set/<职业>/level_N} 的那 13 个；流浪商人（{@code wandering_trader}）走另一套挂法，不在内。 */
    public static final String[] PROFESSIONS = {
            "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher",
            "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith",
    };

    /** 职业的中文名（界面用；顺序与 {@link #PROFESSIONS} 一一对应）。 */
    private static final String[] PROFESSION_CN = {
            "盔甲匠", "屠夫", "制图师", "牧师", "农夫", "渔夫", "制箭师",
            "皮匠", "图书管理员", "石匠", "牧羊人", "工具匠", "武器匠",
    };

    /** 原版只有 level_1 ~ level_5 这五级（{@code TradeSets.FARMER_LEVEL_1..5}）。 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;

    /** 新建交易时的缺省挂载点（最常被举的例子就是「让农夫也能卖钻石」）。 */
    public static final String DEFAULT_PROFESSION = "farmer";
    public static final int DEFAULT_LEVEL = 1;

    /** 缺省的一条交易：1 绿宝石 ←→ 1 钻石（作者改数字就行）。 */
    public static final String BLANK = "{\"wants\":{\"id\":\"minecraft:emerald\",\"count\":1},"
            + "\"gives\":{\"id\":\"minecraft:diamond\",\"count\":1},"
            + "\"max_uses\":12,\"xp\":1,\"reputation_discount\":0.05}";

    /** 这个职业名原版认得吗。 */
    public static boolean isProfession(String p) {
        for (String s : PROFESSIONS) {
            if (s.equals(p)) return true;
        }
        return false;
    }

    /** 职业的中文名（认不出 → 原样回英文）。 */
    public static String professionCn(String p) {
        for (int i = 0; i < PROFESSIONS.length; i++) {
            if (PROFESSIONS[i].equals(p)) return PROFESSION_CN[i];
        }
        return p == null ? "" : p;
    }

    /** 等级夹到 1~5（原版只有这五级）。 */
    public static int clampLevel(int lv) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, lv));
    }

    // ---------- 项目档里的那一条（挂载 + 交易本体）----------
    // 形状 = {"profession":"farmer","level":1,"trade":{…原版 villager_trade JSON 原样…}}；挂载信息与本体同文件。

    /** 拼一条项目档条目（不动传进来的那两个对象）。 */
    public static JsonObject entry(String profession, int level, JsonObject trade) {
        JsonObject o = new JsonObject();
        o.addProperty("profession", isProfession(profession) ? profession : DEFAULT_PROFESSION);
        o.addProperty("level", clampLevel(level));
        o.add("trade", trade == null ? new JsonObject() : trade);
        return o;
    }

    /** 条目里的交易本体（不是对象 → 空对象，别让界面炸）。 */
    public static JsonObject tradeOf(JsonObject entry) {
        if (entry != null && entry.has("trade") && entry.get("trade").isJsonObject()) {
            return entry.getAsJsonObject("trade");
        }
        return new JsonObject();
    }

    public static String professionOf(JsonObject entry) {
        if (entry != null && entry.has("profession") && entry.get("profession").isJsonPrimitive()) {
            return entry.get("profession").getAsString();
        }
        return DEFAULT_PROFESSION;
    }

    public static int levelOf(JsonObject entry) {
        if (entry != null && entry.has("level") && entry.get("level").isJsonPrimitive()) {
            try {
                return clampLevel(entry.get("level").getAsInt());
            } catch (RuntimeException ignored) {
                // 手改坏了 → 缺省级
            }
        }
        return DEFAULT_LEVEL;
    }

    // ---------- 名字 → 原版 id / 数据包路径 ----------

    /** 交易名 → 原版 id 的路径段。 */
    public static String idPath(String name) {
        return RecipeEdit.recipeId(name);
    }

    /** 这一条交易在数据包里的文件（相对数据包根）：{@code data/tablegame/villager_trade/<职业>/<等级>/<id>.json}。 */
    public static String packFile(String profession, int level, String name) {
        return "data/" + GameLayout.PACK_ID + "/villager_trade/"
                + (isProfession(profession) ? profession : DEFAULT_PROFESSION) + "/"
                + clampLevel(level) + "/" + idPath(name) + ".json";
    }

    /** 这一条交易在注册表里的 id：{@code tablegame:<职业>/<等级>/<id>}。 */
    public static String entryId(String profession, int level, String name) {
        String f = packFile(profession, level, name);
        String prefix = "data/" + GameLayout.PACK_ID + "/villager_trade/";
        return GameLayout.PACK_ID + ":" + f.substring(prefix.length(), f.length() - 5);
    }

    /** 往哪份 tag 里追加自己（namespace 必须是 minecraft：追加的是原版那条池子）；tag 默认 {@code replace:false} 与原版合并。 */
    public static String tagFile(String profession, int level) {
        return "data/minecraft/tags/villager_trade/"
                + (isProfession(profession) ? profession : DEFAULT_PROFESSION) + "/level_" + clampLevel(level) + ".json";
    }

    // ---------- 编译：资产名 → 基底 id（原版才认得）----------

    /**
     * 一条交易 → 原版能解的那一份（返回新对象，不动项目档里那份）。
     * {@code gives} = 卖出去的东西：写本项目的资产名就换成它的基底 + 我们那套组件（{@link AssetItem#componentsOf}）。
     * ⚠ {@code wants} = 收购的东西：只换基底 id，不写组件谓词 ⇒ 同基底的两条声明物品分不出。
     */
    public static JsonObject toVanilla(JsonObject trade, List<GameDefinition.AssetDef> assets) {
        JsonObject out = trade.deepCopy();
        JsonObject gv = asObj(out.get("gives"));
        if (gv != null && gv.has("id") && gv.get("id").isJsonPrimitive()) {
            GameDefinition.AssetDef a = LootEdit.assetByName(assets, gv.get("id").getAsString());
            if (a != null) {
                gv.addProperty("id", a.base());
                JsonObject comps = asObj(gv.get("components"));
                if (comps == null) comps = new JsonObject();
                for (var e : AssetItem.componentsOf(a).entrySet()) comps.add(e.getKey(), e.getValue());
                gv.add("components", comps);
            }
        }
        for (String k : new String[]{"wants", "additional_wants"}) {
            JsonObject w = asObj(out.get(k));
            if (w != null && w.has("id") && w.get("id").isJsonPrimitive()) {
                GameDefinition.AssetDef a = LootEdit.assetByName(assets, w.get("id").getAsString());
                if (a != null) w.addProperty("id", a.base());
            }
        }
        return out;
    }

    private static JsonObject asObj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    // ---------- 界面读值 ----------

    /** 那一格（{@code wants} / {@code gives}）里的 {@code id} 文本（没写 → 空串）。 */
    public static String idText(JsonObject trade, String key) {
        JsonObject o = asObj(trade.get(key));
        if (o != null && o.has("id") && o.get("id").isJsonPrimitive()) return o.get("id").getAsString();
        return "";
    }

    /** 那一格里的数量文本（没写 → 空串；给界面显示用）。 */
    public static String countText(JsonObject trade, String key) {
        JsonObject o = asObj(trade.get(key));
        if (o != null && o.has("count") && o.get("count").isJsonPrimitive()) {
            return o.get("count").getAsString();
        }
        return "";
    }

    /** 顶层那一格数字的文本（{@code max_uses} / {@code xp} / {@code reputation_discount}）。 */
    public static String numText(JsonObject trade, String key) {
        if (trade != null && trade.has(key) && trade.get(key).isJsonPrimitive()) return trade.get(key).getAsString();
        return "";
    }

    /** 清单里那一行：职业 等级 · 买什么 → 给什么。 */
    public static String rowLabel(String name, JsonObject entry) {
        JsonObject t = tradeOf(entry);
        String buy = idText(t, "wants");
        String sell = idText(t, "gives");
        return name + "　· " + professionCn(professionOf(entry)) + " " + levelOf(entry) + " 级　· 收 " + buy + " → 给 " + sell;
    }
}
