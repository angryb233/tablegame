package com.tablegame.piece;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardStore;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/**
 * 蓝图库的文件读写 —— 存档风格照 {@link com.tablegame.core.GameStore}：safeName 防路径穿越 + 原子写（tmp + ATOMIC_MOVE）防写一半损档。
 *
 * <p>目录（游戏目录 tablegame/ 下）：{@code pieces/<组>/<名>.json} 组蓝图库（按组分桶，组员共享）；{@code export/pieces/<名>.json} 导出（分享用，无组概念）。
 * 文件内容 = 整个蓝图 ItemStack 的 JSON（{@link ItemStack#CODEC}）——PieceData、CUSTOM_NAME 等组件原样带上。
 * ⚠ CODEC 里的方块状态要查方块注册表，必须用 {@link RegistryOps}（服务端从 {@code server.registryAccess()} 拿），否则存的是空气。
 */
public class PieceLibrary {

    /** 库条目（导入列表用）：名 + 来源（组库名 or 「导出」）。 */
    public record Entry(String name, String source) {
        @Override
        public String toString() {
            return name + "（" + source + "）";
        }
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path piecesRoot;  // tablegame/pieces
    private final Path exportRoot;  // tablegame/export/pieces

    public PieceLibrary(Path gameDir) {
        this.piecesRoot = gameDir.resolve("tablegame").resolve("pieces");
        this.exportRoot = gameDir.resolve("tablegame").resolve("export").resolve("pieces");
    }

    // ===== 路径 =====

    private Path pieceFile(String group, String name) {
        return piecesRoot.resolve(BoardStore.safeName(group)).resolve(BoardStore.safeName(name) + ".json");
    }

    private Path exportFile(String name) {
        return exportRoot.resolve(BoardStore.safeName(name) + ".json");
    }

    /** 库里该组是否已有同名蓝图（保存前的重名/覆盖判定用）。 */
    public boolean exists(String group, String name) {
        return Files.isRegularFile(pieceFile(group, name));
    }


    // ===== 序列化（ItemStack ⇄ JSON，走 RegistryOps 带注册表上下文） =====

    private static JsonElement toJson(ItemStack stack, RegistryAccess access) {
        return ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), stack)
                .getOrThrow();
    }

    /**
     * 蓝图文件 JSON 文本 → 物品（客户端渲染棋子缩略图用；空/坏数据 → {@code ItemStack.EMPTY}）。
     * 客户端造出带 PieceData 的物品，用原版 GUI 物品渲染画进格子（比服务端生成像素省一整套图像管线）。
     */
    public static ItemStack stackFromJson(String json, RegistryAccess access) {
        if (json == null || json.isBlank() || access == null) {
            return ItemStack.EMPTY;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return fromJson(root.get("item"), access);
        } catch (Exception e) {
            TableGame.LOGGER.error("[蓝图库] 解析蓝图文本失败: {}", e.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 蓝图文件 JSON 文本 → 棋子物品（组件库缩略图用）。
     * 蓝图物品的体素不会被渲染（GamePieceSpecialRenderer 只挂在 tablegame:game_piece 上），
     * 把同一份 PieceData 装到棋子物品上走原版 GUI 渲染，才能画出蓝图里的体素。没有体素 → 空物品。
     */
    public static ItemStack pieceStackFromJson(String json, RegistryAccess access) {
        ItemStack bp = stackFromJson(json, access);
        if (bp.isEmpty()) {
            return ItemStack.EMPTY;
        }
        PieceData data = PieceData.get(bp);
        if (data.voxels() == null) {
            return ItemStack.EMPTY;
        }
        ItemStack out = new ItemStack(TableGame.GAME_PIECE.get());
        PieceData.set(out, data);
        return out;
    }

    private static ItemStack fromJson(JsonElement json, RegistryAccess access) {
        return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, access), json)
                .getOrThrow();
    }

    // ===== 存库 / 导出 =====

    /** 存入组蓝图库（原子写）。调用方保证已做权限校验 + 服务端线程。 */
    public void save(String group, String name, ItemStack blueprint, RegistryAccess access) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "tablegame.piece/1");
        root.addProperty("group", group);
        root.addProperty("name", name);
        root.add("item", toJson(blueprint, access));
        atomicWrite(pieceFile(group, name), root);
    }

    /** 导出到 export/pieces/<名>.json（分享用，无组概念，直接覆盖）。 */
    public void export(String name, ItemStack blueprint, RegistryAccess access) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "tablegame.piece/1");
        root.addProperty("name", name);
        root.add("item", toJson(blueprint, access));
        atomicWrite(exportFile(name), root);
    }

    /** 原子写：先写 tmp 再 ATOMIC_MOVE（写一半断电也不会留下半个文件）。 */
    private static void atomicWrite(Path target, JsonObject root) {
        try {
            Files.createDirectories(target.getParent());   // ⚠ 全新存档 tablegame/ 可能还不存在
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.writeString(tmp, PRETTY.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TableGame.LOGGER.error("[蓝图库] 写文件失败 {}: {}", target, e.toString());
        }
    }

    // ===== 读 / 列表 =====

    /** 读库里的一个蓝图；不存在/损坏/反序列化失败返回 null（ItemStack.CODEC 反序列化出的空物品也当 null）。 */
    public ItemStack load(String group, String name, RegistryAccess access) {
        return loadFile(pieceFile(group, name), access);
    }

    /** 读导出的一个蓝图（导入扫描 export/pieces 用）。 */
    public ItemStack loadExport(String name, RegistryAccess access) {
        return loadFile(exportFile(name), access);
    }

    private static ItemStack loadFile(Path f, RegistryAccess access) {
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            ItemStack stack = fromJson(root.get("item"), access);
            return stack.isEmpty() ? null : stack;
        } catch (Exception e) {
            TableGame.LOGGER.error("[蓝图库] 读文件失败 {}: {}", f, e.toString());
            return null;
        }
    }

    /** 蓝图文件**原文**（原样拷进组件库用，导入即拷贝）；没有 → null。 */
    public byte[] readRaw(String group, String name) {
        Path f = pieceFile(group, name);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            TableGame.LOGGER.error("[蓝图库] 读原文失败 {}/{}: {}", group, name, e.toString());
            return null;
        }
    }

    public List<Entry> listFor(List<String> myGroups) {
        List<Entry> out = new ArrayList<>();
        if (Files.isDirectory(piecesRoot)) {
            try (Stream<Path> dirs = Files.list(piecesRoot)) {
                dirs.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(myGroups::contains)   // 只列我所在组的库
                        .sorted()
                        .forEach(g -> listDir(piecesRoot.resolve(g), g, out));
            } catch (IOException ignored) {}
        }
        listDir(exportRoot, "导出", out);
        return out;
    }

    /** 扫一个目录的 .json 文件名（去掉 .json 后缀）进列表，按字母序。 */
    private static void listDir(Path dir, String source, List<Entry> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted(Comparator.naturalOrder())
                    .forEach(n -> out.add(new Entry(n, source)));
        } catch (IOException ignored) {}
    }

    /** 删库里的一条。 */
    public boolean delete(String group, String name) {
        try {
            return Files.deleteIfExists(pieceFile(group, name));
        } catch (IOException e) {
            TableGame.LOGGER.error("[蓝图库] 删除失败 {}/{}: {}", group, name, e.toString());
            return false;
        }
    }

    /** 删导出目录的一条（蓝图库菜单用）。 */
    public boolean deleteExport(String name) {
        try {
            return Files.deleteIfExists(exportFile(name));
        } catch (IOException e) {
            TableGame.LOGGER.error("[蓝图库] 删除导出失败 {}: {}", name, e.toString());
            return false;
        }
    }

    /** pieces 库根目录（蓝图库菜单「打开文件夹」用；仅集成服/单机有本地磁盘意义）。 */
    public Path piecesRoot() {
        return piecesRoot;
    }
}
