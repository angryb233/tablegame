package com.tablegame.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tablegame.script.Ast;
import com.tablegame.script.edit.ScriptEdit;
import com.tablegame.script.Parser;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardStore;
import com.tablegame.editor.data.VanillaJson;
import com.tablegame.editor.pack.AssetStore;

/**
 * 游戏定义的文件读写 —— schema 的**唯一真源**：解析 / 写回 / 校验（{@link #validate}）都只在这里写一份。
 * 编辑器只拼内存对象，服务端保存前调这里的校验 —— 信任边界只有一处。
 *
 * <p>JSON 形态（可手改，pretty print）：
 * <pre>
 * {
 *   "format":  "tablegame.schema/0",
 *   "meta":    { "name": "21点" },
 *   "cards":   [ { "id":"c1", "art":"组/项目名", "back":"blue",
 *                  "fields":{"point":5}, "fieldDefs":{"suit":["spades","hearts"]} } ],
 *   "decks":   [ { "id":"main", "from":["c1","c2"], "shuffle":true } ]   (from = 卡 id 列表)
 *   "pieces":  [ { "id":"p1", "blueprint":"组/蓝图名", "name":"红方战舰",
 *                  "fields":{"hp":10}, "fieldDefs":{…} } ],
 *   "vars":    [ { "id":"score", "name":"分数", "scope":"global", "type":"value", "init":0 } ],
 *   "script":  "// 你画我猜\nvar score = rec()\non start { … }"   ← schema/4：规则真源（纯文本）
 *   ...其余段落（areas / flow / recipe / tags…）原样保留，本类不解析
 * }
 * </pre>
 * ⛔ {@code teams} / {@code actions} / {@code stage} / {@code rules} 四段不解析、不生成，老档跟着 rest 原样透传
 * （{@code stage} 只有发给客户端的**线口径** {@code toJsonWire} 才带）。
 *
 * <p>pieces 的旧格式（纯字符串数组 {@code ["组/蓝图名"]}）读取时自动升级成对象（id 按 p1、p2… 避让生成），保存时落新格式 —— 旧文件不用手动改。
 */
public class GameStore {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** 屏幕画布的默认尺寸（stage.ui 缺 size 时用；16:9）。 */
    private static final int DEFAULT_UI_W = 320;
    private static final int DEFAULT_UI_H = 180;

    private final Path root;   // tablegame/games

    public GameStore(Path gameDir) {
        this.root = gameDir.resolve("tablegame").resolve("games");
    }

    public Path root() {
        return root;
    }

    /** 游戏名 → 防穿越后的安全名（目录名与档内 name 都用它）。 */
    private static String safe(String name) {
        return BoardStore.safeName(name);
    }

    /** 游戏目录 = games/<安全名>/。外部资源（art/music/world/data）放它下面。 */
    public Path dirOf(String name) {
        return root.resolve(safe(name));
    }

    private Path fileOf(String name) {
        return dirOf(name).resolve("game.json");
    }

    /** 老位置（#26 之前）：games/<名>.json 单文件。首次访问时迁进文件夹。 */
    private Path legacyFileOf(String name) {
        return root.resolve(safe(name) + ".json");
    }

    /**
     * 老档迁移（幂等，首次访问该游戏时触发）：老位置存在且新位置不在 → Files.move 整文件进
     * games/<名>/game.json（同盘 move = 改名，快）。资源目录两边都不动（老档本来就没有）。
     */
    private void migrateLegacy(String name) {
        Path legacy = legacyFileOf(name);
        if (!Files.isRegularFile(legacy)) return;
        try {
            Path f = fileOf(name);
            Files.createDirectories(f.getParent());
            Files.move(legacy, f);                            // 新位置已存在时不覆盖（Files.move 默认）
        } catch (IOException e) {
            TableGame.LOGGER.error("[游戏] 迁移老档失败 {}: {}", name, e.toString());
        }
    }

    public boolean exists(String name) {
        return Files.isRegularFile(fileOf(name)) || Files.isRegularFile(legacyFileOf(name));
    }

    /** 删除整个游戏目录（含清单 / src / data / asset…）；不存在 = false。调用方先做提示级校验。 */
    public boolean delete(String name) {
        Path dir = dirOf(name);
        boolean had = deleteTree(dir);
        had |= deleteTree(sibling(name, ".saving"));           // 保存中间态也清掉，别留垃圾
        had |= deleteTree(sibling(name, ".old"));
        try {
            had |= Files.deleteIfExists(legacyFileOf(name));   // 老位置的散文件也要清（迁移失败时留着的那份）
        } catch (IOException e) {
            TableGame.LOGGER.error("[游戏] 删老位置散文件失败 {}: {}", name, e.toString());
        }
        return had;
    }

    /** 递归删一棵目录 / 一个文件（深度优先）；真删了东西 = true。 */
    private static boolean deleteTree(Path p) {
        if (p == null || !Files.exists(p)) return false;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 保存时**原样带走**的子目录（属于玩家 / 外部导入的东西，不是游戏定义生成的）。 */
    private static final String[] KEEP_DIRS = {"players", "art", "music", "model", "world"};

    /** 递归拷贝一棵目录（同名覆盖）。 */
    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** `games/<名><后缀>`（保存中间态用：`.saving` / `.old`）。 */
    private Path sibling(String name, String suffix) {
        return root.resolve(safe(name) + suffix);
    }

    /**
     * 读游戏定义；不存在 / 损坏 → null（调用方提示，不抛异常）。老位置有散文件先迁移。
     * **两种布局都认**（先能读新的，再谈停写老的）：新布局 = {@code game.json} 只有 format + meta，程序在 {@code src/main.tg}、数据在 {@code data/*.json}、
     * 组件在 {@code asset/components.json}（几份拼回一整份）；老布局 = 一套全在 {@code game.json} 里 → 原样读（老档打不开不能接受），
     * 读完**就地迁成新布局**（幂等，通行证 = 这次读成功了）。判据 = 清单里除了 format / meta 还有没有别的键。
     */
    public GameDefinition load(String name) {
        heal(name);
        migrateLegacy(name);
        Path f = fileOf(name);
        if (!Files.isRegularFile(f)) return null;
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject manifest = JsonParser.parseString(text).getAsJsonObject();
            if (!isOldLayout(manifest)) return fromJson(name, GameLayout.join(readLayout(name)));
            // 老档读到即迁：读成功了就地写一次新布局（幂等：下次读到的是薄清单，不再走这条）。
            // 「读成功」本身就是迁移的通行证：模型建得出来 = 这份档拆得开；读崩了（异常）由外层接住，
            // 什么也不写（宁可老档原样留着让人修，也别写出一份更糟的）。
            GameDefinition old = fromJson(name, manifest);
            save(old);
            TableGame.LOGGER.info("[游戏] {} 老档已迁成新布局（game.json + src/ + data/ + asset/）", name);
            return old;
        } catch (Exception e) {
            TableGame.LOGGER.error("[游戏] 读定义失败 {}: {}", f, e.toString());
            return null;
        }
    }

    /** 老布局的判据：清单里除了 format / meta 还有别的键（老档把段和程序都塞在同一个文件里）。 */
    private static boolean isOldLayout(JsonObject manifest) {
        for (String k : manifest.keySet()) {
            if (!k.equals("format") && !k.equals("meta")) return true;
        }
        return false;
    }

    /** 把新布局那几个文件读进来（缺的就不放进表里 = 那个段为空）。 */
    private Map<String, byte[]> readLayout(String name) {
        Path dir = dirOf(name);
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String rel : new String[]{GameLayout.MANIFEST, GameLayout.SCRIPT, GameLayout.VARS,
                GameLayout.AREAS, GameLayout.DECKS, GameLayout.ASSETS, GameLayout.OTHER}) {
            Path p = dir.resolve(rel);
            if (!Files.isRegularFile(p)) continue;
            try {
                files.put(rel, Files.readAllBytes(p));
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读 {} 失败: {}", p, e.toString());
            }
        }
        // 资产内容：art/ 与 model/ 下的真文件（索引里的相对路径引用靠它们解回内容）
        // 区域拆文件：`data/areas/` 下**每个文件**都是一个区域 —— 上面那份白名单里只有老的单文件
        // `data/areas.json`；不扫这个目录，拆出去的区域就读不回来（服务端当场说「区域不存在」）。
        Path areasDir = dir.resolve(GameLayout.AREAS_DIR);
        if (Files.isDirectory(areasDir)) {
            try (Stream<Path> list = Files.list(areasDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(GameLayout.AREAS_DIR + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读区域目录失败 {}: {}", areasDir, e.toString());
            }
        }
        for (String sub : new String[]{"art", "model"}) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> list = Files.list(d)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(sub + "/" + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读 {} 失败: {}", d, e.toString());
            }
        }
        // 战利品表：`loot/` 下**每个文件**都是一张表（一张表一个文件 + 一份索引）。
        // ⚠ 这里漏扫 = 读档看不见表 ⇒ 内存定义里没这段 ⇒ 下次保存「整目录换名」时把那些文件删掉
        Path lootDir = dir.resolve(GameLayout.LOOT_DIR);
        if (Files.isDirectory(lootDir)) {
            try (Stream<Path> list = Files.list(lootDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(GameLayout.LOOT_DIR + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读战利品表目录失败 {}: {}", lootDir, e.toString());
            }
        }
        // 配方：同一套 —— `recipe/` 下**每个文件**都是一张配方（一张配方一个文件 + 一份索引）。
        Path recipeDir = dir.resolve(GameLayout.RECIPE_DIR);
        if (Files.isDirectory(recipeDir)) {
            try (Stream<Path> list = Files.list(recipeDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(GameLayout.RECIPE_DIR + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读配方目录失败 {}: {}", recipeDir, e.toString());
            }
        }
        // 村民交易：同一套 —— `villager_trade/` 下**每个文件**都是一条交易
        //（一条交易一个文件 + 一份索引）。⚠ 漏扫这一处的表现最阴：读档看不见这段 ⇒ 内存里没有它
        // ⇒ 下次保存「整目录换名」把它们删掉，而游戏里看起来与原版一模一样。
        Path tradeDir = dir.resolve(GameLayout.TRADES_DIR);
        if (Files.isDirectory(tradeDir)) {
            try (Stream<Path> list = Files.list(tradeDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(GameLayout.TRADES_DIR + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读村民交易目录失败 {}: {}", tradeDir, e.toString());
            }
        }
        // 标签：同一套 —— `tags/` 下**每个文件**都是一个标签（一个标签一个文件 + 一份索引）。
        // ⚠ 同一处漏扫就静默丢数据（上面那两条注释说的就是它）。
        Path tagDir = dir.resolve(GameLayout.TAGS_DIR);
        if (Files.isDirectory(tagDir)) {
            try (Stream<Path> list = Files.list(tagDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(GameLayout.TAGS_DIR + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读标签目录失败 {}: {}", tagDir, e.toString());
            }
        }
        // 段B 三条：进度 / 魔咒 / 伤害类型 —— `advancement/` `enchantment/` `damage_type/`。
        // ⚠ 这三处漏扫 = 读档看不见 ⇒ 下次保存「整目录换名」把它们删掉，而游戏里看不出来。
        for (String[] sec : VanillaJson.SECTIONS) {
            String d = sec[0];
            Path secDir = dir.resolve(d);
            if (!Files.isDirectory(secDir)) continue;
            try (Stream<Path> list = Files.list(secDir)) {
                for (Path f : list.toList()) {
                    if (!Files.isRegularFile(f)) continue;
                    files.put(d + "/" + f.getFileName(), Files.readAllBytes(f));
                }
            } catch (IOException e) {
                TableGame.LOGGER.error("[游戏] 读 {} 目录失败 {}: {}", d, secDir, e.toString());
            }
        }
        return files;
    }

    /**
     * 保存 = 写整个游戏目录。调用方保证在服务端线程。
     * **原子策略 = 整目录换名**：① 整份写进 {@code games/<名>.saving/} ② 老目录改名 {@code .old/}、新目录顶上 ③ 删 {@code .old}。
     * 中途崩了由 {@link #heal} 自愈（新目录没顶上来 → 把 {@code .old} 改回来；半份 {@code .saving} → 删掉）。
     */
    public void save(GameDefinition def) {
        heal(def.name());
        migrateLegacy(def.name());
        try {
            Path dir = dirOf(def.name());
            Path saving = sibling(def.name(), ".saving");
            Path old = sibling(def.name(), ".old");
            Files.createDirectories(root);                              // ⚠ 全新存档 tablegame/ 可能还不存在
            deleteTree(saving);                                         // 上一次崩在这儿的半份先清掉
            // ⓪ 先把「属于玩家的东西」搬过去：**整目录换名会把它们一起换掉** ——
            //    players/（档案，一人一份）· art|music|model|world/ 里手工放进去的文件（外部导入的图/音频）。
            //    不搬 = 「一保存，玩家数据和外来的图全没」。
            for (String keep : KEEP_DIRS) {
                Path from = dir.resolve(keep);
                if (Files.isDirectory(from)) copyTree(from, saving.resolve(keep));
            }
            Map<String, byte[]> files = GameLayout.split(toJson(def), PRETTY);
            for (Map.Entry<String, byte[]> en : files.entrySet()) {      // ① 整份写进 .saving/
                Path p = saving.resolve(en.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, en.getValue());                          // 文本 / 二进制都走这条
            }
            deleteTree(old);                                            // ② 老目录让位 → 新目录顶上
            if (Files.exists(dir)) Files.move(dir, old);
            Files.move(saving, dir);
            deleteTree(old);                                            // ③ 旧的删掉
        } catch (IOException e) {
            TableGame.LOGGER.error("[游戏] 存定义失败 {}: {}", def.name(), e.toString());
        }
    }

    /**
     * 自愈上一次保存留下的中间态（三条纪律里的「最容易出档坏了」那一处）：
     * 目录被让位了、新的没顶上来 → 把 `.old` 改回来；`.saving` 是半份新档 → 删掉。
     */
    private void heal(String name) {
        try {
            Path dir = dirOf(name);
            Path old = sibling(name, ".old");
            Path saving = sibling(name, ".saving");
            if (!Files.exists(dir) && Files.exists(old)) {
                Files.createDirectories(root);
                Files.move(old, dir);
                TableGame.LOGGER.info("[游戏] {} 上次保存中断，已把旧目录改回来", name);
            }
            if (Files.exists(saving)) deleteTree(saving);
        } catch (IOException e) {
            TableGame.LOGGER.error("[游戏] 自愈保存中间态失败 {}: {}", name, e.toString());
        }
    }

    /**
     * 扫描全部游戏名（按字母序）= **目录式**（games/&lt;名&gt;/game.json，取目录名）
     * ∪ **老散文件**（games/&lt;名&gt;.json，取文件名去 .json——还没被访问触发迁移的也算数）。
     */
    public List<String> listNames() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.list(root)) {
                files.forEach(p -> {
                    String n = p.getFileName().toString();
                    if (Files.isDirectory(p)) {
                        if (n.endsWith(".saving") || n.endsWith(".old")) return;   // 保存中间态，不是游戏
                        out.add(n);                            // 目录式：目录名 = 游戏名
                    } else if (n.endsWith(".json")) {
                        out.add(n.substring(0, n.length() - 5)); // 老散文件（待迁移）
                    }
                });
            } catch (IOException ignored) {}
        }
        return new ArrayList<>(out);
    }

    // ===== JSON ⇄ 模型（schema 唯一真源）=====

    /**
     * JSON → 模型（**磁盘口径**）。容忍缺段（cards/decks/pieces/vars/script 缺 = 空）。
     *
     */
    public static GameDefinition fromJson(String name, JsonObject root) {
        return read(name, root, false);
    }

    /**
     * JSON → 模型（**线口径**）：宿主发来的那份带着它按人合成的舞台，客户端要读它才画得出来。
     *
     * <p>两个口径的分工：<b>盘上不存舞台</b>（真源是脚本）· <b>线上要传舞台</b>（客户端没有解释器）。
     */
    public static GameDefinition fromJsonWire(String name, JsonObject root) {
        return read(name, root, true);
    }

    private static GameDefinition read(String name, JsonObject root, boolean withStage) {
        // 简介（meta.desc）：缺省空串 —— 老档没这个键，读到就是「没写简介」
        String desc = "";
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            if (meta.has("desc") && meta.get("desc").isJsonPrimitive()) {
                desc = meta.get("desc").getAsString();
            }
        }
        List<GameDefinition.CardDef> cards = new ArrayList<>();
        if (root.has("cards")) {
            for (var el : root.getAsJsonArray("cards")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;   // 无 id 的卡无法被引用，跳过
                cards.add(new GameDefinition.CardDef(
                        o.get("id").getAsString(),
                        o.has("art") ? o.get("art").getAsString() : "",
                        o.has("back") ? o.get("back").getAsString() : "blue",
                        o.has("fields") ? o.getAsJsonObject("fields") : null,
                        o.has("fieldDefs") ? o.getAsJsonObject("fieldDefs") : null,
                        // 显示名（老档没这个键 ⇒ 空串 ⇒ 显示口退回 id，一字不变）
                        o.has("name") ? o.get("name").getAsString() : ""));
            }
        }
        List<GameDefinition.DeckDef> decks = new ArrayList<>();
        if (root.has("decks")) {
            for (var el : root.getAsJsonArray("decks")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;
                List<String> from = new ArrayList<>();
                if (o.has("from")) o.getAsJsonArray("from").forEach(x -> from.add(x.getAsString()));
                decks.add(new GameDefinition.DeckDef(
                        o.get("id").getAsString(), from,
                        o.has("shuffle") && o.get("shuffle").getAsBoolean()));
            }
        }
        // 棋子实例：对象格式 {id, blueprint, name, fields, fieldDefs}。
        // 旧格式（纯字符串 "组/蓝图名"）自动升级：id 避让生成、name 空、无属性。
        List<GameDefinition.PieceDef> pieces = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();   // 升级/去重时保证 id 唯一
        if (root.has("pieces")) {
            for (var el : root.getAsJsonArray("pieces")) {
                if (el.isJsonPrimitive()) {   // 旧格式字符串 → 升级为对象
                    pieces.add(new GameDefinition.PieceDef(
                            nextPieceId(usedIds), el.getAsString(), "", null, null, 1.0));
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (!o.has("blueprint") || o.get("blueprint").isJsonNull()) continue;  // 无蓝图引用的实例没有意义
                String bp = o.get("blueprint").getAsString();
                String id = o.has("id") && o.get("id") instanceof JsonPrimitive prim && prim.isString()
                        && !prim.getAsString().isBlank() ? prim.getAsString() : nextPieceId(usedIds);
                if (!usedIds.add(id)) id = nextPieceId(usedIds);   // id 撞了（伪造/手改）→ 重新避让
                pieces.add(new GameDefinition.PieceDef(
                        id, bp,
                        o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "",
                        o.has("fields") ? o.getAsJsonObject("fields") : null,
                        o.has("fieldDefs") ? o.getAsJsonObject("fieldDefs") : null,
                        // 尺寸倍率（棋子页那个自由数字框）：不写 = 1 = 蓝图原尺寸（老档零变化）
                        o.has("scale") && o.get("scale").isJsonPrimitive() ? o.get("scale").getAsDouble() : 1.0));
            }
        }
        // 槽位（schema/2）：vars[] = {id, name, scope, type, init}。
        // ⛔ 旧档里可能有的 vis / role 两键（只写不读）：解析时直接忽略。
        // init 原样保留 JSON 元素（单值或数组），类型由 type 决定——校验在 validate 里报错，
        // 读取侧只做「缺 type 当 value、缺 scope 当 global、缺 init 给合理默认」，不静默改写玩家的值。
        List<GameDefinition.VarDef> vars = new ArrayList<>();
        if (root.has("vars")) {
            for (var el : root.getAsJsonArray("vars")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;   // 无 id 的变量无法被规则引用，跳过
                // 兼容读旧的 kind：pool → list（项池合并进列表）
                String type = o.has("type") ? jstr(o, "type", "value")
                        : ("pool".equals(jstr(o, "kind", "value")) ? "list" : jstr(o, "kind", "value"));
                String scope = jstr(o, "scope", "global");
                JsonElement init = o.has("init") && !o.get("init").isJsonNull()
                        ? o.get("init").deepCopy()
                        : ("list".equals(type) ? new JsonArray()
                                : ("seat".equals(type) ? new JsonPrimitive("") : new JsonPrimitive(0)));
                vars.add(new GameDefinition.VarDef(
                        o.get("id").getAsString(), jstr(o, "name", ""), scope, type, init));
            }
        }
        // ⛔ actions / stage 两段不再从盘上解析（对象与舞台都改走脚本；只有线口径 fromJsonWire 读 stage）。
        GameDefinition.StageDef stage = GameDefinition.StageDef.empty();
        if (withStage && root.has("stage") && root.get("stage").isJsonObject()) {
            JsonObject so = root.getAsJsonObject("stage");
            stage = new GameDefinition.StageDef(jui(so, "ui"));
            stage.setMode(jstr(so, "mode", GameDefinition.StageDef.MODE_HUD));
            stage.currentFull(so.has("curFull") ? so.get("curFull").getAsString() : null);
            stage.currentHud(so.has("curHud") ? so.get("curHud").getAsString() : null);
            stage.hideSeq(so.has("hideSeq") ? so.get("hideSeq").getAsInt() : 0);
            stage.hiddenName(so.has("hiddenName") ? so.get("hiddenName").getAsString() : null);
            if (so.has("views")) {
                for (var el : so.getAsJsonArray("views")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject vo = el.getAsJsonObject();
                    if (!vo.has("id")) continue;
                    stage.views().add(new GameDefinition.StageView(
                            vo.get("id").getAsString(), jstr(vo, "name", ""), jstr(vo, "bg", ""),
                            vo.has("hud") && vo.get("hud").isJsonPrimitive() && vo.get("hud").getAsBoolean(),
                            vo.has("w") ? jnum(vo, "w") : 320, vo.has("h") ? jnum(vo, "h") : 180,
                            vo.has("hudX") ? jnum(vo, "hudX") : GameDefinition.StageView.DEF_HUD_X,
                            vo.has("hudY") ? jnum(vo, "hudY") : GameDefinition.StageView.DEF_HUD_Y,
                            vo.has("hudW") ? jnum(vo, "hudW") : GameDefinition.StageView.DEF_HUD_W,
                            vo.has("hudH") ? jnum(vo, "hudH") : GameDefinition.StageView.DEF_HUD_H,
                            !vo.has("clickable") || vo.get("clickable").getAsBoolean(),
                            readComponents(vo.has("components") ? vo.getAsJsonArray("components") : null)));
                }
            }
        }

        // ⛔ 队伍（teams）段：**不再解析** —— 它留在 rest 里原样透传（老档零损失），运行时也不读它。

        // 脚本段（第五期 schema/4）：规则真源，纯文本。缺段 = 空（这份档开不了局）。
        String script = root.has("script") && root.get("script").isJsonPrimitive()
                ? root.get("script").getAsString() : "";

        // 未知段落快照：整树深拷贝后剜掉已解析的段（真源字段不再进 rest），保存时写回。
        // ⚠ 加了新段（vars / stage / script）就得在这里 remove —— 忘了会「写两份」且越写越多。
        // ⚠ meta **故意不 remove**：只读 name / desc 两键，其余键靠 rest 原样保住；写出时把 name / desc 合并回那份 meta —— 先 remove 再新建会静默丢掉 author / players。
        // 世界区域：{@code areas[] = {id, sizeX, sizeY, sizeZ, palette[], blocks[]}}；空壳声明合法（缺 blocks = 未捕获），老档缺段 = 空表。
        java.util.List<GameDefinition.AreaDef> areas = new ArrayList<>();
        if (root.has("areas") && root.get("areas").isJsonArray()) {
            for (var el : root.getAsJsonArray("areas")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String aid = jstr(o, "id", "");
                if (aid.isBlank()) continue;                 // 无 id 的区域无法被引用，跳过
                java.util.List<net.minecraft.world.level.block.state.BlockState> pal = new ArrayList<>();
                if (o.has("palette") && o.get("palette").isJsonArray()) {
                    for (var pe : o.getAsJsonArray("palette")) {
                        if (!pe.isJsonPrimitive()) continue;
                        // palette 元素 = 方块 id 字符串（"minecraft:stone"）→ 查注册表取默认状态。
                        // （区域快照只存默认状态；带属性的方块状态将来要时再升级。）
                        // PieceData 那套走 BlockState.CODEC 要 RegistryOps，磁盘口径这里没有
                        // registry access，字符串 id + BuiltInRegistries 直查最省且可手改。
                        net.minecraft.world.level.block.state.BlockState st = blockFromString.apply(pe.getAsString());
                        if (st != null) pal.add(st);          // 认不出的方块（跨版本）跳过
                    }
                }
                int asx = o.has("sizeX") ? o.get("sizeX").getAsInt() : 0;
                int asy = o.has("sizeY") ? o.get("sizeY").getAsInt() : 0;
                int asz = o.has("sizeZ") ? o.get("sizeZ").getAsInt() : 0;
                // 逐格表认**两种盘上形态**：
                //   老档 = JSON 数字数组（每格一个十进制数）
                //   新档 = {@link AreaPacked} 的 base64 串（调色板下标位打包 + gzip）
                // 靠 JSON 类型区分，不升 schema 版本、读两种写新的 ⇒ 老档零迁移。
                java.util.List<Integer> blocks = new ArrayList<>();
                if (o.has("blocks") && o.get("blocks").isJsonArray()) {
                    for (var be : o.getAsJsonArray("blocks")) {
                        if (be.isJsonPrimitive() && be.getAsJsonPrimitive().isNumber()) {
                            blocks.add(be.getAsInt());
                        }
                    }
                } else if (o.has("blocks") && o.get("blocks").isJsonPrimitive()
                        && o.get("blocks").getAsJsonPrimitive().isString()) {
                    java.util.List<Integer> un = AreaPacked.unpack(o.get("blocks").getAsString(),
                            asx * asy * asz, pal.size());
                    if (un != null) {
                        blocks = un;
                    } else {
                        TableGame.LOGGER.warn("[区域] {} 的逐格表读不回来（形状/魔数对不上）—— 当未捕获",
                                aid);
                    }
                }
                // 语义格：`cells[] = {role, i, j, k}`（格内坐标）。老档没这一键 = 一个都没标。
                java.util.List<GameDefinition.AreaDef.CellMark> cells = new ArrayList<>();
                if (o.has("cells") && o.get("cells").isJsonArray()) {
                    for (var ce : o.getAsJsonArray("cells")) {
                        if (!ce.isJsonObject()) continue;
                        JsonObject co = ce.getAsJsonObject();
                        String role = jstr(co, "role", "");
                        if (role.isBlank()) continue;            // 没角色的标记没法被 shape_cell 取，跳过
                        cells.add(new GameDefinition.AreaDef.CellMark(role,
                                co.has("i") ? co.get("i").getAsInt() : 0,
                                co.has("j") ? co.get("j").getAsInt() : 0,
                                co.has("k") ? co.get("k").getAsInt() : 0));
                    }
                }
                areas.add(new GameDefinition.AreaDef(aid, asx, asy, asz, pal, blocks, cells));
            }
        }
        JsonObject rest = root.deepCopy();
        // 已导入的组件（#21 切片④）：{ kind, name, w, h, px(base64 ARGB) | bp(蓝图 JSON) }
        // 老档缺段 = 空（新增段不影响老档）；坏条目跳过。
        List<GameDefinition.AssetDef> assets = new ArrayList<>();
        if (root.has("assets") && root.get("assets").isJsonArray()) {
            for (var el : root.getAsJsonArray("assets")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String aname = jstr(o, "name", "");
                if (aname.isBlank()) continue;
                assets.add(new GameDefinition.AssetDef(
                        jstr(o, "kind", ""), aname,
                        o.has("w") ? o.get("w").getAsInt() : 0,
                        o.has("h") ? o.get("h").getAsInt() : 0,
                        jstr(o, "px", ""),
                        o.has("bp") && o.get("bp").isJsonObject() ? o.getAsJsonObject("bp") : null,
                        jstr(o, "base", ""),                       // 物品类：基底原版物品 id（老档缺 = 非物品）
                        GameDefinition.AssetDef.readLore(o)));
            }
        }
        rest.remove("assets");
        // ⚠ cards / pieces 两段**不再当真源**（棋牌声明进语言）：不再从段里当数据用、
        //    不再生成 —— 真源是脚本里的 card / piece 顶层声明（见下面「棋牌声明」那一段）。这里**故意不 remove**：
        //    老档那两份数据跟着 rest 原样落地（保数据，零损失）。
        rest.remove("decks");
        rest.remove("vars");
        // ⛔ teams / actions / stage / rules 四段**故意不 remove**：跟着 rest 原样落地（老档数据保住；新档不再生成）。
        rest.remove("script");                 // ⚠ 加了新段就必须在这里 remove，否则「写两份」越写越多
        rest.remove("areas");                  // ⚠ #23 同上

        // ---- 棋牌声明（真源 = 脚本里的顶层 card / piece 声明）----
        // 老档的 cards / pieces 两段：① 脚本里已有声明 → 段只当历史，不读 ② 还没有（老档第一次打开）→ **就地迁成声明文本追加到脚本末尾**（一次性、幂等）。
        // 迁完统一从**声明**建模型：渲染 / 运行时 / 编辑器三处读的都是它 —— 「谁是真源」只有一个答案。
        boolean scriptOk = true;
        try {
            Parser.parse(script);
        } catch (RuntimeException ex) {
            scriptOk = false;                       // 还没脚本 / 脚本不合法 → 这份档先别动它（段扛着，别写出一份跑不起来的脚本）
        }
        boolean migrated = false;
        if (scriptOk && (!cards.isEmpty() || !pieces.isEmpty())) {
            StringBuilder add = new StringBuilder();
            for (GameDefinition.CardDef c : cards) {
                if (ScriptEdit.declOf(script, "card", c.id()) == null) add.append('\n').append(cardCode(c));
            }
            for (GameDefinition.PieceDef p : pieces) {
                if (ScriptEdit.declOf(script, "piece", p.id()) == null) add.append('\n').append(pieceCode(p));
            }
            if (add.length() > 0) {
                if (!script.isEmpty() && !script.endsWith("\n")) script = script + "\n";
                script = script + add;
                migrated = true;
            }
            if (migrated) TableGame.LOGGER.info("棋牌声明迁移：{} —— cards {} 张 / pieces {} 枚已写成脚本声明",
                    name, cards.size(), pieces.size());
        }
        List<GameDefinition.CardDef> declCards = new ArrayList<>();
        List<GameDefinition.PieceDef> declPieces = new ArrayList<>();
        List<GameDefinition.AssetDef> declAssets = new ArrayList<>();     // 期7：脚本里的对象声明
        try {
            Ast.Script ast = Parser.parse(script);
            for (Ast.CardDecl c : ast.cards()) {
                GameDefinition.CardDef cd = toCardDef(c);
                if (cd != null) declCards.add(cd);
            }
            for (Ast.PieceDecl pd : ast.pieces()) {
                GameDefinition.PieceDef pdef = toPieceDef(pd);
                if (pdef != null) declPieces.add(pdef);
            }
            // 对象声明：block / item / entity / text 段 → AssetDef（段头 = 资产名）
            for (Ast.AssetDecl ad : ast.assetDecls()) {
                if (ad != null) declAssets.add(toAssetDef(ad));
            }
        } catch (RuntimeException ex) {
            // 脚本这份读不出来（不合法）：段里的那份先撑着 —— 报错交给校验那一关，这里不掐局、不吞错
            declCards = new ArrayList<>(cards);
            declPieces = new ArrayList<>(pieces);
        }
        if (declCards.isEmpty() && declPieces.isEmpty() && (!cards.isEmpty() || !pieces.isEmpty())) {
            declCards = new ArrayList<>(cards);
            declPieces = new ArrayList<>(pieces);
        }
        // 对象声明合进**同一份** assets —— 声明优先（同名声明盖掉档里那条）；档里那四类老对象原样留着（老档不管、他手动重建），美术类照旧只有档里有。
        // 合并键两步：先按显示名（name）让声明盖掉同显示名的老副本，再按资产名（ref）合并 —— 同显示名 = 同一对象的两份记录
        // （老条目没有独立 id，名字就是身份）。老副本的美术字段（px / bp / w / h）**声明缺了才带过去**（声明不能被老 bp 覆盖），base / lore 同理。
        // 这样候选▾与对象页都不再「显示名列一遍、资产名再列一遍」。
        java.util.Map<String, GameDefinition.AssetDef> byName = new java.util.LinkedHashMap<>();
        for (GameDefinition.AssetDef a : assets) {
            byName.put(a.name(), a);
        }
        List<GameDefinition.AssetDef> declMerged = new ArrayList<>();
        for (GameDefinition.AssetDef d : declAssets) {
            GameDefinition.AssetDef old = byName.remove(d.name());
            if (old == null) {
                declMerged.add(d);
                continue;
            }
            String px = d.px() == null || d.px().isEmpty() ? old.px() : d.px();
            JsonObject bp = d.bp() == null ? old.bp() : d.bp();
            String base = d.base() == null || d.base().isEmpty() ? old.base() : d.base();
            java.util.List<String> lore = d.lore() == null || d.lore().isEmpty() ? old.lore() : d.lore();
            declMerged.add(new GameDefinition.AssetDef(d.kind(), d.name(),
                    old.w() > 0 ? old.w() : d.w(), old.h() > 0 ? old.h() : d.h(),
                    px, bp, base, lore, d.id()));
        }
        java.util.Map<String, GameDefinition.AssetDef> byRef = new java.util.LinkedHashMap<>();
        for (GameDefinition.AssetDef a : byName.values()) {          // 没被声明认领的老条目照旧
            byRef.put(a.ref(), a);
        }
        for (GameDefinition.AssetDef a : declMerged) {
            byRef.put(a.ref(), a);
        }
        java.util.List<GameDefinition.AssetDef> allAssets = new java.util.ArrayList<>(byRef.values());
        return new GameDefinition(name, desc, declCards, decks, declPieces, vars, stage,
                script, allAssets, areas, rest);
    }

    // ---------- 棋牌声明 ↔ 模型（#23）----------
    //
    // 两侧都是**纯数据搬运**：声明 → CardDef/PieceDef（读侧）· CardDef/PieceDef → 声明文本（迁移侧）。
    // 值一律用源码原文（字符串带引号、数字裸写）—— 与编辑器写的形状一致，读回来逐字一样。

    /** 声明里的字面量 → 原文（字符串带引号 / 数字裸写 / 真假）。不是字面量 = null（这种属性**跳过**，不静默编一个值）。 */
    private static String exprSource(Ast.Expr e) {
        if (e instanceof Ast.Str x) return ScriptEdit.strCode(x.v());
        if (e instanceof Ast.Num x) {
            double d = x.v();
            return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (e instanceof Ast.Bool x) return x.v() ? "true" : "false";
        if (e instanceof Ast.ListLit l) {
            List<String> items = new ArrayList<>();
            for (Ast.Expr it : l.items()) {
                String one = exprSource(it);
                if (one == null) return null;
                items.add(one.startsWith("\"") ? one.substring(1, one.length() - 1) : one);
            }
            return ScriptEdit.listCode(items);
        }
        return null;
    }

    /** 声明里的字符串字面量 → Java 字符串（不是字符串 = 空串）。 */
    private static String strValue(Ast.Expr e) {
        return e instanceof Ast.Str x ? x.v() : "";
    }

    /** 声明里的数字 → double（不是数字 = 缺省）。 */
    private static double numValue(Ast.Expr e, double dflt) {
        return e instanceof Ast.Num x ? x.v() : dflt;
    }

    /**
     * card 声明 → CardDef（{@code art} / {@code back} / {@code name} 是结构键，其余一律当属性；键选项 {@code […]} = 枚举可选值）。
     * {@code name} 是结构键：段头 = **资产名**（{@code id}），{@code name} = **显示名**（不写 ⇒ 空串 ⇒ 退回 id）。
     * public = 自检直接调它断言「声明 → 模型」这一步（纯数据搬运）。
     */
    public static GameDefinition.CardDef toCardDef(Ast.CardDecl d) {
        String art = "";
        String back = "blue";
        String nm = "";                       // 期7 片3：显示名（不写 ⇒ 空串 ⇒ 显示口退回 id）
        JsonObject fields = new JsonObject();
        JsonObject defs = new JsonObject();
        for (Ast.Field f : d.fields()) {
            if (f.key().equals("art")) { art = strValue(f.value()); continue; }
            if (f.key().equals("back")) { back = strValue(f.value()); continue; }
            if (f.key().equals("name")) { nm = strValue(f.value()); continue; }   // 期7 片3：结构键（不当属性）
            String src = exprSource(f.value());
            if (src == null) continue;                                  // 非法字面量：跳过（不编值）
            // 枚举（有可选值）**值那一段是空的** → 只算可选值，不进 fields（老档的 fieldDefs 就是这么存的）
            boolean enumOnly = f.options() != null && strValue(f.value()).isEmpty();
            if (!enumOnly) fields.add(f.key(), literalJson(f.value(), src));
            if (f.options() != null) {
                JsonArray arr = new JsonArray();
                f.options().forEach(arr::add);
                defs.add(f.key(), arr);
            }
        }
        if (back.isEmpty()) back = "blue";
        return new GameDefinition.CardDef(d.name(), art, back,
                fields.isEmpty() ? null : fields, defs.isEmpty() ? null : defs,
                nm);                            // 段头 = 资产名（d.name()）；这一栏 = 显示名（没写就是空串）
    }

    /** piece 声明 → PieceDef（blueprint / name / scale 是结构键；没有蓝图的实例没有意义 → null）。 */
    private static GameDefinition.PieceDef toPieceDef(Ast.PieceDecl d) {
        String bp = "";
        String nm = "";
        double scale = 1.0;
        JsonObject fields = new JsonObject();
        JsonObject defs = new JsonObject();
        for (Ast.Field f : d.fields()) {
            switch (f.key()) {
                case "blueprint" -> { bp = strValue(f.value()); continue; }
                case "name" -> { nm = strValue(f.value()); continue; }
                case "scale" -> { scale = numValue(f.value(), 1.0); continue; }
                default -> { }
            }
            String src = exprSource(f.value());
            if (src == null) continue;
            boolean enumOnly = f.options() != null && strValue(f.value()).isEmpty();
            if (!enumOnly) fields.add(f.key(), literalJson(f.value(), src));
            if (f.options() != null) {
                JsonArray arr = new JsonArray();
                f.options().forEach(arr::add);
                defs.add(f.key(), arr);
            }
        }
        // 蓝图可以空着（「先声明棋子、后配资源」是既有口径）：**留在模型里** —— 编辑器要能看见半成品，
        // 摆不出来由宿主管（blueprintStack 取不到就记日志跳过，不掐局）。
        return new GameDefinition.PieceDef(d.name(), bp, nm,
                fields.isEmpty() ? null : fields, defs.isEmpty() ? null : defs, scale);
    }

    /**
     * 对象声明 → AssetDef：{@code kind} 由段头动词定，{@code id} = 段头（**资产名**）、
     * {@code name} = 段里的显示名（没写就退回资产名，不逼人写两遍）。
     *
     * <p>结构键 = {@code name} / {@code base} / {@code lore} / {@code body}；其余字段（例：文本对象的
     * {@code click} 点击标记）**原样收进 {@code bp}** —— 现在还没人读的也不丢（不做假动作，也不静默吞）。
     *
     * <p>public = 自检直接调它断言「声明 → 模型」这一步（纯数据搬运，没有副作用）。
     */
    public static GameDefinition.AssetDef toAssetDef(Ast.AssetDecl d) {
        String kind = switch (d.kind()) {
            case "block" -> AssetStore.KIND_BLOCK;
            case "entity" -> AssetStore.KIND_ENTITY;
            case "text" -> AssetStore.KIND_TEXT;
            default -> AssetStore.KIND_ITEM;
        };
        String nm = "";
        String base = "";
        java.util.List<String> lore = new ArrayList<>();
        JsonObject extra = new JsonObject();
        for (Ast.Field f : d.fields()) {
            // components 那一层：**按原版组件的形状**收成一棵 JSON 树（不是字符串），
            // 放 bp["components"] —— 服务端直接喂原版 DataComponentPatch 解析、贴到物品栈上；
            // 认不出的组件名 / 坏值由原版 codec 报错（这一层不猜、不静默吞）。
            if (f.key().equals("components") && f.value() instanceof Ast.Obj co) {
                JsonObject cj = objJson(co);
                flagFix(cj);            // 锁死型组件（原版 Unit 型）：true / { } 归一成 { }，false = 不加
                extra.add("components", cj);
                continue;
            }
            String v = strValue(f.value());
            // 值是「变量写法」（例：文本对象的 `click zom1`）时，源码原文就是那个名字 —— 一起收进 bp
            if (v.isEmpty() && f.value() instanceof Ast.Name idv) {
                v = idv.id();
            }
            switch (f.key()) {
                case "name" -> nm = v;
                case "base" -> base = v;
                case "lore", "body" -> {
                    if (f.value() instanceof Ast.ListLit ll) {          // 列表 = 多行（`lore ["第一行", "第二行"]`）
                        for (Ast.Expr it : ll.items()) {
                            String one = strValue(it);
                            if (!one.isEmpty()) lore.add(one);
                        }
                    } else if (!v.isEmpty()) {
                        lore.add(v);
                    }
                }
                default -> {
                    if (!v.isEmpty()) extra.addProperty(f.key(), v);
                }
            }
        }
        return new GameDefinition.AssetDef(kind, nm.isEmpty() ? d.name() : nm, 0, 0, null,
                extra.isEmpty() ? null : extra, base, lore, d.name());
    }

    /** 字面量 → JSON 值（数字 / 真假 / 字符串 / 列表；列表 = JsonArray）。 */
    private static JsonElement literalJson(Ast.Expr e, String src) {
        if (e instanceof Ast.Num x) return new JsonPrimitive(x.v());
        if (e instanceof Ast.Bool x) return new JsonPrimitive(x.v());
        if (e instanceof Ast.ListLit l) {
            JsonArray arr = new JsonArray();
            for (Ast.Expr it : l.items()) {
                String one = exprSource(it);
                if (one == null) continue;
                arr.add(one.startsWith("\"") ? one.substring(1, one.length() - 1) : one);
            }
            return arr;
        }
        return new JsonPrimitive(src.startsWith("\"") ? src.substring(1, src.length() - 1) : src);
    }

    /** 对象字面量 → JSON 对象（键原样，值走 {@link #compJson}）。 */
    private static JsonObject objJson(Ast.Obj o) {
        JsonObject out = new JsonObject();
        for (Ast.Field f : o.fields()) {
            out.add(f.key(), compJson(f.value()));
        }
        return out;
    }

    /**
     * **锁死型组件归一**。
     *
     * <p>原版 `unbreakable` / `glider` / `intangible_projectile` 的值类型是 Unit：codec **只认空对象
     * `{ }`**（`/give @s diamond_pickaxe[unbreakable={}]`）—— 写 `true` 当场解析失败，**整层
     * components 全废**（物品照发，组件一个也没贴上）。脚本层照旧让人写 `true`（界面上是个开关），
     * 这里翻成 `{ }`；写 `false` 当「这条不加」（开关关掉 = 不要这个组件）。
     *
     * <p>名单在 {@link ComponentModel#isFlag}（那边是唯一真源）。
     */
    private static void flagFix(JsonObject components) {
        for (String k : new ArrayList<>(components.keySet())) {
            if (!ComponentModel.isFlag(k)) {
                continue;
            }
            JsonElement v = components.get(k);
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && !v.getAsBoolean()) {
                components.remove(k);
            } else if (!v.isJsonObject()) {
                components.add(k, new JsonObject());
            }
        }
    }

    /**
     * **组件层的值 → JSON**（保留真实类型：数 = 数、真假 = 真假、字符串 = 字符串、列表 = 数组、对象 = 对象）。
     *
     * <p>与 {@link #literalJson} 的分工：那个是「声明字段」的宽松口径（一律往字符串上凑，老档零变化）；
     * 组件要喂**原版 codec**，类型必须是真的 —— 所以另走这一条，不做任何字符串化。
     *
     * <p>不是字面量（变量 / 表达式）当场报错带行号：组件是**建模期数据**，不该在运行时算出来。
     */
    private static JsonElement compJson(Ast.Expr e) {
        if (e instanceof Ast.Num x) return new JsonPrimitive(x.v());
        if (e instanceof Ast.Bool x) return new JsonPrimitive(x.v());
        if (e instanceof Ast.Str x) return new JsonPrimitive(x.v());
        if (e instanceof Ast.ListLit l) {
            JsonArray arr = new JsonArray();
            for (Ast.Expr it : l.items()) arr.add(compJson(it));
            return arr;
        }
        if (e instanceof Ast.Obj o) return objJson(o);
        // 负数字面量（`amount -2.4`）：词法把 `-2.4` 拆成「负号 + 数字」两个节点，组件层原来不认它，
        // 于是「攻击速度 -2.4」这种合法值当场报「只能是字面量」。这里把 `-数字` 也当字面量收。
        if (e instanceof Ast.Unary u && u.op().equals("-") && u.e() instanceof Ast.Num x) {
            return new JsonPrimitive(-x.v());
        }
        throw new Ast.ScriptError(e.line(), "组件里的值只能是字面量（数字 / 真假 / 文本 / 列表 / 对象）");
    }

    /**
     * **组件值原文 → 原版形状的 JSON 树**。
     *
     * <p>值原文 = 我们脚本里的字面量写法（例：{@code { modifiers [ { type "minecraft:attack_damage" amount 6 } ] }}）。
     * 这里**不另写一套 SNBT 解析**：把值包进一份最小声明交给 {@link Parser}（真源），再走组件层那条
     * {@link #objJson} 转 JSON —— 与开局喂 codec 的树**同一条路**，两处语义不会分家。
     *
     * <p>public = 纯数据搬运（无副作用），自检与「条目编辑器」直接调。
     *
     * @return 认不出（语法错 / 不是字面量）返回 {@code null} —— 调用方给提示，**别硬改**人家写的值
     */
    public static JsonElement componentValueTree(String valueText) {
        if (valueText == null || valueText.isBlank()) {
            return null;
        }
        try {
            Ast.Script sc = Parser.parse("item zzedit {\n base \"minecraft:stone\"\n components { zz " + valueText + " }\n}\n");
            for (Ast.AssetDecl d : sc.assetDecls()) {
                if (!d.name().equals("zzedit")) {
                    continue;
                }
                for (Ast.Field f : d.fields()) {
                    if (f.key().equals("components") && f.value() instanceof Ast.Obj o) {
                        return objJson(o).get("zz");
                    }
                }
            }
        } catch (RuntimeException e) {          // Ast.ScriptError 也是 RuntimeException：认不出就交回 null
            return null;
        }
        return null;
    }

    /** CardDef → 声明文本（迁移用）—— 显示名（非空）也带上，读回来逐字一样。 */
    private static String cardCode(GameDefinition.CardDef c) {
        List<ScriptEdit.Field> fs = new ArrayList<>();
        if (!c.art().isBlank()) fs.add(new ScriptEdit.Field("art", ScriptEdit.strCode(c.art()), null));
        // 显示名（没写就不带这一栏 —— 老档迁出来的声明一字不变）
        if (c.name() != null && !c.name().isBlank()) fs.add(new ScriptEdit.Field("name", ScriptEdit.strCode(c.name()), null));
        if (c.back() != null && !c.back().isBlank() && !c.back().equals("blue")) {
            fs.add(new ScriptEdit.Field("back", ScriptEdit.strCode(c.back()), null));
        }
        if (c.fields() != null) {
            for (String k : c.fields().keySet()) {
                String src = jsonToSource(c.fields().get(k));
                if (src == null) continue;
                fs.add(new ScriptEdit.Field(k, src, optionsOf(c.fieldDefs(), k)));
            }
        }
        leftoverEnumFields(fs, c.fields(), c.fieldDefs());
        return ScriptEdit.declCode("card", c.id(), fs);
    }

    /** PieceDef → 声明文本（迁移用）。 */
    private static String pieceCode(GameDefinition.PieceDef p) {
        List<ScriptEdit.Field> fs = new ArrayList<>();
        fs.add(new ScriptEdit.Field("blueprint", ScriptEdit.strCode(p.blueprint()), null));
        if (p.name() != null && !p.name().isBlank()) fs.add(new ScriptEdit.Field("name", ScriptEdit.strCode(p.name()), null));
        if (p.scale() > 0 && p.scale() != 1.0) {
            fs.add(new ScriptEdit.Field("scale", p.scale() == Math.floor(p.scale())
                    ? String.valueOf((long) p.scale()) : String.valueOf(p.scale()), null));
        }
        if (p.fields() != null) {
            for (String k : p.fields().keySet()) {
                String src = jsonToSource(p.fields().get(k));
                if (src == null) continue;
                fs.add(new ScriptEdit.Field(k, src, optionsOf(p.fieldDefs(), k)));
            }
        }
        leftoverEnumFields(fs, p.fields(), p.fieldDefs());
        return ScriptEdit.declCode("piece", p.id(), fs);
    }

    /**
     * 枚举属性（**只有可选值、没有值**那种：编辑器写枚举就是这形状）也要带过去。
     *
     * <p>漏了它就等于丢数据：它在 {@code fields} 里没有条目、只在 {@code fieldDefs} 里。
     * 写法 = 值留空串 + {@code 键选项 […] }（语言与读侧都认这个形状，见 ScriptEdit/Field 的注释）。
     */
    private static void leftoverEnumFields(List<ScriptEdit.Field> fs, JsonObject fields, JsonObject defs) {
        if (defs == null) return;
        for (String k : defs.keySet()) {
            if (fields != null && fields.has(k)) continue;                 // 已随值一起带过去了
            List<String> opts = optionsOf(defs, k);
            if (opts == null || opts.isEmpty()) continue;
            fs.add(new ScriptEdit.Field(k, "\"\"", opts));
        }
    }

    /** JSON 值 → 源码原文（字符串 / 数字 / 真假 / 字符串列表；对象这种表达不出来的 → null，跳过）。 */
    private static String jsonToSource(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean() ? "true" : "false";
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            return ScriptEdit.strCode(p.getAsString());
        }
        if (e.isJsonArray()) {
            List<String> items = new ArrayList<>();
            for (JsonElement it : e.getAsJsonArray()) {
                if (!it.isJsonPrimitive() || !it.getAsJsonPrimitive().isString()) return null;
                items.add(it.getAsString());
            }
            return ScriptEdit.listCode(items);
        }
        return null;
    }

    /** 枚举可选值（{"键": [..]} → 那个键的那串）。 */
    private static List<String> optionsOf(JsonObject defs, String key) {
        if (defs == null || !defs.has(key) || !defs.get(key).isJsonArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : defs.getAsJsonArray(key)) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out.isEmpty() ? null : out;
    }

    /** 组件树读取（递归；各类型专有字段原样带走，内核/渲染按 type 读）。 */
    private static List<GameDefinition.Component> readComponents(JsonArray arr) {
        List<GameDefinition.Component> out = new ArrayList<>();
        if (arr == null) return out;
        for (var el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) continue;
            JsonObject p = o.deepCopy();
            p.remove("id");
            p.remove("type");
            p.remove("x");
            p.remove("y");
            p.remove("w");
            p.remove("h");
            p.remove("children");
            out.add(new GameDefinition.Component(o.get("id").getAsString(), jstr(o, "type", "panel"),
                    jnum(o, "x"), jnum(o, "y"), jnum(o, "w"), jnum(o, "h"), p,
                    readComponents(o.has("children") ? o.getAsJsonArray("children") : null)));
        }
        return out;
    }

    /** 组件树写出（递归）。 */
    private static JsonArray writeComponents(List<GameDefinition.Component> list) {
        JsonArray arr = new JsonArray();
        for (GameDefinition.Component c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("type", c.type());
            o.addProperty("x", c.x());
            o.addProperty("y", c.y());
            o.addProperty("w", c.w());
            o.addProperty("h", c.h());
            if (c.p() != null) for (var e : c.p().entrySet()) o.add(e.getKey(), e.getValue());
            if (!c.children().isEmpty()) o.add("children", writeComponents(c.children()));
            arr.add(o);
        }
        return arr;
    }


    /**
     * 槽位名全集 = 脚本里声明的（schema/4 真源）∪ 老档 {@code vars} 段的 —— 编辑器概览计数与数值页
     * 用它对账，口径只有这一处。
     *
     * <p>脚本语法错时这里静默只给 vars 的名字 —— 语法错由 {@link #validate} 顶部单独报（带行号）。
     */
    public static Set<String> slotNames(GameDefinition def) {
        Set<String> out = new HashSet<>();
        for (GameDefinition.VarDef v : def.vars()) out.add(v.id());
        if (def.script() != null && !def.script().isBlank()) {
            try {
                for (Ast.Decl d : Parser.parse(def.script()).globals()) out.add(d.name());
            } catch (Ast.ScriptError ignored) {
                // 留给 validate 顶部报（那里带行号）
            }
        }
        return out;
    }


    /** JSON 对象取字符串字段：缺失/空值回默认。 */
    private static String jstr(JsonObject o, String key, String dft) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : dft;
    }

    /** JSON 对象取数字字段：缺失/非数字回 0（节点/框坐标的兜底）。 */
    private static double jnum(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsDouble() : 0;
    }

    /**
     * 解析一份「屏幕布局」（{@code { size:[w,h], boxes:[…] }}）—— <b>只在读线口径时用</b>
     * （{@code fromJsonWire}）：宿主发来的那份带 {@code stage.ui}（画布尺寸 + 老式框），客户端照它铺。
     * 缺栏/缺段 = 默认画布 + 无框。
     */
    private static GameDefinition.StageUi jui(JsonObject so, String key) {
        if (!so.has(key) || !so.get(key).isJsonObject()) return GameDefinition.StageUi.empty();
        JsonObject uo = so.getAsJsonObject(key);
        int w = DEFAULT_UI_W, h = DEFAULT_UI_H;
        if (uo.has("size") && uo.get("size").isJsonArray() && uo.getAsJsonArray("size").size() >= 2) {
            JsonArray size = uo.getAsJsonArray("size");
            w = size.get(0).getAsInt();
            h = size.get(1).getAsInt();
        }
        List<GameDefinition.BoxDef> boxes = new ArrayList<>();
        if (uo.has("boxes")) {
            for (var el : uo.getAsJsonArray("boxes")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;   // 无 id 的框无法被编辑/引用，跳过
                GameDefinition.BoxContent content = new GameDefinition.BoxContent("none", "", "");
                if (o.has("content") && o.get("content").isJsonObject()) {
                    JsonObject co = o.getAsJsonObject("content");
                    content = new GameDefinition.BoxContent(
                            jstr(co, "type", "none"), jstr(co, "value", ""), jstr(co, "var", ""));
                }
                GameDefinition.BoxStyle style = new GameDefinition.BoxStyle("#223344", "#ffffff");
                if (o.has("style") && o.get("style").isJsonObject()) {
                    JsonObject sty = o.getAsJsonObject("style");   // ⚠ 别叫 so：会与参数重名（Java 不允许）
                    style = new GameDefinition.BoxStyle(
                            jstr(sty, "bg", "#223344"), jstr(sty, "color", "#ffffff"));
                }
                boxes.add(new GameDefinition.BoxDef(o.get("id").getAsString(),
                        jnum(o, "x"), jnum(o, "y"), jnum(o, "w"), jnum(o, "h"), content, style));
            }
        }
        // 舞台铺底只走 views[]（那份 bg 才是客户端渲染读的）—— 这里的 ui 段只管画布尺寸与框列表
        return new GameDefinition.StageUi(w, h, boxes, "");
    }

    /** 写一份「屏幕布局」到 {@code root[key]}（ui 为 null = 不落段；与 {@link #jui} 严格对称）。 */
    private static void writeUi(JsonObject root, String key, GameDefinition.StageUi ui) {
        if (ui == null) return;
        JsonObject uo = new JsonObject();
        JsonArray size = new JsonArray();
        size.add(ui.w());
        size.add(ui.h());
        uo.add("size", size);
        JsonArray boxes = new JsonArray();
        for (GameDefinition.BoxDef b : ui.boxes()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id());
            o.addProperty("x", b.x());
            o.addProperty("y", b.y());
            o.addProperty("w", b.w());
            o.addProperty("h", b.h());
            JsonObject co = new JsonObject();
            co.addProperty("type", b.content().type());
            if (!b.content().value().isEmpty()) co.addProperty("value", b.content().value());
            if (!b.content().var().isEmpty()) co.addProperty("var", b.content().var());
            o.add("content", co);
            JsonObject st = new JsonObject();
            st.addProperty("bg", b.style().bg());
            st.addProperty("color", b.style().color());
            o.add("style", st);
            boxes.add(o);
        }
        uo.add("boxes", boxes);
        root.add(key, uo);
    }

    /** 棋子实例 id 避让：p1、p2… 跳过已占用编号（与卡牌 c<N> 同款规则）。 */
    private static String nextPieceId(Set<String> used) {
        int n = 1;
        while (!used.add("p" + n)) n++;
        return "p" + n;
    }

    /** 模型 → JSON。meta/cards/decks/pieces/vars/stage/script 重新生成，rest（未知段落 + teams/actions）原样拼回。 */
    public static JsonObject toJson(GameDefinition def) {
        JsonObject root = def.rest() == null ? new JsonObject() : def.rest().deepCopy();
        root.addProperty("format", GameDefinition.FORMAT);
        // meta 段：老档那份（rest 里的，带着 author/players 等我们没解析的键）就地合并 ——
        // 只覆写 name/desc 两个我们管的键，其余原样保住（见 fromJson 里「meta 故意不 remove」）
        JsonObject meta = root.has("meta") && root.get("meta").isJsonObject()
                ? root.getAsJsonObject("meta") : new JsonObject();
        meta.addProperty("name", def.name());
        if (def.desc() != null && !def.desc().isBlank()) meta.addProperty("desc", def.desc());
        else meta.remove("desc");                            // 没写简介 → 不落这个键（老档零变化）
        root.add("meta", meta);
        // ⚠ cards / pieces 两段**不再生成**：真源是脚本里的声明；
        //    老档那两段跟着 rest 原样拼回（见 read 里「故意不 remove」），所以这里什么都不写。
        JsonArray decks = new JsonArray();
        for (GameDefinition.DeckDef d : def.decks()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            JsonArray from = new JsonArray();
            d.from().forEach(from::add);
            o.add("from", from);
            o.addProperty("shuffle", d.shuffle());
            decks.add(o);
        }
        root.add("decks", decks);
        // 数值/池（引擎 v0）：init 原样写回（单值/数组都行），空表不写段（省体积；读取侧缺段=空）
        if (def.vars() != null && !def.vars().isEmpty()) {
            JsonArray vars = new JsonArray();
            for (GameDefinition.VarDef v : def.vars()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", v.id());
                o.addProperty("name", v.name());
                o.addProperty("scope", v.scope());
                o.addProperty("type", v.type());
                // ⛔ vis / role 两键（只写不读的死字段）：不再生成 ——
                //    可见性走脚本的 {@code @条件}，"谁是画者" 由脚本自己的槽位判，引擎不存这两项
                o.add("init", v.init() == null ? new JsonPrimitive(0) : v.init());
                vars.add(o);
            }
            root.add("vars", vars);
        }
        // ⛔ teams 段（队伍）**不再生成** —— 老档里那段原样留在 rest 里透传（见 fromJson）。
        // ⛔ actions 段（自定义动作）**不再生成** —— 老档里那段原样留在 rest 里透传（见 fromJson）；
        //    组件（input/button）的 action 是自由字符串（填什么就提交什么，脚本判 input == "…"）。
        // ⛔ 舞台段（stage）**写盘时不再生成** —— 真源是脚本里的 screen 块。
        //    老档里那段跟着 rest 原样写回（见 fromJson）；客户端要的那份由 toJsonWire 补上。
        // 脚本段（第五期 schema/4）：规则真源（纯文本）。空不写段（读取侧缺段 = 空）
        if (def.script() != null && !def.script().isBlank()) {
            root.addProperty("script", def.script());
        }
        // 已导入的组件（#21 切片④）：空表不写段（读取侧缺段=空 ⇒ 老档零变化）
        if (def.assets() != null && !def.assets().isEmpty()) {
            JsonArray assets = new JsonArray();
            for (GameDefinition.AssetDef a : def.assets()) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", a.kind());
                o.addProperty("name", a.name());
                if (a.w() > 0) o.addProperty("w", a.w());
                if (a.h() > 0) o.addProperty("h", a.h());
                if (a.px() != null && !a.px().isEmpty()) o.addProperty("px", a.px());
                if (a.bp() != null) o.add("bp", a.bp());
                // 物品类（自定义物品）新增的两键：**缺省不写** ⇒ 老档一字不变
                if (a.base() != null && !a.base().isEmpty()) o.addProperty("base", a.base());
                if (a.lore() != null && !a.lore().isEmpty()) {
                    JsonArray ls = new JsonArray();
                    for (String ln : a.lore()) ls.add(ln);
                    o.add("lore", ls);
                }
                assets.add(o);
            }
            root.add("assets", assets);
        }
        // 世界区域（#23）：palette 写方块 id 字符串（与读侧对称）；空表不写该区域的关键（壳声明仍写）。
        if (def.areas() != null && !def.areas().isEmpty()) {
            JsonArray areas = new JsonArray();
            for (GameDefinition.AreaDef a : def.areas()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", a.id());
                o.addProperty("sizeX", a.sizeX());
                o.addProperty("sizeY", a.sizeY());
                o.addProperty("sizeZ", a.sizeZ());
                if (a.palette() != null && !a.palette().isEmpty()) {
                    JsonArray pal = new JsonArray();
                    for (net.minecraft.world.level.block.state.BlockState st : a.palette()) {
                        pal.add(blockToString.apply(st));
                    }
                    o.add("palette", pal);
                    // 写**一律**写紧凑串：体积比老的逐格数字数组小两个量级
                    // （同区域 ~10 KB → ~0.2 KB）。打包失败（形状/下标不符）= 当未捕获，不写这一键。
                    String packed = AreaPacked.pack(a.blocks(), a.sizeX(), a.sizeY(), a.sizeZ(),
                            a.palette().size());
                    if (packed != null) o.addProperty("blocks", packed);
                }
                // 语义格：与读侧对称；一个都没标就不写这一键（老档形态）
                if (a.marks() != null && !a.marks().isEmpty()) {
                    JsonArray cells = new JsonArray();
                    for (GameDefinition.AreaDef.CellMark m : a.marks()) {
                        JsonObject co = new JsonObject();
                        co.addProperty("role", m.role());
                        co.addProperty("i", m.i());
                        co.addProperty("j", m.j());
                        co.addProperty("k", m.k());
                        cells.add(co);
                    }
                    o.add("cells", cells);
                }
                areas.add(o);
            }
            root.add("areas", areas);
        }
        return root;
    }

    /**
     * **线口径**：磁盘口径 + 把内存里的舞台写出来（宿主<b>按人</b>合成过的那份）。
     *
     */
    public static JsonObject toJsonWire(GameDefinition def) {
        JsonObject root = toJson(def);
        GameDefinition.StageDef stage = def.stage();
        if (stage == null || stage.views().isEmpty()) return root;
        JsonObject so = new JsonObject();
        so.addProperty("mode", stage.isFull() ? GameDefinition.StageDef.MODE_FULL : GameDefinition.StageDef.MODE_HUD);
        writeUi(so, "ui", stage.ui());                 // 画布尺寸（框列表为空：客户端渲染靠 views[].components）
        JsonArray views = new JsonArray();
        for (GameDefinition.StageView v : stage.views()) {
            JsonObject vo = new JsonObject();
            vo.addProperty("id", v.id());
            if (!v.name().isBlank()) vo.addProperty("name", v.name());
            if (v.bg() != null && !v.bg().isBlank()) vo.addProperty("bg", v.bg());   // 舞台铺底（脚本 bg(…)）
            if (v.hud()) {
                vo.addProperty("hud", true);
                vo.addProperty("hudX", v.hudX());
                vo.addProperty("hudY", v.hudY());
                vo.addProperty("hudW", v.hudW());          // 板占屏幕的宽/高（来自脚本的 place，没写是缺省）
                vo.addProperty("hudH", v.hudH());
                if (!v.clickable()) vo.addProperty("clickable", false);
            }
            if (v.w() != 320) vo.addProperty("w", v.w());
            if (v.h() != 180) vo.addProperty("h", v.h());
            vo.add("components", writeComponents(v.components()));
            views.add(vo);
        }
        so.add("views", views);
        if (stage.currentFull() != null) so.addProperty("curFull", stage.currentFull());
        if (stage.currentHud() != null) so.addProperty("curHud", stage.currentHud());
        if (stage.hideSeq() > 0) so.addProperty("hideSeq", stage.hideSeq());
        if (stage.hiddenName() != null) so.addProperty("hiddenName", stage.hiddenName());
        root.add("stage", so);
        return root;
    }

    // ===== 校验（schema 真源的一部分，与 fromJson/toJson 同处）=====

    /**
     * 段内校验（引擎 v0：vars 段 + script 文本）。返回 null = 通过；否则第一条错误消息。
     *
     * 
     * <p>调用方：服务端 {@code GameManager#saveGame}（信任边界，伪造包也堵在这）+ 本类自检。
     */
    public static String validate(GameDefinition def) {
        // ① 段内 id 唯一 + 非空
        String bad = checkIds(def.vars().stream().map(GameDefinition.VarDef::id).toList(), "数值");
        if (bad != null) return bad;
        // ⛔ actions 段（自定义动作）不校验 id 唯一（段不进模型，见 fromJson）
        // ② 类型与初始值必须对得上：池变量 = 数组（要能随机抽一项），单值变量 = 不能是数组
        for (GameDefinition.VarDef v : def.vars()) {
            boolean arr = v.init() != null && v.init().isJsonArray();
            if (v.isList() && !arr) return "列表槽位「" + v.id() + "」的初始值必须是数组";
            if (!v.isList() && arr) return "值槽位「" + v.id() + "」的初始值不能是数组";
        }
        // ②b 脚本段（第五期 schema/4）：**保存不校验脚本**（半成品 / 打错字的草稿都要能随存随改）。
        //    拦在校验里的只有数据段的硬伤（id 唯一 / 类型对得上）；脚本的错由**编辑器本地解析**只提示不拦
        //    （GameEditorScreen.syntaxErrorOf）· **开局**时由 Parser.parseForPlay 拦（带行号）。
        // ⛔ ③ 舞台（旧 stage：框列表 / HUD 视口 / 组件树）**不再校验** —— 磁盘口径下 stage
        //    恒空（真源是脚本的 screen 块），这些检查永远查不到东西。舞台的正确性由脚本解析（②b）兜。
        return null;
    }


    /** 一组 id 的非空 + 段内唯一检查。返回第一条错误或 null。 */
    private static String checkIds(List<String> ids, String label) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) return label + "有空的 id";
            if (!seen.add(id)) return label + " id 重复: " + id;
        }
        return null;
    }

    // ===== 方块状态 ⇄ 字符串（#23 区域快照的 palette 编码）=====
    // 真实现走 MC 注册表；自检环境没有 MC，可注入假实现（Function 换表）。
    /**
     * 字符串 → 方块状态。<b>带属性</b>（{@code minecraft:oak_stairs[facing=east,half=bottom]}）；
     * 认不出的属性项忽略，认不出的方块 = null。自检可替换。
     *
     */
    public static java.util.function.Function<String, net.minecraft.world.level.block.state.BlockState> blockFromString =
            s -> {
                net.minecraft.resources.Identifier bid =
                        net.minecraft.resources.Identifier.tryParse(GameDefinition.AreaDef.blockKeyId(s));
                if (bid == null) return null;
                net.minecraft.world.level.block.Block b =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(bid);
                if (b == null) return null;
                net.minecraft.world.level.block.state.BlockState st = b.defaultBlockState();
                for (String pair : GameDefinition.AreaDef.blockKeyProps(s)) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    var p = b.getStateDefinition().getProperty(pair.substring(0, eq));
                    if (p == null) continue;                       // 跨版本档里的陌生属性 → 跳过这一项
                    st = putProp(st, p, pair.substring(eq + 1));
                }
                return st;
            };

    /**
     * 新建项目的骨架脚本：**新建的档天生能存也能开局** ——
     * 有 {@code on} 事件、有主屏，配合「保存只拦语法」那条，新项目可以直接去【世界】圈区域。
     * ⚠ 它必须过 {@code Parser.parseForPlay}（自检里有这一条）。
     */
    public static final String NEW_GAME_SCRIPT = "on start {\n  show(\"main\")\n}\n\nscreen main {\n}\n";

    /**
     * 方块状态 → 字符串：注册表 id + <b>全部属性</b>（{@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight]}）。
     *
     */
    public static java.util.function.Function<net.minecraft.world.level.block.state.BlockState, String> blockToString =
            st -> {
                java.util.List<String> ps = new java.util.ArrayList<>();
                for (var p : st.getProperties()) ps.add(p.getName() + "=" + propText(st, p));
                return GameDefinition.AreaDef.blockKey(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(), ps);
            };

    /** 取一个属性的值文本（{@code facing=east} 的右半边）。26.x：{@code Property#getName(Object)}。 */
    private static <T extends Comparable<T>> String propText(
            net.minecraft.world.level.block.state.BlockState st,
            net.minecraft.world.level.block.state.properties.Property<T> p) {
        return p.getName(st.getValue(p));
    }

    /** 把 {@code 名字=值} 的那一项写回状态；值认不出（跨版本档）就原样返回。 */
    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState putProp(
            net.minecraft.world.level.block.state.BlockState st,
            net.minecraft.world.level.block.state.properties.Property<T> p, String value) {
        return p.getValue(value).map(v -> st.setValue(p, v)).orElse(st);
    }

}
