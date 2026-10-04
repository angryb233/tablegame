package com.tablegame.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tablegame.TableGame;
import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.tablegame.area.AreaCaptureClient;
import com.tablegame.area.AreaGhostRenderer;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GameStore;
import com.tablegame.drawboard.StageBoardCache;
import com.tablegame.editor.EditorMenuScreen;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.pack.AssetLibraryScreen;
import com.tablegame.editor.pack.AssetPickerScreen;
import com.tablegame.net.AreaGhostPackets;
import com.tablegame.net.AssetPackets;
import com.tablegame.net.DealerPackets;
import com.tablegame.net.GamePackets;
import com.tablegame.net.HostPackets;
import com.tablegame.stage.StageScreen;
import com.tablegame.table.DealerScreen;
import com.tablegame.table.GamesScreen;

/**
 * 游戏功能的客户端收包处理（只在物理客户端注册/加载），模式同 ClientBoardHandler。
 *
 * <ul>
 *   <li>GamesListPayload  → 列表屏（就地刷新/新开）
 *   <li>GameDataPayload   → 玩法编辑器（数据已到就地刷新）
 *   <li>FacePixelsPayload → 卡面像素缓存
 *   <li>BlueprintListPayload → 候选蓝图 key 缓存（棋子编辑页 pull 读取）
 *   <li>StagePayload / HostStatePayload → 舞台与对局快照（HUD 每帧 pull）
 *   <li>StageUiPayload    → 全屏舞台界面的开关
 * </ul>
 *
 * <p>缓存一律 pull：包到达后控件每帧自查，不做「包到→通知控件→重建」的 push 链。
 * {@link #FACES} 按 art 键（"组/项目名"）缓存像素+尺寸，{@link #PENDING} 防重复发包。
 */
public final class ClientGameHandler {
    private ClientGameHandler() {}

    /** 缓存条目：像素（ARGB 行优先）+ 源宽高。px 为空数组 = 服务端说找不到（永久占位）。 */
    public record Face(int[] px, int w, int h) {}

    /** art 键 → 卡面缓存。 */
    private static final Map<String, Face> FACES = new HashMap<>();
    /** 已发出请求还没回包的 art 键（防逐帧刷包）。 */
    private static final Set<String> PENDING = new HashSet<>();

    /** 候选蓝图 key 列表（"组/蓝图名"）；null = 还没请求过，空表 = 服务端说我没有任何蓝图。 */
    private static List<String> blueprintList = null;
    private static boolean blueprintPending = false;

    /** 最近一次收到的对局状态快照（HUD 读它）。 */
    private static HostPackets.HostStatePayload hostState;

    private static void onHostState(HostPackets.HostStatePayload p, IPayloadContext ctx) {
        hostState = p;
        if (!p.inGame()) {
            com.tablegame.drawboard.StageBoardCache.clear();   // 局结束：本地临时画板也别忘了清
            AreaGhostRenderer.hide();                          // 幽灵预览同理，不残留
            if (Minecraft.getInstance().screen instanceof StageScreen) Minecraft.getInstance().setScreen(null);
        }
        TableGame.LOGGER.debug("[对局] 收到状态快照 {} · 阶段 {} · 在局 {}", p.gameName(), p.nodeTitle(), p.inGame());
    }

    /** 对局状态快照（null = 还没收到过 / 不在局里）。 */
    public static HostPackets.HostStatePayload hostState() {
        return hostState;
    }

    // ===== 舞台（D 步 / 第1步）：开局收一次定义，HUD 与全屏界面每帧读它 =====

    /** 最近一次收到的舞台包（inGame=false 表示对局结束，界面收起）。 */
    private static HostPackets.StagePayload stage;
    /** 解析好的<b>整份</b>定义（舞台 + 动作表；每帧都要读，不能每帧重解析 JSON）。 */
    private static GameDefinition stageGame;
    /** stageGame 对应哪个游戏（游戏换了才重解析）。 */
    private static String stageFor;

    private static void onStage(HostPackets.StagePayload p, IPayloadContext ctx) {
        stage = p;
        stageGame = null;
        stageFor = null;
        Minecraft mc = Minecraft.getInstance();
        TableGame.LOGGER.debug("[对局] 收到舞台 {} · 在局 {}", p.gameName(), p.inGame());
        if (!p.inGame()) {          // 结束：清掉界面
            AreaGhostRenderer.hide();               // 幽灵预览：局没了就别留在屏上
            if (mc.screen instanceof StageScreen) mc.setScreen(null);
            lastHideSeq = 0;        // 跨局复位（新一局的收起计数从 0 重新开始）
            lastFull = null;
            return;
        }
        // 屏只能靠 show 唤出：脚本 `show("名")` 后宿主随快照下发「当前全屏块」，
        //   客户端第一次看见它（或它换了块）就开屏；开局 / 进存档不再自动弹。
        //   玩家手里开着别的屏（编辑器 / 聊天 / 游戏台）就不抢屏；
        //   游戏台上点【进入游戏界面】走手动路（服务端发 StageUiPayload(true)）。
        //   hide() = 收起（计数变了就关屏，内容不清），同时清掉 lastFull ⇒ 之后同名 show 还能再弹出。
        GameDefinition.StageDef sd = stageDef();
        if (sd == null) return;
        if (sd.hideSeq() > lastHideSeq) {
            lastHideSeq = sd.hideSeq();
            // hide("名") 点名收：那块是全屏时才关屏；收的是 HUD/别的 → 全屏不动。
            // hide() 全收 = 关屏 + 清 lastFull。
            String hidden = sd.hiddenName();
            boolean hitFull = hidden == null || hidden.equals(lastFull);
            if (hitFull) {
                lastFull = null;
                if (mc.screen instanceof StageScreen) mc.setScreen(null);
            }
            // ⚠ 不许 return：脚本「先 hide(谁,名) 再 show(谁,名)」= 一次重新唤出，
            //   同一帧里 hideSeq 变了、cur 还是那块；return 会把这次 show 吞掉 ⇒ 屏再也唤不出来。
            //   hide 已清掉 lastFull ⇒ 往下走 cur != lastFull ⇒ 当场重开。
        }
        String cur = sd.currentFull();
        boolean switched = cur != null && !cur.equals(lastFull);
        lastFull = cur;
        // 换块（shop ⇄ bank）= 重建 StageScreen：输入框是 init() 按当前那块建的，
        // 靠动作变化重建会让旧块的输入框留在新块上。
        if (switched && (mc.screen == null || mc.screen instanceof StageScreen)) mc.setScreen(new StageScreen());
    }

    /**
     * 玩家自己把全屏舞台关掉了（Esc，见 {@link StageScreen#onClose()}）—— 把「上一次的当前全屏块」忘掉，
     * 这样脚本再喊一次同一个 {@code show("名")} 还能把它唤出来。
     */
    public static void stageClosedByPlayer() {
        lastFull = null;
    }

    /**
     * 当前对局的整份定义（不在局里 / 解析失败 = null）。
     *
     * <p>解析走服务端同一套 {@code GameStore.fromJsonWire}（免得两端认的字段分叉），结果缓存，
     * 只在游戏换了之后重解一次。留整份而非只留 stage.ui：全屏输入行要显示「哪个动作、哪个参数」，
     * 动作定义在同一份 JSON 里。
     */
    public static GameDefinition stageGame() {
        if (stage == null || !stage.inGame()) return null;
        if (stageGame == null || !stage.gameName().equals(stageFor)) {
            try {
                var root = com.google.gson.JsonParser.parseString(stage.json()).getAsJsonObject();
                stageGame = GameStore.fromJsonWire(stage.gameName(), root);
            } catch (Exception e) {
                TableGame.LOGGER.error("[对局] 舞台解析失败: {}", e.toString());
                stageGame = null;
            }
            stageFor = stage.gameName();
        }
        return stageGame;
    }

    /** 已处理过的 {@code hide()} 收起计数（跨局复位）—— 比它大才关屏，避免重复关。 */
    private static int lastHideSeq;

    /** 上一次见过的「当前全屏块」；它变了才算「脚本又喊了一次 show」= 该弹屏。 */
    private static String lastFull;

    public static GameDefinition.StageView boundView(boolean hudSide) {
        GameDefinition d = stageGame();
        if (d == null || d.stage() == null || d.stage().views().isEmpty()) return null;
        // 按承载挑第一块：哪块承载这份快照由「HUD 还是全屏」决定。
        // 全屏侧：没 show 过 = 不显示；看板侧：没 show 过 = 回落第一块（看板常驻）。
        String cur = hudSide ? d.stage().currentHud() : d.stage().currentFull();
        if (cur != null) {
            for (GameDefinition.StageView v : d.stage().views()) {
                if (v.id().equals(cur) || v.name().equals(cur)) return v;
            }
            return null;                     // 名字对不上（脚本刚改过）→ 当没显示，别错杀到第一块
        }
        if (!hudSide) return null;           // 全屏：没指定就不显示
        for (GameDefinition.StageView v : d.stage().views()) if (v.hud()) return v;
        return null;
    }

    public static GameDefinition.StageDef stageDef() {
        GameDefinition g = stageGame();
        return g == null ? null : g.stage();
    }

    /**
     * 全屏舞台界面的开关（第1步）：{@code /tablegame ui} 开、对局结束由 {@link #onStage} 收。
     * 形态对不对不在这里判（那已由服务端决定要不要发），这里只负责开/关一个屏。
     */
    private static void onStageUi(HostPackets.StageUiPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (!p.open()) {
            if (mc.screen instanceof StageScreen) mc.setScreen(null);
            return;
        }
        if (stageDef() == null) {
            DrawBoardMenuUi.msg("[对局] 你不在对局里");
            return;
        }
        mc.setScreen(new StageScreen());
    }

    /**
     * 幽灵预览：按「哪条区域 + 落点」给这颗玩家烘一份预览；{@code on=false} = 收回。
     *
     * <p>包里没有快照数据 —— 从这一局的整份定义里查（{@link #stageGame()}，区域在 {@code areas} 段）。
     * 查不到（不在局里 / 游戏对不上 / 没这条区域 / 还没捕获）→ 不画 + 记日志。
     */
    private static void onGhost(AreaGhostPackets.GhostPayload p, IPayloadContext ctx) {
        if (!p.on()) {
            AreaGhostRenderer.hide();
            return;
        }
        GameDefinition d = stageGame();
        if (d == null || !p.game().equals(d.name())) {
            TableGame.LOGGER.warn("[幽灵] 手上没有「{}」这一局的定义（不在局里？）—— 预览不发", p.game());
            return;
        }
        GameDefinition.AreaDef area = GameDefinition.areaOf(d.areas(), p.area());
        if (area == null || !area.captured()) {
            TableGame.LOGGER.warn("[幽灵] 这份定义里没有（或还没捕获）区域「{}」", p.area());
            return;
        }
        AreaGhostRenderer.show(area, p.x(), p.y(), p.z());
    }

    /**
     * 「引用显示」框当前该显示的文字 —— 按槽位名从快照里取（不在局里 / 没这个槽位 = 空串）。
     *
     * <p>取值交给 {@link GameDefinition#snapValue}（行格式「名字: 值」只有那一处定义）。
     */
    public static String varValue(String id) {
        HostPackets.HostStatePayload st = hostState;
        if (st == null || !st.inGame()) return "";
        return GameDefinition.snapValue(st.varValues(), id);
    }

    /** 最近一次收到的游戏列表（编辑器菜单点「游戏列表」时先用它垫一屏，最新包到了就地刷新）。 */
    private static List<GamePackets.GameInfoPayload> gamesList = List.of();

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(GamePackets.GamesListPayload.TYPE, ClientGameHandler::onGamesList);
        event.register(GamePackets.GameDataPayload.TYPE, ClientGameHandler::onGameData);
        event.register(GamePackets.FacePixelsPayload.TYPE, ClientGameHandler::onFacePixels);
        event.register(GamePackets.BlueprintListPayload.TYPE, ClientGameHandler::onBlueprintList);
        event.register(HostPackets.HostStatePayload.TYPE, ClientGameHandler::onHostState);
        event.register(HostPackets.StagePayload.TYPE, ClientGameHandler::onStage);
        event.register(HostPackets.StageUiPayload.TYPE, ClientGameHandler::onStageUi);
        event.register(AreaGhostPackets.GhostPayload.TYPE, ClientGameHandler::onGhost);   // 幽灵预览
        event.register(DealerPackets.StatePayload.TYPE, ClientGameHandler::onDealerState);
        event.register(AssetPackets.PackListPayload.TYPE, ClientGameHandler::onPackList);
        event.register(AssetPackets.PackContentPayload.TYPE, ClientGameHandler::onPackContent);
        event.register(AssetPackets.AssetPixelsPayload.TYPE, ClientGameHandler::onAssetPixels);
        event.register(AssetPackets.BoardsPayload.TYPE, ClientGameHandler::onBoards);
        event.register(AssetPackets.BlueprintsPayload.TYPE, ClientGameHandler::onBlueprints);
        event.register(AssetPackets.ArtFilesPayload.TYPE, ClientGameHandler::onArtFiles);
        event.register(AssetPackets.BlueprintDataPayload.TYPE, ClientGameHandler::onBlueprintData);
    }

    // ===== 组件库（#21）—— 一律按「此刻屏上是什么」分派（#13 的教训：不做静态登记）=====

    private static void onPackList(AssetPackets.PackListPayload p, IPayloadContext ctx) {
        // 库列表两个屏都用：组件库屏（跨游戏管理）与编辑器「组件」页（本项目选用）
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyPacks(p.packs());
        } else if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyPacks(p.packs());
        }
    }

    private static void onPackContent(AssetPackets.PackContentPayload p, IPayloadContext ctx) {
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyContent(p.pack(), p.desc(), p.assets());
        }
    }

    private static void onAssetPixels(AssetPackets.AssetPixelsPayload p, IPayloadContext ctx) {
        // 两个屏都在用缩略图（组件库屏 = 管理 · 编辑器「组件」页 = 选用）
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyPixels(p.pack(), p.id(), p.w(), p.h(), p.argb());
        } else if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyPixels(p.pack(), p.id(), p.w(), p.h(), p.argb());
        }
    }

    private static void onBoards(AssetPackets.BoardsPayload p, IPayloadContext ctx) {
        // 来源列表两个屏都要用（都支持「+ 新增」）
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyBoards(p.keys());
        } else if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyBoards(p.keys());
        }
    }

    /** art/ 里的外部图片清单；只有「组件」页在选来源时用得到。 */
    private static void onArtFiles(AssetPackets.ArtFilesPayload p, IPayloadContext ctx) {
        if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyArtFiles(p.files());
        }
    }

    private static void onBlueprints(AssetPackets.BlueprintsPayload p, IPayloadContext ctx) {
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyBoards(p.keys());            // 来源列表两用：画板（卡牌）/ 蓝图（模型）
        } else if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyBoards(p.keys());
        }
    }

    private static void onBlueprintData(AssetPackets.BlueprintDataPayload p, IPayloadContext ctx) {
        if (Minecraft.getInstance().screen instanceof AssetLibraryScreen s) {
            s.applyBlueprintData(p.pack(), p.id(), p.json());
        } else if (Minecraft.getInstance().screen instanceof AssetPickerScreen s) {
            s.applyBlueprintData(p.pack(), p.id(), p.json());
        }
    }

    /**
     * 台状态到达：开着同一个台的屏 → 就地刷新；否则开一块新的游戏台屏。
     * 「打开哪个屏」由这个包决定（台子屏没有容器，要显示什么都在状态里）。
     */
    private static void onDealerState(DealerPackets.StatePayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DealerScreen ds && ds.tablePos().equals(p.pos())) {
            ds.applyState(p);
            return;
        }
        if (mc.screen == null || mc.screen instanceof DealerScreen) {
            mc.setScreen(new DealerScreen(p));
        }
    }

    private static void onGamesList(GamePackets.GamesListPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        gamesList = new ArrayList<>(p.games());
        // 路由（/tablegame games 先到编辑器菜单）：
        //   列表屏 / 游戏台屏 → 就地刷新；屏为空（指令 / 编辑器退出）→ 开编辑器菜单；
        //   菜单屏与组件库开着 → 只更新缓存，不抢屏
        if (mc.screen instanceof GamesScreen gs) {
            gs.applyData(p.games());
        } else if (mc.screen instanceof DealerScreen ds) {
            ds.applyData(p.games());
        } else if (mc.screen == null) {
            mc.setScreen(new EditorMenuScreen());
        }
    }

    /** 游戏列表缓存（编辑器菜单用；空表 = 还没收到过）。 */
    public static List<GamePackets.GameInfoPayload> games() {
        return gamesList;
    }

    private static void onGameData(GamePackets.GameDataPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        // 世界区域捕获进行中（进捕获前那次静默保存的回执）：屏是关着的、人正在世界里框选，
        // 回执不该把编辑器弹回来（症状：点「选择区域」先闪一下总览）。确认捕获时客户端已把
        // active 置回 false ⇒ 那条「捕获完成」的回执照旧走 ③，带玩家回编辑器。
        if (AreaCaptureClient.active()) {
            return;
        }
        // 路由只看「此刻屏上是什么」，不查任何登记 —— 静态登记就是滞留状态。
        // ① 编辑器本体开着 → 就地刷新
        if (mc.screen instanceof GameEditorScreen es && es.gameName().equals(p.name())) {
            es.applyData(p.json());
            return;
        }
        // ② 任何「编辑器工具屏」开着（都 implements EditorToolScreen，交出背后的编辑器）→
        //    刷新它背后的编辑器 + 原地重建当前屏。不能走 ③：那会新开编辑器 = 正编着被弹回总览。
        if (mc.screen instanceof EditorToolScreen t && t.editor() != null
                && t.editor().gameName().equals(p.name())) {
            t.editor().applyData(p.json());
            mc.screen.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            return;
        }
        // ③ 都没开着（别处收到回执）→ 新开编辑器
        mc.setScreen(new GameEditorScreen(p.name(), p.json()));
    }

    private static void onFacePixels(GamePackets.FacePixelsPayload p, IPayloadContext ctx) {
        PENDING.remove(p.art());
        int n = p.width() * p.height();
        int[] px = new int[n];
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            px[i] = (p.argb()[o] & 0xFF) << 24 | (p.argb()[o + 1] & 0xFF) << 16
                    | (p.argb()[o + 2] & 0xFF) << 8 | (p.argb()[o + 3] & 0xFF);
        }
        FACES.put(p.art(), new Face(px, p.width(), p.height()));
        TableGame.LOGGER.debug("[游戏] 收到卡面 {} ({}x{})", p.art(), p.width(), p.height());
    }

    private static void onBlueprintList(GamePackets.BlueprintListPayload p, IPayloadContext ctx) {
        blueprintList = new ArrayList<>(p.entries());
        blueprintPending = false;
        TableGame.LOGGER.debug("[游戏] 收到候选蓝图列表 {} 条", p.entries().size());
    }

    /** 卡面控件用：未缓存则发一次请求（防重）。找不到时缓存空数组，不再重复请求。 */
    public static void requestFace(String art) {
        if (art == null || art.isBlank()) return;
        if (FACES.containsKey(art) || !PENDING.add(art)) return;
        ClientPacketDistributor.sendToServer(new GamePackets.RequestFacePayload(art));
    }

    /** 某 art 的缓存；null = 还没回包（控件画卡背）。 */
    public static Face face(String art) {
        return art == null ? null : FACES.get(art);
    }

    /**
     * 棋子编辑页用：请求候选蓝图列表。
     *
     * <p>每次进棋子页都重拉（不能短路成「本会话只拉一次」，否则新存的蓝图永远进不了候选）。
     * 防抖靠 {@code blueprintPending}：同一帧里重复调用只发一次请求。
     */
    public static void requestBlueprints() {
        if (blueprintPending) return;
        blueprintPending = true;
        ClientPacketDistributor.sendToServer(new GamePackets.RequestBlueprintsPayload());
    }

    /** 候选蓝图列表；null = 还没回包（编辑页显示「加载中」）。 */
    public static List<String> blueprints() {
        return blueprintList;
    }
}
