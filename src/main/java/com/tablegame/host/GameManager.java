package com.tablegame.host;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.TableGame;
import com.tablegame.drawboard.Board;
import com.tablegame.drawboard.BoardManager;
import com.tablegame.drawboard.BoardStore;
import com.tablegame.piece.PieceLibrary;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.tablegame.area.AreaCaptureServer;
import com.tablegame.area.AreaCapturer;
import com.tablegame.area.AreaPlacer;
import com.tablegame.area.AreaPlanner;
import com.tablegame.area.AreaToolKit;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GamePack;
import com.tablegame.core.GameStore;
import com.tablegame.editor.pack.AssetStore;
import com.tablegame.net.AssetPackets;
import com.tablegame.net.GamePackets;
import com.tablegame.script.Interp;
import com.tablegame.script.edit.ScriptEdit;

/** 游戏定义的服务端总管。照搬 BoardManager：服务端启动时初始化，读写都在主线程，指令/网络包只搬参数。
 * 无内存缓存：读定义 = 直接读文件（低频操作）。 */
public class GameManager {
    private static GameManager INSTANCE;

    private final MinecraftServer server;
    private final GameStore store;
    private final AssetStore assets;   // 组件库存储

    private GameManager(MinecraftServer server) {
        this.server = server;
        this.store = new GameStore(server.getServerDirectory());
        this.assets = new AssetStore(server.getServerDirectory());
    }

    public static void init(MinecraftServer server) {
        INSTANCE = new GameManager(server);
        TableGame.LOGGER.info("[游戏] 已初始化，定义目录: {}", INSTANCE.store.root());
    }

    public static void shutdown() {
        INSTANCE = null;   // 无缓存，无需落盘
    }

    public static GameManager get() {
        return INSTANCE;
    }

    /** 取一份定义（运行时主持人 / 列表 / 详情同一条读路径）；找不到或损坏回 null（调用方自行提示）。 */
    public GameDefinition load(String name) {
        return store.load(name);
    }

    /** 全部项目名（字母序）——配方同步用（逐个读 {@code recipe} 段）。 */
    public java.util.List<String> names() {
        return store.listNames();
    }

    // ===== 列表 / 详情 =====

    /** 全量游戏列表（每项带卡/牌组数量供列表行展示）。 */
    public void sendGamesList(ServerPlayer player) {
        List<GamePackets.GameInfoPayload> games = new ArrayList<>();
        for (String name : store.listNames()) {
            GameDefinition def = store.load(name);
            if (def != null) {
                games.add(new GamePackets.GameInfoPayload(name, def.cards().size(), def.decks().size(),
                        def.desc() == null ? "" : def.desc()));
            }
        }
        PacketDistributor.sendToPlayer(player, new GamePackets.GamesListPayload(games));
    }

    /** 打开编辑器：读定义 → 发 JSON 全文（客户端解析后进编辑屏）。 */
    public void openGame(ServerPlayer player, String name) {
        GameDefinition def = store.load(name);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[游戏] 定义不存在或已损坏: " + name));
            return;
        }
        PacketDistributor.sendToPlayer(player, new GamePackets.GameDataPayload(name, GameStore.toJson(def).toString()));
    }

    // ===== 新建 / 删除 / 保存 =====

    /** 新建游戏（生成最小示例卡组，随后进编辑器改）。成功后回列表 + 编辑器。 */
    public void createGame(ServerPlayer player, String name) {
        name = name.trim();
        if (name.isEmpty()) {
            player.sendSystemMessage(Component.literal("[游戏] 请先填游戏名"));
            return;
        }
        if (name.length() > com.tablegame.drawboard.BoardManager.MAX_NAME) {
            player.sendSystemMessage(Component.literal("[游戏] 游戏名过长（≤40 字符）"));
            return;
        }
        if (store.exists(name)) {
            player.sendSystemMessage(Component.literal("[游戏] 已存在同名游戏: " + name));
            return;
        }
        store.save(sampleDef(name));
        player.sendSystemMessage(Component.literal("[游戏] 已创建: " + name));
        sendGamesList(player);   // 列表屏开着就自动刷新
        openGame(player, name);  // 创建成功直接进编辑器
    }

    /** 删除定义文件（客户端已两次点击确认）。删完回列表刷新。 */
    public void deleteGame(ServerPlayer player, String name) {
        if (!store.exists(name)) {
            player.sendSystemMessage(Component.literal("[游戏] 定义不存在: " + name));
            return;
        }
        if (store.delete(name)) {
            player.sendSystemMessage(Component.literal("[游戏] 已删除: " + name));
        }
        sendGamesList(player);
    }

    // ===== 世界区域捕获 =====

    /** 进入捕获模式：服务端立账；关屏回世界由客户端发请求前自己做。 */
    public boolean startAreaCapture(ServerPlayer player, String game, String area) {
        GameDefinition def = store.load(game);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[世界] 游戏不存在: " + game));
            return false;
        }
        boolean found = def.areas().stream().anyMatch(a -> a.id().equals(area));
        if (!found) {
            player.sendSystemMessage(Component.literal("[世界] 区域不存在: " + area));
            return false;
        }
        AreaCaptureServer.begin(player, game, area);
        // 关屏由客户端发请求前自己 setScreen(null)，这里不用再发。
        return true;                                  // true = 真进了模式（请求方据此发那枚区域工具）
    }

    // ===== 区域工具（拿着才在模式里的那枚物品）=====

    /** 发一枚区域工具（标记带模式 / 游戏 / 区域；物品名写「选取：钻石大陆 / r1」一眼看得出用途）。
     * 先收旧的再发，保证手里只有一枚；背包满 → 掉脚下（不静默吞）。 */
    public static void giveAreaTool(ServerPlayer p, String mode, String game, String area, String cover) {
        takeAreaTool(p);
        var st = new net.minecraft.world.item.ItemStack(com.tablegame.TableGame.AREA_TOOL.get());
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(AreaToolKit.TAG, AreaToolKit.marker(mode, game, area, cover));
        st.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        st.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(AreaToolKit.label(mode, game, area)));
        p.getInventory().add(st);
        if (!st.isEmpty()) p.drop(st, false);
        p.inventoryMenu.broadcastChanges();           // 背包改完必须推客户端
    }

    /** 把那枚区域工具从背包里收走（完成 / 取消都调；本来就没有 = 什么都不做）。 */
    public static void takeAreaTool(ServerPlayer p) {
        var inv = p.getInventory();
        boolean any = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == com.tablegame.TableGame.AREA_TOOL.get()) {
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                any = true;
            }
        }
        if (any) p.inventoryMenu.broadcastChanges();
    }

    /** 确认捕获：两角齐 → 抓快照 → 写进 areas 段 → 落盘 → 回发 GameData 刷新编辑器。 */
    public void confirmAreaCapture(ServerPlayer player, String game, String area) {
        AreaCaptureServer.Session s = AreaCaptureServer.sessionOf(player);
        if (s == null || !s.game().equals(game) || !s.area().equals(area)) {
            player.sendSystemMessage(Component.literal("[世界] 不在捕获模式或会话不匹配"));
            return;
        }
        if (s.sel() == null || !s.sel().hasTwo()) {
            player.sendSystemMessage(Component.literal("[世界] 还没定好两个角"));
            return;
        }
        var box = net.minecraft.world.level.levelgen.structure.BoundingBox
                .fromCorners(s.sel().corner1(), s.sel().corner2());
        // 分相收集：相之间回主线程，大盒不再卡死；小块一相跑完。
        // 收完才写档 / 回执 / 收工具，捕获会话留到那一刻 ⇒ 分相期间状态条一直亮着。
        boolean queued = AreaCapturer.begin(player, player.level(), area, box, packed -> {
            // ── 收完（服务端 tick 里回调）：写档 → 对齐声明 → 回执 → 收工具 ──
            // 写进档：areas 段里找到这个 id 换掉，没有就追加
            GameDefinition def = store.load(game);
            if (def == null) {
                player.sendSystemMessage(Component.literal("[世界] 游戏不存在: " + game));
                AreaCaptureServer.end(player.getUUID());   // 收完才清会话（分相期间状态条还亮着）
                return;
            }
            def = writeSnapshot(def, packed);
            // 框选确认 = 一次性把声明也对齐：框出的范围写进 `area <名>` 的 box，art 指上这份内容 ——
            // 盒与内容同源，作者不用手抄坐标。
            // ⚠ 不能先 addDecl 再补字段（空的 `area r1 { }` 解析不过，checked 会拒收）。
            String script = def.script() == null ? "" : def.script();
            String boxCode = "[" + box.minX() + ", " + box.minY() + ", " + box.minZ() + ", "
                    + box.maxX() + ", " + box.maxY() + ", " + box.maxZ() + "]";
            // 生成多行：多行在脚本页里好读好改
            String declCode = "area " + area + " {\n  box " + boxCode + "\n  art \"" + area + "\"\n}";
            String fixed = script;
            if (com.tablegame.script.edit.ScriptEdit.declOf(script, "area", area) != null) {
                fixed = com.tablegame.script.edit.ScriptEdit.setDeclField(       // 已有块式声明：原地改 box 与 art
                        com.tablegame.script.edit.ScriptEdit.setDeclField(script, "area", area, "box", boxCode).text(),
                        "area", area, "art", "\"" + area + "\"").text();
            } else {
                // 没有块式声明。可能是平铺具名（`area r1 1 2 3 4 5 6`）——`declsOf` 只认块式，
                // 直接追加会留下同名两条（checked 拒收）。⇒ 先删平铺那条再补块式（名字不变，
                // `tp` / `in_area` 引用照样有效）；删不掉（还被引用）就退回不写并回一句说明。
                String t = script;
                boolean flat = com.tablegame.script.edit.ScriptEdit.regionsOf(script).stream()
                        .anyMatch(r -> r.name().equals(area));
                if (flat) t = com.tablegame.script.edit.ScriptEdit.removeArea(script, area).text();
                if (!flat || !t.equals(script)) {
                    fixed = com.tablegame.script.edit.ScriptEdit.appendTop(t, declCode,
                            "已按框选写区域声明 " + area).text();
                } else {
                    player.sendSystemMessage(Component.literal(
                            "[世界] 内容收到了，但声明没自动写：脚本里那条平铺区域还被 tp / in_area 引用 —— 先去改那几处"));
                }
            }
            if (!fixed.equals(script)) def = def.withScript(fixed);
            store.save(def);
            AreaCaptureServer.end(player.getUUID());
            takeAreaTool(player);                        // 选好了：把工具收走（失败早返回，工具留他手里继续选）
            // 不弹聊天栏（选取类提示走屏幕上方状态条）：完成的反馈 = 回执带回编辑器 +
            // 「世界」页签那行「（未捕获）」消失。真出错才弹聊天栏（上面几条）。
            // 回发规范化档（编辑器开着就就地刷新）
            PacketDistributor.sendToPlayer(player,
                    new GamePackets.GameDataPayload(game, GameStore.toJson(def).toString()));
        });
        if (!queued) {
            player.sendSystemMessage(Component.literal("[世界] 这一片收不了（尺寸非法）"));
        }
    }


    // 按盒抓快照走 AreaCapturer 的分相任务（规划在 AreaPlanner.capturePhases）。

    /** 维度注册名 → 世界（认不出 → {@code null}，不退回主世界；调用方拿到 null 自己记一行）。 */
    public static net.minecraft.server.level.ServerLevel levelByName(
            net.minecraft.server.MinecraftServer srv, String dim) {
        var id = net.minecraft.resources.Identifier.tryParse(dim == null ? "" : dim);
        var key = id == null ? null
                : net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, id);
        return key == null || srv == null ? null : srv.getLevel(key);
    }

    /** 那份档里叫这个名字的区域（**按声明解析**：世界 + 已排序的盒 + 内容引用；没有 → null）。 */
    private static com.tablegame.script.Interp.Region regionOf(GameDefinition def, String name) {
        String want = name == null ? "" : name;
        for (com.tablegame.script.Interp.Region r
                : com.tablegame.script.edit.ScriptEdit.regionsOf(def.script())) {
            if (r.name().equals(want)) return r;
        }
        return null;
    }

    /** 一条区域绑定的**快照名**（声明里的 art；空 = 用区域名当快照名）。 */
    private static String snapshotIdOf(com.tablegame.script.Interp.Region r) {
        return r.art().isEmpty() ? r.name() : r.art();
    }

    /** 一条快照写进档（同 id 换掉，没有就追加）——框选确认与按声明捕获共用。 */
    private static GameDefinition writeSnapshot(GameDefinition def, GameDefinition.AreaDef packed) {
        var list = new ArrayList<GameDefinition.AreaDef>();
        boolean replaced = false;
        for (var a : def.areas()) {
            if (a.id().equals(packed.id())) { list.add(packed); replaced = true; } else list.add(a);
        }
        if (!replaced) list.add(packed);
        return def.withAreas(list);
    }

    /**
     * 更新进编辑器：按区域声明的盒抓快照写进档。不框选、不问落点——盒来自脚本 {@code box [...]}，
     * 世界来自声明 {@code dimension}（不写用顶层 {@code dim}）；快照写进声明 {@code art} 指的那条。
     * 两道闸：权限（写档 = 游戏主管级）· 区域按声明找得到；尺寸不限，大盒分 tick 慢收。
     */
    public void captureRegion(ServerPlayer player, String game, String region) {
        if (!player.canUseGameMasterBlocks()) {
            player.sendSystemMessage(Component.literal("[区域] 按声明捕获要创造模式 + 游戏主管权限（OP）"));
            return;
        }
        GameDefinition def = store.load(game);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[区域] 找不到游戏档《" + game + "》"));
            return;
        }
        com.tablegame.script.Interp.Region r = regionOf(def, region);
        if (r == null) {
            player.sendSystemMessage(Component.literal("[区域] 脚本里没有区域 " + region));
            return;
        }
        String id = snapshotIdOf(r);
        if (id.isEmpty()) {
            player.sendSystemMessage(Component.literal("[区域] 这条区域没有名字（匿名），绑不了场景 —— 给它起个名字再试"));
            return;
        }
        var level = levelByName(server, r.dim());
        if (level == null) {
            player.sendSystemMessage(Component.literal("[区域] 认不出的世界：" + r.dim()));
            return;
        }
        int sx = (int) (r.maxX() - r.minX()) + 1, sy = (int) (r.maxY() - r.minY()) + 1,
                sz = (int) (r.maxZ() - r.minZ()) + 1;
        var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                (int) r.minX(), (int) r.minY(), (int) r.minZ(), (int) r.maxX(), (int) r.maxY(), (int) r.maxZ());
        // 分相收集：与框选确认同一条路，收完才写档 + 回执（尺寸不限）。
        boolean queued = AreaCapturer.begin(player, level, id, box, packed -> {
            GameDefinition d = store.load(game);
            if (d == null) {
                player.sendSystemMessage(Component.literal("[区域] 游戏档没了: " + game));
                return;
            }
            GameDefinition saved = writeSnapshot(d, packed);
            store.save(saved);
            PacketDistributor.sendToPlayer(player,
                    new GamePackets.GameDataPayload(game, GameStore.toJson(saved).toString()));
            player.sendSystemMessage(Component.literal("[区域] 已按声明把「" + id + "」收进档（"
                    + sx + "×" + sy + "×" + sz + "）"));
        });
        if (!queued) {
            player.sendSystemMessage(Component.literal("[区域] 这一片收不了（尺寸非法）"));
        }
    }

    /**
     * 应用到世界：把区域引用的快照按声明的盒盖章回世界（落点 = 区域最小角；盒与内容都在声明里）。
     * ⚠ 尺寸必须对得上：快照形状 ≠ 声明的盒 → 拒 + 提示先【更新进编辑器】对齐，否则会写到盒外。
     */
    public void placeRegion(ServerPlayer player, String game, String region, String mode) {
        GameDefinition def = store.load(game);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[区域] 找不到游戏档《" + game + "》"));
            return;
        }
        com.tablegame.script.Interp.Region r = regionOf(def, region);
        if (r == null) {
            player.sendSystemMessage(Component.literal("[区域] 脚本里没有区域 " + region));
            return;
        }
        String id = snapshotIdOf(r);
        GameDefinition.AreaDef snap = null;
        for (GameDefinition.AreaDef a : def.areas()) {
            if (a.id().equals(id)) { snap = a; break; }
        }
        if (snap == null || !snap.captured()) {
            player.sendSystemMessage(Component.literal("[区域] 档里还没有这份场景「" + id
                    + "」—— 先点【更新进编辑器】按声明的盒收一份进来"));
            return;
        }
        int sx = (int) (r.maxX() - r.minX()) + 1, sy = (int) (r.maxY() - r.minY()) + 1,
                sz = (int) (r.maxZ() - r.minZ()) + 1;
        if (snap.sizeX() != sx || snap.sizeY() != sy || snap.sizeZ() != sz) {
            player.sendSystemMessage(Component.literal("[区域] 场景「" + id + "」的尺寸与声明的盒不符（声明 "
                    + sx + "×" + sy + "×" + sz + "，场景 " + snap.sizeX() + "×" + snap.sizeY() + "×" + snap.sizeZ()
                    + "）—— 先【更新进编辑器】对齐"));
            return;
        }
        // 权限 / 覆盖策略 / 写世界：与「摆放（盖章）」同一个入口
        startAreaPlace(player, game, id, (int) r.minX(), (int) r.minY(), (int) r.minZ(), mode,
                levelByName(server, r.dim()));
    }

    /** 脚本行数（空脚本 = 0）——只给保存回执那一行字用。 */
    private static int scriptLines(GameDefinition def) {
        return def.script() == null || def.script().isBlank() ? 0 : def.script().split("\\n").length;
    }

    /**
     * 区域落地（盖章）：把一份区域快照写回世界。
     * 四道校验过了才交给 {@link AreaPlacer} 分批写：权限（{@code canUseGameMasterBlocks()}）·
     * 游戏档在 · 该 id 区域在 · {@code captured()}（真捕获过快照）。
     *
     * @param mode 覆盖策略：{@code all} / {@code non_air} / 别的都当 {@code none}（不动已有方块）
     */
    public void startAreaPlace(ServerPlayer player, String game, String areaId, int x, int y, int z,
            String mode) {
        startAreaPlace(player, game, areaId, x, y, z, mode, null);       // 落在他此刻那个世界
    }

    /** 同上，但**指定目标世界**（{@code level == null} = 他现在这个世界）——按区域落地用它。 */
    public void startAreaPlace(ServerPlayer player, String game, String areaId, int x, int y, int z,
            String mode, net.minecraft.server.level.ServerLevel level) {
        if (!player.canUseGameMasterBlocks()) {
            player.sendSystemMessage(Component.literal("[章] 落地区域要创造模式 + 游戏主管权限（OP）"));
            return;
        }
        AreaPlanner.Replace replace = switch (mode == null ? "" : mode) {
            case "all", "move" -> AreaPlanner.Replace.ALL;      // move：落地一律「全覆盖」（搬建筑不能只贴不改）
            case "non_air" -> AreaPlanner.Replace.NON_AIR;
            default -> AreaPlanner.Replace.NONE;
        };
        GameDefinition def = store.load(game);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[章] 找不到游戏档《" + game + "》"));
            return;
        }
        GameDefinition.AreaDef area = null;
        for (GameDefinition.AreaDef a : def.areas()) {
            if (a.id().equals(areaId)) { area = a; break; }
        }
        if (area == null) {
            player.sendSystemMessage(Component.literal("[章] 档里没有区域 " + areaId));
            return;
        }
        if (!area.captured()) {
            player.sendSystemMessage(Component.literal("[章] 区域 " + areaId + " 还没捕获过快照"));
            return;
        }
        net.minecraft.server.level.ServerLevel lv = level == null ? player.level() : level;
        net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(x, y, z);
        if ("move".equals(mode)) {
            // 「放置棒：移动」—— 源盒就是脚本声明里那条盒（服务端自己解析，客户端只说名字）
            com.tablegame.script.Interp.Region src = regionOf(def, areaId);
            if (src == null) {
                player.sendSystemMessage(Component.literal(
                        "[章] 移动要有个源：脚本里没有区域 " + areaId + " 的声明（先框选一次）"));
                return;
            }
            int sx = (int) (src.maxX() - src.minX()) + 1;
            int sy = (int) (src.maxY() - src.minY()) + 1;
            int sz = (int) (src.maxZ() - src.minZ()) + 1;
            if (!AreaPlacer.move(player, lv, game, area, at,
                    new net.minecraft.core.BlockPos((int) src.minX(), (int) src.minY(), (int) src.minZ()),
                    sx, sy, sz)) {
                player.sendSystemMessage(Component.literal("[章] 移动落不了：区域尺寸与逐格表形状对不上"));
            }
            return;
        }
        if (!AreaPlacer.begin(player, lv, game, area, at, replace)) {
            player.sendSystemMessage(Component.literal("[章] 落不了：区域尺寸与逐格表形状对不上"));
        }
    }

    public void saveGame(ServerPlayer player, String name, String json, boolean quiet) {
        name = name.trim();
        if (name.isEmpty() || name.length() > com.tablegame.drawboard.BoardManager.MAX_NAME) {
            player.sendSystemMessage(Component.literal("[游戏] 保存失败：游戏名不合法"));
            return;
        }
        if (!store.exists(name)) {
            player.sendSystemMessage(Component.literal("[游戏] 保存失败：游戏不存在（请回列表新建）: " + name));
            return;
        }
        GameDefinition def;
        try {
            def = GameStore.fromJson(name, JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("[游戏] 保存失败：定义解析错误 " + e.getMessage()));
            return;
        }
        // 唯一真源校验（段内自洽 + 脚本语法/引用）：信任边界在服务端——编辑器漏校验、伪造包都堵在这里。
        // 逻辑在 GameStore.validate（脚本走同一个解析器，错报「第 N 行」）。
        String err = GameStore.validate(def);
        if (err != null) {
            player.sendSystemMessage(Component.literal("[游戏] 保存失败：" + err));
            return;
        }
        store.save(def);
        // 配方：项目档落盘就把配方同步进存档的数据包（原版自己查数据包）——只在这一处挂，
        // 别的 store.save 调用点不重复触发。
        GamePack.sync(server);
        // 世界玩法：编辑通过（校验过 + 已落盘）→ 立刻在跑着的那一局上生效（换脚本 + 接回状态；
        // 接不上就什么都不换，见 HostManager.reloadGame）。只挂世界局，游戏台那套照旧。
        HostManager hm = HostManager.get();
        if (hm != null) {
            String hot = hm.reloadGame(name);
            if (!hot.isEmpty() && !quiet) player.sendSystemMessage(Component.literal(hot));
        }
        // quiet = 别回执：画布每动一下都存一次，回执会刷屏（出错消息照发）。
        if (!quiet) {
            player.sendSystemMessage(Component.literal("[游戏] 已保存: " + name
                    + "（卡 " + def.cards().size() + " · 棋子 " + def.pieces().size()
                    + " · 槽位 " + def.vars().size()
                    + " · 框 " + def.stage().ui().boxes().size() + " · 舞台 " + def.stage().views().size()
                    + " · 脚本 " + scriptLines(def) + " 行）"));
        }
        PacketDistributor.sendToPlayer(player, new GamePackets.GameDataPayload(name, GameStore.toJson(def).toString()));
    }

    // ===== 候选蓝图列表 =====

    /**
     * 候选蓝图列表：扫「我所在组」的蓝图库目录，回 "组/蓝图名" key 列表（只列组库，不含导出目录——
     * 导出目录无组归属，绑定蓝图必须有组；PieceLibrary 读路径是 组/名）。
     */
    public void sendBlueprintList(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new GamePackets.BlueprintListPayload(blueprintKeys(player)));
    }

    /** 候选蓝图 key（{@code 组/名}）—— 棋子编辑屏与组件库「模型」页共用同一份列法。 */
    private List<String> blueprintKeys(ServerPlayer player) {
        List<String> entries = new ArrayList<>();
        BoardManager bm = BoardManager.get();
        if (bm != null) {
            PieceLibrary lib = bm.pieceLibrary();
            for (PieceLibrary.Entry e : lib.listFor(bm.myGroupNames(player))) {
                // 跳过导出目录条目（source="导出"）：无组归属，客户端无法用它读回蓝图
                if (!"导出".equals(e.source())) entries.add(e.source() + "/" + e.name());
            }
        }
        return entries;
    }

    /** 组件库「模型」页的来源列表（蓝图，不是画板项目）。 */
    public void sendBlueprintKeys(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new AssetPackets.BlueprintsPayload(blueprintKeys(player)));
    }

    /** 把一条蓝图拷进库（模型美术资产）：拷的是蓝图文件原文（导入即拷贝，库里改/删不影响项目）；key = {@code 组/名}。 */
    public void packBlueprint(ServerPlayer player, String key, String pack, String id) {
        String k = key == null ? "" : key.trim();
        int slash = k.indexOf('/');
        BoardManager bm = BoardManager.get();
        if (bm == null || slash <= 0) {
            player.sendSystemMessage(Component.literal("[组件库] 蓝图 key 格式应为 组/名：" + k));
            return;
        }
        String name = k.substring(slash + 1);
        byte[] raw = bm.pieceLibrary().readRaw(k.substring(0, slash), name);
        if (raw == null) {
            player.sendSystemMessage(Component.literal("[组件库] 找不到蓝图：" + k));
            return;
        }
        String pk = (pack == null || pack.isBlank()) ? "默认" : pack.trim();
        String aid = (id == null || id.isBlank()) ? name : id.trim();
        String g = projGame(pack);
        if (g != null) {                                   // 蓝图原文**原样**进项目自己那份
            JsonObject bp;
            try {
                bp = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                player.sendSystemMessage(Component.literal("[组件库] 蓝图不是合法 JSON，没拷：" + k));
                return;
            }
            upsertProj(player, g, new GameDefinition.AssetDef(AssetStore.KIND_MODEL, aid, 0, 0, "", bp,
                    "", java.util.List.of()), true);
            return;
        }
        assets.writeBlueprint(pk, new AssetStore.Asset(aid, AssetStore.KIND_MODEL, aid, "", 0, 0,
                "", java.util.List.of()), raw);
        player.sendSystemMessage(Component.literal("[组件库] 已把蓝图「" + k + "」拷进包「" + pk + "」"));
        sendPackList(player);
        sendPackContent(player, pk);
    }

    /** 某条模型资产的蓝图原文 → 客户端（客户端造物品、用原版 GUI 物品渲染画缩略图）。 */
    public void sendBlueprintData(ServerPlayer player, String pack, String id) {
        String g = projGame(pack);
        if (g != null) {                                   // 项目自己那份：蓝图原文存在档里
            GameDefinition.AssetDef a = projAsset(store.load(g), id);
            String s = (a == null || a.bp() == null) ? "" : a.bp().toString();
            PacketDistributor.sendToPlayer(player, new AssetPackets.BlueprintDataPayload(pack, id, s));
            return;
        }
        byte[] raw = assets.readBlueprint(pack, id);
        String json = raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        PacketDistributor.sendToPlayer(player, new AssetPackets.BlueprintDataPayload(pack, id, json));
    }

    // ===== 卡面像素（服务端代读画板） =====

    /** 读画板项目像素发给客户端；art = "组/项目名"（画板项目 key）。
     * 找不到画板 → 发 w=h=0 空载荷（客户端画占位块）+ 提示，不静默。 */
    public void sendFacePixels(ServerPlayer player, String art) {
        // ① 「导入到本项目的组件」引用（@游戏名/资产名）：从游戏档里读像素（导入即拷贝 ⇒ 不碰库）
        String[] imp = GameDefinition.AssetDef.splitImportedKey(art);
        if (imp != null) {
            GameDefinition def = store.load(imp[0]);
            GameDefinition.AssetDef a = def == null ? null : GameDefinition.assetOf(def.assets(), imp[1]);
            if (a == null || a.px() == null || a.px().isEmpty()) {
                PacketDistributor.sendToPlayer(player, new GamePackets.FacePixelsPayload(art, 0, 0, new byte[0]));
                player.sendSystemMessage(Component.literal("[游戏] 这个游戏里没有导入组件：" + imp[1]));
                return;
            }
            AssetStore.Pixels px = AssetStore.decode(java.util.Base64.getDecoder().decode(a.px()));
            if (px == null) {
                PacketDistributor.sendToPlayer(player, new GamePackets.FacePixelsPayload(art, 0, 0, new byte[0]));
                player.sendSystemMessage(Component.literal("[游戏] 导入组件的像素坏了：" + imp[1]));
                return;
            }
            byte[] bytes = new byte[px.px().length * 4];
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            for (int argb : px.px()) {
                buf.put((byte) (argb >>> 24)).put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb);
            }
            PacketDistributor.sendToPlayer(player, new GamePackets.FacePixelsPayload(art, px.w(), px.h(), bytes));
            return;
        }
        // ② 画板项目引用（组/项目名）
        BoardManager bm = BoardManager.get();
        int slash = art.indexOf('/');
        Board board = (bm != null && slash > 0)
                ? bm.store().loadProject(art.substring(0, slash), art.substring(slash + 1))
                : null;
        if (board == null) {
            PacketDistributor.sendToPlayer(player, new GamePackets.FacePixelsPayload(art, 0, 0, new byte[0]));
            player.sendSystemMessage(Component.literal("[游戏] 找不到卡面画板: " + art));
            return;
        }
        byte[] bytes = new byte[board.pixels.length * 4];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int argb : board.pixels) {
            buf.put((byte) (argb >>> 24)).put((byte) (argb >>> 16))
               .put((byte) (argb >>> 8)).put((byte) argb);
        }
        PacketDistributor.sendToPlayer(player, new GamePackets.FacePixelsPayload(art, board.width, board.height, bytes));
    }

    // ===== 组件库 =====

    /** 组件库存储（服务端）。 */
    public AssetStore assets() {
        return assets;
    }

    /**
     * 虚拟库「@游戏名」= 游戏项目自己那份组件资源（存在游戏档 {@code assets} 段里）。
     * 客户端把 pack 写成 {@code @游戏名} 表示「项目自己那份」；读像素 / 读蓝图 / 改名 / 删除 /
     * 新增五条路都靠这个前缀分流（服务端剥掉 @）。
     *
     * @return 项目名；不是 {@code @} 开头（= 真库）返回 null
     */
    private static String projGame(String pack) {
        return (pack != null && pack.startsWith("@")) ? pack.substring(1) : null;
    }

    /** 项目里那一条（按显示名找）。 */
    private static GameDefinition.AssetDef projAsset(GameDefinition def, String name) {
        return def == null ? null : GameDefinition.assetOf(def.assets(), name);
    }

    /** 档改完了 → 把最新定义推回客户端（编辑器 / 「组件」页据此就地刷新）。 */
    private void pushDef(ServerPlayer player, String game) {
        GameDefinition def = store.load(game);
        if (def != null) {
            PacketDistributor.sendToPlayer(player, new GamePackets.GameDataPayload(game, GameStore.toJson(def).toString()));
        }
    }

    /** 往项目自己那份里放一条（同名覆盖）→ 存盘（+ 可选推回客户端）；单条导入 / 画板打包 / 蓝图打包三条路共用。 */
    private void upsertProj(ServerPlayer player, String game, GameDefinition.AssetDef a, boolean push) {
        GameDefinition def = store.load(game);
        if (def == null) {
            player.sendSystemMessage(Component.literal("[组件] 找不到游戏：" + game));
            return;
        }
        java.util.List<GameDefinition.AssetDef> out = new java.util.ArrayList<>();
        if (def.assets() != null) {
            for (GameDefinition.AssetDef x : def.assets()) {
                if (!x.name().equals(a.name())) {
                    out.add(x);                            // 同名 = 覆盖
                }
            }
        }
        out.add(a);
        store.save(def.withAssets(out));
        player.sendSystemMessage(Component.literal("[组件] 已加进「" + game + "」：" + a.name()
                + "（" + a.kind() + "）　卡面引用可写：@" + game + "/" + a.name()));
        if (push) {
            pushDef(player, game);
        }
    }

    /** 库里有哪些包 → 客户端。 */
    public void sendPackList(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new AssetPackets.PackListPayload(assets.listPacks()));
    }

    /** 某个包的资产清单 → 客户端（包不存在 = 空内容，屏上自己会说）。 */
    public void sendPackContent(ServerPlayer player, String pack) {
        AssetStore.Pack p = assets.readPack(pack);
        java.util.List<AssetPackets.AssetInfo> infos = new java.util.ArrayList<>();
        String desc = "";
        if (p != null) {
            desc = p.desc();
            for (AssetStore.Asset a : p.assets()) {
                infos.add(new AssetPackets.AssetInfo(a.id(), a.kind(), a.name(), a.color(), a.w(), a.h(),
                        a.base(), a.lore()));
            }
        }
        PacketDistributor.sendToPlayer(player, new AssetPackets.PackContentPayload(pack, desc, infos));
    }

    /** 新建（或确保存在）一个库 → 回列表。 */
    public void createPack(ServerPlayer player, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            player.sendSystemMessage(Component.literal("[组件库] 库名不能为空"));
            return;
        }
        assets.writePack(n, "");
        player.sendSystemMessage(Component.literal("[组件库] 已新建库「" + n + "」"));
        sendPackList(player);
        sendPackContent(player, n);
    }

    /** 删掉**整个库**（目录连里面的资产一起）—— 组件库屏库行右键「删除」。 */
    public void deletePack(ServerPlayer player, String pack) {
        AssetStore.Pack p = assets.readPack(pack);
        int n = p == null ? 0 : p.assets().size();
        if (assets.deletePack(pack)) {
            player.sendSystemMessage(Component.literal("[组件库] 已删除库「" + pack + "」（连里面 " + n + " 条资产）"));
        } else {
            player.sendSystemMessage(Component.literal("[组件库] 没有这个库：" + pack));
        }
        sendPackList(player);           // 屏上列表自动少一个
    }

    /** 给库改名（= 改目录名）—— 组件库屏库行右键「编辑」。 */
    public void renamePack(ServerPlayer player, String pack, String name) {
        String n = name == null ? "" : name.trim();
        if (assets.renamePack(pack, n)) {
            player.sendSystemMessage(Component.literal("[组件库] 「" + pack + "」已改名为「" + n + "」"));
            sendPackList(player);
            sendPackContent(player, n);
        } else {
            player.sendSystemMessage(Component.literal(
                    "[组件库] 改名没成（名字空 / 已被占用 / 库不存在）：" + pack + " → " + n));
        }
    }

    /** 删一条：库里那条（组件库屏，副本不受影响）或项目里那条（「组件」页，不动库）。 */
    public void deleteAsset(ServerPlayer player, String pack, String id) {
        String g = projGame(pack);
        if (g != null) {
            GameDefinition def = store.load(g);
            if (def == null || def.assets() == null) {
                player.sendSystemMessage(Component.literal("[组件] 找不到游戏：" + g));
                return;
            }
            java.util.List<GameDefinition.AssetDef> keep = new java.util.ArrayList<>();
            boolean hit = false;
            for (GameDefinition.AssetDef x : def.assets()) {
                if (x.name().equals(id)) {
                    hit = true;                            // 抽走这一条（其余原样带走）
                } else {
                    keep.add(x);
                }
            }
            if (!hit) {
                player.sendSystemMessage(Component.literal("[组件] 项目里没有这条：" + id));
                return;
            }
            store.save(def.withAssets(keep));
            player.sendSystemMessage(Component.literal("[组件] 已从「" + g + "」删掉：" + id));
            pushDef(player, g);
            return;
        }
        assets.deleteAsset(pack, id);
        player.sendSystemMessage(Component.literal("[组件库] 已删除 " + pack + "/" + id + "（已导入项目的副本不受影响）"));
        sendPackContent(player, pack);
    }

    /** 改显示名：库里那条（不动文件名）· 项目里那条（不动像素/蓝图）—— 靠 {@code @} 前缀分流。 */
    public void renameAsset(ServerPlayer player, String pack, String id, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            player.sendSystemMessage(Component.literal("[组件] 名字不能为空"));
            return;
        }
        String g = projGame(pack);
        if (g != null) {
            GameDefinition def = store.load(g);
            GameDefinition.AssetDef a = projAsset(def, id);
            if (def == null || a == null) {
                player.sendSystemMessage(Component.literal("[组件] 项目里没有这条：" + id));
                return;
            }
            java.util.List<GameDefinition.AssetDef> out = new java.util.ArrayList<>();
            for (GameDefinition.AssetDef x : def.assets()) {
                out.add(x == a ? new GameDefinition.AssetDef(x.kind(), n, x.w(), x.h(), x.px(), x.bp(),
                        x.base(), x.lore()) : x);
            }
            store.save(def.withAssets(out));
            player.sendSystemMessage(Component.literal("[组件] 已改名：" + id + " → " + n));
            pushDef(player, g);
            return;
        }
        assets.renameAsset(pack, id, n);
        sendPackContent(player, pack);
    }

    /**
     * 建一条「基底覆盖」资产：自定义物品 = 基底原版物品，自定义方块 = 基底原版方块。
     * 自定义 = 基底 + 名字/描述覆盖，外观用原版；{@code pack} 写真库名 = 进那个库，
     * 写 {@code @游戏名} = 进项目自己那份（{@link #projGame} 分流）。认不出的基底 id → 提示并拒收。
     */
    public void packItem(ServerPlayer player, String kind, String base, String pack, String name, java.util.List<String> lore) {
        String kd = (kind == null || kind.isBlank()) ? AssetStore.KIND_ITEM : kind;
        String bid = base == null ? "" : base.trim();
        var iid = net.minecraft.resources.Identifier.tryParse(bid);
        // ⚠ 一律用 containsKey 判存在：ITEM / BLOCK 都是 **DefaultedRegistry**，getValue(未知 id) 返回默认条目
        //   （air，非 null）⇒ 「getValue == null 才算认不出」永远不成立，这道校验等于没做。
        if (iid == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(iid)) {
            // 原版方块几乎都有对应物品，而方块 id 与它的 BlockItem id 相同 ⇒ 同一道判据把「拿不到手的方块」
            // （水/火/技术方块）也滤掉了 —— 与挑基底的清单（只列 BlockItem）同一个口径。
            player.sendSystemMessage(Component.literal("[组件库] 认不出的原版物品/方块 id：" + bid));
            return;
        }
        String nm = (name == null || name.isBlank()) ? iid.getPath() : name.trim();
        java.util.List<String> ls = lore == null ? java.util.List.of() : lore;
        String g = projGame(pack);
        if (g != null) {                                   // 进项目自己那份
            upsertProj(player, g, new GameDefinition.AssetDef(kd, nm, 0, 0, "", null, bid, ls), true);
            return;
        }
        String pk = (pack == null || pack.isBlank()) ? "默认" : pack.trim();
        assets.writeItem(pk, new AssetStore.Asset(nm, kd, nm, "", 0, 0, bid, ls));
        player.sendSystemMessage(Component.literal("[组件库] 已建" + kd + "「" + nm + "」（基底 " + bid + "）进包「" + pk + "」"));
        sendPackList(player);
        sendPackContent(player, pk);
    }

    /** 改一条物品资产的描述行（组件 {@code minecraft:lore}）—— 库里的 / 项目里的靠 {@code @} 前缀分流。 */
    public void setItemLore(ServerPlayer player, String pack, String id, java.util.List<String> lore) {
        java.util.List<String> ls = new java.util.ArrayList<>();
        if (lore != null) {
            for (String ln : lore) {
                if (ln != null && !ln.isBlank()) {
                    ls.add(ln);                            // 空行丢掉（不留空描述行）
                }
            }
        }
        String g = projGame(pack);
        if (g != null) {
            GameDefinition def = store.load(g);
            GameDefinition.AssetDef a = projAsset(def, id);
            if (def == null || a == null) {
                player.sendSystemMessage(Component.literal("[组件] 项目里没有这条：" + id));
                return;
            }
            java.util.List<GameDefinition.AssetDef> out = new java.util.ArrayList<>();
            for (GameDefinition.AssetDef x : def.assets()) {
                out.add(x == a ? new GameDefinition.AssetDef(x.kind(), x.name(), x.w(), x.h(), x.px(), x.bp(),
                        x.base(), ls) : x);
            }
            store.save(def.withAssets(out));
            player.sendSystemMessage(Component.literal("[组件] 已改描述：" + id + "（" + ls.size() + " 行）"));
            pushDef(player, g);
            return;
        }
        assets.setLore(pack, id, ls);
        player.sendSystemMessage(Component.literal("[组件库] 已改描述：" + id + "（" + ls.size() + " 行）"));
        sendPackContent(player, pack);
    }

    /** 可选的画板项目 key（{@code 组/名}）——「添加美术」的来源列表，只列我所在组的。 */
    public void sendBoards(ServerPlayer player) {
        BoardManager bm = BoardManager.get();
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (bm != null) {
            List<String> mine = bm.myGroupNames(player);
            for (BoardStore.ProjectEntry e : bm.store().listProjects()) {
                if (mine.contains(e.group())) {
                    keys.add(e.group() + "/" + e.name());
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new AssetPackets.BoardsPayload(keys));
    }

    /**
     * 把一个库的全部资产一次导入当前项目（同名覆盖）——「组件」页的【导入库】。
     * 导入 = 在项目下生成项目自己那份组件资源（导入即拷贝，与库脱钩）。
     */
    public void importPack(ServerPlayer player, String game, String pack) {
        AssetStore.Pack p = assets.readPack(pack);
        if (p == null) {
            player.sendSystemMessage(Component.literal("[组件] 库里没有这个库：" + pack));
            return;
        }
        int ok = 0;
        for (AssetStore.Asset a : p.assets()) {
            if (a.isItem() || assets.readAssetPixels(pack, a.id()) != null
                    || assets.readBlueprint(pack, a.id()) != null) {          // 物品类没有 px/bp，看 base
                if (importOne(player, game, pack, a.id(), false)) {   // 逐条走同一条路（含同名覆盖 + 回执）
                    ok++;
                }
            }
        }
        player.sendSystemMessage(Component.literal("[组件] 库「" + pack + "」导入完成：" + ok + " / " + p.assets().size() + " 条"));
        pushDef(player, game);          // 整包只推一次（逐条推会刷一堆全量定义）
        sendPackList(player);
    }

    /**
     * 单条导入的服务端半边：从库里读一条 → {@link #upsertProj} 进档（卡牌拷像素、模型拷蓝图；同名覆盖）。
     *
     * @param push 是否顺带把新定义推回客户端 —— 批量（{@link #importPack}）由调用方最后统一推一次
     * @return 是否真导入了（库里没这条 / 没数据 → false，且已经给过提示）
     */
    private boolean importOne(ServerPlayer player, String game, String pack, String id, boolean push) {
        AssetStore.Pack p = assets.readPack(pack);
        AssetStore.Asset a = null;
        if (p != null) {
            for (AssetStore.Asset x : p.assets()) {
                if (x.id().equals(id)) {
                    a = x;
                    break;
                }
            }
        }
        if (a == null) {
            player.sendSystemMessage(Component.literal("[组件] 库里没有这条资产：" + pack + "/" + id));
            return false;
        }
        AssetStore.Pixels px = assets.readAssetPixels(pack, id);
        byte[] bp = assets.readBlueprint(pack, id);
        // ⚠ 物品类既没像素也没蓝图，判定必须认 base，否则「导入库」把物品牌全拒掉
        if (px == null && bp == null && !a.isItem()) {
            player.sendSystemMessage(Component.literal("[组件] 这条资产没有数据（像素/蓝图/基底都缺）：" + id));
            return false;
        }
        String pxB64 = px == null ? "" : java.util.Base64.getEncoder().encodeToString(AssetStore.encode(px));
        JsonObject bpObj = null;
        if (bp != null) {
            try {
                bpObj = JsonParser.parseString(new String(bp, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                TableGame.LOGGER.error("[组件] 蓝图 JSON 解析失败 {}: {}", id, e.toString());
            }
        }
        upsertProj(player, game, new GameDefinition.AssetDef(a.kind(), a.name(), a.w(), a.h(), pxB64, bpObj,
                a.base(), a.lore()), push);
        return true;
    }

    /** 导入到当前项目：把库里一条资产拷进游戏档（导入即拷贝，导入后归当前游戏，库里再改不影响老游戏）。 */
    public void importAsset(ServerPlayer player, String game, String pack, String id) {
        if (importOne(player, game, pack, id, true)) {
            sendPackList(player);
        }
    }

    /** 某条资产的像素 → 客户端（没有 = 空像素，预览框不画）。 */
    public void sendAssetPixels(ServerPlayer player, String pack, String id) {
        AssetStore.Pixels px;
        String g = projGame(pack);
        if (g != null) {
            // 项目自己那份：像素以 base64 .px 存在档里 → 解回 Pixels
            GameDefinition.AssetDef a = projAsset(store.load(g), id);
            px = (a == null || a.px() == null || a.px().isEmpty())
                    ? null : AssetStore.decode(java.util.Base64.getDecoder().decode(a.px()));
        } else {
            px = assets.readAssetPixels(pack, id);
        }
        if (px == null) {
            PacketDistributor.sendToPlayer(player, new AssetPackets.AssetPixelsPayload(pack, id, 0, 0, new byte[0]));
            return;
        }
        byte[] bytes = new byte[px.px().length * 4];           // 每像素 4 字节 ARGB（与卡面同格式）
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
        for (int argb : px.px()) {
            buf.put((byte) (argb >>> 24)).put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb);
        }
        PacketDistributor.sendToPlayer(player, new AssetPackets.AssetPixelsPayload(pack, id, px.w(), px.h(), bytes));
    }

    /** 打包：把画板项目的像素收成组件库一条资产（拷贝不引用）；包不存在就建、同名覆盖，完事推回客户端。 */
    public void packFromBoard(ServerPlayer player, String boardKey, String pack, String name, String kind) {
        String key = boardKey == null ? "" : boardKey.trim();
        int slash = key.indexOf('/');
        BoardManager bm = BoardManager.get();
        Board board = (bm != null && slash > 0)
                ? bm.store().loadProject(key.substring(0, slash), key.substring(slash + 1))
                : null;
        if (board == null) {
            player.sendSystemMessage(Component.literal("[组件库] 找不到画板项目：" + key + "（格式：组/项目名）"));
            return;
        }
        String pk = (pack == null || pack.isBlank()) ? "默认" : pack.trim();
        String id = (name == null || name.isBlank()) ? key.substring(slash + 1) : name.trim();
        String kd = (kind == null || kind.isBlank()) ? AssetStore.KIND_ART : kind;
        String g = projGame(pack);
        if (g != null) {                                   // 进项目自己那份
            upsertProj(player, g, new GameDefinition.AssetDef(kd, id, board.width, board.height,
                    java.util.Base64.getEncoder().encodeToString(AssetStore.encode(
                            new AssetStore.Pixels(board.width, board.height, board.pixels))), null,
                    "", java.util.List.of()), true);
            return;
        }
        assets.writeAsset(pk, new AssetStore.Asset(id, kd, id, "", board.width, board.height,
                "", java.util.List.of()), board.pixels);
        player.sendSystemMessage(Component.literal("[组件库] 已打包 " + board.width + "×" + board.height
                + " 进包「" + pk + "」：资产「" + id + "」"));
        sendPackList(player);
        sendPackContent(player, pk);
    }

    // ===== 外部图片：扫 art/ 里的 png，服务端解码后收进本项目 =====

    /** 游戏的 {@code art/} 目录（外部图片与本项目的 .px 像素资产）。 */
    private java.nio.file.Path artDir(String game) {
        return store.dirOf(game).resolve("art");
    }

    /**
     * {@code art/*.png} 的文件名清单 → 客户端（按名排序；目录不存在 = 空表）。
     * 只收 {@code .png}：art/ 里还有本项目 .px 与手工放入未导入的东西，一并列出只会让人误点。
     */
    public void sendArtFiles(ServerPlayer player, String game) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.nio.file.Path dir = artDir(game);
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var s = java.nio.file.Files.list(dir)) {
                s.filter(java.nio.file.Files::isRegularFile)
                        .map(f -> f.getFileName().toString())
                        .filter(n -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                        .sorted()
                        .forEach(out::add);
            } catch (java.io.IOException e) {
                TableGame.LOGGER.error("[组件] 列 art 目录失败 {}: {}", game, e.toString());
            }
        }
        PacketDistributor.sendToPlayer(player, new AssetPackets.ArtFilesPayload(out));
    }

    /** 外部图片解码后的边长上限（防超大图撑爆档与载荷）。 */
    private static final int MAX_ART_SIDE = 1024;

    /**
     * 把一张外部图片收进本项目自己那份（导入即拷贝：解码成像素后归项目，与原 png 脱钩）。
     * 解码走 JDK {@code ImageIO}（不是客户端 blaze3d 的 {@code NativeImage}，专用服务器没有）；
     * 解不开 / 太大 / 空图 ⇒ 记一行 + 聊天栏说一句，不掐局。
     * 文件名先跟真实清单比对（白名单式），清单里没有就不动 —— 天然没有目录穿越的余地。
     */
    public void importArt(ServerPlayer player, String game, String file) {
        String f = file == null ? "" : file.trim();
        java.util.List<String> ok = new java.util.ArrayList<>();
        java.nio.file.Path dir = artDir(game);
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var s = java.nio.file.Files.list(dir)) {
                s.filter(java.nio.file.Files::isRegularFile).map(p -> p.getFileName().toString()).forEach(ok::add);
            } catch (java.io.IOException e) {
                TableGame.LOGGER.error("[组件] 列 art 目录失败 {}: {}", game, e.toString());
            }
        }
        if (f.isEmpty() || !ok.contains(f)) {
            player.sendSystemMessage(Component.literal("[组件] art/ 里没有这张图：" + f));
            return;
        }
        String base = f.substring(0, f.length() - 4);          // 去 .png
        int w;
        int h;
        int[] px;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(dir.resolve(f).toFile());
            if (img == null) {
                player.sendSystemMessage(Component.literal("[组件] 解不开这张图（不是 png？）：" + f));
                return;
            }
            w = img.getWidth();
            h = img.getHeight();
            if (w <= 0 || h <= 0 || w > MAX_ART_SIDE || h > MAX_ART_SIDE) {
                player.sendSystemMessage(Component.literal("[组件] 图太大或空的（上限 " + MAX_ART_SIDE + "×"
                        + MAX_ART_SIDE + "，这张 " + w + "×" + h + "）：" + f));
                return;
            }
            px = new int[w * h];
            img.getRGB(0, 0, w, h, px, 0, w);                  // ARGB，与 .px 同口径
        } catch (Throwable e) {                                 // 含 NoClassDefFoundError（极简 JRE 可能没 java.desktop）
            TableGame.LOGGER.error("[组件] 读图失败 {}: {}", f, e.toString());
            player.sendSystemMessage(Component.literal("[组件] 读这张图失败：" + f + "（服务端日志有一行）"));
            return;
        }
        upsertProj(player, game, new GameDefinition.AssetDef(AssetStore.KIND_CARD, base, w, h,
                java.util.Base64.getEncoder().encodeToString(AssetStore.encode(
                        new AssetStore.Pixels(w, h, px))), null, "", java.util.List.of()), true);
    }

    // ===== 示例定义（新建 = 最小可看卡组，随后编辑器改） =====

    /** 最小示例：2 张自定义卡（占位美术）+ 1 副标准扑克牌组。 */
    static GameDefinition sampleDef(String name) {
        JsonObject f1 = new JsonObject();
        f1.addProperty("point", 5);
        f1.addProperty("suit", "spades");
        JsonObject f2 = new JsonObject();
        f2.addProperty("point", 13);
        f2.addProperty("suit", "hearts");
        List<GameDefinition.CardDef> cards = List.of(
                new GameDefinition.CardDef("c1", "", "blue", f1, null),
                new GameDefinition.CardDef("c2", "", "blue", f2, null));
        List<GameDefinition.DeckDef> decks = List.of(
                new GameDefinition.DeckDef("main", List.of("c1", "c2"), true));
        // vars = 空表，stage = 空舞台（默认 320×180 画布、无框）。
        // script（schema/4）= 骨架：`on start { show("main") }` + 一块空主屏 —— 新建的档天生能存也能开局。
        // 骨架真源见 GameStore.NEW_GAME_SCRIPT（自检钉着它）。
        return new GameDefinition(name, "", cards, decks, List.of(),
                List.of(), GameDefinition.StageDef.empty(),
                GameStore.NEW_GAME_SCRIPT, List.of(), List.of(), new JsonObject());
    }
}
