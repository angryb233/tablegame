package com.tablegame.script;

import java.util.List;
import java.util.Map;

/**
 * 脚本语言的 **AST**（抽象语法树）与共享的错误类型 —— 语言三层的第一层产物（文本 → Lexer → token → Parser → **本层** → Interp → 跑）。
 * 全部用 {@code record} + {@code sealed interface}：sealed 让解释器的 {@code switch} 穷举不漏分支，record 免掉样板代码。
 * ⚠ 每个节点都带 {@code line}（行号）—— 硬要求：报错必须能指到第几行。
 */
public final class Ast {
    private Ast() { }

    /**
     * 带行号的语言错误：词法 / 语法 / 运行期一律抛它。
     * 消息形如 {@code 第 3 行：…}，一眼能定位。
     */
    public static final class ScriptError extends RuntimeException {
        /** 出错的行号（1 起）。 */
        public final int line;

        public ScriptError(int line, String msg) {
            super("第 " + line + " 行：" + msg);
            this.line = line;
        }

        /**
         * 没有行号的错误：留给「运行期才知道」的那类问题（解析期根本问不出答案的那些）。
         * 消息不加「第 N 行：」前缀，免得指着一个假行号。
         */
        public ScriptError(String msg) {
            super(msg);
            this.line = 0;
        }
    }

    // ============================================================ 表达式

    /** 表达式：算出一个值（数 / 文本 / 列表 / 真假）。 */
    public sealed interface Expr {
        int line();
    }

    /** 数字字面量 {@code 1} {@code 1.5}（内部一律 double，没有整数/浮点之分）。 */
    public record Num(double v, int line) implements Expr { }

    /** 文本字面量 {@code "苹果"}（转义已在词法阶段解开）。 */
    public record Str(String v, int line) implements Expr { }

    /** 真假字面量 {@code true} / {@code false}。 */
    public record Bool(boolean v, int line) implements Expr { }

    /** 列表字面量 {@code ["a", "b"]}（可为空 {@code []}）。 */
    public record ListLit(List<Expr> items, int line) implements Expr { }

    /** **对象字面量** {@code { 键 值 … }}。⛔ 只能当**声明里的值**（{@code components { … }}）—— 解释器见到它就报错：「结构」不是表达式该有的一种值。键的写法同声明属性（英文标识符或引号字符串）。 */
    public record Obj(List<Field> fields, int line) implements Expr { }

    /**
     * 成员访问 {@code r.field}：取记录里叫 {@code field} 的那格值。实现上是**语法糖** —— 求值 / 赋值都降成 {@code r["field"]}（同一张 Map 的取 / 存路径），
     * 所以混合链（{@code pts[who].gold}）不需要新逻辑。字段名只能是英文标识符；中文走 {@code r["地主"]}，两者同一条路。
     */
    public record Member(Expr base, String field, int line) implements Expr { }

    /**
     * 标识符：变量名，或内建值（{@code actor} {@code input} {@code stage_left}）。
     * 名字认不出来 = 运行期报错（带行号）。
     */
    public record Name(String id, int line) implements Expr { }

    /**
     * 下标 {@code a[i]} —— <b>本步的核心能力</b>。
     *
     */
    public record Index(Expr base, Expr idx, int line) implements Expr { }

    /** 一元：{@code !e} / {@code -e}（还有关键字写法 {@code not e}）。 */
    public record Unary(String op, Expr e, int line) implements Expr { }

    /** 二元：{@code a + b} 等。op 是运算符原文（{@code ==} {@code &&} {@code +} …）。 */
    public record Binary(String op, Expr a, Expr b, int line) implements Expr { }

    /** 三目 {@code 条件 ? 真值 : 假值} —— **一个表达式**，不是条件跳转。求值只算取到的那一支，所以 {@code i < len(h) ? h[i] : ""} 这种越界保护才成立。 */
    public record Cond(Expr cond, Expr yes, Expr no, int line) implements Expr { }

    /** 函数调用：内建（{@code random(3)}）与自定义 {@code func} 共用一个节点，由解释器分派。 */
    public record Call(String fn, List<Expr> args, int line) implements Expr { }

    // ============================================================ 语句

    /** 语句：执行一个动作（可能没有值）。 */
    public sealed interface Stmt {
        int line();
    }

    /**
     * 声明 {@code var 名 @(条件) = 初值}（{@code @own} = 这份数据按收件人裁）。
     * @param vis 可见性条件（可空 = 人人可见）：快照裁切时逐人求值，条件里能用内建值 {@code viewer} 与全部槽位。
     * @param own 值必须是**记录**：快照裁切时每人只拿到「键 = 他自己」的那一项。
     * ⚠ {@code @own} 只在**快照裁切**那条路上生效；画块里读这个变量照旧自己索引（{@code hand[viewer]}）。
     */
    public record Decl(String name, Expr vis, Expr init, boolean own, int line) implements Stmt { }

    /**
     * 赋值：{@code a = e} · {@code a[i] = e} · {@code a += e} · {@code a -= e} · {@code a *= e}。
     *
     * @param subs 下标链（可以为空 = 直接写变量本身；{@code hand[actor][0] = v} 是两段）
     * @param op {@code "="} 或复合运算符
     */
    public record Assign(String name, List<Expr> subs, String op, Expr value, int line) implements Stmt { }

    /** 条件：{@code if (e) { … } else { … }}。 */
    public record If(Expr cond, List<Stmt> then, List<Stmt> els, int line) implements Stmt { }

    /** 循环：{@code while (e) { … }}。 */
    public record While(Expr cond, List<Stmt> body, int line) implements Stmt { }

    /** C 式 for：{@code for (i = 0;i < n;i++) { … }}（step 复用 {@link Incr} 或 {@link Assign}）。 */
    public record For(String var, Expr from, Expr cond, Stmt step, List<Stmt> body, int line) implements Stmt { }

    /** {@code break}（跳出最内层循环）。 */
    public record Break(int line) implements Stmt { }

    /** {@code continue}（进入最内层循环的下一轮）。 */
    public record Continue(int line) implements Stmt { }

    /** {@code return [e]}（只在 func 里合法）。 */
    public record Return(Expr value, int line) implements Stmt { }

    /** {@code goto 阶段名}（换阶段并执行该阶段的 {@code on stage} 入口链）。 */
    public record Goto(String stage, int line) implements Stmt { }

    /** {@code say(谁, 文本)}：谁 = 席位值 / {@code all} / {@code others}。 */
    public record Say(Expr who, Expr text, int line) implements Stmt { }

    /**
     * {@code show("舞台名")} —— 切换「现在显示哪一块舞台」（全屏类 / 看板类**两份独立**；{@code show(谁, "名")} = 只给那几个人切）。
     * ⚠ 只换内容、不开关屏：玩家没开着全屏时只记着不弹出来（收起用 {@code hide}）；幂等（已是当前那块就什么都不做）。
     * @param who {@code null} = 全局（一参形态）
     */
    public record Show(Expr who, Expr name, int line) implements Stmt { }

    /**
     * `hide` 全收 · `hide("名")` 只收那块 · **`hide(谁, "名")` 只收那个人身上那份**。
     *
     * <p>第三形态是给「**物品右键开关自己的面板**」用的：`hide` 按画面名收会把**所有人**身上那块
     * 一起收掉（21点 每人一块手牌面板 ⇒ 一个人的开关会把全场的都关掉）。
     */
    public record Hide(Ast.Expr name, Ast.Expr who, int line) implements Stmt { }

    /** {@code wait(秒)}：挂起这条链，到点接着跑。 */
    public record Wait(double sec, int line) implements Stmt { }

    /**
     * {@code 变量 = scanf(谁〔, "框名"〕)}：**同步读一次提交** —— 这串代码停在这儿，等那个人（{@code all} = 谁都行）交一次，值落进变量再往下跑。
     * 第二个参数 = 框的资产名（不写 = 哪个框都算）；读数字还是文本**看左边变量当前值的类型**（{@code var 猜 = 0} ⇒ 数字 · {@code var 名 = ""} ⇒ 文本，同 C 的 {@code int guess;}）。
     */
    public record Scanf(String target, Expr who, String box, int line) implements Stmt { }

    /** {@code timer(秒)}：给"当前阶段"上闹钟，到点触发 {@code on timeout}。 */
    public record Timer(double sec, int line) implements Stmt { }

    /** {@code end}：收局。 */
    public record End(int line) implements Stmt { }

    /** {@code i++} / {@code i--}（for 的步进也用它）。 */
    public record Incr(String name, int delta, int line) implements Stmt { }

    /** 表达式当语句（主要给函数调用：{@code push(deck, "a")}）。 */
    public record ExprStmt(Expr e, int line) implements Stmt { }

    // ---------------- 画块语句（只写在 part / screen 体里；见 [[舞台去模板化方案]]）----------------

    /**
     * 画一个框：{@code box(x, y, 宽, 高, 底色)} · {@code text(x, y, 宽, 高, 内容, 字色)} —— 所有参数都是表达式，值一变框就跟着变。
     * ⚠ {@code text} 只在**画块里**当画语句；表达式里的 {@code text(v)} 仍是内建函数（转文本），由上下文区分。
     */
    public record Draw(String kind, List<Expr> args, int line) implements Stmt { }

    /**
     * {@code click}：标记「这一份可点」（写在 part 里）。
     *
     * <p>点了之后回 {@code on pick}，{@code pick} = <b>这一份的身份值</b>（= 部件第一个形参的实参）。
     * 身份由作者给（手牌下标 / 席位名 / 棋子 id），所以不受「循环里跳过几次」「一块屏上几块区域」影响。
     */
    public record Click(int line) implements Stmt { }

    /**
     * 画布自己的摆位 {@code place(x, y, 宽, 高)}：四个都是**屏幕比例**（0~1）——
     * 板左上角在屏幕的 x/y 处，板占屏幕的 宽 × 高。**只允许写在 {@code screen hud} 里**（全屏那块铺满，没得摆）。
     *
     */
    public record Place(List<Expr> args, int line) implements Stmt { }

    /** 画布里的一条**声明语句** {@code name("主界面")}：给这块画布起个只给人看的显示名。⚠ 解析期就从 body 里**摘出来**存进 {@link Screen#label}（它不画框，留着每个「逐条走 body」的地方都要多兜一种空语句）。 */
    public record ScreenLabel(String text, int line) implements Stmt { }

    /**
     * 舞台背景 {@code bg("#00000080")}：全屏那块 = 整块背景 · 看板那块 = 板的底色；颜色串 {@code #RRGGBB}[AA]（8 位时后两位 = 透明度）。
     * ⚠ 与 {@link ScreenLabel} 一样：解析期就从 body 里**摘出来**存进 {@link Screen#bg} —— 它不画框，留着每个「逐条走 body」的地方都要多兜一种空语句。
     */
    public record ScreenBg(String color, int line) implements Stmt { }

    /**
     * 组件起名 {@code comp("资产名"〔, "显示名"〕)}：给**刚画的那一个框**两个名字 —— 资产名 = 该框可点时 {@code on pick} 收到的身份值；
     * 显示名 = 只给人看的。两个都必须是**字面量字符串**，显示名可省（同画布的 {@code name("主界面")}）。
     * ⚠ 与画布那条 {@code name(…)} 不同：这条留在 body 里**按顺序**作用到「刚画的那个框」，不摘出去。
     */
    public record Comp(String id, String label, int line) implements Stmt { }

    /**
     * 画块展开出来的**一个框**（脚本侧的中间形态）：宿主把它翻成舞台组件 —— 脚本包零 MC 依赖，所以只摊成这个中立形状，客户端完全不认识画块。
     * @param kind {@code "box"}（色块）/ {@code "text"}（带内容）· @param text 内容（text 才有）· @param bg 底色（box 才有）
     * @param color 字色（text 才有）· @param clickable 是否被 {@code click} 标记过 · @param identity 身份值（点击回 {@code pick}）
     */
    public record Box(String kind, double x, double y, double w, double h, String text, String bg, String color,
                      boolean clickable, Object identity, int line) { }

    // ============================================================ 顶层

    /** 顶层函数定义：{@code func 名(a, b) { … return e }}（可递归）。 */
    public record Func(String name, List<String> params, List<Stmt> body, int line) { }

    /**
     * 部件声明：{@code part 名(身份, 参数…) { 画语句… }} —— 一块可复用的画法。
     */
    public record Part(String name, List<String> params, List<Stmt> body, int line) { }

    /**
     * 画布（舞台）：{@code screen <名字> { … }} 全屏 · {@code screen <名字> hud { … }} 看板。
     * @param name 资产名（引用 / 宿主找块用它；拉丁标识符）· @param label 显示名（块里的 {@code name("主界面")}，只给人看）
     * @param hud true = 常驻叠加层（看板，可写 {@code place(…)}）· false = 全屏界面
     * @param bg 舞台背景（块里的 {@code bg("…")}）：空串 = 缺省那层 40% 黑；看板那块 = 板的底色
     */
    public record Screen(String name, String label, String bg, boolean hud, List<Stmt> body, int line,
                         // 实体画面：`screen e1 entity { … }` —— **画面由一组实体组成**，
                         // 画在世界里、挂在锚点上。true 时 body 是空的，参数与组件在下面两栏。
                         boolean entityView,
                         // 锚点参数（follow / face / away / dx / dy / dz）—— 只有实体画面认
                         List<EntityParam> anchor,
                         // 组件
                         List<EntityComp> comps) {

        /** 普通画布（全屏 / 看板）的构造：零参数零组件 —— 老调用点一个字都不用改。 */
        public Screen(String name, String label, String bg, boolean hud, List<Stmt> body, int line) {
            this(name, label, bg, hud, body, line, false, List.of(), List.of());
        }
    }

    /** 实体画面的锚点参数一行：`follow 1` / `face 1` / `away 8` / `dx 0` / `dy 2` / `dz 2`。 */
    public record EntityParam(String key, Expr value, int line) { }

    /**
     * 实体画面里的一个组件。
     *
     */
    public record EntityComp(String kind, String asset, String label, Expr body, String mark,
            Expr base, double scale, double ax, double ay, double az,
            double rx, double ry, double rz, boolean hasRot, int line) { }

    /** 事件入口（时机）：一局游戏由这六种时机驱动。 */
    public sealed interface Handler {
        int line();
    }

    /** {@code on start { … }} —— 开局跑一次。 */
    public record OnStart(List<Stmt> body, int line) implements Handler { }

    /** {@code on input { … }} —— 有人提交时跑（{@code actor} = 谁交的，{@code input} = 交了什么）。 */
    public record OnInput(List<Stmt> body, int line) implements Handler { }

    /** {@code on stage 名 { … }} —— 进入某阶段时跑。 */
    public record OnStage(String stage, List<Stmt> body, int line) implements Handler { }

    /** {@code on every(秒) { … }} —— 每隔几秒跑一次。 */
    public record OnEvery(double sec, List<Stmt> body, int line) implements Handler { }

    /** {@code on timeout { … }} —— 当前阶段的 {@code timer(秒)} 到点时跑。 */
    public record OnTimeout(List<Stmt> body, int line) implements Handler { }

    /** {@code on pick { … }} —— 有人点了可点的一份（{@code pick} = 那一份的身份值，{@code actor} = 谁点的）。 */
    public record OnPick(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on world { … }} —— 世界里发生了脚本该知道的事。事发格走 {@code bx/by/bz/block}，触发者 {@code actor}。
     * 目前来源只有一种：**局内席位玩家踩到一格新地砖**（宿主每 tick 比脚下坐标，跨格才发；见 {@code HostManager.stepEvents}）。
     */
    public record OnWorld(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on use { … }} —— 玩家**右键空气 / 举着物品点空气**（与右键方块是两件事）。
     * 内建值与 on world 同形：{@code actor} · {@code hand} · {@code offhand} · {@code hand_asset} / {@code off_asset}。
     */
    public record OnUse(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on entity { }} —— **玩家右键了某个实体**（宿主把这一下原版交互吃掉，换成发事件给脚本）。
     * 内建值：{@code actor} · {@code etype}（实体注册名文本）· {@code bx/by/bz}（它脚下的格）。
     * 「那是哪个棋子」由脚本按坐标自己对账 —— 引擎不发实体对象。
     */
    public record OnEntity(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on look { }} —— **玩家视线看向的方块格变了**（数据来自客户端每帧的原版射线，命中格一变才报）。
     * 内建值：{@code actor} · {@code look_x/look_y/look_z/look_block}。
     * 与 {@link OnWorld} 的区别：踩格 = 脚下的格变了（走出来的）· 看向 = 视线命中的格变了（看出来的）。
     */
    public record OnLook(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on leave { }} —— **席位玩家掉线了**（{@code actor} = 掉线那位）。
     * 只在 {@code offline} 为 {@code wait} / {@code skip} 时发 —— {@code stop}（默认）那条路整局终止，没处发。
     * 处置归脚本（跳过他的回合 / 托管 / 播报一句）；没写这个入口 = 宿主悄悄丢掉。
     */
    public record OnLeave(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on join { }} —— **有人进局了**（{@code actor} = 那位）。
     * 进局 = 被接进这一局：世界玩法是进存档，游戏台是点【进入游戏界面】。典型用法：{@code push(players, actor)}。
     * 没写这个入口 = 宿主悄悄丢掉。
     */
    public record OnJoin(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on reload { }} —— **这份脚本每次「生效」都跑一次**：① 建局（新局 / 读存档接回来）② 热替换成功。
     * 给「登记在宿主的那类东西」用：{@code protect(盒)} 登记在宿主、不进快照，而热替换 / 接回来都不重跑 {@code on start}。
     * ⚠ 里面只该写**幂等**的事（登记 / 清表 / 重建派生状态）；**别拿它当初始化** —— 热替换会把状态重置成你写的初值。
     */
    public record OnReload(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on break { }} —— **方块被破坏**。内建值：{@code actor} · {@code bx/by/bz} · {@code block} ·
     * {@code hand} / {@code hand_asset} · {@code drops}（掉的物品 id 列表，只有挖成功那次才有）。
     * ⚠ 两次触发合并成一次：{@code BreakBlockEvent}（挖成功，创造也发）与 {@code BlockDropsEvent}（掉落结算，创造不发）都挂，
     *  靠宿主 {@code breakFired} 账去重（同格同短窗只发一次，掉落那次的信息覆盖普通那次）。
     */
    public record OnBreak(List<Stmt> body, int line) implements Handler { }

    /**
     * {@code on place { }} —— <b>方块被放置</b>。内建值：{@code actor} · {@code bx/by/bz} ·
     * {@code block}（放下的那个方块）。挂在 {@code EntityPlaceEvent}（protect 的兜底那条，已订阅 ⇒ 不新订阅）。
     */
    public record OnPlace(List<Stmt> body, int line) implements Handler { }

    /**
     * 棋牌声明里的一个字段：键 · 值的源码表达式 · 可选值定义（枚举才有，null = 不是枚举）· 行号。
     * {@code 键选项 [...]} 是「给那个键定义可选值」的行（见 {@code Parser.fieldBody}）；值可以是字符串 / 数字 / true|false / 列表。
     */
    public record Field(String key, Expr value, List<String> options, int line) { }

    /** 顶层 {@code card 名 { … }}（卡牌：卡面 / 卡背的资产引用 + 属性）。 */
    public record CardDecl(String name, List<Field> fields, int line) { }

    /** 顶层 {@code piece 名 { … }}（棋子：蓝图引用 + 显示名 + 尺寸倍率 + 属性）。 */
    public record PieceDecl(String name, List<Field> fields, int line) { }

    /**
     * 顶层**对象声明**：{@code block / item / entity / text 资产名 { 键 值 … }}（四类共用一个记录，只有段头动词不同）。
     *
     * 段头 = **资产名**（脚本引用写它，稳定 / 不带色码）· {@code name} = 显示名（界面看到的就是它）·
     * {@code base} = 基底原版 id · {@code lore} = 描述行 · {@code body} = 文本对象的内容。
     */
    public record AssetDecl(String kind, String name, List<Field> fields, int line) { }

    /**
     * 顶层 {@code area} 声明：**一个区域 = 名字 + 世界 + 盒 +（可选）内容引用**。两种写法归到同一个值里：
     * <pre>
     * area r1 { world "minecraft:overworld" box [-10, 60, -10, 10, 70, 10] art "商店" }
     * area r1 -10 60 -10 10 70 10 // 平铺：名字可省（省了 = 那条匿名区域，老档就是它）
     * </pre>
     * @param name 区域名（英文标识符；平铺不写 = 空串）· @param world 维度注册名；空串 = 用顶层 {@code dim} 那个世界
     * @param box 六个数字 x1 y1 z1 x2 y2 z2（两角点不分先后，消费方取 min/max）
     * @param art 引用的**场景快照**名（体素住档里，声明只留名字）· @param label 显示名（只给人看；引用一律走 {@code name}）
     */
    public record AreaDecl(String name, String world, List<Double> box, String art, String label, int line) { }

    /**
     * 顶层 {@code join} 声明：**进局方式** —— 谁算「在这一局里」。可写多条 = 并集：
     * <pre>{@code
     * join world  // 在这个棋盘维度里就算在局（**一条 join 都不写 = 就是这个**）
     * join area r1 // 走进名为 r1 的区域就算在局
     * join named  // 不靠空间：只有脚本 enter(名字) 点到的才算（报名式玩法）
     * }</pre>
     * 再加原语 {@code enter(名字)} / {@code leave(名字)} 的点名进局（点名不看空间），一共三种。
     */
    public record JoinDecl(boolean world, String area, int line) { }



    public record Script(String source, List<Decl> globals, Map<String, Func> funcs,
                         List<Handler> handlers, Map<String, Part> parts, Map<String, Screen> screens,
                         String dim,
                         // 顶层 allow_replace 1 = 允许玩家替换方块；默认 false（false 时潜行右键被当手势吃掉发世界事件）。
                         boolean allowReplace,
                         // 顶层 area（可多条）：① 进出圈边沿事件（edge / edge_area）② 坐标基准（tp 第二位 / in_area）。
                         // ⚠ 不决定「谁在局里」—— 那是 joins 的事。空表 = 一条区域都没有。
                         List<AreaDecl> areas,
                         // 顶层 join（可多条 = 并集；一条不写 = join world）：谁收快照 / 舞台 / 事件投递。
                         List<JoinDecl> joins,
                         // 顶层 resident 1 = 常驻玩法：席位掉线只退席（槽位留着等他），不终止整局。
                         boolean resident,
                         // 顶层 offline stop|wait|skip = 席位掉线怎么办（stop 默认 = 整局终止 · wait = 挂起 · skip = 他退席局照跑）；
                         // wait / skip 都会发一次 on leave（actor = 掉线那位）。
                         String offline,
                         // 棋牌声明（card / piece）：真源 = 脚本声明；档里的 cards / pieces 段读到即迁过来（美术仍住文件）。
                         List<CardDecl> cards,
                         List<PieceDecl> pieces,
                         // 对象声明（block / item / entity / text）：与档里 assets 段合并成同一份对象表
                         // （对象页 / give / spawn_mob / hand_asset 都读它）。
                         List<AssetDecl> assetDecls,
                         // 顶层认领 click "方块id"（可多条）：这些方块的右键归脚本（发 on world + 取消原版那一下）；
                         // 没认领的方块引擎不碰。空表 = 一个都不认领。
                         List<String> clicks,
                         // 顶层手持认领 click_hand "资产名"（可多条，与 clicks 并集）：手里拿着它时右键任何方块都归脚本。
                         List<String> clickHands,
                         int lines) { }
}
