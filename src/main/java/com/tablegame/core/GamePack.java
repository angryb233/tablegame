package com.tablegame.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.tablegame.TableGame;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.tablegame.core.GameLayout.PACK_ID;
import com.tablegame.editor.data.RecipeEdit;
import com.tablegame.editor.data.TagEdit;
import com.tablegame.editor.data.TradeEdit;
import com.tablegame.editor.data.VanillaJson;
import com.tablegame.host.GameManager;

/**
 * 项目里声明的**原版注册类内容** → **存档里的数据包**（配方 · 村民交易）。
 * 落点 {@code <存档>/datapacks/tablegame/}（{@code pack.mcmeta} + {@code data/tablegame/recipe/*.json} + {@code data/tablegame/villager_trade/<职业>/<等级>/*.json} +
 * {@code data/minecraft/tags/villager_trade/<职业>/level_N.json}）—— ⚠ **这个目录是引擎的，别手放东西**（同步时多出来的文件会被删）。
 * 村民交易那两份是**原版的布局**（一条交易一个文件 + 往原版那一级的 tag 里追加，tag 默认 merge ⇒ 原版那几条一条不动）；细节见 {@link TradeEdit} 类注释。
 * 让它当场生效三步（都是原版 public API）：{@code PackRepository.reload()} 重扫 → {@code addPack(id)} 选中（第一次时 level.dat 里还没这条）→ {@code MinecraftServer.reloadResources(已选清单)} 全量重载。
 * ⚠ **只有文件真变了才跑那三步**（逐字节比）：编辑器每动一下存一次档，白重载一次会卡。
 */
public final class GamePack {
    private GamePack() {
    }

    /** 存档里的数据包目录。 */
    public static Path packDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("datapacks").resolve(PACK_ID);
    }

    /** 把「项目里声明的配方 / 村民交易」同步进存档数据包（幂等：没变就什么都不干）。 */
    public static void sync(MinecraftServer server) {
        if (server == null) return;
        try {
            Map<String, byte[]> want = build();
            Path dir = packDir(server);
            if (want.size() <= 1) {                        // 一份内容都没有
                if (!Files.isDirectory(dir)) return;        // 没内容也没包：不碰
                deleteTree(dir);                            // 删了最后一份 ⇒ 包也该走
                reload(server);
                TableGame.LOGGER.info("[数据包] 项目里没有配方 / 村民交易了，已从存档撤下数据包");
                return;
            }
            boolean changed = writeIfChanged(dir, want);     // 逐字节比：一样就什么都不写
            // ⚠ 文件没变**也要**确认「它真的被选中了」：写包与选中是两件事
            //（「id 认错 ⇒ 没选中 ⇒ 一直没加载」时字节也没变 ⇒
            //  光看文件看不出来）。所以「没变 + 已选中」才跳过。
            if (!changed && packSelected(server)) return;
            reload(server);
            TableGame.LOGGER.info("[数据包] 已同步进存档数据包（{} 个文件）", want.size());
        } catch (Exception e) {
            TableGame.LOGGER.error("[数据包] 同步失败：{}", e.toString());
        }
    }

    /**
     * 三步：重扫 → 选中 → 全量重载。⚠ **包的 id 不是目录名**：世界目录里的数据包 id 是 {@code "file/" + 目录名}
     * （原版 {@code FolderRepositorySource.createDiscoveredFilePackInfo}）⇒ 拿 {@code tablegame} 去 {@code addPack} **找不到**。所以重扫完先问仓库「真 id 是什么」再选。
     */
    private static void reload(MinecraftServer server) {
        PackRepository repo = server.getPackRepository();
        repo.reload();
        String pid = packIdIn(repo);
        if (pid.isEmpty()) {
            TableGame.LOGGER.warn("[数据包] 目录 {} 没被目录扫描发现（里面有 pack.mcmeta 吗？）",
                    packDir(server));
            return;
        }
        repo.addPack(pid);
        server.reloadResources(repo.getSelectedIds());
    }

    /** 数据包在仓库里的**真 id**（{@code tablegame} 或 {@code file/tablegame}）。 */
    static String packIdIn(PackRepository repo) {
        for (String id : repo.getAvailableIds()) {
            if (id.equals(PACK_ID) || id.endsWith("/" + PACK_ID)) return id;
        }
        return "";
    }

    /** 这一份数据包**现在**被选中了吗（= 原版真的在读它）。 */
    private static boolean packSelected(MinecraftServer server) {
        for (String id : server.getPackRepository().getSelectedIds()) {
            if (id.equals(PACK_ID) || id.endsWith("/" + PACK_ID)) return true;
        }
        return false;
    }

    /**
     * 这一份数据包该有的全部文件（相对路径 → 字节）—— 纯组装。
     *
     * <p>取的是**全部项目**的内容（配方与村民交易本来就是全局的：原版这两样都只有一份同 id 的）；
     * 两个项目写了同名配方 / 同一个挂载点撞了 id 时**文件路径相同 ⇒ 排序先者赢**，后一个记一行日志。
     */
    static Map<String, byte[]> build() {
        Map<String, byte[]> out = new TreeMap<>();
        out.put("pack.mcmeta", utf8(packMeta()));
        GameManager gm = GameManager.get();
        if (gm == null) return out;
        for (String g : gm.names()) {
            GameDefinition def = gm.load(g);
            if (def == null) continue;
            JsonObject rs = def.recipes();
            for (String nm : new TreeSet<>(rs.keySet())) {
                JsonElement e = rs.get(nm);
                if (e == null || !e.isJsonObject()) continue;
                String path = "data/" + PACK_ID + "/recipe/" + RecipeEdit.recipeId(nm) + ".json";
                if (out.containsKey(path)) {
                    TableGame.LOGGER.warn("[数据包] 配方「{}」在多个项目里同名，{} 那份被跳过", nm, g);
                    continue;
                }
                JsonObject vanilla = RecipeEdit.toVanilla(e.getAsJsonObject(), def.assets());
                out.put(path, utf8(vanilla.toString()));
            }
    
        }
        // 村民交易：**原版布局** —— 一条交易一个文件（
        // data/tablegame/villager_trade/<职业>/<等级>/<id>.json）+ 往原版那一级的 tag 里追加它的 id。
        // ⚠ tag 那一步才是「挂上去」：不动它，文件写了也没人读（原版的 trade_set 引用的是那个 tag）。
        Map<String, TreeSet<String>> tags = new TreeMap<>();       // tag 文件 → 该写进去的 id（排序，字节稳定）
        for (String g : gm.names()) {
            GameDefinition def = gm.load(g);
            if (def == null) continue;
            JsonObject ts = def.trades();
            for (String nm : new TreeSet<>(ts.keySet())) {
                JsonElement e = ts.get(nm);
                if (e == null || !e.isJsonObject()) continue;
                JsonObject entry = e.getAsJsonObject();
                String prof = TradeEdit.professionOf(entry);
                int lvl = TradeEdit.levelOf(entry);
                String path = TradeEdit.packFile(prof, lvl, nm);
                if (out.containsKey(path)) {
                    TableGame.LOGGER.warn("[数据包] 村民交易「{}」在多个项目里撞了同一个 id，{} 那份被跳过", nm, g);
                    continue;
                }
                JsonObject vanilla = TradeEdit.toVanilla(TradeEdit.tradeOf(entry), def.assets());
                out.put(path, utf8(vanilla.toString()));
                tags.computeIfAbsent(TradeEdit.tagFile(prof, lvl), k -> new TreeSet<>())
                        .add(TradeEdit.entryId(prof, lvl, nm));
            }
        }
        for (Map.Entry<String, TreeSet<String>> en : tags.entrySet()) {
            JsonArray vals = new JsonArray();
            for (String v : en.getValue()) vals.add(v);
            JsonObject o = new JsonObject();
            // ⚠ **不写 replace**（缺省 false）= 与原版那份**合并**（原版那几条一条不动）。
            //    写成 replace:true 就是把整个池子换成我们这一条 —— 原版村民的别的交易全没了。
            o.add("values", vals);
            out.put(en.getKey(), utf8(o.toString()));
        }
        // 标签：项目档里的标签 → `data/tablegame/tags/<类型>/<路径>.json`
        //（原版布局 —— `tags/<类型>/` 这一层由这里拼，项目档里 "type" 只是条目上的一个字段）。
        // **零编译**：values 里写的 id 原样进包（`#别的标签` 也是原版认的写法）。
        // ⚠ 复用的是原版自己的注册表目录名（item / block / …）；类型不在白名单 = 原版没有这个注册表 ⇒ 跳过 + 记一行。
        for (String g : gm.names()) {
            GameDefinition def = gm.load(g);
            if (def == null) continue;
            JsonObject tg = def.tags();
            for (String nm : new TreeSet<>(tg.keySet())) {
                JsonElement e = tg.get(nm);
                if (e == null || !e.isJsonObject()) continue;
                JsonObject entry = e.getAsJsonObject();
                String type = TagEdit.typeOf(entry);
                if (!TagEdit.isType(type)) {
                    TableGame.LOGGER.warn("[数据包] 标签「{}」的类型「{}」原版没有这个注册表，跳过", nm, type);
                    continue;
                }
                if (!TagEdit.isName(nm)) {                 // 名字**同时就是原版 id**（不改写）：非法名生成出来 = 引用它的配方整条报废
                    TableGame.LOGGER.warn("[数据包] 标签「{}」的名字不能当原版 id（只许 a-z 0-9 _ . - /），跳过", nm);
                    continue;
                }
                String path = TagEdit.packFile(type, nm);
                if (out.containsKey(path)) {
                    TableGame.LOGGER.warn("[数据包] 标签「{}」在多个项目里撞了同一个 id，{} 那份被跳过", nm, g);
                    continue;
                }
                out.put(path, utf8(TagEdit.toVanilla(entry).toString()));
            }
        }
        // 段B 三条：进度 / 魔咒 / 伤害类型 —— **原版 JSON 原样进包**（零编译）。
        // 一个名字一份文件；名字非法 / 撞 id ⇒ 跳过 + 记一行（生成原版解不开的 id = 引用它的那条整条报废）。
        for (String[] sec : VanillaJson.SECTIONS) {
            String d = sec[0];
            for (String g : gm.names()) {
                GameDefinition def = gm.load(g);
                if (def == null) continue;
                JsonObject vsec = def.vanillaSection(d);
                for (String nm : new TreeSet<>(vsec.keySet())) {
                    JsonElement e = vsec.get(nm);
                    if (e == null || e.isJsonNull()) continue;
                    if (!VanillaJson.isName(nm)) {
                        TableGame.LOGGER.warn("[数据包] {}「{}」的名字不能当原版 id（只许 a-z 0-9 _ . - /），跳过", d, nm);
                        continue;
                    }
                    String vpath = VanillaJson.packFile(d, nm);
                    if (out.containsKey(vpath)) {
                        TableGame.LOGGER.warn("[数据包] {}「{}」在多个项目里撞了同一个 id，{} 那份被跳过", d, nm, g);
                        continue;
                    }
                    out.put(vpath, utf8(e.toString()));
                }
            }
        }
        return out;
    }

    /**
     * {@code pack.mcmeta}：形状交给**原版 codec**（别手写 {@code pack_format} 数字 ——
     * MC 升版会变，写死的那天就成了一包废）。
     */
    private static String packMeta() {
        var meta = new PackMetadataSection(
                Component.literal("桌游模拟器 · 项目里声明的配方 / 村民交易 / 标签（引擎自动同步，别手改）"),
                SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).minorRange());
        JsonElement inner = PackMetadataSection.SERVER_TYPE.codec()
                .encodeStart(JsonOps.INSTANCE, meta).getOrThrow();
        JsonObject root = new JsonObject();
        root.add("pack", inner);
        return root.toString();
    }

    /** 逐字节比：完全一样 → 什么都不写（返回 false = 不必重载）。多出来的文件删掉。 */
    private static boolean writeIfChanged(Path dir, Map<String, byte[]> want) throws Exception {
        boolean changed = false;
        Set<String> keep = new HashSet<>();
        for (Map.Entry<String, byte[]> en : want.entrySet()) {
            Path p = dir.resolve(en.getKey());
            keep.add(en.getKey());
            byte[] have = Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
            if (Arrays.equals(have, en.getValue())) continue;
            Files.createDirectories(p.getParent());
            Files.write(p, en.getValue());
            changed = true;
        }
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.toList()) {
                    if (!Files.isRegularFile(p)) continue;
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    if (keep.contains(rel)) continue;
                    Files.delete(p);                       // 配方删了 ⇒ 文件也跟着走
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
