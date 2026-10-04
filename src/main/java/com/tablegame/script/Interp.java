package com.tablegame.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.tablegame.core.ProfileStore;

    /**
     * 解释器：把 {@link Ast} 跑起来 —— 纯逻辑，零 MC 依赖（宿主通过 {@link Sink} 接回去）。
     * 执行模型（事件推进 · 步数预算 · 挂起续跑）见 KB《引擎脚本语言规格》§四，不变量写在各自那一处。
     * 值是弱类型：数 / 文本 / 列表 / 真假 / 记录；认不出不静默兜底，一律报错带行号。
     */
public final class Interp {

    /** 宿主回调：发全场 / 发某人 / 状态变了（该重发快照）。
     * 宿主注入的所有能力（读世界 / 背包 / 玩家 / 容器 / 图案…）都遵循同一个缺省口径：
     * **没注入（自检 / 老宿主）→ 读给零值、写什么都不发生，一律不报错**（同 {@code block_at} 的「空串 = 不知道」）。 */
    public interface Sink {
        void message(String text);

        void messageTo(String who, String text);

        void stateChanged();
    }

    private static final Sink NOOP = new Sink() {
        @Override public void message(String t) { }

        @Override public void messageTo(String w, String t) { }

        @Override public void stateChanged() { }
    };

    /** 一次外部推进（开局 / 一次 tick / 一次提交）的步数预算。 */
    public static final int MAX_STEPS = 2000;
    /** 函数递归深度上限（防爆栈）。 */
    private static final int MAX_DEPTH = 64;

    // ============================================================ 状态

    private final Ast.Script sc;
    /**
     * 本局**在场名单**（宿主给的局内成员名；启动时给，之后随 {@link #acceptJoin} / {@link #acceptLeave} 增减）。
     * 唯一用途：解析 {@code say} / {@code show} 的 {@code others}（「除我之外，在场的那些人」）。
     *
     */
    private final List<String> people;
    private final Sink sink;
    private final Random rnd;

    /** <b>变量仓库</b>：变量名 → 值（B 批起没有「拥有者」那一维 —— 名单是脚本自己的数组）。 */
    private final Map<String, Object> vars = new LinkedHashMap<>();
    /** 变量名 → 它的声明（可见性、初值）——铺初值 / 快照裁切都要看它。 */
    private final Map<String, Ast.Decl> decls = new LinkedHashMap<>();
    /** 函数/链内的局部变量帧（栈顶 = 当前帧）；让递归与局部变量互不干扰。 */
    private final List<Map<String, Object>> frames = new ArrayList<>();

    /** 现在该显示哪一块**全屏**舞台（null = 不显示）；{@code show(...)} 改它，客户端开/关屏。 */
    private String curFull;
    /** 每个人**单独**指定的当前全屏块（{@code show(谁, "名")} 写的；没写 = 用全局那份 {@link #curFull}）。 */
    private final Map<String, String> fullPer = new java.util.LinkedHashMap<>();
    /** 同上，看板那一份（有 {@code show(谁, "看板名")} 就写这里）。 */
    private final Map<String, String> hudPer = new java.util.LinkedHashMap<>();
    /** 现在该显示哪一块**看板**舞台（null = 不显示）。与 {@link #curFull} 并存。 */
    private String curHud;
    /** 最近一次 {@code hide("名")} 收的是哪块（null = hide 全收）—— 只为随快照下发。 */
    private String lastHidden;
    /**
     * 脚本 {@code hide} 的**收起计数**。
     *
     */
    private int hideSeq;

    private final List<String> trace = new ArrayList<>();
    /** {@code on every} 上次触发时刻（第一次见到只记时刻，下一轮才开始计时）。 */
    private final Map<String, Long> lastEvery = new LinkedHashMap<>();

    /** 宿主注入的"现在"（纯逻辑不碰系统时钟 —— 自检能塞假时间）。 */
    private long clock;
    private String stage = "";
    private long stageAt;
    /** 本阶段限时（秒）；&lt;= 0 = 没限时。 */
    private double stageSec = -1;
    private boolean timeoutFired;

    /** 一次外部推进里还剩多少步。 */
    private int budget;

    private boolean waiting;
    /**
     * 正在等一次提交（{@code 变量 = scanf(谁)}）。挂起机制与 {@link #waiting} 共用：这条为 true 时 waiting 也 true。
     * ponytail: 挂起状态**不落盘** —— 重进存档时这条等输入作废；要续跑得把它塞进 {@link Snapshot} + HostStore 读写。
     */
    private boolean scanning;
    private String scanVar;      // 值落进哪个变量
    private String scanWho;      // 只认他（空串 = 谁都行）
    private String scanBox;      // 只认这个框的提交（空串 = 哪个框都行）
    private boolean scanText;    // true = 要文本；false（默认）= 要数字
    private long waitAt;
    /**
     * 执行栈：一帧 = 一段语句 + 下一条要跑的位置。挂起时**整栈留着**，唤醒从断点接着跑（不重放 —— 重放会把 wait 之前的 say / 加分再执行一遍）。
     * 环帧额外带循环状态（for 的循环变量与步进），所以循环里等也能从当次迭代中间接着跑。
     */
    private final java.util.Deque<Frame> stack = new java.util.ArrayDeque<>();

    /** 一帧：{@code body} 里下一条要跑的语句是 {@code ip}。 */
    private static final class Frame {
        final List<Ast.Stmt> body;
        int ip;
        /** 环帧（{@code while}）：帧尾重算条件，真就回帧头。 */
        final Ast.While loopW;
        /** 环帧（{@code for}）：帧尾先跑步进再重算条件。 */
        final Ast.For loopF;
        /** 本帧自己开的局部作用域（出帧要撤）。 */
        final boolean ownScope;
        /** 调用帧：正等它返回值的表达式卡在栈上（这种帧里不能挂起，见 doWait）。 */
        final boolean awaiting;
        /** 调用帧的返回值落点。 */
        final Object[] ret;

        Frame(List<Ast.Stmt> body, Ast.While loopW, Ast.For loopF, boolean ownScope) {
            this(body, loopW, loopF, ownScope, false, null);
        }

        Frame(List<Ast.Stmt> body, Ast.While loopW, Ast.For loopF, boolean ownScope,
              boolean awaiting, Object[] ret) {
            this.body = body;
            this.loopW = loopW;
            this.loopF = loopF;
            this.ownScope = ownScope;
            this.awaiting = awaiting;
            this.ret = ret;
        }
    }

    private String actor = "";
    private String input = "";
    /**
     * 提交来自**哪个框**（框的身份值 = 它的资产名；没起名 / 命令提交 ⇒ 空串）——
     * 内建值 {@code input_box}。客户端只说得出「哪个框」，宿主拿展开时记的账换成身份值。
     */
    private String inputBox = "";
    /**
     * 被点那一份的<b>身份值</b>（舞台去模板化的内建值 {@code pick}；-1 = 还没被点过）。
     *
     * <p>默认给 {@code -1.0}（Double）而不是 null：脚本里就写 {@code if (pick >= 0) …}，
     * 数值比较不必先判空。身份值本身可以是数（手牌下标）也可以是文本（席位名 / 棋子 id）。
     */
    private Object pick = -1.0;
    /**
     * 「这局现在允许谁在画板上画」（空串 = 谁都不行）—— 由脚本用原语 {@code may_draw(谁)} 设定。
     *
     */
    private String drawer = "";
    /**
     * 脚本请求「清空局内画板」（{@code clear_board} 置位；宿主每 tick 取一次，取完即清）。
     *
     * <p>用「置位 + 取走」而不是回调：{@code Sink} 接口不必为它多一个方法（各处实现都要改），
     * 宿主本来就每 tick 跟引擎打交道。
     */
    private boolean boardClear;
    /**
     * 脚本请求改世界方块（原语 {@code set_block(x, y, z, "方块id")} 累积；宿主每 tick 取走整批执行）。
     *
     *
     */
    private final List<SetBlock> setBlocks = new ArrayList<>();
    /**
     * 脚本请求大片改方块（原语 {@code fill(x1,y1,z1,x2,y2,z2,"方块id")}）。
     *
     */
    private final List<Fill> fills = new ArrayList<>();
    /** 脚本请求保护的区域（原语 {@code protect} / {@code unprotect}）—— 攒批，宿主取走记账。 */
    private final List<Protect> protects = new ArrayList<>();
    /**
     * 脚本请求在世界里立标签（原语 {@code label(x, y, z, "文字")}）—— 世界层 W3「世界承载显示」。
     *
     */
    private final List<Label> labels = new ArrayList<>();
    /**
     * 脚本请求把棋子放到世界里（原语 {@code place_piece(棋子id, x, y, z)}）：一个原语兼管「生成 + 移动」—— 不在就 spawn、已在就挪过去（宿主按 id 记账）。
     * 棋子 id = 档里 {@code pieces} 段的 id（编辑器「棋子」页编）；语言只带 id 出去，不知道蓝图与实体是什么。
     */
    private final List<PlacePiece> placePieces = new ArrayList<>();
    /** 卡牌实体的「生成 / 挪位置」请求。 */
    private final List<PlaceCard> placeCards = new ArrayList<>();
    /** 卡牌翻面请求（{@code flip_card}）。 */
    private final List<FlipCard> flipCards = new ArrayList<>();
    /** 实体画面的「挂上锚点」请求。 */
    private final List<ShowEntity> showEntities = new ArrayList<>();
    /** 实体画面的「收起」请求（{@code hide("名")}；名字空 = 全收）。 */
    private final List<HideEntity> hideEntities = new ArrayList<>();
    /**
    /**
     * 脚本请求发一条**带点击回投的文本** —— 攒批，宿主取走。
     *
     */
    private final List<SayMark> sayMarks = new ArrayList<>();
    /**
     * 脚本请求给某人开 / 关**蓝图幽灵预览**—— 攒批，宿主取走发包。
     *
     */
    private final List<Ghost> ghosts = new ArrayList<>();
    /**
     * 读世界的回调（宿主注入）：问「那格是什么方块」。
     *
     */
    public interface WorldReader { String blockAt(int x, int y, int z); }

    private WorldReader worldReader;

    /** 宿主接上世界读取（开局建解释器时调一次）。 */
    public void setWorldReader(WorldReader r) {
        this.worldReader = r;
    }

    /** 读写**容器内容**（宿主注入）：世界里的按坐标、玩家背包按名字。 */
    public interface WorldContainers {
        List<SlotItem> itemsAt(int x, int y, int z);                        // 那一格的容器（箱子 / 桶 / 熔炉…）
        List<SlotItem> itemsIn(String who);                                // 那个玩家的背包
        void setAt(int x, int y, int z, int slot, String item, int count);  // 覆盖式：count ≤ 0 或 item 空 = 清空该槽
        void setIn(String who, int slot, String item, int count);

        /**
         * **发**物品给他：与上面两条「设成」不同 —— 这条是「**加**」：
         * 先并进已有的同款堆、再找空格，塞不完的掉在脚下（照原版 {@code /give} 的路子）。
         *
         */
        void give(String who, String item, int count);

        /**
         * **数**一个盒里有多少个某种方块，认不出 → 0。
         * ⚠ 体量上限在宿主侧（照原版 {@code /fill} 的 max_block_modifications）：盒子太大 → 记一行日志、回 0，不按住服务端。
         */
        double countBlocks(int x1, int y1, int z1, int x2, int y2, int z2, String block);

        /**
         * **塞**东西进那一格的容器，返回实际塞进去几件 —— 与 {@link #setAt} 的「设成」不同，这条是「**加**」：
         * 先并堆 → 再找空槽 → 塞不下就留在脚本手里（返回 0 = 熔炉停工，不溢出、不掉地上）。
         * ⚠ 造栈走宿主的 {@code itemStackOf}（组件与 tg_asset 身份标签都在里面）；脚本手拼 ItemStack 必丢组件。
         */
        int chestGive(int x, int y, int z, String item, int count);

        /**
         * 从**背包**取走东西，返回实际拿走几件 —— **全有或全无**（先求和：不够一件不动返回 0；够了逐槽扣）。
         * 认物品口径与 {@link #itemsIn} 同（基底 id）：同基底的两条声明物品分不出来。
         */
        int bagTake(String who, String item, int count);

        /**
         * 整份背包**寄存 / 发回**（{@code bag_save(actor, "orig")} / {@code bag_restore(…)}）：走原版 NBT 序列化成 SNBT、落进现成的 {@link ProfileStore}。
         * 存的是原版 {@code Inventory.save/load} 的口径 —— **背包 36 格**，护甲 / 副手不在里面。
         */
        void bagSave(String who, String key);
        void bagRestore(String who, String key);

        /**
         * 按**项目资产名**（物品的 {@code tg_asset} 标签）数 / 取背包：take 同 {@link #bagTake} —— 全有或全无（不够一件不动返回 0）。认不出资产 → 0。
         */
        double bagCountAsset(String who, String asset);
        int bagTakeAsset(String who, String asset, int count);
    }


    private WorldContainers worldContainers;

    /** 宿主接上容器读写（开局建解释器时调一次）。 */
    public void setWorldContainers(WorldContainers c) {
        this.worldContainers = c;
    }

    /** 图案与语义格（宿主注入）：区域快照住在宿主那份游戏定义的 {@code areas} 段，世界方块也只有宿主读得到。 */
    public interface Shapes {
        /**
         * 那条区域的快照里标了 `role` 的那一格，**在世界里的坐标**（{@code x,y,z} = 锚点 = 区域最小角）。
         * 没这条区域 / 没标过这个角色 / 认不出 → null（调用侧给坐标全 0）。
         */
        double[] cellAt(String area, String role, int x, int y, int z);

        /**
         * 逐格比图案（严的那半）：{@code x,y,z} = **锚点角**（区域最小角对齐到这）= 1，对不上 = 0。
         *
         * <p>比在**宿主内部**逐格做（脚本自己循环会撞「每事件步数预算」），空气格放松、通配格放过
         * —— 两条口径写在 {@link com.tablegame.core.GameDefinition.AreaDef#shapeMatches}。
         */
        boolean matchShape(String area, int x, int y, int z);
    }

    private Shapes shapes;

    /** 宿主接上「图案与语义格」（开局建解释器时调一次）。 */
    public void setShapes(Shapes s) {
        this.shapes = s;
    }

    /**
     * 撒方块（宿主注入）—— 原语 {@code scatter(盒, "表名", 数量[, 清单])}：盒里随机挑 N 格（不重复）→ 每格抽一次原版战利品表 → 抽到的第一件物品当方块摆下。
     */
    public interface Scatter {
        /**
         * @param table 项目档里的表名（{@code loot/<表名>.json}）
         * @param replace 可替换的方块 id 清单（逗号分隔；**空串 = 宿主默认只替换石头 / 深板岩**）
         * @return 实际撒下去几格（候选不够 / 表抽空 / 抽到的不是方块物品 → 少于要的数）
         */
        int scatter(int x1, int y1, int z1, int x2, int y2, int z2, String table, int count, String replace);
    }

    /**
     * 弹对话框（宿主注入）—— 原语 {@code dialog(谁, "框名")}：走原版 {@code Player.openDialog}（按钮 / 输入框由那份数据自己写，引擎不掺和）。
     * 框名 = 项目档 {@code dialog/<名>.json}；认不出的人 / 名字 → 宿主记一行，不掐局。
     */
    public interface Dialogs {
        void openDialog(String who, String name);
    }

    /**
     * 发粒子：原语 {@code particle(x, y, z, "粒子"[, 数量])}；粒子文本写原版那套（纯 id 或带参数的完整形式，走 {@code ParticleTypes.CODEC}）。
     * 只有宿主发得出去；没注入 → 0，不报错。纯反馈，不动玩法状态。
     */
    public interface Particles {
        /** @return 实际发了几颗（认不出粒子 / 没世界 → 0） */
        int particle(double x, double y, double z, String spec, int count);
    }

    /** 方块实体的**数据键**（宿主注入）：{@code block_data_get(x, y, z, "键")} / {@code block_data_set(x, y, z, "键", 值)}。 */
    public interface BlockData {
        double get(int x, int y, int z, String path);
        void set(int x, int y, int z, String path, double value);
        /** 这格 BE 上所有键名（逗号分隔、已排序）—— 键名**版本相关**，先看再用。 */
        String keys(int x, int y, int z);
    }

    private Scatter scatter;

    /** 宿主接上「撒方块」（开局建解释器时调一次）。 */
    public void setScatter(Scatter s) {
        this.scatter = s;
    }

    private Dialogs dialogs;

    /** 宿主接上「弹对话框」（开局建解释器时调一次）。 */
    public void setDialogs(Dialogs d) {
        this.dialogs = d;
    }

    private Particles particles;

    /** 宿主接上「发粒子」（开局建解释器时调一次）。 */
    public void setParticles(Particles p) {
        this.particles = p;
    }

    private BlockData blockData;

    /** 宿主接上「方块实体的数据键」（开局建解释器时调一次）。 */
    public void setBlockData(BlockData b) {
        this.blockData = b;
    }

    /**
     * 「谁在局里」的**点名**那一半（宿主注入）。
     *
     *
     */
    public interface Members {
        void enterGame(String who);
        void leaveGame(String who);
    }

    private Members members;

    /** 宿主接上「点名进局」（开局建解释器时调一次）。 */
    public void setMembers(Members m) {
        this.members = m;
    }

    /**
     * 一个活物此刻的位置（宿主 → 解释器的**原始数据**，{@code in_area} 用它）。
     *
     * @param dim 他所在维度的注册名（如 {@code minecraft:overworld}）—— 与 {@link Region#dim} 同口径
     */
    public record LivingPos(double x, double y, double z, String dim) { }

    public interface EntityOps {
        void gameMode(String who, String mode);              // "survival" / "creative" / "adventure" / "spectator"
        void tp(String who, double x, double y, double z);   // 传送到坐标（**同世界**，方块坐标，可带小数）
        // 传送到**指定世界**：dim 空串 = 这一局的棋盘维度（= 老 tp 的行为）；
        // x/y/z 一律**绝对坐标** —— 区域坐标基准的加法在解释器里算完（读区声明是引擎的事，见 Region）。
        void tpTo(String who, String dim, double x, double y, double z);
        // 读他此刻在哪：`in_area` 要用「坐标 + 世界」两样，一次查完（找不到 → null）。
        // ⚠ 走**记录**而不是 double[3]：多一个分量（维度）时不用改所有调用点，也不会有人拿 [3] 当 y。
        LivingPos whereIs(String who);
        // 读他此刻的游戏模式："survival" / "creative" / "adventure" / "spectator"；
        // 找不到人 → 空串（读宽容，同 whereIs 的口径）。「进圈改模式、出圈还回」这类脚本段要靠它存旧值。
        String gameModeOf(String who);
        void health(String who, double value);               // 设当前生命（原版 setHealth 自带 0~上限夹取）
        void heal(String who, double amount);                // 回血
        void damage(String who, double amount, String type);  // 扣血（type 空 = 原版 generic；走原版伤害管线）
        void effect(String who, String id, double seconds, double amplifier);  // 给 buff（原版效果注册名 + 秒 + 等级）
        void clearEffects(String who);                       // 清掉他身上所有 buff
        void attribute(String who, String id, double value); // 设属性基础值（max_health / movement_speed …）
        void food(String who, double value);                 // 设饱食度（只有玩家有意义；实体上跳过）
        void xp(String who, double points);                  // **加**经验点数（只有玩家有意义）
        void fly(String who, boolean on);                    // 允许 / 禁止飞行（玩家）
        void invulnerable(String who, boolean on);           // 免伤开关
        void fire(String who, double seconds);               // 点火（0 = 灭火）
        /**
         * 放一段音效给那个人听（{@code id} = 原版音效 id，可省命名空间）：照原版 {@code /playsound} 那条路 —— **不查注册表**，
         * 认不出的 id 客户端静默不响（不报错、不掐局）。位置取他自己那儿。
         */
        void sound(String who, String id, double volume, double pitch);
        void face(String who, double x, double y, double z); // 转头看向那一点
        void knockback(String who, double x, double y, double z); // 推一把（击退；不是「设速度」）
                                                              // ⚠ 名字不能叫 push：那是内建函数（列表追项），重名会把内建整个盖掉
        void ride(String who, String target);                // 骑上去（目标 = 名字，玩家或账上实体）
        //  「区域规则」两个**按人**动词 —— 都立刻生效（注入），与上面那一族同路。
        // 为什么按人：原版 KEEP_INVENTORY 是**全服一条** gamerule（分不了人、也分不了区），
        // 而「在这块区域内死亡不掉落」天生是按人的语义 —— 判定交给脚本（in_area / edge），动词只管**这个人**。
        void keepItems(String who, boolean on);              // 这个人死亡不掉落（0/1 开关）
        void respawn(String who, double x, double y, double z); // 设他的重生点（当前世界，死了原版送回）
        //  放原版生物；** 起立刻生效**（原攒批、宿主每 tick 取走 —— 与 `spawn_mob` 那条勘误同因：
        // 脚本紧接着就会写它的 NBT / 上装备 / 让它骑什么，攒批时找不到那只实体）。
        void spawnMob(String name, double x, double y, double z);
        double healthOf(String who);                         // 读当前生命（认不出 → 0）
        double maxHealthOf(String who);                      // 读生命上限（认不出 → 0）
        //  两条读（坐标那条复用上面的 whereIs —— 形状在解释器里拼，宿主只交原始数）。
        double attributeOf(String who, String id);           // 读属性**基础值**（与 attribute 写入那一项对称；认不出 → 0）
        java.util.List<String> effectsOf(String who);        // 读身上 buff 的**效果 id** 列表（认不出 → 空表）
        // **写任意 NBT** —— 一段原版 SNBT 并进那个实体（走原版 `/data merge entity`
        // 那条：存出他此刻的数据 → merge → load → UUID 还回去）。生物 Tags / 自定义名 / 装备 /
        // 村民 `Offers`（= 逐只必出的自定义交易）都靠它 —— 之前只有白名单动词，写不了任意 NBT。
        // 写上了 → 1；目标找不到 / SNBT 不合法 / 目标是玩家（原版也不让写）→ 0（记一行，不掐局）。
        double entityData(String who, String snbt);
        // **替玩家开某个活物的原版交易界面** —— 商店的「店主」整类被 click 认领后，
        // 靠这条把原版界面还给他（走原版 Merchant#openTradingScreen：开屏 + 发交易清单）。
        // 立刻生效（同这一族口径，不攒批 —— 开界面是给玩家的即时反馈）。
        // 开上了 → 1；玩家 / 活物找不到、或那只不是商人 → 0（记一行，不掐局）。
        double openTrade(String who, String eid);
    }

    private EntityOps entityOps;

    /** 宿主接上「动活物」（开局建解释器时调一次）。 */
    public void setEntityOps(EntityOps ops) {
        this.entityOps = ops;
    }

    /**
     * **玩家档案**（宿主注入）—— 跨局持久的「按名字存的键值」：
     * 常驻玩法（钻石大陆）的金钱/等级存这里，掉线重连、服务器重启都还在。
     *
     */
    public interface ProfileStore {
        String get(String who, String key);                      // 没存过 → 空串
        void set(String who, String key, String value);          // 空串 = 删键（不落）
    }

    private ProfileStore profiles;

    /** 宿主接上玩家档案（开局建解释器时调一次）。 */
    public void setProfileStore(ProfileStore p) {
        this.profiles = p;
    }
    /**
     * 世界事件（{@code on world}）的<b>事发坐标与那格方块</b> —— 宿主发事件前写，脚本只读
     * （内建值 {@code bx} / {@code by} / {@code bz} / {@code block}）。
     *
     */
    private double wx, wy, wz;
    private String wblock = "";
    /**
     * 右键那一下**主手**里的物品（内建值 {@code hand}）—— 物品注册名；空手 / 认不出 = 空串。
     * ⚠ 自定义物品报的是**基底**物品 id（注册表认的是它）；要按「哪一件自定义物品」判得另做反向查。
     */
    private String whand = "";
    /** 副手物品的注册名（内建值 {@code offhand}）—— 与 {@code hand} 互相独立，两个值齐了才写得出「主手 A / 副手 B」两支条件。 */
    private String woffhand = "";
    /** 这一下是不是潜行右键（内建值 {@code sneak}：1 = 潜行 / 0 = 普通）—— 两种手势都进 {@code on world}，靠它分。 */
    private double wsneak;
    /** 这一下是不是右键方块（内建值 {@code rclick}：1 = 右键 / 0 = 不是；踩格与右键共用 {@code on world}，靠它分）。 */
    private double wrclick;
    /**
     * 手里那件物品的**项目资产名**（内建值 {@code hand_asset} / {@code off_asset}）—— 空 = 不是本项目物品。
     * 与 {@code hand} / {@code offhand}（基底 id）是两个层次：两条基底相同的自定义物品只有它俩分得开。
     */
    private String whandAsset = "";
    private String woffAsset = "";
    /**
     * 进 / 出脚本声明的 {@code area} 范围（内建值 {@code edge}：1 = 刚进 · -1 = 刚出 · 0 = 不是边沿事件）。
     * 只在**写了 area** 的局里发（没写 = 整个维度，没有「圈边」可言）。
     */
    private double wedge;
    /**
     * 这一下跨过的是**哪个区域**（内建值 {@code edge_area}）—— 匿名那条报空串。
     * ⚠ 与 {@code edge} 一样走 {@code acceptEdge} 参数，不能靠「先跑脚本再补写字段」（见 {@code fireWorld}）。
     */
    private String wedgeArea = "";
    /** {@code on break} 的掉落清单（物品 id 文本列表；{@code BlockDropsEvent} 那次才填）。 */
    private final List<Object> wdrops = new ArrayList<>();
    /** 右键的实体类型（世界事件 {@code on entity} 的值 {@code etype}）。 */
    private String etype = "";
    /**
     * 这一下是**哪个**实体对象（内建值 {@code eid}）—— 脚本 {@code spawn_mob} 放出来的那只的**名字**；
     * 不是脚本放的就空串。宿主反查自己的实体账得出（与 {@code look_piece} 反查棋子账同款）。
     */
    private String eid = "";
    /** 这一下是左键（攻击）还是右键。 */
    private double hit;
    /** 这一下是不是**把那只打死了**（内建值 {@code edead}，：1 = 打死 / 0 = 右键它）—— 两种都进 {@code on entity}，靠它分。 */
    private double edead;
    /** 看向哪格（世界事件 {@code on look} 的值）：客户端射线的命中格。 */
    private double lx, ly, lz;
    private String lblock = "";
    /** 看向的**玩家**（{@code look_player}）：他的名字 = 席位身份，可直接当
     *  「每人一份」槽位的键（如 {@code money[look_player]}）。没看人 → 空串。 */
    private String lplayer = "";
    /** 看向的**棋子**（{@code look_piece}）：档里 pieces 段的棋子 id。
     *  值由宿主反查棋子账（坐标 → 谁站这格）得出；没看棋子 → 空串。 */
    private String lpiece = "";
    /** 正在给谁裁快照（可见性条件里的内建值 {@code viewer}；不在裁切期 = null）。 */
    private String viewer;
    private boolean finished;
    private boolean halted;
    private String error = "";

    public Interp(Ast.Script script, List<String> people, Sink sink) {
        this(script, people, sink, new Random());
    }

    /**
     * @param people 本局**在场名单**（宿主给的局内成员）—— 只用来解析 {@code say} / {@code show} 的 {@code others}；玩法数据不靠它（名单归宿主，谁入席归脚本自己的数组）。
     * @param rnd 随机源（自检可塞固定种子 → 跑两遍结果一致）
     */
    public Interp(Ast.Script script, List<String> people, Sink sink, Random rnd) {
        this.sc = script;
        this.regions = regionsOf(script);
        this.people = new ArrayList<>(people == null ? List.of() : people);
        this.sink = sink == null ? NOOP : sink;
        this.rnd = rnd == null ? new Random() : rnd;
        seed();
        indexBlocks();          // 快照用的「语句块寻址表」（落盘 · 断点续跑，见文件末尾「快照」一节）
    }

    /** 铺初值：顶层 {@code var} 声明（每条一份新值 —— 列表 / 记录是引用类型，不会共用同一张）。 */
    private void seed() {
        for (Ast.Decl d : sc.globals()) {
            decls.put(d.name(), d);
            vars.put(d.name(), eval(d.init()));
        }
        log("铺初值：" + vars.keySet());
    }

    // ============================================================ 外部驱动

    /** 开局：跑 {@code on start}。返回 false = 这份脚本开不了局（没有 on start）。 */
    public boolean start() {
        if (finished || halted) return false;
        Ast.OnStart h = handler(Ast.OnStart.class);
        if (h == null) {
            sink.message("[对局] 这份脚本没有 on start，开不了局");
            return false;
        }
        log("开局，在场 " + String.join("/", people));
        fire(h, "开局", h.body());
        if (!halted) acceptReload();                 // 建局也算「脚本生效」一次（登记类原语有地方放）
        return !halted;
    }

    /** {@code hide} 的收起计数（客户端比对它决定"关掉全屏屏"这一次动作）。 */
    public int hideSeq() {
        return hideSeq;
    }

    /** 最近一次收的是哪块（{@code hide("名")} 点名；null = 全收）—— 随快照下去，客户端只关该关的。 */
    public String hiddenName() {
        return lastHidden;
    }

    /**
     * <b>脚本每次生效</b>时跑一次 {@code on reload}：建局与热替换都算。
     *
     *
     * <p>站的位置在 start / apply **末尾**：登记类原语（{@code protect} 等）攒批写在引擎里，
     * 宿主在 tick 里取走 —— 所以这里跑完不用同步做别的。
     */
    public boolean acceptReload() {
        if (finished || halted) return false;
        Ast.OnReload h = handler(Ast.OnReload.class);
        if (h == null) return false;
        log("脚本生效（on reload）");
        fire(h, "生效", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /** 现在该显示的全屏舞台名（全局那份；null = 没指定）。 */
    public String currentFull() {
        return curFull;
    }

    /** 现在该显示的看板舞台名（全局那份；null = 不显示）。 */
    public String currentHud() {
        return curHud;
    }

    /**
     * <b>这个人</b>现在该看的全屏块：他有没有被 {@code show(谁, …)} 单独指定过？没有就回退全局那份。
     * {@code who} 空 = 全局。
     */
    public String currentFull(String who) {
        String per = who == null ? null : fullPer.get(who);
        return per != null ? per : curFull;
    }

    /** 同上，看板那一份。 */
    public String currentHud(String who) {
        String per = who == null ? null : hudPer.get(who);
        return per != null ? per : curHud;
    }

    public java.util.Map<String, Boolean> screens() {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Ast.Screen> e : sc.screens().entrySet()) {
            if (e.getValue().entityView()) continue;      // 实体画面不走 HUD/全屏那套（期2）
            out.put(e.getKey(), e.getValue().hud());
        }
        return out;
    }

    /** 实体画面：名字 → 声明。宿主按它生成 / 维持 / 收掉那组实体。 */
    public java.util.Map<String, Ast.Screen> entityScreens() {
        java.util.Map<String, Ast.Screen> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Ast.Screen> e : sc.screens().entrySet()) {
            if (e.getValue().entityView()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 展开一块实体画面：锚点参数 + 每个组件求值后的内容。
     *
     * <p>与普通画布的展开同一个道理 —— **按 viewer 求值**（`value(…)` / 每人一份那套白送）：
     * 锚点是人时 viewer 就是他，锚点是实体时给空串。求值失败（表达式写错）⇒ 报错带行号（调用方决定怎么处理）。
     */
    public EntityView expandEntity(String screen, String viewer) {
        Ast.Screen s = sc.screens().get(screen);
        if (s == null || !s.entityView()) return null;
        // 缺省：跟着锚点走（follow 1）· **不跟视角**（face 0 —— 跟了视角画面就永远待在你眼前、准星永远点不到它）
        boolean follow = true;
        int faceMode = 0;
        double away = -1, dx = 0, dy = 1, dz = 2;
        for (Ast.EntityParam p : s.anchor()) {
            Object v = eval(p.value());
            switch (p.key()) {
                case "follow" -> follow = Builtins.truthy(v);
                case "face" -> faceMode = faceModeOf(v);
                case "away" -> away = Builtins.numOf(v) == null ? -1 : Builtins.numOf(v);
                case "dx" -> dx = Builtins.numOf(v) == null ? 0 : Builtins.numOf(v);
                case "dy" -> dy = Builtins.numOf(v) == null ? 1 : Builtins.numOf(v);
                case "dz" -> dz = Builtins.numOf(v) == null ? 2 : Builtins.numOf(v);
                default -> { }
            }
        }
        String oldViewer = this.viewer;
        this.viewer = viewer == null ? "" : viewer;
        List<EntityCompView> comps = new ArrayList<>();
        try {
            for (Ast.EntityComp c : s.comps()) {
                String text = c.body() == null ? "" : Builtins.text(eval(c.body()));
                // base 也**按 viewer 求值**（手牌是私密的：同一块画面在不同人眼里内容不同）
                String base = c.base() == null ? "" : Builtins.text(eval(c.base()));
                comps.add(new EntityCompView(c.kind(), c.asset(), c.label(), text, c.mark(),
                        base, c.scale(), c.ax(), c.ay(), c.az(),
                        c.rx(), c.ry(), c.rz(), c.hasRot()));
            }
        } finally {
            this.viewer = oldViewer;
        }
        return new EntityView(screen, s.label(), follow, faceMode, away, dx, dy, dz, List.copyOf(comps));
    }

    /**
     * {@code face} 的档位：{@code 0}（不写）= 世界轴（画面恒朝南，dx/dz 按世界坐标）·
     * {@code 1} = 每帧跟锚点视角（画面钉在眼前）· {@code 2} = show 那一刻按视角放一次，之后只跟位置不跟视角。
     * 老写法白送：不是数字时按真假 → 1 / 0。
     */
    private static int faceModeOf(Object v) {
        Double d = Builtins.numOf(v);
        if (d != null) return (int) (double) d;
        return Builtins.truthy(v) ? 1 : 0;
    }

    /**
     * 一块实体画面的锚点参数（求值后）+ 组件内容。
     *
     * <p>{@code faceMode} = 0 / 1 / 2 三档（见 {@link #faceModeOf}）。
     */
    public record EntityView(String screen, String label, boolean follow, int faceMode, double away,
            double dx, double dy, double dz, List<EntityCompView> comps) { }

    /**
     * 组件求值后的样子（宿主拿它摆实体）。
     *
     * <p>位置 = **锚点局部系**（x 右 / y 上 / z 前，见 {@code HostManager.compPos}）；
     * {@code hasRot} = 作者写没写 {@code rot} —— 没写就走老行为（文字/物品恒面向观察者、卡牌正面朝锚点），
     * 写了就按 {@code rx/ry/rz}（XYZ 欧拉）摆。
     */
    public record EntityCompView(String kind, String asset, String label, String text, String mark,
            String base, double scale, double ax, double ay, double az,
            double rx, double ry, double rz, boolean hasRot) { }

    /**
     * 某块画布写的<b>舞台背景</b> {@code bg("…")}；没这块画布 / 没写 = 空串。
     *
     */
    public String screenBg(String name) {
        Ast.Screen s = sc.screens().get(name);
        return s == null || s.bg() == null ? "" : s.bg();
    }

    /**
     * 有人提交。{@code actor} = 谁交的，{@code input} = 交了什么；跑 {@code on input} 链。
     * 返回 true = 这次提交被接住了（有 on input 入口）。
     */
    public boolean acceptInput(String player, String text) {
        return acceptInput(player, text, "");
    }

    /**
     * 有人提交（{@code box} = 来自**哪个框**的身份值；空串 = 命令提交 / 按钮 / 那个框没起名）。
     *
     * <p>两条路只在这一处分流：有 {@code scanf} 在等、且等的就是他（或谁都行）、框也对得上 ⇒ 值落进变量、
     * 从下一句接着跑（C 的 scanf 那一下）；否则照旧跑 {@code on input} 链 —— 两套并存，互不干扰。
     * 提交来自哪个框由脚本用内建值 {@code input_box} 读（多框界面的分流就靠它）。
     */
    public boolean acceptInput(String player, String text, String box) {
        if (finished || halted) return false;
        String who = player == null ? "" : player;
        String body = text == null ? "" : text;
        String from = box == null ? "" : box;
        // 有人在 scanf 等着，而且等的就是他（或谁都行）、框也对得上（或他没指定框）
        // ⇒ 值落进那个变量、从下一句接着跑（C 的 scanf 那一下）。
        // ⚠ 挂起期间**整局暂停**（runChain 的循环条件就是 !waiting）：别人交的、别的框交的**都被吃掉** ——
        // 既不解挂、也不跑 on input。所以「多人 / 一个界面多个框」要用 on input + 内建值 input_box，
        // scanf 只适合「一个人、一个框、一步一步来」的流程（猜数字那种）。
        if (scanning && (scanWho.isEmpty() || scanWho.equals(who))
                && (scanBox.isEmpty() || scanBox.equals(from))) {
            Object value = scanText ? (Object) body : (Object) scanNumber(body);
            String target = scanVar;
            scanning = false;
            scanVar = null;
            scanBox = null;
            actor = who;
            input = body;
            inputBox = from;
            assign(new Ast.Assign(target, List.of(), "=", valueExpr(value, body), 0));
            waiting = false;
            log("等到 " + who + " 的输入：" + body);
            if (!halted && !finished) sink.stateChanged();
            resumeChain("提交");
            return true;
        }
        actor = who;
        input = body;
        inputBox = from;
        Ast.OnInput h = handler(Ast.OnInput.class);
        if (h == null) return false;
        log("收到 " + actor + " 的提交：" + input);
        fire(h, "提交", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * 有人**进局**了（宿主调的）：{@code actor} = 那位；跑 {@code on join} 链。
     *
     * <p>与 {@link #acceptLeave} 对称：进局 = 被接进这一局（世界玩法：进存档；游戏台：点【进入游戏界面】）。
     * 返回 true = 档里写了这个入口、被接住了；没写 = false，宿主悄悄丢掉（「这档不关心进人」是正常状态）。
     */
    public boolean acceptJoin(String player) {
        if (finished || halted) return false;
        actor = player == null ? "" : player;
        if (!actor.isEmpty() && !people.contains(actor)) people.add(actor);   // 在场名单（others 用）
        Ast.OnJoin h = handler(Ast.OnJoin.class);
        if (h == null) return false;
        log("进局：" + actor);
        fire(h, "进局", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * 席位玩家**掉线**了：{@code actor} = 掉线那位，跑 {@code on leave} 链；返回 true = 档里写了这个入口。
     * ⚠ 只有 {@code offline} 为 {@code wait} / {@code skip} 时才走到这儿 —— {@code stop} 那条路整局终止，发事件没意义。
     */
    public boolean acceptLeave(String player) {
        if (finished || halted) return false;
        actor = player == null ? "" : player;
        people.remove(actor);
        Ast.OnLeave h = handler(Ast.OnLeave.class);
        if (h == null) return false;
        log("掉线：" + actor);
        fire(h, "掉线", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * 有人点了可点的一份（舞台去模板化）：{@code actor} = 谁点的，{@code pick} = 那一份的<b>身份值</b>；
     * 跑 {@code on pick} 链。返回 true = 这次点击被接住了（有 {@code on pick} 入口）。
     *
     * <p>身份值是<b>宿主在展开时记下来的</b>（= 部件第一个形参的实参），不是从点击数据里解析出来的：
     * 客户端只认识「框 id」，点击回来由宿主查账换成身份值再交给这里。
     */
    public boolean acceptPick(String player, Object identity) {
        if (finished || halted) return false;
        actor = player == null ? "" : player;
        pick = identity;
        Ast.OnPick h = handler(Ast.OnPick.class);
        if (h == null) return false;
        log("收到 " + actor + " 的点选：" + Builtins.text(identity));
        fire(h, "点选", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * 世界里发生了脚本该知道的事（世界层 W2）：{@code actor} = 谁触发的，
     * 坐标是事发那格（{@code bx/by/bz}），{@code block} = 那格的方块 id；跑 {@code on world} 链。
     *
     * <p>返回 true = 这次世界事件被接住了（档里写了 {@code on world}）。
     * <b>没写就是 false，宿主悄悄丢掉</b> —— 「这档不关心世界」是正常状态，不是错误。
     */
    public boolean acceptWorld(String player, double x, double y, double z, String block) {
        return fireWorld(player, x, y, z, block, "", "", false, false, 0, "", "", "", false);
    }

    /**
     * 玩家**右键空气 / 举着物品点空气**。
     *
     * <p>走**新事件 {@code on use}**，与 {@code on world} 共用同一个注入口（值完全相同，只是派发给谁不同）。
     */
    public boolean acceptUse(String player, String hand, String offhand,
                             String handAsset, String offAsset) {
        return fireWorld(player, 0, 0, 0, "", hand, offhand, false, false, 0, "", handAsset, offAsset, true);
    }

    /**
     * 玩家**右键方块**（世界事件之四）—— 普通与潜行都走这里。
     *
     * <p>脚本拿内建值 {@code hand}（主手物品）· {@code offhand}（副手物品）· {@code sneak}（1/0）判这一下：
     * 两个手的值是**互相独立**的两支条件，而这一下**只发一个事件** —— 宿主侧的守卫见
     * {@code TableGame.onRightClickBlock}（原版会为副手补发第二个包，不挡就是「点一次做两次」）。
     */
    public boolean acceptClick(String player, double x, double y, double z, String block,
                               String hand, String offhand, boolean sneak,
                               String handAsset, String offAsset) {
        return fireWorld(player, x, y, z, block, hand, offhand, sneak, true, 0, "", handAsset, offAsset, false);
    }

     /**
     * <b>方块被破坏</b>。
     *
     * @param drops 这一下掉的物品 id（{@code BlockDropsEvent} 那次才有；普通那次传 null ⇒ 清成空表）
     * @return true = 档里写了 {@code on break} 且被接住
     */
    public boolean acceptBreak(String player, double x, double y, double z, String block,
                               String hand, String handAsset, java.util.List<String> drops) {
        if (finished || halted) return false;
        Ast.OnBreak h = handler(Ast.OnBreak.class);
        if (h == null) return false;
        actor = player == null ? "" : player;
        wx = x; wy = y; wz = z;
        wblock = block == null ? "" : block;
        whand = hand == null ? "" : hand;
        whandAsset = handAsset == null ? "" : handAsset;
        wdrops.clear();
        if (drops != null) for (String d : drops) wdrops.add(d);
        log("破坏：" + actor + " @(" + (int) x + "," + (int) y + "," + (int) z + ") " + wblock
                + (drops == null ? "" : " 掉落 " + drops.size() + " 种"));
        fire(h, "破坏", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * <b>方块被放置</b>。
     *
     * @param placed 放下的那个方块（注册名）
     */
    public boolean acceptPlace(String player, double x, double y, double z, String placed) {
        if (finished || halted) return false;
        Ast.OnPlace h = handler(Ast.OnPlace.class);
        if (h == null) return false;
        actor = player == null ? "" : player;
        wx = x; wy = y; wz = z;
        wblock = placed == null ? "" : placed;
        log("放置：" + actor + " @(" + (int) x + "," + (int) y + "," + (int) z + ") " + wblock);
        fire(h, "放置", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

   /**
     * 玩家**跨过某条区域（{@code area}）的边界**：
     * {@code edge} = 1（刚进）/ -1（刚出）· {@code areaName} = 跨过的是哪条（内建值 {@code edge_area}；
     * 匿名那条 = 空串）。
     *
     *
     * <p>只在写了 {@code area} 的局里发 —— 没写就是整个维度，没有「圈边」可言。
     */
    public boolean acceptEdge(String player, double x, double y, double z, String block,
                              double edge, String areaName) {
        return fireWorld(player, x, y, z, block, "", "", false, false, edge, areaName, "", "", false);
    }

    /**
     * 世界事件（{@code on world}）的<b>唯一</b>入口：落坐标 / 方块 / 触发者与「这一下是什么手势」的三个值，
     * 再跑脚本。
     *
     *
     * @return true = 脚本收下了这一下（宿主据此决定吃不吃原版交互）
     */
    private boolean fireWorld(String player, double x, double y, double z, String block,
                              String hand, String offhand, boolean sneak, boolean rclick, double edge,
                              String edgeArea, String handAsset, String offAsset, boolean useAir) {
        if (finished || halted) return false;
        // on use（右键空气用物品）与 on world 共用一个入口：**注入的那一套值一模一样**，只有派发给谁不同 ——
        // 所以按旗标选 handler，不另抄一遍注入。
        // ⚠ Ast.Handler 上只有 line，body 在各具体记录上 ⇒ 两条分支各自取，别硬套一个类型
        Ast.Handler home = null;
        List<Ast.Stmt> hbody = null;
        if (useAir) {
            Ast.OnUse hu = handler(Ast.OnUse.class);
            if (hu != null) { home = hu; hbody = hu.body(); }
        } else {
            Ast.OnWorld hw = handler(Ast.OnWorld.class);
            if (hw != null) { home = hw; hbody = hw.body(); }
        }
        if (hbody == null) return false;
        actor = player == null ? "" : player;
        wx = x;
        wy = y;
        wz = z;
        wblock = block == null ? "" : block;
        whand = hand == null ? "" : hand;
        woffhand = offhand == null ? "" : offhand;
        // 手里那条**资产**的名字 —— 与 hand/offhand（基底 id）是两个层次的同一个东西：
        // 想要「是不是**这条**自定义物品」看它，想要「是不是某原版物品」看 hand。
        whandAsset = handAsset == null ? "" : handAsset;
        woffAsset = offAsset == null ? "" : offAsset;
        wsneak = sneak ? 1.0 : 0.0;
        wrclick = rclick ? 1.0 : 0.0;
        wedge = edge;
        // 跨过的是哪条区域：不是边沿事件时显式传空串（同 hand/sneak 那条纪律）
        wedgeArea = edgeArea == null ? "" : edgeArea;
        fire(home, useAir ? "使用物品" : "世界", hbody);
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    // ============================================================ 画块展开（舞台去模板化）

    /** 画块展开的步数预算（画块没有副作用，但 {@code while(true)} 一样能卡死服务器 → 掐断并指行号）。 */
    private static final int MAX_DRAW = 4000;
    /** 部件嵌套深度上限（防「自己调自己」）。 */
    private static final int MAX_PART_DEPTH = 8;

    /** 画块展开的上下文：产物 + 预算 + 「这一份」是谁。 */
    private static final class DrawCtx {
        final List<Ast.Box> out = new ArrayList<>();
        int budget = MAX_DRAW;
        /** 当前这一份的身份值（部件第一个实参）；画布顶层 = -1。 */
        Object identity = -1.0;
        /** 属于「当前这一份」的框从哪个下标开始（{@code click} 从这里往后标）。 */
        int mark;
    }

    /** 这份脚本里有没有画块（{@code screen}）—— 宿主据此决定要不要走「展开」这条路（没有 = 老档静态组件树）。 */
    public boolean hasScreens() {
        return !sc.screens().isEmpty();          // 实体画面也算「有舞台」（期内含）
    }

    /**
     * 这份脚本里有没有绘画区（{@code paint}）—— 宿主据此决定要不要为本局建临时画板。
     *
     */
    public boolean usesPaint() {
        for (Ast.Screen s : sc.screens().values()) if (hasPaint(s.body())) return true;
        for (Ast.Part p : sc.parts().values()) if (hasPaint(p.body())) return true;
        return false;
    }

    private static boolean hasPaint(List<Ast.Stmt> body) {
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Draw d && d.kind().equals("paint")) return true;
            if (s instanceof Ast.If f && (hasPaint(f.then()) || hasPaint(f.els()))) return true;
            if (s instanceof Ast.While w && hasPaint(w.body())) return true;
            if (s instanceof Ast.For f && hasPaint(f.body())) return true;
        }
        return false;
    }

    /**
     * 展开前把「这个人看不到的槽位」临时置空 —— 与快照裁切<b>同一条口径</b>。
     *
     */
    private List<Object[]> hideInvisible(String forViewer) {
        List<Object[]> undo = new ArrayList<>();
        for (Ast.Decl d : decls.values()) {
            if (d.vis() == null) continue;
            boolean hide;
            try {
                hide = !visibleTo(d.name(), forViewer);
            } catch (Ast.ScriptError e) {
                hide = true;                                   // 条件本身出错 → 当看不到（宁可少给，不泄露）
            }
            if (!hide) continue;
            if (!vars.containsKey(d.name())) continue;
            undo.add(new Object[] { d.name(), vars.get(d.name()) });
            vars.put(d.name(), "");                        // 临时置空（展开是同步的，finally 里还原）
        }
        return undo;
    }

    /** 还原 {@link #hideInvisible} 动过的变量。 */
    private void restoreHidden(List<Object[]> undo) {
        for (Object[] u : undo) vars.put((String) u[0], u[1]);
    }

    /** 上一次展开里 {@code place(…)} 的值（{x, y, 宽, 高}，屏幕比例）；没写 = null。宿主与编辑器读它。 */
    private double[] place;

    /**
     * 上一次展开那块画布的摆位（{@code screen hud} 里写的 {@code place(…)}）。
     *
     */
    public double[] lastPlace() {
        return place;
    }

    /**
     * 按<b>某个人</b>展开一块画布：跑 {@code screen 名 { … }} → 一串框（宿主拿它拼舞台组件）。
     *
     *
     * <p>没有这块画布 = 空列表（宿主回落静态组件树）。
     */
    public List<Ast.Box> expand(String screenName, String forViewer) {
        place = null;                                       // 每次展开先清：上一次那块画布的摆位不许残留
        Ast.Screen scr = sc.screens().get(screenName);
        if (scr == null) return List.of();
        String prevViewer = viewer;
        viewer = forViewer == null ? "" : forViewer;
        DrawCtx ctx = new DrawCtx();
        List<Object[]> undo = hideInvisible(viewer);        // 看不到的槽位临时置空（与快照同口径）
        frames.add(new LinkedHashMap<>());                  // 画块自己的作用域（循环变量 / 部件形参住这里）
        try {
            drawBody(scr.body(), ctx, 0);
        } catch (HaltSig e) {
            // halt 只记了错误没带出来 —— 展开失败不该把整局掐掉，转成带行号的脚本错交给宿主显示
            throw new Ast.ScriptError(scr.line(), "展开画布 " + screenName + " 出错：" + error);
        } finally {
            frames.remove(frames.size() - 1);
            restoreHidden(undo);
            viewer = prevViewer;
        }
        return List.copyOf(ctx.out);
    }

    /** 跑一段画块语句（产物进 ctx；depth = 部件嵌套深度）。 */
    private void drawBody(List<Ast.Stmt> body, DrawCtx ctx, int depth) {
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Draw d) {
                step(ctx, d.line());
                if (d.kind().equals("paint")) {
                    // paint 不画新框：把「刚画的那个框」升级成绘画区（临时画板画在它里面）
                    if (ctx.out.size() <= ctx.mark) {
                        throw new Ast.ScriptError(d.line(), "paint 前面没有刚画的框 —— 它把上一个框变成绘画区");
                    }
                    Ast.Box b = ctx.out.get(ctx.out.size() - 1);
                    ctx.out.set(ctx.out.size() - 1, new Ast.Box("paint", b.x(), b.y(), b.w(), b.h(),
                            "", b.bg(), b.color(), false, ctx.identity, b.line()));
                } else {
                    ctx.out.add(boxOf(d, ctx.identity));
                }
            } else if (s instanceof Ast.Place pl) {
                step(ctx, pl.line());
                // 画布自己的摆位（只有 hud 那块有）：解析期已保证「只写在 hud 里、最多一条」，这里再兜一层
                if (place != null) throw new Ast.ScriptError(pl.line(), "place(…) 只能写一条");
                double[] v = new double[4];
                for (int k = 0; k < 4; k++) {
                    Double d = Builtins.numOf(eval(pl.args().get(k)));
                    if (d == null) throw new Ast.ScriptError(pl.line(), "place 的 4 个参数都要是数字（屏幕比例 0~1）");
                    v[k] = d;
                }
                if (v[0] < 0 || v[1] < 0 || v[2] <= 0 || v[3] <= 0 || v[0] > 1 || v[1] > 1 || v[2] > 1 || v[3] > 1) {
                    throw new Ast.ScriptError(pl.line(), "place 的 x / y / 宽 / 高 都是 0~1 的屏幕比例"
                            + "（越界会被贴到屏幕外，这里是 " + v[0] + ", " + v[1] + ", " + v[2] + ", " + v[3] + "）");
                }
                // 注意：**不查** x+宽 ≤ 1 —— 缺省那组（0.66 + 0.45）本来就超出，靠「贴边夹回来」收敛，
                // 这与旧实现一致（hudX + 上限宽，落到屏上时被 clamp）。
                place = v;
            } else if (s instanceof Ast.Click cl) {
                if (ctx.out.size() <= ctx.mark) {
                    throw new Ast.ScriptError(cl.line(), "click 前面没有框 —— 它标记的是「这一份画出来的那些框」");
                }
                for (int k = ctx.mark; k < ctx.out.size(); k++) {          // 这一份的每个框都可点 → 点边缘也算点中
                    Ast.Box b = ctx.out.get(k);
                    // ⚠ comp("名") 定过的身份（字符串）**不许被覆盖**：
                    // click 是「这一份的框批量可点」，comp 是显式命名；批量盖掉显式命名会让
                    // 「box → click → comp」这种最自然的写法**静默失效**（按钮点了没反应）。
                    Object id = (b.identity() instanceof String) ? b.identity() : ctx.identity;
                    ctx.out.set(k, new Ast.Box(b.kind(), b.x(), b.y(), b.w(), b.h(), b.text(), b.bg(), b.color(),
                            true, id, b.line()));
                }
            } else if (s instanceof Ast.Comp cp) {
                step(ctx, cp.line());
                // 组件起名：只作用到**刚画的那一个框**（像 paint 一样），
                // 资产名 = 它的身份值（可点时 on pick 收到的就是这个名字）。
                if (ctx.out.size() <= ctx.mark) {
                    throw new Ast.ScriptError(cp.line(), "comp(…) 前面没有刚画的框 —— 它给上一个框起名");
                }
                Ast.Box b = ctx.out.get(ctx.out.size() - 1);
                ctx.out.set(ctx.out.size() - 1, new Ast.Box(b.kind(), b.x(), b.y(), b.w(), b.h(), b.text(),
                        b.bg(), b.color(), b.clickable(), cp.id(), b.line()));
            } else if (s instanceof Ast.If f) {
                step(ctx, f.line());
                drawBody(Builtins.truthy(eval(f.cond())) ? f.then() : f.els(), ctx, depth);
            } else if (s instanceof Ast.While w) {
                while (Builtins.truthy(eval(w.cond()))) {
                    step(ctx, w.line());
                    drawBody(w.body(), ctx, depth);
                }
            } else if (s instanceof Ast.For f) {
                Map<String, Object> loop = new LinkedHashMap<>();
                loop.put(f.var(), eval(f.from()));
                frames.add(loop);
                try {
                    while (Builtins.truthy(eval(f.cond()))) {
                        step(ctx, f.line());
                        drawBody(f.body(), ctx, depth);
                        runStep(f.step());
                    }
                } finally {
                    frames.remove(frames.size() - 1);
                }
            } else if (s instanceof Ast.ExprStmt es && es.e() instanceof Ast.Call c) {
                Ast.Part pt = sc.parts().get(c.fn());
                if (pt == null) throw new Ast.ScriptError(c.line(), "没有这个部件：" + c.fn());
                if (depth >= MAX_PART_DEPTH) {
                    throw new Ast.ScriptError(c.line(), "部件嵌套太深了（超过 " + MAX_PART_DEPTH + " 层）—— 是不是自己调自己？");
                }
                List<Object> args = new ArrayList<>();
                for (Ast.Expr ae : c.args()) args.add(eval(ae));
                if (args.size() != pt.params().size()) {
                    throw new Ast.ScriptError(c.line(), "部件 " + pt.name() + " 要 " + pt.params().size()
                            + " 个参数（第一个是它的身份），这里给了 " + args.size() + " 个");
                }
                step(ctx, c.line());
                Map<String, Object> scope = new LinkedHashMap<>();
                for (int k = 0; k < args.size(); k++) scope.put(pt.params().get(k), args.get(k));
                frames.add(scope);
                Object prevId = ctx.identity;
                int prevMark = ctx.mark;
                ctx.identity = args.get(0);                 // 第一个实参 = 这一份的身份（点了就回它）
                ctx.mark = ctx.out.size();
                try {
                    drawBody(pt.body(), ctx, depth + 1);
                } finally {
                    frames.remove(frames.size() - 1);
                    ctx.identity = prevId;
                    ctx.mark = prevMark;
                }
            } else {
                throw new Ast.ScriptError(s.line(),
                        "画块里不认识的语句（只允许 box / text / click / 部件调用 / if / while / for）");
            }
        }
    }

    /** 展开预算扣一格（防 {@code while(true)} 卡死服务器）。 */
    private void step(DrawCtx ctx, int line) {
        if (ctx.budget-- <= 0) {
            throw new Ast.ScriptError(line, "画块展开超过 " + MAX_DRAW + " 步（疑似死循环）");
        }
    }

    /** {@code for} 的步进：画块里的循环变量住在画块作用域里，改的是局部那一份。 */
    private void runStep(Ast.Stmt step) {
        if (step instanceof Ast.Incr i) {
            assign(new Ast.Assign(i.name(), List.of(), "+=", new Ast.Num(i.delta(), i.line()), i.line()));
        } else if (step instanceof Ast.Assign a) {
            assign(a);
        }
    }

    /** 一个画语句 → 一个框：几何 / 样式 / 内容全是表达式，现算。（{@code paint} 不走这里 —— 它升级上一个框。） */
    private Ast.Box boxOf(Ast.Draw d, Object identity) {
        List<Ast.Expr> a = d.args();
        String kind = d.kind();
        // 值框 `value(x, y, 宽, 高, 表达式)`：把**表达式的值**当文字画出来。
        // 与别的框最大的不同：**每次展开（每份快照）重新求值** —— 展开是按观看者做的
        //（{@link #expand} 的 forViewer，那里面 viewer 有值）⇒ 所以能写
        // `"金币：" + text(num(profile_get(viewer, "gold"), 0))`，每人看到自己那一份。
        // 归一成 "text" 交给下游：宿主 / 渲染器 / 编辑器都不用认识新种类。
        if (kind.equals("value")) {
            return new Ast.Box("text", numAt(a.get(0), d.line(), "x"), numAt(a.get(1), d.line(), "y"),
                    numAt(a.get(2), d.line(), "宽"), numAt(a.get(3), d.line(), "高"),
                    Builtins.text(eval(a.get(4))), "", "", false, identity, d.line());
        }
        double x = numAt(a.get(0), d.line(), "x");
        double y = numAt(a.get(1), d.line(), "y");
        double w = numAt(a.get(2), d.line(), "宽");
        double h = numAt(a.get(3), d.line(), "高");
        // 第 5 个参数按种类变义：box=底色 · text=内容 · input=占位提示 · card_face/card_back=卡 id
        //  加：`box(x,y,宽,高,底色,文字,字色)`（**7 个实参**）= 「框 + 文字」合成一种。
        // 出来的框 kind 记 **"text"**（与 text(…) 同一条渲染路，只是多了底色）—— 下游（宿主 / 渲染器）
        // 看见 text 就会去读 bg / text / color 三样，正好是这三条。⚠ kind 在这里只表示「怎么渲染」，
        // 画语句叫什么由 Ast.Draw.kind 记（`box`）—— 编辑器认的是后者，不会混。
        boolean labelled = kind.equals("box") && a.size() >= 7;
        String text = kind.equals("text") || kind.equals("input")
                || kind.equals("card_face") || kind.equals("card_back") || kind.equals("img")   // img（期12）：第 5 参 = 资产名
                ? Builtins.text(eval(a.get(4)))
                : (labelled ? Builtins.text(eval(a.get(5))) : "");
        String bg = kind.equals("box") ? Builtins.text(eval(a.get(4))) : "";
        String color = kind.equals("text") ? Builtins.text(eval(a.get(5)))
                : (labelled ? Builtins.text(eval(a.get(6))) : "");
        return new Ast.Box(labelled ? "text" : kind, x, y, w, h, text, bg, color, false, identity, d.line());
    }

    private double numAt(Ast.Expr e, int line, String what) {
        Double v = Builtins.numOf(eval(e));
        if (v == null) throw new Ast.ScriptError(line, what + " 要是一个数字");
        return v;
    }

    /**
     * 每 tick 调一次（MC 20 tick/秒）：推进"周期"与"阶段超时"两类时机，并唤醒到点的挂起链。
     * 时间由调用方注入（纯逻辑不碰时钟）。
     */
    public void tick(long nowMs) {
        clock = nowMs;
        budget = MAX_STEPS;
        if (finished || halted) return;

        // ① 挂起到点 → 接着跑（执行栈还在，从断点续）
        if (waiting && nowMs >= waitAt) {
            waiting = false;
            log("到点，接着跑");
            resumeChain("接着跑");
        }

        // ② 周期时机 on every(秒)
        for (Ast.Handler h : sc.handlers()) {
            if (finished || halted) return;
            if (!(h instanceof Ast.OnEvery ev)) continue;
            String key = "every@" + ev.line();
            long period = Math.max(50, (long) (ev.sec() * 1000));
            Long last = lastEvery.get(key);
            if (last == null) {
                lastEvery.put(key, nowMs);                  // 第一次见到：只记时刻（否则默认值会被当成"刚触发过"）
            } else if (nowMs - last >= period) {
                lastEvery.put(key, nowMs);
                fire(ev, "每 " + Builtins.fmt(ev.sec()) + " 秒", ev.body());
            }
        }

        // ③ 阶段超时（timer(秒) 上了闹钟才算）
        if (stageSec > 0 && !timeoutFired && nowMs - stageAt >= (long) (stageSec * 1000)) {
            timeoutFired = true;
            Ast.OnTimeout h = handler(Ast.OnTimeout.class);
            log("阶段「" + stage + "」超时");
            if (h != null) fire(h, "阶段超时", h.body());
        }
    }

    /** 跑一条事件链：重置预算（另有链挂在栈上时这次事件不接，口径同旧版）。 */
    private void fire(Ast.Handler home, String label, List<Ast.Stmt> body) {
        if (finished || halted || waiting) return;
        budget = MAX_STEPS;
        runChain(body == null ? List.of() : body, label);
    }

    /** 唤醒挂起的链：预算重置一次，接着跑留着的执行栈。 */
    private void resumeChain(String label) {
        if (finished || halted) return;
        budget = MAX_STEPS;
        runChain(null, label);
    }

    // ============================================================ 链与语句

    /**
     * 走一条链（一个事件入口的语句序列），并在 {@code goto} 时接力跑目标阶段的入口链。
     *
     * <p><b>预算不在这里重置</b>：一次推进里的多条链（goto 接力）公用同一个预算 → 阶段自环能拦住。
     */
    private void runChain(List<Ast.Stmt> body, String what) {
        List<Ast.Stmt> cur = body;
        boolean push = body != null;
        while (!finished && !halted && !waiting) {
            if (push) {
                frames.add(new LinkedHashMap<>());            // 链的局部作用域
                stack.push(new Frame(cur, null, null, true));
            }
            push = false;
            if (stack.isEmpty()) return;                     // 唤醒时栈空 = 没东西可跑
            String target = driveChain(what, 1);
            if (halted || finished || waiting || target == null) return;
            enterStage(target);                               // goto：换阶段（预算不重置）
            Ast.OnStage h = stageHandler(target);
            if (h == null) {
                halt("阶段「" + target + "」没有 on stage 入口");
                return;
            }
            cur = h.body();
            push = true;
        }
    }

    /**
     * 驱动执行栈：跑下一条语句 → 块推帧 / 帧尾回环 / 出帧；直到栈回落到 {@code minDepth} 之下、
     * 挂起、结束或出错。返回 {@code goto} 的目标阶段（null = 不是 goto 结束的）。
     *
     * <p>跳转类异常（break/continue/return/goto/end/出错）都在这里统一处理。
     */
    private String driveChain(String what, int minDepth) {
        while (!finished && !halted && !waiting && stack.size() >= minDepth) {
            Frame f = stack.peek();
            try {
                if (f.ip >= f.body.size()) {                  // 帧尾
                    frameTail(f);
                    continue;
                }
                Ast.Stmt s = f.body.get(f.ip++);
                step(s);
                exec(s);
            } catch (BreakSig b) {
                if (!unwindToLoop(true)) { halt(what + " 里出现了不在循环里的 break"); return null; }
            } catch (ContinueSig c) {
                if (!unwindToLoop(false)) { halt(what + " 里出现了不在循环里的 continue"); return null; }
            } catch (ReturnSig r) {
                // ⚠ 这里**不能**做 text 转换：自定义函数的返回值要保持原类型（数还是数、列表还是列表）。
                // 曾经是 Builtins.text(r.value) —— 返回 6 出来变成 "6"，外面 s += 累加就成了字符串相接
                // （21 点庄家试验抓到的：点数算成 "098"、`"098" < 17` 也失灵），而返回文本的函数看不出问题。
                if (!unwindCall(r.value)) { halt(what + " 里出现了不在函数里的 return"); return null; }
            } catch (GotoSig g) {
                dropAll();
                return g.stage;
            } catch (EndSig e) {
                dropAll();
                finished = true;
                waiting = false;
                log("对局结束");
                sink.stateChanged();
                return null;
            } catch (HaltSig e) {
                dropAll();
                return null;                                  // 预算超了：就地停下（halt 已经在 step 里报过）
            } catch (Ast.ScriptError e) {
                dropAll();
                halt(e.getMessage());                         // 运行期报错也带行号
                return null;
            }
        }
        return null;
    }

    /** 帧尾：环帧回帧头（for 先跑步进），普通帧出栈。 */
    private void frameTail(Frame f) {
        if (f.loopW != null) {
            step(f.loopW);                                    // 空循环体的 while(true){} 也靠这行被掐断
            if (Builtins.truthy(eval(f.loopW.cond()))) {
                f.ip = 0;
                return;
            }
        } else if (f.loopF != null) {
            step(f.loopF);
            exec(f.loopF.step());                             // continue 也走到这儿（C 式 for 的语义）
            if (Builtins.truthy(eval(f.loopF.cond()))) {
                f.ip = 0;
                return;
            }
        }
        popFrame(f);
    }

    /** 出帧：带走它自己开的局部作用域。 */
    private void popFrame(Frame f) {
        Frame top = stack.pop();
        if (top != f) throw new IllegalStateException("执行栈错位");
        if (f.ownScope && !frames.isEmpty()) frames.remove(frames.size() - 1);
    }

    /** 清空执行栈（goto / 结束 / 出错时用）：连各帧的局部作用域一起撤。 */
    private void dropAll() {
        while (!stack.isEmpty()) popFrame(stack.peek());
    }

    /** break / continue 找最近的环帧：break 连它弹掉；continue 跳到它的帧尾（for 的步进照跑）。 */
    private boolean unwindToLoop(boolean isBreak) {
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            boolean loop = f.loopW != null || f.loopF != null;
            if (loop) {
                if (isBreak) popFrame(f);
                else f.ip = f.body.size();
                return true;
            }
            popFrame(f);
        }
        return false;
    }

    /** {@code return}：把值交给最近的调用帧（值保持原类型：数还是数、文本还是文本）。 */
    private boolean unwindCall(Object value) {
        while (!stack.isEmpty()) {
            Frame f = stack.peek();
            boolean call = f.ret != null;
            popFrame(f);
            if (call) {
                f.ret[0] = value;
                return true;
            }
        }
        return false;
    }

    /** 预算：每执行一条语句扣一步；扣完就地掉断（防死循环）。 */
    private void step(Ast.Stmt s) {
        if (budget-- <= 0) {
            halt("一次推进超过 " + MAX_STEPS + " 步（疑似死循环，已在第 " + s.line() + " 行附近掉断）");
            throw new HaltSig();
        }
    }

    private void exec(Ast.Stmt s) {
        if (s instanceof Ast.Decl d) {
            declare(d);
        } else if (s instanceof Ast.Assign a) {
            assign(a);
        } else if (s instanceof Ast.Incr i) {
            assign(new Ast.Assign(i.name(), List.of(), "+=", new Ast.Num(i.delta(), i.line()), i.line()));
        } else if (s instanceof Ast.If f) {
            List<Ast.Stmt> arm = Builtins.truthy(eval(f.cond())) ? f.then() : f.els();
            if (!arm.isEmpty()) stack.push(new Frame(arm, null, null, false));
        } else if (s instanceof Ast.While w) {
            if (Builtins.truthy(eval(w.cond()))) stack.push(new Frame(w.body(), w, null, false));
        } else if (s instanceof Ast.For f) {
            if (frames.size() >= MAX_DEPTH) {
                throw new Ast.ScriptError(f.line(), "循环嵌套太深了（超过 " + MAX_DEPTH + " 层）");
            }
            Map<String, Object> scope = new LinkedHashMap<>();   // 循环变量住这一层（每进一次循环新建）
            scope.put(f.var(), eval(f.from()));
            frames.add(scope);
            if (Builtins.truthy(eval(f.cond()))) stack.push(new Frame(f.body(), null, f, true));
            else frames.remove(frames.size() - 1);               // 一次都不跑：作用域别留下
        } else if (s instanceof Ast.Break) {
            throw new BreakSig();
        } else if (s instanceof Ast.Continue) {
            throw new ContinueSig();
        } else if (s instanceof Ast.Return r) {
            throw new ReturnSig(r.value() == null ? "" : eval(r.value()));
        } else if (s instanceof Ast.Goto g) {
            throw new GotoSig(g.stage());
        } else if (s instanceof Ast.Say sp) {
            doSay(sp);
        } else if (s instanceof Ast.Show sh) {
            doShow(sh);
        } else if (s instanceof Ast.Hide hd) {
            doHide(hd);
        } else if (s instanceof Ast.Wait w) {
            doWait(w);
        } else if (s instanceof Ast.Scanf sc) {
            doScanf(sc);
        } else if (s instanceof Ast.Timer t) {
            stageSec = t.sec();
            stageAt = clock;
            timeoutFired = false;
            log("本阶段限时 " + Builtins.fmt(t.sec()) + " 秒");
        } else if (s instanceof Ast.End) {
            throw new EndSig();
        } else if (s instanceof Ast.ExprStmt es) {
            eval(es.e());
        } else {
            throw new Ast.ScriptError(s.line(), "不认识的语句");
        }
    }


    /** {@code var}：在函数/链里 = 局部变量；顶层声明由 {@link #seed} 铺。 */
    private void declare(Ast.Decl d) {
        if (frames.isEmpty()) {
            throw new Ast.ScriptError(d.line(), "var 只能写在顶层（或函数/链里当局部变量）");
        }
        if (d.vis() != null) {
            throw new Ast.ScriptError(d.line(), "可见性条件只能写在顶层槽位上（局部 var 不进快照，写了也没用）");
        }
        frames.get(frames.size() - 1).put(d.name(), eval(d.init()));
    }

    /**
     * {@code show("名")}：记下「现在该显示哪一块舞台」——**引擎只记状态，开屏/关屏是客户端的事**。
     *
     * <p>按那块自己的类别生效（全屏 → 当前全屏；看板 → 当前看板），两份「当前」并存；
     * {@code show("")} = 两类都收起。<b>幂等</b>由消费方判（引擎这里只赋值，同样的值赋两次没有副作用）。
     *
     * <p>名字认不出 ⇒ 报带行号的脚本错（作者写错了就该当场看见，别静默什么都不发生）。
     */
    private void doShow(Ast.Show s) {
        String name = Builtins.text(eval(s.name()));
        if (name.isEmpty()) {                                  // show("") 已作废（2026-09-18 用户定案）
            throw new Ast.ScriptError(s.line(), "show 的名字不能是空的 —— 要收起全屏请用 hide()");
        }
        Ast.Screen scr = sc.screens().get(name);
        if (scr == null) {
            throw new Ast.ScriptError(s.line(), "没有叫「" + name + "」的舞台 —— 检查 screen 声明的名字");
        }
        // 实体画面：**必须写锚点**（show(谁, "e1")）—— 它要挂在谁身上是运行时的事。
        if (scr.entityView()) {
            if (s.who() == null) {
                throw new Ast.ScriptError(s.line(),
                        "实体画面要写锚点：show(谁, \"" + name + "\")（谁 = 玩家名或本局实体名）");
            }
            String anchor = Builtins.text(eval(s.who()));
            if (anchor.isEmpty() || anchor.equals("all")) {
                throw new Ast.ScriptError(s.line(), "实体画面的锚点不能是空 / all —— 写一个玩家名或本局实体名");
            }
            showEntities.add(new ShowEntity(name, anchor));
            log("显示实体画面：" + name + "（挂在 " + anchor + "）");
            sink.stateChanged();
            return;
        }
        List<String> who = showTargets(s.who());
        if (who.isEmpty()) {                                    // 全局：一参形态，或 who 是 all
            if (scr.hud()) {
                if (name.equals(curHud)) return;                // 幂等
                curHud = name;
            } else {
                if (name.equals(curFull)) return;
                curFull = name;
            }
            // 全局切 = 顺带把每人的**单独指定**清掉：留着会被旧的压着，看起来像「全局 show 没生效」
            fullPer.clear();
            hudPer.clear();
            log("切舞台：" + name + "（全体）");
        } else {
            for (String w : who) {
                (scr.hud() ? hudPer : fullPer).put(w, name);
            }
            log("切舞台：" + name + "（只给 " + String.join("/", who) + "）");
        }
        sink.stateChanged();                                    // 让宿主把新状态发下去
    }

    /**
     * {@code show(谁, …)} 的地址表（照 {@link #doSay} 的「谁」口径）：{@code all} = 全体（当全局切）·
     * {@code others} = 除当前 actor 之外的席位（非人席位跳过，它没人看）· 其余按名字逐个。
     * 返回空表 = 全局。
     */
    private List<String> showTargets(Ast.Expr whoExpr) {
        if (whoExpr == null) return List.of();
        String who = Builtins.text(eval(whoExpr));
        if (who.isEmpty() || who.equals("all")) return List.of();
        if (who.equals("others")) {
            List<String> out = new ArrayList<>();
            for (String st : people) {
                if (st.equals(actor)) continue;
                out.add(st);
            }
            return out;
        }
        return List.of(who);
    }

    /**
     * {@code hide}＝全收（HUD / 全屏都清）；{@code hide("名")}＝只收那块。
     *
     * <p>全屏照旧只 bump {@link #hideSeq} 让客户端关屏、保留内容（收起 ≠ 清空）；点名收全屏也在同一个 bump 里。
     * 点名收 HUD / 单独指定的：直接清掉那格，快照变了客户端自己不画。
     */
    private void doHide(Ast.Hide h) {
        hideSeq++;                                              // 客户端比对计数 → 关掉全屏屏（内容保留）
        if (h.name() == null) {
            curFull = null;
            curHud = null;
            fullPer.clear();
            hudPer.clear();
            lastHidden = null;
            hideEntities.add(new HideEntity("", ""));           // 实体画面也全收（期2）
            log("收起全部舞台");
            return;
        }
        String name = Builtins.text(eval(h.name()));
        if (name.isEmpty()) throw new Ast.ScriptError(h.line(), "hide 的名字不能是空的 —— 全收写 hide()");
        String who = h.who() == null ? "" : Builtins.text(eval(h.who()));   // 两参形态：只收那个人身上那份
        Ast.Screen scr = sc.screens().get(name);
        if (scr == null) throw new Ast.ScriptError(h.line(), "没有叫「" + name + "」的舞台 —— 检查 screen 声明的名字");
        if (scr.entityView()) {
            hideEntities.add(new HideEntity(name, who));
            lastHidden = name;
            log("收起实体画面：" + name);
            sink.stateChanged();
            return;
        }
        if (who.isEmpty()) {
            if (name.equals(curFull)) curFull = null;
            if (name.equals(curHud)) curHud = null;
            fullPer.values().removeIf(v -> v.equals(name));
            hudPer.values().removeIf(v -> v.equals(name));
        } else {
            // 两参形态：只摘**他那格**的单独指定；全局那块不归他一个人关（记一行日志说明）
            if (name.equals(fullPer.get(who))) fullPer.remove(who);
            if (name.equals(hudPer.get(who))) hudPer.remove(who);
            if (name.equals(curFull) || name.equals(curHud)) {
                log("hide(" + who + ", \"" + name + "\")：这块是全服共用的（show(\"名\") 切的）"
                        + "—— 只摘了他的单独指定，共用那块没动");
            }
        }
        lastHidden = name;
        log("收起舞台：" + name);
        sink.stateChanged();
    }

    private void doSay(Ast.Say s) {
        String who = Builtins.text(eval(s.who()));
        String text = Builtins.text(eval(s.text()));
        if (who.isEmpty() || who.equals("all")) {
            sink.message(text);
        } else if (who.equals("others")) {
            // 「others」= 在场名单里除我之外的人（名单随进局 / 离局事件维护，见 acceptJoin / acceptLeave）。
            for (String st : people) {
                if (st.equals(actor)) continue;
                sink.messageTo(st, text);
            }
        } else {
            sink.messageTo(who, text);
        }
        log("说给 " + (who.isEmpty() ? "all" : who) + "：" + text);
    }

    /**
     * {@code wait(秒)}：挂起这条链 —— 执行栈 <b>原地留着</b>，到点从下一条语句接着跑。
     *
     * <p>ponytail: 唯一不能等的地方 = {@code func} 里面。自定义函数是**同步算完就返回值**的
     * （值要立刻交给调用处），挂起了没法送回来 —— 要真支持得给表达式做续体那一套。
     * 撞上就报错指路，不静默；升级路径：给表达式也做续体，或把这段挪进事件链/阶段。
     */
    private void doWait(Ast.Wait w) {
        for (Frame f : stack) {
            if (f.awaiting) {
                throw new Ast.ScriptError(w.line(),
                        "wait 不能写在函数（func）里 —— 函数是同步算完就返回值的，等不了；"
                                + "把要等的那段挪到事件链 / 阶段里");
            }
        }
        waiting = true;
        waitAt = clock + (long) (w.sec() * 1000);
        log("挂起：等 " + Builtins.fmt(w.sec()) + " 秒");
        sink.stateChanged();
    }

    /**
     * {@code 变量 = scanf(谁〔, "文"〕)}：**挂起这一串等一次提交**，值落进那个变量再接着跑。
     * ⚠ 与 {@link #doWait} 共用 {@link #waiting} 开关 ⇒ 挂起期间整局暂停（别的链都不跑；`runChain` 的循环条件是结构性保证）。
     * 区别只有一点：wait 到点自动醒，这里只等提交（waitAt 设成永不到期）；要限时就 timer + on timeout。不许写在函数里。
     */
    private void doScanf(Ast.Scanf sc) {
        for (Frame f : stack) {
            if (f.awaiting) {
                throw new Ast.ScriptError(sc.line(),
                        "scanf 不能写在函数（func）里 —— 函数是同步算完就返回值的，等不了；把要等的那段挪到事件链 / 阶段里");
            }
        }
        String who = Builtins.text(eval(sc.who())).trim();
        // 类型跟**左边变量当前的值的类型**走（C 的 `int guess;` 那道理）：数字 ⇒ 读数字；其余 ⇒ 读文本。
        // 为什么不在解析期定：那时只是「猜」这个名字，值还没落地；这里读一次最准，也省掉一个特例参数。
        Object cur = null;
        try {
            cur = eval(new Ast.Name(sc.target(), sc.line()));
        } catch (Ast.ScriptError e) {
            throw new Ast.ScriptError(sc.line(),
                    "scanf 要写一个**声明过的变量**（先 var 猜 = 0，再 猜 = scanf(actor)）");
        }
        scanning = true;
        scanVar = sc.target();
        scanWho = who.isEmpty() || who.equals("all") ? "" : who;      // all = 谁都行
        scanBox = sc.box();                                           // 空串 = 哪个框交的都算
        scanText = !(cur instanceof Number);
        waiting = true;
        waitAt = Long.MAX_VALUE;                                      // 只等提交，不等时间
        log("挂起：等 " + (scanWho.isEmpty() ? "任何人" : scanWho)
                + (scanBox.isEmpty() ? "" : "（框 " + scanBox + "）") + " 提交");
        sink.stateChanged();
    }

    /** 交进来的文本当数字用（转不了 = 0，不掐局 —— 命令那条路可能塞进任意文本）。 */
    private static double scanNumber(String text) {
        Double d = Builtins.numOf(text);
        return d == null ? 0 : d;
    }

    /** 给「落值」造一棵表达式（数字 / 文本各一支）。 */
    private static Ast.Expr valueExpr(Object value, String body) {
        return value instanceof Double d ? new Ast.Num(d, 0) : new Ast.Str(body, 0);
    }

    /** {@code goto}：换阶段 + 跑该阶段入口链；预算不重置（跨链共享）。 */
    private void enterStage(String to) {
        stage = to;
        stageAt = clock;
        stageSec = -1;
        timeoutFired = false;
        waiting = false;                                    // 流程转移 → 之前挂起的链作废
        scanning = false;                                   // 等输入也一起作废
        log("进入阶段「" + to + "」");
        sink.stateChanged();
    }

    private void halt(String reason) {
        halted = true;
        waiting = false;
        scanning = false;
        error = reason;
        log("停住：" + reason);
        sink.message("[对局] ⚠ " + reason);
        sink.stateChanged();
    }

    // ============================================================ 赋值

    /**
     * 赋值：{@code a = e} / {@code a[i] = e} / {@code a += e} ——
     * 目标可以是局部变量、变量本身、或者它的某一项 / 某个字段。
     */
    private void assign(Ast.Assign a) {
        int line = a.line();
        String name = a.name();
        Ast.Decl d = decls.get(name);
        boolean local = frameHas(name);
        int n = a.subs().size();

        Object target;                                      // 要写的那个"东西"：局部变量 / 变量本身 / 列表 / 记录
        if (local) {
            target = frameGet(name);
        } else if (d != null) {
            target = vars.get(name);
        } else {
            throw new Ast.ScriptError(line, "没有这个名字：" + name + "（变量要先 var 声明）");
        }

        if (n == 0) {                                       // 没有下标 → 写这个变量本身
            Object val = combine(target, a.op(), eval(a.value()), line);
            if (local) framePut(name, val);
            else vars.put(name, val);
            log("设 " + name + " " + a.op() + " → " + Builtins.text(val));
            return;
        }

        for (int k = 0; k < n - 1; k++) target = indexForWrite(target, eval(a.subs().get(k)), line);
        // 最后一段下标：列表 → set；记录 → put。写字段不存在就**创建**
        // （rec 空记录的工作流就是逐字段赋出来的）；对嵌套链 pts[who].gold 同样成立。
        if (target instanceof java.util.LinkedHashMap<?, ?>) {
            @SuppressWarnings("unchecked")
            java.util.LinkedHashMap<String, Object> r = (java.util.LinkedHashMap<String, Object>) target;
            String key = Builtins.text(eval(a.subs().get(n - 1)));
            if (key.isEmpty()) {
                throw new Ast.ScriptError(line, "记录的字段名不能是空文本");
            }
            r.put(key, combine(r.get(key), a.op(), eval(a.value()), line));
            log("设 " + name + "[…" + key + "] " + a.op() + " → " + Builtins.text(r.get(key)));
            return;
        }
        List<Object> list = Builtins.listOf(target, line, "下标左边的值");
        int i = Builtins.toInt(eval(a.subs().get(n - 1)), line, "下标");
        if (i < 0 || i >= list.size()) {
            throw new Ast.ScriptError(line, "下标越界：" + i + "（这张列表只有 " + list.size() + " 项）");
        }
        list.set(i, combine(list.get(i), a.op(), eval(a.value()), line));
        log("设 " + name + "[…] " + a.op() + " → " + Builtins.text(list.get(i)));
    }

    /** 复合赋值：{@code +=} 数字相加 / 文本相接 / 列表追加；{@code -= *=} 只算数字。 */
    private static Object combine(Object old, String op, Object val, int line) {
        if (op.equals("=")) return val;
        // 记录本身不参与 += -= *=（字段级运算走 r.f += x，到不了这里）；直接指路不静默。
        if (old instanceof java.util.LinkedHashMap<?, ?>) {
            throw new Ast.ScriptError(line, "记录不能整体 " + op + "（要对字段算：r.字段 " + op + " 值）");
        }
        if (op.equals("+=")) {
            if (old instanceof List<?>) {                    // 列表 += 一项（沿用第四期"列表加项"语义）
                List<Object> l = Builtins.listOf(old, line, "+= 的左边");
                l.add(val);
                return l;
            }
            if (old instanceof String || val instanceof String) return Builtins.text(old) + Builtins.text(val);
            Double x = Builtins.numOf(old);
            Double y = Builtins.numOf(val);
            if (x == null || y == null) throw new Ast.ScriptError(line, "算不了：" + Builtins.text(old) + " 和 " + Builtins.text(val) + " 不是数字");
            return x + y;
        }
        Double x = Builtins.numOf(old);
        Double y = Builtins.numOf(val);
        if (x == null || y == null) throw new Ast.ScriptError(line, "算不了（" + op + "）：" + Builtins.text(old) + " 和 " + Builtins.text(val) + " 不是数字");
        return switch (op) {
            case "-=" -> x - y;
            case "*=" -> x * y;
            default -> throw new Ast.ScriptError(line, "不认识的赋值符：" + op);
        };
    }

    // ============================================================ 求值

    /** 求一个表达式的值（数 / 文本 / 列表 / 真假）。 */
    Object eval(Ast.Expr e) {
        if (e instanceof Ast.Num n) return n.v();
        if (e instanceof Ast.Str s) return s.v();
        if (e instanceof Ast.Bool b) return b.v();
        if (e instanceof Ast.ListLit l) {
            List<Object> out = new ArrayList<>();
            for (Ast.Expr it : l.items()) out.add(eval(it));
            return out;
        }
        // 对象字面量**只能当声明里的值**（对象声明的 components { … }）—— 跑到表达式里就是写错地方了
        if (e instanceof Ast.Obj o) {
            throw new Ast.ScriptError(o.line(), "对象字面量只能写在声明里（如 components { … }），不能当表达式的值");
        }
        if (e instanceof Ast.Name n) return readName(n);
        if (e instanceof Ast.Index ix) return readIndex(ix);
        if (e instanceof Ast.Unary u) {
            if (u.op().equals("!")) return !Builtins.truthy(eval(u.e()));
            Double d = Builtins.numOf(eval(u.e()));
            if (d == null) throw new Ast.ScriptError(u.line(), "负号要用在数字上");
            return -d;
        }
        if (e instanceof Ast.Binary b) return binary(b);
        // 三目：只求取到的那一支（另一支不求值）—— 这样 i < len(h) ? h[i] : "" 才敢写
        if (e instanceof Ast.Cond c) return Builtins.truthy(eval(c.cond())) ? eval(c.yes()) : eval(c.no());
        if (e instanceof Ast.Call c) return call(c);
        // 记录成员 r.field：降糖成 r["field"] —— 同一条字符串下标路径，
        // 嵌套链、缺键报错全部免费复用（Member 只是语法糖，不另写求值器）。
        if (e instanceof Ast.Member m) return eval(new Ast.Index(m.base(), new Ast.Str(m.field(), m.line()), m.line()));
        throw new Ast.ScriptError(e.line(), "不认识的表达式");
    }

    /** 读一个名字：局部变量 → 变量 → 内建值。 */
    private Object readName(Ast.Name n) {
        String id = n.id();
        if (frameHas(id)) return frameGet(id);
        if (vars.containsKey(id)) return vars.get(id);
        return switch (id) {
            case "actor" -> actor;
            case "input" -> input;
            case "input_box" -> inputBox;
            case "pick" -> pick;
            case "bx" -> wx;
            case "by" -> wy;
            case "bz" -> wz;
            case "block" -> wblock;
            case "hand" -> whand;
            case "offhand" -> woffhand;
            case "hand_asset" -> whandAsset;
            case "off_asset" -> woffAsset;
            case "sneak" -> wsneak;
            case "rclick" -> wrclick;
            case "edge" -> wedge;
            case "edge_area" -> wedgeArea;      // 跨过的是哪条区域（批10；空串 = 匿名那条 / 不是边沿事件）
            case "drops" -> new ArrayList<Object>(wdrops);   // on break：这一下掉的物品 id 列表（挖成功那次才有）
            case "etype" -> etype;
            case "eid" -> eid;
            case "hit" -> hit;
            case "edead" -> edead;
            case "look_x" -> lx;
            case "look_y" -> ly;
            case "look_z" -> lz;
            case "look_block" -> lblock;
            case "look_player" -> lplayer;
            case "look_piece" -> lpiece;
            case "stage_left" -> stageLeft();
            case "viewer" -> {
                if (viewer == null) {
                    throw new Ast.ScriptError(n.line(), "viewer 只能在可见性条件里用（它表示\"这份快照给谁\"）");
                }
                yield viewer;
            }
            case "all" -> "all";
            case "others" -> "others";
            default -> throw new Ast.ScriptError(n.line(), "没有这个名字：" + id + "（变量要先 var 声明）");
        };
    }

    /**
     * 下标 {@code a[i]}：列表取第 i 项 · 记录取那个键 —— 一条路径（{@link #indexOf}）。
     *
     */
    private Object readIndex(Ast.Index ix) {
        return indexOf(eval(ix.base()), eval(ix.idx()), ix.line());
    }

    /**
     * 写链上的中间那一段下标：与 {@link #indexOf} 一样，只是**记录里缺的那个键会当场建一个空记录**。
     *
     */
    private Object indexForWrite(Object base, Object idx, int line) {
        if (base instanceof java.util.LinkedHashMap<?, ?>) {
            @SuppressWarnings("unchecked")
            java.util.LinkedHashMap<String, Object> r = (java.util.LinkedHashMap<String, Object>) base;
            String key = Builtins.text(idx);
            if (!r.containsKey(key)) r.put(key, new java.util.LinkedHashMap<String, Object>());
            return r.get(key);
        }
        return indexOf(base, idx, line);
    }

    /**
     * 下标 / 字段取值：列表走数字下标（带越界报错），记录（Map，缺口 #7）走文本键 ——
     * 键可以是任意文本表达式（{@code r["地主"]} / {@code r[key]}），缺键<b>报错带行号</b>不静默给空
     * （静默兜底是本类 bug 头号来源，见 Builtins 的值工具箱注释）。
     */
    private Object indexOf(Object base, Object idx, int line) {
        if (base instanceof java.util.LinkedHashMap<?, ?>) {
            @SuppressWarnings("unchecked")
            java.util.LinkedHashMap<String, Object> r = (java.util.LinkedHashMap<String, Object>) base;
            String key = Builtins.text(idx);
            if (!r.containsKey(key)) {
                throw new Ast.ScriptError(line, "记录里没有这个字段：" + key + "（现在有：" + Builtins.text(new ArrayList<>(r.keySet())) + "）");
            }
            return r.get(key);
        }
        if (base instanceof String s) {                    // 文本下标 = 取第 i 个字符（单字符文本，2026-10-03 钻石大陆）
            int i = Builtins.toInt(idx, line, "下标");
            if (i < 0 || i >= s.length()) {
                throw new Ast.ScriptError(line, "下标越界：" + i + "（这段文本只有 " + s.length() + " 个字符）");
            }
            return String.valueOf(s.charAt(i));
        }
        List<Object> l = Builtins.listOf(base, line, "这个值");
        int i = Builtins.toInt(idx, line, "下标");
        if (i < 0 || i >= l.size()) {
            throw new Ast.ScriptError(line, "下标越界：" + i + "（这张列表只有 " + l.size() + " 项）");
        }
        return l.get(i);
    }

    private Object binary(Ast.Binary b) {
        String op = b.op();
        if (op.equals("&&")) return Builtins.truthy(eval(b.a())) && Builtins.truthy(eval(b.b()));
        if (op.equals("||")) return Builtins.truthy(eval(b.a())) || Builtins.truthy(eval(b.b()));
        Object x = eval(b.a());
        Object y = eval(b.b());
        Double dx = Builtins.numOf(x);
        Double dy = Builtins.numOf(y);
        switch (op) {
            case "==" -> { return bothNum(x, y) ? dx.doubleValue() == dy.doubleValue() : Builtins.text(x).equals(Builtins.text(y)); }
            case "!=" -> { return bothNum(x, y) ? dx.doubleValue() != dy.doubleValue() : !Builtins.text(x).equals(Builtins.text(y)); }
            case "+" -> {
                if (x instanceof String || y instanceof String) return Builtins.text(x) + Builtins.text(y);
                if (dx == null || dy == null) throw new Ast.ScriptError(b.line(), "加不了：" + Builtins.text(x) + " 和 " + Builtins.text(y) + " 不是数字");
                return dx + dy;
            }
            case "-", "*", "/", "%" -> {
                if (dx == null || dy == null) throw new Ast.ScriptError(b.line(), "算不了（" + op + "）：" + Builtins.text(x) + " 和 " + Builtins.text(y) + " 不是数字");
                if ((op.equals("/") || op.equals("%")) && dy == 0) throw new Ast.ScriptError(b.line(), "除数不能是 0");
                return switch (op) {
                    case "-" -> dx - dy;
                    case "*" -> dx * dy;
                    case "/" -> dx / dy;
                    default -> dx % dy;
                };
            }
            case "<", "<=", ">", ">=" -> {
                int c = bothNum(x, y) ? Double.compare(dx, dy) : Builtins.text(x).compareTo(Builtins.text(y));
                return switch (op) {
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            default -> throw new Ast.ScriptError(b.line(), "不认识的运算符：" + op);
        }
    }

    private static boolean bothNum(Object x, Object y) {
        return Builtins.numOf(x) != null && Builtins.numOf(y) != null;
    }

    /** 函数调用：内建先走，其余当自定义 {@code func}（可递归）。 */
    private Object call(Ast.Call c) {
        List<Object> args = new ArrayList<>();
        for (Ast.Expr e : c.args()) args.add(eval(e));
        String fn = c.fn();
        // 引擎原语：clear_board = 请宿主把局内画板清空（新一局/新一轮开始用）。
        if (fn.equals("clear_board")) {
            if (!args.isEmpty()) throw new Ast.ScriptError(c.line(), "clear_board 不要参数");
            boardClear = true;
            sink.stateChanged();
            return "";
        }
        // 引擎原语：may_draw(谁) = 允许谁在局内画板上画（空串 = 谁都不行）。它是「权限」不是数据，
        // 所以不做成变量；脚本每轮换人时调一次就行。
        if (fn.equals("may_draw")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "may_draw 要 1 个参数（谁可以画）");
            drawer = Builtins.text(args.get(0));
            sink.stateChanged();
            return "";
        }
        // 引擎原语：place_piece(棋子id, x, y, z) = 把那枚棋子放到那个坐标（没生成过就 spawn，已有就挪过去）。
        // 一个原语兼管「生成 + 移动」：脚本只管「它现在该在哪」。棋子 id 认档里 pieces 段；模型来自它选的蓝图。
        if (fn.equals("place_piece")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "place_piece 要 4 个参数（棋子id, x, y, z）");
            String pid = Builtins.text(args.get(0));
            if (pid.isBlank()) throw new Ast.ScriptError(c.line(), "place_piece 的棋子 id 是空的");
            placePieces.add(new PlacePiece(pid.trim(), Builtins.numOf(args.get(1)),
                    Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3))));
            return "";
        }
        // 引擎原语：place_card(名, "卡资产名", x, y, z〔, 朝向〔, 尺寸〕〕) ——
        // 一张卡放进世界。与 place_piece 同款：一个原语兼管「生成 + 挪位置」，宿主按名记账、局末收掉。
        // 朝向：数字 = 角度（度，0 = 牌面朝 +Z）；文本 = 玩家/实体名 ⇒ **牌面朝他**（21 点手牌正对自己）。
        // 尺寸：数字（分母，值越小牌越大，1 = 原尺寸）；不写 = 1。
        if (fn.equals("place_card")) {
            if (args.size() < 5 || args.size() > 7) {
                throw new Ast.ScriptError(c.line(),
                        "place_card 要 5~7 个参数（名字, 卡资产名, x, y, z〔, 朝谁/角度〔, 尺寸〕〕）");
            }
            String cname = Builtins.text(args.get(0));
            String cart = Builtins.text(args.get(1));
            if (cname.isBlank()) throw new Ast.ScriptError(c.line(), "place_card 的名字是空的（宿主按它记账，如 \"c1\"）");
            if (cart.isBlank()) throw new Ast.ScriptError(c.line(), "place_card 的卡资产名是空的");
            String faceWho = "";
            double yaw = 0;
            boolean yawGiven = false;
            if (args.size() >= 6) {
                Object v6 = args.get(5);                  // 原语收到的是**已求值**的实参
                if (v6 instanceof Double d6) {
                    yaw = d6;
                    yawGiven = true;
                } else {
                    faceWho = Builtins.text(args.get(5));
                }
            }
            double cscale = args.size() >= 7 ? Builtins.numOf(args.get(6)) : 1.0;
            placeCards.add(new PlaceCard(cname.trim(), cart.trim(),
                    Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)), Builtins.numOf(args.get(4)),
                    faceWho, yaw, yawGiven, cscale));
            return "";
        }
        // 引擎原语：flip_card(名) —— 把那张牌翻个面（yRot + 180；21 点翻开庄家的暗牌）。
        if (fn.equals("flip_card")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "flip_card 要 1 个参数（牌的名字）");
            String cname = Builtins.text(args.get(0));
            if (cname.isBlank()) throw new Ast.ScriptError(c.line(), "flip_card 的名字是空的");
            flipCards.add(new FlipCard(cname.trim()));
            return "";
        }
        // 引擎原语 spawn_mob(实体名, x, y, z)：请宿主把那**只**原版生物放到那儿（一个原语兼管「生成 + 挪位置」，宿主按名字记账）。
        // 实体名 = 档里 assets 段 kind = 自定义实体 那条的名字（脚本写 @游戏名/资产名；对象页【放到这儿】替你写这行）。
        // ⚠ 立刻生效 —— 攒批时「放出来 → 紧接着写它的 NBT」会当场找不到这只实体。
        if (fn.equals("spawn_mob")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "spawn_mob 要 4 个参数（实体名, x, y, z）");
            String mid = Builtins.text(args.get(0));
            if (mid.isBlank()) throw new Ast.ScriptError(c.line(), "spawn_mob 的实体名是空的");
            if (entityOps != null) {
                entityOps.spawnMob(mid.trim(), Builtins.numOf(args.get(1)),
                        Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
            }
            return "";
        }
        // 引擎原语：say_mark(谁, "文本资产名") = 把那条**文本对象**发给谁（谁 = 席位名 / all）。
        // 玩家**点它**就回投一次 on pick（内建值 pick = 那条文本的 mark）—— 文本对象。
        // 这一层只带名字出去：正文是声明里的 body、点击标记是声明里的 mark —— 查项目档是宿主的事
        // （Interp 零 MC 依赖，口径同 spawn_mob）。点击挂的是内建命令 tablegame pick，回投走现成的 acceptPick。
        if (fn.equals("say_mark")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "say_mark 要 2 个参数（谁, 文本资产名）");
            String mid = Builtins.text(args.get(0));
            String tid = Builtins.text(args.get(1));
            if (tid.isBlank()) throw new Ast.ScriptError(c.line(), "say_mark 的文本资产名是空的");
            sayMarks.add(new SayMark(mid.trim(), tid.trim()));
            return "";
        }
        // 引擎原语：label(x, y, z, "文字") = 请宿主在那格立一块牌子写这段字（世界层 W3）。
        // 语义就是「立牌」：那格已经有牌子 → 改文本（同一格重复写 = 幂等，不堆东西）。
        // 想标在某一格上方就自己写 y + 1（别把地砖覆盖成牌子）。
        if (fn.equals("label")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "label 要 4 个参数（x, y, z, 文字）");
            String lt = Builtins.text(args.get(3));
            if (lt.isBlank()) throw new Ast.ScriptError(c.line(), "label 的文字是空的");
            labels.add(new Label(Builtins.numOf(args.get(0)), Builtins.numOf(args.get(1)),
                    Builtins.numOf(args.get(2)), lt));
            return "";
        }
        // 引擎原语：set_block(x, y, z, "方块id") = 请宿主把那格方块换成这个（世界层 W1 第一刀）。
        // 同 clear_board 的「置位 + 取走」但**攒批**：宿主每 tick 取走整批执行，脚本一次循环铺一片不用来回。
        // ⚠ 只验参数形态（个数 / 空 id）：id 认不认得出、那格加载没加载是服务器的事，执行时由宿主判。
        if (fn.equals("set_block")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "set_block 要 4 个参数（x, y, z, 方块id）");
            String bid = Builtins.text(args.get(3));
            if (bid.isBlank()) throw new Ast.ScriptError(c.line(), "set_block 的方块 id 是空的");
            setBlocks.add(new SetBlock(Builtins.numOf(args.get(0)), Builtins.numOf(args.get(1)),
                    Builtins.numOf(args.get(2)), bid.trim()));
            return "";
        }
        // 引擎原语：fill(x1, y1, z1, x2, y2, z2, "方块id") = 请宿主把这块区域都换成这个方块
        // （抄原版 /fill 的用处：铺棋盘一条指令，而不是 while 里写几百次 set_block）。
        // 只记区域，不展开成一条条 —— 体积上限由宿主判（MAX_FILL，抄原版 max_block_modifications）。
        if (fn.equals("fill")) {
            if (args.size() != 7) throw new Ast.ScriptError(c.line(), "fill 要 7 个参数（x1, y1, z1, x2, y2, z2, 方块id）");
            String fid = Builtins.text(args.get(6));
            if (fid.isBlank()) throw new Ast.ScriptError(c.line(), "fill 的方块 id 是空的");
            fills.add(new Fill(Builtins.numOf(args.get(0)), Builtins.numOf(args.get(1)), Builtins.numOf(args.get(2)),
                    Builtins.numOf(args.get(3)), Builtins.numOf(args.get(4)), Builtins.numOf(args.get(5)), fid.trim()));
            return "";
        }
        // 引擎原语：protect(x1,y1,z1, x2,y2,z2) / unprotect(同参) = 保护 / 解除保护一片区域
        // 被保护的格子玩家挖不掉（破坏被取消、不掉落、方块保持原样）。
        // 攒批走宿主（同 fill）：真实消费方是宿主的破坏事件监听，解释器够不着；
        // 「这个格子保不保护」由宿主按区域表判 —— 脚本只说「哪片要保护」。
        if (fn.equals("protect") || fn.equals("unprotect")) {
            boolean on = fn.equals("protect");
            // 三级保护：
            //  1 = 只拦**生存**（冒险不额外拦 —— 交给原版：它自己挡、带 canDestroy 组件的工具能破例；创造不拦）
            //  2（缺省）= 拦**生存 + 冒险**（都挡死，白名单也不给；创造不拦）
            //  3 = **连创造也挡**
            // 缺省取 2：手写 `protect(盒)` 时「保护」的直觉含义就是「非创造别动」（也等于老档升级后的行为）。
            // `unprotect` 不需要级别（同一片就摘，见宿主 applyProtects）。
            if (args.size() != 6 && !(on && args.size() == 7)) {
                throw new Ast.ScriptError(c.line(), fn + (on
                        ? " 要 6 个参数（x1, y1, z1, x2, y2, z2），或者 7 个（末尾加级别 1|2|3）"
                        : " 要 6 个参数（x1, y1, z1, x2, y2, z2）"));
            }
            int lv = 2;                                   // 缺省二级（= 非创造都别动；也等于老档升级后的行为）
            if (args.size() == 7) {
                lv = (int) Math.round(Builtins.numOf(args.get(6)));
                if (lv < 1 || lv > 3) {
                    throw new Ast.ScriptError(c.line(), "保护级别只能是 1 / 2 / 3（1 = 只拦生存，"
                            + "2 = 拦生存+冒险，3 = 连创造也挡），这里写了 " + lv);
                }
            }
            protects.add(new Protect(Builtins.numOf(args.get(0)), Builtins.numOf(args.get(1)),
                    Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)),
                    Builtins.numOf(args.get(4)), Builtins.numOf(args.get(5)), on, lv));
            return "";
        }
        // 读世界的函数：block_at(x, y, z) = 那格是什么方块（注册名文本；不知道 → 空串）。
        // 这是**读**，不是原语（原语是「置位 + 取走」的异步请求，读要当场拿返回值）——
        // 所以走宿主注入的回调，INTERP 本身不知道 Level 是什么。
        if (fn.equals("block_at")) {
            if (args.size() != 3) throw new Ast.ScriptError(c.line(), "block_at 要 3 个参数（x, y, z）");
            if (worldReader == null) return "";
            return worldReader.blockAt((int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))));
        }
        // 读世界的函数：chest_items(x, y, z) = 那一格**容器里**有什么；bag_items(谁) = 那个人的背包里有什么。
        // 两者**同一套语义**（只列非空槽），只是「容器从哪来」不同（坐标 vs 名字）⇒ 一条分支搞定。
        // 与 block_at 同族：这是**读**，要当场拿返回值 ⇒ 走宿主注入；没注入（自检 / 老宿主）→ 空列表，不报错。
        // 值形状 = 列表，每项一个记录 rec("slot", 槽, "item", 物品id, "count", 数量) ——
        // 形状只在**这一处**定义（宿主交上来的是原始清单 Interp.SlotItem，见 WorldContainers）。
        if (fn.equals("chest_items") || fn.equals("bag_items")) {
            boolean byPos = fn.equals("chest_items");
            if (args.size() != (byPos ? 3 : 1)) {
                throw new Ast.ScriptError(c.line(),
                        fn + (byPos ? " 要 3 个参数（x, y, z）" : " 要 1 个参数（谁的背包）"));
            }
            List<Object> out = new ArrayList<>();
            if (worldContainers == null) return out;
            List<SlotItem> raw = byPos
                    ? worldContainers.itemsAt((int) Math.floor(Builtins.numOf(args.get(0))),
                            (int) Math.floor(Builtins.numOf(args.get(1))),
                            (int) Math.floor(Builtins.numOf(args.get(2))))
                    : worldContainers.itemsIn(Builtins.text(args.get(0)));
            for (SlotItem it : raw) {
                LinkedHashMap<String, Object> rec = new LinkedHashMap<>();
                rec.put("slot", (double) it.slot());         // 脚本的数只有 Double：别放 Integer（数字只有一种类型）
                rec.put("item", it.item());
                rec.put("count", (double) it.count());
                out.add(rec);
            }
            return out;
        }
        // 引擎原语：chest_set(x, y, z, 槽, "物品id", 数量) / bag_set(谁, 槽, "物品id", 数量)
        // = 把那一槽**设成**「N 个某物品」——**覆盖式**（数量 ≤ 0 或 id 空串 = 清空该槽），幂等：
        // 「让它现在就是这样」与 set_block / place_piece 同一套哲学；取走/累加由脚本读-算-写自己拼。
        // **立刻生效**（不走「置位 + takeXxx 攒批」）：脚本常「先摆好再核对」，攒批会让紧接着的读拿到旧内容
        //（攒批就会「先摆好再核对」看不到 —— 一条教训）。认不出 id / 槽越界 / 人不在 → 宿主记日志跳过，不掐局。
        if (fn.equals("chest_set") || fn.equals("bag_set")) {
            boolean byPos = fn.equals("chest_set");
            if (args.size() != (byPos ? 6 : 4)) {
                throw new Ast.ScriptError(c.line(), fn + (byPos
                        ? " 要 6 个参数（x, y, z, 槽, 物品id, 数量）"
                        : " 要 4 个参数（谁, 槽, 物品id, 数量）"));
            }
            if (worldContainers == null) return "";          // 没注入：什么都不发生（自检 / 老宿主）
            int at = byPos ? 3 : 1;
            int slot = Builtins.toInt(args.get(at), c.line(), fn + " 的槽");
            String item = Builtins.text(args.get(at + 1)).trim();
            int count = Builtins.toInt(args.get(at + 2), c.line(), fn + " 的数量");
            if (byPos) {
                worldContainers.setAt((int) Math.floor(Builtins.numOf(args.get(0))),
                        (int) Math.floor(Builtins.numOf(args.get(1))),
                        (int) Math.floor(Builtins.numOf(args.get(2))), slot, item, count);
            } else {
                worldContainers.setIn(Builtins.text(args.get(0)), slot, item, count);
            }
            return "";
        }
        // 引擎原语 give(谁, "物品id", 数量) = **发**（并堆 → 找空位 → 塞不完掉脚下），立刻生效，≤ 0 什么也不发。
        // 引擎原语 enter(谁) / leave(谁) = **点名**进局 / 退局：不看空间；on join / on leave 照旧由宿主按边沿发一次。
        if (fn.equals("enter") || fn.equals("leave")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), fn + " 要 1 个参数（谁）");
            String who = Builtins.text(args.get(0)).trim();
            if (!who.isEmpty() && members != null) {          // 没注入（自检 / 老宿主）：什么都不发生
                if (fn.equals("enter")) members.enterGame(who); else members.leaveGame(who);
            }
            return "";
        }
        if (fn.equals("give")) {
            if (args.size() != 3) throw new Ast.ScriptError(c.line(), "give 要 3 个参数（谁, 物品id, 数量）");
            if (worldContainers != null) {                     // 没注入（自检 / 老宿主）：什么都不发生，不报错
                worldContainers.give(Builtins.text(args.get(0)), Builtins.text(args.get(1)).trim(),
                        Builtins.toInt(args.get(2), c.line(), "give 的数量"));
            }
            return "";
        }

        // 引擎原语：count_blocks(x1, y1, z1, x2, y2, z2, "方块id") = 那个盒里有多少个这种方块。
        // 与 block_at 同族（读世界，要当场拿返回值 ⇒ 走宿主注入）；宿主一趟数完，脚本不必写三重循环
        //（17×9×17 逐格 block_at ≈ 9800 步，撞 2000 的预算；7×7×3 = 556 步，本身不撞）。
        // 没注入（自检 / 老宿主）→ 0，不报错（同 block_at 口径）；体量上限在宿主侧。
        if (fn.equals("count_blocks")) {
            if (args.size() != 7) {
                throw new Ast.ScriptError(c.line(),
                        "count_blocks 要 7 个参数（x1, y1, z1, x2, y2, z2, 方块id）");
            }
            if (worldContainers == null) return 0.0;
            return worldContainers.countBlocks(
                    (int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))),
                    (int) Math.floor(Builtins.numOf(args.get(3))),
                    (int) Math.floor(Builtins.numOf(args.get(4))),
                    (int) Math.floor(Builtins.numOf(args.get(5))),
                    Builtins.text(args.get(6)).trim());
        }
        // 引擎原语：chest_give(x, y, z, "物品id", 数量) = 往那一格的容器**塞**。
        // 与 chest_set 的「设成」不同：这条是「加」——先并进同款堆、再找空槽，塞不下就留在手上；
        // 返回**实际塞进去几件**（0 = 熔炉停工，不溢出、不掉地上）。立刻生效（同写容器那一族）。
        if (fn.equals("chest_give")) {
            if (args.size() != 5) {
                throw new Ast.ScriptError(c.line(), "chest_give 要 5 个参数（x, y, z, 物品id, 数量）");
            }
            if (worldContainers == null) return 0.0;
            return (double) worldContainers.chestGive(
                    (int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))),
                    Builtins.text(args.get(3)).trim(),
                    Builtins.toInt(args.get(4), c.line(), "chest_give 的数量"));
        }

        // 引擎原语：bag_take(谁, "物品id", 数量) = 从背包**取走**。
        // **全有或全无**：不够 ⇒ 一件不动、返回 0；够才逐槽扣（余量留槽、跨槽继续）—— 一次事务。
        // 认物品的口径 = bag_items 报的那个（基底 id）⇒ 读得到的就取得到。
        if (fn.equals("bag_take")) {
            if (args.size() != 3) throw new Ast.ScriptError(c.line(), "bag_take 要 3 个参数（谁, 物品id, 数量）");
            if (worldContainers == null) return 0.0;      // 没注入：什么都不发生（自检 / 老宿主）
            return (double) worldContainers.bagTake(Builtins.text(args.get(0)),
                    Builtins.text(args.get(1)).trim(),
                    Builtins.toInt(args.get(2), c.line(), "bag_take 的数量"));
        }
        // 引擎原语：按**项目资产名**数 / 取背包。
        //  bag_count_asset(谁, "资产名") → 件数；bag_take_asset(谁, "资产名", 数量) → 实取（全有或全无）。
        // 为什么：bag_items / bag_take 只认**基底 id** —— 五阶混沌金锭同用金锭基底、靠 tg_asset 标签分，
        // 那边五条读出来全一样。这两个口子认标签：同基底的几条声明物品各归各（认不出的资产 → 0，不掐局）。
        if (fn.equals("bag_count_asset") || fn.equals("bag_take_asset")) {
            int want = fn.equals("bag_count_asset") ? 2 : 3;
            if (args.size() != want) {
                throw new Ast.ScriptError(c.line(), fn + " 要 " + want + " 个参数（谁, 资产名"
                        + (want == 3 ? ", 数量" : "") + "）");
            }
            if (worldContainers == null) return fn.equals("bag_count_asset") ? 0.0 : 0.0;
            String who = Builtins.text(args.get(0));
            String asset = Builtins.text(args.get(1)).trim();
            if (fn.equals("bag_count_asset")) return worldContainers.bagCountAsset(who, asset);
            return (double) worldContainers.bagTakeAsset(who, asset,
                    Builtins.toInt(args.get(2), c.line(), "bag_take_asset 的数量"));
        }
        // 引擎原语：bag_save(谁, 键) / bag_restore(谁, 键) = 整份背包**寄存 / 发回**。
        // 值存进现成的玩家档案（ProfileStore）—— 零存储改动；内容是原版 NBT 序列化后的 SNBT 文本。
        // 同一个键连存两次 = 覆盖：脚本自己用标记键挡「把游戏道具当原背包存下」（见校对清单）。
        if (fn.equals("bag_save") || fn.equals("bag_restore")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), fn + " 要 2 个参数（谁, 键）");
            if (worldContainers != null) {                 // 没注入：什么都不发生
                if (fn.equals("bag_save")) {
                    worldContainers.bagSave(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
                } else {
                    worldContainers.bagRestore(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
                }
            }
            return "";
        }

        // 引擎原语：shape_cell("区域名", "角色", x, y, z) = 那条快照里**标了这个角色的那格**
        // 在世界里的坐标（x,y,z = 锚点角 = 区域最小角）—— 返回记录 {x, y, z}，与 pos_of 同形（成员 = 字符串下标）。
        // 锚点 / 核心槽 / 产出箱都是**作者在区域视口里标的**语义格（存进快照的 cells）。
        // 认不出区域 / 没标过这个角色 / 没注入 → 坐标全 0，不抛错（读宽容：给不出就当没有，日志说一声）。
        if (fn.equals("shape_cell")) {
            if (args.size() != 5) throw new Ast.ScriptError(c.line(),
                    "shape_cell 要 5 个参数（区域名, 角色, x, y, z）");
            String scArea = Builtins.text(args.get(0)).trim();
            String scRole = Builtins.text(args.get(1)).trim();
            int scx = (int) Math.floor(Builtins.numOf(args.get(2)));
            int scy = (int) Math.floor(Builtins.numOf(args.get(3)));
            int scz = (int) Math.floor(Builtins.numOf(args.get(4)));
            double[] wc = shapes == null ? null : shapes.cellAt(scArea, scRole, scx, scy, scz);
            if (wc == null) {
                log("shape_cell 取不到：\"" + scArea + "\" 里没有\"" + scRole + "\"这个标记（或没这条区域）");
            }
            var pos = new java.util.LinkedHashMap<String, Object>();
            pos.put("x", wc == null ? 0.0 : wc[0]);
            pos.put("y", wc == null ? 0.0 : wc[1]);
            pos.put("z", wc == null ? 0.0 : wc[2]);
            return pos;
        }

        // 引擎原语：match_shape("区域名", x, y, z) = 那份快照的图案**在这儿成形了吗**，
        // 1 / 0。x,y,z = 锚点角（区域最小角对齐到这）—— 作者搭一遍 + 世界页框选捕获，那张快照就是图案。
        // 判定在**宿主内部**逐格比（脚本自己循环会撞步数预算）；空气格放松、通配格放过（两条口径见
        // GameDefinition.AreaDef.shapeMatches）。**不做全图搜索**：搭完最后一块走 on place，那格就是锚点，
        // 定点核一次、结果存 var，on break 重验（触发式 · 结果缓存 · 方块变动才失效）。
        if (fn.equals("match_shape")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(),
                    "match_shape 要 4 个参数（区域名, x, y, z）");
            String msArea = Builtins.text(args.get(0)).trim();
            int mx = (int) Math.floor(Builtins.numOf(args.get(1)));
            int my = (int) Math.floor(Builtins.numOf(args.get(2)));
            int mz = (int) Math.floor(Builtins.numOf(args.get(3)));
            return shapes != null && shapes.matchShape(msArea, mx, my, mz)
                    ? Boolean.TRUE : Boolean.FALSE;
        }

        // 引擎原语：scatter(x1,y1,z1, x2,y2,z2, "表名", 数量[, "只替换 stone,deepslate"])
        // = 在盒里随机挑 N 个合格格（不重复），每格抽一次那张**原版战利品表**、把抽到的第一件物品当方块摆下去，
        // 返回实际撒了几格。为什么要下沉：脚本自己 for + random + set_block ① 撞每事件步数预算（10³ 候选区 = 1000 格），
        // ② 逐格方块更新太密。表名 = 项目档 loot/<表名>.json（可视化编辑器编的权重，不是自建的第二套掉落）。
        // 可替换清单省略 ⇒ 宿主按「只替换石头 / 深板岩」这条默认走。
        if (fn.equals("scatter")) {
            if (args.size() != 8 && args.size() != 9) {
                throw new Ast.ScriptError(c.line(),
                        "scatter 要 8 或 9 个参数（x1, y1, z1, x2, y2, z2, 表名, 数量[, 只替换清单]）");
            }
            String scTable = Builtins.text(args.get(6)).trim();
            if (scTable.isEmpty()) throw new Ast.ScriptError(c.line(), "scatter 的表名是空的");
            if (scatter == null) return 0.0;               // 没注入（自检 / 老宿主）：什么都不发生，不报错
            return (double) scatter.scatter(
                    (int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))),
                    (int) Math.floor(Builtins.numOf(args.get(3))),
                    (int) Math.floor(Builtins.numOf(args.get(4))),
                    (int) Math.floor(Builtins.numOf(args.get(5))),
                    scTable,
                    Builtins.toInt(args.get(7), c.line(), "scatter 的数量"),
                    args.size() == 9 ? Builtins.text(args.get(8)).trim() : "");
        }

        // 引擎原语：ghost(谁, "区域名", x, y, z) = 让那个人看见这份区域快照的**幽灵预览**，
        // 钉在 (x, y, z)（= 区域最小角）；同参数重调 = 挪一下落点（宿主去重，视线没换格就不发包）。
        // ghost_off(谁) = 收回。**什么时候开 / 关归脚本**（on look 跟随 / on world rclick 钉住 / on use 收回）——
        // 玩家侧不发任何东西上来，引擎只管「画出来」。与 say_mark 同款：攒批 + 宿主取走发包。
        if (fn.equals("ghost")) {
            if (args.size() != 5) {
                throw new Ast.ScriptError(c.line(), "ghost 要 5 个参数（谁, 区域名, x, y, z）");
            }
            String gArea = Builtins.text(args.get(1)).trim();
            if (gArea.isEmpty()) throw new Ast.ScriptError(c.line(), "ghost 的区域名是空的");
            ghosts.add(new Ghost(Builtins.text(args.get(0)), gArea,
                    (int) Math.floor(Builtins.numOf(args.get(2))),
                    (int) Math.floor(Builtins.numOf(args.get(3))),
                    (int) Math.floor(Builtins.numOf(args.get(4))), true));
            return "";
        }
        if (fn.equals("ghost_off")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "ghost_off 要 1 个参数（谁）");
            ghosts.add(new Ghost(Builtins.text(args.get(0)), "", 0, 0, 0, false));
            return "";
        }

        // 引擎原语：gamemode(谁, "adventure") = 把那个玩家的游戏模式改成这个。
        // **立刻生效**（走宿主注入，不攒批）：脚本常「改成冒险再让他动」，攒批会让同一事件里的
        // 后续判定拿到旧模式。模式名认原版那四个；恢复旧模式是脚本的事（自己存旧值、局末改回来）——
        // 引擎只管动词，语义（什么时候改回来）归脚本。
        if (fn.equals("gamemode")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "gamemode 要 2 个参数（谁, 模式名）");
            if (entityOps != null) {
                entityOps.gameMode(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
            }
            return "";
        }
        // 引擎原语 tp：三参 = 同世界传（老形态）· 五参 tp(谁, "世界id 或 区域名", x, y, z)：
        //   区域名 → 相对区域最小角的偏移（区域搬走脚本不用改）· 维度 id → 那个世界的绝对坐标。
        // 立刻生效；没注入不动；两位都认不出 → 只记一行、什么都不传（不掐局）。
        if (fn.equals("tp")) {
            if (args.size() != 4 && args.size() != 5) {
                throw new Ast.ScriptError(c.line(),
                        "tp 要 4 或 5 个参数（谁, x, y, z ／ 谁, \"世界或区域名\", x, y, z）");
            }
            int at = args.size() - 3;                             // 4 参 = 0（谁是第 1 位）· 5 参 = 1
            String who = Builtins.text(args.get(0));
            if (args.size() == 4) {
                if (entityOps != null) {
                    entityOps.tp(who, Builtins.numOf(args.get(1)),
                            Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
                }
                return "";
            }
            String target = Builtins.text(args.get(1)).trim();
            double x = Builtins.numOf(args.get(at)), y = Builtins.numOf(args.get(at + 1)),
                    z = Builtins.numOf(args.get(at + 2));
            Region r = regionNamed(target);
            if (r != null) {
                if (entityOps != null) {
                    entityOps.tpTo(who, r.dim(), r.minX() + x, r.minY() + y, r.minZ() + z);
                }
                return "";
            }
            if (target.contains(":")) {                            // 有命名空间 → 当维度 id（认不认得出是宿主的事）
                if (entityOps != null) entityOps.tpTo(who, target, x, y, z);
                return "";
            }
            log("tp 的第二位认不出：\"" + target + "\" 既不是声明过的区域，也不是维度 id（要世界请写 minecraft:xxx）");
            return "";
        }
        // 读：in_area("区域名", 谁) = 他在不在这条区域里 —— 真 / 假。
        // 为什么要它：区域一具名，脚本就不该再抄一遍那六个数字（抄了 = 第二份真源，区域一挪就漂）。
        // 认不出区域名 / 人不在 / 没注入 → **假**（读的口径：认得的值给不出就回「没有」，不掐局）。
        if (fn.equals("in_area")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "in_area 要 2 个参数（区域名, 谁）");
            String rn = Builtins.text(args.get(0));
            Region r = regionNamed(rn);
            if (r == null) {
                log("in_area 找不到区域：\"" + rn + "\"（顶层 area 声明里没有这个名字）");
                return Boolean.FALSE;
            }
            if (entityOps == null) return Boolean.FALSE;
            LivingPos at = entityOps.whereIs(Builtins.text(args.get(1)));
            if (at == null) return Boolean.FALSE;
            return r.holds(at.dim(), at.x(), at.y(), at.z());
        }
        // ===== 动活物：玩家与实体共用一套动词（「谁」= 玩家名 或 账上实体名）=====
        // 全部**注入 + 立刻生效**（理由同 gamemode / tp：脚本常「回了血紧接着看血量」）；认不出 → 宿主记一行跳过，不掐局。
        // 生命三件：health = 设成（原版自带 0~上限夹取，要抬上限先 attribute）· heal = 回血 · damage = 扣血（走原版伤害管线，护甲 / 无敌照算）。
        if (fn.equals("health")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "health 要 2 个参数（谁, 生命值）");
            if (entityOps != null) entityOps.health(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)));
            return "";
        }
        if (fn.equals("heal")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "heal 要 2 个参数（谁, 回多少）");
            if (entityOps != null) entityOps.heal(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)));
            return "";
        }
        // sound(谁, "音效id"[, 音量, 音高]) —— 放一段音效**只给那个人**听。
        // 音量 / 音高可省（省 = 1.0 / 1.0）；id **不查注册表**（照原版 /playsound：认不出 = 客户端静音，
        // 不报错、不掐局）；位置取他自己那儿。
        if (fn.equals("sound")) {
            if (args.size() != 2 && args.size() != 4) {
                throw new Ast.ScriptError(c.line(), "sound 要 2 或 4 个参数（谁, \"音效id\"[, 音量, 音高]）");
            }
            if (entityOps != null) {
                // 音量 / 音高：填了就用、没填（2 参写法）给 1.0；转不成数（numOf 回 null）也退回 1.0，不掐局
                Double vol = args.size() == 4 ? Builtins.numOf(args.get(2)) : null;
                Double pit = args.size() == 4 ? Builtins.numOf(args.get(3)) : null;
                entityOps.sound(Builtins.text(args.get(0)), Builtins.text(args.get(1)),
                        vol == null ? 1.0 : vol, pit == null ? 1.0 : pit);
            }
            return "";
        }
        if (fn.equals("damage")) {
            if (args.size() != 2 && args.size() != 3) {
                throw new Ast.ScriptError(c.line(), "damage 要 2 或 3 个参数（谁, 扣多少[, \"伤害类型\"]）");
            }
            if (entityOps != null) {
                entityOps.damage(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)),
                        args.size() == 3 ? Builtins.text(args.get(2)) : "");
            }
            return "";
        }
        // 引擎原语：dialog(谁, "框名") = 把那**一个**对话框弹给那个人。
        // 走**原版那条路**（宿主 Player.openDialog → 原版自己发包，玩家侧界面是原版现成的 ⇒ 客户端零改动）。
        // 玩家自己关（能不能 Esc 关、按钮干什么、输入框回传哪条命令 —— 全是那份数据自己写的，引擎不掺和）。
        // 框名 = 项目档 dialog/<名>.json（同 damage 第三参口径：认不出 → 宿主记一行，不掐局）。
        if (fn.equals("dialog")) {
            if (args.size() != 2) {
                throw new Ast.ScriptError(c.line(), "dialog 要 2 个参数（谁, \"框名\"）");
            }
            if (dialogs != null) dialogs.openDialog(Builtins.text(args.get(0)), Builtins.text(args.get(1)).trim());
            return "";
        }
        // 引擎原语：particle(x, y, z, "粒子"[, 数量]) = 在那一点发几颗粒子。
        // 粒子文本就是原版那套（纯 id 或带参数的完整 JSON）；只有宿主发得出去（要 ServerLevel）。纯反馈，不动状态。
        if (fn.equals("particle")) {
            if (args.size() != 4 && args.size() != 5) {
                throw new Ast.ScriptError(c.line(), "particle 要 4 或 5 个参数（x, y, z, \"粒子\"[, 数量]）");
            }
            String pSpec = Builtins.text(args.get(3)).trim();
            if (pSpec.isEmpty()) throw new Ast.ScriptError(c.line(), "particle 的粒子名是空的");
            if (particles == null) return 0.0;          // 没注入（自检 / 老宿主）：什么都不发生，不报错
            return (double) particles.particle(Builtins.numOf(args.get(0)), Builtins.numOf(args.get(1)),
                    Builtins.numOf(args.get(2)), pSpec,
                    args.size() == 5 ? Builtins.toInt(args.get(4), c.line(), "particle 的数量") : 1);
        }
        // 引擎原语：block_data_keys(x, y, z) = 列出这格方块实体上**所有键名**（逗号分隔、已排序）。
        // 为什么补它：键名是**版本相关**的 —— 26.x 熔炉的 CookTime / BurnTime 已经改叫 cooking_time_spent /
        // lit_time_remaining，抄老教程的键名会「读 0、写不进」 ⇒ 先 keys 看一眼再 get / set。
        if (fn.equals("block_data_keys")) {
            if (args.size() != 3) {
                throw new Ast.ScriptError(c.line(), "block_data_keys 要 3 个参数（x, y, z）");
            }
            if (blockData == null) return "";
            return blockData.keys((int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))));
        }
        // 引擎原语：block_data_get(x, y, z, "键") / block_data_set(x, y, z, "键", 值)
        // —— 方块实体上那些**标量键**（熔炉 BurnTime / CookTime 这类）。读不到 → 0；写只认它自己已有的键。
        if (fn.equals("block_data_get")) {
            if (args.size() != 4) {
                throw new Ast.ScriptError(c.line(), "block_data_get 要 4 个参数（x, y, z, \"键\"）");
            }
            if (blockData == null) return 0.0;
            return blockData.get((int) Math.floor(Builtins.numOf(args.get(0))),
                    (int) Math.floor(Builtins.numOf(args.get(1))),
                    (int) Math.floor(Builtins.numOf(args.get(2))), Builtins.text(args.get(3)));
        }
        if (fn.equals("block_data_set")) {
            if (args.size() != 5) {
                throw new Ast.ScriptError(c.line(), "block_data_set 要 5 个参数（x, y, z, \"键\", 值）");
            }
            if (blockData != null) {
                blockData.set((int) Math.floor(Builtins.numOf(args.get(0))),
                        (int) Math.floor(Builtins.numOf(args.get(1))),
                        (int) Math.floor(Builtins.numOf(args.get(2))),
                        Builtins.text(args.get(3)), Builtins.numOf(args.get(4)));
            }
            return "";
        }
        // 读生命两条（**读**：要当场拿返回值，同 block_at 走注入）—— 血量条 / 「血量过半才怎样」都靠它。
        // 认不出的人 / 实体 → 0（读数值没有「空串」可言，0 最接近；宿主同时记一行日志）。
        if (fn.equals("health_of")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "health_of 要 1 个参数（谁）");
            return entityOps == null ? 0.0 : entityOps.healthOf(Builtins.text(args.get(0)));
        }
        if (fn.equals("max_health_of")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "max_health_of 要 1 个参数（谁）");
            return entityOps == null ? 0.0 : entityOps.maxHealthOf(Builtins.text(args.get(0)));
        }
        //  三条读。形状在**解释器**这边定（宿主只交原始数），三条各有各的「空值」口径：
        //  pos_of → 记录 {dimension, x, y, z}：`pos_of(actor).x`（成员就是字符串下标，同 r.field 一条路）
        //  attribute_of → 数（读**基础值**，与 attribute 写入的那一项对称；带了修饰符的「现值」看 health_of 那套）
        //  effects_of → 列表（效果 id）：配 len / [i] / while 就能自己扫（「有没有某个 buff」由脚本拼）
        // 认不出的人 / 实体 → 坐标全 0、世界空串 / 属性 0 / 空表（同 health_of 的「认不出 → 0」口径，**不抛错**）：
        // 读宽容、写严格 —— 舞台与判定不该因为一个名字不认识就整块炸掉。
        if (fn.equals("mode_of")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "mode_of 要 1 个参数（谁）");
            return entityOps == null ? "" : entityOps.gameModeOf(Builtins.text(args.get(0)));
        }
        if (fn.equals("pos_of")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "pos_of 要 1 个参数（谁）");
            var p = entityOps == null ? null : entityOps.whereIs(Builtins.text(args.get(0)));
            var rec = new java.util.LinkedHashMap<String, Object>();
            // ⚠ 字段叫 dimension 不叫 dim / world：后两个都是保留字（`dim` 声明 / `on world`），
            //  当字段名就写不出 `.dim`（点后面必须是标识符）—— 与区域声明那个字段同一个口径。
            rec.put("dimension", p == null ? "" : p.dim());
            rec.put("x", p == null ? 0.0 : p.x());
            rec.put("y", p == null ? 0.0 : p.y());
            rec.put("z", p == null ? 0.0 : p.z());
            return rec;
        }
        if (fn.equals("attribute_of")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "attribute_of 要 2 个参数（谁, 属性id）");
            return entityOps == null ? 0.0
                    : entityOps.attributeOf(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
        }
        if (fn.equals("effects_of")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "effects_of 要 1 个参数（谁）");
            // 拷一份：脚本 push 自己的清单时不该动到宿主那份（列表是可变值）
            return new java.util.ArrayList<>(entityOps == null ? java.util.List.<String>of()
                    : entityOps.effectsOf(Builtins.text(args.get(0))));
        }
        // **写任意 NBT** —— entity_data(谁, "{Tags:[\"boss\"],CustomName:'…'}")
        // 第二格是**原版 SNBT 一段**（不是我们的语法，里面有引号/花括号很正常，所以必须用字符串写）。
        // 用法：生物 Tags / 自定义名 / 装备 / 村民 Offers（= 逐只必出的自定义交易）；
        // 走原版 `/data merge entity` 同一条路（并进去，不是整份换掉）。
        // 写上了 → 1；目标找不到 / SNBT 不合法 → 0（宿主记一行，不掐局 —— 同「写」那一族的宽容口径）。
        if (fn.equals("entity_data")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "entity_data 要 2 个参数（谁, NBT 文本）");
            String nbt = Builtins.text(args.get(1)).trim();
            if (nbt.isEmpty()) throw new Ast.ScriptError(c.line(), "entity_data 的 NBT 文本是空的");
            return entityOps == null ? 0.0 : entityOps.entityData(Builtins.text(args.get(0)), nbt);
        }
        // **替玩家开某个活物的原版交易界面** —— open_trade(谁, eid)。
        // `谁` = 玩家名（界面开给他）· `eid` = 活物（与 on entity 的 eid 同一个口径：脚本名）。
        // 走原版 Merchant#openTradingScreen（开屏 + 发交易清单）—— 不新造交易系统。
        // 开上了 → 1；认不出 / 那只不是商人 → 0（宿主记一行，不掐局 —— 同「写」那一族的宽容口径）。
        if (fn.equals("open_trade")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "open_trade 要 2 个参数（谁, 活物）");
            return entityOps == null ? 0.0 : entityOps.openTrade(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
        }
        // buff：effect(谁, "minecraft:speed", 秒, 等级) = 给他这个效果（等级 0 = I 级，同原版 /effect）。
        // 秒 ≤ 0 → 原版当「无限时长」；效果 id 认不出 → 宿主记一行跳过（不掐局）。
        // 要「无限」写 effect(actor, "minecraft:speed", 0, 0)；要清干净用 clear_effects(谁)。
        if (fn.equals("effect")) {
            if (args.size() != 4) {
                throw new Ast.ScriptError(c.line(), "effect 要 4 个参数（谁, 效果id, 秒, 等级）");
            }
            String eff = Builtins.text(args.get(1)).trim();
            if (eff.isEmpty()) throw new Ast.ScriptError(c.line(), "effect 的效果 id 是空的");
            if (entityOps != null) {
                entityOps.effect(Builtins.text(args.get(0)), eff,
                        Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
            }
            return "";
        }
        if (fn.equals("clear_effects")) {
            if (args.size() != 1) throw new Ast.ScriptError(c.line(), "clear_effects 要 1 个参数（谁）");
            if (entityOps != null) entityOps.clearEffects(Builtins.text(args.get(0)));
            return "";
        }
        // 属性基础值：attribute(谁, "minecraft:max_health", 40) —— 力量 / 移速 / 血上限这类都走它。
        // ⚠ 设的是**基础值**（原版这套加法乘法的根）；装备 / buff 那层修正照旧另算，脚本别在那儿加数。
        if (fn.equals("attribute")) {
            if (args.size() != 3) throw new Ast.ScriptError(c.line(), "attribute 要 3 个参数（谁, 属性id, 值）");
            String at = Builtins.text(args.get(1)).trim();
            if (at.isEmpty()) throw new Ast.ScriptError(c.line(), "attribute 的属性 id 是空的");
            if (entityOps != null) {
                entityOps.attribute(Builtins.text(args.get(0)), at, Builtins.numOf(args.get(2)));
            }
            return "";
        }
        // 饱食度 / 经验：只对玩家有意义（实体上宿主记一行跳过）。xp 是**加**（原版 /xp add 口径，同 give 的「加」）。
        if (fn.equals("food")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "food 要 2 个参数（谁, 饱食度 0~20）");
            if (entityOps != null) {
                entityOps.food(Builtins.text(args.get(0)), Builtins.toInt(args.get(1), c.line(), "food 的饱食度"));
            }
            return "";
        }
        if (fn.equals("xp")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "xp 要 2 个参数（谁, 加多少经验点）");
            if (entityOps != null) {
                entityOps.xp(Builtins.text(args.get(0)), Builtins.toInt(args.get(1), c.line(), "xp 的点数"));
            }
            return "";
        }
        // 飞行 / 免伤两个开关（0 = 关）：飞行改的是玩家能力（要重新告诉客户端，宿主会调 onUpdateAbilities）·
        // 免伤是原版的 invulnerable 标记（伤害管线会直接放过他 —— 与 protect 那种「取消挖方块」是两件事）。
        if (fn.equals("allow_fly")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "allow_fly 要 2 个参数（谁, 0 或 1）");
            if (entityOps != null) {
                entityOps.fly(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)) != 0);
            }
            return "";
        }
        if (fn.equals("invulnerable")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "invulnerable 要 2 个参数（谁, 0 或 1）");
            if (entityOps != null) {
                entityOps.invulnerable(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)) != 0);
            }
            return "";
        }
        // 不开就什么都不做（不是「关掉别人的」）—— 谁开谁关，按人账在宿主那边分。
        if (fn.equals("keep_items")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "keep_items 要 2 个参数（谁, 0 或 1）");
            if (entityOps != null) {
                entityOps.keepItems(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)) != 0);
            }
            return "";
        }
        // 重生点：传**当前世界**的绝对坐标（与 tp 的 4 参同一个坐标口径）；区域里用就自己带区域坐标算好再传。
        if (fn.equals("respawn")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "respawn 要 4 个参数（谁, x, y, z）");
            if (entityOps != null) {
                entityOps.respawn(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)),
                        Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
            }
            return "";
        }
        // 点火（秒；0 = 灭火）· 朝向（看向一个点）· 击退（推一把，不是「设速度」）· 骑乘（目标也按名字找）。
        // ⚠ knockback 本来叫 push —— 与内建函数 push(列表, 值) 撞名，原语的 if 链在内建之前，
        //  一重名就把内建整个盖掉（老档《你画我猜》当场 halt）。加原语前先核 Builtins.FUNCS。
        if (fn.equals("fire")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "fire 要 2 个参数（谁, 几秒）");
            if (entityOps != null) entityOps.fire(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)));
            return "";
        }
        if (fn.equals("face")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "face 要 4 个参数（谁, x, y, z）");
            if (entityOps != null) {
                entityOps.face(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)),
                        Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
            }
            return "";
        }
        if (fn.equals("knockback")) {
            if (args.size() != 4) throw new Ast.ScriptError(c.line(), "knockback 要 4 个参数（谁, x, y, z）");
            if (entityOps != null) {
                entityOps.knockback(Builtins.text(args.get(0)), Builtins.numOf(args.get(1)),
                        Builtins.numOf(args.get(2)), Builtins.numOf(args.get(3)));
            }
            return "";
        }
        if (fn.equals("ride")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "ride 要 2 个参数（谁, 骑上谁）");
            String rTarget = Builtins.text(args.get(1));
            if (rTarget.isEmpty()) throw new Ast.ScriptError(c.line(), "ride 的目标是空的");
            if (entityOps != null) entityOps.ride(Builtins.text(args.get(0)), rTarget);
            return "";
        }
        // seat / unseat 不再提供：席位改用脚本自己的数组之后，
        // 入席 = push(players, actor)、退席 = del(players, 下标) —— 引擎不再替脚本记「谁在座」。
        // 留着这两条判据只为给老档一句**带指引**的报错（不然报的是「没有这个函数」，作者不知道改成什么）。
        if (fn.equals("seat") || fn.equals("unseat")) {
            throw new Ast.ScriptError(c.line(), fn + "(…) 已去掉 —— 名单自己写成数组："
                    + "入席 push(players, actor)、退席 del(players, 下标)");
        }
        // 玩家档案：profile_get(名字, 键) / profile_set(名字, 键, 值)。
        // 跨局持久的「按名字键值」—— 金钱/等级存这里，掉线重连、重启都在。值是文本一层
        // （数字用 num 拼回来）；没存过读回空串；没注入不报错不发生（同 block_at 口径）。
        if (fn.equals("profile_get")) {
            if (args.size() != 2) throw new Ast.ScriptError(c.line(), "profile_get 要 2 个参数（名字, 键）");
            if (profiles == null) return "";
            return profiles.get(Builtins.text(args.get(0)), Builtins.text(args.get(1)));
        }
        if (fn.equals("profile_set")) {
            if (args.size() != 3) throw new Ast.ScriptError(c.line(), "profile_set 要 3 个参数（名字, 键, 值）");
            if (profiles != null) {
                profiles.set(Builtins.text(args.get(0)), Builtins.text(args.get(1)), Builtins.text(args.get(2)));
            }
            return "";
        }
        if (Builtins.isFunc(fn)) return Builtins.call(fn, args, c.line(), rnd);
        Ast.Func f = sc.funcs().get(fn);
        if (f == null) throw new Ast.ScriptError(c.line(), "没有这个函数：" + fn);
        if (args.size() != f.params().size()) {
            throw new Ast.ScriptError(c.line(), fn + " 要 " + f.params().size() + " 个参数，这里给了 " + args.size() + " 个");
        }
        if (frames.size() >= MAX_DEPTH) throw new Ast.ScriptError(c.line(), "递归太深了（超过 " + MAX_DEPTH + " 层）");
        Map<String, Object> scope = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) scope.put(f.params().get(i), args.get(i));
        frames.add(scope);
        // 自定义函数**同步跑完再取值**（值立刻要用）：推个调用帧，让驱动跑到它出帧为止。
        // 代价见 doWait：这种帧里不能 wait（值正卡在表达式里，挂起了送不回来）。
        Object[] box = new Object[]{""};
        stack.push(new Frame(f.body(), null, null, true, true, box));
        String jump = driveChain("函数 " + fn, stack.size());
        if (jump != null) throw new GotoSig(jump);            // 函数里 goto：交回链上换阶段
        return box[0];
    }

    // ============ 变量（B 批起只有「名字 → 值」这一层：拥有者那一维随「每人一份」一起去掉）============

    private boolean frameHas(String name) {
        for (int k = frames.size() - 1; k >= 0; k--) if (frames.get(k).containsKey(name)) return true;
        return false;
    }

    private Object frameGet(String name) {
        for (int k = frames.size() - 1; k >= 0; k--) {
            Map<String, Object> f = frames.get(k);
            if (f.containsKey(name)) return f.get(name);
        }
        return "";
    }

    private void framePut(String name, Object v) {
        for (int k = frames.size() - 1; k >= 0; k--) {
            Map<String, Object> f = frames.get(k);
            if (f.containsKey(name)) {
                f.put(name, v);
                return;
            }
        }
    }

    private Ast.OnStage stageHandler(String st) {
        for (Ast.Handler h : sc.handlers()) {
            if (h instanceof Ast.OnStage os && os.stage().equals(st)) return os;
        }
        return null;
    }

    private <T extends Ast.Handler> T handler(Class<T> cls) {
        for (Ast.Handler h : sc.handlers()) if (cls.isInstance(h)) return cls.cast(h);
        return null;
    }


    // ============================================================ 读状态（宿主 / 自检用）

    public boolean isFinished() { return finished; }

    public boolean isHalted() { return halted; }

    public String error() { return error; }

    public boolean isWaiting() { return waiting; }

    /** 当前阶段 id（空 = 还没进过阶段）。 */
    public String stageId() { return stage; }

    /**
     * 这一局的**棋盘维度**（脚本顶层的 {@code dim "tablegame:board"}；空串 = 没声明 = 主世界）。
     *
     * <p>宿主拿它决定三件事：世界原语落在哪个维度、{@code block_at} 读哪个维度、
     * 世界事件（踩格 / 潜行右键）只认那个维度里的玩家。**语言自己不知道维度是什么**，它只是把这个名字带出去。
     */
    public String dimId() { return sc.dim() == null ? "" : sc.dim(); }

    /**
     * 本局是否**允许玩家替换方块**（顶层 {@code allow_replace 1}；默认 false = 老档行为）。
     *
     * <p>false：潜行右键归引擎 —— 吃掉那一下原版交互 + 当手势发世界事件（{@code on world} 的点击来源）。
     * true：引擎不抢，原版交互照常（玩家照样能放方块），也不发世界事件。
     *
     * <p>⚠ <b>只管玩家的潜行右键</b>；脚本自己的 {@code set_block} / {@code fill} 不受影响（两条通道）。
     */
    public boolean resident() {
        return sc.resident();
    }

    /**
     * 席位玩家掉线的处置口径（顶层 {@code offline stop|wait|skip}；没写 = {@code stop}）。
     *
     */
    public String offlineMode() {
        String m = sc.offline();
        return m == null || m.isEmpty() ? "stop" : m;
    }

    /**
     * 一条**区域**（顶层 {@code area} 的运行时形态）：名字 + 已解析的世界 + 已排序的盒（两角点不分先后）。
     * @param name 区域名（匿名 = 空串）· @param dim 维度注册名 · @param art 场景快照名（空 = 没绑）· @param label 显示名（空 = 退回 name）
     */
    public record Region(String name, String dim, String art, String label,
                         double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        /** 这个点在不在这条区域里（**世界也要对上** —— 同坐标在另一个维度不算在圈里）。 */
        public boolean holds(String hisDim, double x, double y, double z) {
            return dim.equals(hisDim)
                    && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    private final List<Region> regions;

    /**
     * 一份脚本里的区域（**静态版**）：声明里的世界解析掉 + 盒排序好。
     *
     */
    public static List<Region> regionsOf(Ast.Script sc) {
        List<Region> out = new ArrayList<>();
        for (Ast.AreaDecl a : sc.areas()) {
            if (a.box().size() != 6) continue;                      // 解析器已经保证是六个；防御性跳过
            // 区域不写 dimension → 用顶层 dim；dim 也没写 → 主世界（默认维度 id 是平台契约的一部分：
            // 「没写 dim」在宿主侧本来就是「主世界」，这里只是把同一个口径落成一个能比较的字符串）
            String rdim = a.world().isEmpty() ? (sc.dim().isEmpty() ? "minecraft:overworld" : sc.dim()) : a.world();
            out.add(new Region(a.name(), rdim, a.art(), a.label(),
                    Math.min(a.box().get(0), a.box().get(3)), Math.min(a.box().get(1), a.box().get(4)),
                    Math.min(a.box().get(2), a.box().get(5)), Math.max(a.box().get(0), a.box().get(3)),
                    Math.max(a.box().get(1), a.box().get(4)), Math.max(a.box().get(2), a.box().get(5))));
        }
        return List.copyOf(out);
    }

    /**
     * 这个档声明的**所有区域**（顶层 {@code area}，顺序 = 源码顺序）—— 宿主拿它做三件事：
     * 常驻局自动接人（进**任何一条**都算范围内）· 进出圈边沿（`inBox` 换成 {@link Region#holds}）·
     * 脚本侧 {@code tp} / {@code in_area} 的名字解析。
     * 空列表 = 没写 area = 整个 {@link #dimId 棋盘维度}（宿主自己判）。
     */
    public List<Region> regions() {
        return regions;
    }

    /**
     * 这一局的进局方式里有「**这个世界**」那条吗？—— <b>一条 {@code join} 都没写 = 有</b>
     *（默认 = {@code join world}，与「没写 area 就是整个棋盘维度」的老口径对齐）。
     * 静态版：宿主**建引擎之前**只有脚本声明可用（那时还没有 {@code Interp} 实例）。
     */
    public static boolean joinsWorldOf(Ast.Script sc) {
        List<Ast.JoinDecl> js = sc.joins();
        if (js.isEmpty()) return true;
        for (Ast.JoinDecl j : js) if (j.world()) return true;
        return false;
    }

    /**
     * 进局方式里**点名的那些区域**（{@code join area r1} ⇒ 那条 {@link Region}）——
     * 宿主拿它判「他走进来了吗」。名字已由解析器保证存在（找不到就跳过，不在这里报错）。
     */
    public static List<Region> joinRegionsOf(Ast.Script sc) {
        List<Region> all = regionsOf(sc);
        List<Region> out = new ArrayList<>();
        for (Ast.JoinDecl j : sc.joins()) {
            if (j.world() || j.area().isEmpty()) continue;                 // named / world 不带区域
            for (Region r : all) if (r.name().equals(j.area())) out.add(r);
        }
        return out;
    }

    /**
     * **他此刻算不算「在局里」—— 全仓唯一判据**：点名（enter 过且没 leave）∪ join world 且在棋盘维度 ∪ join area 的那些区域里。
     * 住在引擎而不在宿主：判据只该一份，且不碰 MC 类型（维度名 / 坐标 / 布尔都是宿主喂的原始值）⇒ 自检能直接驱动。
     * @param named 点名进局的名字（null / 空 = 没点名过）· @param inBoardDim 他此刻在不在棋盘维度（join world 那条要它）
     */
    public static boolean inGame(Ast.Script sc, java.util.Set<String> named, String who,
                                 boolean inBoardDim, String hisDim, double x, double y, double z) {
        if (sc == null) return false;
        if (named != null && named.contains(who)) return true;             // ③ 脚本点名
        if (joinsWorldOf(sc) && inBoardDim) return true;                   // ① 这个世界（默认）
        for (Region r : joinRegionsOf(sc)) {                               // ② join area 的那些区域
            if (r.holds(hisDim, x, y, z)) return true;
        }
        return false;
    }

    /** 按名字找一条区域（没这条 → null；匿名那条的名字是空串）。 */
    public Region regionNamed(String name) {
        for (Region r : regions) if (r.name().equals(name == null ? "" : name)) return r;
        return null;
    }

    public boolean allowReplace() { return sc.allowReplace(); }

    /**
     * 归一一个方块 id：省命名空间的写法按 {@code minecraft:} 认（{@code "chest"} == {@code "minecraft:chest"}）。
     * 解析器（查重）与认领判据共用这一处，别各写一份。
     */
    public static String claimKey(String id) {
        String t = id == null ? "" : id.trim();
        return t.isEmpty() || t.indexOf(':') >= 0 ? t : "minecraft:" + t;
    }

    /**
     * 这一格方块**归脚本吃吗**。
     *
     * <p>没认领 ⇒ 宿主放行原版那一下（箱子 / 工作台 / 熔炉照常打开）。不碰 MC 类型 ⇒ 自检直接驱动它。
     *
     * <p>：认领表里写 {@code "*"} = 认领**所有方块**（蓝图这类「右键任意方块」的工具）。
     *
     */
    public static boolean claimsBlock(Ast.Script sc, String blockId, String handAsset) {
        if (sc == null) return false;
        // 手持认领：手里真是那条资产 ⇒ 任何方块都吃（先比这条，与方块 id 无关）。
        String hand = handAsset == null ? "" : handAsset.trim();
        if (!hand.isEmpty()) {
            for (String h : sc.clickHands()) if (h.equals(hand)) return true;
        }
        if (sc.clicks().isEmpty()) return false;
        String want = claimKey(blockId);
        if (want.isEmpty()) return false;
        for (String c : sc.clicks()) {
            // `*` 先比（原文）—— 别交给 claimKey：那会给它补 minecraft: 前缀。
            if ("*".equals(c)) return true;
            if (claimKey(c).equals(want)) return true;
        }
        return false;
    }

    /** 老口径：不看手里（自检 / 不关心手持的调用点用这条）。 */
    public static boolean claimsBlock(Ast.Script sc, String blockId) { return claimsBlock(sc, blockId, ""); }

    /** 同 {@link #claimsBlock(Ast.Script, String, String)}，用本局这一份脚本（宿主一次一下地调）。 */
    public boolean claimsBlock(String blockId, String handAsset) { return claimsBlock(sc, blockId, handAsset); }

    /** 老口径：不看手里。 */
    public boolean claimsBlock(String blockId) { return claimsBlock(sc, blockId, ""); }

    /**
     * **同一个认领表也管实体类型 id**：`click "minecraft:villager"` 写在顶层，
     * 宿主右键这个实体时才吃那一下 —— 没认领 ⇒ 放行原版（村民交易界面这才打得开）。
     *
     * <p>方块与实体**共用一张表**：运行时两边各自按自己的注册表查（方块那半传方块 id、这半传实体
     * 类型 id），互不干扰。「一个 id 同时是方块又是实体」现实中不存在，真撞了也不会错得更离谱。
     */
    public boolean claimsEntity(String typeId) {
        if (sc == null || sc.clicks().isEmpty()) return false;
        // `"*"` 只认方块 ⇒ 这里自己比 key、不认通配（否则一句 `*` 会把所有实体一起吃掉：
        // 村民 / 流浪商人的交易界面全灭）。方块那半认通配，在 claimsBlock 里。
        String want = claimKey(typeId);
        if (want.isEmpty()) return false;
        for (String c : sc.clicks()) if (claimKey(c).equals(want)) return true;
        return false;
    }

    /** 「现在谁能画」（{@code may_draw(谁)} 设的；空串 = 谁都不行）—— 画板权限照它判。 */
    public String drawer() { return drawer; }

    /** 取走「请清空画板」的请求（取一次就清掉，宿主一 tick 调一次）。 */
    public boolean takeBoardClear() {
        boolean b = boardClear;
        boardClear = false;
        return b;
    }

    /** 取走「请改这些方块」的请求（取一次清空，宿主一 tick 调一次）。 */
    public List<SetBlock> takeSetBlocks() {
        if (setBlocks.isEmpty()) return List.of();
        List<SetBlock> out = List.copyOf(setBlocks);
        setBlocks.clear();
        return out;
    }

    /** 取走「请填这些区域」的请求（取一次清空，宿主一 tick 调一次）。 */
    public List<Fill> takeFills() {
        if (fills.isEmpty()) return List.of();
        List<Fill> out = List.copyOf(fills);
        fills.clear();
        return out;
    }

    /** 取走「请保护 / 解除保护这些区域」的请求（取一次清空，宿主一 tick 调一次）。 */
    public List<Protect> takeProtects() {
        if (protects.isEmpty()) return List.of();
        List<Protect> out = List.copyOf(protects);
        protects.clear();
        return out;
    }

    /** 取走「请立这些标签」的请求（取一次清空，宿主一 tick 调一次）。 */
    public List<Label> takeLabels() {
        if (labels.isEmpty()) return List.of();
        List<Label> out = List.copyOf(labels);
        labels.clear();
        return out;
    }

    /**
     * 世界事件之二：**看向的格变了**—— 宿主收到客户端射线报告后调它。
     *
     * <p>与 {@link #acceptWorld}（踩格）同构：进事件前把内建值 {@code look_x/look_y/look_z/look_block}
     * 设成那格，跑完清干净（读宽容：没在事件里也读得到上一次的值，不报错）。
     */
    public boolean acceptLook(String player, double x, double y, double z, String block, String lookPlayer, String lookPiece) {
        if (finished || halted) return false;
        Ast.OnLook h = handler(Ast.OnLook.class);
        if (h == null) return false;
        actor = player == null ? "" : player;
        lx = x;
        ly = y;
        lz = z;
        lblock = block == null ? "" : block;
        lplayer = lookPlayer == null ? "" : lookPlayer;
        lpiece = lookPiece == null ? "" : lookPiece;
        fire(h, "看向", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /**
     * 玩家右键了某个实体（世界事件之三）：{@code actor} = 谁点的，
     * {@code type} = 实体类型注册名（{@code etype}），坐标 = 实体脚下的格（复用 {@code bx/by/bz}）。
     *
     * <p>与 {@link #acceptWorld} 同构：跑完清干净（读宽容），没写 {@code on entity} 就 false（宿主悄悄丢掉）。
     */
    public boolean acceptEntity(String player, String type, double x, double y, double z, String eid, double edead) {
        return acceptEntity(player, type, x, y, z, eid, edead, 0);
    }

    /**
     * 同上，多一个「左键还是右键」({@code hit}）：1 = 左键（攻击）/ 0 = 右键。
     */
    public boolean acceptEntity(String player, String type, double x, double y, double z, String eid,
            double edead, double hit) {
        if (finished || halted) return false;
        Ast.OnEntity h = handler(Ast.OnEntity.class);
        if (h == null) return false;
        actor = player == null ? "" : player;
        etype = type == null ? "" : type;
        // 是哪个实体对象（空串 = 不是脚本 spawn_mob 放出来的那只）；这一下是不是把它打死了（0 = 右键它 / 1 = 打死）
        this.eid = eid == null ? "" : eid;
        this.hit = hit;
        this.edead = edead;
        wx = x;
        wy = y;
        wz = z;
        fire(h, "实体", h.body());
        if (!halted && !finished) sink.stateChanged();
        return true;
    }

    /** 取走「请放这些棋子」的请求（取一次清空，宿主一 tick 调一次）。 */
    public List<PlacePiece> takePlacePieces() {
        if (placePieces.isEmpty()) return List.of();
        List<PlacePiece> out = List.copyOf(placePieces);
        placePieces.clear();
        return out;
    }

    public List<PlaceCard> takePlaceCards() {
        if (placeCards.isEmpty()) return List.of();
        List<PlaceCard> out = List.copyOf(placeCards);
        placeCards.clear();
        return out;
    }

    /** 取走「请把这几张牌翻面」的请求（取一次清空）。 */
    public List<FlipCard> takeFlipCards() {
        if (flipCards.isEmpty()) return List.of();
        List<FlipCard> out = List.copyOf(flipCards);
        flipCards.clear();
        return out;
    }

    /** 取走「请把这块实体画面挂到锚点上」的请求（取一次清空，宿主一 tick 取一次）。 */
    public List<ShowEntity> takeShowEntities() {
        if (showEntities.isEmpty()) return List.of();
        List<ShowEntity> out = List.copyOf(showEntities);
        showEntities.clear();
        return out;
    }

    /** 取走「请收起实体画面」的请求（取一次清空）。 */
    public List<HideEntity> takeHideEntities() {
        if (hideEntities.isEmpty()) return List.of();
        List<HideEntity> out = List.copyOf(hideEntities);
        hideEntities.clear();
        return out;
    }

    /** 取走「请把这些带点击的文本发出去」的请求。 */
    public List<SayMark> takeSayMarks() {
        if (sayMarks.isEmpty()) return List.of();
        List<SayMark> out = List.copyOf(sayMarks);
        sayMarks.clear();
        return out;
    }

    /** 取走「给谁开 / 关幽灵预览」的请求。 */
    public List<Ghost> takeGhosts() {
        if (ghosts.isEmpty()) return List.of();
        List<Ghost> out = List.copyOf(ghosts);
        ghosts.clear();
        return out;
    }

    /**
     * 一条「把那枚棋子放到这个坐标」的请求（世界层：棋子）。
     *
     * @param id 档里 {@code pieces} 段的棋子 id
     * @param x,y,z 落点（方块坐标，可带小数 —— 棋子是实体，按实体坐标摆）
     */
    public record PlacePiece(String id, double x, double y, double z) { }

    /**
     * 脚本请求把一张卡放进世界（原语 {@code place_card(名, "卡", x, y, z〔, 朝向〔, 尺寸〕〕)}）—— 世界层「卡牌实体」。
     *
     * @param who 牌面朝谁（玩家/本局实体名；空串 = 不朝人）
     * @param yaw 角度（{@code yawGiven=true} 时才用）
     * @param scale 尺寸分母（值越小牌越大）
     */
    public record PlaceCard(String name, String card, double x, double y, double z,
            String who, double yaw, boolean yawGiven, double scale) { }

    /** 脚本请求把某张牌翻面（原语 {@code flip_card(名)}）。 */
    public record FlipCard(String name) { }

    /** 脚本请求把一块实体画面挂到某个锚点上。 */
    public record ShowEntity(String screen, String anchor) { }

    /** 收起请求：`who` 空 = 所有锚点都收（老口径）；非空 = 只收挂在他身上的那份（`hide(谁, "名")`）。 */
    public record HideEntity(String screen, String who) { }

    /**
     * 一条「把这条文本发给谁」的请求。
     *
     * @param who 发给谁（席位名 / {@code all} / 空串 —— 宿主按 {@code say} 同一套口径分派）
     * @param name **文本对象的资产名**（脚本声明 {@code text 资产名 { … }} 的段头；宿主按它查正文与标记）
     */
    public record SayMark(String who, String name) { }

    /**
     * 一条「给谁开 / 关**幽灵预览**」的请求。
     *
     * @param who 给谁看（席位名 / 玩家名 —— 宿主按 {@code say_mark} 那套口径找人；认不出 = 跳过记日志）
     * @param area **区域 id**（项目档 {@code areas} 段里的那条快照；{@code on=false} 时无意义）
     * @param x,y,z 落点 = **区域最小角**那格（宿主与客户端只搬数，怎么触发归脚本：on look 跟随 / on world rclick 钉住）
     * @param on true = 开（钉在这格）· false = 关（收回）
     */
    public record Ghost(String who, String area, int x, int y, int z, boolean on) { }

    /**
     * 一条「在那格立/改一块牌子写这段字」的请求（世界层 W3）。
     *
     * <p>坐标是**牌子自己那格**（想标在某格上方就写 y + 1）—— 别把地砖覆盖掉。
     */
    public record Label(double x, double y, double z, String text) { }

    /**
     * 一条「把那片区域换成这个」的请求（世界层 W1，抄原版 {@code /fill} 的用处）。
     *
     * <p>两个角点不分先后（宿主取 min/max）。体积上限由宿主判 —— 语言只保证参数形态对。
     */
    public record Fill(double x1, double y1, double z1, double x2, double y2, double z2, String id) { }

    /**
     * 一条「保护 / 解除保护一片区域」的请求（{@code protect} / {@code unprotect}）。
     *
     * @param on true = 登记保护，false = 解除
     * @param level 保护级别：**1** = 只拦生存（冒险交给原版：自己挡、
     *  带 canDestroy 组件的工具能破例；创造不拦）；**2** = 拦生存 + 冒险（都挡死、白名单也不给）；
     *  **3** = 连创造也挡。{@code unprotect} 这个值没用。
     */
    public record Protect(double x1, double y1, double z1, double x2, double y2, double z2, boolean on,
                          int level) { }

    /**
     * 一条「把那格方块换成这个」的请求（世界层 W1）。
     *
     * @param x,y,z 方块整数坐标（脚本写数字；宿主取整到方块格）
     * @param id 方块注册名，如 {@code minecraft:red_wool}
     */
    public record SetBlock(double x, double y, double z, String id) { }

    /**
     * 一格容器内容（宿主 → 解释器的**原始数据**，{@code chest_items} 把它包成列表值）。
     *
     *
     * @param slot 槽位下标（0 起）
     * @param item 物品注册名，如 {@code minecraft:stone}
     * @param count 这一堆的数量
     */
    public record SlotItem(int slot, String item, int count) { }

    /** 本阶段剩余秒（没限时 → -1）。 */
    public double stageLeft() {
        if (stageSec <= 0) return -1;
        return Math.max(0, stageSec - (clock - stageAt) / 1000.0);
    }

    /** 这一局用的那份脚本（**已经解析好的**）—— 宿主要查脚本里的声明 时用它，别重解析。 */
    public Ast.Script script() {
        return sc;
    }

    // ⛔ actor / inputText / slot 三个访问器 清理：全工程零调用。
    // ⛔ seatsView（席位表拷贝） B 批删：席位表没了 —— 名单是脚本自己的数组。

    /** 变量的文本形态（没这条 → 空串）。 */
    public String varText(String name) {
        return Builtins.text(vars.get(name));
    }

    /** 变量的原值。 */
    public Object value(String name) {
        return vars.get(name);
    }

    // ⛔ slot(String)（「槽位的全部拥有者」） 清理：全工程零调用。

    /**
     * 这个槽位该不该给 {@code viewer} 看（求值声明上的可见性条件；没写 = 人人可见）。
     * 条件里能用内建值 {@code viewer}（这份快照给谁）与全部槽位：{@code var answer @(viewer == painter) = ""} / {@code @(score[viewer] > 3)}。
     */
    public boolean visibleTo(String name, String viewer) {
        Ast.Decl d = decls.get(name);
        if (d == null) return false;
        if (d.vis() == null) return true;
        String prev = this.viewer;
        this.viewer = viewer;                              // 让条件里的 viewer 有意义
        try {
            return Builtins.truthy(eval(d.vis()));
        } finally {
            this.viewer = prev;
        }
    }

    /**
     * 这个玩家该看到的变量 —— {@code 变量名 → 文本}（宿主的每帧快照裁切就用它）。
     *
     */
    public Map<String, String> visibleValues(String viewer) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Ast.Decl d : decls.values()) {
                if (!visibleTo(d.name(), viewer)) continue;
                Object val = vars.get(d.name());
                if (d.own()) {
                    // @own：值是一个记录，每人只拿「键 = 他自己」那一项。
                    // 没有他那一项 ⇒ 这个变量不进他的快照（等于「他手上还没有这份东西」）；
                    // 挂在非记录上 ⇒ 报错带行号（不静默 —— 与「读缺字段报错」同一套风格）。
                    if (!(val instanceof Map<?, ?> m)) {
                        String kind = val instanceof java.util.List ? "列表" : (val instanceof Double ? "数值" : "文本");
                        throw new Ast.ScriptError(d.line(), "@own 只能挂在记录上（var " + d.name()
                                + " = rec()），现在它是个" + kind);
                    }
                    if (!m.containsKey(viewer)) continue;
                    out.put(d.name(), Builtins.text(m.get(viewer)));
                    continue;
                }
                out.put(d.name(), Builtins.text(val));
            }
        } catch (Ast.ScriptError e) {
            halt("快照裁切出错 —— " + e.getMessage());       // 兜底：静态检查漏了也不炸服务器
        }
        return out;
    }

    public List<String> trace() { return trace; }

    private void log(String s) {
        trace.add(s);
    }

    // ============================================================ 快照（落盘 · 断点续跑）

    /**
     * 一局的**引擎状态快照**：能把局在盘上停住，重进存档原地接上。
     * ⚠ 时间一律存**相对量**（相对 {@link #clock}）—— 恢复时按当下时钟重算 ⇒ 关档期间不流逝。
     * @param vars 变量仓库（名字 → 值）· @param scopes 局部作用域栈 · @param stack 执行栈（栈顶 → 栈底）
     * @param stageElapsedSec 本阶段已过秒 · @param waitLeftSec 挂起剩余秒 · @param everyElapsedSec 各 on every 距上次触发的秒（键 = every@行号）
     */
    public record Snapshot(
            Map<String, Object> vars,
            List<Map<String, Object>> scopes,
            List<FramePos> stack,
            String stage, double stageElapsedSec, double stageSec, boolean timeoutFired,
            boolean waiting, double waitLeftSec,
            int hideSeq, String curFull, String curHud, String drawer,
            Map<String, String> fullPer, Map<String, String> hudPer,
            Map<String, Double> everyElapsedSec) {

        /** 执行栈上的一帧：<b>语句块号</b>（{@link Interp#blocks} 的下标）+ 块内下一条 + 本帧自己开的作用域。 */
        public record FramePos(int block, int ip, boolean ownScope) { }
    }

    /**
     * 快照的<b>语句块寻址表</b>：解析完成后遍历一次 AST，把每段可执行的语句序列编号。
     *
     *
     * <p>收哪些体：所有 {@code on …} 入口体 + {@code func} 体（调用帧用）+ 递归收嵌套的
     * {@code if} 两支 / {@code while} / {@code for} 的体。<b>不收 {@code part} / {@code screen} 的体</b>：
     * 那些是画舞台时走的（{@code drawBody} 的活），永远不在执行栈上。
     */
    private final List<List<Ast.Stmt>> blocks = new ArrayList<>();
    /** 块号 → 它是哪个循环的循环体（null = 不是）——恢复时把 {@code loopW} / {@code loopF} 接回去。 */
    private final List<Ast.Stmt> blockLoop = new ArrayList<>();
    /** 语句体（按<b>身份</b>比对，不能用 equals —— 两段内容相同的体是两个块）→ 块号。 */
    private final java.util.Map<List<Ast.Stmt>, Integer> blockOf = new java.util.IdentityHashMap<>();

    /** 建块号表（构造函数里铺完初值就建，每局只做一次）。 */
    private void indexBlocks() {
        for (Ast.Handler h : sc.handlers()) addBlock(handlerBody(h), null);
        for (Ast.Func f : sc.funcs().values()) addBlock(f.body(), null);
    }

    /** {@code Handler} 的共同口只有 {@code line}，体得逐型取。 */
    private static List<Ast.Stmt> handlerBody(Ast.Handler h) {
        if (h instanceof Ast.OnStart x) return x.body();
        if (h instanceof Ast.OnInput x) return x.body();
        if (h instanceof Ast.OnStage x) return x.body();
        if (h instanceof Ast.OnEvery x) return x.body();
        if (h instanceof Ast.OnTimeout x) return x.body();
        if (h instanceof Ast.OnPick x) return x.body();
        if (h instanceof Ast.OnWorld x) return x.body();
        if (h instanceof Ast.OnEntity x) return x.body();
        if (h instanceof Ast.OnLook x) return x.body();
        if (h instanceof Ast.OnLeave x) return x.body();
        return List.of();
    }

    /**
     * 给一段语句体编号，并递归收它里面的嵌套块。
     *
     * @param loop 这段体是哪个循环的体（{@code while} / {@code for}；null = 不是循环体）
     */
    private void addBlock(List<Ast.Stmt> body, Ast.Stmt loop) {
        if (body == null || body.isEmpty()) return;    // 空体上不了栈（if 的空支不推帧；空 while 体会被预算掐断）
        if (blockOf.containsKey(body)) return;         // 同一段体只编一次
        blockOf.put(body, blocks.size());
        blocks.add(body);
        blockLoop.add(loop);
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.If i) {                   // then 与 else 各自是一段体
                addBlock(i.then(), null);
                addBlock(i.els(), null);
            } else if (s instanceof Ast.While w) {
                addBlock(w.body(), w);
            } else if (s instanceof Ast.For f) {           // for 体是 ownScope 帧，循环指针就是它自己
                addBlock(f.body(), f);
            }
        }
    }

    /**
     * 取引擎状态快照（宿主在一 tick 的活干完之后调）：空闲 / {@code wait} 挂起都行；
     * 已结束 / 已停住 / 栈上还压着调用帧（理论上到不了）→ 返回 null —— 宁可这轮不落盘，也别存半个表达式。
     * 返回对象**归调用方**（值已深拷贝）。
     */
    public Snapshot snapshot() {
        if (finished || halted) return null;
        if (!waiting && !stack.isEmpty()) return null;
        List<Snapshot.FramePos> st = new ArrayList<>();
        for (Frame f : stack) {                              // 迭代顺序 = 栈顶 → 栈底
            Integer bi = blockOf.get(f.body);
            if (bi == null || f.awaiting || f.ret != null) return null;
            st.add(new Snapshot.FramePos(bi, f.ip, f.ownScope));
        }
        Map<String, Object> vs = new LinkedHashMap<>();
        for (var e : vars.entrySet()) vs.put(e.getKey(), copyValue(e.getValue()));
        List<Map<String, Object>> scs = new ArrayList<>();
        for (Map<String, Object> sc : frames) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var v : sc.entrySet()) m.put(v.getKey(), copyValue(v.getValue()));
            scs.add(m);
        }
        Map<String, Double> ev = new LinkedHashMap<>();
        for (var e : lastEvery.entrySet()) ev.put(e.getKey(), (clock - e.getValue()) / 1000.0);
        return new Snapshot(vs, scs, st,
                stage, stage.isEmpty() ? 0 : (clock - stageAt) / 1000.0, stageSec, timeoutFired,
                waiting && !scanning, waiting && !scanning ? (waitAt - clock) / 1000.0 : 0,
                hideSeq, curFull, curHud, drawer, Map.copyOf(fullPer), Map.copyOf(hudPer), ev);
    }

    /**
     * 把快照接回来（宿主重建好这一局之后调）。{@code nowMs} = 宿主当下的时钟，用来把相对量换算回绝对时刻。
     *
     * <p>返回 {@code false} = 这份快照接不上（块号 / 下标越界 ⇒ 脚本改过；或这局已结束 / 已停住）——
     * 调用方该按「从头重开 / 等玩家手动点【运行】」处理，<b>不要半途接</b>。
     */
    public boolean apply(Snapshot s, long nowMs) {
        if (s == null || finished || halted) return false;
        // 先把执行栈整个验一遍再动状态：验到一半失败会留下半个状态，比压根不接更糟。
        List<Frame> want = new ArrayList<>();
        for (Snapshot.FramePos fp : s.stack()) {
            if (fp.block() < 0 || fp.block() >= blocks.size()) return false;
            List<Ast.Stmt> body = blocks.get(fp.block());
            if (fp.ip() < 0 || fp.ip() > body.size()) return false;
            Ast.Stmt lp = blockLoop.get(fp.block());
            // ⚠ 帧的「下一条语句」（ip）是**可变字段、不在构造参数里** —— 建完必须自己接回去，
            // 否则恢复出来的帧从块头重跑（自检抓到的坑：整条链会从头再执行一遍）。
            Frame f;
            if (lp instanceof Ast.While w) f = new Frame(body, w, null, fp.ownScope());
            else if (lp instanceof Ast.For fo) f = new Frame(body, null, fo, fp.ownScope());
            else f = new Frame(body, null, null, fp.ownScope());
            f.ip = fp.ip();
            want.add(f);
        }
        if (!s.waiting() && !want.isEmpty()) return false;     // 栈只认挂起态（与 snapshot 同一口径）
        clock = nowMs;
        vars.clear();
        for (var e : s.vars().entrySet()) vars.put(e.getKey(), copyValue(e.getValue()));
        // 快照里没有的变量（脚本新加了个 var）→ 用声明初值补一格，别让后面的读落空。
        for (Ast.Decl d : sc.globals()) {
            if (vars.containsKey(d.name())) continue;
            vars.put(d.name(), eval(d.init()));
        }
        frames.clear();
        for (Map<String, Object> sc : s.scopes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var v : sc.entrySet()) m.put(v.getKey(), copyValue(v.getValue()));
            frames.add(m);
        }
        stack.clear();
        for (int i = want.size() - 1; i >= 0; i--) stack.push(want.get(i));   // 存的是栈顶→栈底，push 要倒着来
        stage = s.stage();
        stageAt = stage.isEmpty() ? clock : clock - (long) (s.stageElapsedSec() * 1000);
        stageSec = s.stageSec();
        timeoutFired = s.timeoutFired();
        waiting = s.waiting();
        waitAt = clock + (long) (s.waitLeftSec() * 1000);
        hideSeq = s.hideSeq();
        curFull = s.curFull();
        curHud = s.curHud();
        fullPer.clear();
        fullPer.putAll(s.fullPer());
        hudPer.clear();
        hudPer.putAll(s.hudPer());
        drawer = s.drawer();
        lastEvery.clear();
        for (var e : s.everyElapsedSec().entrySet()) lastEvery.put(e.getKey(), clock - (long) (e.getValue() * 1000));
        log("从快照恢复：阶段「" + stage + "」" + (waiting ? "、挂起中" : ""));
        acceptReload();                              // 热替换 / 读存档接回来 = 脚本又生效了一次
        return true;
    }

    /**
     * 值的深拷贝：快照与引擎<b>不共用可变对象</b>。列表是最常见的槽位形状（手牌 / 题库），
     * 共享一份的话「恢复出来的那一局」和「还在跑的那一局」会互相改到。
     * 数 / 文本 / 真假都是不可变的，直接共用。
     */
    private static Object copyValue(Object v) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(copyValue(o));
            return out;
        }
        return v;
    }

    // ============================================================ 内部信号（用异常走"跳出"最省事）

    private static final class BreakSig extends RuntimeException { }

    private static final class ContinueSig extends RuntimeException { }

    private static final class EndSig extends RuntimeException { }

    private static final class HaltSig extends RuntimeException { }

    private static final class GotoSig extends RuntimeException {
        final String stage;

        GotoSig(String s) { this.stage = s; }
    }

    private static final class ReturnSig extends RuntimeException {
        final Object value;

        ReturnSig(Object v) { this.value = v; }
    }
}
