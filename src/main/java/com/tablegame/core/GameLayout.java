package com.tablegame.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.tablegame.drawboard.BoardStore;
import com.tablegame.editor.data.VanillaJson;

/**
 * 游戏目录布局（B 全拆；**纯逻辑零 MC 依赖，可自检**）：一个游戏 = 一个文件夹，**一个文件只装一类东西**。
 * <pre>
 * games/&lt;游戏名&gt;/
 *   game.json                    # 清单：format + meta —— 识别标志，沿用旧名
 *   src/main.tg                  # 程序：脚本源码（纯文本，VS Code 能开、能 diff）
 *   data/vars.json · data/decks.json          # 数据：数值 / 池 · 牌组
 *   data/areas/&lt;序号&gt;-&lt;id&gt;.json   # 区域快照（一区域一文件；老档单文件 areas.json 仍读得下）
 *   data/other.json              # 退役 / 未知段：原样透传（零损失）
 *   asset/components.json · art/&lt;名字&gt;.px · model/&lt;名字&gt;.bp   # 组件索引 · 像素（.px 二进制）· 蓝图原文（.bp = JSON）
 *   loot/&lt;表名&gt;.json + loot/index.json        # 原版 loot_table 原样存 + 索引（表名可能带 & / 中文，要能反查）
 *   players/&lt;uuid&gt;.json          # 玩家数据（一人一份，见 ProfileStore）
 * </pre>
 * 两个方向在同一个文件里：{@link #split}（一整份档 → 各文件内容）· {@link #join}（各文件 → 一整份档）—— 模型没变，换的只是「从哪几个文件拼出那份 JSON」。
 * 值是 {@code byte[]}：脚本 / 数据是 UTF-8 文本，资产是**真二进制**（.px）。
 */
public final class GameLayout {

    private GameLayout() {
    }

    /** 清单文件（＝识别标志，沿用旧名 `game.json`）：只装 format + meta。 */
    public static final String MANIFEST = "game.json";
    /** 程序：脚本源码（纯文本）。 */
    public static final String SCRIPT = "src/main.tg";
    /** 数据：数值 / 池。 */
    public static final String VARS = "data/vars.json";
    /** 数据：区域快照 —— **老档的单文件形态**（新档已拆成一区域一文件，读侧仍认得它）。 */
    public static final String AREAS = "data/areas.json";
    /** 区域目录：**一个区域一个文件**（见 {@link #areaRef}）。 */
    public static final String AREAS_DIR = "data/areas/";

    /** 战利品表：一张表一个文件（{@link #lootRef}）+ 一份索引（表名 → 路径）。 */
    public static final String LOOT_DIR = "loot/";
    public static final String LOOT_INDEX = LOOT_DIR + "index.json";
    /** 配方：一张配方一个文件（{@link #recipeRef}）+ 一份索引（配方名 → 路径）。 */
    public static final String RECIPE_DIR = "recipe/";
    public static final String RECIPE_INDEX = RECIPE_DIR + "index.json";
    /** 村民交易：一条交易一个文件（{@link #tradeRef}）+ 一份索引（交易名 → 路径）。 */
    public static final String TRADES_DIR = "villager_trade/";
    public static final String TRADES_INDEX = TRADES_DIR + "index.json";
    /**
     * 标签：一个标签一个文件（{@link #tagRef}）+ 一份索引（标签名 → 路径）。条目形状 = {@code {type, values, replace}} ——
     * {@code type} 是我们加的**挂载信息**（写进原版哪个注册表目录），{@code values} / {@code replace} 是**原版 TagFile 原样**。
     */
    public static final String TAGS_DIR = "tags/";
    public static final String TAGS_INDEX = TAGS_DIR + "index.json";
    /**
     * 存档里的数据包目录名（也是 {@code /datapack list} 里那个名字）。
     *
     * <p>常量住**这里**（纯逻辑层）：写作方是 {@code GamePack}（依赖 MC，自检编不到），
     * 而 {@link TradeEdit} 拼数据包路径时要用它 ⇒ 放纯逻辑层两边都看得见。**别在别处再写一份字面量**。
     */
    public static final String PACK_ID = "tablegame";
    /** 数据：牌组。 */
    public static final String DECKS = "data/decks.json";
    /** 组件（导入的像素 / 自定义物品）。 */
    public static final String ASSETS = "asset/components.json";
    /** 未知段（原样透传）。 */
    public static final String OTHER = "data/other.json";

    /** 一条像素资产的落盘位置（相对游戏目录）。 */
    public static String pxRef(String name) {
        return "art/" + BoardStore.safeName(name) + ".px";
    }

    /**
     * 一个区域的落盘位置：{@code data/areas/003-r7.json}。
     *
     */
    public static String areaRef(int index, String id) {
        return AREAS_DIR + String.format("%03d", index) + "-" + BoardStore.safeName(id) + ".json";
    }

    /** 一张战利品表落盘的位置：文件名 = 表名（安全化）。 */
    public static String lootRef(String name) {
        return "loot/" + BoardStore.safeName(name) + ".json";
    }

    /** 一张配方落盘的位置：文件名 = 配方名（安全化）。 */
    public static String recipeRef(String name) {
        return RECIPE_DIR + BoardStore.safeName(name) + ".json";
    }

    /**
     * 一条村民交易落盘的位置：文件名 = 交易名（安全化）。
     *
     * <p>挂载信息（职业 / 等级）住在**文件内容里**（{@code {profession, level, trade}}），不进文件名
     * —— 数据包那边才是原版布局（{@code villager_trade/<职业>/<等级>/<id>.json}），由 {@code GamePack} 拼。
     */
    public static String tradeRef(String name) {
        return TRADES_DIR + BoardStore.safeName(name) + ".json";
    }

    /**
     * 一个标签落盘的位置：文件名 = 标签名（安全化）。
     *
     * <p>挂载信息（{@code type}：写进哪个注册表目录）住在**文件内容里**，不进文件名 —— 数据包那边
     * 才是原版布局（{@code data/<ns>/tags/<type>/<路径>.json}），由 {@code GamePack} 拼（{@code TagEdit.packFile}）。
     */
    public static String tagRef(String name) {
        return TAGS_DIR + BoardStore.safeName(name) + ".json";
    }

    /** 一条模型的蓝图落盘位置。 */
    public static String bpRef(String name) {
        return "model/" + BoardStore.safeName(name) + ".bp";
    }

    /** 哪一段进哪个文件（顺序 = 写出顺序）。 */
    private static final String[][] SEGMENTS = {
            {"vars", VARS}, {"areas", AREAS}, {"decks", DECKS}, {"assets", ASSETS},
    };

    /**
     * 拆成「真文件」的段（一区域一文件 / 一张战利品表一文件 / 一张配方一文件 / 一条交易一文件 / 一个标签一文件 / 一条原版数据线一文件）。
     * 原版数据那几条（段B / 段C…）**从 {@link VanillaJson#SECTIONS} 那张表现拿** —— 表驱动不会漂（硬列过一处，段C 加对话框时漏了，被自检的「三处齐」闸门逮到）。
     */
    private static final java.util.Set<String> FILE_SEGMENTS = fileSegments();

    private static java.util.Set<String> fileSegments() {
        java.util.Set<String> s = new java.util.HashSet<>(
                java.util.List.of("areas", "loot", "recipe", "villager_trade", "tags"));
        for (String[] sec : VanillaJson.SECTIONS) {
            s.add(sec[0]);
        }
        return java.util.Set.copyOf(s);
    }

    private static boolean isFileSegment(String key) {
        return FILE_SEGMENTS.contains(key);
    }

    /** 这是不是一个「段」键（不是段 = 进 other.json 透传）。 */
    private static boolean isSegment(String key) {
        for (String[] seg : SEGMENTS) {
            if (seg[0].equals(key)) return true;
        }
        return false;
    }

    /**
     * 一整份档（{@code GameStore.toJson} 的产物）→ 新布局的各文件（相对路径 → 文本）。
     *
     * <p><b>老档迁移与新档写盘是同一个函数</b>：老档拆出来的东西，就是新档要写下去的东西。
     * 空段**不写文件**（与「空表不写段」同一口径，省体积；读侧缺文件 = 空）。
     */
    public static Map<String, byte[]> split(JsonObject full, Gson gson) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        JsonObject manifest = new JsonObject();                        // 清单：只留 format + meta
        if (full.has("format")) manifest.add("format", full.get("format"));
        if (full.has("meta")) manifest.add("meta", full.get("meta"));
        out.put(MANIFEST, text(gson.toJson(manifest)));

        String script = full.has("script") && full.get("script").isJsonPrimitive()
                ? full.get("script").getAsString() : "";
        out.put(SCRIPT, text(script));

        for (String[] seg : SEGMENTS) {
            if (isFileSegment(seg[0])) continue;   // 这三段要拆成「真文件」，见下
            JsonElement e = full.get(seg[0]);
            if (e != null && !e.isJsonNull()) out.put(seg[1], text(gson.toJson(e)));
        }
        // 区域：**一个区域一个文件**（原先是整段一个 data/areas.json）。区域又多又大时，改一个不必重写全部，
        // diff / 手改 / 单独拿出来看也都只动那一份。
        JsonElement ar = full.get("areas");
        if (ar != null && ar.isJsonArray()) {
            int i = 0;
            for (JsonElement el : ar.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString() : "";
                if (id.isEmpty()) continue;                            // 没 id 的条目落不了文件（读不回来，跳过）
                out.put(areaRef(i++, id), text(gson.toJson(o)));
            }
        }
        // 战利品表：**一张表一个文件**（原版 loot_table 格式原样落盘 —— 手改 / 外部工具 / diff 都只动那一份），
        // 表名 → 路径的索引单独一份（表名可能带 & / 中文，文件名要能反查）。
        JsonElement lt = full.get("loot");
        if (lt != null && lt.isJsonObject()) {
            JsonObject idx = new JsonObject();
            for (String nm : lt.getAsJsonObject().keySet()) {
                JsonElement t = lt.getAsJsonObject().get(nm);
                if (t == null || !t.isJsonObject()) continue;
                out.put(lootRef(nm), text(gson.toJson(t)));
                idx.addProperty(nm, lootRef(nm));
            }
            if (idx.size() > 0) out.put(LOOT_INDEX, text(gson.toJson(idx)));
        }
        // 配方：同上一套 —— **一张配方一个文件**（原版 recipe JSON 原样落盘，
        // 手改 / 外部工具 / diff 都只动那一份）+ 一份索引（配方名可能带中文，文件名要能反查）。
        JsonElement rc = full.get("recipe");
        if (rc != null && rc.isJsonObject()) {
            JsonObject ridx = new JsonObject();
            for (String nm : rc.getAsJsonObject().keySet()) {
                JsonElement r = rc.getAsJsonObject().get(nm);
                if (r == null || !r.isJsonObject()) continue;
                out.put(recipeRef(nm), text(gson.toJson(r)));
                ridx.addProperty(nm, recipeRef(nm));
            }
            if (ridx.size() > 0) out.put(RECIPE_INDEX, text(gson.toJson(ridx)));
        }
        // 村民交易：同上一套 —— **一条交易一个文件**（挂载信息住在文件内容里：
        // {profession, level, trade}，trade 那一层是**原版 villager_trade JSON 原样**）+ 一份索引。
        JsonElement vt = full.get("villager_trade");
        if (vt != null && vt.isJsonObject()) {
            JsonObject tidx = new JsonObject();
            for (String nm : vt.getAsJsonObject().keySet()) {
                JsonElement t = vt.getAsJsonObject().get(nm);
                if (t == null || !t.isJsonObject()) continue;
                out.put(tradeRef(nm), text(gson.toJson(t)));
                tidx.addProperty(nm, tradeRef(nm));
            }
            if (tidx.size() > 0) out.put(TRADES_INDEX, text(gson.toJson(tidx)));
        }
        // 标签：同上一套 —— **一个标签一个文件**（条目 = {type, values, replace}，
        // values / replace 是原版 TagFile 原样；type = 挂到哪个注册表目录）+ 一份索引。
        JsonElement tg = full.get("tags");
        if (tg != null && tg.isJsonObject()) {
            JsonObject tgidx = new JsonObject();
            for (String nm : tg.getAsJsonObject().keySet()) {
                JsonElement t = tg.getAsJsonObject().get(nm);
                if (t == null || !t.isJsonObject()) continue;
                out.put(tagRef(nm), text(gson.toJson(t)));
                tgidx.addProperty(nm, tagRef(nm));
            }
            if (tgidx.size() > 0) out.put(TAGS_INDEX, text(gson.toJson(tgidx)));
        }
        // 段B 三条：进度 / 魔咒 / 伤害类型 —— 一物一文件（原版 JSON 原样）+ 一份索引。
        for (String[] sec : VanillaJson.SECTIONS) {
            String d = sec[0];
            JsonElement se = full.get(d);
            if (se == null || !se.isJsonObject()) continue;
            JsonObject vIdx = new JsonObject();
            for (String nm : se.getAsJsonObject().keySet()) {
                JsonElement e = se.getAsJsonObject().get(nm);
                if (e == null || e.isJsonNull()) continue;
                String ref = VanillaJson.fileRef(d, nm);
                out.put(ref, text(gson.toJson(e)));
                vIdx.addProperty(nm, ref);
            }
            if (vIdx.size() > 0) out.put(d + "/index.json", text(gson.toJson(vIdx)));
        }
        // 组件：索引留下元数据（kind / name / w / h / base / lore），**像素与蓝图落成真文件**，
        // 索引里只留相对路径（一个文件一类内容：图能直接看、能外部编辑、能 diff）。
        JsonElement as = full.get("assets");
        if (as != null && as.isJsonArray()) {
            JsonArray index = new JsonArray();
            for (JsonElement el : as.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject().deepCopy();
                String nm = o.has("name") ? o.get("name").getAsString() : "";
                if (o.has("px") && o.get("px").isJsonPrimitive() && !o.get("px").getAsString().isEmpty()) {
                    // in-memory 的 px = base64(.px 二进制) —— 直接解开落盘，读侧再 base64 回去，逐字节不变
                    out.put(pxRef(nm), java.util.Base64.getDecoder().decode(o.get("px").getAsString()));
                    o.addProperty("px", pxRef(nm));
                }
                if (o.has("bp") && o.get("bp").isJsonObject()) {
                    out.put(bpRef(nm), text(gson.toJson(o.get("bp"))));
                    o.addProperty("bp", bpRef(nm));                    // 引用是字符串了（原来这里是个对象）
                }
                index.add(o);
            }
            out.put(ASSETS, text(gson.toJson(index)));
        }
        JsonObject other = new JsonObject();                           // 退役 / 未知段：原样透传
        for (String k : full.keySet()) {
            // ⚠ 「拆成真文件的段」（区域 / 战利品表 / 配方）**不进 other.json** —— 它们已经有自己的文件，
            //   在这儿再留一份 = 盘上两个真源：手改 loot/x.json （或添一张）会被 other.json 里那份旧的顶掉。
            if (k.equals("format") || k.equals("meta") || k.equals("script") || isSegment(k)
                    || isFileSegment(k)) continue;
            other.add(k, full.get(k));
        }
        if (other.size() > 0) out.put(OTHER, text(gson.toJson(other)));
        return out;
    }

    private static byte[] text(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String asText(byte[] b) {
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 新布局的各文件 → 一整份档（喂给 {@code GameStore.fromJson}）。缺文件 = 那个段为空。 */
    public static JsonObject join(Map<String, byte[]> files) {
        JsonObject full = new JsonObject();
        JsonObject m = asObject(asText(files.get(MANIFEST)));
        if (m != null) {
            for (String k : m.keySet()) full.add(k, m.get(k));         // format + meta
        }
        for (String[] seg : SEGMENTS) {
            if (isFileSegment(seg[0])) continue;
            JsonElement e = parse(asText(files.get(seg[1])));
            if (e != null) full.add(seg[0], e);
        }
        // 区域：一区域一文件（**文件名排序 = 序号 = 原数组顺序**）；
        // 一个都没有时再认**老档的单文件** data/areas.json（读到即迁：下次保存就落成新布局）。
        JsonArray areas = new JsonArray();
        java.util.List<String> areaRefs = new java.util.ArrayList<>();
        for (String k : files.keySet()) {
            if (k.startsWith(AREAS_DIR) && k.endsWith(".json")) areaRefs.add(k);
        }
        areaRefs.sort(null);
        for (String k : areaRefs) {
            JsonElement e = parse(asText(files.get(k)));
            if (e != null && e.isJsonObject()) areas.add(e);           // 坏文件（手改坏）跳过，不掐
        }
        if (areas.size() == 0) {
            JsonElement legacy = parse(asText(files.get(AREAS)));
            if (legacy != null && legacy.isJsonArray()) areas = legacy.getAsJsonArray();
        }
        if (areas.size() > 0) full.add("areas", areas);
        // 战利品表：按索引把 loot/*.json 读回成 loot 段（索引里没有的表 = 手写多出来的文件，**也认** ——
        // 表名 = 文件名去掉目录与扩展名，作者手放一张表就能直接用）。
        JsonObject lootIdx = asObject(asText(files.get(LOOT_INDEX)));
        JsonObject loot = new JsonObject();
        if (lootIdx != null) {
            // **索引在 = 只认索引**：删掉一张表后磁盘上留下的旧文件不会把它「复活」
            for (String nm : lootIdx.keySet()) {
                if (!lootIdx.get(nm).isJsonPrimitive()) continue;
                JsonElement t = parse(asText(files.get(lootIdx.get(nm).getAsString())));
                if (t != null && t.isJsonObject()) loot.add(nm, t);
            }
        } else {
            // 没有索引（手放的表）：按文件名当表名收进来
            for (String k : files.keySet()) {
                if (!k.startsWith("loot/") || !k.endsWith(".json") || k.equals(LOOT_INDEX)) continue;
                JsonElement t = parse(asText(files.get(k)));
                if (t == null || !t.isJsonObject()) continue;
                loot.add(k.substring("loot/".length(), k.length() - ".json".length()), t);
            }
        }
        if (loot.size() > 0) full.add("loot", loot);
        // 配方：同一套 —— 一张配方一个文件 + 一份索引（索引在 = 只认索引；
        // 没有索引就按文件名当配方名收进来，手放的也认）。
        JsonObject recIdx = asObject(asText(files.get(RECIPE_INDEX)));
        JsonObject recipe = new JsonObject();
        if (recIdx != null) {
            for (String nm : recIdx.keySet()) {
                if (!recIdx.get(nm).isJsonPrimitive()) continue;
                JsonElement r = parse(asText(files.get(recIdx.get(nm).getAsString())));
                if (r != null && r.isJsonObject()) recipe.add(nm, r);
            }
        } else {
            for (String k : files.keySet()) {
                if (!k.startsWith(RECIPE_DIR) || !k.endsWith(".json") || k.equals(RECIPE_INDEX)) continue;
                JsonElement r = parse(asText(files.get(k)));
                if (r == null || !r.isJsonObject()) continue;
                recipe.add(k.substring(RECIPE_DIR.length(), k.length() - ".json".length()), r);
            }
        }
        if (recipe.size() > 0) full.add("recipe", recipe);
        // 村民交易：同一套 —— 一条交易一个文件 + 一份索引（索引在 = 只认索引；
        // 没有索引就按文件名当交易名收进来，手放的也认）。
        JsonObject trIdx = asObject(asText(files.get(TRADES_INDEX)));
        JsonObject trades = new JsonObject();
        if (trIdx != null) {
            for (String nm : trIdx.keySet()) {
                if (!trIdx.get(nm).isJsonPrimitive()) continue;
                JsonElement t = parse(asText(files.get(trIdx.get(nm).getAsString())));
                if (t != null && t.isJsonObject()) trades.add(nm, t);
            }
        } else {
            for (String k : files.keySet()) {
                if (!k.startsWith(TRADES_DIR) || !k.endsWith(".json") || k.equals(TRADES_INDEX)) continue;
                JsonElement t = parse(asText(files.get(k)));
                if (t == null || !t.isJsonObject()) continue;
                trades.add(k.substring(TRADES_DIR.length(), k.length() - ".json".length()), t);
            }
        }
        if (trades.size() > 0) full.add("villager_trade", trades);
        // 标签：同一套 —— 一个标签一个文件 + 一份索引（索引在 = 只认索引；
        // 没有索引就按文件名当标签名收进来，手放的也认）。
        // 条目形状 = {type, values, replace}：**type 是挂载信息**（写进哪个注册表目录），
        // values / replace 是**原版 TagFile 原样**（`data/<ns>/tags/<type>/<路径>.json`）。
        JsonObject tagIdx = asObject(asText(files.get(TAGS_INDEX)));
        JsonObject tags = new JsonObject();
        if (tagIdx != null) {
            for (String nm : tagIdx.keySet()) {
                if (!tagIdx.get(nm).isJsonPrimitive()) continue;
                JsonElement t = parse(asText(files.get(tagIdx.get(nm).getAsString())));
                if (t != null && t.isJsonObject()) tags.add(nm, t);
            }
        } else {
            for (String k : files.keySet()) {
                if (!k.startsWith(TAGS_DIR) || !k.endsWith(".json") || k.equals(TAGS_INDEX)) continue;
                JsonElement t = parse(asText(files.get(k)));
                if (t == null || !t.isJsonObject()) continue;
                tags.add(k.substring(TAGS_DIR.length(), k.length() - ".json".length()), t);
            }
        }
        if (tags.size() > 0) full.add("tags", tags);
        // 段B 三条：进度 / 魔咒 / 伤害类型 —— 同一套（一物一文件 + 索引；原版 JSON 原样）。
        for (String[] sec : VanillaJson.SECTIONS) {
            String d = sec[0];
            JsonObject vIdx = asObject(asText(files.get(d + "/index.json")));
            JsonObject got = new JsonObject();
            if (vIdx != null) {
                for (String nm : vIdx.keySet()) {
                    if (!vIdx.get(nm).isJsonPrimitive()) continue;
                    JsonElement e = parse(asText(files.get(vIdx.get(nm).getAsString())));
                    if (e != null) got.add(nm, e);
                }
            } else {
                for (String k : files.keySet()) {
                    if (!k.startsWith(d + "/") || !k.endsWith(".json") || k.endsWith("/index.json")) continue;
                    JsonElement e = parse(asText(files.get(k)));
                    if (e == null) continue;
                    got.add(k.substring(d.length() + 1, k.length() - ".json".length()), e);
                }
            }
            if (got.size() > 0) full.add(d, got);
        }
        // 组件索引 → 把 art/ model/ 里的内容读回来（px 变回 base64、bp 变回对象）
        JsonElement as = parse(asText(files.get(ASSETS)));
        if (as != null && as.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : as.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject().deepCopy();
                if (o.has("px") && o.get("px").isJsonPrimitive()) {
                    byte[] raw = files.get(o.get("px").getAsString());
                    if (raw != null) o.addProperty("px", java.util.Base64.getEncoder().encodeToString(raw));
                }
                if (o.has("bp") && o.get("bp").isJsonPrimitive()) {
                    JsonElement bp = parse(asText(files.get(o.get("bp").getAsString())));
                    if (bp != null && bp.isJsonObject()) o.add("bp", bp);
                }
                out.add(o);
            }
            full.add("assets", out);
        }
        JsonObject other = asObject(asText(files.get(OTHER)));
        if (other != null) {
            for (String k : other.keySet()) full.add(k, other.get(k));
        }
        full.addProperty("script", files.containsKey(SCRIPT) ? asText(files.get(SCRIPT)) : "");
        return full;
    }

    /** 文本 → JSON（空 / 不是 JSON = null：手改坏了不掐局，那个段当空）。 */
    private static JsonElement parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return JsonParser.parseString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject asObject(String s) {
        JsonElement e = parse(s);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
