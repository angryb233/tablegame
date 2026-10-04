package com.tablegame.script;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

    /**
     * 语法分析：token 序列 → {@link Ast}（递归下降）。
     *
     * <p>递归下降 = 每种语法结构一个方法：{@code script → handler/func → statement → expr}；
     * 表达式用优先级爬升（{@code || → && → == → 比较 → 加减 → 乘除}，越靠下结合越紧）。
     *
     * <p>硬要求：报错带行号（一律 {@link Ast.ScriptError}，写清「这里应该是 X」+ 实际 token）。
     * 顺手做三条静态检查：goto 的阶段必须存在 · break/continue/return 位置合法 · 同类事件入口不重复。
     */
public final class Parser {

    private final List<Lexer.Token> ts;
    private final String src;
    private int p;

    /** goto 的落点（解析完统一对账；存成 [阶段名, 行号, 行号…]）。 */
    private final List<Object[]> gotos = new ArrayList<>();
    /** 已声明的阶段（来自 {@code on stage 名}）。 */
    private final Map<String, Integer> stages = new LinkedHashMap<>();
    private int loopDepth;
    private int funcDepth;
    /** 画块深度（{@code part} / {@code screen} 体里 > 0）：> 0 时块体只认画语句与 if/while/for。 */
    private int drawDepth;

    private Parser(List<Lexer.Token> ts, String src) {
        this.ts = ts;
        this.src = src;
    }

    /**
     * <b>开局前</b>的解析：{@link #parse} + 「必须至少一个 {@code on} 事件」这条**能不能开局**的语义。
     *
     */
    public static Ast.Script parseForPlay(String src) {
        Ast.Script sc = parse(src);
        if (sc.handlers().isEmpty()) {
            throw new Ast.ScriptError(1, "这份脚本没有任何 on 事件，开不了局");
        }
        return sc;
    }

    public static Ast.Script parse(String src) {
        return new Parser(Lexer.lex(src), src).script();
    }

    // ============================================================ 顶层

    private Ast.Script script() {
        List<Ast.Decl> globals = new ArrayList<>();
        Map<String, Ast.Func> funcs = new LinkedHashMap<>();
        List<Ast.Handler> handlers = new ArrayList<>();
        Map<String, Ast.Part> parts = new LinkedHashMap<>();
        Map<String, Ast.Screen> screens = new LinkedHashMap<>();
        Set<String> seenStart = new HashSet<>();
        Set<String> seenInput = new HashSet<>();
        Set<String> seenTimeout = new HashSet<>();
        Set<String> seenPick = new HashSet<>();
        Set<String> seenWorld = new HashSet<>();
        Set<String> seenLook = new HashSet<>();         // 看向哪格（世界事件之二）
        Set<String> seenEntity = new HashSet<>();       // 右键实体（世界事件之三，2026-09-24）
        String dimId = "";                                        // dim 声明（空 = 主世界）
        Boolean allowReplace = null;                              // allow_replace 声明（null = 没写 = 不许改方块）
        Boolean resident = null;                                  // resident 声明（null = 没写 = 一局一局的玩法）
        String offline = null;                                    // offline 声明（null = 没写 = stop）
        List<Ast.AreaDecl> areas = new ArrayList<>();              // area 声明（空 = 没写 = 整个 dim 世界）
        Set<String> seenArea = new HashSet<>();                    // 区域名查重（同名两个 = 引用时分不清）
        List<Ast.JoinDecl> joins = new ArrayList<>();               // join 声明（进局方式；空 = 没写 = join world）
        // 棋牌声明（#23）：card / piece 各一份，顺序 = 源码顺序（编辑器按这个顺序列）
        List<Ast.CardDecl> cards = new ArrayList<>();
        List<Ast.PieceDecl> pieces = new ArrayList<>();
        /** 对象声明：四类共用一个列表（段头动词 = kind）。 */
        List<Ast.AssetDecl> assetDecls = new ArrayList<>();
        java.util.Set<String> seenAsset = new java.util.HashSet<>();
        Set<String> seenCard = new HashSet<>();
        Set<String> seenPiece = new HashSet<>();
        // 顶层**认领**声明：`click "minecraft:crafting_table"` 可写多条 —— 列的是「这些方块上的右键
        // 归脚本吃」。查重按归一后的 id（省命名空间的写法与带命名空间的是同一个方块）。
        List<String> clicks = new ArrayList<>();
        Set<String> seenClick = new HashSet<>();
        // 顶层**手持认领**：`click_hand "蓝图"`（可写多条）—— 手里拿着这条资产时，右键**任何**方块归脚本。
        // 查重按资产名原样（资产名是我们自己的名字，没有命名空间归一那一套）。
        List<String> clickHands = new ArrayList<>();
        Set<String> seenHand = new HashSet<>();

        while (peek().kind() != Lexer.Kind.EOF) {
            if (at("var")) {
                globals.add((Ast.Decl) statement());
            } else if (at("func")) {
                Ast.Func f = func();
                if (funcs.putIfAbsent(f.name(), f) != null) {
                    throw new Ast.ScriptError(f.line(), "函数名重复：已经有一个 func " + f.name() + " 了");
                }
            } else if (at("part")) {
                Ast.Part pt = part();
                if (parts.putIfAbsent(pt.name(), pt) != null) {
                    throw new Ast.ScriptError(pt.line(), "部件名重复：已经有一个 part " + pt.name() + " 了");
                }
            } else if (at("screen")) {
                Ast.Screen scr = screen();
                if (screens.putIfAbsent(scr.name(), scr) != null) {
                    throw new Ast.ScriptError(scr.line(), "画布只能声明一块：screen " + scr.name() + " 写了两次");
                }
            } else if (at("on")) {
                Ast.Handler h = handler();
                if (h instanceof Ast.OnStage os) {
                    if (stages.putIfAbsent(os.stage(), os.line()) != null) {
                        throw new Ast.ScriptError(os.line(), "阶段「" + os.stage() + "」的进入入口写了两次（on stage " + os.stage() + "）");
                    }
                } else if (h instanceof Ast.OnStart && !seenStart.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on start 只能写一个");
                } else if (h instanceof Ast.OnInput && !seenInput.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on input 只能写一个");
                } else if (h instanceof Ast.OnPick && !seenPick.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on pick 只能写一个");
                } else if (h instanceof Ast.OnWorld && !seenWorld.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on world 只能写一个");
                } else if (h instanceof Ast.OnLook && !seenLook.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on look 只能写一个");
                } else if (h instanceof Ast.OnEntity && !seenEntity.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on entity 只能写一个");
                } else if (h instanceof Ast.OnTimeout && !seenTimeout.add("x")) {
                    throw new Ast.ScriptError(h.line(), "on timeout 只能写一个");
                }
                handlers.add(h);
            } else if (at("npc") || at("players")) {
                // {@code npc 名} 与 {@code players N} （B 批）一起去掉：席位表降级成脚本自己的数组。
                // 这两个词已从关键字表拿掉（指引让人写 var players = [] —— 名字位置上得能用），
                // 所以这里按**源码文本**认这两个词：老档走到这儿照样看见一句带指引的报错，不是别处看不懂的错。
                Lexer.Token kw = take();
                throw new Ast.ScriptError(kw.line(), (kw.text().equals("npc") ? "npc 声明" : "players 声明")
                        + "已去掉 —— 名单自己写成数组，如 var players = [] + on join { push(players, actor) }");
            } else if (at("card")) {
                // 顶层 card 名 { 键 值 … }：
                // 卡的**属性与引用**写成声明（与 screen / part 同一层）；美术仍住文件，声明里只留名字。
                int line = take().line();                             // 'card'
                String nm = declName("卡牌名");
                if (!seenCard.add(nm)) {
                    throw new Ast.ScriptError(line, "卡牌名重复：已经有一张 card " + nm + " 了");
                }
                cards.add(new Ast.CardDecl(nm, fieldBody("卡片"), line));
            } else if (at("piece")) {
                // 顶层 piece 名 { 键 值 … }（同上）：棋子的蓝图引用 + 显示名 + 尺寸倍率 + 属性。
                int line = take().line();                             // 'piece'
                String nm = declName("棋子名");
                if (!seenPiece.add(nm)) {
                    throw new Ast.ScriptError(line, "棋子名重复：已经有一个 piece " + nm + " 了");
                }
                pieces.add(new Ast.PieceDecl(nm, fieldBody("棋子"), line));
            } else if (at("block") || at("item") || at("entity") || at("text")) {
                // 顶层**对象声明**：block / item / entity / text 资产名 { 键 值 … }
                // 段头 = **资产名**（脚本里引用就写它）· 段里的 name = **显示名**。
                // 形状与 card / piece 同族（同一个 fieldBody 读法，四类共用一个记录）。
                Lexer.Token kw = take();                              // 'block' / 'item' / 'entity' / 'text'
                String kind = kw.text();
                int line = kw.line();
                String nm = declName("资产名");
                if (!seenAsset.add(kind + " " + nm)) {
                    throw new Ast.ScriptError(line, "资产名重复：已经有一个 " + kind + " " + nm + " 了");
                }
                List<Ast.Field> fs = fieldBody("对象");
                // components 那一层 **必须写成对象字面量** { 键 值 … }：
                // 写成别的形状当场报错（不静默吞 —— 槽了值却在服务端不生效最坑人）。
                for (Ast.Field f : fs) {
                    if (f.key().equals("components") && !(f.value() instanceof Ast.Obj)) {
                        throw new Ast.ScriptError(f.line(),
                                "components 要写成对象字面量：components { 键 值 … }（一个组件一段）");
                    }
                }
                assetDecls.add(new Ast.AssetDecl(kind, nm, fs, line));
            } else if (at("dim")) {
                // 棋盘维度：这一局的**世界**在哪个维度（世界原语 / 世界事件都落在这里）。
                // 不写 = 主世界（老档零变化）。写成字符串是因为维度 id 带命名空间（tablegame:board）。
                int line = take().line();                             // 'dim'
                if (!dimId.isEmpty()) throw new Ast.ScriptError(line, "棋盘维度只能声明一次（已经声明过 " + dimId + "）");
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.STR) {
                    throw new Ast.ScriptError(t.line(), "dim 后面要写维度名字符串，如 dim \"tablegame:board\"");
                }
                dimId = t.text().trim();
                if (dimId.isEmpty()) throw new Ast.ScriptError(line, "dim 的维度名是空的");
            } else if (at("allow_replace")) {
                // 允许玩家替换方块：
                //  0（默认，同老档）= 不许 —— 潜行右键归引擎：吃掉原版交互 + 当手势发世界事件（`on world` 里的点击来源）；
                //  1 = 允许 —— 引擎不抢这一下，原版交互照常（玩家照样能潜行右键放方块），也不发世界事件。
                // ⚠ 只管**玩家的潜行右键**；脚本自己的 set_block / fill 不受影响（那是两条通道）。
                int line = take().line();                             // 'allow_replace'
                if (allowReplace != null) throw new Ast.ScriptError(line, "allow_replace 只能声明一次");
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.NUM) {
                    throw new Ast.ScriptError(t.line(),
                            "allow_replace 后面要写 0 或 1（0 = 不许玩家改方块、1 = 允许），这里是 " + describe(t));
                }
                double v = Double.parseDouble(t.text());
                if (v != 0 && v != 1) throw new Ast.ScriptError(t.line(), "allow_replace 只能写 0 或 1");
                allowReplace = v == 1;
            } else if (at("area")) {
                // 顶层区域声明：两种写法都归这一个值。
                // 两种写法（老档一个字不用改）：
                //  area r1 { world "minecraft:overworld" box [-10, 60, -10, 10, 70, 10] art "商店" }
                //  area r1 -10 60 -10 10 70 10 // 平铺形态；名字可省（省了 = 那条匿名的「活动范围」）
                // 谁在范围内 = 所有区域的**并集**；跨过某条的边沿会发 on world（edge / edge_area 报是哪条）。
                int line = take().line();                             // 'area'
                String aName = peek().kind() == Lexer.Kind.ID ? take().text() : "";
                if (!aName.isEmpty() && !seenArea.add(aName)) {
                    throw new Ast.ScriptError(line, "区域名写重了：" + aName + "（引用它时（tp / in_area）分不清是哪条）");
                }
                if (at("{")) {
                    // 块式：dimension / box / art 三个字段（都可省，box 必须写 —— 没有盒的区域没有意义）。
                    // ⚠「哪个世界」这个字段**不能叫 world**：`world` 是语言保留字（`on world` 那个），
                    // 拿它当属性名当场报「属性名 要写一个名字」⇒ 叫 dimension（与顶层声明 `dim` 同源）。
                    String aWorld = "";
                    String aArt = "";
                    String aLabel = "";
                    List<Double> aBox = null;
                    for (Ast.Field f : fieldBody("区域")) {
                        switch (f.key()) {
                            case "dimension" -> aWorld = strOf(f.value(), "dimension");
                            case "art" -> aArt = strOf(f.value(), "art");
                            case "name" -> aLabel = strOf(f.value(), "name");
                            case "box" -> aBox = boxList(f.value());
                            default -> throw new Ast.ScriptError(f.line(),
                                    "区域里没有这个字段：" + f.key() + "（只有 name / dimension / box / art）");
                        }
                    }
                    if (aBox == null) {
                        throw new Ast.ScriptError(line, "区域要写盒：box [-10, 60, -10, 10, 70, 10]（六个数字，两角点不分先后）");
                    }
                    areas.add(new Ast.AreaDecl(aName, aWorld.trim(), aBox, aArt.trim(), aLabel.trim(), line));
                } else {
                    // 平铺：六个**字面量数字**（可带负号）。范围是布局常量，不是运行时算出来的东西 ——
                    // 要按变量算的边界自己写进事件判定里（`in_area` 读 + 事件条件）。
                    List<Double> box = new ArrayList<>();
                    for (int k = 0; k < 6; k++) box.add(boxNum("area 的坐标"));
                    areas.add(new Ast.AreaDecl(aName, "", box, "", "", line));
                }
            } else if (at("join")) {
                // 顶层**进局方式**：谁算「在这一局里」。软关键字（同 area / drop / loot）——
                // 只在顶层这一处认（`on join` 那支以 `on` 开头，走不到这里）。
                //  join world 在这个棋盘维度里就算在局（常驻玩法；**一条 join 都不写 = 就是这个**）
                //  join area r1 走进名为 r1 的那条区域就算在局（名字要在别处有 area r1）
                // 可写多条 = **并集**（任一条满足即在局）。与 area 解绑：区域只管空间（进出圈事件 / 保护）。
                int jLine = take().line();                           // 'join'
                if (at("world")) {
                    take();
                    joins.add(new Ast.JoinDecl(true, "", jLine));
                } else if (at("named")) {
                    // join named = **不靠空间**：只有脚本 enter(名字) 点到的算在局。
                    // 自己不带空间方式（只是关掉「默认 = world」那条）⇒ 与 join world / join area 并集时
                    // 它是个空集那一项，写了不吃亏。
                    take();
                    joins.add(new Ast.JoinDecl(false, "", jLine));
                } else if (at("area")) {
                    take();
                    Lexer.Token nt = take();
                    if (nt.kind() != Lexer.Kind.ID) {
                        throw new Ast.ScriptError(nt.line(),
                                "join area 后面要写区域名（别处要有同名的一条 area），这里是 " + describe(nt));
                    }
                    joins.add(new Ast.JoinDecl(false, nt.text(), jLine));
                } else {
                    throw new Ast.ScriptError(peek().line(),
                            "join 后面要写 world（这个棋盘维度）/ area <区域名>（走进那条区域）/ named（只有脚本 enter 点到的），这里是 " + describe(peek()));
                }
            } else if (at("click")) {
                // 顶层**认领**：`click "minecraft:crafting_table"`（可写多条）—— 「这一格方块上的
                // 右键归脚本吃」。**没认领的方块一律还给原版**（箱子 / 工作台 / 熔炉照常打开）。
                // 这张表**也管实体类型 id**（`click "minecraft:villager"`）——
                // 没认领的实体放行原版，村民 / 流浪商人的**交易界面**这才打得开。
                // 与画块里的 `click`（把框标成可点）**不是一件事**：那条只在 screen / part 的体内认（drawBlock）。
                // 只能写字符串：裸写 `click crafting_table` 与 `click minecraft:chest` 都读不出意图（后者的 `:` 不是合法 token）。
                int line = take().line();                             // 'click'
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.STR) {
                    throw new Ast.ScriptError(t.line(),
                            "click 后面要写方块 id 或实体类型 id 的字符串，如 click \"minecraft:crafting_table\" / "
                                    + "click \"minecraft:villager\"，这里是 " + describe(t));
                }
                String bid = t.text().trim();
                if (bid.isEmpty()) throw new Ast.ScriptError(line, "click 的 id 是空的");
                if (!seenClick.add(Interp.claimKey(bid))) {
                    throw new Ast.ScriptError(line, "认领写重了：" + bid + "（同一个 id 写了两遍）");
                }
                clicks.add(bid);
            } else if (at("click_hand")) {
                // 顶层**手持认领**：`click_hand "蓝图"` —— 手里拿着这条**资产**
                // （`tg_asset` 标签认的那条物品）时，右键**任何**方块归脚本（蓝图这类「右键任意方块」的工具）。
                // ⚠ 值是**资产名**、不是基底 id：蓝图基底是 `minecraft:paper`，按 id 认会把普通纸也算上。
                // 与 `click` **并集**：那半按方块 id 认，这条按「手里是什么」认。
                int line = take().line();                             // 'click_hand'
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.STR) {
                    throw new Ast.ScriptError(t.line(),
                            "click_hand 后面要写「手里拿着什么」的资产名字符串（如 click_hand \"蓝图\"），这里是 " + describe(t));
                }
                String hand = t.text().trim();
                if (hand.isEmpty()) throw new Ast.ScriptError(line, "click_hand 的资产名是空的");
                if (!seenHand.add(hand)) {
                    throw new Ast.ScriptError(line, "手持认领写重了：" + hand + "（同一个资产名写了两遍）");
                }
                clickHands.add(hand);
            } else if (at("resident")) {
                // 顶层 resident 0|1：常驻玩法声明 —— 席位玩家掉线只退席不终止整局
                //（钻石大陆这类：局一直挂着，谁在线谁在局里）。
                int line = take().line();                             // 'resident'
                if (resident != null) throw new Ast.ScriptError(line, "resident 只能声明一次");
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.NUM) {
                    throw new Ast.ScriptError(t.line(),
                            "resident 后面要写 0 或 1（1 = 常驻玩法：席位掉线不终止整局），这里是 " + describe(t));
                }
                double v = Double.parseDouble(t.text());
                if (v != 0 && v != 1) throw new Ast.ScriptError(t.line(), "resident 只能写 0 或 1");
                resident = v == 1;
            } else if (at("offline")) {
                // 顶层 offline stop|wait|skip：**席位玩家掉线了怎么办**（选择权给脚本作者）。
                //  stop = 整局终止（默认，老口径不变）；wait = 整局挂起等人点【继续游戏】；skip = 他退席、局照跑。
                // 写的是标识符（不是数字）：三个值是三个意思，`offline 1` 这种写法读不出意图。
                int line = take().line();                             // 'offline'
                if (offline != null) throw new Ast.ScriptError(line, "offline 只能声明一次");
                Lexer.Token t = take();
                if (t.kind() != Lexer.Kind.ID) {
                    throw new Ast.ScriptError(t.line(),
                            "offline 后面要写 stop / wait / skip（掉线了怎么办），这里是 " + describe(t));
                }
                String v = t.text().trim();
                if (!v.equals("stop") && !v.equals("wait") && !v.equals("skip")) {
                    throw new Ast.ScriptError(t.line(),
                            "offline 只能写 stop / wait / skip（stop = 终止整局 · wait = 挂起等继续 · skip = 他退席局照跑），这里是 " + v);
                }
                offline = v;
            } else {
                throw new Ast.ScriptError(peek().line(),
                        "顶层只能写 var 声明 / func / part / screen / card 卡 / piece 棋子 / dim 维度 / allow_replace / resident / offline / area / join 进局方式 / click 认领方块 / click_hand 认领手持 / on 事件（这里出现了 " + describe(peek()) + "）");
            }
        }

        // 画块体检：画块里调的部件必须声明过 —— 画块是每次快照前展开时跑的，名字写错 = 屏上一直空、作者看不见原因
        for (Ast.Screen scr : screens.values()) checkDrawCalls(scr.body(), parts.keySet());
        for (Ast.Part pt : parts.values()) checkDrawCalls(pt.body(), parts.keySet());

        // goto 对账：跳的阶段必须存在
        for (Object[] g : gotos) {
            String stage = (String) g[0];
            int line = (Integer) g[1];
            if (!stages.containsKey(stage)) {
                throw new Ast.ScriptError(line, "goto 「" + stage + "」 找不到这个阶段（没有对应的 on stage " + stage + "）");
            }
        }
        // ⚠ 「必须至少一个 on 事件」这条自 起**不在这里报** —— 它是「能不能开局」的语义，
        //  不是语法。见 Parser.parseForPlay：保存 / 读档 / 编辑器只拦语法，开局才拦这条。

        // 可见性条件体检：里面引用的名字必须存在（槽位 / 内建值 / 函数名）
        Set<String> slots = new HashSet<>();
        for (Ast.Decl d : globals) slots.add(d.name());
        for (Ast.Decl d : globals) {
            if (d.vis() != null) checkNames(d.vis(), slots, funcs.keySet());
        }

        // 进局方式体检：`join area r1` 必须真有那条区域（写在 area 之前还是之后都算）
        for (Ast.JoinDecl j : joins) {
            if (j.world() || j.area().isEmpty()) continue;        // join world / join named 不带区域名
            if (!seenArea.contains(j.area())) {
                throw new Ast.ScriptError(j.line(),
                        "join area " + j.area() + "：没有这条区域（要另写一条 area " + j.area() + " { box [...] }）");
            }
        }

        int lines = 1;
        for (int k = 0; k < src.length(); k++) if (src.charAt(k) == '\n') lines++;
        return new Ast.Script(src, List.copyOf(globals), Map.copyOf(funcs), List.copyOf(handlers),
                Map.copyOf(parts), java.util.Collections.unmodifiableMap(screens), dimId,
                allowReplace != null && allowReplace,
                List.copyOf(areas), List.copyOf(joins),
                resident != null && resident, offline == null ? "stop" : offline,
                List.copyOf(cards), List.copyOf(pieces), List.copyOf(assetDecls),
                List.copyOf(clicks), List.copyOf(clickHands), lines);
    }

    /**
     * 读一个**对象字面量**（{@code { item "x" count 1 chance 50 }}）—— 进来时当前 token 必须是 {@code &#123;}。
     *
     */
    private Ast.Obj objLit(Lexer.Token t) {
        take();                                                 // '{'
        List<Ast.Field> fs = new ArrayList<>();
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) {
                throw new Ast.ScriptError(peek().line(), "对象字面量没关上（少一个 }）");
            }
            int fl = peek().line();
            String k = declName("对象字面量的键");
            Ast.Expr v = expr();
            for (Ast.Field old : fs) {
                if (old.key().equals(k)) {
                    throw new Ast.ScriptError(fl, "对象字面量里这个键写了两遍：" + k);
                }
            }
            fs.add(new Ast.Field(k, v, null, fl));
        }
        expect("}");
        return new Ast.Obj(List.copyOf(fs), t.line());
    }


    private void checkNames(Ast.Expr e, Set<String> slots, Set<String> funcs) {
        if (e instanceof Ast.Name n) {
            if (!slots.contains(n.id()) && !Builtins.VALUES.contains(n.id()) && !n.id().equals("viewer")) {
                throw new Ast.ScriptError(n.line(), "可见性条件里用了不存在的名字：" + n.id()
                        + "（只能用槽位名 / 内建值 actor input stage_left / viewer）");
            }
        } else if (e instanceof Ast.Binary b) {
            checkNames(b.a(), slots, funcs);
            checkNames(b.b(), slots, funcs);
        } else if (e instanceof Ast.Unary u) {
            checkNames(u.e(), slots, funcs);
        } else if (e instanceof Ast.Index ix) {
            checkNames(ix.base(), slots, funcs);
            checkNames(ix.idx(), slots, funcs);
        } else if (e instanceof Ast.Member m) {
            checkNames(m.base(), slots, funcs);                     // 记录成员：字段名是标识符不是变量，只递归底座
        } else if (e instanceof Ast.ListLit l) {
            for (Ast.Expr it : l.items()) checkNames(it, slots, funcs);
        } else if (e instanceof Ast.Call c) {
            if (!Builtins.isFunc(c.fn()) && !funcs.contains(c.fn())) {
                throw new Ast.ScriptError(c.line(), "可见性条件里调了不存在的函数：" + c.fn());
            }
            for (Ast.Expr a : c.args()) checkNames(a, slots, funcs);
        }
    }

    /**
     * 棋牌声明的花括号体（#23）：{@code 键 值} 一个字段一段，值可以是字符串 / 数字 / true|false / 列表。
     *
     * <p>枚举的可选值另起一段写 {@code "键选项" ["甲", "乙"]}（名字是「键 + 选项」整串，列表字面量），
     * 应用在后面 —— 顺序随意。
     * 只有「键以『选项』结尾」且「基础字段存在」时才当它是枚举定义，否则就是一个普通字段（不藏特例）。
     */
    private List<Ast.Field> fieldBody(String what) {
        expect("{");
        Map<String, Ast.Field> fs = new java.util.LinkedHashMap<>();
        Map<String, List<String>> opts = new java.util.LinkedHashMap<>();
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) {
                throw new Ast.ScriptError(peek().line(), what + "声明的花括号没关上（少一个 }）");
            }
            int line = peek().line();
            String key = declName(what + "的属性名");
            Ast.Expr v = expr();
            if (key.endsWith("选项") && v instanceof Ast.ListLit ll) {   // 枚举的可选值
                List<String> o = new ArrayList<>();
                for (Ast.Expr it : ll.items()) o.add(literalText(it, what));
                opts.put(key.substring(0, key.length() - 2), o);
                continue;
            }
            if (fs.putIfAbsent(key, new Ast.Field(key, v, null, line)) != null) {
                throw new Ast.ScriptError(line, what + "声明的属性写重了：" + key + "（一段一个属性）");
            }
        }
        expect("}");
        List<Ast.Field> out = new ArrayList<>();
        // 只有可选值、没有值那一段的键（编辑器写枚举就是这么写的）：值按空串补上 —— 认。
        for (String k : opts.keySet()) {
            fs.putIfAbsent(k, new Ast.Field(k, new Ast.Str("", peek().line()), null, peek().line()));
        }
        for (Ast.Field f : fs.values()) {
            List<String> o = opts.remove(f.key());
            out.add(o == null ? f : new Ast.Field(f.key(), f.value(), List.copyOf(o), f.line()));
        }
        return List.copyOf(out);
    }

    /**
     * 棋牌声明的名字位：英文标识符，或**字符串字面量**。
     *
     */
    private String declName(String what) {
        Lexer.Token t = peek();
        if (t.kind() == Lexer.Kind.STR) {
            take();
            return t.text();
        }
        return name(what);
    }

    /** 字面量的原文（枚举可选值用）：字符串 / 数字 / 真假，别的写法报错（可选值要是字面量）。 */
    private static String literalText(Ast.Expr e, String what) {
        if (e instanceof Ast.Str x) return x.v();
        if (e instanceof Ast.Num x) {
            double d = x.v();
            return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (e instanceof Ast.Bool x) return x.v() ? "true" : "false";
        throw new Ast.ScriptError(e.line(), what + "的可选值只能写字面量（字符串 / 数字 / 真假）");
    }

    /** 函数定义：{@code func 名(a, b) { … }}。 */
    private Ast.Func func() {
        int line = take().line();                                     // 'func'
        String name = name("函数名");
        expect("(");
        List<String> params = new ArrayList<>();
        if (!at(")")) {
            do {
                params.add(name("参数名"));
            } while (eat(","));
        }
        expect(")");
        funcDepth++;
        List<Ast.Stmt> body = block();
        funcDepth--;
        return new Ast.Func(name, List.copyOf(params), body, line);
    }

    /** 事件入口：{@code on start / input / pick / stage 名 / every(秒) / timeout}。 */
    private Ast.Handler handler() {
        int line = take().line();                                     // 'on'
        Lexer.Token t = take();
        if (t.is("start")) return new Ast.OnStart(block(), line);
        if (t.is("input")) return new Ast.OnInput(block(), line);
        if (t.is("pick")) return new Ast.OnPick(block(), line);        // 点了可点的一份（pick = 身份值）
        if (t.is("world")) return new Ast.OnWorld(block(), line);       // 世界事件（踩格：bx/by/bz/block）
        if (t.is("look")) return new Ast.OnLook(block(), line);         // 世界事件（看向：look_x/look_y/look_z/look_block）
        if (t.is("entity")) return new Ast.OnEntity(block(), line);     // 世界事件（右键实体：etype + bx/by/bz）
        if (t.is("timeout")) return new Ast.OnTimeout(block(), line);
        if (t.is("leave")) return new Ast.OnLeave(block(), line);        // 席位玩家掉线（actor = 掉线那位）
        if (t.is("join")) return new Ast.OnJoin(block(), line);          // 有人进局（actor = 那位）
        if (t.is("reload")) return new Ast.OnReload(block(), line);       // 脚本每次生效（建局 / 热替换）
        if (t.is("break")) return new Ast.OnBreak(block(), line);          // 方块被破坏（创造也发；掉落那次带清单）
        if (t.is("place")) return new Ast.OnPlace(block(), line);          // 方块被放置
        if (t.is("use")) return new Ast.OnUse(block(), line);            // 右键空气用物品（手里那条资产）
        // ⚠ 必须在这上面这些**字面判定**里、且在下面 `stage 名` 那支**之前** —— 否则 `on reload`
        //  会被当成某个阶段名。加新事件入口时照这个位置放。
        if (t.is("stage")) {
            String stage = name("阶段名");
            return new Ast.OnStage(stage, block(), line);
        }
        if (t.is("every")) {
            expect("(");
            double sec = seconds("every 的间隔");
            expect(")");
            return new Ast.OnEvery(sec, block(), line);
        }
        throw new Ast.ScriptError(line, "on 后面只能写 start / input / pick / stage 名 / every(秒) / timeout / world / look / entity / leave，这里是 " + describe(t));
    }

    // ============================================================ 语句

    private List<Ast.Stmt> block() {
        expect("{");
        List<Ast.Stmt> out = new ArrayList<>();
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) throw new Ast.ScriptError(peek().line(), "代码块没有关上（少了 }）");
            out.add(drawDepth > 0 ? drawStmt() : statement());    // 画块里只认画语句（含 if/while/for）
            eat(";");                                             // 分号可选：{ a = 1; b = 2 } 这种 C 习惯写法也认
        }
        expect("}");
        return out;
    }

    private Ast.Stmt statement() {
        Lexer.Token t = peek();
        int line = t.line();
        if (drawDepth > 0 && !t.is("if") && !t.is("while") && !t.is("for")) {
            // 画块只在 part / screen 体里，里面只允许 if/while/for（分支与循环决定画什么），其余一律拒收：
            // 画块每次快照前都跑一遍，允许赋值就成了「渲染顺手改数据」—— 那种 bug 只在特定帧出现，最难查。
            throw new Ast.ScriptError(line, "画块里只能写 box / text / input / place / name / click / paint / 部件调用 / if / while / for，这里是 " + describe(t));
        }
        switch (t.text()) {
            case "var" -> { return decl(); }
            case "if" -> { return ifStmt(); }
            case "while" -> {
                take();
                expect("(");
                Ast.Expr cond = expr();
                expect(")");
                loopDepth++;
                List<Ast.Stmt> body = block();
                loopDepth--;
                return new Ast.While(cond, body, line);
            }
            case "for" -> { return forStmt(); }
            case "break" -> {
                take();
                if (loopDepth == 0) throw new Ast.ScriptError(line, "break 只能写在循环里");
                return new Ast.Break(line);
            }
            case "continue" -> {
                take();
                if (loopDepth == 0) throw new Ast.ScriptError(line, "continue 只能写在循环里");
                return new Ast.Continue(line);
            }
            case "return" -> {
                take();
                if (funcDepth == 0) throw new Ast.ScriptError(line, "return 只能写在 func 里");
                Ast.Expr v = (at("}") || peek().kind() == Lexer.Kind.EOF) ? null : expr();
                return new Ast.Return(v, line);
            }
            case "goto" -> {
                take();
                String stage = name("阶段名");
                gotos.add(new Object[] { stage, line });
                return new Ast.Goto(stage, line);
            }
            case "say" -> {
                take();
                expect("(");
                Ast.Expr who = expr();
                expect(",");
                Ast.Expr text = expr();
                expect(")");
                return new Ast.Say(who, text, line);
            }
            case "hide" -> {
                // hide＝全收；hide("名")＝只收那块
                // hide(谁, "名")＝只收**那个人身上**那份（同名画面挂了好几个锚点时用）—— 靠**逗号**分
                take();
                expect("(");
                Ast.Expr first = at(")") ? null : expr();
                Ast.Expr who = null;
                Ast.Expr name = first;
                if (at(",")) {
                    take();
                    who = first;
                    name = expr();
                }
                expect(")");
                if (who != null && name == null) {
                    throw new Ast.ScriptError(line, "hide 两参要写「谁 + 画面名」—— 只收名字写 hide(\"名\")");
                }
                return new Ast.Hide(name, who, line);
            }
            case "show" -> {
                // show("舞台名") 切「现在显示哪一块舞台」（全局）；
                // **show(谁, "舞台名")** ＝ 只给那几个人切（谁 = 席位值 / all / others）。
                // 一参还是两参靠**逗号**分：`show(all, "x")` 与 `show("x")` 不会混。
                take();
                expect("(");
                Ast.Expr first = expr();
                Ast.Expr who = null;
                Ast.Expr name = first;
                if (at(",")) {
                    take();
                    who = first;
                    name = expr();
                }
                expect(")");
                return new Ast.Show(who, name, line);
            }
            case "wait" -> {
                take();
                expect("(");
                double sec = seconds("wait 的秒数");
                expect(")");
                return new Ast.Wait(sec, line);
            }
            case "timer" -> {
                take();
                expect("(");
                double sec = seconds("timer 的秒数");
                expect(")");
                return new Ast.Timer(sec, line);
            }
            case "end" -> {
                take();
                return new Ast.End(line);
            }
            default -> { }
        }
        return assignOrExpr();
    }

    /**
     * 区域块里的一个**文本字段**（{@code world} / {@code art}）：只收**字符串字面量**。
     *
     */
    private static String strOf(Ast.Expr e, String what) {
        if (e instanceof Ast.Str x) return x.v();
        throw new Ast.ScriptError(e.line(), "区域的 " + what + " 要写字符串（带引号，如 \""
                + (what.equals("dimension") ? "minecraft:overworld" : "商店") + "\"）");
    }

    /**
     * 区域的盒：{@code box [x1, y1, z1, x2, y2, z2]} —— **六个字面量数字**（列表字面量，可带负号）。
     *
     */
    private List<Double> boxList(Ast.Expr e) {
        if (!(e instanceof Ast.ListLit ll)) {
            throw new Ast.ScriptError(e.line(), "区域的 box 要写列表：box [-10, 60, -10, 10, 70, 10]");
        }
        if (ll.items().size() != 6) {
            throw new Ast.ScriptError(e.line(), "区域的 box 要写六个数字（x1 y1 z1 x2 y2 z2），这里是 "
                    + ll.items().size() + " 个");
        }
        List<Double> box = new ArrayList<>();
        for (Ast.Expr it : ll.items()) box.add(constNum(it, "区域的 box 里的坐标"));
        return box;
    }

    /** 声明里一个字面量数字（块式区域 {@code box} 用）：{@code 12} 与 {@code -12} 两种形态都认。 */
    private static double constNum(Ast.Expr e, String what) {
        if (e instanceof Ast.Num n) return n.v();
        if (e instanceof Ast.Unary u && u.op().equals("-") && u.e() instanceof Ast.Num n) return -n.v();
        throw new Ast.ScriptError(e.line(), what + "要写字面量数字（如 100 或 -100），别的写法读不出「这块在哪」");
    }

    private double boxNum(String what) {
        Lexer.Token t = peek();
        boolean neg = at("-");
        if (neg) take();
        Lexer.Token n = take();
        if (n.kind() != Lexer.Kind.NUM) {
            throw new Ast.ScriptError(n.line(), what + "要写数字（如 100 或 -100），这里是 " + describe(n));
        }
        double v = Double.parseDouble(n.text());
        return neg ? -v : v;
    }

    /** C 式 for：{@code for (i = 0;i < n;i++) { … }}。 */
    private Ast.Stmt forStmt() {
        int line = take().line();
        expect("(");
        String v = name("循环变量");
        expect("=");
        Ast.Expr from = expr();
        expect(";");
        Ast.Expr cond = expr();
        expect(";");
        Ast.Stmt step = simpleStmt();
        expect(")");
        loopDepth++;
        List<Ast.Stmt> body = block();
        loopDepth--;
        return new Ast.For(v, from, cond, step, body, line);
    }

    /** 只允许 {@code i++} / {@code i--} / {@code i += e} 这种单步（for 的第三段）。 */
    private Ast.Stmt simpleStmt() {
        int line = peek().line();
        String v = name("变量名");
        for (String op : new String[] { "++", "--", "=", "+=", "-=", "*=" }) {
            if (at(op)) {
                take();
                if (op.equals("++")) return new Ast.Incr(v, 1, line);
                if (op.equals("--")) return new Ast.Incr(v, -1, line);
                return new Ast.Assign(v, List.of(), op, expr(), line);
            }
        }
        throw new Ast.ScriptError(line, "for 的第三段只能是 i++ / i-- / i += 1 这类单步，这里是 " + describe(peek()));
    }

    private Ast.Stmt ifStmt() {
        int line = take().line();                                     // 'if'
        expect("(");
        Ast.Expr cond = expr();
        expect(")");
        List<Ast.Stmt> then = block();
        List<Ast.Stmt> els = List.of();
        if (at("else")) {
            take();
            els = at("if") ? List.of(ifStmt()) : block();             // else if 链
        }
        return new Ast.If(cond, then, els, line);
    }

    /** {@code var 名 @可见 = 初值}。 */
    private Ast.Stmt decl() {
        int line = take().line();                                     // 'var'
        String name = name("变量名");
        if (at("[")) {
            // 没有「每人一份」：名单自己写成数组。当场报错带指引（不静默忽略下标）。
            throw new Ast.ScriptError(line, "每人一份（" + name + "[seat]）已去掉 —— 名单自己写成数组，"
                    + "如 var players = [] + on join { push(players, actor) }");
        }
        Ast.Expr vis = null;                                 // 可见性 = 一个条件表达式（不写 = 人人可见）
        boolean own = false;                                 // @own = 「这份数据按收件人裁」（只发他自己那一项）
        if (at("@")) {
            take();
            if (at("own")) {
                // 裸 own 是**裁切开关**，不是条件表达式。
                // 条件里真要读一个叫 own 的变量，写 @(own) 带括号 —— 静态体检会把两种情况分辨清楚。
                take();
                own = true;
            } else {
                vis = expr();
            }
        }
        expect("=");
        Ast.Expr init = expr();
        return new Ast.Decl(name, vis, init, own, line);
    }

    /** 赋值 / {@code i++} / 表达式当语句。 */
    private Ast.Stmt assignOrExpr() {
        int line = peek().line();
        Ast.Expr e = expr();
        String op = null;
        for (String cand : new String[] { "=", "+=", "-=", "*=" }) {
            if (at(cand)) { op = cand; break; }
        }
        if (op == null) return new Ast.ExprStmt(e, line);
        take();

        // 赋值左边只能是变量（可带下标链 / 成员链）：a / a[i] / a[i][j] / r.f / pts[who].gold
        // 成员降糖：Member 段折成字符串下标塞进 subs（Interp 的下标链求值/写入只有一条 Map 路径，不用另写）
        String name;
        List<Ast.Expr> subs = new ArrayList<>();
        Ast.Expr base = e;
        while (true) {
            if (base instanceof Ast.Index ix) {
                subs.add(0, ix.idx());
                base = ix.base();
            } else if (base instanceof Ast.Member m) {
                subs.add(0, new Ast.Str(m.field(), m.line()));   // ← 字段名 → 字符串字面量下标
                base = m.base();
            } else {
                break;
            }
        }
        if (!(base instanceof Ast.Name n2)) {
            throw new Ast.ScriptError(line, "赋值左边只能是变量（可带下标 / 字段），这里是 " + describe(ts.get(p - 1)));
        }
        name = n2.id();
        // `变量 = scanf(谁〔, "框的资产名"〕)`：**同步读一次提交**（C 的 scanf 那一行）。
        // ⚠ 必须在这里**先认**：scanf 的参数不是普通表达式列表（第二个参数只能是字符串字面量），
        // 而且它本身是「挂起 + 落值」的语句、不是表达式 —— 丢给 expr 会被那里的守卫当成非法用法。
        if (subs.isEmpty() && op.equals("=") && at("scanf")) {
            int scLine = take().line();                        // 'scanf'
            expect("(");
            List<Ast.Expr> sa = new ArrayList<>();
            if (!at(")")) {
                sa.add(expr());
                while (at(",")) {
                    take();
                    sa.add(expr());
                }
            }
            expect(")");
            if (sa.isEmpty() || sa.size() > 2) {
                throw new Ast.ScriptError(scLine,
                        "scanf 要写 1 或 2 个参数：谁〔, \"框的资产名\"〕 —— 例：猜 = scanf(actor)");
            }
            String boxName = "";
            if (sa.size() == 2) {
                boxName = strOf(sa.get(1), "scanf 的第二个参数（框的资产名）").trim();
                if (boxName.isEmpty()) {
                    throw new Ast.ScriptError(scLine,
                            "scanf 第二个参数要写**框的资产名**（不写 = 哪个框交的都算），这里是空串");
                }
            }
            return new Ast.Scanf(name, sa.get(0), boxName, line);
        }
        Ast.Expr value = expr();
        return new Ast.Assign(name, List.copyOf(subs), op, value, line);
    }

    // ============================================================ 表达式（优先级上升）

    private Ast.Expr expr() {
        return ternary();
    }

    /**
     * 三目 {@code 条件 ? 真值 : 假值}：优先级**最低**、**右结合**
     * （{@code a ? b : c ? d : e} 读作 {@code a ? b : (c ? d : e)}）。
     *
     * <p>条件那段走 {@link #or}（低一级的完整表达式），所以 {@code who == "B" ? … : …} 这种写法天然对；
     * 右结合靠两个分支都递归回本方法。加入它的理由见 {@code Ast.Cond} 的注释（画块里不能赋值 → 选值只能靠表达式）。
     */
    private Ast.Expr ternary() {
        Ast.Expr cond = or();
        if (!at("?")) return cond;
        int line = take().line();                                    // '?'
        Ast.Expr yes = ternary();
        expect(":");
        Ast.Expr no = ternary();
        return new Ast.Cond(cond, yes, no, line);
    }

    private Ast.Expr or() {
        Ast.Expr a = and();
        while (at("||") || at("or")) {
            int line = take().line();
            a = new Ast.Binary("||", a, and(), line);
        }
        return a;
    }

    private Ast.Expr and() {
        Ast.Expr a = equality();
        while (at("&&") || at("and")) {
            int line = take().line();
            a = new Ast.Binary("&&", a, equality(), line);
        }
        return a;
    }

    private Ast.Expr equality() {
        Ast.Expr a = relation();
        while (at("==") || at("!=")) {
            Lexer.Token op = take();
            a = new Ast.Binary(op.text(), a, relation(), op.line());
        }
        return a;
    }

    private Ast.Expr relation() {
        Ast.Expr a = addition();
        while (at("<") || at("<=") || at(">") || at(">=")) {
            Lexer.Token op = take();
            a = new Ast.Binary(op.text(), a, addition(), op.line());
        }
        return a;
    }

    private Ast.Expr addition() {
        Ast.Expr a = multiply();
        while (at("+") || at("-")) {
            Lexer.Token op = take();
            a = new Ast.Binary(op.text(), a, multiply(), op.line());
        }
        return a;
    }

    private Ast.Expr multiply() {
        Ast.Expr a = unary();
        while (at("*") || at("/") || at("%")) {
            Lexer.Token op = take();
            a = new Ast.Binary(op.text(), a, unary(), op.line());
        }
        return a;
    }

    private Ast.Expr unary() {
        if (at("!") || at("-") || at("not")) {
            Lexer.Token op = take();
            return new Ast.Unary(op.text().equals("not") ? "!" : op.text(), unary(), op.line());
        }
        return postfix();
    }

    /** 后缀：下标 {@code [i]}、成员 {@code r.field} 与调用 {@code (…)}，可连成一串。 */
    private Ast.Expr postfix() {
        Ast.Expr e = primary();
        while (true) {
            if (at("[")) {
                int line = take().line();
                Ast.Expr idx = expr();
                expect("]");
                e = new Ast.Index(e, idx, line);
            } else if (at(".")) {
                // 记录成员：r.field。字段名限英文标识符（词法规则同变量名）。
                // 降糖成 Member 节点，求值/赋值走 r["field"] 同一条 Map 路径 —— 中文字段用 r["地主"]。
                take();
                e = new Ast.Member(e, name("字段名"), peek().line());
            } else if (at("(") && e instanceof Ast.Name n) {
                int line = take().line();
                List<Ast.Expr> args = new ArrayList<>();
                if (!at(")")) {
                    do {
                        args.add(expr());
                    } while (eat(","));
                }
                expect(")");
                if (n.id().equals("scanf")) {
                    throw new Ast.ScriptError(line,
                            "scanf 只能写成 `变量 = scanf(谁〔, \"文\"〕)` —— 它是「停下等一次输入」的语句，不能塞在别的表达式里");
                }
                e = new Ast.Call(n.id(), List.copyOf(args), line);
            } else {
                return e;
            }
        }
    }

    private Ast.Expr primary() {
        Lexer.Token t = peek();
        switch (t.kind()) {
            case NUM -> {
                take();
                return new Ast.Num(Double.parseDouble(t.text()), t.line());
            }
            case STR -> {
                take();
                return new Ast.Str(t.text(), t.line());
            }
            case ID -> {
                take();
                if (t.is("true")) return new Ast.Bool(true, t.line());
                if (t.is("false")) return new Ast.Bool(false, t.line());
                if (Lexer.KEYWORDS.contains(t.text())) {
                    throw new Ast.ScriptError(t.line(), "这里不能写关键字“" + t.text() + "”");
                }
                return new Ast.Name(t.text(), t.line());
            }
            case SYM -> {
                if (t.is("(")) {
                    take();
                    Ast.Expr e = expr();
                    expect(")");
                    return e;
                }
                if (t.is("[")) {                                    // 列表字面量
                    take();
                    List<Ast.Expr> items = new ArrayList<>();
                    if (!at("]")) {
                        do {
                            items.add(expr());
                        } while (eat(","));
                    }
                    expect("]");
                    return new Ast.ListLit(List.copyOf(items), t.line());
                }
                if (t.is("{")) {                                    // 对象字面量（声明里的 components { … }）
                    return objLit(t);
                }
            }
            default -> { }
        }
        throw new Ast.ScriptError(t.line(), "这里应该是一个值（数字 / 文本 / 变量 / 列表 / 括号），实际是 " + describe(t));
    }

    // ============================================================ 部件与画布（舞台去模板化）

    /**
     * 部件声明：{@code part 名(身份, 参数…) { 画语句… }}。
     *
     */
    private Ast.Part part() {
        int line = take().line();                                     // 'part'
        String name = name("部件名");
        expect("(");
        List<String> params = new ArrayList<>();
        if (!at(")")) {
            do {
                params.add(name("参数名"));
            } while (eat(","));
        }
        expect(")");
        if (params.isEmpty()) {
            throw new Ast.ScriptError(line, "部件至少要有一个形参 —— 第一个形参是这一份的「身份」（点击时 pick 拿到的就是它）");
        }
        drawDepth++;
        List<Ast.Stmt> body = drawBlock();
        drawDepth--;
        // 显示名只属于「一块屏」：部件是复用写法，没有屏这个东西 —— 写了当场报错（不要让一行声明静默失效）。
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.ScreenLabel sl) {
                throw new Ast.ScriptError(sl.line(), "name(…) 只能写在 screen 块里（部件没有显示名）");
            }
            if (s instanceof Ast.ScreenBg sb) {               // 舞台背景同理（2026-09-27）
                throw new Ast.ScriptError(sb.line(), "bg(…) 只能写在 screen 块里（部件没有背景）");
            }
        }
        return new Ast.Part(name, List.copyOf(params), body, line);
    }

    /**
     * 画布声明：{@code screen <名字> { … }}（全屏可交互）· {@code screen <名字> hud { … }}（常驻叠加层，可写 {@code place(…)}）。
     * 名字合标识符规则即可（拉丁字母 / 数字 / 下划线）。
     * ⚠ 兼容老档：{@code screen main { … }} 仍是「开局自动开的那块」；名字就叫 {@code hud} 又没写类别的仍按**看板**算。
     */
    private Ast.Screen screen() {
        int line = take().line();                                     // 'screen'
        String name = name("画布名");
        boolean hud = false;
        boolean entityView = false;
        if (at("entity")) {                                           // screen e1 entity { … } = 实体画面
            take();
            entityView = true;
        } else if (at("hud")) {                                       // screen shop hud { … } = 看板类
            take();
            hud = true;
        } else if (name.equals("hud")) {
            hud = true;                                               // 老档：名字就是 hud ⇒ 看板（行为不变）
        }
        if (entityView) return entityScreen(name, line);
        drawDepth++;
        List<Ast.Stmt> body = drawBlock();
        drawDepth--;
        // 显示名：块里的 name("主界面") 在这层**摘出来**存进声明 —— 它不画框，留在
        // body 里会让每个「逐条走 body」的地方（展开 / 组件节点 / 结构视图）都各自多兜一种空语句。
        // 最多一条：写两条当场报错（不然「目录里显示哪一个」全看运气）。
        String label = "";
        boolean sawLabel = false;
        for (java.util.Iterator<Ast.Stmt> it = body.iterator(); it.hasNext();) {
            Ast.Stmt s = it.next();
            if (!(s instanceof Ast.ScreenLabel sl)) continue;
            if (sawLabel) throw new Ast.ScriptError(sl.line(), "显示名只能写一条（name(…)）");
            sawLabel = true;
            label = sl.text();
            it.remove();
        }
        // place(…) 是「这块画布自己摆在屏幕哪儿、多大」—— 只有看板类需要（全屏那块铺满屏幕，没得摆）
        int nPlace = countPlace(body);
        if (nPlace > 0 && !hud) {
            throw new Ast.ScriptError(line,
                    "place(…) 只能写在看板类画布里（screen <名字> hud { … }）—— 全屏那块铺满屏幕，没得摆");
        }
        if (nPlace > 1) throw new Ast.ScriptError(line, "看板画布里的 place(…) 只能写一条");
        // 舞台背景：同显示名，也从 body 里摘出来（它不画框）；最多一条。
        String bg = "";
        boolean sawBg = false;
        for (java.util.Iterator<Ast.Stmt> it = body.iterator(); it.hasNext();) {
            Ast.Stmt s = it.next();
            if (!(s instanceof Ast.ScreenBg sb)) continue;
            if (sawBg) throw new Ast.ScriptError(sb.line(), "舞台背景只能写一条（bg(…)）");
            sawBg = true;
            bg = sb.color();
            it.remove();
        }
        return new Ast.Screen(name, label, bg, hud, body, line);
    }

    /**
     * 实体画面的体：{@code { 参数行 组件块 }} ——
     * <pre>
     * screen e1 entity {
     *  name("手牌面板")               // 显示名（照普通画布那套）
     *  follow 1 face 1 away 8         // 锚点参数：跟随移动 / 跟随视角 / 超距收起
     *  dx 0 dy 2 dz 2                 // 相对锚点的偏移（格）
     *  text t1 { name "要牌" body "【要牌】" mark "hit" at 0 0 }
     * }
     * </pre>
     * 参数行 = {@code 键 值}（键只认那六个 + {@code name}）；组件块 = {@code 种类 资产名 { … }}。
     * ⚠ 参数写重 / 名字写重 / 组件种类不认 —— 解析期当场报（声明体是作者手写的，写错要立刻看得见）。
     */
    private Ast.Screen entityScreen(String name, int line) {
        expect("{");
        List<Ast.EntityParam> params = new ArrayList<>();
        List<Ast.EntityComp> comps = new ArrayList<>();
        String label = "";
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) {
                throw new Ast.ScriptError(peek().line(), "实体画面声明没关上（少一个 }）");
            }
            int l = peek().line();
            // 显示名：与普通画布同一写法 name("…")（实参必须是字面量）
            if (at("name") && peek(1).is("(")) {
                take();
                expect("(");
                String s = literalOrErr(l, "实体画面的显示名");
                expect(")");
                if (!label.isEmpty()) throw new Ast.ScriptError(l, "显示名只能写一条（name(…)）");
                label = s;
                continue;
            }
            String key = declName("实体画面的参数名 / 组件种类");
            if (key.equals("text") || key.equals("card") || key.equals("item")) {
                comps.add(entityComp(key, l, comps));
                continue;
            }
            if (!ENTITY_KEYS.contains(key)) {
                throw new Ast.ScriptError(l, "实体画面里不认这个键：`" + key
                        + "`（参数只认 follow / face / away / dx / dy / dz / name，组件只认 text / card / item）");
            }
            if (!seen.add(key)) throw new Ast.ScriptError(l, "实体画面的参数写重了：" + key);
            params.add(new Ast.EntityParam(key, expr(), l));
        }
        expect("}");
        return new Ast.Screen(name, label, "", false, List.of(), line, true, List.copyOf(params), List.copyOf(comps));
    }

    /** 实体画面的锚点参数键（写别的当场报错，别静默吞掉作者写错的键）。 */
    private static final java.util.Set<String> ENTITY_KEYS =
            java.util.Set.of("follow", "face", "away", "dx", "dy", "dz");

    /** 实体画面的一个组件块：{@code text t1 { name "…" body "…" mark "…" at x y }}。 */
    private Ast.EntityComp entityComp(String kind, int line, List<Ast.EntityComp> already) {
        boolean isText = kind.equals("text");
        // ⚠ 调用方（entityScreen）已经把「组件种类」那个词吃掉了 —— 这里别再 take，
        // 否则会把资产名（t1）当种类吃掉，接着在 `{` 上报「资产名要写一个名字」（写错过一次）。
        String asset = declName(kind + " 组件的资产名");
        for (Ast.EntityComp c : already) {
            if (c.asset().equals(asset)) {
                throw new Ast.ScriptError(line, "同一块实体画面里组件的资产名写重了：" + asset);
            }
        }
        String label = "";
        Ast.Expr body = null;
        String mark = "";
        Ast.Expr base = null;                    // 表达式（可以是变量：手牌槽位）
        double scale = 1;
        double ax = 0, ay = 0, az = 0;                       // 局部系：右 / 上 / 前（2026-09-29 期1 加第三维）
        double rx = 0, ry = 0, rz = 0;                       // 组件自身角度（XYZ 欧拉）
        boolean hasRot = false;                              // 写没写 rot（不写 = 老行为：面向观察者 / 卡牌朝锚点）
        expect("{");
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) {
                throw new Ast.ScriptError(peek().line(), "组件 " + asset + " 的花括号没关上（少一个 }）");
            }
            int l = peek().line();
            String key = declName("组件属性名");
            switch (key) {
                case "name" -> label = literalOrErr(l, "组件的显示名");
                case "body" -> body = expr();
                case "mark" -> mark = literalOrErr(l, "组件的 mark（点击回投的标记）");
                // 表达式：卡牌槽位要能写「手牌里的第 n 张」这种变量
                case "base" -> base = expr();
                case "scale" -> scale = numLiteral("组件的 scale（尺寸分母，值越小越大）");
                case "at" -> {
                    ax = numLiteral("at 的第一个数（局部系 x：右）");
                    ay = numLiteral("at 的第二个数（局部系 y：上）");
                    if (peek().kind() == Lexer.Kind.NUM || at("-")) {
                        az = numLiteral("at 的第三个数（局部系 z：前，可省）");
                    }
                }
                case "rot" -> {
                    // 三个都要写：两个数到底什么意思有歧义（绕 Y？还是绕 XY？）⇒ 不给简写
                    rx = numLiteral("rot 的第一个数（绕 x 的角度）");
                    ry = numLiteral("rot 的第二个数（绕 y 的角度）");
                    rz = numLiteral("rot 的第三个数（绕 z 的角度）");
                    hasRot = true;
                }
                default -> throw new Ast.ScriptError(l, "组件里不认这个属性：`" + key
                        + "`（只认 name / body / mark / base / scale / at / rot）");
            }
        }
        expect("}");
        if (isText && body == null) throw new Ast.ScriptError(line, "text 组件要写正文（body \"…\"）");
        if (!isText && base == null) {
            throw new Ast.ScriptError(line, kind + " 组件要写它显示什么（base："
                    + (kind.equals("card") ? "卡牌资产名" : "原版物品 id")
                    + " —— 也可以写变量，如 base 手牌[1]；求值成空串 = 这个组件不显示）");
        }
        if (scale <= 0) throw new Ast.ScriptError(line, "组件的 scale 要大于 0（值越小越大）");
        return new Ast.EntityComp(kind, asset, label, body, mark, base, scale, ax, ay, az,
                rx, ry, rz, hasRot, line);
    }

    /**
     * 声明体里要一个**字面量数字**（`at 0 0.45` / `at -0.7 0`）。
     *
     * ⚠ 不能用 {@code expr} 再取两次：`at -0.7 -1.5` 会被当成**减法**一口吃光，
     * 只能一个 token 一个 token 地吃（负号 + 数字两个 token —— 词法就是这么拆的）。
     */
    private double numLiteral(String what) {
        boolean neg = false;
        if (at("-")) {
            take();
            neg = true;
        }
        Lexer.Token t = peek();
        if (t.kind() != Lexer.Kind.NUM) {
            throw new Ast.ScriptError(t.line(), what + "要写数字（这里写的是 "
                    + describe(t) + "）");
        }
        take();
        double v = Double.parseDouble(t.text());
        return neg ? -v : v;
    }

    private String literalOrErr(int line, String what) {
        Ast.Expr e = expr();
        if (!(e instanceof Ast.Str s)) throw new Ast.ScriptError(line, what + "要写字符串字面量（不代值）");
        return s.v();
    }

    /** 画块里（含 if / while / for 体内）{@code place} 语句的条数。 */
    private static int countPlace(List<Ast.Stmt> body) {
        int n = 0;
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Place) n++;
            else if (s instanceof Ast.If f) n += countPlace(f.then()) + countPlace(f.els());
            else if (s instanceof Ast.While w) n += countPlace(w.body());
            else if (s instanceof Ast.For f) n += countPlace(f.body());
        }
        return n;
    }

    /** 画块体 {@code { … }}：逐条走 {@link #drawStmt} 把关（里面不接受 var / 赋值 / return / say / wait…）。 */
    private List<Ast.Stmt> drawBlock() {
        expect("{");
        List<Ast.Stmt> out = new ArrayList<>();
        while (!at("}")) {
            if (peek().kind() == Lexer.Kind.EOF) throw new Ast.ScriptError(peek().line(), "画块没有关上（少了 }）");
            out.add(drawStmt());
            eat(";");
        }
        expect("}");
        return out;
    }

    /** 画块里的一条语句：{@code box} / {@code text} / {@code input} / {@code click} / {@code paint} / {@code place} / 部件调用 / if / while / for。 */
    private Ast.Stmt drawStmt() {
        Lexer.Token t = peek();
        if (t.is("box") || t.is("text") || t.is("input") || t.is("value") || t.is("img")
                || t.is("card_face") || t.is("card_back")) return draw();   // 值框 · 摆牌 · img = 图片框（期12）
        // ⚠ 名字不能叫 card：`card(身份, …)` 早已是脚本里常见的**部件名**（引擎 javadoc 的示例就是它），
        // 同名会把现有脚本当场弄报错（加之前 grep 一下该名字在既有档/夹具里有没有被当部件/变量用）。
        if (t.is("place")) return place();
        if (t.is("click")) {
            take();
            return new Ast.Click(t.line());
        }
        if (t.is("paint")) {
            // 不带参数的画语句：把「刚画的那个框」变成绘画区（本局的临时画板就画在它里面）
            take();
            return new Ast.Draw("paint", List.of(), t.line());
        }
        if (t.is("if") || t.is("while") || t.is("for")) return statement();
        if (t.is("comp") && peek(1).is("(")) return compStmt();         // 组件起名（2026-09-27）
        if (t.is("name") && peek(1).is("(")) return screenNameStmt();   // 画布显示名（2026-09-27）
        if (t.is("bg") && peek(1).is("(")) return screenBgStmt();       // 舞台背景（2026-09-27）
        if (t.kind() == Lexer.Kind.ID && !Lexer.KEYWORDS.contains(t.text())) {
            Ast.Expr e = expr();                                      // 部件调用：card(0, "A")
            if (!(e instanceof Ast.Call)) {
                if (at("=") || at("+=") || at("-=") || at("*=")) {
                    throw new Ast.ScriptError(t.line(), "画块里不能赋值（" + t.text() + " = …）—— 画块每次快照前都跑一遍，"
                            + "改数据请写在 on 事件里；这里只能写 box / text / input / place / name / comp / bg / click / paint / 部件调用 / if / while / for");
                }
                throw new Ast.ScriptError(t.line(), "画块里这一行得是部件调用（像 card(0, \"A\")），这里是 " + describe(peek()));
            }
            return new Ast.ExprStmt(e, t.line());
        }
        throw new Ast.ScriptError(t.line(), "画块里只能写 box / text / input / place / name / comp / bg / click / paint / 部件调用 / if / while / for，这里是 " + describe(t));
    }

    /** 画语句：{@code box(x, y, 宽, 高, 底色)} · {@code text(x, y, 宽, 高, 内容, 字色)} · {@code input(x, y, 宽, 高, 提示)}。 */
    private Ast.Stmt draw() {
        Lexer.Token t = take();                                       // 'box' / 'text' / 'input'
        String kind = t.text();
        expect("(");
        List<Ast.Expr> args = new ArrayList<>();
        if (!at(")")) {
            do {
                args.add(expr());
            } while (eat(","));
        }
        expect(")");
        boolean isText = kind.equals("text");
        boolean isInput = kind.equals("input");
        boolean isValue = kind.equals("value");                        // 值框：第 5 个参数 = **表达式**
        boolean isCard = kind.equals("card_face") || kind.equals("card_back");   // 第 5 个参数 = 卡 id（档里 cards 段）
        boolean isImg = kind.equals("img");                                      // 第 5 个参数 = 资产名（期12）
        // 框带文字：`box(x, y, 宽, 高, 底色, 文字, 字色)` —— **7 个实参**那一种写法。
        // 为什么加这两个可选实参而不是「框 + 紧跟一条 text」两条语句：两条的话画布上拖框只改框的坐标，
        // 文字会留在原地（一拖就看出「没跟着走」）；一条语句里就永远同步。
        // 传 5 个 = 老写法（纯色块，老档零变化）。
        boolean boxLabel = kind.equals("box") && args.size() == 7;
        int want = isText ? 6 : 5;                                     // value 与 input 同是 5 个
        if (args.size() != want && !boxLabel) {
            throw new Ast.ScriptError(t.line(), kind + " 要 " + want + (kind.equals("box") ? " 或 7" : "")
                    + " 个参数（"
                    + (isText ? "x, y, 宽, 高, 内容, 字色"
                       : isValue ? "x, y, 宽, 高, 表达式（值框：每次展开按观看者算）"
                       : isInput ? "x, y, 宽, 高, 提示"
                       : isImg ? "x, y, 宽, 高, 资产名"
                       : isCard ? "x, y, 宽, 高, 卡 id"
                       : "x, y, 宽, 高, 底色〔, 文字, 字色〕")
                    + "），这里是 " + args.size() + " 个");
        }
        return new Ast.Draw(kind, List.copyOf(args), t.line());
    }

    /**
     * 画布摆位 {@code place(x, y, 宽, 高)}：四个都是**屏幕比例**（0~1），只许写在 {@code screen hud} 里
     * （约束在 {@link #screen} 那一层查：写在 main 里、或写了两条都当场报错）。
     */
    private Ast.Stmt place() {
        Lexer.Token t = take();                                       // 'place'
        expect("(");
        List<Ast.Expr> args = new ArrayList<>();
        if (!at(")")) {
            do {
                args.add(expr());
            } while (eat(","));
        }
        expect(")");
        if (args.size() != 4) {
            throw new Ast.ScriptError(t.line(), "place 要 4 个参数（x, y, 宽, 高 —— 都是屏幕比例 0~1），这里是 "
                    + args.size() + " 个");
        }
        return new Ast.Place(List.copyOf(args), t.line());
    }

    /**
     * 画布显示名 {@code name("主界面")}：**一个字面量**字符串（人话标签，不代值 —— 与结构视图同一口径）。
     *
     * <p>约束在 {@link #screen} 那一层查：只许写在 {@code screen} 块里、最多一条；{@link #part} 里写了
     * 当场报错（部件是复用写法，没有「一块屏」这个东西）。
     */
    private Ast.Stmt screenNameStmt() {
        Lexer.Token t = take();                                       // 'name'
        expect("(");
        Ast.Expr e = expr();
        expect(")");
        if (!(e instanceof Ast.Str s)) {
            throw new Ast.ScriptError(t.line(), "name(…) 里要写一个字面量字符串（显示名是给人看的标签，不代值）："
                    + "name(\"主界面\")");
        }
        return new Ast.ScreenLabel(s.v(), t.line());
    }

    /**
     * 舞台背景 {@code bg("#00000080")}：**一个字面量**字符串（颜色串 + 可省的两位透明度）。
     *
     * <p>约束在 {@link #screen} 那一层查：只许写在 {@code screen} 块里、最多一条；{@link #part} 里写了
     * 当场报错（部件是复用写法，没有「一块屏的背景」这个东西）。
     */
    private Ast.Stmt screenBgStmt() {
        Lexer.Token t = take();                                       // 'bg'
        expect("(");
        Ast.Expr e = expr();
        expect(")");
        if (!(e instanceof Ast.Str s)) {
            throw new Ast.ScriptError(t.line(), "bg(…) 里要写一个字面量颜色串（背景是画布自己的属性，不代值）："
                    + "bg(\"#00000080\")");
        }
        return new Ast.ScreenBg(s.v(), t.line());
    }

    /**
     * 组件起名 {@code comp("资产名")} / {@code comp("资产名", "显示名")}：**1 或 2 个字面量字符串**。
     *
     * <p>作用对象 = 它前面那一个框（写在画块里、按顺序生效，见 {@link Ast.Comp}）；
     * 两个名字都不代值 —— 与画布的 {@code name(…)}、世界的显示名同一口径（写表达式当场报错）。
     */
    private Ast.Stmt compStmt() {
        Lexer.Token t = take();                                       // 'comp'
        expect("(");
        List<Ast.Expr> args = new ArrayList<>();
        if (!at(")")) {
            do {
                args.add(expr());
            } while (eat(","));
        }
        expect(")");
        if (args.size() != 1 && args.size() != 2) {
            throw new Ast.ScriptError(t.line(), "comp 要 1 或 2 个参数（\"资产名\"〔, \"显示名\"〕），这里是 "
                    + args.size() + " 个");
        }
        for (Ast.Expr e : args) {
            if (!(e instanceof Ast.Str)) {
                throw new Ast.ScriptError(t.line(), "comp(…) 里要写字面量字符串（名字不代值）：comp(\"开始按钮\", \"开始\")");
            }
        }
        String id = ((Ast.Str) args.get(0)).v();
        String label = args.size() > 1 ? ((Ast.Str) args.get(1)).v() : "";
        return new Ast.Comp(id, label, t.line());
    }

    /**
     * 画块静态体检：画块里的部件调用，名字必须是已声明的 {@code part}（递归进 if/while/for 体里查）。
     *
     */
    private void checkDrawCalls(List<Ast.Stmt> body, Set<String> parts) {
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.ExprStmt es && es.e() instanceof Ast.Call c) {
                if (!parts.contains(c.fn())) {
                    throw new Ast.ScriptError(c.line(), "画块里调了没声明过的部件：" + c.fn()
                            + "（部件要先写 part " + c.fn() + "(…) { … }）");
                }
            } else if (s instanceof Ast.If f) {
                checkDrawCalls(f.then(), parts);
                checkDrawCalls(f.els(), parts);
            } else if (s instanceof Ast.While w) {
                checkDrawCalls(w.body(), parts);
            } else if (s instanceof Ast.For f) {
                checkDrawCalls(f.body(), parts);
            }
        }
    }

    // ============================================================ 小工具

    /** {@code wait/timer/every} 的秒数：必须是字面数字（语言规格只允许常量）。 */
    private double seconds(String what) {
        Lexer.Token t = take();
        if (t.kind() != Lexer.Kind.NUM) {
            throw new Ast.ScriptError(t.line(), what + " 要写数字（如 wait(3)），这里是 " + describe(t));
        }
        return Double.parseDouble(t.text());
    }

    /** 读一个名字（标识符且不是关键字）。 */
    private String name(String what) {
        Lexer.Token t = take();
        if (t.kind() != Lexer.Kind.ID || Lexer.KEYWORDS.contains(t.text())) {
            throw new Ast.ScriptError(t.line(), what + " 要写一个名字（英文开头），这里是 " + describe(t));
        }
        return t.text();
    }

    private Lexer.Token peek() {
        return ts.get(p);
    }

    /** 往后看第 n 个 token（0 = 当前；n 越界 = 最后那个 EOF）。 */
    private Lexer.Token peek(int n) {
        return ts.get(Math.min(p + n, ts.size() - 1));
    }

    private boolean at(String s) {
        return peek().is(s);
    }

    /** 吃掉当前 token 并返回它（返回 Token 是为了顺手拿它的行号）。 */
    private Lexer.Token take() {
        return ts.get(p++);
    }

    private boolean eat(String s) {
        if (!at(s)) return false;
        take();
        return true;
    }

    private void expect(String s) {
        if (!at(s)) {
            throw new Ast.ScriptError(peek().line(), "这里应该是 “" + s + "”，实际是 " + describe(peek()));
        }
        take();
    }

    /** 报错时把"实际遇到的东西"说清楚。 */
    private static String describe(Lexer.Token t) {
        return switch (t.kind()) {
            case NUM -> "数字 " + t.text();
            case STR -> "文本“" + t.text() + "”";
            case ID -> "名字 " + t.text();
            case SYM -> "符号 " + t.text();
            case EOF -> "脚本结束";
        };
    }
}
