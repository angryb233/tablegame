package com.tablegame.drawboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.TableGame;

/**
 * 画板项目的文件读写。目录结构（都在服务器游戏目录下，单机即 .minecraft）：
 * <ul>
 *   <li>{@code tablegame/projects/<组>/<项目名>.json}  项目存档（按组分组）
 *   <li>{@code tablegame/export/}                      导出（JSON，按作者名）
 * </ul>
 * 客户端个人镜像写到客户端游戏目录的同一相对路径（联机 = 服务器 + 个人双份）。
 *
 * <p>JSON 格式（可手改）：
 * <pre>
 * { "width": 32, "height": 48, "ownerName": "Steve", "rows": ["00000000aarrggbb...", ...] }
 * </pre>
 * 兼容旧格式：只含 {@code size} 的文件按 w=h=size 读。导出只写 JSON（PNG 需外部工具从 rows 转）。
 */
public class BoardStore {
    private final Path projectRoot;   // tablegame/projects
    private final Path exportDir;     // tablegame/export

    public BoardStore(Path gameDir) {
        this.projectRoot = gameDir.resolve("tablegame").resolve("projects");
        this.exportDir = gameDir.resolve("tablegame").resolve("export");
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path exportDir() {
        return exportDir;
    }

    /** 名字 → 安全路径段（防目录穿越；中文保留，其余非常规字符替换）。 */
    public static String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\n\r\t]", "_").trim();
    }

    private Path projectFile(String group, String name) {
        return projectRoot.resolve(safeName(group)).resolve(safeName(name) + ".json");
    }

    // ===== 项目存档 =====

    /** 读项目；不存在/损坏返回 null。 */
    public Board loadProject(String group, String name) {
        Path f = projectFile(group, name);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            int w, h;
            if (root.has("width")) {
                w = root.get("width").getAsInt();
                h = root.has("height") ? root.get("height").getAsInt() : w;
            } else {
                int size = root.get("size").getAsInt(); // 旧格式容错
                w = h = size;
            }
            if (w < 1 || w > 1024 || h < 1 || h > 1024) return null;
            String owner = root.has("ownerName") ? root.get("ownerName").getAsString() : "?";
            Board board = new Board(group, name, owner, w, h);
            parseRowsInto(board, root.getAsJsonArray("rows"));
            return board;
        } catch (Exception e) {
            TableGame.LOGGER.error("[画板] 读项目失败 {}: {}", f, e.toString());
            return null;
        }
    }

    /** 落盘（原子写：临时文件 + move）。调用方保证在服务端线程。 */
    public void save(Board board) {
        try {
            Path f = projectFile(board.group, board.name);
            Files.createDirectories(f.getParent());
            writeJson(f, board);
            board.dirty = false;
        } catch (IOException e) {
            TableGame.LOGGER.error("[画板] 存项目失败 {}: {}", board.key(), e.toString());
        }
    }

    /** 组目录下是否已存在同名项目。 */
    public boolean exists(String group, String name) {
        return Files.isRegularFile(projectFile(group, name));
    }

    /** 删除项目文件（本地/单机：彻底删除）。调用方先做权限校验；返回是否真删了。 */
    public boolean deleteProjectFile(String group, String name) {
        try {
            return Files.deleteIfExists(projectFile(group, name));
        } catch (IOException e) {
            TableGame.LOGGER.error("[画板] 删项目失败 {}: {}", group + "/" + name, e.toString());
            return false;
        }
    }

    /**
     * 把项目移到垃圾桶（专用服务器：tablegame/trash/）。目标 = trash/<组>/<名>_<毫秒时间戳>.json。
     * 返回新路径；源文件不存在/失败返回 null。
     */
    public Path moveProjectToTrash(String group, String name) {
        Path src = projectFile(group, name);
        if (!Files.isRegularFile(src)) return null;
        Path trashRoot = projectRoot.getParent().resolve("trash");
        Path dir = trashRoot.resolve(safeName(group));
        String base = safeName(name) + "_" + System.currentTimeMillis();
        Path target = dir.resolve(base + ".json");
        try {
            Files.createDirectories(dir);
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            TableGame.LOGGER.error("[画板] 移垃圾桶失败 {}: {}", group + "/" + name, e.toString());
            return null;
        }
    }

    /** 扫描全部项目，返回按组分组的条目（过滤由调用方按「我所在组」做）。 */
    public List<ProjectEntry> listProjects() {
        List<ProjectEntry> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;
        try (Stream<Path> groups = Files.list(projectRoot)) {
            List<Path> groupDirs = groups.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path g : groupDirs) {
                String group = g.getFileName().toString();
                try (Stream<Path> files = Files.list(g)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                         .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                         .forEach(p -> out.add(entryOf(group, p)));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    /** 读取一个项目文件的头部信息（名/作者/宽高），用于列表展示；失败返回 null。 */
    private ProjectEntry entryOf(String group, Path f) {
        String name = f.getFileName().toString();
        name = name.substring(0, name.length() - 5);
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("width")) {
                return new ProjectEntry(group, name,
                        root.has("ownerName") ? root.get("ownerName").getAsString() : "?",
                        root.get("width").getAsInt(),
                        root.has("height") ? root.get("height").getAsInt() : root.get("width").getAsInt());
            }
            int size = root.get("size").getAsInt();
            return new ProjectEntry(group, name,
                    root.has("ownerName") ? root.get("ownerName").getAsString() : "?", size, size);
        } catch (Exception e) {
            return new ProjectEntry(group, name, "(损坏)", 0, 0);
        }
    }

    /** 项目列表条目。 */
    public record ProjectEntry(String group, String name, String ownerName, int width, int height) {
        public String key() {
            return group + "/" + name;
        }
    }

    // ===== 导出 =====

    /** 导出当前内容为 JSON（文件名 = 作者名，自动覆盖旧导出）。返回导出目录。 */
    public Path exportBoard(Board board) throws IOException {
        Files.createDirectories(exportDir);
        String base = safeName(board.ownerName);
        if (base.isEmpty()) base = safeName(board.name);
        writeJson(exportDir.resolve(base + ".json"), board);
        return exportDir;
    }


    // ===== 组（阶段 B） =====

    /** 一个真实组。单人组（组名 = 玩家名）不落盘、永远隐式存在。 */
    public record GroupInfo(String name, String owner, List<String> members, boolean open, List<String> readOnly, List<String> admins) {
        public boolean contains(String player) {
            return members.contains(player);
        }
    }

    /** groups.json 位于 tablegame/groups.json（与 projects/、export/ 同级）。 */
    public Path groupsFile() {
        return projectRoot.getParent().resolve("groups.json");
    }

    /** 读全部组；文件不存在/损坏返回空表。 */
    public List<GroupInfo> loadGroups() {
        List<GroupInfo> out = new ArrayList<>();
        Path f = groupsFile();
        if (!Files.isRegularFile(f)) return out;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("name") || !o.has("owner")) continue;
                List<String> members = new ArrayList<>();
                if (o.has("members")) o.getAsJsonArray("members").forEach(m -> members.add(m.getAsString()));
                List<String> ro = new ArrayList<>();
                if (o.has("readOnly")) o.getAsJsonArray("readOnly").forEach(m -> ro.add(m.getAsString()));
                List<String> admins = new ArrayList<>();
                if (o.has("admins")) o.getAsJsonArray("admins").forEach(m -> admins.add(m.getAsString()));
                // 容错：owner 必须出现在 members 里
                if (!members.contains(o.get("owner").getAsString())) members.add(0, o.get("owner").getAsString());
                out.add(new GroupInfo(o.get("name").getAsString(), o.get("owner").getAsString(),
                        members, o.has("open") && o.get("open").getAsBoolean(), ro, admins));
            }
        } catch (Exception e) {
            TableGame.LOGGER.error("[画板] 读组失败: {}", e.toString());
        }
        return out;
    }

    /** 全量原子写盘（组每次变更都整表重写；组数量级很小，无需增量）。 */
    public void saveGroups(List<GroupInfo> groups) {
        try {
            Files.createDirectories(groupsFile().getParent());   // ⚠ 服务器全新存档时 tablegame/ 可能还不存在
            JsonArray arr = new JsonArray();
            for (GroupInfo g : groups) {
                JsonObject o = new JsonObject();
                o.addProperty("name", g.name());
                o.addProperty("owner", g.owner());
                o.addProperty("open", g.open());
                JsonArray ms = new JsonArray();
                g.members().forEach(ms::add);
                o.add("members", ms);
                JsonArray rs = new JsonArray();
                g.readOnly().forEach(rs::add);
                o.add("readOnly", rs);
                JsonArray as = new JsonArray();
                g.admins().forEach(as::add);
                o.add("admins", as);
                arr.add(o);
            }
            Path tmp = groupsFile().resolveSibling("groups.json.tmp");
            Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, groupsFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TableGame.LOGGER.error("[画板] 存组失败: {}", e.toString());
        }
    }

    /** 是否存在某组名的项目目录（防真实组名与别人的单人组目录撞名）。 */
    public boolean groupDirExists(String group) {
        return Files.isDirectory(projectRoot.resolve(safeName(group)));
    }

    // ===== JSON 细节 =====

    private static void writeJson(Path target, Board board) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("width", board.width);
        root.addProperty("height", board.height);
        root.addProperty("ownerName", board.ownerName);
        root.addProperty("group", board.group);
        JsonArray rows = new JsonArray(board.height);
        for (int y = 0; y < board.height; y++) {
            StringBuilder sb = new StringBuilder(board.width * 8);
            int base = y * board.width;
            for (int x = 0; x < board.width; x++) {
                sb.append(Cells.toHex8(board.pixels[base + x]));
            }
            rows.add(sb.toString());
        }
        root.add("rows", rows);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void parseRowsInto(Board board, JsonArray rows) {
        for (int y = 0; y < board.height && y < rows.size(); y++) {
            String row = rows.get(y).getAsString();
            int base = y * board.width;
            for (int x = 0; x < board.width; x++) {
                if ((x + 1) * 8 > row.length()) break;
                board.pixels[base + x] = Cells.fromHex8(row.substring(x * 8, (x + 1) * 8));
            }
        }
    }
}
