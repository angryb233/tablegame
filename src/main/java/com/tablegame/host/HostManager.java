package com.tablegame.host;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tablegame.TableGame;
import com.tablegame.drawboard.BoardManager;
import com.tablegame.script.Ast;
import com.tablegame.script.Interp;
import com.tablegame.script.Parser;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import com.tablegame.card.CardEntity;
import com.tablegame.core.BagTake;
import com.tablegame.core.ChestFill;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GameLayout;
import com.tablegame.core.GameStore;
import com.tablegame.core.HostStore;
import com.tablegame.core.ProfileStore;
import com.tablegame.core.ScatterPick;
import com.tablegame.core.WorldGameStore;
import com.tablegame.editor.data.LootEdit;
import com.tablegame.editor.pack.AssetItem;
import com.tablegame.editor.pack.AssetStore;
import com.tablegame.net.AreaGhostPackets;
import com.tablegame.net.DealerPackets;
import com.tablegame.net.HostPackets;
import com.tablegame.piece.GamePieceEntity;
import com.tablegame.piece.PieceData;
import com.tablegame.piece.PieceLibrary;
import com.tablegame.script.Builtins;

/**
 * 对局的服务端总管 / MC 适配层：随服务器启动初始化，读写都在服务端主线程，指令与网络包只搬参数。
 * 四件事：开局（读定义 → 建 {@link Interp}）、发消息到聊天栏、状态变化时发快照（按接收者过滤变量）、
 * 把界面提交路由回所在局。流程推进 / 判定 / 数值全在 {@link Interp}（不依赖 MC）；规则真源是档里的
 * {@code script} 段。对局态落盘在 {@code <世界>/tablegame/tables.json}，中断 = 终止整局。
 */
public class HostManager {
    /** 席位收集半径（格）：站一桌就是玩家。 */
    private static final double SEAT_RADIUS = 16.0;

    /**
     * 本局生成的活物身上的标签前缀（{@code tg_mob_<脚本里的名字>}）—— 重进世界后内存账是空的，
     * 靠它在世界里把**同一只**认领回来（不重复生成，见 {@code spawnOne}）。
     */
    private static final String MOB_TAG = "tg_mob_";

    /** 局内临时画板的组名前缀（与画板总管的约定一致：以此开头的板不落盘、不进项目列表）。 */
    public static final String STAGE_GROUP = "@stage";
    /** 局内临时画板尺寸（定稿 32×32：1024 格，全量一次包就发完了）。 */
    public static final int STAGE_DIM = 32;

    private static HostManager INSTANCE;

    private final MinecraftServer server;
    /** 玩家档案（跨局持久）：脚本 profile_get / profile_set 的落盘处。 */
    private ProfileStore profiles;
    /** 同时只会有很少几局（一般 1~2），线性扫足够。 */
    private final List<Session> sessions = new ArrayList<>();
    /**
     * 对局落盘：台状态与在跑的局落 {@code <世界>/tablegame/tables.json}，节流 5 秒一次 + 停机前必写。
     * {@code ponytail:} 没做脏标记（引擎改变量不回调宿主，必漏 = 静默不落盘）；要省盘就加变更回调。
     */
    private HostStore store;
    /** 已经从盘上接回来过了吗（在第一个 tick 里做 —— 那时维度肯定加载好了，见 {@link #restore}）。 */
    private boolean restored;
    /** 上次落盘的毫秒（节流用）。 */
    private long lastSaveMs;

    /**
     * 世界玩法的存档级绑定：{@code <存档>/data/tablegame/world.json}。与对局的续跑账不同 ——
     * 那个住游戏目录（所有存档共用），这个必须随存档走。
     */
    private WorldGameStore worldStore;
    /** 这个存档绑的是哪款游戏（空串 = 没开世界玩法）。读盘时机 = 第一个 tick，见 {@link #tick}。 */
    private String worldGame = "";

    /** 一局对局的 MC 侧包装：本局的状态账 + 纯逻辑实例本人。（包层要看，所以不是 private） */
    static final class Session implements Interp.Members {
        final String gameName;
        /** 脚本解释器（第五期）：事件入口 / 挂起 / 步数预算都在它里面，纯逻辑。 */
        Interp eng;
        /** 本局定义（快照要把槽位 id 翻成显示名）。 */
        GameDefinition def;
        /** 本局的临时画板 key（界面里有「画板（本局）」框才建；null = 这局没画板）。 */
        String stageBoardKey;
        /** 上一次推给客户端的「阶段剩余秒」（-1 = 没在计时）—— 秒数变了才推，不每 tick 发。 */
        long leftPushed = -1;
        /**
         * 每个局内席位玩家「上一次踩到的地砖坐标」（世界事件的去抖账）—— 变了才发一次 {@code on world}。
         * 站着不动、原地转头都不会重复触发；跳跃/上下台阶会各发一次（脚本自己判要不要管）。
         */
        final Map<UUID, BlockPos> lastStep = new java.util.HashMap<>();
        /**
         * 本局「上一 tick 在不在每条区域里」（进出圈边沿的账）：键 = 区域名（匿名 = 空串），值 = 在圈玩家 UUID。
         * 跨过某条边沿发一次 {@code on world}（{@code edge} = 1/-1 · {@code edge_area} = 哪条）。
         * ⚠ 掉线要从每条账里摘掉（{@link #playerLoggedOut}），否则他回来时「一直在圈里」，首次进圈类脚本会哑。
         */
        final Map<String, Set<UUID>> insideArea = new java.util.HashMap<>();
        /**
         * 上一 tick 在场的人（进出圈边沿的账）：键 = UUID，值 = 那一刻的名字（掉线后也报得出是谁）。
         * ⚠ 不是成员表：只回答「这一 tick 是新进圈还是刚出圈」（见 {@link #autoSpectate}），不影响谁收快照 /
         * 谁能提交（那些现算）。掉线摘账同 {@link #insideArea}。
         */
        final Map<UUID, String> presentLast = new java.util.HashMap<>();

        /**
         * 脚本点名进局的名单：{@code enter(名字)} 加、{@code leave(名字)} 摘；不看空间，掉线也还在。
         * 只参与「谁在局里」（{@link Interp#inGame}）。住 Session 而非引擎：热替换重建 {@code Interp} 不该
         * 把报名的人踢出去。
         */
        final Set<String> namedPlayers = new LinkedHashSet<>();

        @Override
        public void enterGame(String who) {
            namedPlayers.add(who);
        }

        @Override
        public void leaveGame(String who) {
            namedPlayers.remove(who);
        }
        /** 死亡不掉落：谁的背包在他死后要原样回来（{@code keep_items(谁,1)} 开的）。 */
        final Set<UUID> keepItems = new LinkedHashSet<>();
        /**
         * 死亡瞬间的「背包 + 经验」快照 —— 重生时按槽位回填（见 {@link #playerDied} / {@link #playerRespawned}）。
         *
         * <p>挂在会话上是故意的：局一结束这份账就跟着没（他死后局散了 = 不再回填，而不是留一份孤儿背包）。
         */
        final Map<UUID, DeathStash> deathStash = new java.util.HashMap<>();
        /**
         * 本局已经 spawn 出来的棋子实体（棋子 id → 实体）—— {@code place_piece} 靠它判断
         * 「这枚还没生成（spawn）还是已经在世界里（挪过去）」，局末按它清理。
         */
        final Map<String, com.tablegame.piece.GamePieceEntity> pieceEntities = new java.util.HashMap<>();
        /**
         * 本局脚本保护的区域（{@code protect} / {@code unprotect}）：每项 =
         * {minX, minY, minZ, maxX, maxY, maxZ, 级别}（两角已取好 min/max）。消费方 = 破坏 / 放置监听
         * （{@code TableGame}）→ {@link #protectedLevelFor}。局末随会话一起没 —— 保护是「这一局」的。
         */
        final List<long[]> protectedRegions = new ArrayList<>();
        /**
         * 每个玩家「最近一次展开」的<b>框 id → 身份值</b>（舞台去模板化）：点击回来拿框 id 查这张账。
         *
         * <p>为什么按人存：同一条手牌，A 与 B 看到的展开不同（框 id 也不同），不能共用一份账。
         */
        final java.util.Map<UUID, java.util.Map<String, Object>> pickIds = new java.util.LinkedHashMap<>();
        /**
         * 每个玩家上一次推过的舞台 JSON —— 展开结果变了才重推。不推屏上会冻在开局那一帧。
         * {@code ponytail:} 大舞台（几百个框）改成比组件签名；现在几十个框，直接比串就够。
         */
        final java.util.Map<UUID, String> stagePushed = new java.util.LinkedHashMap<>();
        /**
         * 开局时的成员（游戏台模式 = 只有运行者）。他走 = 局没人主持了，终止。
         * 与「此刻在场的人」的差集 = 后来才进圈的（他们走了只发一次 on leave，不动局）。
         */
        final Set<UUID> foundingMembers = new LinkedHashSet<>();
        /** 游戏台的坐标（指令开局 = null）：观众加入按它找局、入座按它验距离。 */
        GlobalPos tablePos;
        /**
         * 引擎快照里的脚本指纹（sha-256 前 16 位，见 {@link #fingerprint}）：重进存档时拿它比对
         * 「盘上那份快照是这一版脚本跑出来的吗」。对不上就不续跑（等玩家手动点【运行】）。
         */
        String fingerprint = "";
        /**
         * 这局是不是**挂起**着（非常驻局重进存档后的样子）：挂起的局不 tick（离线不推进），
         * 等玩家到台子点【继续游戏】才接着跑。常驻局（{@code resident 1}）不挂起 —— 无人也在跑。
         */
        boolean paused;
        /**
         * 上一次「一条 area 都没读到」提示时的脚本 area 条数（初值 -2 = 还没提示过）—— 只在条数变化时
         * 记一行，免得每 tick 刷屏；脚本改了 area 条数会再提示一次。
         */
        int lastEdgeWarn = -2;
        /**
         * {@code on break} 的合并账：key = "x,y,z"（破坏的那格）· 值 = 那格上一次发事件的时刻。
         * 两个挂点（BreakBlockEvent 成功挖、BlockDropsEvent 掉落结算）对同一格先后触发 ⇒ 短窗内第二次只补信息。
         */
        final java.util.Map<String, Long> breakFired = new java.util.HashMap<>();
        /**
         * 这局是不是存档级世界玩法的局：没有游戏台、没有发起人、读存档即开局、进服务器自动进
         * （见 {@link #startWorldIfNeeded}）。落盘时带 {@code world} 标记，好在从盘上接回来时认出它。
         */
        boolean world;
        /**
         * 脚本 spawn 出来的棋子 id → 实体 UUID（{@code place_piece} 用它认领回世界里的那颗）。
         * {@link #pieceEntities} 是内存账，重进存档就没了，UUID 才落盘：存档那刻实体可能不在加载区块里，
         * 当场找不回，等下次 {@code place_piece} 再按 UUID 认领。否则重进后会多生一颗孤儿棋子。
         */
        final Map<String, UUID> pieceUuids = new java.util.HashMap<>();
        /**
         * 本局脚本放出来的原版生物（实体对象名 → 实体）：{@code spawn_mob} 靠它判断这只该生成还是挪过去，
         * 局末按它收掉。⚠ {@code ponytail:} 只有内存账、没落 UUID 盘账 —— 重进存档后再跑同一句
         * {@code spawn_mob} 会多放出一只；要跨存档认领就照 {@link #pieceUuids} 补一份盘账。
         */
        final Map<String, net.minecraft.world.entity.Entity> mobEntities = new java.util.HashMap<>();
        /**
         * 本局脚本放出来的卡牌实体（牌名 → 实体）—— {@code place_card} 靠它判断这张该生成还是挪过去，
         * 局末按它收掉。
         */
        final Map<String, com.tablegame.card.CardEntity> cardEntities = new java.util.HashMap<>();
        /**
         * 牌名 → 实体 UUID（{@code place_card} 认领回世界里那张）—— 与 {@link #pieceUuids} 同一套：
         * 内存账重进存档就没了，UUID 才落盘，等脚本下次 {@code place_card} 时人已到位再认领。
         */
        final Map<String, UUID> cardUuids = new java.util.HashMap<>();
        /**
         * 幽灵预览的去重账：玩家 UUID → 上一次发出去的落点（{@code 区域@x,y,z} / {@code off}）。
         * 玩家侧 {@code on look} 每换一格就调一次 {@code ghost}，一模一样的落点不该重发包；
         * 掉线时摘掉（见 {@link #playerLoggedOut}）—— 留着会让下次进服的第一次发包被旧账吃掉。
         */
        final Map<UUID, String> ghostKey = new java.util.HashMap<>();
        /**
         * 本局挂着的实体画面实例：一条 = （画面名, 锚点名）—— 同一块画面可以挂好几个锚点。组件实体
         * （原版 text_display）由 {@code maintainEntityStages} 每 tick 按需生成 / 摆位 / 收掉。
         */
        final List<EntityStage> entityStages = new java.util.ArrayList<>();

        Session(String gameName) {
            this.gameName = gameName;
        }

    }

    private HostManager(MinecraftServer server) {
        this.server = server;
    }

    public static void init(MinecraftServer server) {
        INSTANCE = new HostManager(server);
        INSTANCE.profiles = new ProfileStore(server.getServerDirectory());
        INSTANCE.store = new HostStore(server.getServerDirectory());   // 对局落盘（读在第一个 tick 里做，见 restore）
        // 世界玩法的绑定**随存档**（不是游戏目录 —— 那一层是所有存档共用的）
        INSTANCE.worldStore = new WorldGameStore(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATA));
        TableGame.LOGGER.info("[对局] 主持人已就绪");
    }

    /**
     * 服务器停机：**先落盘再扔内存**。{@code ServerStoppingEvent} 在玩家被踢下线之前发，
     * 所以此刻对局还是活的，写下来的才是「正在跑的那一局」；再晚就只剩「人全走完、局已终止」。
     */
    public static void shutdown() {
        if (INSTANCE != null) INSTANCE.saveNow();              // 停机前先把「正在跑的那些局」写盘
        INSTANCE = null;   // 内存里的对局状态随服务器一起扔（盘上那份才是真源）
    }

    public static HostManager get() {
        return INSTANCE;
    }

    /**
     * 每服务器 tick 喂一次时间：{@code on every(秒)} / 阶段超时 / 到点唤醒都在 {@link Interp#tick} 里触发。
     * 开销：一局一次毫秒差 + 一次整数比较；没在计时的阶段直接 return（不限时的游戏零成本）。
     */
    public void tick() {
        long now = System.currentTimeMillis();
        // 进世界后的第一个 tick：把盘上那份接回来（在这里而不是 init —— 此刻维度已加载好，
        // 认领棋子 / 判棋盘维度都要 ServerLevel）。
        if (!restored) {
            restored = true;
            // 世界玩法：先读**本存档**的绑定（restore 要用它筛掉别的存档的世界局），再读盘，最后按它开局。
            worldGame = worldStore == null ? "" : worldStore.game();
            restore();
            startWorldIfNeeded();               // 开了世界玩法 → 读存档即开局（无人也跑）
            // 接回来的那一局 / 刚开的这一局都不必在这里「补接人」：谁在场谁收（现算），
            // 进圈那一步由下面每 tick 的 autoSpectate 发 on join。
        }
        // 推时间之后：脚本已经「收局」（end / 开局就被拒）的会话要**真的结束掉** ——
        // 不结束的话会话一直留在表里：HUD/舞台收不掉，下一次 start 还会被
        // 「你已经在局里了」挡住（僵尸会话）。遍历走副本，边遍历边删会抛异常。
        for (Session s : new java.util.ArrayList<>(sessions)) {
            if (s.eng == null) continue;
            // 挂起中的局（非常驻局重进存档后的样子）：不 tick —— 离线不推进（时间不流逝、every 不跑），
            // 等玩家到台子点【继续游戏】才接着跑（见 resume）。
            if (s.paused) continue;
            s.eng.tick(now);
            if (s.eng.takeBoardClear() && s.stageBoardKey != null) {
                BoardManager bm = BoardManager.get();
                if (bm != null) bm.clearStageBoard(s.stageBoardKey);   // 脚本要求清板（新一轮开始）
            }
            // 脚本要求改世界方块（世界层 W1 第一刀）：攒批取走、一次执行。
            List<Interp.SetBlock> tbs = s.eng.takeSetBlocks();
            if (!tbs.isEmpty()) applySetBlocks(s, tbs);
            List<Interp.Fill> tfl = s.eng.takeFills();          // 区域填充（W1 第二样，抄 /fill）
            if (!tfl.isEmpty()) applyFills(s, tfl);
            List<Interp.Label> tlb = s.eng.takeLabels();        // 立标签（W3：世界承载显示）
            if (!tlb.isEmpty()) applyLabels(s, tlb);
            List<Interp.PlacePiece> tpc = s.eng.takePlacePieces();   // 棋子：生成 / 挪位置
            if (!tpc.isEmpty()) applyPlacePieces(s, tpc);
            List<Interp.PlaceCard> tcard = s.eng.takePlaceCards();   // 卡牌实体：生成 / 挪位置
            if (!tcard.isEmpty()) applyPlaceCards(s, tcard);
            List<Interp.FlipCard> tflip = s.eng.takeFlipCards();      // 卡牌翻面
            if (!tflip.isEmpty()) applyFlipCards(s, tflip);
            // 脚本要的实体画面「挂上 / 收起」
            List<Interp.ShowEntity> tse = s.eng.takeShowEntities();
            if (!tse.isEmpty()) applyShowEntities(s, tse);
            List<Interp.HideEntity> the = s.eng.takeHideEntities();
            if (!the.isEmpty()) applyHideEntities(s, the);
            maintainEntityStages(s);                       // 每 tick 跟随 / 刷新内容 / 锚点没了就收
            // 文本对象的点击回投：宿主查声明里的正文 / 标记，挂上 `/tablegame pick 标记` 再发。
            List<Interp.SayMark> tsk = s.eng.takeSayMarks();
            if (!tsk.isEmpty()) applySayMarks(s, tsk);
            List<Interp.Ghost> tgh = s.eng.takeGhosts();          // 幽灵预览：开 / 关（按玩家发 S2C）
            if (!tgh.isEmpty()) applyGhosts(s, tgh);
            List<Interp.Protect> tpr = s.eng.takeProtects();      // 区域保护（protect / unprotect）
            if (!tpr.isEmpty()) applyProtects(s, tpr);
            // 世界 → 脚本（W2）：谁踩到了新的一格 → 发一次 on world（带 bx/by/bz/block）。
            stepEvents(s);
            // 进 / 出圈：每 tick 现算谁在区域里，与上一 tick 比 —— 新进圈发一次 on join，出圈发一次 on leave。
            // 谁在场谁收（收件人现算），进圈 = 进局，对哪种局都一样。
            autoSpectate(s);
            // 圈边（另一个内建值面）：写了 area 的局，谁跨过圈边就发一次
            // on world（内建值 edge：1 刚进 / -1 刚出）。**不要求 resident** —— 圈边对任何玩法都有用。
            edgeEvents(s);
            // 不补推的话，屏幕上的倒计时会冻在最后一帧。
            long left = (long) s.eng.stageLeft();
            if (left >= 0 && left != s.leftPushed) {
                s.leftPushed = left;
                broadcastState(s);
            }
            if (s.eng.isFinished()) endSession(s, "[对局] 对局已结束");
        }
        maybeSave();                                   // 节流落盘（见 saveNow 的注释）
    }

// ===== 对局落盘（退出存档再进来，台上的局还在）=====

    /**
     * 节流落盘：有台选中 / 有局在跑时，每 5 秒写一次。
     * {@code ponytail:} 5 秒 = 硬崩最多丢 5 秒进度（正常退出走 {@link #shutdown} 必写）；要省盘就加变更回调。
     */
    private void maybeSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < 5000) return;
        lastSaveMs = now;
        saveNow();
    }

    /**
     * 立刻落盘（停机前 / 节流到点时调）。没有台与局 → 把文件撤掉。记什么：① 每张台「选中了哪款」
     * ② 在跑（或挂起）的每局 —— 局骨架（成员 / 保护区域 / 棋子 UUID / 桌面坐标）+ 脚本指纹 + 引擎快照。
     */
    public void saveNow() {
        if (store == null) return;                               // 还没 init（自检 / 早期事件）
        JsonObject root = new JsonObject();
        root.addProperty("format", HostStore.FORMAT);
        JsonArray tables = new JsonArray();
        for (Map.Entry<BlockPos, String> e : tableSelected.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("x", e.getKey().getX());
            t.addProperty("y", e.getKey().getY());
            t.addProperty("z", e.getKey().getZ());
            t.addProperty("game", e.getValue());
            tables.add(t);
        }
        root.add("tables", tables);
        JsonArray ss = new JsonArray();
        for (Session s : sessions) {
            if (s.eng == null) continue;
            // 只落「有台的局」与世界玩法的局：/tablegame start 那条没台就没【继续游戏】入口，接回来只会
            // 卡在挂起态；世界玩法反过来没台也得落 —— 它就是靠「读存档即开局」跑起来的那一套。
            if (s.tablePos == null && !s.world) continue;
            Interp.Snapshot snap = s.eng.snapshot();
            if (snap == null) continue;                          // 已结束 / 已停住（脚本报错）：没落盘的意义
            JsonObject o = new JsonObject();
            o.addProperty("game", s.gameName);
            o.addProperty("fingerprint", s.fingerprint);
            o.addProperty("paused", s.paused);
            if (s.world) o.addProperty("world", true);            // 世界局：接回来时要拿它核本存档的绑定
            // 不落「局内成员表」—— 谁在局里是现算的（见 recipients）；老档里那份 members 字段读时直接忽略。
            o.add("founders", uuidsJson(s.foundingMembers));
            if (s.tablePos != null) {
                JsonObject tp = new JsonObject();
                tp.addProperty("dim", s.tablePos.dimension().identifier().toString());   // 26.x: ResourceKey#identifier()（不是老的 location()）
                tp.addProperty("x", s.tablePos.pos().getX());
                tp.addProperty("y", s.tablePos.pos().getY());
                tp.addProperty("z", s.tablePos.pos().getZ());
                o.add("table", tp);
            }
            JsonArray pr = new JsonArray();                      // 保护区域：{minX..maxZ} 六个 long
            for (long[] r : s.protectedRegions) {
                JsonArray a = new JsonArray();
                for (long v : r) a.add(v);
                pr.add(a);
            }
            o.add("protect", pr);
            JsonArray pc = new JsonArray();                      // 棋子 id → 实体 UUID（认领用，见 Session#pieceUuids）
            for (Map.Entry<String, UUID> e : s.pieceUuids.entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("id", e.getKey());
                p.addProperty("uuid", e.getValue().toString());
                pc.add(p);
            }
            o.add("pieces", pc);
            JsonArray cc = new JsonArray();                      // 牌名 → 实体 UUID（认领用，见 Session#cardUuids）
            for (Map.Entry<String, UUID> e : s.cardUuids.entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("name", e.getKey());
                p.addProperty("uuid", e.getValue().toString());
                cc.add(p);
            }
            o.add("cards", cc);
            o.add("snap", HostStore.snapJson(snap));
            if (s.stageBoardKey != null) {
                BoardManager bm = BoardManager.get();
                int[] px = bm == null ? null : bm.stageBoardPixels(s.stageBoardKey);
                if (px != null) {
                    JsonObject bd = HostStore.boardJson(px, STAGE_DIM, STAGE_DIM);
                    if (bd != null) o.add("board", bd);          // 全空不写（省几 KB）
                }
            }
            ss.add(o);
        }
        root.add("sessions", ss);
        if (tables.isEmpty() && ss.isEmpty()) {
            store.clear();
            return;
        }
        store.save(root);
    }

    /**
     * 从盘上接回对局（进世界后的第一个 tick 做一次 —— 那时维度已加载，要拿它认领棋子）。接不回来一律
     * 「当作没存过」继续（档没了 / 脚本改过 / 块号对不上 / 文件读坏）；局没了不崩服，报一行日志就够。
     */
    private void restore() {
        if (store == null) return;
        JsonObject root = store.load();
        int nTables = 0;
        for (JsonElement e : arr(root, "tables")) {
            JsonObject t = e.getAsJsonObject();
            tableSelected.put(new BlockPos(num(t, "x"), num(t, "y"), num(t, "z")), str(t, "game"));
            nTables++;
        }
        int nSessions = 0;
        int nBroken = 0;
        for (JsonElement e : arr(root, "sessions")) {
            JsonObject o = e.getAsJsonObject();
            // 世界局是**存档级**的，而这份账住在游戏目录（所有存档共用）⇒ 接回来之前先核一遍
            // 「这一局是不是本存档绑的那款」—— 不核的话切个存档就把别的存档的世界局接进来了。
            if (isWorldRecord(o) && !str(o, "game").equals(worldGame)) {
                TableGame.LOGGER.info("[对局] 跳过《{}》的世界局：它不是这个存档绑的玩法", str(o, "game"));
                continue;
            }
            Session s = rebuildSession(o);
            if (s == null) {
                nBroken++;
                noteFor(o, "上次存档里这一局接不上（脚本改过或档坏了）—— 点【运行】重开一局");
                continue;
            }
            sessions.add(s);
            nSessions++;
        }
        if (nTables > 0 || nSessions > 0 || nBroken > 0) {
            TableGame.LOGGER.info("[对局] 从存档接回：{} 张台的选中、{} 局（接不上 {} 局）",
                    nTables, nSessions, nBroken);
        }
    }

    /** 盘上这条记录是不是世界玩法的局（老档没有这个字段 → false）。 */
    private static boolean isWorldRecord(JsonObject o) {
        return o.has("world") && o.get("world").isJsonPrimitive() && o.get("world").getAsBoolean();
    }

    /** 接不上的那一局：把理由挂到它那张台上（台屏一行提示；指令开局的局没台可挂，只写日志）。 */
    private void noteFor(JsonObject o, String note) {
        if (!o.has("table") || !o.get("table").isJsonObject()) return;
        JsonObject t = o.getAsJsonObject("table");
        tableNotes.put(new BlockPos(num(t, "x"), num(t, "y"), num(t, "z")), note);
    }

    /**
     * 把盘上一条局记录重建成活的会话；返回 null = 接不上（调用方计一笔数、继续）。① 先比脚本指纹，改过
     * 就不接；② 走 {@link #buildEngine} 同一套注入，否则续跑与开局行为不同；③ 常驻局不挂起，其余等【继续游戏】。
     */
    private Session rebuildSession(JsonObject o) {
        try {
            String game = str(o, "game");
            GameManager gm = GameManager.get();
            GameDefinition def = gm == null ? null : gm.load(game);
            if (def == null || def.script() == null || def.script().isBlank()) {
                TableGame.LOGGER.warn("[对局] 存档里那局《{}》的游戏现在找不到了 —— 跳过", game);
                return null;
            }
            String fp = fingerprint(def.script());
            // 脚本改过就不续跑（快照块号是旧脚本的）——只对非世界局（那种局没入口，接错没处纠）。
            // 世界玩法反过来：先拿新脚本试接那份快照（改数值 / 字面量 / 尾部新增时进度保得住），
            // 接不上再由 startWorldIfNeeded 用新脚本从头开一局 —— 宁可重来，不悄悄重置玩家进度。
            if (!fp.equals(str(o, "fingerprint")) && !isWorldRecord(o)) {
                TableGame.LOGGER.info("[对局] 《{}》的脚本与存档里的快照对不上（脚本改过）"
                        + "—— 不续跑，等玩家手动点【运行】", game);
                return null;
            }
            Ast.Script script;
            try {
                script = Parser.parseForPlay(def.script());            // 开局口径（恢复也算开局）
            } catch (Ast.ScriptError e) {
                TableGame.LOGGER.info("[对局] 《{}》的脚本开不了局（{}）—— 不续跑", game, e.getMessage());
                return null;
            }
            Session s = new Session(game);
            s.def = def;
            // ⚠ 老档里那份 `members`（局内成员表）直接忽略：谁在局里是现算的。引擎那份「在场名单」
            // （只用来解析 say 的 others）也照现算结果填（见 presentNames）。
            for (JsonElement e : arr(o, "founders")) s.foundingMembers.add(UUID.fromString(e.getAsString()));
            if (!buildEngine(s, script, presentNames(s, script), null)) return null;
            Interp.Snapshot snap = o.has("snap") && o.get("snap").isJsonObject()
                    ? HostStore.snapOf(o.getAsJsonObject("snap")) : null;
            if (!s.eng.apply(snap, System.currentTimeMillis())) {
                TableGame.LOGGER.info("[对局] 《{}》的存档快照接不上（与脚本对不上）—— 不续跑", game);
                return null;
            }
            s.fingerprint = fp;
            if (o.has("table") && o.get("table").isJsonObject()) {
                JsonObject tp = o.getAsJsonObject("table");
                var id = net.minecraft.resources.Identifier.tryParse(str(tp, "dim"));
                var key = id == null ? null : net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, id);
                if (key != null) {
                    s.tablePos = GlobalPos.of(key, new BlockPos(num(tp, "x"), num(tp, "y"), num(tp, "z")));
                }
            }
            for (JsonElement e : arr(o, "protect")) {
                JsonArray a = e.getAsJsonArray();
                long[] r = new long[a.size()];
                for (int i = 0; i < a.size(); i++) r[i] = a.get(i).getAsLong();
                // 老档 6 位（没级别，那时就是「非创造别动」）⇒ 补成二级（语义最接近）；新档 7 位（末位 = 级别 1|2|3）。
                if (r.length == 6) s.protectedRegions.add(new long[]{r[0], r[1], r[2], r[3], r[4], r[5], 2});
                else if (r.length == 7) s.protectedRegions.add(r);
            }
            if (!s.protectedRegions.isEmpty()) {
                // 「保护又没用了」先看有没有这行：没有 = 落盘里就没有（登记那步漏了落盘）—— 静默恢复最坑。
                TableGame.LOGGER.info("[保护] 从落盘恢复 {} 片保护", s.protectedRegions.size());
            }
            for (JsonElement e : arr(o, "pieces")) {            // 只记 UUID：实体要等区块加载才认领得回来
                JsonObject p = e.getAsJsonObject();
                s.pieceUuids.put(str(p, "id"), UUID.fromString(str(p, "uuid")));
            }
            for (JsonElement e : arr(o, "cards")) {             // 卡牌同理
                JsonObject p = e.getAsJsonObject();
                s.cardUuids.put(str(p, "name"), UUID.fromString(str(p, "uuid")));
            }
            s.world = isWorldRecord(o);
            s.paused = !(s.eng.resident() || s.world);   // 世界玩法 = 常驻：无人也跑（不挂起）
            // 画板：重进后**新开一块**（内容要等下一批才落盘接得回来）。不新开的话，界面里那块
            // 「画板（本局）」框会整块失效（key 为 null）—— 比空白更难看出原因。
            openStageBoardFor(s, def);
            if (s.stageBoardKey != null && o.has("board") && o.get("board").isJsonObject()) {
                BoardManager bm = BoardManager.get();
                int[] px = HostStore.boardPixels(o.getAsJsonObject("board"), STAGE_DIM, STAGE_DIM);
                if (bm != null && px != null) bm.restoreStageBoardPixels(s.stageBoardKey, px);
            }
            return s;
        } catch (Exception e) {
            TableGame.LOGGER.error("[对局] 重建存档里那一局失败（跳过）：{}", e.toString());
            return null;
        }
    }

    /**
     * 【继续游戏】（台上按钮，见 {@link #dealerRun}）：把挂起的局接着跑。
     * 挂起期间不 tick，所以这里把快照按**现在**重新落地 —— 挂了几天的 {@code wait(5)} 是「继续后 5 秒」到点。
     */
    private void resume(Session s, ServerPlayer sp) {
        Interp.Snapshot snap = s.eng == null ? null : s.eng.snapshot();
        if (snap != null) s.eng.apply(snap, System.currentTimeMillis());
        s.paused = false;
        // 他手上的屏由下面这次 broadcastState 补（收件人 = 此刻在区域里的人 —— 无需「接进名单」）
        say(s, "[对局] 《" + s.gameName + "》接着跑（" + sp.getName().getString() + " 点的继续）");
        broadcastState(s);
        lastSaveMs = 0;                                       // 状态变了：下一 tick 立刻落盘
    }

    /**
     * 脚本指纹（SHA-256 前 16 位十六进制）：判「盘上那份快照是这一版脚本跑出来的吗」（行数 / 时间戳都不行）。
     * 拿不到摘要（不该发生）→ 返回空串 → 与盘上对不上 → 不续跑（宁可不接，不接错）。
     */
    private static String fingerprint(String script) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new JsonArray();
        return o.getAsJsonArray(key);
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static int num(JsonObject o, String key) {
        if (o == null || !o.has(key)) return 0;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    private static JsonArray uuidsJson(java.util.Collection<UUID> ids) {
        JsonArray a = new JsonArray();
        for (UUID id : ids) a.add(id.toString());
        return a;
    }

    // ===== 开局 / 中断 =====

    /**
     * 开局（游戏台「运行」键 / {@code /tablegame start}）。收件人每 tick 现算：谁此刻在 {@code area} 里
     * （一条都没写 = 整个棋盘维度）谁就收到东西，进圈发一次 {@code on join}（见 {@link #autoSpectate}）；
     * 谁算在座玩家由脚本自己的名单说话。
     */
    public void start(ServerPlayer host, String gameName) {
        startInternal(host, gameName, null);
    }

    /** 游戏台「运行」：发起人只是「谁开的这一局」（他是 founding 那位 —— 他走 = 局终止）。 */
    public void startAtTable(ServerPlayer host, String gameName, GlobalPos gp) {
        // 同一张台已经有开着的局 → 拒（这是「各开一份」 bug 的根：原来不查，人人都能再开一桌）
        for (Session s : sessions) {
            if (gp.equals(s.tablePos)) {
                fail(host, "这张台已经在跑《" + s.gameName + "》了 —— 点【进入游戏界面】加入，或先【中断游戏】");
                return;
            }
        }
        startInternal(host, gameName, gp);
    }

    private void startInternal(ServerPlayer host, String gameName, GlobalPos tablePos) {
        startInternal(host, gameName, tablePos, false);
    }

    /**
     * 建局的唯一实现（三个入口共用）：{@code /tablegame start} · 游戏台【运行】· 世界玩法（{@code host == null}）。
     * 发东西看现算的在场（{@link #recipients}），刚进圈由 {@link #autoSpectate} 发 {@code on join}。
     *
     * @param world 存档级世界玩法的局（没台 · 要落盘 · 常驻）
     */
    private void startInternal(ServerPlayer host, String gameName, GlobalPos tablePos, boolean world) {
        GameManager gm = GameManager.get();
        if (gm == null) {
            fail(host, "服务端还没准备好（定义总管未初始化）");
            return;
        }
        GameDefinition def = gm.load(gameName);
        if (def == null) {
            fail(host, "找不到游戏定义: " + gameName);
            return;
        }
        if (host != null && sessionOf(host) != null) {
            fail(host, "你已经在局里了（先 /tablegame abort）");
            return;
        }

        Session s = new Session(def.name());
        s.world = world;
        // 名单不在这里攒（C 批）：在场是每 tick 现算的 —— 建引擎时给的那份「在场名单」也是现算的
        // （引擎只拿它解析 say/show 的 others；「谁入局」归脚本自己的数组）。

        if (def.script() == null || def.script().isBlank()) {
            fail(host, "这份档没有 script 段（第 3 步的文本编辑屏还没做，先手写一段脚本）");
            return;
        }
        Ast.Script script;
        try {
            script = Parser.parseForPlay(def.script());   // 开局口径：这里才拦「没有 on 事件」
        } catch (Ast.ScriptError e) {
            fail(host, "这份档的脚本有问题 —— " + e.getMessage());   // 带行号，作者能直接定位
            return;
        }

        s.def = def;
        s.fingerprint = fingerprint(def.script());   // 落盘用：重进存档时拿它判「脚本改过没」（改过就不续跑）
        if (!buildEngine(s, script, presentNames(s, script), host)) return;
        // 先把引擎时钟拨到"现在"：开局链里上的闹钟（timer）拿它当起点 ——
        // clock 只在有对局的 tick 里更新，不拨的话起点是 0，第一个 tick 就误判成"超时"。
        s.eng.tick(System.currentTimeMillis());
        if (host != null) s.foundingMembers.add(host.getUUID());   // 运行者走了 = 没人主持，局终止（世界玩法没有发起人）
        s.tablePos = tablePos;                   // 指令开局 = null（不按台找局/验距离）
        sessions.add(s);
        say(s, "[对局] 开局：" + def.name()
                + (world ? " · 世界玩法（无台 · 常驻）" : " · 谁在场谁收（走进区域即进局）")
                + " · 脚本 " + script.lines() + " 行");
        sendStage(s, def, true);         // 舞台定义发一次：客户端拿到就挂 HUD
        openStageBoardFor(s, def);       // 界面里有「画板（本局）」框才建临时板 + 登记观看
        if (!s.eng.start()) {
            // 开不了局（没有 on start）：不留半成品会话 —— 但先把已经发出去的舞台收回来：sendStage 在 start
            // 之前就发过（客户端一拿到就挂 HUD / 开屏），不收就是一块 **关不掉的孤儿 HUD**。
            // ⚠ 收件人要先算：sessions.remove 之后 sessionOf 就查不到这一局了。
            for (ServerPlayer pl : recipients(s)) sendStageTo(s, pl, null, false);
            sessions.remove(s);
            closeStageBoard(s);
        } else if (s.eng.isFinished()) {
            // 脚本在**开局链里就 end 了**（例如"席位不够，拒绝开局"）→ 当场收掉：
            // 舞台/HUD 闪都不闪，也不留会话（否则下一次 start 会撞"已经在局里"）。
            endSession(s, "[对局] 对局已结束");
        } else {
            // 开局后先推一次完整状态。两个理由：① 客户端输入框能不能打字看快照里的 waitAction，
            // 没有这一份就得到第一次状态变化才解冻；② 画块（part / screen）的**第一次展开**挂在这条路上
            // —— 老档靠 timer 的倒计时广播顺带罩着，没计时的档就一次都不推（屏上只剩档里的静态框）。
            broadcastState(s);
        }
    }

    /**
     * 建引擎 + 接上宿主侧注入（读世界 / 容器 / 玩家操作 / 玩家档案）。开局与「重进存档续跑」共用这一处：
     * 注入漏一条，续跑起来的局就跟开局那局行为不同。
     *
     * @param people 本局在场名单 —— 引擎只拿它解析 say/show 的 others
     * @param tell   出错时回话给谁（续跑时为 null → 只记日志）
     * @return false = 脚本有问题（已经回话 / 记日志，调用方直接收场）
     */
    private boolean buildEngine(Session s, Ast.Script script, List<String> people, ServerPlayer tell) {
        try {
            // 建引擎：名单只用来解析 say / show 的 others；「谁入席」归脚本自己的数组。
            s.eng = new Interp(script, people, new HubSink(s));
            s.eng.setMembers(s);                             // 点名进局（enter / leave）记在 Session 上
            // 接上「读世界」（脚本 block_at(x,y,z) 要问服务器那格是什么方块）——
            // Interp 是零 MC 依赖的纯逻辑，只能靠宿主注入这个回调（同 Sink 的路子）。
            // 读的是**这一局的棋盘维度**（脚本 dim 声明）。
            s.eng.setWorldReader((x, y, z) -> blockIdAt(levelFor(s), new BlockPos(x, y, z)));
            // 接上「容器读写」（脚本 chest_items / bag_items / chest_set / bag_set）——同一条注入路子，
            // 交给解释器的是**原始清单**（读）与「覆盖式写一槽」（写）；包成语言里的值形状是解释器的事。
            // 写走注入而不攒批，是因为写必须**立刻生效**（脚本常「先摆好再核对」，攒批会让紧接着的读拿到旧内容）。
            s.eng.setWorldContainers(new Interp.WorldContainers() {
                @Override public java.util.List<Interp.SlotItem> itemsAt(int x, int y, int z) {
                    return itemsOf(containerAt(levelFor(s), new BlockPos(x, y, z)));
                }

                @Override public java.util.List<Interp.SlotItem> itemsIn(String who) {
                    return itemsOf(bagAt(who));                 // 玩家背包（原版 Inventory 本身就是 Container）
                }

                @Override public void setAt(int x, int y, int z, int slot, String item, int count) {
                    setSlotIn(containerAt(levelFor(s), new BlockPos(x, y, z)), slot, item, count, s.gameName);
                }

                @Override public void setIn(String who, int slot, String item, int count) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[容器] 写不了背包：找不到玩家 {}", who);
                        return;
                    }
                    setSlotIn(pl.getInventory(), slot, item, count, s.gameName);
                    // ⚠ 背包改完必须推给客户端：Inventory.setItem 自己**不同步**（连 setChanged() 也只是计数），
                    // 不推的话玩家屏幕上还是旧背包。原版 /give 加完东西也做这一步（GiveCommand:81）。
                    // 用 inventoryMenu 而不是 containerMenu：玩家开着箱子界面时后者是那个箱子菜单，
                    // 只同步 36 个背包格，护甲/副手会漏。
                    pl.inventoryMenu.broadcastChanges();
                }

                @Override public void give(String who, String item, int count) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[物品] 发不了：找不到玩家 {}", who);
                        return;
                    }
                    var st = itemStackOf(item, count, s.gameName);
                    if (st == null) return;                        // 认不出 / 数量 ≤ 0：itemStackOf 已记日志
                    pl.getInventory().add(st);                     // 先并进同款堆、再找空格（原版 /give 同款）
                    if (!st.isEmpty()) pl.drop(st, false);         // 背包满：掉在脚下 —— 别静默吞掉东西
                    pl.inventoryMenu.broadcastChanges();           // 同 setIn：背包改完必须推客户端
                }

                @Override public double countBlocks(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
                    return countBlocksIn(levelFor(s), x1, y1, z1, x2, y2, z2, block);
                }

                @Override public int bagTake(String who, String item, int count) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[背包] 取不了：找不到玩家 {}", who);
                        return 0;
                    }
                    var probe = itemStackOf(item, 1, s.gameName);   // 认 id 只有这一条路（与 give / chest_give 同口径）
                    if (probe == null) return 0;                  // 认不出：itemStackOf 已记日志
                    int got = takeFrom(pl.getInventory(), probe.getItem(), count);
                    if (got > 0) pl.inventoryMenu.broadcastChanges();   // 同 setIn：背包改完必须推客户端
                    return got;
                }

                @Override public double bagCountAsset(String who, String asset) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) return 0;
                    String want = assetRef(s, asset);
                    if (want.isEmpty()) return 0;                 // 项目里没这条物品：0（不掐局）
                    int n = 0;
                    var inv = pl.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        if (assetTagOf(inv.getItem(i)).equals(want)) n += inv.getItem(i).getCount();
                    }
                    return n;
                }

                @Override public int bagTakeAsset(String who, String asset, int count) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null || count <= 0) return 0;
                    String want = assetRef(s, asset);
                    if (want.isEmpty()) return 0;
                    var inv = pl.getInventory();
                    // 全有或全无：先数一遍，不够就一件不动（同 bagTake 口径）
                    int have = 0;
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        var st = inv.getItem(i);
                        if (!st.isEmpty() && assetTagOf(st).equals(want)) have += st.getCount();
                    }
                    if (have < count) return 0;
                    int left = count;
                    for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                        var st = inv.getItem(i);
                        if (st.isEmpty() || !assetTagOf(st).equals(want)) continue;
                        int take = Math.min(left, st.getCount());
                        inv.setItem(i, take >= st.getCount() ? net.minecraft.world.item.ItemStack.EMPTY
                                : st.copyWithCount(st.getCount() - take));
                        left -= take;
                    }
                    pl.inventoryMenu.broadcastChanges();           // 背包改完必须推客户端（同 setIn）
                    return count;
                }

                /** 资产名 → 引用名（assetNameOf 归一；项目里没有这条物品 → 空串）。 */
                private String assetRef(Session s2, String asset) {
                    GameManager gm = GameManager.get();
                    GameDefinition def = gm == null ? null : gm.load(s2.gameName);
                    GameDefinition.AssetDef a = def == null ? null
                            : GameDefinition.assetOf(def.assets(), GameDefinition.assetNameOf(asset));
                    return a != null && a.isItem() ? a.ref() : "";
                }

                @Override public void bagSave(String who, String key) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null || key.isEmpty()) {
                        TableGame.LOGGER.warn("[背包] 存不了：找不到玩家 {} / 键是空的", who);
                        return;
                    }
                    if (INSTANCE == null || INSTANCE.profiles == null) return;
                    INSTANCE.profiles.set(s.gameName, uuidOf(who), key, bagSnapshot(pl, levelFor(s)));
                }

                @Override public void bagRestore(String who, String key) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null || key.isEmpty()) {
                        TableGame.LOGGER.warn("[背包] 发不回：找不到玩家 {} / 键是空的", who);
                        return;
                    }
                    String snbt = INSTANCE == null || INSTANCE.profiles == null ? ""
                            : INSTANCE.profiles.get(s.gameName, uuidOf(who), key);
                    if (snbt.isEmpty()) return;                   // 没存过：什么都不发生（不报错）
                    bagLoad(pl, levelFor(s), snbt);
                }

                @Override public int chestGive(int x, int y, int z, String item, int count) {
                    var c = containerAt(levelFor(s), new BlockPos(x, y, z));
                    if (c == null) {
                        TableGame.LOGGER.warn("[容器] 塞不了：那一格不是容器 / 没加载（{},{},{}）", x, y, z);
                        return 0;
                    }
                    var st = itemStackOf(item, count, s.gameName);
                    if (st == null) return 0;                  // 认不出 / 数量 ≤ 0：itemStackOf 已记日志
                    return fillInto(c, st);
                }
            });
            // 接上「图案与语义格」（脚本 shape_cell / match_shape）—— 区域快照在这一局的游戏定义里
            // （s.def：热替换 / 重进存档时刷新），世界方块也只有宿主读得到。
            s.eng.setShapes(new Interp.Shapes() {
                @Override public double[] cellAt(String area, String role, int x, int y, int z) {
                    GameDefinition.AreaDef a = s.def == null ? null
                            : GameDefinition.areaOf(s.def.areas(), area);
                    GameDefinition.AreaDef.CellMark m = a == null
                            ? null : GameDefinition.AreaDef.markOf(a, role);
                    if (m == null) return null;                // 没这条区域 / 没标过这个角色 → 脚本拿坐标全 0
                    return new double[] { x + m.i(), y + m.j(), z + m.k() };
                }

                @Override public boolean matchShape(String area, int x, int y, int z) {
                    GameDefinition.AreaDef a = s.def == null ? null
                            : GameDefinition.areaOf(s.def.areas(), area);
                    if (a == null || !a.captured()) {
                        TableGame.LOGGER.warn("[图案] match_shape 找不到捕获过的区域「{}」—— 回 0", area);
                        return false;
                    }
                    int n = a.sizeX() * a.sizeY() * a.sizeZ();
                    if (n > MAX_FILL) {                        // 同 count_blocks 一条口径：太大不判（防按住服务端）
                        TableGame.LOGGER.warn("[图案] match_shape 一次最多 {} 格，这条 {} 格 —— 回 0",
                                MAX_FILL, n);
                        return false;
                    }
                    // ponytail: 逐格把世界读成两个平行数组（键 + 算不算实心）——宿主这层只是壳，
                    // 判定全在 AreaDef.shapeMatches（纯逻辑，自检真跑）；真卡了再改成边读边比。
                    ServerLevel lv = levelFor(s);
                    int sx = a.sizeX(), sy = a.sizeY();
                    String[] keys = new String[n];
                    boolean[] solid = new boolean[n];
                    for (int idx = 0; idx < n; idx++) {
                        var pos = new BlockPos(x + idx % sx, y + (idx / sx) % sy, z + idx / (sx * sy));
                        var st = lv.getBlockState(pos);
                        keys[idx] = GameStore.blockToString.apply(st);
                        // 「实心」= **有碰撞箱**：火把 / 草 / 水没碰撞 ⇒ 该空的格放松放行；
                        // 玻璃 / 半砖有碰撞 ⇒ 算「多了一块」。⚠ 别用 isSolid()：26.x 里已标 @Deprecated。
                        solid[idx] = !st.getCollisionShape(lv, pos).isEmpty();
                    }
                    return GameDefinition.AreaDef.shapeMatches(a, keys, solid);
                }
            });
            // 接上「撒方块」（脚本 scatter）—— 表来自这一局的项目档（现成的 lootTableOf 直解），
            // 写世界与「随机挑格不重复」也只有宿主做得到。
            s.eng.setScatter(new Interp.Scatter() {
                @Override public int scatter(int x1, int y1, int z1, int x2, int y2, int z2,
                        String table, int count, String replace) {
                    return scatterIn(s, levelFor(s), x1, y1, z1, x2, y2, z2, table, count, replace);
                }
            });
            // 接上「弹对话框」（脚本 dialog(谁, "框名")）—— 走原版那条路：注册表里查 Holder →
            // Player.openDialog（原版自己发 ClientboundShowDialogPacket，玩家侧是原版现成界面，客户端零改动）。
            // 框名没写命名空间按 tablegame 补；认不出 / 不是玩家 → 记一行，不掐局。
            s.eng.setDialogs((who, name) -> {
                ServerPlayer pl = playerByName(who);
                if (pl == null) {
                    TableGame.LOGGER.warn("[原版数据] 弹不了对话框：找不到玩家 {}（{}）", who, name);
                    return;
                }
                String full = name.contains(":") ? name : (GameLayout.PACK_ID + ":" + name);
                var key = net.minecraft.resources.Identifier.tryParse(full);
                var reg = levelFor(s).registryAccess()
                        .lookup(net.minecraft.core.registries.Registries.DIALOG).orElse(null);
                var dlg = (key == null || reg == null) ? null : reg.get(key).orElse(null);
                if (dlg == null) {
                    TableGame.LOGGER.warn("[原版数据] 认不出的对话框：\"{}\"", name);
                    return;
                }
                pl.openDialog(dlg);
            });
            // 接上「发粒子」（脚本 particle）—— 解析走原版 ParticleTypes.CODEC（纯 id 与带参数的完整 JSON 都认）；
            // 只有服务端发得出去（ServerLevel.sendParticles）⇒ 必须过宿主这一层。认不出 → 记一行 + 0 颗，不掐局。
            s.eng.setParticles((x, y, z, spec, count) -> {
                var lvP = levelFor(s);
                var opt = particleOf(lvP, spec);
                if (opt == null) {
                    TableGame.LOGGER.warn("[粒子] 认不出的粒子：\"{}\"", spec);
                    return 0;
                }
                return lvP.sendParticles(opt, x, y, z, Math.max(0, count), 0.0, 0.0, 0.0, 0.0);
            });
            // 接上「方块实体的数据键」（脚本 block_data_get / block_data_set）—— 走原版那条路：拿 BE 的 NBT
            // （saveWithoutMetadata）→ 按路径读 / 改 → loadCustomOnly 打回去 + setChanged。
            // ⚠ 只写它自己已有的键（按原本类型写）—— 不造新键（新键要造结构，不属于这一格）。
            s.eng.setBlockData(new Interp.BlockData() {
                @Override public double get(int x, int y, int z, String path) {
                    return blockDataGet(levelFor(s), x, y, z, path);
                }
                @Override public void set(int x, int y, int z, String path, double value) {
                    blockDataSet(levelFor(s), x, y, z, path, value);
                }
                @Override public String keys(int x, int y, int z) {
                    return blockDataKeys(levelFor(s), x, y, z);
                }
            });
            // 接上「改玩家」（脚本 gamemode / tp）：立刻生效；认不出模式 / 人不在 → 记一行跳过，不掐局。
            // 接上「玩家档案」（profile_get / profile_set，按游戏分家 · 一人一份）：落
            // games/<游戏>/players/<uuid>.json。「哪款游戏」「名字 → UUID」在这里补上；值是文本一层。
            final String profileGame = s.gameName;
            s.eng.setProfileStore(new Interp.ProfileStore() {
                @Override public String get(String who, String key) {
                    return INSTANCE != null && INSTANCE.profiles != null
                            ? INSTANCE.profiles.get(profileGame, uuidOf(who), key) : "";
                }
                @Override public void set(String who, String key, String value) {
                    if (INSTANCE != null && INSTANCE.profiles != null) {
                        INSTANCE.profiles.set(profileGame, uuidOf(who), key, value);
                    }
                }
            });
            s.eng.setEntityOps(new Interp.EntityOps() {
                @Override public void gameMode(String who, String mode) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[玩家] 改不了模式：找不到玩家 {}", who);
                        return;
                    }
                    var gt = net.minecraft.world.level.GameType.byName(mode, null);
                    if (gt == null) {
                        TableGame.LOGGER.warn("[玩家] 认不出游戏模式 \"{}\"（gamemode {}）", mode, who);
                        return;
                    }
                    TableGame.LOGGER.info("[模式] {} → {}（脚本 gamemode）", who, mode);
                    // ⚠ 必须走原版 ServerPlayer#setGameMode：它替我们改服务端状态、重算 abilities、
                    // 发 ClientboundGameEventPacket 通知客户端。只调 changeGameModeForPlayer 是「只改服务端」，
                    // 客户端 HUD 还按创造画、行为已是生存 ⇒ 「创造 + 生存叠加态」。
                    pl.setGameMode(gt);
                }

                @Override public void tp(String who, double x, double y, double z) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[玩家] 传送不了：找不到玩家 {}", who);
                        return;
                    }
                    pl.teleportTo(x, y, z);
                }

                /**
                 * 传送到指定世界（tp 的 5 参形态 / 区域坐标基准）。目标 == 他现在的世界 → 老三参
                 * {@code teleportTo(x,y,z)}；换世界 → 26.x 带 {@code ServerLevel} 的重载，原版自己发维度切换。
                 * 认不出的世界 → 记一行跳过（不掐局）。
                 */
                @Override public void tpTo(String who, String dim, double x, double y, double z) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[玩家] 传送不了：找不到玩家 {}", who);
                        return;
                    }
                    ServerLevel lv = dim == null || dim.isEmpty() ? levelFor(s) : levelByName(dim);
                    if (lv == null) {
                        TableGame.LOGGER.warn("[玩家] 传送不了：认不出的世界 \"{}\"（tp {}）", dim, who);
                        return;
                    }
                    if (lv == pl.level()) {
                        pl.teleportTo(x, y, z);
                        return;
                    }
                    pl.teleportTo(lv, x, y, z, java.util.Set.of(), pl.getYRot(), pl.getXRot(), false);
                }

                @Override public String gameModeOf(String who) {
                    var le = livingOf(s, who);
                    return le instanceof ServerPlayer sp
                            ? sp.gameMode.getGameModeForPlayer().getName() : "";   // 找不到 → 空串（读宽容）
                }

                @Override public Interp.LivingPos whereIs(String who) {
                    var le = livingOf(s, who);
                    if (le == null) return null;                 // 找不到 → 空（读的口径，脚本侧当「不在圈里」）
                    return new Interp.LivingPos(le.getX(), le.getY(), le.getZ(),
                            le.level().dimension().identifier().toString());
                }

    // ===== 动活物一族（玩家与实体共用，「谁」按名字分派）=====

                @Override public void health(String who, double value) {
                    var le = targetOf(s, who, "health");
                    // 原版 setHealth 自带 0~上限 夹取 ⇒ 想把他抬成 40 血得先 attribute(那谁, "minecraft:max_health", 40)
                    if (le != null) le.setHealth((float) value);
                }

                @Override public void heal(String who, double amount) {
                    var le = targetOf(s, who, "heal");
                    if (le != null) le.heal((float) amount);
                }

                @Override public void damage(String who, double amount, String type) {
                    var le = targetOf(s, who, "damage");
                    // 走原版伤害管线（护甲 / 抗性 / 无敌 / 死亡结算 / 击杀归属全都照旧）—— 别用 setHealth 手算掉血，
                    // 那条路会绕过一切保护与事件，作者会以为「免伤失灵」。
                    if (le == null || amount <= 0) return;
                    // type 空 = 原版 generic；写了就查我们那条伤害类型（动态注册表 ⇒ 走 level 的 registryAccess）。
                    // 认不出 → 记一行 + 退回 generic。
                    var src = le.damageSources().generic();
                    if (type != null && !type.isBlank()) {
                        String id = type.contains(":") ? type : (GameLayout.PACK_ID + ":" + type);
                        var key = net.minecraft.resources.Identifier.tryParse(id);
                        var reg = levelFor(s).registryAccess()
                                .lookup(net.minecraft.core.registries.Registries.DAMAGE_TYPE).orElse(null);
                        var dt = (key == null || reg == null) ? null : reg.getValue(key);
                        if (dt == null) {
                            TableGame.LOGGER.warn("[活物] 认不出的伤害类型：\"{}\"（退回 generic）", type);
                        } else {
                            src = new net.minecraft.world.damagesource.DamageSource(reg.wrapAsHolder(dt), null, null);
                        }
                    }
                    le.hurtServer(levelFor(s), src, (float) amount);
                }

                @Override public void effect(String who, String id, double seconds, double amplifier) {
                    var le = targetOf(s, who, "effect");
                    if (le == null) return;
                    var key = net.minecraft.resources.Identifier.tryParse(id);
                    // MOB_EFFECT 是**普通注册表**（不是 defaulted）⇒ 认不出就是 null，正好当真假判据用。
                    var eff = key == null ? null
                            : net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getValue(key);
                    if (eff == null) {
                        TableGame.LOGGER.warn("[活物] 认不出的效果 id：\"{}\"（effect {}）", id, who);
                        return;
                    }
                    int ticks = (int) Math.round(Math.max(0.0, seconds) * 20.0);   // 秒 ≤ 0 → 原版当「无限时长」
                    le.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(eff),
                            ticks, (int) Math.max(0.0, amplifier)), null);
                }

                @Override public void clearEffects(String who) {
                    var le = targetOf(s, who, "clear_effects");
                    if (le != null) le.removeAllEffects();
                }

                /**
                 * 读属性**基础值**（与上面 attribute 写入项对称，取 {@code getBaseValue()} 而非
                 * {@code getValue()}，一读一写才对得上）。带装备 / buff 修正的现值另有 {@code health_of} /
                 * {@code max_health_of}。认不出的人 / 属性 id / 属性没挂在他身上 → 0（记一行，不掐局）。
                 */
                @Override public double attributeOf(String who, String id) {
                    var le = targetOf(s, who, "attribute_of");
                    if (le == null) return 0.0;
                    var key = net.minecraft.resources.Identifier.tryParse(id);
                    var at = key == null ? null
                            : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getValue(key);
                    if (at == null) {
                        TableGame.LOGGER.warn("[活物] 认不出的属性 id：\"{}\"（attribute_of {}）", id, who);
                        return 0.0;
                    }
                    var inst = le.getAttribute(
                            net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.wrapAsHolder(at));
                    if (inst == null) {
                        TableGame.LOGGER.warn("[活物] {} 身上没有这条属性：\"{}\"", who, id);
                        return 0.0;
                    }
                    return inst.getBaseValue();
                }

                /**
                 * 读身上 buff 的效果 id 列表（原版注册名，如 {@code minecraft:speed}）。只给 id、不给等级与
                 * 剩余时长：要「有没有某个 buff」脚本自己扫（`len` + `[i]`），要等级 / 时长再加一处。
                 */
                @Override public java.util.List<String> effectsOf(String who) {
                    var le = targetOf(s, who, "effects_of");
                    if (le == null) return java.util.List.of();
                    var out = new java.util.ArrayList<String>();
                    for (var inst : le.getActiveEffects()) {
                        // ⚠ 26.x：ResourceKey 上没有 location()（老 1.21 有），现在叫 identifier()
                        String id = inst.getEffect().unwrapKey()
                                .map(k -> k.identifier().toString()).orElse("");
                        if (!id.isEmpty()) out.add(id);
                    }
                    return out;
                }

                /**
                 * 把一段 SNBT 并进那个实体（原版 {@code /data merge entity}）。用途：生物 Tags / 自定义名 /
                 * 装备 / 村民 {@code Offers}。写上了 → 1；目标找不到 / NBT 不合法 / 目标是玩家 → 0（记一行）。
                 */
                @Override public double entityData(String who, String snbt) {
                    var le = targetOf(s, who, "entity_data");
                    if (le == null) return 0.0;
                    if (le instanceof ServerPlayer) {
                        TableGame.LOGGER.warn("[活物] entity_data 不写玩家（原版 /data 同一条口径）：{}", who);
                        return 0.0;
                    }
                    net.minecraft.nbt.CompoundTag add;
                    try {
                        add = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
                    } catch (Exception ex) {
                        TableGame.LOGGER.warn("[活物] entity_data 的 NBT 读不动（{}）：{}", who, ex.getMessage());
                        return 0.0;
                    }
                    return writeEntityNbt(le, add) ? 1.0 : 0.0;
                }

                /**
                 * 替玩家开某个活物的原版交易界面（商店的「店主」用）。走原版 {@code Merchant#openTradingScreen}
                 * （它自己 openMenu + 发交易清单），不新造交易系统。认不出 / 那只不是商人 → 记一行 + 0（不掐局）。
                 */
                @Override public double openTrade(String who, String eid) {
                    ServerPlayer pl = playerByName(who);            // 开界面得是玩家（谁 = 玩家名）
                    if (pl == null) {
                        TableGame.LOGGER.warn("[活物] open_trade 开不了：找不到玩家 {}", who);
                        return 0.0;
                    }
                    var le = targetOf(s, eid, "open_trade");
                    if (le == null) return 0.0;
                    if (!(le instanceof net.minecraft.world.item.trading.Merchant m)) {
                        TableGame.LOGGER.warn("[活物] open_trade：{} 不是商人（没有交易）", eid);
                        return 0.0;
                    }
                    if (m.getTradingPlayer() != null) {   // 已经在跟别人交易：原版 mobInteract 也这么挡
                        TableGame.LOGGER.warn("[活物] open_trade：{} 正在跟别人交易", eid);
                        return 0.0;
                    }
                    m.setTradingPlayer(pl);                          // 原版先设交易对手，再开屏
                    m.openTradingScreen(pl, le.getDisplayName(), 1); // 1 = 界面上那排骑士/石匠小圆点级别
                    return 1.0;
                }

                @Override public void attribute(String who, String id, double value) {
                    var le = targetOf(s, who, "attribute");
                    if (le == null) return;
                    var key = net.minecraft.resources.Identifier.tryParse(id);
                    var at = key == null ? null
                            : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getValue(key);
                    if (at == null) {
                        TableGame.LOGGER.warn("[活物] 认不出的属性 id：\"{}\"（attribute {}）", id, who);
                        return;
                    }
                    var inst = le.getAttribute(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.wrapAsHolder(at));
                    if (inst == null) {          // 这条属性没挂在他身上（原版按实体类型挂属性）
                        TableGame.LOGGER.warn("[活物] {} 身上没有这条属性：\"{}\"", who, id);
                        return;
                    }
                    inst.setBaseValue(value);    // 基础值：装备 / buff 那层修正照旧另算
                }

                @Override public void food(String who, double value) {
                    var le = targetOf(s, who, "food");
                    if (le == null) return;
                    if (le instanceof net.minecraft.world.entity.player.Player p) {
                        p.getFoodData().setFoodLevel((int) net.minecraft.util.Mth.clamp(value, 0.0, 20.0));
                    } else {
                        TableGame.LOGGER.warn("[活物] food 只对玩家有意义：{} 不是玩家", who);
                    }
                }

                @Override public void xp(String who, double points) {
                    var le = targetOf(s, who, "xp");
                    if (le == null) return;
                    if (le instanceof net.minecraft.world.entity.player.Player p) {
                        p.giveExperiencePoints((int) points);      // **加**（原版 /xp add 口径，同 give 的「加」）
                    } else {
                        TableGame.LOGGER.warn("[活物] xp 只对玩家有意义：{} 不是玩家", who);
                    }
                }

                @Override public void fly(String who, boolean on) {
                    var le = targetOf(s, who, "allow_fly");
                    if (!(le instanceof ServerPlayer sp)) {
                        if (le != null) TableGame.LOGGER.warn("[活物] allow_fly 只对玩家有意义：{} 不是玩家", who);
                        return;
                    }
                    // 26.x/NeoForge 的正路是**属性** neoforge:creative_flight（0/1），不是 abilities.mayfly
                    // 那个字段（源码里标了 @Deprecated）；属性是 syncable 的 ⇒ 客户端也会知道能飞了。
                    var inst = sp.getAttribute(net.neoforged.neoforge.common.NeoForgeMod.CREATIVE_FLIGHT);
                    if (inst != null) inst.setBaseValue(on ? 1.0 : 0.0);
                    if (!on && sp.getAbilities().flying) sp.getAbilities().flying = false;  // 关掉时别留他在空中飘
                    sp.onUpdateAbilities();      // 能力包重发（只发状态、不覆盖上面设的属性）
                }

                @Override public void invulnerable(String who, boolean on) {
                    var le = targetOf(s, who, "invulnerable");
                    if (le != null) le.setInvulnerable(on);      // 原版标记：伤害管线直接放过他
                }

                /**
                 * 这个人死亡不掉落（0/1），只对玩家有意义（账上实体传进来记一行跳过）。
                 * 关掉时连他那份待回填一起丢 —— 否则「关掉 → 又死一次」会把上一次的旧背包还回来。
                 */
                @Override public void keepItems(String who, boolean on) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[活物] keep_items 只对玩家有意义：找不到玩家 {}", who);
                        return;
                    }
                    Session ss = sessionOf(pl);
                    if (ss == null) {
                        TableGame.LOGGER.warn("[活物] keep_items 认不出 {} 在哪一局（不在局里 → 不管）", who);
                        return;
                    }
                    if (on) {
                        ss.keepItems.add(pl.getUUID());
                    } else {
                        ss.keepItems.remove(pl.getUUID());
                        ss.deathStash.remove(pl.getUUID());
                    }
                }

                @Override public void respawn(String who, double x, double y, double z) {
                    ServerPlayer pl = playerByName(who);
                    if (pl == null) {
                        TableGame.LOGGER.warn("[活物] 设不了重生点：找不到玩家 {}", who);
                        return;
                    }
                    respawnAt(pl, x, y, z);
                }

                @Override public void fire(String who, double seconds) {
                    var le = targetOf(s, who, "fire");
                    if (le != null) le.igniteForTicks((int) Math.round(Math.max(0.0, seconds) * 20.0));   // 0 = 灭火
                }

                /**
                 * 放音效给那个人听：走原版 {@code /playsound} 那条路 —— 直接构造一个 {@code SoundEvent}
                 * （不查注册表，所以数据包加的音效也认），以 ClientboundSoundPacket 发给他一个人。位置取他自己那儿。
                 * id 认不出 / 找不到人 → 记一行，什么都不发（不掐局）。
                 */
                @Override public void sound(String who, String id, double volume, double pitch) {
                    ServerPlayer pl = playerByName(who);
                    String sid = id == null ? "" : id.trim();
                    var key = sid.isEmpty() ? null : net.minecraft.resources.Identifier.tryParse(
                            sid.contains(":") ? sid : ("minecraft:" + sid));
                    if (pl == null || key == null) {
                        TableGame.LOGGER.warn("[音效] 放不了：{} 的「{}」", who, sid);
                        return;
                    }
                    pl.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                            net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(key)),
                            net.minecraft.sounds.SoundSource.MASTER,
                            pl.getX(), pl.getY(), pl.getZ(),
                            (float) volume, (float) pitch,
                            pl.level().getRandom().nextLong()));
                }

                @Override public void face(String who, double x, double y, double z) {
                    var le = targetOf(s, who, "face");
                    if (le == null) return;
                    // 眼睛朝那一点看：玩家 = 转头（相机也转），生物 = 头与身子一起转（原版 /tp facing 用的就是它）
                    le.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                            new net.minecraft.world.phys.Vec3(x, y, z));
                }

                @Override public void knockback(String who, double x, double y, double z) {
                    var le = targetOf(s, who, "knockback");
                    if (le != null) le.push(x, y, z);            // 加一笔速度（瞬间推力），不是「设速度」
                }

                @Override public void ride(String who, String target) {
                    var le = targetOf(s, who, "ride");
                    var seat = targetOf(s, target, "ride 的目标");
                    // 26.x 是三参：force = 忽略「这位置坐不下/实体拒绝」这种校验；最后那个 = 要不要发事件与触发器
                    if (le != null && seat != null && le != seat) le.startRiding(seat, true, true);
                }

                // 放原版生物，立刻生效（原来攒批 ⇒ 脚本紧接着写它的 NBT 时那只还没进账 ⇒ entity_data 返回 0）。
                @Override public void spawnMob(String name, double x, double y, double z) {
                    spawnOne(s, name, x, y, z);
                }

                @Override public double healthOf(String who) {
                    var le = livingOf(s, who);
                    if (le == null) {
                        TableGame.LOGGER.warn("[活物] health_of 找不到「{}」：既不是在线玩家，也不在本局实体账上", who);
                        return 0;
                    }
                    return le.getHealth();
                }

                @Override public double maxHealthOf(String who) {
                    var le = livingOf(s, who);
                    if (le == null) {
                        TableGame.LOGGER.warn("[活物] max_health_of 找不到「{}」：既不是在线玩家，也不在本局实体账上", who);
                        return 0;
                    }
                    return le.getMaxHealth();
                }
            });
        } catch (Ast.ScriptError e) {
            // 解释器构造期也会挑错：最典型 = 脚本声明的非人席位与在座真人重名（两人会共用一份槽位）。
            // 这里还没 sessions.add，直接 fail 收场，不留半成品会话。
            if (tell != null) fail(tell, "这份档的脚本有问题 —— " + e.getMessage());
            else TableGame.LOGGER.error("[对局] 脚本有问题（续跑那条路）：{}", e.getMessage());
            return false;
        }
        return true;
    }

    /** 玩家主动中断（仅限局内玩家）。 */
    public void abort(ServerPlayer p) {
        Session s = sessionOf(p);
        if (s == null) {
            fail(p, "你没有在局里");
            return;
        }
        endSession(s, "[对局] 已被 " + p.getName().getString() + " 中断");
    }

    /**
     * 玩家进服务器：他此刻在场（在某局的区域里）→ 把整份状态补给他（客户端只在开局 / 加入 / 状态变化时
     * 收到对局状态）。不在场不用补：他一进圈 {@link #autoSpectate} 发 {@code on join} 就把屏推给他。
     */
    public void playerJoined(ServerPlayer p) {
        Session s = sessionOf(p);
        if (s == null) return;                                    // 不在任何一局的区域里：没得补
        pushGameStateTo(s, p);
    }

    // ===== 游戏台（对局台方块）：接人 =====

    /** 这个人是不是在「这一台子 16 格内」（防隔空开台子屏；指令开局的老局不设限）。 */
    private boolean withinTable(Session s, ServerPlayer p) {
        if (s.tablePos == null) return true;                     // 指令开局的老局：不设限
        if (s.tablePos.dimension() != p.level().dimension()) return false;
        return p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(s.tablePos.pos()))
                <= SEAT_RADIUS * SEAT_RADIUS;
    }

    /**
     * 把这一局此刻的状态补给一个人：舞台定义 + 首份快照 + 画板观看/全量。共用者：进服务器（{@link #playerJoined}）
     * · 点【进入游戏界面】（{@link #joinAsSpectator}）。⚠ 这里不强制开全屏屏（要开屏的地方自己补一条开屏包）。
     */
    private void pushGameStateTo(Session s, ServerPlayer p) {
        if (s.def != null) sendStageTo(s, p, s.def, true);        // 舞台定义 + 这一帧的快照
        if (s.stageBoardKey != null) {
            BoardManager bm = BoardManager.get();
            if (bm != null) {
                bm.stageWatch(s.stageBoardKey, p.getUUID());
                bm.sendStageBoardFull(s.stageBoardKey);           // 他手上没有已有笔迹 → 补一次全量
            }
        }
        broadcastState(s);
    }

    /**
     * 点【进入游戏界面】：把这一局的界面给这个人。「入不入局」不由这一下决定 —— 谁在场谁收
     * （{@link #recipients}，判据 = 他此刻在不在脚本声明的区域里），进圈由 {@link #autoSpectate} 发
     * {@code on join}。这里只管把屏给他（+ 防隔空的距离闸门）。
     */
    public void joinAsSpectator(ServerPlayer p, Session s) {
        Session other = sessionOf(p);
        if (other != null && other != s) { fail(p, "你已经在另一局里了"); return; }
        if (!withinTable(s, p)) { fail(p, "离那台游戏台太远了（走过去再点）"); return; }
        pushGameStateTo(s, p);
        // 明确开屏：他是从游戏台点【进入游戏界面】来的，屏就该让给舞台（不等脚本 show 那套）
        PacketDistributor.sendToPlayer(p, new HostPackets.StageUiPayload(true));
    }

    /**
     * 玩家退出服务器：开局就在的那几个人走了 = 整局终止；其余人走了 = 只摘账，局照跑。能终止一局的是
     * foundingMembers 或世界玩法的局（常驻，谁走都不终止）。{@code on leave} 统一由在场边沿发，这里不重复发。
     */
    public void playerLoggedOut(ServerPlayer p) {
        Session s = sessionOf(p);
        if (s == null) s = sessionWithPresent(p.getUUID());    // 走开了 / 在别的维度才掉线：按在场账兜底
        if (s == null) return;
        // 圈边账里摘掉他（每条区域都要摘）：回来时重新算一次「刚进圈」。
        for (Set<UUID> in : s.insideArea.values()) in.remove(p.getUUID());
        // ⚠ 在场边沿账（presentLast）**偏偏不能在这里摘**：autoSpectate 正是靠它看出
        // 「他这一 tick 不在场了」才发得出 on leave —— 摘在这儿 = 掉线那次 on leave 永远没了。
        // 踩格账可以摘（他不在线，记着上一格没意义）。
        s.lastStep.remove(p.getUUID());
        // 那两条按人账一样要摘：不掉落开关 + 待回填的背包快照（人不在线了，留着只会变成孤儿背包）。
        s.keepItems.remove(p.getUUID());
        s.deathStash.remove(p.getUUID());
        s.ghostKey.remove(p.getUUID());   // 幽灵去重账：人不在线了，下次发包别被旧账挡住
        boolean isSeated = s.world || s.foundingMembers.contains(p.getUUID());
        if (!isSeated) return;                                 // 后来才进圈的人：只摘账，局照跑
        String name = p.getName().getString();
        if (s.world) {
            // 世界玩法：退了也不离席 —— 这一局是常驻的。
            say(s, "[对局] " + name + " 掉线 —— 席位留着，回来接着玩");
            lastSaveMs = 0;                                   // 大状态变化：立刻落盘
            return;
        }
        // 处置口径：脚本顶层 offline stop|wait|skip（选择权给作者）。
        String mode = s.eng == null ? "stop" : s.eng.offlineMode();
        // 世界玩法的局同一条口径：它是「存档的玩法」，不该因为一个人下线就整局重来。
        // ⚠ 作者写 wait 仍然照挂起（「人不在就别跑」比常驻更具体，按它办）—— 老规矩没变。
        if (mode.equals("stop") && s.eng != null && (s.eng.resident() || s.world)
                && !s.foundingMembers.contains(p.getUUID())) {
            mode = "skip";
        }
        if (mode.equals("wait")) {
            // 整局挂起：时间冻住，等人回来点【继续游戏】（离线不推进）。写在 resident 局上也一样生效
            // —— 作者写了 wait 就是「人不在就别跑」，比 resident 更具体，按它办。
            s.paused = true;
            say(s, "[对局] " + name + " 掉线 —— 本局已挂起（回来点【继续游戏】接着跑）");
            lastSaveMs = 0;                                      // 大状态变化：立刻落盘
            return;
        }
        if (mode.equals("skip")) {
            // 他退席、局照跑（老 resident 局的行为）：槽位保留等他回来。
            say(s, "[对局] " + name + " 掉线退席（局继续）");   // 脚本的名单在 on leave 里自己 del(players, …)
            return;
        }
        endSession(s, "[对局] " + name + " 离开了，本局终止");
    }

// ===== 世界玩法（存档级）=====

    /**
     * 本存档在跑的世界局（没有 → null）。
     *
     * <p>一个存档只服务一款游戏 ⇒ 世界局最多一个，线性找第一个就够。
     */
    private Session worldSession() {
        for (Session s : sessions) {
            if (s.world) return s;
        }
        return null;
    }

    /**
     * 开了世界玩法就开局：没有游戏台、没有发起人，读存档时开一次，而且无人也跑（世界局不挂起）。
     * 「进圈 = 进局」由 {@link #autoSpectate} 每 tick 发一次 {@code on join}；已在场的人手上屏由
     * {@link #playerJoined} 补。
     */
    private void startWorldIfNeeded() {
        if (worldGame.isEmpty() || worldSession() != null) return;
        GameManager gm = GameManager.get();
        if (gm == null || gm.load(worldGame) == null) {
            TableGame.LOGGER.warn("[世界玩法] 找不到游戏定义《{}》—— 用 /tablegame world <游戏名> 换一款，或 world off 关掉",
                    worldGame);
            return;
        }
        startInternal(null, worldGame, null, true);
    }

    /** {@code /tablegame world <游戏名>}：给**本存档**开这道玩法（绑定落在存档里；马上开局）。 */
    public void worldEnable(CommandSourceStack src, String gameName) {
        String name = gameName == null ? "" : gameName.trim();
        GameManager gm = GameManager.get();
        if (name.isEmpty() || gm == null || gm.load(name) == null) {
            reply(src, "[世界玩法] 找不到游戏定义: " + name);
            return;
        }
        worldGame = name;
        if (worldStore != null) worldStore.set(name);
        Session old = worldSession();                 // 换绑 = 旧的那一局收掉（一个存档只服务一款）
        if (old != null) endSession(old, "[对局] 《" + old.gameName + "》已被本存档换成的《" + name + "》取代");
        startWorldIfNeeded();
        reply(src, "[世界玩法] 本存档已开启：《" + name + "》"
                + (worldSession() != null ? "（已开局）" : "（没能开局：看服务端日志）"));
    }

    /** {@code /tablegame world off}：关掉（正跑的那一局一起收）。 */
    public void worldDisable(CommandSourceStack src) {
        String was = worldGame;
        worldGame = "";
        if (worldStore != null) worldStore.clear();
        Session s = worldSession();
        if (s != null) endSession(s, "[对局] 世界玩法已关闭");
        reply(src, was.isEmpty() ? "[世界玩法] 本存档本来就没开" : "[世界玩法] 已关闭（原来是《" + was + "》）");
    }

    /** {@code /tablegame world}：这个存档绑的是哪款 · 局在不在跑 · 几人在座。 */
    public void worldStatus(CommandSourceStack src) {
        Session s = worldSession();
        String state;
        if (worldGame.isEmpty()) {
            state = "没开（/tablegame world <游戏名> 开）";
        } else if (s == null) {
            state = "《" + worldGame + "》· 没在跑（看服务端日志）";
        } else {
            state = "《" + worldGame + "》· " + (s.paused ? "已挂起" : "在跑")
                    + " · 局内 " + recipients(s).size() + " 人";
        }
        reply(src, "[世界玩法] 本存档：" + state);
    }

    private static void reply(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * 编辑通过（校验过、已落盘）→ 立刻在跑着的那一局上生效，只挂世界玩法的局。
     *
     * <p>做法：先取跑着那局的状态（{@link Interp#snapshot}），用新脚本走 {@link #buildEngine} 同一份注入
     * 重建引擎，再把状态接回去（{@link Interp#apply}）。接不上就什么都不换（宁可改动晚一局，也不重置进度）。
     *
     * <p>⚠ 顺序不能反：先 snapshot 再建新引擎（buildEngine 会换掉 {@code s.eng}，旧状态就没了）。
     *
     * @return 给作者看的一句话（空串 = 这一款没在跑，不吭声）
     */
    public String reloadGame(String gameName) {
        Session s = worldSession();
        if (s == null || s.def == null || !s.gameName.equals(gameName)) return "";
        GameManager gm = GameManager.get();
        GameDefinition def = gm == null ? null : gm.load(gameName);
        if (def == null || def.script() == null || def.script().isBlank()) {
            return "[对局] 改动没生效：读不到这份档";
        }
        Ast.Script script;
        try {
            script = Parser.parseForPlay(def.script());     // 与开局同一道门（没有 on 事件 → 开不了局）
        } catch (Ast.ScriptError e) {
            return "[对局] 改动没生效（脚本开不了局）：" + e.getMessage();
        }
        Interp old = s.eng;
        Interp.Snapshot snap = old == null ? null : old.snapshot();
        if (snap == null) return "[对局] 改动没生效：这一局现在接不上（已结束 / 已停住）";
        if (!buildEngine(s, script, recipientNames(s), null)) {
            s.eng = old;                                    // 新脚本有问题：原样退回
            return "[对局] 改动没生效：脚本有问题（看服务端日志）";
        }
        if (!s.eng.apply(snap, System.currentTimeMillis())) {
            s.eng = old;                                    // 接不上：一个值都不动，照旧跑
            return "[对局] 这次改动引擎接不上（动了结构）—— 下一局生效";
        }
        s.def = def;
        s.fingerprint = fingerprint(def.script());
        s.stagePushed.clear();                               // 舞台缓存作废：换脚本了，必须重推
        s.pickIds.clear();
        sendStage(s, def, true);
        broadcastState(s);
        lastSaveMs = 0;                                      // 状态变了：下一 tick 立刻落盘
        return "[对局] 《" + gameName + "》改动已生效（进度原样接着跑）";
    }

    // ===== 游戏台（dealer 方块）=====

    /**
     * 台级共享状态：这台子选中了哪款游戏（没选 = 不在表里）。放服务端（台子是世界里的一台实体，客户端只是
     * 视图；放本地会「各开一局」）。{@code ponytail:} 方块被拆了不清；要长期运行再加 BlockEvent.Break 清理。
     */
    private final Map<BlockPos, String> tableSelected = new java.util.HashMap<>();

    /**
     * 某一台上要显示的一行提示（只在内存里，重开一局就清）。来源：重进存档时那一局接不上
     * （脚本改过 / 快照对不上）—— 让玩家在台子上看见为什么，而不是只在服务端日志里。
     */
    private final Map<BlockPos, String> tableNotes = new java.util.HashMap<>();

    /** 这台子正在跑的那一局（没在跑 → null）。 */
    public Session tableSession(ServerPlayer viewer, BlockPos pos) {
        for (Session s : sessions) {
            if (s.tablePos == null) continue;
            if (s.tablePos.dimension() != viewer.level().dimension()) continue;
            if (s.tablePos.pos().equals(pos)) return s;
        }
        return null;
    }

    /** 打开台子：把台状态发给这个人（客户端收到就开屏；已选好的游戏照原样带过去）。 */
    public void dealerOpen(ServerPlayer sp, BlockPos pos) {
        // 顺序要紧：先状态（客户端凭它开屏），再列表（列表页要用到，含简介 meta.desc——
        // 客户端收到列表包时屏已经开着，走「就地刷新」而不是「屏为空 → 开编辑器菜单」那条岔路）
        PacketDistributor.sendToPlayer(sp, tableState(sp, pos));
        GameManager gm = GameManager.get();
        if (gm != null) gm.sendGamesList(sp);
    }

    /** 【进入游戏】／换选／取消选择（gameName 空串 = 把这台的选中清掉，回到列表页）。 */
    public void dealerSelect(ServerPlayer sp, BlockPos pos, String gameName) {
        if (gameName == null || gameName.isEmpty()) {
            tableSelected.remove(pos);                            // 重新选择 = 台子回到「还没选」
            pushTableState(sp, pos);
            return;
        }
        GameManager gm = GameManager.get();
        if (gm == null || gm.load(gameName) == null) {
            fail(sp, "找不到游戏定义: " + gameName);
            return;
        }
        tableSelected.put(pos.immutable(), gameName);
        pushTableState(sp, pos);
    }

    /**
     * 【运行】/【继续游戏】：用这台选中的游戏开局（游戏名从服务端台状态取）。同一台已在跑 → 拒绝。
     * 挂起的那一局（重进存档后的样子）在这颗按钮上就是【继续游戏】：不发新的，接着跑（{@link #resume}）；
     * 客户端按台状态里的 {@code paused} 把字换掉。
     */
    public void dealerRun(ServerPlayer sp, BlockPos pos) {
        tableNotes.remove(pos);                              // 玩家动手了：上一次那条「接不上」的提示到此为止
        Session running = tableSession(sp, pos);
        if (running != null && running.paused) {
            resume(running, sp);
            pushTableState(sp, pos);
            return;
        }
        if (running != null) {
            fail(sp, "这台已经在跑《" + running.gameName + "》了（先在总览页【中断游戏】）");
            return;
        }
        String gameName = tableSelected.get(pos);
        if (gameName == null || gameName.isEmpty()) {
            fail(sp, "这台还没选游戏");
            return;
        }
        startAtTable(sp, gameName, net.minecraft.core.GlobalPos.of(sp.level().dimension(), pos));
        pushTableState(sp, pos);
    }

    /** 【进入游戏界面】：把这一局的屏/状态给他（没在跑 → 回话）。「入局」不由这一下决定，见 joinAsSpectator。 */
    public void dealerEnter(ServerPlayer sp, BlockPos pos) {
        Session s = tableSession(sp, pos);
        if (s == null) {
            fail(sp, "这台还没开局（先在总览页【运行】）");
            return;
        }
        joinAsSpectator(sp, s);
        pushTableState(sp, pos);
    }

    /** 【中断游戏】：收掉这台那一局（只有在场的人能中断）。 */
    public void dealerAbort(ServerPlayer sp, BlockPos pos) {
        Session s = tableSession(sp, pos);
        if (s == null) {
            fail(sp, "这台没有在跑的局");
            return;
        }
        if (sessionOf(sp) != s) {
            fail(sp, "你没参与这一局（走到台前 / 点【进入游戏界面】）");
            return;
        }
        endSession(s, "[对局] 已被 " + sp.getName().getString() + " 中断");
        pushTableState(sp, pos);
    }

    /** 台状态包（按收件人裁一个 joined：他此刻在不在这一局的区域里）。 */
    private DealerPackets.StatePayload tableState(ServerPlayer viewer, BlockPos pos) {
        Session s = tableSession(viewer, pos);
        return new DealerPackets.StatePayload(pos.immutable(),
                tableSelected.getOrDefault(pos, ""),
                s == null ? "" : s.gameName,
                s == null ? List.of() : recipientNames(s),
                s != null && sessionOf(viewer) == s,
                s != null && s.paused,
                tableNotes.getOrDefault(pos, ""));
    }

    /** 把台状态推给「台子 16 格内的人」——开着这台屏的人就地刷新，没开的人无感。 */
    private void pushTableState(ServerPlayer actor, BlockPos pos) {
        DealerPackets.StatePayload st0 = tableState(actor, pos);
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            if (pl.level().dimension() != actor.level().dimension()) continue;
            if (pl.distanceToSqr(center) > SEAT_RADIUS * SEAT_RADIUS) continue;
            PacketDistributor.sendToPlayer(pl, new DealerPackets.StatePayload(st0.pos(), st0.selected(),
                    st0.running(), st0.seats(), tableState(pl, pos).joined(), st0.paused(), st0.note()));
        }
    }

    // ===== 输入 =====

    /**
     * 有人从界面提交（{@code boxId} = 来自哪个框；按钮 / 框没起名 ⇒ 空串）。全屏舞台 / HUD 的输入框
     * 回车 → 客户端提交包 → 这里。客户端只说得出「哪个框」（宿主展开给的 id），身份（组件的资产名）
     * 只有宿主知道 —— 这里拿展开时记的账 {@code pickIds} 换成身份值交给脚本（{@code input_box}）。
     */
    public void input(ServerPlayer p, String actionId, String text, String boxId) {
        Session s = sessionOf(p);
        if (s == null) {
            fail(p, "你没有在局里");
            return;
        }
        String in = text == null || text.isBlank() ? actionId : text;
        if (!s.eng.acceptInput(p.getName().getString(), in, boxIdentity(s, p, boxId))) {
            fail(p, s.eng.isHalted()
                    ? "这一局已经被**脚本报错停掉了** —— 往上看那条「⚠ 第 N 行」，改好脚本、重开局就能接着玩"
                    : "现在提交不了（对局已结束，或这份脚本没有 on input）");
        }
    }

    /**
     * 「界面上的框 id」→ 身份值（= 那个组件的资产名），输入框用。没起名（展开给的 -1）/ 没这个框
     * （老客户端 / 手搓包）⇒ 空串。文本形态走 {@link com.tablegame.script.Builtins#text}，与脚本里
     * {@code text()} 一个口径（数字不带小数点尾巴）。
     */
    private static String boxIdentity(Session s, ServerPlayer p, String boxId) {
        if (boxId == null || boxId.isEmpty()) return "";
        java.util.Map<String, Object> ids = s.pickIds.get(p.getUUID());
        Object id = ids == null ? null : ids.get(boxId);
        if (id == null) return "";
        if (id instanceof Number n && n.doubleValue() == -1) return "";    // 没起名
        return com.tablegame.script.Builtins.text(id);
    }

    /**
     * 有人点了可点的一份（舞台去模板化）：把「框 id」换成<b>身份值</b>（展开时记的账），跑 {@code on pick}。
     *
     * <p>客户端只会说「点了哪个框」—— 身份值（点了这一份是谁）只有宿主知道（它展开时记的）。
     */
    public void pick(ServerPlayer p, String boxId) {
        Session s = sessionOf(p);
        if (s == null) {
            fail(p, "你没有在局里");
            return;
        }
        java.util.Map<String, Object> ids = s.pickIds.get(p.getUUID());
        if (ids == null || !ids.containsKey(boxId)) {
            fail(p, "这一下点晚了（界面已经换过一版）");           // 陈旧点击：展开重排过，框 id 对不上
            return;
        }
        if (!s.eng.acceptPick(p.getName().getString(), ids.get(boxId))) {
            fail(p, "现在点不了（对局已结束，或这份脚本没有 on pick）");
        }
    }

    /**
     * 文本消息的点击回投：{@code /tablegame pick <标记>} 的落点。{@code say_mark} 发出的消息挂着
     * {@code runCommand("tablegame pick 标记")}，点它 → 命令送回 → 直接喂给 {@link Interp#acceptPick}
     * （与舞台 click 同一入口）：跑 {@code on pick}，{@code pick} = 标记。标记不经身份账本。
     */
    public void pickMark(ServerPlayer p, String mark) {
        Session s = sessionOf(p);
        if (s == null) {
            fail(p, "你没有在局里");
            return;
        }
        if (!s.eng.acceptPick(p.getName().getString(), mark)) {
            fail(p, "现在点不了（对局已结束，或这份脚本没有 on pick）");
        }
    }

    /**
     * 打开 / 收掉全屏舞台界面（{@code /tablegame ui}）。服务端只做「他在不在局里」的粗判 —— 真的要开屏得
     * 客户端自己来（形态与布局都在它的舞台缓存里），所以这里只发一条「开关一下」的包。
     */
    public void ui(ServerPlayer p, boolean open) {
        if (open && sessionOf(p) == null) {
            fail(p, "你不在局里（先 /tablegame start <游戏名> 开局）");
            return;
        }
        PacketDistributor.sendToPlayer(p, new HostPackets.StageUiPayload(open));
    }

    // ===== 内部 =====

    // ===== 局内临时画板（「画板（本局）」框：玩家零预备，开局自动建一块） =====

    /**
     * 画者入口（{@code /tablegame draw}）：打开本局的临时画板（普通的画板屏，只是不落盘、一局一块）。
     * 不打开屏幕的人也能实时看到笔迹 —— 那走 HUD 的 art 框（舞台观看登记）。
     */
    public void openStageBoard(ServerPlayer p) {
        Session s = sessionOf(p);
        if (s == null) {
            fail(p, "你没有在局里");
            return;
        }
        if (s.stageBoardKey == null) {
            fail(p, "这一局没有画板（在编辑器【界面】里加一块「画板（本局）」框）");
            return;
        }
        BoardManager bm = BoardManager.get();
        if (bm == null) {
            fail(p, "画板总管未就绪");
            return;
        }
        bm.openStageBoard(p, s.stageBoardKey);
    }

    /**
     * 这块临时画板属于某一局、且该玩家**在场** + 是脚本当前指定的画者？（防伪造包：只有局内人能改）
     * 画板总管的写操作守卫会问到这里——「谁在局里」由主持人现算（C 批：没有成员表了）。
     */
    public boolean canEditStageBoard(String key, String playerName) {
        for (Session s : sessions) {
            // 在场只是一半：**还要是脚本当前指定的画者**（may_draw 设的）——
            // 猜的人在「猜」的阶段碰不到画板，就是靠这一句。
            if (key.equals(s.stageBoardKey)) {
                return isPresent(s, playerName) && s.eng != null && playerName.equals(s.eng.drawer());
            }
        }
        return false;
    }

    /** 这个玩家在任意一局里？（画板 canWrite 的粗判，只用于界面 canEdit 标记） */
    public boolean inAnyStage(String playerName) {
        for (Session s : sessions) {
            if (isPresent(s, playerName)) return true;
        }
        return false;
    }

    /**
     * 本局要不要建临时画板：只看脚本里有没有 {@code paint}（{@code eng.usesPaint()}）。
     * 舞台由脚本画后，档里那份静态组件树与老 {@code StageUi.boxes} 探测再也不会有人填。
     */
    private static boolean hasArtBox(Interp eng) {
        return eng != null && eng.usesPaint();
    }

    /** 开局：建本局临时画板 + 把此刻在场的人登记成观看者 + 发一次全量（HUD 拿到就画）。 */
    private void openStageBoardFor(Session s, GameDefinition def) {
        if (!hasArtBox(s.eng)) return;
        BoardManager bm = BoardManager.get();
        if (bm == null) return;
        s.stageBoardKey = bm.createStageBoard(def.name());
        for (ServerPlayer pl : recipients(s)) bm.stageWatch(s.stageBoardKey, pl.getUUID());
        bm.sendStageBoardFull(s.stageBoardKey);
    }

    /** 收掉本局临时画板（结束/开不了局时调；幂等）。 */
    private void closeStageBoard(Session s) {
        if (s.stageBoardKey == null) return;
        BoardManager bm = BoardManager.get();
        if (bm != null) bm.disposeStageBoard(s.stageBoardKey);
        s.stageBoardKey = null;
    }

    /**
     * {@code on break} 的合并入口：两个挂点都挂（{@code BreakBlockEvent} 创造也发、{@code BlockDropsEvent}
     * 有完整掉落清单）。同一格 200ms 窗内只发一次；带 drops 的那次允许再发一次补齐（脚本拿 {@code drops}）。
     */
    public void blockBroken(ServerPlayer p, BlockPos pos, net.minecraft.world.level.block.state.BlockState st,
                            net.minecraft.world.item.ItemStack tool, java.util.List<String> drops) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return;
        if (p.level() != levelFor(s)) return;                          // 不在棋盘世界：不发
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        long now = System.currentTimeMillis();
        Long last = s.breakFired.get(key);
        boolean hasDrops = drops != null;
        if (last != null && now - last < 200 && !hasDrops) return;     // 窗内的普通重复：丢
        s.breakFired.put(key, now);
        // 账会攒（挖很多方块）⇒ 过 512 条就把过期的扫掉
        // (ponytail: 全扫一遍 O(n)；真到了卡的程度再换 LRU)
        if (s.breakFired.size() > 512) s.breakFired.entrySet().removeIf(e -> now - e.getValue() > 5_000);
        // 记一行进日志文件（`Interp.log` 只进内存 trace，排查时看不见）。
        TableGame.LOGGER.info("[破坏] {} 挖了 ({},{},{}) {} 手={} 掉落={}",
                p.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), blockIdOf(st),
                handIdOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                drops == null ? "（无清单那一次）" : drops);
        s.eng.acceptBreak(p.getName().getString(), pos.getX(), pos.getY(), pos.getZ(),
                blockIdOf(st), handIdOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                handAssetOf(p, net.minecraft.world.InteractionHand.MAIN_HAND), drops);
    }

    /** {@code on place}：方块被放置（挂在 protect 兜底那条已订阅的 EntityPlaceEvent 上）。 */
    public void blockPlaced(ServerPlayer p, BlockPos pos, net.minecraft.world.level.block.state.BlockState placed) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return;
        if (p.level() != levelFor(s)) return;
        TableGame.LOGGER.info("[放置] {} 放了 ({},{},{}) {}", p.getName().getString(),
                pos.getX(), pos.getY(), pos.getZ(), blockIdOf(placed));
        s.eng.acceptPlace(p.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), blockIdOf(placed));
    }

// ---------- 原版战利品表（掉落交回原版）----------

    /**
     * 方块被破坏那一下：有表就交给原版算掉落（时运 / 精准采集 / 附魔 / 谓词 / 数量范围都是原版的事）。
     * 表 = 项目档 {@code loot/<表名>.json}（原版 loot_table 格式原样存）；方块挂哪张表 = 脚本对象声明写
     * {@code loot "表名"}（按基底原版 id 认）。没挂表 = 一个字节都不动。
     *
     * <p>前置五道（任一不满足 = 不介入）：创造模式 · 服务端/这一局没就绪 · 不在本局棋盘世界 ·
     * 不是本局成员 · 没挂表/表读不出来。
     *
     * @return true = 原版那份掉落清单已经被换掉
     */
    public boolean applyLootTable(ServerPlayer p, ServerLevel lv, BlockPos pos,
            net.minecraft.world.level.block.state.BlockState st, net.minecraft.world.item.ItemStack tool,
            java.util.List<net.minecraft.world.entity.item.ItemEntity> drops) {
        if (p.getAbilities().instabuild) return false;
        Session s = sessionOf(p);
        if (s == null || s.eng == null || s.def == null) return false;
        if (p.level() != levelFor(s)) return false;
        if (!isPresent(s, p.getName().getString())) return false;
        String table = tableFor(s, "block", blockIdOf(st));
        if (table == null) return false;
        var t = lootTableOf(s, table, lv);
        if (t == null) return false;
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(lv)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, st)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        net.minecraft.world.phys.Vec3.atCenterOf(pos))
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,
                        tool == null ? net.minecraft.world.item.ItemStack.EMPTY : tool)
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, p)
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY,
                        lv.getBlockEntity(pos))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK);
        drops.clear();                                                 // 表在 = 接管（原版那批不再落地）
        for (net.minecraft.world.item.ItemStack it : t.getRandomItems(params, lv.getRandom())) {
            if (it.isEmpty()) continue;
            drops.add(new net.minecraft.world.entity.item.ItemEntity(lv,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, withAsset(s, it)));
        }
        return true;
    }

    /**
     * 生物死了那一下：有表就交给原版算（抢夺 / 时运 / 谓词同理）。表头按基底原版实体 id 认
     * （{@code entity 名 { base "minecraft:zombie" loot "表名" } }）。只认本局账上那只（野生的死不惊动表）。
     *
     * @return true = 原版那份掉落清单已经被换掉
     */
    public boolean applyLootTable(net.minecraft.world.entity.Entity dead,
            net.minecraft.world.damagesource.DamageSource src,
            java.util.Collection<net.minecraft.world.entity.item.ItemEntity> drops) {
        if (!(dead.level() instanceof ServerLevel lv)) return false;
        Session s = null;
        for (Session x : sessions) {                                   // 认死亡那刻还在账上的那只
            if (x.mobEntities.containsValue(dead)) { s = x; break; }
        }
        if (s == null || s.eng == null || s.def == null) return false;
        if (dead.level() != levelFor(s)) return false;
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType());
        String table = tableFor(s, "entity", key == null ? "" : key.toString());
        if (table == null) return false;
        var t = lootTableOf(s, table, lv);
        if (t == null) return false;
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(lv)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, dead)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, dead.position())
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DAMAGE_SOURCE, src)
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ATTACKING_ENTITY,
                        src.getEntity())
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DIRECT_ATTACKING_ENTITY,
                        src.getDirectEntity())
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.LAST_DAMAGE_PLAYER,
                        src.getEntity() instanceof ServerPlayer kp ? kp : null)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ENTITY);
        drops.clear();
        for (net.minecraft.world.item.ItemStack it : t.getRandomItems(params, lv.getRandom())) {
            if (it.isEmpty()) continue;
            drops.add(new net.minecraft.world.entity.item.ItemEntity(lv,
                    dead.getX(), dead.getY(), dead.getZ(), withAsset(s, it)));
        }
        return true;
    }

    /**
     * 掉落物落到我们的物品上：表里 {@code item} 只能填原版 id，所以按它找回项目里把它当 {@code base}
     * 声明的对象，用同一条 {@link #itemStackOf} 重建 —— 名字 / components / 身份标签与 {@code give} 完全一致。
     * 认不出对应对象 = 原样丢。
     */
    private static net.minecraft.world.item.ItemStack withAsset(Session s, net.minecraft.world.item.ItemStack raw) {
        if (s == null || s.def == null) return raw;
        String ref = assetTagOf(raw);                       // 表里写的是**资产名**时，编译期已把标记注进这条掉落物
        if (ref.isEmpty()) return raw;                      // 没标记 = 原版物品：一个字节都不动
        var st = itemStackOf(ref, raw.getCount(), s.gameName);
        return st == null ? raw : st;
    }

    /**
     * 这个方块 / 生物挂的是哪张表：读脚本里的对象声明（`loot "表名"` 那一格），按**基底原版 id** 认。
     *
     * <p>ponytail: 每次判定现算（声明就几条、不在热路径上）—— 真成热路径了再按会话缓存 + 热替换时清。
     */
    private static String tableFor(Session s, String kind, String baseId) {
        if (baseId == null || baseId.isEmpty()) return null;
        for (Ast.AssetDecl d : s.eng.script().assetDecls()) {
            if (!d.kind().equals(kind)) continue;
            String base = "";
            String table = "";
            for (Ast.Field f : d.fields()) {
                if (f.value() instanceof Ast.Str x) {
                    if (f.key().equals("base")) base = x.v();
                    if (f.key().equals("loot")) table = x.v();
                }
            }
            if (table.isEmpty()) continue;
            if (baseId.equals(base) || baseId.equals(d.name())) return table;
        }
        return null;
    }

    /**
     * 项目档里的那张表 → **真原版 `LootTable`**（原版 codec 直解档里那份 JSON —— 不经过数据包机制，
     * 表跟着项目档走）。读不出 / 格式不对 → null + 一行日志（**不静默吞**：作者看日志才知道表写坏了）。
     */
    private static net.minecraft.world.level.storage.loot.LootTable lootTableOf(Session s, String name,
            net.minecraft.server.level.ServerLevel lv) {
        com.google.gson.JsonObject loot = s.def.lootTables();
        if (!loot.has(name) || !loot.get(name).isJsonObject()) {
            TableGame.LOGGER.warn("[掉落表] 脚本里写了 loot \"{}\"，但项目档里没有这张表（找 loot/{}.json）", name, name);
            return null;
        }
        try {
            // ⚠ 必须带注册表访问：表里有 enchantment / enchantments（时运、精准采集）—— 附魔是**动态注册表**，
            //   光 JsonOps 解不出来（会报 "Can't access registry .../minecraft:enchantment" ⇒ 静默退回原版掉落）。
            var ops = net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess());
            // 表里写的**资产名**要先编译成原版能解的形状（改名 + 物品注入身份标记），见 LootEdit.toVanilla
            var vanilla = LootEdit.toVanilla(loot.get(name).getAsJsonObject(),
                    s.def == null ? java.util.List.of() : s.def.assets());
            return net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                    .parse(ops, vanilla)
                    .getOrThrow();
        } catch (RuntimeException e) {
            TableGame.LOGGER.warn("[掉落表] 表「{}」不是合法原版 loot_table：{}", name, e.getMessage());
            return null;
        }
    }

    // ---------- 区域规则（按人）：死不掉落 / 重生点 ----------

    /**
     * 死亡瞬间的「背包 + 经验」快照。
     *
     * <p>必须在死亡那一刻抓：{@code LivingDeathEvent} 发在 {@code LivingEntity.die} 最顶上，再往下几行
     * 原版就 {@code inventory.dropAll()} 倒空背包，到掉落物那步再回填拿到的是空背包。
     * 按槽位记（{@code 0..getContainerSize()-1}，含盔甲 / 副手 / 26.x 胸甲与马鞍槽）而不是按掉落物记 ——
     * 否则穿在身上的甲会跑进背包（换了地方，像 bug）。
     */
    private record DeathStash(java.util.List<net.minecraft.world.item.ItemStack> slots, int xp) {}

    /** 把这份快照原样还给他（按槽位；经验按总点数补回去）。 */
    private void restoreStash(ServerPlayer p, DeathStash st) {
        var inv = p.getInventory();
        int n = Math.min(st.slots().size(), inv.getContainerSize());
        for (int i = 0; i < n; i++) inv.setItem(i, st.slots().get(i).copy());
        if (st.xp() > 0) p.giveExperiencePoints(st.xp());
    }

    /**
     * 设置重生点（{@code respawn(谁,x,y,z)}）：原版 {@code setRespawnPosition(forced=true)}，死了原版把他送
     * 回这里、跨维度也对（照 {@code /spawnpoint} 写的）。朝向取他此刻的朝向；世界 = 他现在这个世界。
     */
    private void respawnAt(ServerPlayer pl, double x, double y, double z) {
        var data = net.minecraft.world.level.storage.LevelData.RespawnData.of(pl.level().dimension(),
                net.minecraft.core.BlockPos.containing(x, y, z), pl.getYRot(), pl.getXRot());
        pl.setRespawnPosition(new ServerPlayer.RespawnConfig(data, true), false);
    }

    /** 他这条命要不要「不掉落」（{@code keep_items} 开着的）—— 给死亡事件三件套判闸。 */
    public boolean keepDrops(ServerPlayer sp) {
        Session s = sessionOf(sp);
        return s != null && s.keepItems.contains(sp.getUUID());
    }

    /**
     * 他死了（表在 {@code LivingDeathEvent} 里叫）：开了 {@code keep_items} 的 → 抓一份背包 + 经验等重生回填。
     * 没开 / 不在局里 → 什么都不做（原版怎么办就怎么办）。
     */
    public void playerDied(ServerPlayer sp) {
        Session s = sessionOf(sp);
        if (s == null || !s.keepItems.contains(sp.getUUID())) return;
        var inv = sp.getInventory();
        var slots = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) slots.add(inv.getItem(i).copy());   // ⚠ copy：原栈马上要被倒掉
        s.deathStash.put(sp.getUUID(), new DeathStash(slots, sp.totalExperience));
        TableGame.LOGGER.info("[活物] {} 死了：{} 个槽位 + {} 点经验先存着（keep_items），重生回填",
                sp.getName().getString(), slots.size(), sp.totalExperience);
    }

    /** 他重生回来了：有快照就还给他（没快照 = 不关他的事，直接走）。 */
    public void playerRespawned(ServerPlayer np) {
        Session s = sessionOf(np);
        if (s == null) return;
        DeathStash st = s.deathStash.remove(np.getUUID());
        if (st == null) return;
        restoreStash(np, st);
        TableGame.LOGGER.info("[活物] {} 重生：{} 个槽位 + {} 点经验已回填",
                np.getName().getString(), st.slots().size(), st.xp());
    }

    /**
     * 他此刻在哪一局（物理判据：在那一局的区域里）。⚠ 掉线找局不能只靠它：人走开了 / 转维度才掉线时它返回
     * null，掉线处置整段会漏 —— 那一路要用 {@link #sessionWithPresent} 按在场账兜底。
     */
    private Session sessionOf(ServerPlayer p) {
        for (Session s : sessions) {
            if (s.eng != null && inGame(s, p)) return s;
        }
        return null;
    }

    /**
     * 按「上一 tick 在场账」（{@code presentLast}）找他那一局 —— 只给掉线那一条路用。
     * 物理判据（{@link #sessionOf}）找不到「走开了才掉线」的人，在场账记得这件事。
     * 它不参与任何分发判据（谁收快照 / 谁能提交一律现算）。
     */
    private Session sessionWithPresent(java.util.UUID id) {
        for (Session s : sessions) {
            if (s.presentLast.containsKey(id)) return s;
        }
        return null;
    }

    /**
     * 这一局此刻的收件人（每 tick 现算，不记账）：在线玩家里「{@link #sessionOf 在局里} 是他」且
     * 「{@link #inAnyRegion 在区域里}」的那些人。发快照 / 舞台 / 画板、收提交与交互、报「局内 N 人」全走它。
     */
    private List<ServerPlayer> recipients(Session s) {
        List<ServerPlayer> out = new ArrayList<>();
        if (s.eng == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (sessionOf(p) == s && inGame(s, p)) out.add(p);
        }
        return out;
    }

    /** 收件人的名字（顺序 = 在线名单顺序；**只列此刻在线的**）—— 显示 / 按名字转发的地方统一走它。 */
    private List<String> recipientNames(Session s) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : recipients(s)) out.add(p.getName().getString());
        return out;
    }

    /**
     * 建引擎时那份「在场名单」（引擎只拿它解析 {@code say}/{@code show} 的 {@code others}）：此刻在线、且按
     * 这份脚本自己的区域声明算在场的人。参数是 {@code script} 而不是会话：建引擎之前还没有 {@code s.eng}。
     */
    private List<String> presentNames(Session s, Ast.Script script) {
        ServerLevel board = boardLevel(script.dim() == null ? "" : script.dim());
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (inGame(script, s == null ? null : s.namedPlayers, p, board)) out.add(p.getName().getString());
        }
        return out;
    }

    /** 这个玩家是这一局的在场者？（按玩家名比对——名字就是身份，重命名即失联） */
    private boolean isPresent(Session s, String playerName) {
        ServerPlayer p = playerByName(playerName);
        return p != null && sessionOf(p) == s;
    }

    private void endSession(Session s, String reason) {
        // ⚠ 收件人**先算**：sessions.remove 之后 sessionOf 就查不到这一局了（现算的判据要用到它）。
        List<ServerPlayer> crew = recipients(s);
        sessions.remove(s);
        // 本局 spawn 的棋子实体随局一起没（脚本放的棋子不该留在世界上 —— 同临时画板一个口径）
        for (com.tablegame.piece.GamePieceEntity ent : s.pieceEntities.values()) {
            if (ent != null && ent.isAlive()) ent.discard();
        }
        // 脚本放出来的原版生物同理：局都没了，不该还有一群僵尸杵在棋盘上
        for (net.minecraft.world.entity.Entity ent : s.mobEntities.values()) {
            if (ent != null && ent.isAlive()) ent.discard();
        }
        s.mobEntities.clear();
        // 卡牌实体：局的牌随局收掉（与棋子同口径）
        for (com.tablegame.card.CardEntity ent : s.cardEntities.values()) {
            if (ent != null && ent.isAlive()) ent.discard();
        }
        s.cardEntities.clear();
        // 实体画面：挂着的画面随局收掉（组件实体不留在世界里）
        for (EntityStage st : s.entityStages) dropEntityStage(st);
        s.entityStages.clear();
        // 盘上认领过 UUID、但这轮还没被 place_piece 认领回来的那几颗：按 UUID 一起收掉。
        // 不找的话它们留在世界里当孤儿（局都没了，棋子还杵在棋盘上）。此刻人在棋盘边、区块已加载。
        ServerLevel lv0 = levelFor(s);
        for (UUID u : s.pieceUuids.values()) {
            if (lv0.getEntity(u) instanceof com.tablegame.piece.GamePieceEntity gp && gp.isAlive()) gp.discard();
        }
        s.pieceEntities.clear();
        s.pieceUuids.clear();
        // 卡牌同理：盘上认领过 UUID、这轮还没被 place_card 认领回来的那些，按 UUID 一起收掉
        for (UUID u : s.cardUuids.values()) {
            if (lv0.getEntity(u) instanceof com.tablegame.card.CardEntity ce && ce.isAlive()) ce.discard();
        }
        s.cardUuids.clear();
        closeStageBoard(s);              // 临时画板随局一起没（不落盘，本就没东西可留）
        for (ServerPlayer pl : crew) {
            sendStageTo(s, pl, null, false);     // 先让客户端收掉对局界面（舞台定义清空）
            pl.sendSystemMessage(Component.literal(reason));
            // 收尾快照：inGame=false，客户端可以收起对局界面
            PacketDistributor.sendToPlayer(pl, new HostPackets.HostStatePayload(
                    s.gameName, List.of(), List.of(), "已结束", "对局已结束", false, ""));
        }
        // 幽灵预览：局没了就别把它留在客户端屏上 —— 手里那份去重账正好回答「谁还挂着哪份」，
        // 在圈边但没收到收尾快照的人也包含在内。
        for (UUID gu : s.ghostKey.keySet()) {
            ServerPlayer gpl = server == null ? null : server.getPlayerList().getPlayer(gu);
            if (gpl != null) {
                PacketDistributor.sendToPlayer(gpl,
                        new AreaGhostPackets.GhostPayload(s.gameName, "", 0, 0, 0, false));
            }
        }
        s.ghostKey.clear();
    }

    /** 给此刻在局里的所有人发一条对局消息。 */
    private void say(Session s, String text) {
        for (ServerPlayer pl : recipients(s)) {
            // `&` 颜色码同样生效（脚本 say 的文字出口就这一处 + 上面 messageTo 那处）。
            pl.sendSystemMessage(Component.literal(ColorText.mask(text)));
        }
    }

    /**
     * 把这一局的定义发给人：开局发一次（客户端据此知道在局里、该开屏就开屏），结束时发
     * {@code inGame=false} 收掉界面。
     *
     * <p>走线口径 {@link GameStore#toJsonWire} 并按人展开一次（同 {@link #pushStageIfChanged}）——
     * 客户端没有解释器，只认宿主合成好的舞台；发共享档会让每人都看到别人的秘密槽位。
     *
     * <p>⚠ 结束时别用它：那时会话已从表里摘掉、收件人现算为空，用 {@link #endSession} 先算好的 crew。
     */
    private void sendStage(Session s, GameDefinition def, boolean inGame) {
        for (ServerPlayer pl : recipients(s)) sendStageTo(s, pl, def, inGame);
    }

    /** {@link #sendStage} 的单人版（游戏台观众加入时只给他发，不动别人）。 */
    private void sendStageTo(Session s, ServerPlayer pl, GameDefinition def, boolean inGame) {
        boolean expand = inGame && def != null && s.eng != null && s.eng.hasScreens();
        String json = "";
        if (inGame && def != null) {
            GameDefinition send = def;
            if (expand) {
                try {
                    // ids 用一次性的：点选那本账只归 pushStageIfChanged 记，别在这儿覆盖
                    send = withExpanded(s.eng, def, pl.getName().getString(),
                            new java.util.LinkedHashMap<>());
                } catch (Ast.ScriptError e) {
                    // 展开不了（脚本写错 / 画块死循环）：发不带舞台的那份，至少让客户端知道「在局里」
                    TableGame.LOGGER.warn("[对局] 舞台展开失败（{}）：{}", pl.getName().getString(), e.getMessage());
                }
            }
            json = GameStore.toJsonWire(send).toString();
        }
        PacketDistributor.sendToPlayer(pl, new HostPackets.StagePayload(s.gameName, json, inGame));
    }

    /**
     * 舞台去模板化：按这个人把脚本里的画块展开成组件，变了才重推一份舞台给他。不重推屏上会冻在
     * 开局那一帧（手牌出掉一张还是 17 个框）。比的是整份 JSON —— 每人可见数据不同，展开也不同。
     * 脚本里没有画块（老档）= 一次都不推，客户端用档里那份静态组件树。
     */
    private void pushStageIfChanged(Session s, ServerPlayer pl, String me) {
        if (s.eng == null || s.def == null || !s.eng.hasScreens()) return;
        java.util.Map<String, Object> ids = new java.util.LinkedHashMap<>();
        GameDefinition expanded;
        try {
            expanded = withExpanded(s.eng, s.def, me, ids);
        } catch (Ast.ScriptError e) {
            // 展开失败（脚本写错 / 画块死循环）：只在第一次说一声，别每帧刷屏
            if (!s.stagePushed.containsKey(pl.getUUID())) fail(pl, "舞台展开不了 —— " + e.getMessage());
            return;
        }
        // ⚠ 必须是线口径：磁盘口径的 toJson 不写 stage 段（真源在脚本里）—— 发错这一处，客户端收到的是
        //    「没有舞台的定义」，屏上只剩壳（阶段名、输入提示、绘画区），组件一个都没有、HUD 不画。
        String json = GameStore.toJsonWire(expanded).toString();
        if (json.equals(s.stagePushed.get(pl.getUUID()))) return;          // 没变 → 不打扰客户端
        s.stagePushed.put(pl.getUUID(), json);
        s.pickIds.put(pl.getUUID(), ids);
        PacketDistributor.sendToPlayer(pl, new HostPackets.StagePayload(s.gameName, json, true));
    }

    /**
     * 复制一份定义，把每块承载换成「这个人该看到的那份展开」。必须复制而不是改原定义：原定义是共享的那份，
     * 每人展开不同；复制出来的只用来发一次包，不落盘。
     */
    private GameDefinition withExpanded(Interp eng, GameDefinition def, String who,
                                        java.util.Map<String, Object> ids) {
        // 承载不进档：**按脚本里有没有那块画布**决定 —— 有 screen main = 全屏（开局自动开屏）·
        // 只有 screen hud = 常驻看板。画布尺寸恒 320×180（脚本里那些坐标就是按它写的）。
        GameDefinition.StageDef out = new GameDefinition.StageDef(GameDefinition.StageUi.empty());
        // 脚本 show(…) 指定的「现在显示哪块」随快照下去（客户端据此开/关屏、挑块）
        out.currentFull(eng.currentFull(who));   // 逐人：show(谁, "名") 只改那个人的这份
        out.currentHud(eng.currentHud(who));
        out.hideSeq(eng.hideSeq());          // 客户端比对它 → 关掉全屏屏（收起是一次性动作，内容不清）
        out.hiddenName(eng.hiddenName());    // hide("名") 点名收：客户端只关该关的（null = hide() 全收）
        for (java.util.Map.Entry<String, Boolean> e : eng.screens().entrySet()) {
            String screen = e.getKey();
            java.util.List<GameDefinition.Component> comps = expandComponents(eng, def, screen, who, ids);
            if (comps.isEmpty()) continue;                             // 脚本里没这块画布
            boolean hud = e.getValue();                                // 看板类？由声明给（不再靠名字猜）
            // 看板那块的位置与大小 = 脚本里 screen hud 的 place(x, y, 宽, 高)（屏幕比例）；
            // 没写 place = 缺省那组值（与旧的写死值逐字一致 —— 不缺省就不变）
            double[] pl = hud ? eng.lastPlace() : null;
            out.views().add(new GameDefinition.StageView(screen, screen, eng.screenBg(screen), hud,
                    out.ui().w(), out.ui().h(),
                    pl == null ? GameDefinition.StageView.DEF_HUD_X : pl[0],
                    pl == null ? GameDefinition.StageView.DEF_HUD_Y : pl[1],
                    pl == null ? GameDefinition.StageView.DEF_HUD_W : pl[2],
                    pl == null ? GameDefinition.StageView.DEF_HUD_H : pl[3],
                    false, comps));
        }
        out.setMode(out.views().stream().anyMatch(v -> !v.hud())
                ? GameDefinition.StageDef.MODE_FULL : GameDefinition.StageDef.MODE_HUD);
        return new GameDefinition(def.name(), def.desc(), def.cards(), def.decks(), def.pieces(), def.vars(),
                out, def.script(), def.assets(), def.areas(), def.rest());
    }

    /**
     * 跑一遍画块 → 客户端认识的那几种组件（{@code box} → panel、{@code text} → text）。顺手记「框 id → 身份值」：
     * 点击只带回框 id，身份值得从这里查。框 id 每次展开重排，陈旧点击查不到时提示一句就完了。
     */
    private java.util.List<GameDefinition.Component> expandComponents(Interp eng, GameDefinition def, String screen, String who,
                                                                     java.util.Map<String, Object> ids) {
        java.util.List<Ast.Box> boxes = eng.expand(screen, who);
        java.util.List<GameDefinition.Component> out = new java.util.ArrayList<>();
        int n = 0;
        for (Ast.Box b : boxes) {
            String id = screen + (n++);
            String kind = b.kind();
            com.google.gson.JsonObject p = new com.google.gson.JsonObject();
            if (kind.equals("text")) {
                p.addProperty("text", b.text());
                p.addProperty("color", b.color().isEmpty() ? "#FFFFFF" : b.color());
                if (!b.bg().isEmpty()) p.addProperty("bg", b.bg());        // 文本框默认不出底色
            } else if (kind.equals("input")) {
                p.addProperty("hint", b.text());                          // 第 5 个参数 = 占位提示
                p.addProperty("action", "input");                        // 自由文本：脚本在 on input 里判 input
                // 输入框也带上自己的框 id：客户端提交时原样回传，宿主用它查「这个框是谁」，
                // 脚本侧读内建值 input_box（= 资产名）。和 click 那条一模一样的套路。
                p.addProperty("inbox", id);
                ids.put(id, b.identity());
            } else if (kind.equals("card_face") || kind.equals("card_back")) {
                // 舞台上摆一张牌：脚本只写卡 id（档里 cards 段的 id），卡面 / 卡背的画板引用由宿主在这里查出 ——
                // 脚本不该知道画板项目路径，那是卡定义的事。认不出的卡 id / 没填引用 → 记日志 + 空引用（画占位）。
                String cardId = b.text();
                String art = GameDefinition.cardArt(def.cards(), cardId, kind.equals("card_back"));
                if (art.isEmpty()) {
                    TableGame.LOGGER.warn("[舞台] 卡「{}」没找到，或它的{}没填画板引用（画占位）",
                            cardId, kind.equals("card_face") ? "卡面" : "卡背");
                }
                p.addProperty("art", art);
                p.addProperty("card", cardId);
            } else if (kind.equals("img")) {
                // 图片框：第 5 个实参就是资产名（画板项目 或 @游戏名/资产名）—— 宿主原样下发，
                // 像素走客户端 pull（同卡面那条路，见 ClientGameHandler.FACES）。
                p.addProperty("art", b.text());
            } else if (kind.equals("paint")) {
                // 绘画区：不写额外键（体内画板靠 hasDrawComponent / boardCellAt 按类型探测）
            } else {
                p.addProperty("bg", b.bg().isEmpty() ? "#00000000" : b.bg());
            }
            if (b.clickable()) {
                p.addProperty("click", id);                               // 客户端看到它就认「这个框可点」
                ids.put(id, b.identity());
            }
            out.add(new GameDefinition.Component(id, compType(kind),
                    b.x(), b.y(), b.w(), b.h(), p, java.util.List.of()));
        }
        return out;
    }

    /** 画句子的种类 → 客户端认识的组件类型（客户端不认识画块，只认识这几种）。 */
    private static String compType(String kind) {
        return switch (kind) {
            case "text" -> "text";
            case "input" -> "input";
            case "paint" -> "draw";
            case "card_face" -> "card";         // 舞台上的一张牌（正面）
            case "card_back" -> "cardback";     // 同上（背面）
            case "img" -> "img";                // 图片框：像素按 art 属性拉（客户端 pull）
            default -> "panel";
        };
    }

    /**
     * 状态快照：每个席位收到的是按他自己的可见性过滤过的那一份（裁切在 {@link Interp#visibleValues}，声明上的
     * 条件表达式为真才放进来）。行格式走 {@link GameDefinition#snapLine}：生产与消费两侧共用一处。
     */
    private void broadcastState(Session s) {
        if (!sessions.contains(s) || s.eng == null || s.def == null) return;
        List<String> names = recipientNames(s);         // 名单 = 此刻在场的人（现算）
        for (ServerPlayer pl : recipients(s)) {
            String me = pl.getName().getString();
            // 脚本里没有「显示名」这个概念：变量名就是显示名（HUD 的引用组件也按名字取值）
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : s.eng.visibleValues(me).entrySet()) {
                // 行格式只有一处定义：HostManager 曾被写成 "名: 值"、客户端却按 '=' 切 → 舞台框永远空
                lines.add(GameDefinition.snapLine(e.getKey(), e.getValue()));
            }
            PacketDistributor.sendToPlayer(pl, new HostPackets.HostStatePayload(
                    s.gameName, names, lines,
                    s.eng.stageId(), inputHint(s.def), true, inputAction(s.def)));
            pushStageIfChanged(s, pl, me);          // 画块展开变了就补推一份舞台（老档不推）
        }
    }

    /**
     * 手里那件物品的项目资产名（{@code hand_asset} / {@code off_asset}）：读物品自带的 {@link #ASSET_TAG}
     * 标签（只有 {@link #customItemStack} 一处写它）；不是项目物品 → 空串。⚠ 资产改名后已发出的物品还是旧名字。
     */
    private static String handAssetOf(ServerPlayer p, net.minecraft.world.InteractionHand h) {
        return assetTagOf(p.getItemInHand(h));
    }

    /** 一件物品自带的 {@link #ASSET_TAG} 标签（写它的地方只有 {@link #customItemStack} 一处）。 */
    private static String assetTagOf(net.minecraft.world.item.ItemStack st) {
        var cd = st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return cd == null ? "" : cd.copyTag().getStringOr(LootEdit.ASSET_TAG, "");
    }

    /**
     * 玩家右键空气用物品（{@code on use}）—— 判据与 {@link #worldClick} 同一套（在棋盘维度里 + 在场 +
     * 没开「允许替换方块」）。不取消原版那一下：用物品是原版的事，引擎只顺便报一声。
     */
    public boolean itemUsed(ServerPlayer p) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return false;
        if (p.level() != levelFor(s)) return false;
        if (s.eng.allowReplace()) return false;
        String name = p.getName().getString();
        if (!isPresent(s, name)) return false;
        return s.eng.acceptUse(name,
                handIdOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                handIdOf(p, net.minecraft.world.InteractionHand.OFF_HAND),
                handAssetOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                handAssetOf(p, net.minecraft.world.InteractionHand.OFF_HAND));
    }

    public boolean worldClick(ServerPlayer p, BlockPos pos, boolean sneak) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return false;
        if (p.level() != levelFor(s)) return false;                  // 不在棋盘维度里：原版交互照常（不吃）
        if (s.eng.allowReplace()) return false;                      // 本局「允许替换方块」→ 不抢这一下：
        // 这一格没被认领（`click` 按方块 id · `click_hand` 按「手里拿着什么」）⇒ 原版那一下照常
        // （箱子 / 工作台 / 熔炉打得开）。⚠ 手里的东西按资产名认（`tg_asset` 标签），不是基底 id。
        if (!s.eng.claimsBlock(blockIdAt(p.level(), pos),
                handAssetOf(p, net.minecraft.world.InteractionHand.MAIN_HAND))) return false;
        String name = p.getName().getString();
        if (!isPresent(s, name)) return false;
        s.eng.acceptClick(name, pos.getX(), pos.getY(), pos.getZ(),
                blockIdAt(p.level(), pos), handIdOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                handIdOf(p, net.minecraft.world.InteractionHand.OFF_HAND), sneak,
                handAssetOf(p, net.minecraft.world.InteractionHand.MAIN_HAND),
                handAssetOf(p, net.minecraft.world.InteractionHand.OFF_HAND));
        return true;
    }

    /**
     * 局内席位玩家右键实体 → 发一次世界事件（{@code on entity}）。返回 true = 这一下被吃掉。
     * 口径同 worldClick：只认席位玩家 + 棋盘维度内；吃掉原版交互（对局里右键生物不该顺手喂它 / 骑它 /
     * 打开它的界面）。观众不抢（他还没入局，原版该干嘛干嘛）。
     */
    public boolean entityInteract(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return false;
        if (p.level() != levelFor(s)) return false;                  // 不在棋盘维度里：原版交互照常
        String name = p.getName().getString();
        if (!isPresent(s, name)) return false;                        // 不在这一局的人点了不算
        // 实体画面上的组件：右键 = 点了那个组件 → 走现成的点选通道（pick = 组件的 mark）。
        ComponentHit ch = componentOf(s, target);
        if (ch != null) {
            if (!ch.mark().isEmpty()) s.eng.acceptPick(name, ch.mark());
            return true;                                             // 引擎自己放的东西，这一下不还给原版
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        String type = key == null ? "" : key.toString();
        // 实体也要认领（顶层 `click`，与方块共用一张表）—— 没认领 ⇒ 这一下还给原版（村民 / 流浪商人的
        // 交易界面这才打得开）。口径与改方块那次一字不差：认领过才吃。
        if (!s.eng.claimsEntity(type)) return false;
        BlockPos bp = target.blockPosition();
        // 这一下是哪个实体对象：脚本放出来的（生物 / 卡牌）→ 反查本局账拿名字；不是 → 空串。
        String eid = eidOf(s, target);
        return s.eng.acceptEntity(name, type, bp.getX(), bp.getY(), bp.getZ(), eid, 0, 0);
    }

    /**
     * 玩家左键（攻击）了一个实体：交给脚本一次 {@code on entity}，{@code hit} = 1（右键那一路给 0）。
     * 只认引擎自己的东西（本局账上的生物 / 卡牌 / 实体画面组件）—— 野生生物挨打不惊动脚本。
     * 原版伤害照旧结算（不 cancel）。
     */
    public void leftClickEntity(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return;
        if (p.level() != levelFor(s)) return;
        String name = p.getName().getString();
        if (!isPresent(s, name)) return;
        String eid = eidOf(s, target);
        if (eid.isEmpty()) return;                                   // 不是引擎的东西：不理
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        BlockPos bp = target.blockPosition();
        s.eng.acceptEntity(name, key == null ? "" : key.toString(),
                bp.getX(), bp.getY(), bp.getZ(), eid, 0, 1);
    }

    /** 实体 → 引擎账上的名字（本局放出来的生物 / 卡牌 / 实体画面组件；都不是 = 空串）。 */
    private String eidOf(Session s, net.minecraft.world.entity.Entity target) {
        for (var e : s.mobEntities.entrySet()) {
            if (e.getValue() == target) return e.getKey();
        }
        for (var e : s.cardEntities.entrySet()) {
            if (e.getValue() == target) return e.getKey();
        }
        ComponentHit ch = componentOf(s, target);
        return ch == null ? "" : ch.screen() + "." + ch.asset();
    }

    /** 这个实体是不是某块实体画面上的组件：是就给「画面 / 组件 / 标记」三样，不是 = null。 */
    private ComponentHit componentOf(Session s, net.minecraft.world.entity.Entity target) {
        for (EntityStage st : s.entityStages) {
            for (var e : st.comps.entrySet()) {
                if (e.getValue() == target) {
                    return new ComponentHit(st.screen, e.getKey(),
                            st.marks.getOrDefault(e.getKey(), ""));
                }
            }
            for (var e : st.hits.entrySet()) {                 // 命中代理（interaction）也是这个组件
                if (e.getValue() == target) {
                    return new ComponentHit(st.screen, e.getKey(),
                            st.marks.getOrDefault(e.getKey(), ""));
                }
            }
        }
        return null;
    }

    /** 组件命中（画面名 / 组件资产名 / 标记）。 */
    private record ComponentHit(String screen, String asset, String mark) { }

    /**
     * 玩家视线看向某格（客户端射线报告）—— 世界事件之二，与 {@link #worldClick}（潜行右键）分开。
     * 判定同踩格：局内席位玩家 + 棋盘维度内才发事件。看向不抢任何原版行为，所以不看
     * {@code allow_replace}（那个只管潜行右键那一下属谁）。
     */
    public void look(ServerPlayer p, BlockPos pos, String lookPlayer) {
        Session s = sessionOf(p);
        if (s == null || s.eng == null) return;
        if (p.level() != levelFor(s)) return;                        // 不在棋盘维度里：不算看向棋盘
        String name = p.getName().getString();
        if (!isPresent(s, name)) return;
        // 看向的玩家：客户端报的名字必须真是本局在场的人才发 —— 报的不可信，名字不在场就当没看人（空串）。
        // 身份 = 玩家名，脚本直接拿它当自己名单的键。
        String who = lookPlayer != null && isPresent(s, lookPlayer) ? lookPlayer : "";
        // 看向的棋子：客户端不认棋子（id 的账在宿主）—— 反查本局棋子账：哪枚棋子正站在被看的那格 → 它的 id。
        // 没有 → 空串。⚠ ponytail: 两枚棋子叠同格时取到哪枚不定；要精确再按实体记账区分。
        String piece = "";
        for (var e : s.pieceEntities.entrySet()) {
            com.tablegame.piece.GamePieceEntity ent = e.getValue();
            if (ent != null && ent.isAlive() && ent.blockPosition().equals(pos)) {
                piece = e.getKey();
                break;
            }
        }
        s.eng.acceptLook(name, pos.getX(), pos.getY(), pos.getZ(), blockIdAt(p.level(), pos), who, piece);
    }

    /**
     * 踩格检测（W2）：局内席位玩家脚下方块换了一格 → 给脚本发一次世界事件。自己在宿主 tick 里比坐标
     * （原版 {@code enter_block} 触发器太频繁、还没法给脚本发事件）。开销：每 tick O(席位数) 次坐标比较。
     */
    private void stepEvents(Session s) {
        ServerLevel lv = levelFor(s);                                // 只认这一局的棋盘维度
        // 收件人 = 此刻在场的人（现算，见 recipients）—— 一轮发完，不再分两轮。进圈那一步发 on join
        // （见 autoSpectate），脚本自己决定收不收进名单：{@code on join { push(players, actor) } }。
        for (ServerPlayer p : recipients(s)) {
            if (p.level() != lv) continue;                            // 不在棋盘维度：不发事件
            String name = p.getName().getString();
            BlockPos bp = p.blockPosition().below();                  // 踩的那一格（脚底下）
            if (bp.equals(s.lastStep.get(p.getUUID()))) continue;     // 没换格：不发（去抖）
            s.lastStep.put(p.getUUID(), bp.immutable());
            s.eng.acceptWorld(name, bp.getX(), bp.getY(), bp.getZ(), blockIdAt(p.level(), bp));
        }
    }

    /**
     * 进 / 出圈：每 tick 现算谁在区域里，与上一 tick 的账（{@link Session#presentLast}）比 ——
     * 新进圈的发一次 {@code on join}，出圈的（走出去 / 走开 / 掉线 / 换维度）发一次 {@code on leave}。
     * 只负责事件；发东西的判据是现算的（见 {@link #recipients}）。跨过边沿才发，站着不动不发。
     *
     * <p>进任何一条顶层 {@code area} 声明的区域都算走进来（盒由 {@link Interp.Region} 给，两角点已排序）；
     * 一条都没写 = 整个棋盘维度。不回话、不发开屏包：要说就由脚本在 {@code on join} 里说。
     */
    private void autoSpectate(Session s) {
        List<ServerPlayer> now = recipients(s);                   // 这一 tick 在场的人（现算）
        Set<UUID> nowIds = new java.util.HashSet<>();
        for (ServerPlayer p : now) {
            String name = p.getName().getString();
            nowIds.add(p.getUUID());
            if (s.presentLast.put(p.getUUID(), name) != null) continue;   // 上次就在：不是边沿
            // 刚进圈 = 刚进局：舞台 / 画板（HUD 那块 art 框）也要接上，再给脚本发一次 on join
            if (s.def != null) sendStageTo(s, p, s.def, true);
            if (s.stageBoardKey != null) {
                BoardManager bm = BoardManager.get();
                if (bm != null) bm.stageWatch(s.stageBoardKey, p.getUUID());
            }
            if (s.eng != null) s.eng.acceptJoin(name);       // 进圈 = 进局：脚本 push 进自己的名单
            TableGame.LOGGER.info("[对局] {} 进局《{}》（join world / join area / 点名）", name, s.gameName);
        }
        // 出圈：上一 tick 在、这一 tick 不在（走出区域 / 掉线 / 换维度）—— 掉线的名字照账上的报
        //（此刻查不到人，但账里存了名字，脚本要靠它 del(players, actor)）。
        for (java.util.Iterator<java.util.Map.Entry<UUID, String>> it = s.presentLast.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<UUID, String> e = it.next();
            if (nowIds.contains(e.getKey())) continue;       // 还在场
            String name = e.getValue();
            // 点名在局的（enter 过的）掉线不退局：名单不动、账也不摘、on leave 不发。要让他退出，
            // 脚本得显式 leave(名字) —— 那条会从 namedPlayers 里摘掉，下一 tick 照常发 on leave。
            if (s.namedPlayers.contains(name)) continue;
            it.remove();
            if (s.eng != null) s.eng.acceptLeave(name);
            TableGame.LOGGER.info("[对局] {} 退局《{}》", name, s.gameName);
        }
    }

    /**
     * 他在不在这一局的任何一条区域里；一条区域都没写 = 整个棋盘维度。盒的判定只有一份
     * （{@link Interp.Region#holds}，含「世界也要对上」）：同一坐标在别的维度不算在圈内。
     */
    private boolean inGame(Session s, ServerPlayer p) {
        return s != null && s.eng != null && inGame(s.eng.script(), s.namedPlayers, p, levelFor(s));
    }

    /**
     * 判据的**宿主包装**：把「他在哪个维度 / 什么坐标 / 是不是这个棋盘维度」这些 MC 事实翻成原始值，
     * 问 {@link Interp#inGame}（判据本体在那儿，不碰 MC 类型 ⇒ 自检能直接驱动它）。
     */
    private boolean inGame(Ast.Script sc, java.util.Set<String> named, ServerPlayer p, ServerLevel board) {
        BlockPos bp = p.blockPosition();
        return Interp.inGame(sc, named, p.getName().getString(), p.level() == board,
                p.level().dimension().identifier().toString(), bp.getX(), bp.getY(), bp.getZ());
    }


    /**
     * 进出圈边沿：脚本写了顶层 {@code area} 时，谁跨过圈边就发一次 {@code on world}
     * （{@code edge}：1 = 刚进 · -1 = 刚出）。
     *
     * <p>交给宿主算：脚本要自己记「上一 tick 在不在圈里」得写进名单还得处理掉线残留；宿主本就在算同一份
     * 在场判定，天然知道谁上一 tick 在哪。开销同踩格：每人一次坐标比较，没跨边就不发。
     *
     * <p>与 {@link #autoSpectate} 分工：那个答「谁进 / 出了这一局」（on join / on leave），
     * 这个答「跨过的是哪条圈边」（on world + edge + edge_area）。只在写了 area 的局里发。
     */
    private void edgeEvents(Session s) {
        List<Interp.Region> regs = s.eng.regions();
        if (regs.isEmpty()) {
            // 「一条 area 都没有」= 没有圈边可言（正常）。但读不到和真没写长得一样 —— 脚本里明明写了 area
            // 却没有，就是解析/热替换那半的事，记一行省得来回猜。
            if (s.lastEdgeWarn != (s.eng.script() == null ? -1 : s.eng.script().areas().size())) {
                s.lastEdgeWarn = s.eng.script() == null ? -1 : s.eng.script().areas().size();
                TableGame.LOGGER.info("[圈边] 这一局一条 area 都没读到（脚本里 {} 条）—— 有 area 却读到空就是解析那半的事",
                        s.lastEdgeWarn < 0 ? "?" : s.lastEdgeWarn);
            }
            return;
        }
        // ⚠ 这里要带「刚走出去的那位」，不能用 recipients（他只是「此刻在场」）—— 否则出圈永远判不到
        List<ServerPlayer> crew = worldCrew(s);
        for (Interp.Region r : regs) {
            Set<UUID> was = s.insideArea.computeIfAbsent(r.name(), k -> new java.util.HashSet<>());
            for (ServerPlayer p : crew) edgeOf(s, r, was, p, p.getName().getString());
        }
    }

    /**
     * 这一 tick 要跟谁说世界 / 圈边的事 = 此刻在场的人 ∪ 圈边账里还在线的人。不能用 {@link #recipients}
     * （那只是「此刻在场」）：走出去的人已不在场，那样他永远等不到 {@code edge == -1}，圈边账也会卡住。
     */
    private List<ServerPlayer> worldCrew(Session s) {
        List<ServerPlayer> out = new ArrayList<>(recipients(s));
        Set<UUID> seen = new java.util.HashSet<>();
        for (ServerPlayer p : out) seen.add(p.getUUID());
        List<UUID> wait = new ArrayList<>();
        for (Set<UUID> was : s.insideArea.values()) wait.addAll(was);
        wait.addAll(s.presentLast.keySet());
        for (UUID id : wait) {
            if (!seen.add(id)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);      // 下线了 = 拿不到人，跳过
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * 一个人这一 tick 在某一条区域里的边沿判定：翻面才发一次事件，翻不了什么都不发生；
     * 事件里报明是哪条（{@code edge_area}）。按「区域名 → 玩家集」发是因为一个人可能同时待在两条区域里，
     * 两种边沿各报各的。
     */
    private void edgeOf(Session s, Interp.Region r, Set<UUID> was, ServerPlayer p, String name) {
        BlockPos bp = p.blockPosition();
        boolean inside = r.holds(p.level().dimension().identifier().toString(), bp.getX(), bp.getY(), bp.getZ());
        boolean old = was.contains(p.getUUID());
        if (inside == old) return;
        if (inside) was.add(p.getUUID());
        else was.remove(p.getUUID());
        // 记一行：`edge` 这套全是静默的 —— 「脚本没生效」时先看这行在不在；没有这行 = 那一刻你身体那格
        // 不在盒里（或不在席位 / 维度），不是脚本写错了。
        TableGame.LOGGER.info("[圈边] {}{} {} @({},{},{}) 盒 ({},{},{})-({},{},{})",
                name, inside ? "进" : "出", r.name(), bp.getX(), bp.getY(), bp.getZ(),
                (long) r.minX(), (long) r.minY(), (long) r.minZ(),
                (long) r.maxX(), (long) r.maxY(), (long) r.maxZ());
        s.eng.acceptEdge(name, bp.getX(), bp.getY(), bp.getZ(),
                blockIdAt(p.level(), bp), inside ? 1 : -1, r.name());
    }

    /** 那格是什么方块（注册名文本，如 {@code minecraft:white_wool}）；没加载 → 空串。 */
    private static String blockIdAt(net.minecraft.world.level.Level lv, BlockPos bp) {
        if (!lv.isLoaded(bp)) return "";
        return blockIdOf(lv.getBlockState(bp));
    }

    /**
     * 一个方块状态 → 注册名文本（空串 = 认不出）。⚠ 破坏事件里必须用它，不能去世界里现读那一格：
     * {@code BlockDropsEvent} 在 {@code removeBlock} 之后发，那格已经是空气，现读永远读到 air。
     */
    private static String blockIdOf(net.minecraft.world.level.block.state.BlockState st) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
        return key == null ? "" : key.toString();
    }

    /**
     * 那只手拿着的物品的注册名（世界事件内建值 {@code hand} = 主手 / {@code offhand} = 副手）；
     * 空手 / 认不出 → 空串。
     * ⚠ 自定义物品（{@code @游戏/资产名}）报的是它的**基底** id —— 注册表里只有那个键。
     */
    private static String handIdOf(ServerPlayer p, net.minecraft.world.InteractionHand hand) {
        net.minecraft.world.item.ItemStack st = p.getItemInHand(hand);
        if (st == null || st.isEmpty()) return "";
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
        return key == null ? "" : key.toString();
    }

    /**
     * 粒子文本 → 原版粒子：纯 id（{@code minecraft:flame}）先包成 {@code {"type": ...}} 再走原版
     * {@code ParticleTypes.CODEC}（带参数的那几种 —— dust / item / block —— 直接写完整 JSON）；
     * 认不出 → null（调用方记一行）。
     */
    private static net.minecraft.core.particles.ParticleOptions particleOf(
            net.minecraft.world.level.Level lv, String spec) {
        try {
            String js = spec.indexOf('{') >= 0 ? spec : "{\"type\":\"" + spec + "\"}";
            var ops = net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess());
            return net.minecraft.core.particles.ParticleTypes.CODEC
                    .parse(ops, com.google.gson.JsonParser.parseString(js)).getOrThrow();
        } catch (Exception e) {
            return null;
        }
    }

    /** 路径 `a.b` → 那个 Tag（没有 → null）。路径是给熔炉那类标量键用的，不做数组下标。 */
    private static net.minecraft.nbt.Tag pathOf(net.minecraft.nbt.CompoundTag tag, String path) {
        net.minecraft.nbt.Tag cur = tag;
        for (String k : path.split("\\.")) {
            if (!(cur instanceof net.minecraft.nbt.CompoundTag c)) return null;
            cur = c.get(k);
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * 那格方块实体上所有键名（逗号分隔、已排序；没 BE → 空串 + 一行日志）—— 自省口子。键名是版本相关的
     * （26.x 熔炉：{@code cooking_time_spent} / {@code lit_time_remaining}）⇒ 作者先 keys 看一眼，再 get / set。
     */
    private static String blockDataKeys(net.minecraft.world.level.Level lv, int x, int y, int z) {
        var be = lv.getBlockEntity(new BlockPos(x, y, z));
        if (be == null) {
            TableGame.LOGGER.warn("[方块实体] 那格没有方块实体：{},{},{}", x, y, z);
            return "";
        }
        var ks = new java.util.ArrayList<>(be.saveWithoutMetadata(lv.registryAccess()).keySet());
        java.util.Collections.sort(ks);
        return String.join(",", ks);
    }

    /** 方块实体上那个键的**数**（没那格 BE / 没那个键 / 不是数 → 0）—— 熔炉 BurnTime / CookTime 那类。 */
    private static double blockDataGet(net.minecraft.world.level.Level lv, int x, int y, int z, String path) {
        var be = lv.getBlockEntity(new BlockPos(x, y, z));
        if (be == null) return 0.0;
        var t = pathOf(be.saveWithoutMetadata(lv.registryAccess()), path);
        return (t instanceof net.minecraft.nbt.NumericTag n) ? n.doubleValue() : 0.0;
    }

    /**
     * 写方块实体上一个已有的数键 —— 按它原本的类型写：拿 NBT（{@code saveWithoutMetadata}）→ 改那个键 →
     * {@code loadCustomOnly} 打回去 + {@code setChanged}。⚠ 不造新键（新键要造结构，不是这一格的事）。
     */
    private static void blockDataSet(net.minecraft.world.level.Level lv, int x, int y, int z, String path,
            double value) {
        var be = lv.getBlockEntity(new BlockPos(x, y, z));
        if (be == null) {
            TableGame.LOGGER.warn("[方块实体] 那格没有方块实体：{},{},{}", x, y, z);
            return;
        }
        var tag = be.saveWithoutMetadata(lv.registryAccess());
        int dot = path.lastIndexOf('.');
        var parent = dot < 0 ? tag
                : (pathOf(tag, path.substring(0, dot)) instanceof net.minecraft.nbt.CompoundTag c ? c : null);
        String key = dot < 0 ? path : path.substring(dot + 1);
        var cur = parent == null ? null : parent.get(key);
        if (cur == null) {
            TableGame.LOGGER.warn("[方块实体] 认不出的键：\"{}\"（只写它自己已有的键）", path);
            return;
        }
        if (cur instanceof net.minecraft.nbt.ByteTag) parent.putByte(key, (byte) value);
        else if (cur instanceof net.minecraft.nbt.ShortTag) parent.putShort(key, (short) value);
        else if (cur instanceof net.minecraft.nbt.IntTag) parent.putInt(key, (int) value);
        else if (cur instanceof net.minecraft.nbt.LongTag) parent.putLong(key, (long) value);
        else if (cur instanceof net.minecraft.nbt.FloatTag) parent.putFloat(key, (float) value);
        else if (cur instanceof net.minecraft.nbt.DoubleTag) parent.putDouble(key, value);
        else {
            TableGame.LOGGER.warn("[方块实体] 那个键不是数：\"{}\"", path);
            return;
        }
        be.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, lv.registryAccess(), tag));
        be.setChanged();
    }

    private static net.minecraft.world.Container containerAt(net.minecraft.world.level.Level lv, BlockPos bp) {
        if (lv == null || !lv.isLoaded(bp)) return null;
        if (!(lv.getBlockEntity(bp) instanceof net.minecraft.world.Container base)) return null;
        var state = lv.getBlockState(bp);
        if (base instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                && state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock cb) {
            return net.minecraft.world.level.block.ChestBlock.getContainer(cb, state, lv, bp, true);   // 双箱 → 54 槽
        }
        return base;
    }

    /**
     * 容器内容清单：只列非空槽；不是容器（null）→ 空清单。方块容器与背包共用。
     * 认不出（没加载 / 不是方块实体 / 不实现 Container / 人不在）→ 空清单、不报错，脚本看到 {@code len(…) = 0}。
     * 只交原始数据（槽 / 物品 id / 数量），包成「列表 + 记录」是解释器的事。
     */
    private static List<Interp.SlotItem> itemsOf(net.minecraft.world.Container c) {
        List<Interp.SlotItem> out = new ArrayList<>();
        if (c == null) return out;
        for (int i = 0; i < c.getContainerSize(); i++) {
            var st = c.getItem(i);
            if (st.isEmpty()) continue;                      // 只列非空槽（口径：len = 物品堆数）
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            out.add(new Interp.SlotItem(i, key == null ? "" : key.toString(), st.getCount()));
        }
        return out;
    }

    /**
     * 数一个盒里有多少个这种方块。与 {@code fill} 同口径：两角点不分先后（取 min/max）· 体积上限
     * {@link #MAX_FILL}（超了记一行、回 0）。没加载的区块里的格不算；方块 id 写法归一走 {@link GameDefinition#assetNameOf}。
     */
    private static double countBlocksIn(ServerLevel lv, int x1, int y1, int z1, int x2, int y2, int z2,
            String id) {
        var block = resolveBlock(GameDefinition.assetNameOf(id));
        if (block == null || lv == null) return 0;
        long vol = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        if (vol > MAX_FILL) {
            TableGame.LOGGER.warn("[世界] count_blocks 一次最多 {} 格，这次 {} 格 —— 回 0", MAX_FILL, vol);
            return 0;
        }
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    if (!lv.isLoaded(pos)) continue;
                    if (lv.getBlockState(pos).getBlock() == block) n++;
                }
            }
        }
        return n;
    }

    /**
     * 撒方块：在盒里随机挑 N 个合格格（不重复），每格抽一次那张原版战利品表，把抽到的第一件物品当方块
     * 摆下去 —— 返回实际撒了几格。
     *
     * <p>表 = 项目档里的原版战利品表（走 {@link #lootTableOf}）。写方块照 {@code AreaPlacer} 的两条：
     * {@code setBlock(pos, state, 2|16)}（不带邻居更新）+ 收尾对这批格统一 {@code updateNeighborsAt}。
     * {@code replace} 空串 = 只替换石头 / 深板岩（矿井的天然宿主；建筑 / 水 / 空气跳过）。体量上限 {@link #MAX_FILL}。
     */
    private static int scatterIn(Session s, ServerLevel lv, int x1, int y1, int z1, int x2, int y2, int z2,
            String table, int count, String replace) {
        if (s == null || s.def == null || lv == null) return 0;
        long vol = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        if (vol > MAX_FILL) {
            TableGame.LOGGER.warn("[世界] scatter 一次最多 {} 格，这次 {} 格 —— 回 0", MAX_FILL, vol);
            return 0;
        }
        if (count <= 0) return 0;
        var t = lootTableOf(s, table, lv);             // 项目档里没这张表 / 解不出来：lootTableOf 已记日志
        if (t == null) return 0;
        var replaceable = replaceableBlocks(replace);
        if (replaceable.isEmpty()) return 0;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        List<BlockPos> cand = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    if (!lv.isLoaded(pos)) continue;
                    if (!replaceable.contains(lv.getBlockState(pos).getBlock())) continue;
                    cand.add(pos.immutable());
                }
            }
        }
        // 挑格用 java.util.Random（纯逻辑那一层不认 MC 的 RandomSource）—— 种子取自这一局的随机流
        int[] idx = ScatterPick.pick(cand.size(), count, new java.util.Random(lv.getRandom().nextLong()));
        List<BlockPos> touched = new ArrayList<>(idx.length);
        boolean toldOnce = false;
        for (int i : idx) {
            BlockPos p = cand.get(i);
            var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(lv)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE,
                            lv.getBlockState(p))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                            net.minecraft.world.phys.Vec3.atCenterOf(p))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,
                            net.minecraft.world.item.ItemStack.EMPTY)
                    .withOptionalParameter(
                            net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY,
                            lv.getBlockEntity(p))
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.BLOCK);
            net.minecraft.world.item.ItemStack rolled = net.minecraft.world.item.ItemStack.EMPTY;
            for (var it : t.getRandomItems(params, lv.getRandom())) {
                if (!it.isEmpty()) { rolled = it; break; }          // 口径：一张表 = 一格一抽、只取第一件
            }
            if (!(rolled.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                if (!toldOnce) {                                    // 一次调用只念一行，不刷屏
                    TableGame.LOGGER.warn("[世界] scatter 表「{}」抽到的不是方块物品（{}）—— 跳过该格",
                            table, rolled.isEmpty() ? "空表" : rolled.getItem());
                    toldOnce = true;
                }
                continue;
            }
            if (lv.setBlock(p, bi.getBlock().defaultBlockState(), 2 | 16)) touched.add(p);
        }
        for (BlockPos p : touched) {
            lv.updateNeighborsAt(p, lv.getBlockState(p).getBlock(), null);
        }
        return touched.size();
    }

    /** 可替换清单（逗号分隔的方块 id）→ 方块集合；空串 = 默认石头 + 深板岩。认不出的 id 记一行日志跳过。 */
    private static java.util.Set<net.minecraft.world.level.block.Block> replaceableBlocks(String replace) {
        var out = new java.util.LinkedHashSet<net.minecraft.world.level.block.Block>();
        if (replace == null || replace.isBlank()) {
            addReplaceable(out, "minecraft:stone");
            addReplaceable(out, "minecraft:deepslate");
            return out;
        }
        for (String part : replace.split(",")) {
            if (!part.isBlank()) addReplaceable(out, part.trim());
        }
        return out;
    }

    private static void addReplaceable(java.util.Set<net.minecraft.world.level.block.Block> out, String id) {
        var b = resolveBlock(GameDefinition.assetNameOf(id));
        if (b != null) out.add(b);
    }

    /**
     * 从容器里取走若干件（{@code bag_take} 的内核）—— 返回实际拿走几件。认物品按基底 id（与
     * {@code itemsOf} 报给脚本的同一个）⇒ bag_items 读得到的就取得走。「全有或全无」的推法在
     * {@link BagTake}（纯逻辑，能自检）；这里只把逐槽扣件如实写下去。
     */
    private static int takeFrom(net.minecraft.world.Container c, net.minecraft.world.item.Item item, int want) {
        if (c == null || item == null) return 0;
        int n = c.getContainerSize();
        int[] have = new int[n];
        for (int i = 0; i < n; i++) {
            var cur = c.getItem(i);
            if (!cur.isEmpty() && cur.getItem() == item) have[i] = cur.getCount();
        }
        int[] take = BagTake.plan(have, want);
        for (int i = 0; i < n; i++) {
            if (take[i] <= 0) continue;
            var cur = c.getItem(i);
            c.setItem(i, take[i] >= cur.getCount() ? net.minecraft.world.item.ItemStack.EMPTY
                    : cur.copyWithCount(cur.getCount() - take[i]));
        }
        return BagTake.total(take);
    }

    /**
     * 整份背包 → SNBT 文本。走原版存档那一对（{@code Inventory.save} + {@code ItemStackWithSlot.CODEC}）——
     * 只存背包 36 格：护甲 / 副手在 {@code LivingEntity.equipment} 上、26.x 无公开口，引擎不碰。
     */
    private static String bagSnapshot(ServerPlayer p, ServerLevel lv) {
        try (net.minecraft.util.ProblemReporter.ScopedCollector rep =
                new net.minecraft.util.ProblemReporter.ScopedCollector(p.problemPath(), TableGame.LOGGER)) {
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(rep, lv.registryAccess());
            p.getInventory().save(out.list("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
            return out.buildResult().toString();
        }
    }

    /** SNBT 文本 → 写回背包（认不出 / 读坏 → 记一行、**原背包不动**，不掐局）。 */
    private static void bagLoad(ServerPlayer p, ServerLevel lv, String snbt) {
        try (net.minecraft.util.ProblemReporter.ScopedCollector rep =
                new net.minecraft.util.ProblemReporter.ScopedCollector(p.problemPath(), TableGame.LOGGER)) {
            var tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);   // 认不出先在这儿抛：背包一个字节没动
            p.getInventory().load(net.minecraft.world.level.storage.TagValueInput
                    .create(rep, lv.registryAccess(), tag)
                    .listOrEmpty("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
        } catch (Exception e) {
            TableGame.LOGGER.warn("[背包] 还原失败，原背包不动：{}", e.getMessage());
            return;
        }
        p.inventoryMenu.broadcastChanges();                 // 同 setIn：背包改完必须推客户端
    }

    private static int fillInto(net.minecraft.world.Container c, net.minecraft.world.item.ItemStack st) {
        int n = c.getContainerSize();
        int[] mergeRoom = new int[n];
        int[] emptyRoom = new int[n];
        int cap = st.getMaxStackSize();
        for (int i = 0; i < n; i++) {
            var cur = c.getItem(i);
            if (cur.isEmpty()) {
                emptyRoom[i] = cap;
            } else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(cur, st)) {
                mergeRoom[i] = Math.min(cur.getMaxStackSize(), cap) - cur.getCount();
            }
        }
        int[] add = ChestFill.plan(mergeRoom, emptyRoom, st.getCount());
        for (int i = 0; i < n; i++) {
            if (add[i] <= 0) continue;
            var cur = c.getItem(i);
            c.setItem(i, cur.isEmpty() ? st.copyWithCount(add[i])
                    : cur.copyWithCount(cur.getCount() + add[i]));
        }
        return ChestFill.total(add);
    }


    /**
     * 脚本里的「人」（席位名）→ 档案文件名用的 UUID。人不在线（或名字对不上）时退回用名字当文件名：
     * 档案是「按人一份」，宁可文件名难看也别把数据丢了（已知的粗糙边：下次在线按 UUID 找的是另一份）。
     */
    private String uuidOf(String who) {
        ServerPlayer pl = playerByName(who);
        return pl == null ? who : pl.getUUID().toString();
    }

    private ServerPlayer playerByName(String who) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(who)) return p;
        }
        return null;
    }

    /**
     * 脚本写的名字 → 世界里的那个活物（{@code Interp.EntityOps} 一族动词的目标解析）：先当在线玩家名找，
     * 再查本局账上的实体（{@code spawn_mob} 放出来的那只，见 {@link Session#mobEntities}）。都没有 → null。
     * 玩家优先：名字撞了时玩家那条路是老行为，想指名实体就把资产名取独特一点。
     */
    private net.minecraft.world.entity.LivingEntity livingOf(Session s, String who) {
        ServerPlayer pl = playerByName(who);
        if (pl != null) return pl;
        if (s == null) return null;
        net.minecraft.world.entity.Entity e = s.mobEntities.get(who);
        if (e == null) e = s.mobEntities.get(GameDefinition.assetNameOf(who));
        return e instanceof net.minecraft.world.entity.LivingEntity le ? le : null;
    }

    /**
     * 上面那条的「找不到就记一行、回 null」版 —— 一族动词（十四个）都要这一句，收在一处：
     * 「认不出就跳过、绝不掐局」是本工程世界层的常驻口径（同 set_block 认不出方块名）。
     */
    private net.minecraft.world.entity.LivingEntity targetOf(Session s, String who, String verb) {
        net.minecraft.world.entity.LivingEntity le = livingOf(s, who);
        if (le == null) {
            TableGame.LOGGER.warn("[活物] {} 找不到「{}」：既不是在线玩家，也不在本局实体账上", verb, who);
        }
        return le;
    }

    /**
     * 把一段 NBT 并进一个实体（原版 {@code /data merge entity}）：存出全部数据 → merge → 整份 load 回去 →
     * 把 UUID 还回去。⚠ 别直接把脚本那一段喂给 {@code load}：它是整份载入，位置 / 速度 / 血量会变默认值。
     */
    private static boolean writeEntityNbt(net.minecraft.world.entity.Entity e, net.minecraft.nbt.CompoundTag add) {
        java.util.UUID u = e.getUUID();
        try (var rep = new net.minecraft.util.ProblemReporter.ScopedCollector(
                e.problemPath(), TableGame.LOGGER)) {
            // 原版 /data get entity 用的就是这一支（saveWithoutId 26.x 收的是 ValueOutput，不是 CompoundTag）
            net.minecraft.nbt.CompoundTag now =
                    net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(e);
            now.merge(add);
            e.load(net.minecraft.world.level.storage.TagValueInput.create(rep, e.registryAccess(), now));
            e.setUUID(u);
            // 回读校验：原版 Codec 读不动某个字段时**静默**（orElse / orElseGet 兜默认值）—— 只有这一遍能看见
            net.minecraft.nbt.CompoundTag back =
                    net.minecraft.advancements.criterion.NbtPredicate.getEntityTagToCompare(e);
            for (String k : add.keySet()) {
                if (!back.contains(k)) {
                    TableGame.LOGGER.warn("[活物] 写 NBT：{}「{}」那个键没吃进去 —— 原版读不动它（脚本给的是 {}）",
                            e.getType().toShortString(), k, add.get(k));
                }
            }
        } catch (Exception ex) {
            TableGame.LOGGER.warn("[活物] 写 NBT 失败：{}", ex.toString());
            return false;
        }
        return true;
    }

    /**
     * 那个玩家的**背包**容器 —— 原版 {@code Inventory} 本身就是 {@code Container}
     * （0-8 快捷栏 · 9-35 主背包 · 36-39 护甲 · 40 副手 · 41-42 其余装备槽）。
     * 人不在（离线 / 名字写错，比如往非人席位写）→ null（读回空清单、写记日志跳过）。
     */
    private net.minecraft.world.Container bagAt(String who) {
        ServerPlayer p = playerByName(who);
        return p == null ? null : p.getInventory();
    }

    /**
     * {@code @游戏名/资产名} → 造一个自定义物品栈：基底原版物品 + 组件覆盖（{@code item_name} 名字带色 /
     * {@code lore} 描述行）。自定义物品 = 对原版物品的改造、外观用原版，所以只写这两个组件，不碰模型贴图。
     * 认不出 → null，调用方记日志跳过、不掐局。
     */
    private static net.minecraft.world.item.ItemStack customItemStack(String gameName, String key, int count) {
        if (gameName == null || gameName.isEmpty()) {
            return null;
        }
        GameManager gm = GameManager.get();
        GameDefinition def = gm == null ? null : gm.load(gameName);
        // 引用写法统一走 assetNameOf —— 资产名 / @资产名 / 游戏名/资产名 / @游戏名/资产名 四种都认
        GameDefinition.AssetDef a = def == null ? null
                : GameDefinition.assetOf(def.assets(), GameDefinition.assetNameOf(key));
        if (a == null || !a.isItem()) {
            return null;
        }
        var iid = net.minecraft.resources.Identifier.tryParse(a.base());
        var it = iid == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(iid);
        if (it == null) {
            return null;
        }
        var st = new net.minecraft.world.item.ItemStack(it);
        // 名字 / 描述 / 玩家写的 components 层 / 身份标签 —— **唯一一份**在 AssetItem.componentsOf
        //（配方产物那边也用它：两边逐组件一致，「工作台合出来的」与「脚本 give 的」才算同款）。
        applyComponents(st, AssetItem.componentsOf(a));
        // 截上限用**造好的栈**：组件可能改过 max_stack_size（现在虽然只写两样，但别埋雷）
        return st.copyWithCount(Math.min(count, st.getMaxStackSize()));
    }

    /** 把一棵组件 JSON 贴到栈上（走**原版** codec；认不出 = 记日志跳过，不掐局）。 */
    private static void applyComponents(net.minecraft.world.item.ItemStack st, com.google.gson.JsonObject components) {
        net.minecraft.core.component.DataComponentPatch patch = componentPatch(components, "");
        if (patch != null) {
            st.applyComponents(patch);
        }
    }

    /**
     * 组件 JSON → 原版 patch（可空 = 没写 / 解不出来）。走原版 {@code DataComponentPatch.CODEC}
     * （键就是组件 id，省 {@code minecraft:} 也认）—— 原版 110 个组件一个都不用我们写代码。
     *
     * <p>认不出（组件名不存在 / 值不合法）→ 记日志、返回 null（整层跳过）：物品照旧给出去，不掐局。
     */
    static net.minecraft.core.component.DataComponentPatch componentPatch(com.google.gson.JsonElement components, String who) {
        if (components == null || !components.isJsonObject()) {
            return null;
        }
        // ⚠ 必须带注册表上下文：属性 / 附魔 / 药水 / 工具规则这类组件引用注册表，用纯 JsonOps 解析
        // 一律报「not valid in current registry set」→ 整层被跳过（只有字面量型组件能过）。
        // 写法同 PieceLibrary：RegistryOps.create(JsonOps.INSTANCE, registryAccess)。
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                com.mojang.serialization.JsonOps.INSTANCE;
        var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (srv != null) {
            ops = net.minecraft.resources.RegistryOps.create(ops, srv.registryAccess());
        }
        var res = net.minecraft.core.component.DataComponentPatch.CODEC.parse(ops, components);
        if (res.error().isPresent()) {
            TableGame.LOGGER.warn("[对象] {} 的 components 认不出，这一层整块跳过：{}",
                    who == null || who.isEmpty() ? "（无名字）" : who, res.error().get().message());
            return null;
        }
        return res.result().orElse(null);
    }

    /**
     * 覆盖式写一槽：方块容器与背包共用。语义同 {@code set_block}（幂等）—— 数量 ≤ 0 或物品 id 空串 =
     * 清空该槽；取走 / 累加由脚本读-算-写自己拼。数量超堆叠上限 → 截到上限。
     * 认不出 / 槽越界 / 容器不在 → 记日志跳过、不掐局。
     */
    private static void setSlotIn(net.minecraft.world.Container c, int slot, String item, int count,
            String gameName) {
        if (c == null) {
            TableGame.LOGGER.warn("[容器] 写不了：容器不在（人不在 / 那格不是容器 / 没加载）");
            return;
        }
        if (slot < 0 || slot >= c.getContainerSize()) {
            TableGame.LOGGER.warn("[容器] 槽越界：{}（这个容器只有 {} 槽）", slot, c.getContainerSize());
            return;
        }
        if (count <= 0 || item == null || item.isBlank()) {
            c.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);      // 清空该槽
            return;
        }
        var st = itemStackOf(item, count, gameName);
        if (st == null) return;                            // 认不出：itemStackOf 已记日志
        c.setItem(slot, st.copyWithCount(Math.min(count, st.getMaxStackSize())));
    }

    /**
     * 物品 id → 一个栈（数量不截，由调用方按自己口径截）；认不出 → null 并记一行日志。
     * 两副面孔：{@code @游戏名/资产名} = 本局的自定义物品（{@link #customItemStack}）；其余 = 原版注册名。
     * 写容器 / 写背包 / 发物品（{@code give}）都走这一条 —— 「认 id 拿栈」的口径只该有一份。
     */
    private static net.minecraft.world.item.ItemStack itemStackOf(String item, int count, String gameName) {
        if (item == null || item.isBlank() || count <= 0) return null;
        // ⚠ 这一步原来只在名字以 @ 开头时才去找项目资产 ⇒ 裸资产名被当成原版 id 解析 ⇒ 发 / 掉的是原版钻石，
        // 不是声明的那条。现在先查项目资产（assetNameOf 认四种写法），查不到再退回原版 id。
        var custom = customItemStack(gameName, item, count);
        if (custom != null) return custom;
        if (item.startsWith("@")) {
            TableGame.LOGGER.warn("[物品] 认不出的自定义物品：{}（本局游戏 {}）", item, gameName);
            return null;
        }
        var iid = net.minecraft.resources.Identifier.tryParse(item);
        var it = iid == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(iid);
        if (it == null) {
            TableGame.LOGGER.warn("[物品] 认不出的物品 id：{}", item);
            return null;
        }
        return new net.minecraft.world.item.ItemStack(it, count);
    }

    /**
     * 执行脚本要的方块改动（W1）。认不出的 id / 没加载的格子 → 记一行日志跳过，不掐局（脚本写错一个
     * 方块名不该把整局弄停，沉默跳过又难查）。落在这一局的棋盘维度（脚本 {@code dim}，不写 = 主世界）。
     */
    private void applySetBlocks(Session s, List<Interp.SetBlock> list) {
        ServerLevel lv = levelFor(s);
        for (Interp.SetBlock sb : list) {
            var block = resolveBlock(sb.id());
            if (block == null) continue;                     // 认不出：resolveBlock 已记日志
            BlockPos bp = BlockPos.containing(sb.x(), sb.y(), sb.z());
            if (!lv.isLoaded(bp)) continue;                  // 没加载的格子：悄悄跳过（远处棋盘先不问）
            lv.setBlock(bp, block.defaultBlockState(), 3);   // 3 = 更新客户端 + 触发邻居更新
        }
    }

    /** 执行脚本要的「保护 / 解除保护一片区域」—— 改的是 Session 的区域表。 */
    private void applyProtects(Session s, List<Interp.Protect> list) {
        for (Interp.Protect pr : list) {
            long minX = Math.round(Math.min(pr.x1(), pr.x2())), maxX = Math.round(Math.max(pr.x1(), pr.x2()));
            long minY = Math.round(Math.min(pr.y1(), pr.y2())), maxY = Math.round(Math.max(pr.y1(), pr.y2()));
            long minZ = Math.round(Math.min(pr.z1(), pr.z2())), maxZ = Math.round(Math.max(pr.z1(), pr.z2()));
            if (pr.on()) {
                // 同一片再来一次 = **覆盖**（所以「改级别」就是再记一次同坐标、带新级别；不会攒成两片）
                s.protectedRegions.removeIf(r -> sameBox(r, minX, minY, minZ, maxX, maxY, maxZ));
                s.protectedRegions.add(new long[]{minX, minY, minZ, maxX, maxY, maxZ, pr.level()});
                // 记一行：保护有没有落地、盒在哪、几级 —— 全都只看这一行（「挡不了」的排查起点）。
                TableGame.LOGGER.info("[保护] 登记 {} 级保护盒 ({},{},{})-({},{},{})（本局现在 {} 片）",
                        pr.level(), minX, minY, minZ, maxX, maxY, maxZ, s.protectedRegions.size());
                // ⚠ 登记完必须立刻落盘 —— 世界局重开客户端是接回来的（apply 落盘快照，不重跑 on start），
                // 所以「on start 里 protect 一次」的局只要这一下没落盘，下次进存档保护就凭空消失。
                lastSaveMs = 0;
            } else {
                TableGame.LOGGER.info("[保护] 解除保护盒 ({},{},{})-({},{},{})（本局还剩 {} 片）",
                        minX, minY, minZ, maxX, maxY, maxZ, s.protectedRegions.size() - 1);
                lastSaveMs = 0;                                   // 解除也要落盘（同上面那条）
                // 解除保护：同一片（按坐标全等）才摘 —— 脚本用同一组数字调 unprotect 就能摘掉
                s.protectedRegions.removeIf(r -> r[0] == minX && r[1] == minY && r[2] == minZ
                        && r[3] == maxX && r[4] == maxY && r[5] == maxZ);
            }
        }
    }

    /** 两片是不是同一片（只比前 6 位坐标；级别不参与 —— 解除保护不该要求级别写对）。 */
    private static boolean sameBox(long[] r, long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        return r[0] == minX && r[1] == minY && r[2] == minZ && r[3] == maxX && r[4] == maxY && r[5] == maxZ;
    }

    /**
     * 这格被这一局保护到**哪一级**（0 = 没保护 · 1 = 只拦生存 · 2 = 拦生存+冒险 · 3 = 连创造也挡）。
     * 多片重叠取**最高级**（一片一级一片二级 ⇒ 二级胜 —— 重叠时按最严的来，不然二级形同虚设）。
     */
    public int protectedLevel(Session s, net.minecraft.core.BlockPos bp) {
        int lv = 0;
        for (long[] r : s.protectedRegions) {
            if (bp.getX() >= r[0] && bp.getX() <= r[3] && bp.getY() >= r[1]
                    && bp.getY() <= r[4] && bp.getZ() >= r[2] && bp.getZ() <= r[5]) {
                lv = (int) Math.max(lv, r.length > 6 ? r[6] : 1);   // 老档（6 位）= 一级
            }
        }
        return lv;
    }

    /** 这一格对**这个玩家**来说是几级保护（跨局取最高）。0 = 没保护，随便动。 */
    public int protectedLevelFor(net.minecraft.world.entity.player.Player p, net.minecraft.core.BlockPos bp) {
        int lv = 0;
        for (Session s : sessions) lv = Math.max(lv, protectedLevel(s, bp));
        return lv;
    }

    /**
     * 这一下能不能挖（三级保护的口径，全工程只有这一处）：
     * <ul>
     *   <li>没保护 → 能。</li>
     *   <li>**一级** → 只拦生存；冒险交给原版（原版白名单照旧破例）、创造不拦。</li>
     *   <li>**二级** → 拦生存 + 冒险（白名单也不给）。</li>
     *   <li>**三级** → 连创造也挡。</li>
     * </ul>
     * ⚠ 一级「冒险交给原版」= 这里直接放行、不 cancel，别自己再判一遍白名单；二三级才需要我们挡。
     */
    public boolean mayBreak(net.minecraft.server.level.ServerPlayer p, net.minecraft.core.BlockPos bp) {
        int lv = protectedLevelFor(p, bp);
        if (lv == 0) return true;
        if (p.getAbilities().instabuild) return lv < 3;                  // 创造：一/二级不拦，三级拦
        if (lv == 1) return p.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL;
        return false;                                                    // 二级（非创造都挡）/ 三级
    }

    /** 这一下**能不能放**（口径与 {@link #mayBreak} 同一套，只是放置侧没有原版白名单这回事）。 */
    public boolean mayPlace(net.minecraft.server.level.ServerPlayer p, net.minecraft.core.BlockPos bp) {
        int lv = protectedLevelFor(p, bp);
        if (lv == 0) return true;
        if (p.getAbilities().instabuild) return lv < 3;
        if (lv == 1) return p.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL;
        return false;                                                    // 二级（非创造都挡）/ 三级
    }

    /**
     * 这一局的**棋盘维度**（脚本顶层的 {@code dim "tablegame:board"}；没声明 = 主世界）。
     *
     * <p>维度名写错 / 那个维度的数据没加载 → **退回主世界并记一行日志**（不掐局）：玩家看到棋盘铺在主世界，
     * 比「什么都没发生还说不出为什么」好查。
     */
    private ServerLevel levelFor(Session s) {
        return boardLevel(s.eng == null ? "" : s.eng.dimId());
    }

    /**
     * 维度名 → 棋盘世界（空串 = 主世界）。{@link #levelFor} 与建引擎时的
     * {@link #presentNames} 共用这一份（那时还没有 {@code s.eng}，维度只能从脚本声明里读）。
     */
    private ServerLevel boardLevel(String dim) {
        if (dim.isEmpty()) return server.overworld();
        var id = net.minecraft.resources.Identifier.tryParse(dim);
        var key = id == null ? null
                : net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        ServerLevel lv = key == null ? null : server.getLevel(key);
        if (lv == null) {
            TableGame.LOGGER.warn("[世界] 找不到维度 {} —— 本局的棋盘退回主世界", dim);
            return server.overworld();
        }
        return lv;
    }

    /**
     * 按维度注册名取世界（tp 的第二位 / 区域声明的 {@code world}）。认不出 → {@code null}
     * （调用方记一行跳过 —— 这里不退回主世界：「你说要去下界，我给你送主世界」比什么都不做更难查）。
     */
    private ServerLevel levelByName(String dim) {
        var id = net.minecraft.resources.Identifier.tryParse(dim);
        var key = id == null ? null
                : net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        return key == null ? null : server.getLevel(key);
    }

    /**
     * 执行脚本要的「放棋子」（W1）：一个原语兼管生成与移动 —— 本局这枚还没 spawn 过 → 按 {@code pieces} 段的
     * 蓝图造一个棋子物品 {@link com.tablegame.piece.GamePieceEntity#place}；已经 spawn 过 → {@code setPos} 挪过去。
     * 认不出 id / 蓝图空 / 文件不在 → 记一行跳过（不掐局）。
     */
    private void applyPlacePieces(Session s, List<Interp.PlacePiece> list) {
        ServerLevel lv = levelFor(s);
        for (Interp.PlacePiece pp : list) {
            com.tablegame.piece.GamePieceEntity ent = s.pieceEntities.get(pp.id());
            if (ent == null) {
                // 重进存档后续跑：内存账是空的，拿盘上那份 UUID 现找（此刻人在棋盘边、区块已加载）。
                // 认领回来 = 接着挪它；找不到才 spawn 新的 —— 不认领的话同一格会多出一颗孤儿棋子。
                java.util.UUID u = s.pieceUuids.get(pp.id());
                if (u != null && lv.getEntity(u) instanceof com.tablegame.piece.GamePieceEntity gp && gp.isAlive()) {
                    s.pieceEntities.put(pp.id(), gp);
                    ent = gp;
                }
            }
            if (ent != null && ent.isAlive()) {                          // 已经在世界里：挪过去
                ent.setPos(pp.x(), pp.y(), pp.z());
                continue;
            }
            GameDefinition.PieceDef def = null;
            for (GameDefinition.PieceDef d : s.def.pieces()) {
                if (d.id().equals(pp.id())) { def = d; break; }
            }
            if (def == null) {
                TableGame.LOGGER.warn("[棋子] 档里没有 id 为 {} 的棋子（编辑器「棋子」页里加一个）", pp.id());
                continue;
            }
            ItemStack stack = blueprintStack(s, def);
            if (stack == null) continue;                                 // 日志已在 blueprintStack 里打了
            // spawn 出来的那颗也要记 UUID（落盘 / 下次重进按它认领，见上面那段）。
            com.tablegame.piece.GamePieceEntity made = com.tablegame.piece.GamePieceEntity.place(
                    lv, pp.x(), pp.y(), pp.z(), 0f, stack);
            s.pieceEntities.put(pp.id(), made);
            s.pieceUuids.put(pp.id(), made.getUUID());
        }
    }

    /**
     * 放 / 挪一只原版生物（对象页「实体」栏那一类）。一个原语兼管生成与移动：还没放过就按实体的 {@code base}
     * 造一只，已经在世界里就 {@code snapTo} 挪过去；名字认 assets 段里 kind = 自定义实体 那条。
     * ⚠ 立刻生效而不是攒批（作者会紧接着写它的 NBT，攒批时那只还没进账 ⇒ {@code entity_data} 返回 0）。
     */
    private void spawnOne(Session s, String name, double sx, double sy, double sz) {
        ServerLevel lv = levelFor(s);
        // ⚠ 位置一律按方块中心（原版 spawn 内部就是 spawnPos + 0.5）。用整数坐标 = 方块西北角，症状是
        //    「进世界时商人挪到方块角上」—— 首次生成走原版（中心）、认领/挪位置走 setPos（角）⇒ 每进一次世界漂一次。
        double cx = Math.floor(sx) + 0.5, cz = Math.floor(sz) + 0.5;
        net.minecraft.world.entity.Entity ent = s.mobEntities.get(name);
        if (ent != null && ent.isAlive()) {                          // 已经在世界里：挪过去
            ent.snapTo(cx, sy, cz, ent.getYRot(), ent.getXRot());   // snapTo 同原版 spawn：连 prev 位置一起设，不滑一段
            return;
        }
        // 盘上认领：内存账一重进世界就空了，而实体是存档里真存着的 ⇒ 只认内存 = 每进一次世界多生成一只。
        // 生成时给实体打了 {@link #MOB_TAG}+名字 的标签，这里先在本局维度里把它捞回来（捞到 = 认领 + 挪位置）。
        // ⚠ 26.x 的读标签叫 entityTags()（不是 getTags）。
        String tag = MOB_TAG + name;
        for (net.minecraft.world.entity.Entity e : lv.getAllEntities()) {
            if (e.entityTags().contains(tag)) {
                e.snapTo(cx, sy, cz, e.getYRot(), e.getXRot());
                s.mobEntities.put(name, e);
                return;
            }
        }
        GameDefinition.AssetDef a = GameDefinition.assetOf(s.def.assets(),
                GameDefinition.assetNameOf(name));
        if (a == null || a.base() == null || a.base().isEmpty()) {
            TableGame.LOGGER.warn("[实体] 档里没有名为 {} 的实体对象（对象页「实体」栏里加一个）", name);
            return;
        }
        // 原版 id → 实体类型：直接用 EntityType.byString（/summon 用的就是它），比自己拆注册表的
        // Optional<Reference> 少一层（26.x 的 Registry.get 返回的是 Optional<Reference<T>>）。
        var ot = net.minecraft.world.entity.EntityType.byString(a.base());
        if (ot.isEmpty()) {
            TableGame.LOGGER.warn("[实体] 认不出的原版实体 id：{}（对象页「实体」栏重新挑一个）", a.base());
            return;
        }
        net.minecraft.world.entity.EntityType<?> type = ot.get();
        net.minecraft.world.entity.Entity made = type.spawn(lv,
                BlockPos.containing(sx, sy, sz),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (made == null) {
            TableGame.LOGGER.warn("[实体] {} 造不出来（原版这次不给创建）", a.base());
            return;
        }
        made.addTag(tag);                                            // 下次进世界靠它认领（见上）
        s.mobEntities.put(name, made);
    }

    /**
     * 脚本要的「把这张卡放到那儿」（{@code place_card}）—— 一个原语兼管生成与挪位置。三件事：① 牌名认
     * 内存账 / 盘上 UUID（重进存档后认领回来，不重复生成）② 卡 id → 档里 {@code cards} 段的卡面 / 卡背
     * ③ 朝向：写「朝谁」就当场算角度。
     */
    private void applyPlaceCards(Session s, List<Interp.PlaceCard> list) {
        ServerLevel lv = levelFor(s);
        for (Interp.PlaceCard pc : list) {
            float yaw = (float) (pc.yawGiven() ? pc.yaw() : yawFacing(s, pc));
            com.tablegame.card.CardEntity ent = s.cardEntities.get(pc.name());
            if (ent == null) {
                // 重进存档续跑：内存账是空的，拿盘上那份 UUID 现找（同棋子的认领）。
                UUID u = s.cardUuids.get(pc.name());
                if (u != null && lv.getEntity(u) instanceof com.tablegame.card.CardEntity ce && ce.isAlive()) {
                    s.cardEntities.put(pc.name(), ce);
                    ent = ce;
                }
            }
            if (ent != null && ent.isAlive()) {                          // 已经在世界里：挪过去 + 换牌
                ent.setPos(pc.x(), pc.y(), pc.z());
                ent.setYRot(yaw);
                ent.setCard(cardArt(s, pc.card(), false), cardArt(s, pc.card(), true), (float) pc.scale());
                continue;
            }
            GameDefinition.CardDef cd = cardDef(s, pc.card());
            if (cd == null) {
                TableGame.LOGGER.warn("[卡牌] 档里没有 id 为 {} 的卡（对象页「卡牌」栏里加一张）", pc.card());
                continue;
            }
            com.tablegame.card.CardEntity made = com.tablegame.card.CardEntity.place(
                    lv, pc.x(), pc.y(), pc.z(), yaw,
                    cd.art() == null ? "" : cd.art(), cd.back(), (float) pc.scale());
            s.cardEntities.put(pc.name(), made);
            s.cardUuids.put(pc.name(), made.getUUID());
        }
    }

    /** 「把这几张牌翻面」（原语 {@code flip_card}）：账上找得到就 yRot + 180，认不出记一行跳过。 */
    private void applyFlipCards(Session s, List<Interp.FlipCard> list) {
        for (Interp.FlipCard fc : list) {
            com.tablegame.card.CardEntity ent = s.cardEntities.get(fc.name());
            if (ent == null || !ent.isAlive()) {
                TableGame.LOGGER.warn("[卡牌] flip_card 认不出那张牌：{}（先 place_card 放出来）", fc.name());
                continue;
            }
            ent.flip();
        }
    }

    /** 牌面朝谁的角度（度）：牌的位置 → 那个人/那只实体，算出来的方向就是牌面法线要指的方向。 */
    private double yawFacing(Session s, Interp.PlaceCard pc) {
        double tx = 0, tz = 0;
        boolean found = false;
        if (pc.who() != null && !pc.who().isEmpty()) {
            net.minecraft.server.level.ServerPlayer p = playerByName(pc.who());
            if (p != null) {
                tx = p.getX();
                tz = p.getZ();
                found = true;
            } else {
                net.minecraft.world.entity.Entity e = s.mobEntities.get(pc.who());
                if (e != null && e.isAlive()) {
                    tx = e.getX();
                    tz = e.getZ();
                    found = true;
                }
            }
            if (!found) {
                TableGame.LOGGER.warn("[卡牌] place_card 的「朝谁」认不出：{}（算成 0 度朝 +Z）", pc.who());
            }
        }
        if (!found) return pc.yaw();
        double dx = tx - pc.x();
        double dz = tz - pc.z();
        if (dx == 0 && dz == 0) return 0;
        return Math.toDegrees(Math.atan2(dx, -dz));
    }

    /** 卡 id → CardDef（档里 cards 段；认引用写法走 assetNameOf 归一）。 */
    private static GameDefinition.CardDef cardDef(Session s, String card) {
        if (s.def.cards() == null) return null;
        String want = GameDefinition.assetNameOf(card);
        for (GameDefinition.CardDef cd : s.def.cards()) {
            if (cd.id().equals(card) || cd.id().equals(want)) return cd;
        }
        return null;
    }

    /** 卡 id → 那一面的画板引用（空 = 没填）。 */
    private static String cardArt(Session s, String card, boolean back) {
        GameDefinition.CardDef cd = cardDef(s, card);
        if (cd == null) return "";
        String a = back ? cd.back() : cd.art();
        return a == null ? "" : a;
    }

// ===== 实体画面：画面 = 一组挂在锚点上的实体 =====

    /**
     * 脚本要的「把这块画面挂到那个锚点上」（{@code show(锚点, "名")}）。幂等：同一（画面, 锚点）已在账上
     * 就什么都不做；新的才建一条实例，组件实体由 {@link #maintainEntityStages} 按需生成。
     */
    private void applyShowEntities(Session s, List<Interp.ShowEntity> list) {
        for (Interp.ShowEntity se : list) {
            if (s.eng.entityScreens().get(se.screen()) == null) {
                TableGame.LOGGER.warn("[实体画面] 档里没有叫 {} 的实体画面", se.screen());
                continue;
            }
            boolean exists = false;
            for (EntityStage st : s.entityStages) {
                if (st.screen.equals(se.screen()) && st.anchor.equals(se.anchor())) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;
            s.entityStages.add(new EntityStage(se.screen(), se.anchor()));
            TableGame.LOGGER.info("[实体画面] {} 挂到 {}", se.screen(), se.anchor());
        }
    }

    /** 脚本要的「收起实体画面」（{@code hide("名")}；名字空 = 全收）。 */
    private void applyHideEntities(Session s, List<Interp.HideEntity> list) {
        for (Interp.HideEntity he : list) {
            for (java.util.Iterator<EntityStage> it = s.entityStages.iterator(); it.hasNext();) {
                EntityStage st = it.next();
                if ((he.screen().isEmpty() || st.screen.equals(he.screen()))
                        && (he.who().isEmpty() || st.anchor.equals(he.who()))) {      // 两参形态：只收他那份
                    dropEntityStage(st);
                    it.remove();
                }
            }
        }
    }

    /**
     * 每 tick 维护一趟：跟随锚点摆位 · 内容变了就重写 · 锚点没了 / 超距就收掉。内容求值走
     * {@link Interp#expandEntity}（按锚点求值 ⇒ 每人一份）；文本用原版 {@code text_display} 渲染，
     * 内容经 NBT 口写进去（原版那几个 setter 都是 private）。
     */
    private void maintainEntityStages(Session s) {
        if (s.entityStages.isEmpty()) return;
        for (java.util.Iterator<EntityStage> it = s.entityStages.iterator(); it.hasNext();) {
            EntityStage st = it.next();
            net.minecraft.world.entity.Entity anchor = entityByName(s, st.anchor);
            if (anchor == null) {                       // 锚点没了（掉线 / 被打死 / 退局）⇒ 画面收掉
                dropEntityStage(st);
                it.remove();
                continue;
            }
            Interp.EntityView v;
            try {
                v = s.eng.expandEntity(st.screen,
                        anchor instanceof ServerPlayer sp ? sp.getName().getString() : "");
            } catch (Ast.ScriptError e) {
                TableGame.LOGGER.warn("[实体画面] {} 展开失败：{}", st.screen, e.getMessage());
                dropEntityStage(st);
                it.remove();
                continue;
            }
            if (v == null) {
                dropEntityStage(st);
                it.remove();
                continue;
            }
            double[] base = stageBase(anchor, v, stageYaw(st, anchor, v));
            // 远离收起：画面（不跟随时就钉在那处）离锚点超过 away 格 ⇒ 收掉。
            // ponytail: 收掉就没了（再走一次 show 才回来）；要「回来」的玩法再加。
            if (v.away() > 0 && anchor.distanceToSqr(base[0], base[1], base[2]) > v.away() * v.away()) {
                dropEntityStage(st);
                it.remove();
                continue;
            }
            double syaw = stageYaw(st, anchor, v);                 // 画面基准朝向（face 2 在这里冻结一次）
            for (Interp.EntityCompView c : v.comps()) {
                if (!c.kind().equals("text") && !c.kind().equals("card") && !c.kind().equals("item")) {
                    continue;                                    // 认不出的组件种类：跳过（解析期已拦过）
                }
                // ★ 槽位为空（`base` 求值成空串）⇒ 这个组件**不显示**（实体 + 命中代理一起收）。
                //   数量可变的东西就靠它：**声明固定几个槽位，脚本让多余的槽位空着**（21点 手牌用）。
                if (!c.kind().equals("text") && c.base().isEmpty()) {
                    dropComp(st, c.asset());
                    continue;
                }
                double[] pos = compPos(base, v, c, syaw);
                net.minecraft.world.entity.Entity e = st.comps.get(c.asset());
                if (e == null || !e.isAlive()) {
                    e = switch (c.kind()) {
                        case "card" -> spawnCardEntity(s, pos, anchor, c);
                        case "item" -> spawnItemEntity(s, pos, c);
                        default -> spawnTextEntity(s, pos);
                    };
                    if (e == null) continue;
                    st.comps.put(c.asset(), e);
                    st.sigs.remove(c.asset());
                } else {
                    e.setPos(pos[0], pos[1], pos[2]);
                    if (e instanceof com.tablegame.card.CardEntity ce) {
                        // 卡牌朝向：写了 rot 就按「画面朝向 + 组件角度」，不写还是正面朝锚点（老行为）
                        if (c.hasRot()) {
                            ce.setTilt((float) c.rx(), (float) c.rz());
                            ce.setYRot((float) (syaw + c.ry()));
                        } else {
                            ce.setTilt(0, 0);
                            ce.setYRot((float) yawToward(pos, anchor));
                        }
                    }
                    if (e instanceof net.minecraft.world.entity.Display idisp) {
                        idisp.setYRot(0);                                  // billboard 自己会转，别和 yRot 打架
                    }
                }
                // 命中代理：display 系的碰撞盒是 0×0（准星永远打不中它），原版的正解是旁边杵一只 interaction
                // 实体当命中盒。卡牌自己就有碰撞盒，不用配。
                if (!c.kind().equals("card")) {
                    net.minecraft.world.entity.Entity h = st.hits.get(c.asset());
                    if (h == null || !h.isAlive()) {
                        h = spawnHitEntity(s, pos, c);
                        if (h != null) st.hits.put(c.asset(), h);
                    } else {
                        h.setPos(pos[0], pos[1], pos[2]);
                    }
                }
                String sig = (c.kind().equals("text") ? c.text() : c.kind() + "|" + c.base())
                        + "|" + c.scale() + "|" + c.hasRot() + "|" + c.rx() + "," + c.ry() + "," + c.rz();
                if (!c.mark().isEmpty() || !st.marks.containsKey(c.asset())) {
                    st.marks.put(c.asset(), c.mark());        // 组件点击那条路靠它（每 tick 刷新，跟着脚本变）
                }
                if (!sig.equals(st.sigs.get(c.asset()))) {
                    double yaw = syaw;
                    switch (c.kind()) {
                        case "card" -> writeCard((com.tablegame.card.CardEntity) e, s, c);
                        case "item" -> setItemDisplay(e, c, s.def.name(), yaw);
                        default -> setTextDisplay(e, c, yaw);
                    }
                    st.sigs.put(c.asset(), sig);
                }
            }
        }
    }

    /**
     * 画面基准朝向（度）：`face 0` = 世界轴（0）· `face 1` = 每帧跟锚点 · `face 2` = **show 那一刻冻结**。
     *
     * <p>冻结值在**锚点第一次认得出**的那一 tick 记下（`show` 与实体到齐差一 tick，也不会被记成 0）。
     */
    private static double stageYaw(EntityStage st, net.minecraft.world.entity.Entity anchor,
            Interp.EntityView v) {
        if (v.faceMode() == 2) {
            if (Double.isNaN(st.frozenYaw)) st.frozenYaw = anchor.getYRot();
            return st.frozenYaw;
        }
        return v.faceMode() >= 1 ? anchor.getYRot() : 0;
    }

    /** 画面基准点：锚点坐标 + 偏移（face ≥ 1 = 按 {@code yaw} 算「前方 / 右手 / 上方」，face 0 = 世界坐标）。 */
    private static double[] stageBase(net.minecraft.world.entity.Entity anchor, Interp.EntityView v,
            double yaw0) {
        double dx = v.dx(), dz = v.dz();
        if (v.faceMode() > 0) {
            double yaw = Math.toRadians(yaw0);
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);      // 朝向（原版 yaw 0 = +Z）
            double rx = -fz, rz = fx;                            // 右手方向
            double ox = rx * v.dx() + fx * v.dz();
            double oz = rz * v.dx() + fz * v.dz();
            dx = ox;
            dz = oz;
        }
        return new double[]{anchor.getX() + dx, anchor.getY() + v.dy(), anchor.getZ() + dz};
    }

    /**
     * 组件在画面局部系里的位置（x 沿画面右手 · y 向上 · z 沿画面前方）。局部系朝向与 {@link #stageBase}
     * 同一口径（face 1 = 跟锚点朝向 · face 0 = 世界轴，前 = +Z、右 = +X）。
     */
    private static double[] compPos(double[] base, Interp.EntityView v, Interp.EntityCompView c,
            double yaw0) {
        double fx = 0, fz = 1;                                   // 画面「前」的基向量（缺省 +Z 南）
        if (v.faceMode() > 0) {
            double yaw = Math.toRadians(yaw0);
            fx = -Math.sin(yaw);
            fz = Math.cos(yaw);
        }
        double rx = -fz, rz = fx;                                // 「右」= 前顺时针 90°
        return new double[]{base[0] + rx * c.ax() + fx * c.az(),
                            base[1] + c.ay(),
                            base[2] + rz * c.ax() + fz * c.az()};
    }

    /** 生成一只原版 text_display（画面上的一个文字组件）。 */
    private net.minecraft.world.entity.Entity spawnTextEntity(Session s, double[] pos) {
        ServerLevel lv = levelFor(s);
        net.minecraft.world.entity.Display.TextDisplay td =
                net.minecraft.world.entity.EntityType.TEXT_DISPLAY.spawn(lv,
                        net.minecraft.core.BlockPos.containing(pos[0], pos[1], pos[2]),
                        net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (td == null) {
            TableGame.LOGGER.warn("[实体画面] text_display 造不出来（原版这次不给创建）");
            return null;
        }
        td.setPos(pos[0], pos[1], pos[2]);
        return td;
    }

    /** 生成一张卡牌实体（画面上的一个 card 组件），正面朝锚点。 */
    private net.minecraft.world.entity.Entity spawnCardEntity(Session s, double[] pos,
            net.minecraft.world.entity.Entity anchor, Interp.EntityCompView c) {
        GameDefinition.CardDef cd = cardDef(s, c.base());
        if (cd == null) {
            TableGame.LOGGER.warn("[实体画面] 档里没有 id 为 {} 的卡（对象页「卡牌」栏里加一张）", c.base());
            return null;
        }
        return com.tablegame.card.CardEntity.place(levelFor(s), pos[0], pos[1], pos[2],
                (float) yawToward(pos, anchor), cd.art() == null ? "" : cd.art(), cd.back(),
                (float) c.scale());
    }

    /** 生成一只原版 item_display（画面上的一个 item 组件 —— 六把铁剑那种）。 */
    private net.minecraft.world.entity.Entity spawnItemEntity(Session s, double[] pos,
            Interp.EntityCompView c) {
        ServerLevel lv = levelFor(s);
        net.minecraft.world.entity.Display.ItemDisplay id =
                net.minecraft.world.entity.EntityType.ITEM_DISPLAY.spawn(lv,
                        net.minecraft.core.BlockPos.containing(pos[0], pos[1], pos[2]),
                        net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (id == null) {
            TableGame.LOGGER.warn("[实体画面] item_display 造不出来（原版这次不给创建）");
            return null;
        }
        id.setPos(pos[0], pos[1], pos[2]);
        return id;
    }

    /**
     * 生成一只原版 {@code interaction}（组件的命中代理）—— 玩家点组件时点到的是它。尺寸分母跟组件的
     * {@code scale} 走（盒子 = 2.0×0.6 格再乘 1/scale）。
     * ponytail: 盒子是估的（长文字会超出）；要精确再加组件参数（`hit 宽 高`），先按估的用。
     */
    private net.minecraft.world.entity.Entity spawnHitEntity(Session s, double[] pos,
            Interp.EntityCompView c) {
        ServerLevel lv = levelFor(s);
        net.minecraft.world.entity.Interaction it = net.minecraft.world.entity.EntityType.INTERACTION.spawn(lv,
                net.minecraft.core.BlockPos.containing(pos[0], pos[1], pos[2]),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (it == null) {
            TableGame.LOGGER.warn("[实体画面] interaction 造不出来 ⇒ 这个组件点不着（看得见点不着）");
            return null;
        }
        it.setPos(pos[0], pos[1], pos[2]);
        float k = (float) (1.0 / Math.max(0.05, c.scale()));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putFloat("width", 2.0F * k);
        tag.putFloat("height", 0.6F * k);
        tag.putBoolean("response", false);        // 原版别自回话（回话交脚本）
        applyNbt(it, tag);
        return it;
    }

    /** 换牌：卡牌组件的 base / scale 变了就把这张牌改成新卡（生成时已写好，这里管变化）。 */
    private static void writeCard(com.tablegame.card.CardEntity ce, Session s, Interp.EntityCompView c) {
        GameDefinition.CardDef cd = cardDef(s, c.base());
        if (cd == null) return;
        ce.setCard(cd.art() == null ? "" : cd.art(), cd.back(), (float) c.scale());
    }

    /**
     * 物品组件的内容：{@code item}（一整份物品栈的 NBT）+ {@code transformation.scale}，与文本同一条 NBT 口。
     * 物品栈用 {@code itemStackOf} 造（认项目资产 / 原版 id 四条写法），再用 {@code ItemStack.CODEC} 编成 NBT ——
     * 不手搓字段形状（形状漂移是静默 bug 的老家）。
     */
    private void setItemDisplay(net.minecraft.world.entity.Entity e, Interp.EntityCompView c,
            String gameName, double yaw) {
        net.minecraft.world.item.ItemStack stack = itemStackOf(c.base(), 1, gameName);
        if (stack == null || stack.isEmpty()) {
            TableGame.LOGGER.warn("[实体画面] item 组件认不出这个物品：{}（写原版 id 或项目里的资产名）", c.base());
            return;
        }
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.world.item.ItemStack.CODEC.encodeStart(
                        net.minecraft.nbt.NbtOps.INSTANCE, stack)
                .result().ifPresent(t -> tag.put("item", t));
        tag.putString("billboard", c.hasRot() ? "fixed" : "center");   // 写了 rot = 固定朝向，否则面向观察者
        putTransform(tag, c, yaw);
        applyNbt(e, tag);
    }

    /** 把内容写进 text_display：走 NBT 口（见 {@link #applyNbt}）。写了 rot 就不再 billboard。 */
    private static void setTextDisplay(net.minecraft.world.entity.Entity e, Interp.EntityCompView c,
            double yaw) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        String json = net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
                        net.minecraft.network.chat.Component.literal(c.text()))
                .result().map(Object::toString).orElse("{\"text\":\"\"}");
        tag.putString("text", json);
        tag.putString("billboard", c.hasRot() ? "fixed" : "center");   // 写了 rot = 固定朝向，否则面向观察者
        putTransform(tag, c, yaw);
        applyNbt(e, tag);
    }

    /**
     * 把 {@code transformation}（尺寸 + 可选旋转）写进 NBT。尺寸：值越小越大（1 = 原尺寸）。旋转只在作者写了
     * {@code rot} 时才写。⚠ 形状交给 {@code Transformation.EXTENDED_CODEC} 生成，不手搓字段名。
     */
    private static void putTransform(net.minecraft.nbt.CompoundTag tag, Interp.EntityCompView c,
            double yaw) {
        float sc = (float) (1.0 / Math.max(0.05, c.scale()));
        org.joml.Quaternionf q = new org.joml.Quaternionf();
        if (c.hasRot()) {
            q.rotateY((float) Math.toRadians(yaw + c.ry()))
                    .rotateX((float) Math.toRadians(c.rx()))
                    .rotateZ((float) Math.toRadians(c.rz()));
        }
        com.mojang.math.Transformation tr = new com.mojang.math.Transformation(
                new org.joml.Vector3f(0f, 0f, 0f), q,
                new org.joml.Vector3f(sc, sc, sc), new org.joml.Quaternionf());
        com.mojang.math.Transformation.EXTENDED_CODEC
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, tr)
                .result().ifPresent(t -> tag.put("transformation", t));
    }

    /**
     * 把一段 NBT 合进实体：{@code TagValueInput.create} + {@code entity.load}，与 {@code /data merge entity}
     * 同一条路（原版 display 系实体的 setter 在 26.x 都是 private）。
     * ⚠ {@code load} 会换掉 UUID ⇒ 存旧值再写回。
     */
    private static void applyNbt(net.minecraft.world.entity.Entity e, net.minecraft.nbt.CompoundTag tag) {
        try (net.minecraft.util.ProblemReporter.ScopedCollector reporter =
                     new net.minecraft.util.ProblemReporter.ScopedCollector(e.problemPath(), TableGame.LOGGER)) {
            java.util.UUID u = e.getUUID();
            e.load(net.minecraft.world.level.storage.TagValueInput.create(reporter,
                    e.registryAccess(), tag));
            e.setUUID(u);
        }
    }

    /** 从 pos 看向目标的 yaw（度）—— 卡牌正面朝锚点就靠它（与 {@code place_card} 的朝向同一口径）。 */
    private static double yawToward(double[] pos, net.minecraft.world.entity.Entity target) {
        double dx = target.getX() - pos[0];
        double dz = target.getZ() - pos[2];
        if (dx == 0 && dz == 0) return 0;
        return Math.toDegrees(Math.atan2(dx, -dz));
    }

    /** 锚点名 → 实体（先查在线玩家，再查本局实体账 —— 与 {@code health(谁, …)} 那一套同口径）。 */
    private net.minecraft.world.entity.Entity entityByName(Session s, String name) {
        ServerPlayer p = playerByName(name);
        if (p != null && sessionOf(p) == s) return p;
        net.minecraft.world.entity.Entity e = s.mobEntities.get(name);
        return e != null && e.isAlive() ? e : null;
    }

    /** 收掉一条实体画面的所有组件实体（局末 / hide / 锚点没了 / 超距都用它）。 */
    private static void dropEntityStage(EntityStage st) {
        for (net.minecraft.world.entity.Entity e : st.comps.values()) {
            if (e != null && e.isAlive()) e.discard();
        }
        for (net.minecraft.world.entity.Entity e : st.hits.values()) {
            if (e != null && e.isAlive()) e.discard();
        }
        st.comps.clear();
        st.hits.clear();
    }

    /** 收掉**一个**组件的实体 + 命中代理 + 签名（槽位求值为空 = 这个组件不显示）。 */
    private static void dropComp(EntityStage st, String asset) {
        net.minecraft.world.entity.Entity e = st.comps.remove(asset);
        if (e != null && e.isAlive()) e.discard();
        net.minecraft.world.entity.Entity h = st.hits.remove(asset);
        if (h != null && h.isAlive()) h.discard();
        st.sigs.remove(asset);
    }

    /** 本局的一块实体画面实例（画面名 + 锚点名 = 一条；同一画面可以挂好几个锚点）。 */
    private static final class EntityStage {
        final String screen;
        final String anchor;
        /** `face 2` 冻结下来的画面朝向（度）；NaN = 还没记过（见 {@link #stageYaw}）。 */
        double frozenYaw = Double.NaN;
        /** 组件资产名 → 实体。 */
        final Map<String, net.minecraft.world.entity.Entity> comps = new java.util.LinkedHashMap<>();
        /** 组件资产名 → 上次写进去的签名（文本 / 卡牌引用 + 尺寸；变了才重写）。 */
        final Map<String, String> sigs = new java.util.LinkedHashMap<>();
        /** 组件资产名 → 命中代理（interaction；非卡牌组件才有）。 */
        final Map<String, net.minecraft.world.entity.Entity> hits = new java.util.LinkedHashMap<>();
        /** 组件资产名 → 它的 mark（点击回投的身份值；空 = 纯展示，组件点击那条路读它）。 */
        final Map<String, String> marks = new java.util.LinkedHashMap<>();

        EntityStage(String screen, String anchor) {
            this.screen = screen;
            this.anchor = anchor;
        }
    }

    /**
     * 执行脚本要的「把这条文本发给谁」（文本对象 {@code text 资产名 { body … mark … }}）：① 按资产名查项目档
     * （正文在 {@code lore}、标记在 {@code bp}）② 组 Component 挂 {@code runCommand("tablegame pick 标记")}
     * ③ 按 {@code who} 发（`all` / 空串 = 全员，席位名 = 只发他；标记空 = 发出去但不可点）。
     */
    private void applySayMarks(Session s, List<Interp.SayMark> list) {
        for (Interp.SayMark sm : list) {
            GameDefinition.AssetDef a = GameDefinition.assetOf(s.def.assets(),
                    GameDefinition.assetNameOf(sm.name()));
            if (a == null || !AssetStore.KIND_TEXT.equals(a.kind())) {
                TableGame.LOGGER.warn("[文本] 档里没有名为 {} 的文本对象（对象页「文本」栏里加一条）", sm.name());
                continue;
            }
            String body = a.lore().isEmpty() ? a.name() : String.join("\n", a.lore());
            String mk = a.bp() != null && a.bp().has("mark") && a.bp().get("mark").isJsonPrimitive()
                    ? a.bp().get("mark").getAsString() : "";
            Style st = mk.isEmpty() ? Style.EMPTY                       // 没标记：发出去就完了，不可点
                    : Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/tablegame pick " + mk));
            Component msg = Component.literal(ColorText.mask(body)).withStyle(st);
            String who = sm.who() == null ? "" : sm.who();
            ServerPlayer one = who.isEmpty() || who.equals("all") ? null : playerNamed(s, who);
            if (one != null) {
                one.sendSystemMessage(msg);
                continue;
            }
            for (ServerPlayer pl : recipients(s)) pl.sendSystemMessage(msg);
        }
    }

    /**
     * 执行脚本要的「开 / 关幽灵预览」（原语 {@code ghost} / {@code ghost_off}）。三件事：① 只认局内在线的
     * 那个玩家（认不出 → 记一行跳过）；② 按「区域 + 落点 + 开/关」去重（玩家侧 {@code on look} 每换一格
     * 就调一次 ghost，视线没换格不重发；去重账住 Session，掉线摘掉）；③ 未捕获 / 没这条区域 → 不发。
     *
     * <p>快照数据一个字节都不发：收包客户端此刻就在局里，手上已有整份定义 ⇒ 只发「哪条区域 + 落点」，
     * 客户端自己查（见 {@link AreaGhostPackets}）。
     */
    private void applyGhosts(Session s, List<Interp.Ghost> list) {
        for (Interp.Ghost g : list) {
            ServerPlayer pl = playerNamed(s, g.who());
            if (pl == null) {
                TableGame.LOGGER.warn("[幽灵] 找不到局内在线的玩家「{}」（ghost 的目标限定局内在线玩家）", g.who());
                continue;
            }
            String key = g.on() ? g.area() + "@" + g.x() + "," + g.y() + "," + g.z() : "off";
            if (key.equals(s.ghostKey.get(pl.getUUID()))) continue;       // 与上一次一模一样：不重发
            if (g.on() && !hasCapturedArea(s, g.area())) {
                TableGame.LOGGER.warn("[幽灵] 档里没有（或还没捕获）区域「{}」—— 不发预览", g.area());
                continue;
            }
            s.ghostKey.put(pl.getUUID(), key);
            PacketDistributor.sendToPlayer(pl, new AreaGhostPackets.GhostPayload(
                    s.gameName, g.area(), g.x(), g.y(), g.z(), g.on()));
        }
    }

    /** 档里有没有这条**已捕获**的区域（幽灵靠它烘快照）。 */
    private static boolean hasCapturedArea(Session s, String area) {
        GameDefinition.AreaDef a = s.def == null ? null : GameDefinition.areaOf(s.def.areas(), area);
        return a != null && a.captured();
    }

    /** 此刻在局里、叫这个名字的玩家（没有 → null）；席位名 → 玩家就靠它（口径同 {@code HubSink.messageTo}）。 */
    private ServerPlayer playerNamed(Session s, String name) {
        for (ServerPlayer pl : recipients(s)) {
            if (pl.getName().getString().equals(name)) return pl;
        }
        return null;
    }

    /**
     * 脚本放出来的那只被打死了（{@code on entity} 的第二条来源）。与「右键它」共用同一个 {@code on entity}，
     * 靠 {@code edead} 分：1 = 打死 / 0 = 右键。只认本局账上那只（{@link Session#mobEntities}）—— 野生的
     * 被打死不该惊动脚本。从账上摘掉后脚本再 {@code spawn_mob} 会重新放一只（「打死 → 补货」的写法基础）。
     */
    public void mobDead(net.minecraft.world.entity.LivingEntity dead, ServerPlayer killer) {
        for (Session s : sessions) {
            if (s.eng == null || dead.level() != levelFor(s)) continue;
            String name = "";
            for (var e : s.mobEntities.entrySet()) {
                if (e.getValue() == dead) { name = e.getKey(); break; }
            }
            if (name.isEmpty()) return;                              // 不是脚本放的那只：不惊动脚本
            s.mobEntities.remove(name);
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType());
            BlockPos bp = dead.blockPosition();
            s.eng.acceptEntity(killer == null ? "" : killer.getName().getString(),
                    key == null ? "" : key.toString(), bp.getX(), bp.getY(), bp.getZ(), name, 1);
            return;
        }
    }

    /**
     * 按棋子的 {@code blueprint}（形如 {@code 组/蓝图名}）从蓝图库取那份棋子物品；取不到（格式不对 /
     * 文件不在 / 蓝图库没初始化）→ 记日志返回 null。蓝图库挂在画板总管上（{@code BoardManager.pieceLibrary()}）。
     */
    private ItemStack blueprintStack(Session s, GameDefinition.PieceDef def) {
        String bp = def.blueprint() == null ? "" : def.blueprint().trim();
        // @资产名 = 本项目 assets 段里的「模型美术」（组件库资源，bp 本体就在档里）：
        // assetOf 查到 → pieceStackFromJson 直接造栈（蓝图库 load 也是这条路，零新协议）。
        // 认不出（没这条 / 不是模型美术 / 蓝图数据坏）→ 日志跳过，与「蓝图库找不到」同口径。
        if (bp.startsWith("@") || bp.indexOf('/') > 0) {
            // 引用写法统一 —— 蓝图引用也可以是 资产名 / @资产名 / 游戏名/资产名（都归一成资产名再查）
            GameDefinition.AssetDef a = GameDefinition.assetOf(s.def.assets(), GameDefinition.assetNameOf(bp));
            if (a == null || a.bp() == null) {
                TableGame.LOGGER.warn("[棋子] 本项目组件库里没有可用的模型美术：{}（棋子 {}）", bp, def.id());
                return null;
            }
            ItemStack stack = com.tablegame.piece.PieceLibrary.pieceStackFromJson(
                    a.bp().toString(), server.registryAccess());
            if (stack == null) {
                TableGame.LOGGER.warn("[棋子] 模型美术 {} 的蓝图数据解析失败（棋子 {}）", bp, def.id());
                return null;
            }
            return withPieceScale(stack, def);
        }
        int slash = bp.indexOf('/');
        if (slash <= 0 || slash == bp.length() - 1) {
            TableGame.LOGGER.warn("[棋子] 棋子 {} 的蓝图没设好（现在：{}）—— 在编辑器「棋子」页选一个", def.id(), bp);
            return null;
        }
        BoardManager bm = BoardManager.get();
        if (bm == null) return null;
        ItemStack stack = bm.pieceLibrary().load(bp.substring(0, slash), bp.substring(slash + 1), server.registryAccess());
        if (stack == null) {
            TableGame.LOGGER.warn("[棋子] 蓝图库里找不到 {}/{}（棋子 {}）", bp.substring(0, slash), bp.substring(slash + 1), def.id());
            return null;
        }
        return withPieceScale(stack, def);
    }

    /** 棋子「尺寸」倍率（棋子编辑页那个自由数字框）：乘在蓝图 scale 上 = 整枚缩放（两条造栈路共用）。 */
    private static ItemStack withPieceScale(ItemStack stack, GameDefinition.PieceDef def) {
        // 渲染侧零改动 —— GamePieceEntityRenderer 自己算「每体素 = 1/(16*scale) 格」，scale 越小每块越大。
        com.tablegame.piece.PieceData data = com.tablegame.piece.PieceData.get(stack);
        com.tablegame.piece.PieceData.VoxelData vox = data.voxels();
        float scaled = def.effectiveScale(vox.scale());
        if (Math.abs(scaled - vox.scale()) > 1e-6F) {
            com.tablegame.piece.PieceData.set(stack, new com.tablegame.piece.PieceData(data.selection(),
                    new com.tablegame.piece.PieceData.VoxelData(vox.sizeX(), vox.sizeY(), vox.sizeZ(),
                            scaled, vox.palette(), vox.paletteIds()), data.meta()));
        }
        return stack;
    }

    /** 方块 id 文本 → Block（认不出记一行日志、返回 null；`set_block` 与 `fill` 共用这一处）。 */
    private static net.minecraft.world.level.block.Block resolveBlock(String id) {
        var bid = net.minecraft.resources.Identifier.tryParse(id);
        var block = bid == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(bid);
        if (block == null) TableGame.LOGGER.warn("[世界] 认不出的方块 id：{}", id);
        return block;
    }

    /**
     * 执行脚本要的「立标签」（W3）：那格立一块木牌并写第一行。已有牌子就只改文本（幂等）；不是牌子就摆
     * 一块（会覆盖那格 —— 脚本该标在目标格的上方一格）。牌子会留在世界上。
     */
    private void applyLabels(Session s, List<Interp.Label> list) {
        ServerLevel lv = levelFor(s);
        for (Interp.Label lb : list) {
            BlockPos bp = BlockPos.containing(lb.x(), lb.y(), lb.z());
            if (!lv.isLoaded(bp)) continue;
            if (!(lv.getBlockState(bp).getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
                lv.setBlock(bp, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
            }
            if (lv.getBlockEntity(bp) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sbe) {
                // `&` 颜色码同样生效（与 say 拉齐：立牌是最常被剧本写文字的地方之一）。
                var st = sbe.getFrontText().setMessage(0, Component.literal(ColorText.mask(lb.text())));
                sbe.setText(st, true);                       // true = 正面
            }
        }
    }

    /**
     * 一次 {@code fill} 最多改多少格 —— 抄原版 {@code max_block_modifications} 默认值 32768。
     * 脚本坐标是表达式，手滑写出 {@code fill(0,0,0, 9999,9999,9999)} 能一次点爆服务器；超了记一行跳过。
     */
    private static final int MAX_FILL = 32768;

    /**
     * 执行脚本要的区域填充（W1，抄原版 {@code /fill}）。两个角点不分先后（取 min/max），体积上限 {@link #MAX_FILL}。
     */
    private void applyFills(Session s, List<Interp.Fill> list) {
        ServerLevel lv = levelFor(s);
        for (Interp.Fill f : list) {
            var block = resolveBlock(f.id());
            if (block == null) continue;
            int xa = (int) Math.min(f.x1(), f.x2()), xb = (int) Math.max(f.x1(), f.x2());
            int ya = (int) Math.min(f.y1(), f.y2()), yb = (int) Math.max(f.y1(), f.y2());
            int za = (int) Math.min(f.z1(), f.z2()), zb = (int) Math.max(f.z1(), f.z2());
            long vol = (long) (xb - xa + 1) * (yb - ya + 1) * (zb - za + 1);   // long：防 int 乘法溢出
            if (vol > MAX_FILL) {
                TableGame.LOGGER.warn("[世界] fill 一次最多 {} 格，这次 {} 格 —— 跳过", MAX_FILL, vol);
                continue;
            }
            var state = block.defaultBlockState();
            for (int x = xa; x <= xb; x++) {
                for (int y = ya; y <= yb; y++) {
                    for (int z = za; z <= zb; z++) {
                        BlockPos bp = new BlockPos(x, y, z);
                        if (!lv.isLoaded(bp)) continue;
                        lv.setBlock(bp, state, 3);
                    }
                }
            }
        }
    }

    /**
     * 界面上「输入框」组件声明的动作 id —— 快照里的 {@code waitAction} 就是它。客户端输入框在
     * {@code waitAction} 非空时才让打字、回车提交，所以对局进行中恒给一个非空动作 id（缺省 {@code input}）。
     */
    private static String inputAction(GameDefinition def) {
        String found = findInputAction(def);
        return found.isBlank() ? "input" : found;
    }

    /** 同上：给客户端信息带用的一句话（界面输入框的占位提示；没有就写通用提示）。 */
    private static String inputHint(GameDefinition def) {
        String hint = findInputHint(def);
        return hint.isBlank() ? "在界面输入框里打字，回车提交" : hint;
    }

    private static String findInputAction(GameDefinition def) {
        for (GameDefinition.StageView v : def.stage().views()) {
            String s = scanInput(v.components(), true);
            if (s != null) return s;
        }
        return "";
    }

    private static String findInputHint(GameDefinition def) {
        for (GameDefinition.StageView v : def.stage().views()) {
            String s = scanInput(v.components(), false);
            if (s != null) return s;
        }
        return "";
    }

    /** 找第一个 input 组件（递归）：wantAction = 要它的动作 id，否则要它的占位提示。 */
    private static String scanInput(java.util.List<GameDefinition.Component> list, boolean wantAction) {
        for (GameDefinition.Component c : list) {
            if (c.type().equals("input")) {
                return wantAction ? c.s("action", "") : c.s("hint", "");
            }
            if (!c.children().isEmpty()) {
                String s = scanInput(c.children(), wantAction);
                if (s != null) return s;
            }
        }
        return null;
    }

    private void fail(ServerPlayer p, String text) {
        // 世界玩法开局没有发起人（也可能一个人都不在线）：回话改成记一行日志 —— 否则开局那几处
        // fail(host, …) 当场 NPE（世界局是无人自动开的）。
        if (p == null) {
            TableGame.LOGGER.warn("[对局] {}", text);
            return;
        }
        p.sendSystemMessage(Component.literal("[对局] " + text));
    }

    /** 纯逻辑实例的输出口：消息→聊天栏，状态变化→重发快照。 */
    private final class HubSink implements Interp.Sink {
        private final Session s;

        HubSink(Session s) {
            this.s = s;
        }

        @Override
        public void messageTo(String target, String text) {      // 私密：只发给他（不吞消息：人不在就退回全场）
            if (!sessions.contains(s)) return;
            for (ServerPlayer pl : recipients(s)) {
                if (pl.getName().getString().equals(target)) {
                    // 脚本的 say 像原版 tellraw 那样直接输出：不带前缀，& 颜色码照原版生效（ColorText.mask）。
                    pl.sendSystemMessage(Component.literal(ColorText.mask(text)));
                    return;
                }
            }
            say(s, text);
        }

        @Override
        public void message(String text) {
            if (sessions.contains(s)) say(s, text);
        }

        @Override
        public void stateChanged() {
            broadcastState(s);
        }
    }
}
