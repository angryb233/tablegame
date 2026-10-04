package com.tablegame.editor.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardStore;

/**
 * 组件库的存储层：{@code tablegame/assets/<包名>/}
 *
 * <pre>
 *   pack.json          包元数据 { "desc": "…" }
 *   &lt;资产id&gt;.json     一条组件 { id, kind, name, color, w, h }
 *   &lt;资产id&gt;.px       像素：8 字节头（w, h 各 int，大端）+ w*h 个 ARGB int
 * </pre>
 *
 * <p>纯 Path/JSON 逻辑、零 MC 依赖。安全命名复用 {@link BoardStore#safeName}（防目录穿越）；写盘用 tmp + ATOMIC_MOVE 防写一半损档。
 */
public class AssetStore {

    /**
     * 组件类型（界面定成四个页签：组件库 / 卡牌 / 模型 / 物品）。
     * 卡牌美术与模型美术同属「美术资源」大类，用途不同 ⇒ 用 kind 区分（同库同存储）。
     */
    public static final String KIND_ART = "美术资源";     // 老值：旧包用它，卡牌页签兼容显示
    public static final String KIND_CARD = "卡牌美术";
    public static final String KIND_MODEL = "模型美术";
    public static final String KIND_ITEM = "自定义物品";
    /**
     * 自定义方块：基底 = 原版方块 id + 名字 + 描述，外观用原版。
     * 存储层与物品类完全一样（{@link Asset#isItem()} 只判「基底非空」，与 kind 无关）。
     */
    public static final String KIND_BLOCK = "自定义方块";
    /**
     * 自定义实体：基底 = 原版实体 id（如 {@code minecraft:zombie}）+ 名字 + 描述，存储层与物品类一样。
     *
     * <p>脚本用 {@code spawn_mob("@游戏名/资产名", x, y, z)} 放出；宿主按名字记账，打死/右键进 {@code on entity}
     * （{@code eid} = 是哪只 · {@code edead} = 是不是被打死的）。
     */
    public static final String KIND_ENTITY = "自定义实体";
    /**
     * 自定义文本：名字 + 显示名 + 内容（{@code lore} 存正文行）+ 点击标记（暂存在 {@code bp}，见 {@code GameStore.toAssetDef}）。
     * 没有基底 —— 它不是物品。
     */
    public static final String KIND_TEXT = "自定义文本";

    /**
     * @param base 物品类资产的**基底原版物品 id**（非物品类空串）
     * @param lore 描述行（{@code minecraft:lore}；空表 = 不写）
     */
    public record Asset(String id, String kind, String name, String color, int w, int h,
            String base, java.util.List<String> lore) {

        /** 物品类？（基底非空） */
        public boolean isItem() {
            return base != null && !base.isEmpty();
        }
    }

    /** 一个组件包 = 若干组件的集合（包 = 一套，可含多件）。 */
    public record Pack(String name, String desc, List<Asset> assets) {}

    /** 一块像素（宽 + 高 + ARGB 序列）。 */
    public record Pixels(int w, int h, int[] px) {}

    private static final int MAX_SIDE = 4096;   // 像素尺寸上限（防手改的坏文件炸内存）

    private final Path root;                     // tablegame/assets

    public AssetStore(Path gameDir) {
        this.root = gameDir.resolve("tablegame").resolve("assets");
    }

    public Path root() {
        return root;
    }

    // ==================== 包 ====================

    /** 库里所有包名（目录名，字母序）。目录不存在 = 空库。 */
    public List<String> listPacks() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".") && !n.endsWith(".tmp"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 列包失败: {}", e.toString());
        }
        return out;
    }

    /** 读一个包（不存在 → null）；资产按 id 排序，坏文件跳过并记日志。 */
    public Pack readPack(String name) {
        Path dir = packDir(name);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String desc = "";
        Path meta = dir.resolve("pack.json");
        try {
            if (Files.isRegularFile(meta)) {
                JsonObject o = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("desc") && !o.get("desc").isJsonNull()) {
                    desc = o.get("desc").getAsString();
                }
            }
        } catch (Exception e) {
            TableGame.LOGGER.error("[组件库] 读 pack.json 失败 {}: {}", name, e.toString());
        }
        List<Asset> assets = new ArrayList<>();
        List<Path> jsons = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("pack.json"))
                    .forEach(jsons::add);
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 列资产失败 {}: {}", name, e.toString());
        }
        jsons.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path p : jsons) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                assets.add(new Asset(
                        str(o, "id"), str(o, "kind"), str(o, "name"), str(o, "color"),
                        o.has("w") ? o.get("w").getAsInt() : 0,
                        o.has("h") ? o.get("h").getAsInt() : 0,
                        str(o, "base"), strList(o, "lore")));
            } catch (Exception e) {
                TableGame.LOGGER.error("[组件库] 坏资产文件，跳过 {}: {}", p, e.toString());
            }
        }
        return new Pack(name, desc, assets);
    }

    /** 写/覆盖一个包的 pack.json（建目录）。 */
    public void writePack(String name, String desc) {
        if (BoardStore.safeName(name).isEmpty()) {
            return;
        }
        try {
            Path dir = packDir(name);
            Files.createDirectories(dir);
            JsonObject o = new JsonObject();
            o.addProperty("desc", desc == null ? "" : desc);
            atomic(dir.resolve("pack.json"), o.toString());
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 写 pack.json 失败 {}: {}", name, e.toString());
        }
    }

    // ==================== 资产（元数据 + 像素） ====================

    /** 写一条**像素类**资产（json + px），同名覆盖；顺带保证包存在。 */
    public void writeAsset(String pack, Asset a, int[] px) {
        String id = BoardStore.safeName(a.id());
        if (BoardStore.safeName(pack).isEmpty() || id.isEmpty()) {
            return;
        }
        try {
            Path dir = prepare(pack);
            writeMeta(dir, a, id);
            if (px != null) {
                atomicBytes(dir.resolve(id + ".px"), encode(new Pixels(a.w(), a.h(), px)));
            } else {
                Files.deleteIfExists(dir.resolve(id + ".px"));
            }
            Files.deleteIfExists(dir.resolve(id + ".bp"));          // 这条不是蓝图类：别留同名残件
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 写资产失败 {}/{}: {}", pack, a.id(), e.toString());
        }
    }

    /**
     * 写一条蓝图类资产（模型美术）：元数据 json + 蓝图原文 {@code <id>.bp}（蓝图库文件字节原样拷贝）。
     * ⚠ 扩展名用 {@code .bp}：{@link #readPack} 会把目录里所有 .json 当资产元数据扫，.bp 不参与扫描。
     */
    public void writeBlueprint(String pack, Asset a, byte[] bpJson) {
        String id = BoardStore.safeName(a.id());
        if (BoardStore.safeName(pack).isEmpty() || id.isEmpty() || bpJson == null) {
            return;
        }
        try {
            Path dir = prepare(pack);
            writeMeta(dir, a, id);
            atomicBytes(dir.resolve(id + ".bp"), bpJson);
            Files.deleteIfExists(dir.resolve(id + ".px"));          // 这条不是像素类
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 写蓝图资产失败 {}/{}: {}", pack, a.id(), e.toString());
        }
    }

    /**
     * 写一条物品类资产（自定义物品 = 基底原版物品 + 组件覆盖；无像素无蓝图），只写元数据 json，
     * 并清掉同名残留的 {@code .px} / {@code .bp}（换过类型时不留半个）。
     */
    public void writeItem(String pack, Asset a) {
        String id = BoardStore.safeName(a.id());
        if (BoardStore.safeName(pack).isEmpty() || id.isEmpty() || !a.isItem()) {
            return;
        }
        try {
            Path dir = prepare(pack);
            writeMeta(dir, a, id);
            Files.deleteIfExists(dir.resolve(id + ".px"));
            Files.deleteIfExists(dir.resolve(id + ".bp"));
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 写物品资产失败 {}/{}: {}", pack, a.id(), e.toString());
        }
    }

    /** 建目录 + 保证 pack.json 在（三种写法共用）。 */
    private Path prepare(String pack) throws IOException {
        Path dir = packDir(pack);
        Files.createDirectories(dir);
        if (!Files.isRegularFile(dir.resolve("pack.json"))) {
            writePack(pack, "");
        }
        return dir;
    }

    /**
     * 写元数据 json（三种资产共用）。
     * ⚠ {@code base} / {@code lore} 是物品类专有：**缺省不写** ⇒ 卡牌 / 模型类与老资产一字不变。
     */
    private void writeMeta(Path dir, Asset a, String id) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("kind", a.kind());
        o.addProperty("name", a.name());
        o.addProperty("color", a.color());
        o.addProperty("w", a.w());
        o.addProperty("h", a.h());
        if (a.base() != null && !a.base().isEmpty()) {
            o.addProperty("base", a.base());
        }
        if (a.lore() != null && !a.lore().isEmpty()) {
            com.google.gson.JsonArray ls = new com.google.gson.JsonArray();
            for (String ln : a.lore()) {
                ls.add(ln);
            }
            o.add("lore", ls);
        }
        atomic(dir.resolve(id + ".json"), o.toString());
    }

    /** 删一条资产（元数据 json + 像素 px + 蓝图 bp 一起删）；不存在 = 静默。 */
    public void deleteAsset(String pack, String id) {
        String sid = BoardStore.safeName(id);
        if (sid.isEmpty()) {
            return;
        }
        Path dir = packDir(pack);
        for (String ext : new String[]{".json", ".px", ".bp"}) {
            try {
                Files.deleteIfExists(dir.resolve(sid + ext));
            } catch (IOException e) {
                TableGame.LOGGER.error("[组件库] 删资产失败 {}/{}{}: {}", pack, id, ext, e.toString());
            }
        }
    }

    /** 读一条蓝图资产的原文（没有 / 坏文件 → null）。 */
    public byte[] readBlueprint(String pack, String id) {
        Path f = packDir(pack).resolve(BoardStore.safeName(id) + ".bp");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 读蓝图失败 {}/{}: {}", pack, id, e.toString());
            return null;
        }
    }

    /** 读一条资产的像素（没有 / 坏文件 → null）。 */
    public Pixels readAssetPixels(String pack, String id) {
        Path f = packDir(pack).resolve(BoardStore.safeName(id) + ".px");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return decode(Files.readAllBytes(f));
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 读像素失败 {}/{}: {}", pack, id, e.toString());
            return null;
        }
    }

    /** 改一条资产的**描述行**（只动 json 里的 lore；空表 = 删掉该键）。 */
    public void setLore(String pack, String id, java.util.List<String> lore) {
        Path f = packDir(pack).resolve(BoardStore.safeName(id) + ".json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (lore == null || lore.isEmpty()) {
                o.remove("lore");
            } else {
                com.google.gson.JsonArray ls = new com.google.gson.JsonArray();
                for (String ln : lore) {
                    ls.add(ln);
                }
                o.add("lore", ls);
            }
            atomic(f, o.toString());
        } catch (Exception e) {
            TableGame.LOGGER.error("[组件库] 改描述失败 {}/{}: {}", pack, id, e.toString());
        }
    }

    /** 改一条资产的**显示名**（只动 json 里的 name；文件名与像素都不动）。 */
    public void renameAsset(String pack, String id, String newName) {
        Path f = packDir(pack).resolve(BoardStore.safeName(id) + ".json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            o.addProperty("name", newName == null ? "" : newName.trim());
            atomic(f, o.toString());
        } catch (Exception e) {
            TableGame.LOGGER.error("[组件库] 改名失败 {}/{}: {}", pack, id, e.toString());
        }
    }

    /**
     * 删掉**整个库**（目录连里面的资产一起）—— 组件库屏库行右键「删除」。
     * 返回是否真删了东西（库不存在 = false）。
     */
    public boolean deletePack(String name) {
        String safe = BoardStore.safeName(name);
        Path dir = root.resolve(safe);
        if (safe.isEmpty() || !Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {   // 先删里面的文件，目录最后删
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    TableGame.LOGGER.error("[组件库] 删库失败 {}: {}", f, e.toString());
                }
            });
            return true;
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 删库失败 {}: {}", name, e.toString());
            return false;
        }
    }

    /**
     * 给库**改名** = 改目录名。库里的资产 id / 像素 / 蓝图一概不动（所以别去动里面的文件）。
     * 名字非法 / 新名已被占用 / 原库不存在 → false，什么都不做。
     */
    public boolean renamePack(String from, String to) {
        String a = BoardStore.safeName(from), b = BoardStore.safeName(to);
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        Path src = root.resolve(a), dst = root.resolve(b);
        if (!Files.isDirectory(src) || Files.exists(dst)) {
            return false;
        }
        try {
            Files.move(src, dst);
            return true;
        } catch (IOException e) {
            TableGame.LOGGER.error("[组件库] 库改名失败 {} → {}: {}", from, to, e.toString());
            return false;
        }
    }

    // ==================== 像素编解码（纯逻辑，自检覆盖） ====================

    /** 编码：8 字节头（w, h 各 int 大端）+ w*h 个 ARGB。 */
    public static byte[] encode(Pixels p) {
        int w = Math.max(0, p.w());
        int h = Math.max(0, p.h());
        int n = w * h;
        byte[] out = new byte[8 + n * 4];
        putInt(out, 0, w);
        putInt(out, 4, h);
        for (int i = 0; i < n; i++) {
            int argb = (p.px() != null && i < p.px().length) ? p.px()[i] : 0;
            putInt(out, 8 + i * 4, argb);
        }
        return out;
    }

    /** 解码；头不合法 / 长度不符 → null（坏文件不炸，屏上当作没像素）。 */
    public static Pixels decode(byte[] b) {
        if (b == null || b.length < 8) {
            return null;
        }
        int w = getInt(b, 0);
        int h = getInt(b, 4);
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
            return null;
        }
        if (b.length != 8 + w * h * 4) {
            return null;
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = getInt(b, 8 + i * 4);
        }
        return new Pixels(w, h, px);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    // ==================== 内部 ====================

    private Path packDir(String name) {
        return root.resolve(BoardStore.safeName(name));
    }

    /** 读一个字符串数组键（缺失 / 坏值 → 空表）。 */
    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var el : o.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    private static void atomic(Path target, String text) throws IOException {
        atomicBytes(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicBytes(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
