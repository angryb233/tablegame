package com.tablegame.drawboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.tablegame.TableGame;
import com.tablegame.host.HostManager;
import com.tablegame.net.HostPackets;
import com.tablegame.piece.PieceLibrary;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端画板（项目）总管，每进程一实例。
 * 项目 key = {@code 组/项目名}；玩家默认单人组（组名=玩家名），真实组来自 groups.json，按「我所在组」过滤列表。
 * 职责：项目缓存 + 观看登记表（广播发给所有在看同一项目的人）+ 网络操作翻成 Board 操作并广播/落盘，全部在服务端主线程。
 * // ponytail: 若未来把 handler 挪到网络线程再考虑线程安全
 */
public class BoardManager {
    private static BoardManager INSTANCE;

    private final MinecraftServer server;
    private final BoardStore store;
    private final Map<String, Board> boards = new HashMap<>();       // key(组/名) -> Board
    private final Map<UUID, String> viewing = new HashMap<>();       // 玩家 -> 正在看的项目 key
    /** 局内临时板的观看登记：板 key -> 看它的玩家（HUD art 框用，非画板屏）。 */
    private final Map<String, Set<UUID>> stageViewers = new HashMap<>();
    /** 临时板序号（防同名两局串台）。 */
    private int stageSeq;
    private final List<BoardStore.GroupInfo> groups = new ArrayList<>(); // 真实组缓存（groups.json 镜像；单人组不在此）

    /** 画布宽高范围（防恶意尺寸撑爆内存/渲染）。 */
    public static final int MIN_DIM = 8;
    public static final int MAX_DIM = 512;
    /** 项目名长度上限。 */
    public static final int MAX_NAME = 40;
    /** 真实组名长度上限。 */
    public static final int MAX_GROUP = 24;
    /** S2C 单包上限 1MiB ≈ 130k 格；广播分块按 2 万格（160KB）一包。 */
    private static final int BROADCAST_CHUNK_CELLS = 20000;

    private BoardManager(MinecraftServer server) {
        this.server = server;
        this.store = new BoardStore(server.getServerDirectory());
        this.pieceLibrary = new PieceLibrary(server.getServerDirectory());
    }

    public static void init(MinecraftServer server) {
        INSTANCE = new BoardManager(server);
        INSTANCE.groups.addAll(INSTANCE.store.loadGroups());
        TableGame.LOGGER.info("[画板] 已初始化，项目目录: {}（真实组 {} 个）",
                INSTANCE.store.projectRoot(), INSTANCE.groups.size());
    }

    public static void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.saveAll();
            INSTANCE = null;
        }
    }

    public static BoardManager get() {
        return INSTANCE;
    }

    /** 画板存档读写器（游戏定义读画板卡面像素用；画板仍是美术唯一真源）。 */
    public BoardStore store() {
        return store;
    }

    /** 蓝图库存取器（模型制作器保存/导出/导入共用；目录随 BoardManager 一次性算好）。 */
    public PieceLibrary pieceLibrary() {
        return pieceLibrary;
    }

    // 蓝图库：目录 tablegame/pieces + tablegame/export/pieces，无缓存，存/读直接落盘
    private final PieceLibrary pieceLibrary;

    /** 玩家默认组（单人组 = 玩家名）。 */
    public static String defaultGroup(ServerPlayer player) {
        return player.getName().getString();
    }

    // ===== 组：身份与权限判定（全部以玩家名为准，与存档目录一致） =====

    private static void say(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s));
    }

    /** 组名合法性：≤24 字符，不含空格与 /、\（会进路径与指令参数）。 */
    public static boolean validGroupName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_GROUP
                && !name.contains(" ") && !name.contains("/") && !name.contains("\\");
    }

    /** 玩家可用的组：自己的单人组永远第一，其后是他加入的真实组（保持 groups.json 顺序）。 */
    public List<String> myGroupNames(ServerPlayer player) {
        String me = player.getName().getString();
        List<String> out = new ArrayList<>();
        out.add(me);
        for (BoardStore.GroupInfo g : groups) {
            if (g.contains(me) && !out.contains(g.name())) out.add(g.name());
        }
        return out;
    }

    private BoardStore.GroupInfo findGroup(String name) {
        for (BoardStore.GroupInfo g : groups) {
            if (g.name().equals(name)) return g;
        }
        return null;
    }

    /** 组员判定：组名 == 玩家名 = 该玩家的单人组；否则查真实组 members。 */
    private boolean inGroup(String group, String player) {
        if (group == null) return false;
        if (group.equals(player)) return true;
        BoardStore.GroupInfo g = findGroup(group);
        return g != null && g.contains(player);
    }

    /** 真实组里该玩家是否被组长标为只读（单人组/组长本人永不只读）。 */
    private boolean readOnlyOf(String group, String player) {
        BoardStore.GroupInfo g = findGroup(group);
        return g != null && !g.owner().equals(player) && !g.admins().contains(player)
                && g.readOnly().contains(player);
    }

    /** 管理权限：组长或管理员（invite/kick/setReadOnly 的门槛）。 */
    private boolean canManage(BoardStore.GroupInfo g, String player) {
        return g.owner().equals(player) || g.admins().contains(player);
    }

    /** 写权限：组员且非只读（读取只要 inGroup）。局内临时板只看「在不在任意一局」，精确校验在 {@link #requireWrite}。 */
    public boolean canWrite(String group, String player) {
        if (isStageGroup(group)) {
            HostManager hm = HostManager.get();
            return hm != null && hm.inAnyStage(player);
        }
        return inGroup(group, player) && !readOnlyOf(group, player);
    }

    /** 变更后整表写盘。 */
    private void saveGroups() {
        store.saveGroups(groups);
    }

    // ===== 项目 =====

    /** 新建项目：名/尺寸校验、重名拒绝；成功后直接打开（登记观看 + 发全量）。 */
    public void createProject(ServerPlayer player, String group, String name, int width, int height) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME
                || name.contains("/") || name.contains("\\")) {
            say(player, "[画板] 项目名不合法（≤" + MAX_NAME + "字符，不能含 / 与 \\）");
            return;
        }
        if (width < MIN_DIM || width > MAX_DIM || height < MIN_DIM || height > MAX_DIM) {
            say(player, "[画板] 尺寸需在 " + MIN_DIM + "~" + MAX_DIM + " 之间");
            return;
        }
        // 目标组：客户端可选；空 = 自己的单人组
        String g = (group == null || group.isBlank()) ? defaultGroup(player) : group;
        if (!myGroupNames(player).contains(g)) {
            say(player, "[画板] 你不在组「" + g + "」中，无法在那里创建项目");
            return;
        }
        if (store.exists(g, name)) {
            say(player, "[画板] 组「" + g + "」里已有同名项目「" + name + "」");
            return;
        }
        Board board = new Board(g, name, player.getName().getString(), width, height);
        boards.put(board.key(), board);
        store.save(board);
        openAndSend(player, board);
    }

    /** 打开已有项目：组员校验 → 读盘/取缓存 → 登记观看 → 发全量。 */
    public void openProject(ServerPlayer player, String key) {
        String me = player.getName().getString();
        Board board = boards.get(key);
        if (board == null) {
            int slash = key.indexOf('/');
            if (slash <= 0 || slash == key.length() - 1) {
                say(player, "[画板] 项目不存在: " + key);
                return;
            }
        // 权限前置：先确认属于该玩家所在组再读盘，防越权读文件
            String g = key.substring(0, slash);
            if (!inGroup(g, me)) {
                say(player, "[画板] 你不在组「" + g + "」中，无法打开该组项目");
                return;
            }
            board = store.loadProject(g, key.substring(slash + 1));
            if (board == null) {
                say(player, "[画板] 项目不存在: " + key);
                return;
            }
            boards.put(key, board);
        } else if (!inGroup(board.group, me)) {
            say(player, "[画板] 你不在组「" + board.group + "」中，无法打开该组项目");
            return;
        }
        openAndSend(player, board);
    }

    /** 登记观看者并发整板全量。canEdit = 组员且非只读（客户端据此隐藏画笔/填充/橡皮）。 */
    private void openAndSend(ServerPlayer player, Board board) {
        viewing.put(player.getUUID(), board.key());
        BoardPackets.OpenBoardPayload payload = new BoardPackets.OpenBoardPayload(
                board.key(), board.ownerName, board.width, board.height,
                Cells.fullBoardCells(board.width, board.height, board.pixels),
                canWrite(board.group, player.getName().getString()));
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 请求项目列表：只返回「我所在组」的项目，并带每条 canEdit（列表只读标记用）。 */
    public void sendProjectList(ServerPlayer player) {
        List<String> mine = myGroupNames(player);
        String me = player.getName().getString();
        java.util.List<BoardPackets.ProjectInfoPayload> list = new java.util.ArrayList<>();
        for (BoardStore.ProjectEntry e : store.listProjects()) {
            if (!mine.contains(e.group())) continue;
            list.add(new BoardPackets.ProjectInfoPayload(e.group(), e.name(), e.ownerName(),
                    e.width(), e.height(), canWrite(e.group(), me), canDeleteProject(me, e.group())));
        }
        PacketDistributor.sendToPlayer(player, new BoardPackets.ProjectsListPayload(list));
    }

    /** 删除项目。权限：单人组仅本人，真实组仅组长/管理员。集成服彻底删文件，专用服务器移入 tablegame/trash/；在看的人被撤登记并弹回主菜单。 */
    public void deleteProject(ServerPlayer player, String key) {
        String me = player.getName().getString();
        int slash = key == null ? -1 : key.indexOf('/');
        if (key == null || slash <= 0 || slash == key.length() - 1) {
            say(player, "[画板] 项目不存在: " + key);
            return;
        }
        String group = key.substring(0, slash);
        String name = key.substring(slash + 1);
        if (!group.equals(me)) {
            BoardStore.GroupInfo g = requireManage(player, group);
            if (g == null) return;   // 组不存在/无管理权（已提示）
        }
        if (!store.exists(group, name) && !boards.containsKey(key)) {
            say(player, "[画板] 项目不存在: " + key);
            return;
        }
        boards.remove(key);   // 清内存缓存（未封存笔画一并丢弃）
        viewing.forEach((uuid, k) -> {
            if (k != null && k.equals(key)) {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null) {
                    say(p, "[画板] 项目 " + key + " 已被删除");
                    PacketDistributor.sendToPlayer(p, new BoardPackets.OpenMenuPayload());
                }
            }
        });
        viewing.entrySet().removeIf(e -> key.equals(e.getValue()));
        // 按运行环境处置文件
        if (server.isDedicatedServer()) {
            java.nio.file.Path t = store.moveProjectToTrash(group, name);
            say(player, t != null
                    ? "[画板] 已删除（文件移入垃圾桶: " + t.getFileName() + "）"
                    : "[画板] 删除失败：文件不在磁盘（可能已被移动/删除）");
        } else {
            say(player, store.deleteProjectFile(group, name)
                    ? "[画板] 已彻底删除"
                    : "[画板] 删除失败：文件不在磁盘（可能已被移动/删除）");
        }
        sendProjectList(player);
    }

    /** 删除权限判定（列表 canDelete 用）：单人组仅本人；真实组组长/管理员。 */
    private boolean canDeleteProject(String me, String group) {
        if (group.equals(me)) return true;
        BoardStore.GroupInfo g = findGroup(group);
        return g != null && canManage(g, me);
    }

    /** 当前玩家正在看的项目，且必须有权限（没在看/越权返回 null）；普通项目看「在不在组里」，局内临时板看「在不在拥有这块板的那一局里」。 */
    private Board currentBoard(ServerPlayer player, String key) {
        Board board = boards.get(key);
        if (board == null) return null;
        String me = player.getName().getString();
        // 局内临时板（@stage/...）：合法性只看「是不是这一局的席位」；舞台 UI 绘画区的人没开过画板屏，viewing 里没有他，用 viewing 卡会丢笔迹。
        if (isStage(board)) return inStage(board.key(), me) ? board : null;
        if (!viewing.getOrDefault(player.getUUID(), "").equals(key)) return null;
        return inGroup(board.group, me) ? board : null;
    }

    /** 写操作守卫：能改才放行（失败已提示）。局内临时板走 {@link #inStage}。 */
    private boolean requireWrite(ServerPlayer player, Board board) {
        String me = player.getName().getString();
        if (isStage(board)) {
            if (inStage(board.key(), me)) return true;
            say(player, "[画板] 你不在这一局里，不能改这块对局画板");
            return false;
        }
        if (canWrite(board.group, me)) return true;
        say(player, "[画板] 你在组「" + board.group + "」内为只读（或不在组中），不能修改");
        return false;
    }

    /** 局内临时板的存在/可改判定交给主持人。 */
    private static boolean inStage(String key, String player) {
        HostManager hm = HostManager.get();
        return hm != null && hm.canEditStageBoard(key, player);
    }

    /** key 属于局内临时板？ */
    private static boolean isStageGroup(String group) {
        return group != null && group.startsWith(HostManager.STAGE_GROUP);
    }

    /** 这块板是局内临时板？ */
    private static boolean isStage(Board b) {
        return b != null && isStageGroup(b.group);
    }

    /**
     * 落盘口（唯一）：局内临时板永不落盘。所有「改完就存」都走这里，别直接调 store.save。
     */
    private void persist(Board b) {
        if (!isStage(b)) store.save(b);
    }

    // ===== 编辑操作 =====

    public boolean stroke(ServerPlayer player, String key, long strokeId, byte[] cells, boolean end) {

        Board board = currentBoard(player, key);
        if (board == null) return false;
        if (!requireWrite(player, board)) return false;   // 只读组员拒笔（正常客户端已被 canEdit 挡住，防伪造包）
        int changed = board.applyStroke(cells, player.getUUID().toString(), strokeId, end);
        if (changed == 0 && !end) return false;
        if (changed > 0) broadcastCells(key, cells);
        if (end && board.dirty) {
            persist(board); // ponytail: 每笔同步写盘（局内临时板不落盘），量大可改防抖合并写
        }
        return true;
    }

    public boolean undo(ServerPlayer player, String key) {
        Board board = currentBoard(player, key);
        if (board == null) return false;
        if (!requireWrite(player, board)) return true;   // 已发原因，调用方无需再补「没有可撤销」
        byte[] reverted = board.undoLatestOf(player.getUUID().toString());
        if (reverted == null) return false;
        broadcastCells(key, reverted);
        persist(board);
        return true;
    }

    public void fill(ServerPlayer player, String key, int x, int y, int color) {
        Board board = currentBoard(player, key);
        if (board == null) return;
        if (!requireWrite(player, board)) return;
        byte[] cells = board.applyFill(player.getUUID().toString(), x, y, color);
        if (cells == null) {
            player.sendSystemMessage(Component.literal("[画板] 该区域已经是这个颜色，无需填充"));
            return;
        }
        broadcastCells(key, cells);
        persist(board);
    }

    // ===== 导出 / 导入 =====

    public void exportBoard(ServerPlayer player, String key) {
        Board board = boards.get(key);
        if (board == null) return;
        // 只读成员也能导出（导出 = 下载副本，不改板）；非组员不行
        if (!inGroup(board.group, player.getName().getString())) {
            say(player, "[画板] 你不在组「" + board.group + "」中，不能导出该组项目");
            return;
        }
        try {
            java.nio.file.Path dir = store.exportBoard(board);
            String base = board.ownerName.replaceAll("[^A-Za-z0-9_-]", "_");
            say(player, "[画板] 已导出: " + dir.toAbsolutePath().normalize()
                    + java.io.File.separator + base + ".json");
        } catch (Exception e) {
            TableGame.LOGGER.error("[画板] 导出失败: {}", e.toString());
            player.sendSystemMessage(Component.literal("[画板] 导出失败: " + e.getMessage()));
        }
    }



    // ===== 广播 =====

    private void broadcastCells(String key, byte[] cells) {
        int chunk = BROADCAST_CHUNK_CELLS * 8;
        for (int off = 0; off < cells.length; off += chunk) {
            int len = Math.min(chunk, cells.length - off);
            byte[] part = new byte[len];
            System.arraycopy(cells, off, part, 0, len);
            BoardPackets.BoardCellsPayload payload = new BoardPackets.BoardCellsPayload(key, part);
            viewing.forEach((uuid, k) -> {
                if (k.equals(key)) {
                    ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                    if (p != null) PacketDistributor.sendToPlayer(p, payload);
                }
            });
            // 局内临时板：局内玩家没开画板屏（只在 HUD art 框看）也要收增量
            forEachStageViewer(key, p -> PacketDistributor.sendToPlayer(p, payload));
        }
    }


    /** 玩家断开：取消观看登记 + 丢弃其未封存笔画。 */
    public void playerLoggedOut(UUID playerId) {
        viewing.remove(playerId);
        stageViewers.values().forEach(s -> s.remove(playerId));
        for (Board b : boards.values()) {
            b.dropOpenStroke(playerId.toString());
        }
    }

    // ===== 局内临时板（引擎 v0 的「画板（本局）」框：不改项目、不落盘） =====

    /**
     * 建一块局内临时板（主持人开局时调，一局一块）；key = {@code @stage/<游戏名>-<序号>}（序号防同名两局串台）。
     * 与普通项目三点不同：不落盘、不进项目列表、成员判断走对局而非组。
     */
    public String createStageBoard(String label) {
        String name = label + "-" + (++stageSeq);
        Board b = new Board(HostManager.STAGE_GROUP, name, "对局", HostManager.STAGE_DIM, HostManager.STAGE_DIM);
        boards.put(b.key(), b);
        TableGame.LOGGER.info("[对局] 建临时画板 {} ({}x{})", b.key(), b.width, b.height);
        return b.key();
    }

    /** 登记舞台观看者（局内玩家）：他没开画板屏，但 HUD art 框要看增量。 */
    public void stageWatch(String key, UUID player) {
        stageViewers.computeIfAbsent(key, k -> new HashSet<>()).add(player);
    }

    /**
     * 清空一块局内临时板（新一轮开始时用）：像素全抹 0 + 清掉撤销栈（否则按撤销上一轮的画会「复活」）+ 发全量覆盖客户端旧画。
     * 谁在什么时候清由脚本说（原语 {@code clear_board()}）。
     */
    public void clearStageBoard(String key) {
        Board b = boards.get(key);
        if (b == null) return;
        java.util.Arrays.fill(b.pixels, 0);
        b.undoStack.clear();
        // ponytail: undoCellCount 私有且只当「撤销预算上限」，清栈后偏大 = 预算更严，无害；要归零得给 Board 加方法，不值。
        sendStageBoardFull(key);
        TableGame.LOGGER.info("[对局] 清空临时画板 {}", key);
    }

    public void sendStageBoardFull(String key) {
        Board b = boards.get(key);
        if (b == null) return;
        var payload = new HostPackets.StageBoardPayload(key, b.width, b.height,
                Cells.fullBoardCells(b.width, b.height, b.pixels));
        forEachStageViewer(key, p -> PacketDistributor.sendToPlayer(p, payload));
    }

    /** 落盘用：把这块临时板的像素拷一份出来（没有 → null）。必须拷贝：调用方序列化的同时还会把引用交给下一个 tick，共享可变数组会写到半幅画。 */
    public int[] stageBoardPixels(String key) {
        Board b = boards.get(key);
        return b == null ? null : java.util.Arrays.copyOf(b.pixels, b.pixels.length);
    }

    /** 读档用：把像素灌回这块临时板 + 给观看者发一次全量（没有这块板 / 长度不对 → false）。 */
    public boolean restoreStageBoardPixels(String key, int[] pixels) {
        Board b = boards.get(key);
        if (b == null || pixels == null || pixels.length != b.pixels.length) return false;
        System.arraycopy(pixels, 0, b.pixels, 0, pixels.length);
        b.undoStack.clear();                 // 读档回来没有「上一笔」可撤（同 clearStageBoard 的口径）
        sendStageBoardFull(key);
        TableGame.LOGGER.info("[对局] 临时画板 {} 接回 {} 个像素", key, pixels.length);
        return true;
    }

    /** 对局结束：移除观看登记 + 丢掉这块板（未封存笔画一并丢弃）。 */
    public void disposeStageBoard(String key) {
        stageViewers.remove(key);
        Board b = boards.remove(key);
        if (b != null) TableGame.LOGGER.info("[对局] 销毁临时画板 {}", key);
    }

    /** 画者用：打开发给这一局的临时板（走普通画板屏，能画能导出，只是不落盘）。 */
    public void openStageBoard(ServerPlayer player, String key) {
        Board b = boards.get(key);
        if (b == null) {
            say(player, "[画板] 对局画板已不存在（对局可能已结束）");
            return;
        }
        openAndSend(player, b);
    }

    /** 按 key 把舞台全量重发（当前只有局内临时板在用）。 */
    private void forEachStageViewer(String key, java.util.function.Consumer<ServerPlayer> fn) {
        Set<UUID> ids = stageViewers.get(key);
        if (ids == null) return;
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) fn.accept(p);
        }
    }

    // ===== 组操作（/tablegame group …，全部服务端主线程） =====

    /** 把我的组名列表发给客户端（新建项目界面的「创建到」选择用）。 */
    public void sendMyGroups(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new BoardPackets.GroupsListPayload(myGroupNames(player)));
    }

    /** 组管理 GUI 快照：mine=我所在的真实组（单人组不在内，客户端拼首行），joinable=我没加入的公开组；身份由客户端对照 owner/members/readOnly 判定。 */
    public void sendGroupUi(ServerPlayer player) {
        String me = player.getName().getString();
        List<BoardPackets.GroupUiPayload> mine = new ArrayList<>();
        List<BoardPackets.GroupUiPayload> joinable = new ArrayList<>();
        for (BoardStore.GroupInfo g : groups) {
            // 拷贝列表：载荷会进网络线程，不能直接交出内部可变列表
            BoardPackets.GroupUiPayload p = new BoardPackets.GroupUiPayload(g.name(), g.owner(),
                    new ArrayList<>(g.members()), g.open(), new ArrayList<>(g.readOnly()), new ArrayList<>(g.admins()));
            if (g.contains(me)) mine.add(p);
            else if (g.open()) joinable.add(p);
        }
        PacketDistributor.sendToPlayer(player, new BoardPackets.GroupsUiPayload(mine, joinable));
    }

    /** 组管理 GUI 通用操作入口（对应 {@link BoardPackets.GroupActionPayload}）：执行后总是重发快照；未知 action 静默忽略。 */
    public void groupAction(ServerPlayer player, String action, String group, String target, boolean flag) {
        switch (action == null ? "" : action) {
            case "create" -> createGroup(player, group);
            case "invite" -> inviteGroup(player, group, target);
            case "join" -> joinGroup(player, group);
            case "leave" -> leaveGroup(player, group);
            case "rule" -> setGroupOpen(player, group, flag);
            case "readonly" -> setReadOnly(player, group, target, flag);
            case "admin" -> setGroupAdmin(player, group, target, flag);
            case "kick" -> kickMember(player, group, target);
            case "disband" -> disbandGroup(player, group);
            default -> { return; }   // 防伪造：未知 action 不发快照不提示
        }
        sendGroupUi(player);
    }

    /** owner-only 守卫：不是组长/组不存在则发提示并返回 null。 */
    private BoardStore.GroupInfo requireOwner(ServerPlayer player, String groupName) {
        BoardStore.GroupInfo g = findGroup(groupName);
        if (g == null) {
            say(player, "[画板] 组不存在: " + groupName);
            return null;
        }
        if (!g.owner().equals(player.getName().getString())) {
            say(player, "[画板] 只有组长 " + g.owner() + " 能执行该操作");
            return null;
        }
        return g;
    }

    /** manage 守卫：组长或管理员（invite/kick/setReadOnly 共用）；否则发提示返回 null。 */
    private BoardStore.GroupInfo requireManage(ServerPlayer player, String groupName) {
        BoardStore.GroupInfo g = findGroup(groupName);
        if (g == null) {
            say(player, "[画板] 组不存在: " + groupName);
            return null;
        }
        if (!canManage(g, player.getName().getString())) {
            say(player, "[画板] 只有组长 " + g.owner() + " 或管理员能执行该操作");
            return null;
        }
        return g;
    }

    /** create：建私有组（owner=自己，成员=自己）。 */
    public void createGroup(ServerPlayer player, String name) {
        String me = player.getName().getString();
        if (!validGroupName(name) || name.equals(me)) {
            say(player, "[画板] 组名不合法：≤" + MAX_GROUP + "字符、不含空格与 / 或 \\，且不能与自己的单人组同名");
            return;
        }
        if (findGroup(name) != null) {
            say(player, "[画板] 组「" + name + "」已存在");
            return;
        }
        if (store.groupDirExists(name)) {
            say(player, "[画板] 组名与已有项目目录冲突（可能是别的玩家的单人组），换一个名字");
            return;
        }
        groups.add(new BoardStore.GroupInfo(name, me, new ArrayList<>(List.of(me)), false, new ArrayList<>(), new ArrayList<>()));
        saveGroups();
        say(player, "[画板] 已创建私有组「" + name + "」。邀请成员：/tablegame group invite " + name + " <玩家名>");
    }

    /** invite：组长拉人入组（v1 无需对方同意；对方在线则即时通知）。 */
    public void inviteGroup(ServerPlayer player, String groupName, String target) {
        BoardStore.GroupInfo g = requireManage(player, groupName);
        if (g == null) return;
        if (target == null || target.isBlank()) {
            say(player, "[画板] 玩家名不能为空");
            return;
        }
        if (g.contains(target)) {
            say(player, "[画板] " + target + " 已在组「" + groupName + "」中");
            return;
        }
        g.members().add(target);
        g.readOnly().remove(target);   // 被再次邀请时清掉旧只读标记
        saveGroups();
        say(player, "[画板] 已邀请 " + target + " 加入「" + groupName + "」");
        ServerPlayer t = server.getPlayerList().getPlayerByName(target);
        if (t != null) say(t, "[画板] 你被邀请加入了组「" + groupName + "」，/tablegame drawboard 里可见其项目");
    }

    /** join：公开组直接加入；私有组提示需邀请。 */
    public void joinGroup(ServerPlayer player, String groupName) {
        String me = player.getName().getString();
        BoardStore.GroupInfo g = findGroup(groupName);
        if (g == null) {
            say(player, "[画板] 组不存在: " + groupName);
            return;
        }
        if (g.contains(me)) {
            say(player, "[画板] 你已在组「" + groupName + "」中");
            return;
        }
        if (!g.open()) {
            say(player, "[画板] 组「" + groupName + "」是私有的，需组长邀请（invite " + groupName + " " + me + "）");
            return;
        }
        g.members().add(me);
        saveGroups();
        say(player, "[画板] 已加入公开组「" + groupName + "」");
    }

    /** leave：普通成员退组（组长不能退，v1 未做解散/转让）。 */
    public void leaveGroup(ServerPlayer player, String groupName) {
        String me = player.getName().getString();
        BoardStore.GroupInfo g = findGroup(groupName);
        if (g == null) {
            say(player, "[画板] 组不存在: " + groupName);
            return;
        }
        if (g.owner().equals(me)) {
            say(player, "[画板] 你是组长，不能直接退组（v1 未做组解散/组长转让）");
            return;
        }
        if (!g.members().remove(me)) {
            say(player, "[画板] 你不在组「" + groupName + "」中");
            return;
        }
        g.readOnly().remove(me);
        saveGroups();
        say(player, "[画板] 已退出组「" + groupName + "」");
    }

    /** rule：组长切换 open（公开可 join）/ invite（私有需邀请）。 */
    public void setGroupOpen(ServerPlayer player, String groupName, boolean open) {
        BoardStore.GroupInfo g = requireOwner(player, groupName);
        if (g == null) return;
        if (g.open() == open) {
            say(player, "[画板] 组「" + groupName + "」已是" + (open ? "公开" : "私有"));
            return;
        }
        int i = groups.indexOf(g);
        groups.set(i, new BoardStore.GroupInfo(g.name(), g.owner(), g.members(), open, g.readOnly(), g.admins()));
        saveGroups();
        say(player, "[画板] 组「" + groupName + "」已改为" + (open ? "公开（任何人可 join）" : "私有（仅限邀请）"));
    }

    /** readonly：组长把某个成员设为/取消只读（组长本人永不只读）。 */
    public void setReadOnly(ServerPlayer player, String groupName, String target, boolean ro) {
        BoardStore.GroupInfo g = requireManage(player, groupName);
        if (g == null) return;
        if (!g.contains(target)) {
            say(player, "[画板] " + target + " 不在组「" + groupName + "」中");
            return;
        }
        if (g.owner().equals(target) || g.admins().contains(target)) {
            say(player, "[画板] 不能把组长或管理员设为只读（管理成员恒可写）");
            return;
        }
        boolean cur = g.readOnly().contains(target);
        if (cur == ro) {
            say(player, "[画板] " + target + " 已是" + (ro ? "只读" : "可写"));
            return;
        }
        if (ro) g.readOnly().add(target);
        else g.readOnly().remove(target);
        saveGroups();
        say(player, "[画板] " + target + " 在组「" + groupName + "」内已改为" + (ro ? "只读（只能看/导出）" : "可写"));
    }

    /** admin：组长任命/撤销管理员（flag=true 任命）。管理员可邀请/踢普通成员/设只读，恒可写；组长不在 admins 列表。 */
    public void setGroupAdmin(ServerPlayer player, String groupName, String target, boolean admin) {
        BoardStore.GroupInfo g = requireOwner(player, groupName);
        if (g == null) return;
        if (!g.contains(target)) {
            say(player, "[画板] " + target + " 不在组「" + groupName + "」中");
            return;
        }
        if (g.owner().equals(target)) {
            say(player, "[画板] 组长本身就是最高管理，无需设为管理员");
            return;
        }
        boolean cur = g.admins().contains(target);
        if (cur == admin) {
            say(player, "[画板] " + target + " 已是" + (admin ? "管理员" : "普通成员"));
            return;
        }
        if (admin) {
            g.admins().add(target);
            g.readOnly().remove(target);   // 管理员要能管理，必须可写
        } else {
            g.admins().remove(target);
        }
        saveGroups();
        say(player, "[画板] " + target + " 已被设为" + (admin ? "管理员（可邀请/踢普通成员/设只读）" : "普通成员"));
        ServerPlayer t = server.getPlayerList().getPlayerByName(target);
        if (t != null && admin) say(t, "[画板] 你被组长任命为组「" + groupName + "」的管理员");
    }

    /** kick：组长或管理员把成员移出组。组长不能被踢（用解散），管理员不能踢管理员；被踢者若在看该组项目则撤登记。 */
    public void kickMember(ServerPlayer player, String groupName, String target) {
        BoardStore.GroupInfo g = requireManage(player, groupName);
        if (g == null) return;
        String me = player.getName().getString();
        if (!g.contains(target)) {
            say(player, "[画板] " + target + " 不在组「" + groupName + "」中");
            return;
        }
        if (g.owner().equals(target)) {
            say(player, "[画板] 不能踢组长");
            return;
        }
        if (!g.owner().equals(me) && g.admins().contains(target)) {
            say(player, "[画板] 管理员不能踢管理员（组长可以）");
            return;
        }
        g.members().remove(target);
        g.admins().remove(target);
        g.readOnly().remove(target);
        saveGroups();
        say(player, "[画板] 已将 " + target + " 移出组「" + groupName + "」");
        ServerPlayer t = server.getPlayerList().getPlayerByName(target);
        if (t != null) {
            say(t, "[画板] 你已被移出组「" + groupName + "」");
            dropGroupViewing(t, groupName);   // 停掉他正在看的该组项目广播
        }
    }

    /** disband：组长解散组（删组记录）。安全阀：组内磁盘还有项目文件时禁止（须先经「打开项目文件夹」删除/转移）；通过后清缓存与观看登记并通知在线组员。 */
    public void disbandGroup(ServerPlayer player, String groupName) {
        BoardStore.GroupInfo g = requireOwner(player, groupName);
        if (g == null) return;
        // 安全阀：projects/<组>/ 下还有项目文件 → 拒绝
        boolean hasProjects = store.listProjects().stream()
                .anyMatch(e -> e.group().equals(groupName));
        if (hasProjects) {
            say(player, "[画板] 组「" + groupName + "」内还有画板项目。请先经主菜单「打开项目文件夹」"
                    + "删除或转移 projects/" + groupName + "/ 下的文件，再解散");
            return;
        }
        groups.remove(g);
        saveGroups();
        // 清内存残留（手动删文件后缓存可能还活着）与该组观看登记
        boards.keySet().removeIf(k -> k.startsWith(groupName + "/"));
        List<java.util.UUID> drop = new ArrayList<>();
        viewing.forEach((uuid, k) -> {
            if (k.startsWith(groupName + "/")) drop.add(uuid);
        });
        drop.forEach(viewing::remove);
        drop.forEach(uuid -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) say(p, "[画板] 组「" + groupName + "」已被组长解散");
        });
        say(player, "[画板] 组「" + groupName + "」已解散");
    }

    /** 该玩家正在看属于该组的项目时，撤销其观看登记（看别的组则不动）。 */
    private void dropGroupViewing(ServerPlayer p, String groupName) {
        if (viewing.getOrDefault(p.getUUID(), "").startsWith(groupName + "/")) {
            viewing.remove(p.getUUID());
        }
    }

    /** list：列出我的组（单人组 + 加入的真实组）与角色/规则/只读成员。 */
    public void listMyGroups(ServerPlayer player) {
        String me = player.getName().getString();
        say(player, "[画板] 我的组：");
        say(player, "  · " + me + " —— 我的单人组");
        boolean any = false;
        for (BoardStore.GroupInfo g : groups) {
            if (!g.contains(me)) continue;
            any = true;
            String role = g.owner().equals(me) ? "组长" : (g.admins().contains(me) ? "管理员" : "成员");
            StringBuilder sb = new StringBuilder("  · ").append(g.name()).append(" [").append(role).append("]")
                    .append(" 成员 ").append(g.members().size()).append(" · ").append(g.open() ? "公开" : "私有");
            if (!g.readOnly().isEmpty()) sb.append(" · 只读: ").append(String.join(", ", g.readOnly()));
            say(player, sb.toString());
        }
        if (!any) say(player, "  （还没加入任何真实组——/tablegame group create <组名> 建一个）");
    }

    /** 服务器停止前把所有脏项目写盘（局内临时板不落盘，跳过）。 */
    public void saveAll() {
        for (Board b : boards.values()) {
            if (b.dirty && !isStage(b)) store.save(b);
        }
    }
}
