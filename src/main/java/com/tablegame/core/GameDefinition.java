package com.tablegame.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


/**
 * 游戏定义（L1 数据层）—— 一张「游戏定义 JSON」在内存里的形态（schema/4）。
 * 段一览：{@code meta} 元信息 · {@code cards} / {@code decks} / {@code pieces} 卡牌 / 牌组 / 棋子实例 · {@code vars} 数值与池（玩法数据全在这）·
 * {@code stage} 舞台（**线格式**：盘上不存，真源 = 脚本的 {@code screen} 块）· {@code script} 脚本全文（运行时唯一真源）· {@code rest} 其余未解析段。
 * ⛔ {@code actions} / {@code teams} / {@code rules} 不解析、不生成，老档原样透传。
 *
 * 外部资源段（{@code recipe} / {@code villager_trade} / {@code tags} / 段B 三条 / 战利品表）都住 {@code rest}：
 * 保存时 {@link GameLayout} 拆成 {@code <段名>/<名字>.json} 一张一个文件；缺了**建一个挂回 rest**再返回（编辑器要就地改）。
 *
 * 属性字段：值存 {@code fields}（JSON 类型自推：数字 = number、布尔 = boolean、其余 = 字符串）；只有**枚举**推不出来，
 * 可选值单独存 {@code fieldDefs}（可 null）。卡牌与棋子共用同一套规则（两者 fields 同构）。
 *
 * @param name 游戏名（= 文件名，存档 {@code tablegame/games/<名>.json}）· @param desc 简介（{@code meta.desc}，空 = 不写这个键）
 * @param cards 卡牌定义（顺序即定义顺序）· @param decks 牌组定义（引用 cards 的 id 或 {@code builtin:} 牌组）· @param pieces 棋子实例定义
 * @param vars 数值 / 池变量定义 · @param stage 舞台（线格式，宿主按脚本派生后发客户端）
 * @param script 脚本全文（解析不过 = 这份档开不了局）· @param rest 其余未解析段落（原样保留，保存时写回 —— 旧 mod 读新档不丢数据）
 */
public record GameDefinition(String name, String desc, java.util.List<CardDef> cards, java.util.List<DeckDef> decks,
                             java.util.List<PieceDef> pieces,
                             java.util.List<VarDef> vars,
                             StageDef stage,
                             String script,
                             java.util.List<AssetDef> assets,
                             java.util.List<AreaDef> areas,
                             JsonObject rest) {

    /** 格式版本号：导入导出兼容判断，结构不兼容时递增尾号（当前 schema/4：规则 = {@code script} 纯文本段）。 */
    public static final String FORMAT = "tablegame.schema/4";

    /**
     * 项目档里的战利品表：表名 → 原版 loot_table JSON。
     * 缺了就**建一个挂到 rest 上再返回** —— 编辑器要就地改它（改完 saving 走整份档上传那条路）。
     */
    public JsonObject lootTables() {
        JsonElement e = rest == null ? null : rest.get("loot");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        if (rest != null) rest.add("loot", o);
        return o;
    }

    /** 项目档里的**配方**：配方名 → 原版 {@code recipe} JSON（原样存，原版 codec 解）。 {@link GameLayout} 拆成一张一个文件。 */
    public JsonObject recipes() {
        JsonElement e = rest == null ? null : rest.get("recipe");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        if (rest != null) rest.add("recipe", o);
        return o;
    }

    /** 项目档里的**村民交易**：交易名 → {@code {profession, level, trade}}（{@code trade} = 原版 {@code villager_trade} JSON 原样；
     * 挂载信息与它同住一个文件 —— 拷一个文件就把整条交易带走；{@code TradeEdit.entry} 是唯一的拼法）。 */
    public JsonObject trades() {
        JsonElement e = rest == null ? null : rest.get("villager_trade");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        if (rest != null) rest.add("villager_trade", o);
        return o;
    }

    /** 项目档里的**标签**：标签名 → {@code {type, values, replace}}（{@code values} / {@code replace} = 原版 {@code TagFile} 原样；
     * {@code type} = 写进哪个注册表目录：item / block / entity_type / damage_type / enchantment / villager_trade）。 */
    public JsonObject tags() {
        JsonElement e = rest == null ? null : rest.get("tags");
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        if (rest != null) rest.add("tags", o);
        return o;
    }

    /** 项目档里的**段B 三条**：段键 → 名字 → 原版 JSON 原样（键 = {@code advancement} / {@code enchantment} / {@code damage_type}，见 {@link VanillaJson#SECTIONS}）。 */
    public JsonObject vanillaSection(String key) {
        JsonElement e = rest == null ? null : rest.get(key);
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        if (rest != null) rest.add(key, o);
        return o;
    }

    public static String snapLine(String name, String value) {
        return name + ": " + value;
    }

    /**
     * 从快照的行列表里取某个槽位的值（{@link #snapLine} 的逆）；找不到 = 空串。
     *
     * <p>只匹配<b>「名字 + 冒号空格」前缀</b>，再用 substring 取剩下全部 ——
     * 所以值里带冒号、带等号、带方括号都不会串味（旧写法按第一个 {@code '='} 切，值里一有等号就整行对不上）。
     */
    public static String snapValue(java.util.List<String> lines, String name) {
        if (lines == null || name == null || name.isEmpty()) return "";
        String head = name + ": ";
        for (String line : lines) {
            if (line != null && line.startsWith(head)) return line.substring(head.length());
        }
        return "";
    }

    /**
     * 换一段脚本 —— {@code script} 是这条 record 里唯一需要「整体替换」的字段。
     *
     */
    public GameDefinition withScript(String newScript) {
        return new GameDefinition(name, desc, cards, decks, pieces, vars, stage,
                newScript, assets, areas, rest);
    }

    /** 换「已导入的组件」清单（copy-with，同 {@link #withScript}）。 */
    public GameDefinition withAssets(java.util.List<AssetDef> newAssets) {
        return new GameDefinition(name, desc, cards, decks, pieces, vars, stage,
                script, newAssets == null ? java.util.List.of() : newAssets, areas, rest);
    }

    /** 换「世界区域」清单（copy-with，#23）。 */
    public GameDefinition withAreas(java.util.List<AreaDef> newAreas) {
        return new GameDefinition(name, desc, cards, decks, pieces, vars, stage,
                script, assets, newAreas == null ? java.util.List.of() : newAreas, rest);
    }

    public GameDefinition withDesc(String newDesc) {
        return new GameDefinition(name, newDesc == null ? "" : newDesc, cards, decks, pieces, vars, stage,
                script, assets, areas, rest);
    }

    /**
     * 单张卡的定义（schema: components.cards[]）。
     * @param id 卡 id（游戏内唯一，如 {@code "c1"}；牌组 from 列表引用它）· @param art 正面美术 = 画板项目引用（{@code "组/项目名"}）；空串 = 显示卡背
     * @param back 背面样式名（资源里预留扩展位）· @param fields 属性字段值（Gson 对象原样透传，可 null）
     * @param fieldDefs 枚举型属性的可选值（{@code {"键": ["可选值", …]}}，可 null）—— 数字 / 文本 / 布尔不进这里（类型从值自推）
     * @param name 显示名（脚本里写 {@code name "红桃A"}）；空串 ⇒ {@link #display()} 退回 id
     */
    public record CardDef(String id, String art, String back, JsonObject fields, JsonObject fieldDefs,
            String name) {

        /**
         * 老形状（**资产名与显示名是同一个**）—— 老档走这条，调用点零改动
         * （同 {@link AssetDef} 那对构造的分工）。
         */
        public CardDef(String id, String art, String back, JsonObject fields, JsonObject fieldDefs) {
            this(id, art, back, fields, fieldDefs, "");
        }

        /**
         * 显示口一律用它：**有 name 用 name、没有退回 id**。
         *
         * <p>与 {@link AssetDef#ref()} 的分工正好相反 —— 那边 ref() = 引用名（资产名）、name() = 显示名；
         * 这里 id() 就是引用名（{@code card("…")} / 牌组都写它），本方法才是给人看的那一个。
         */
        public String display() {
            return name == null || name.isEmpty() ? id : name;
        }
    }

    /**
     * 卡 id → 卡面 / 卡背的**画板引用**（舞台摆牌用）。脚本只写卡 id，宿主展开时来这里换 —— 脚本不该知道画板路径。
     * 放在数据类上当**纯函数**是为了自检（宿主在 MC 侧跑不起来）；找不到卡 / 那一面没填 → 空串（调用方记日志 + 画占位，不掐局）。
     */
    public static String cardArt(java.util.List<CardDef> cards, String id, boolean back) {
        if (cards == null || id == null) return "";
        for (CardDef cd : cards) {
            if (cd.id().equals(id)) {
                String a = back ? cd.back() : cd.art();
                return a == null ? "" : a;
            }
        }
        return "";
    }

    /**
     * 牌组定义（schema: components.decks[]）。
     * @param id 牌组 id（如 {@code "main"}）· @param from 组牌来源：**卡 id 列表**（可重复）· @param shuffle 开局是否洗牌（真正洗牌在运行时的主持人）
     */
    public record DeckDef(String id, java.util.List<String> from, boolean shuffle) {
    }

    /**
     * 棋子实例定义（schema: components.pieces[]）—— 同一蓝图可绑多次、各有名字。
     * @param id 实例 id（玩法内唯一）· @param blueprint 蓝图引用（{@code "组/蓝图名"}）· @param name 显示名（可空串）
     * @param fields 属性字段值（同卡牌规则）· @param fieldDefs 枚举可选值定义（仅枚举需要）
     */
    public record PieceDef(String id, String blueprint, String name, JsonObject fields, JsonObject fieldDefs,
            double scale) {

        /**
         * 这一局用的**尺寸倍率**（{@code <= 0} 或不填 = 1 = 蓝图原尺寸）。**值越小棋子越大**（{@code 0.25} = 放大 4 倍）——
         * 它乘的是蓝图那份 {@code VoxelData.scale}，渲染侧算的是「每体素边长 = {@code 1/(16*scale)} 格」，所以整枚缩放不用动渲染。
         */
        public float effectiveScale(float blueprintScale) {
            return blueprintScale * (scale > 0 ? (float) scale : 1.0F);
        }
    }

    // ===== 数值（引擎 v0 第一段：vars）=====

    /**
     * @param base      物品类资产的**基底原版物品 id**（如 {@code minecraft:paper}）；非物品类空串
     * @param lore      组件 {@code minecraft:lore} 的**描述行**（逐行；空表 = 不写该组件）
     */
    public record AssetDef(String kind, String name, int w, int h, String px, JsonObject bp,
            String base, java.util.List<String> lore, String id) {

        /**
         * 老形状（**资产名与显示名是同一个**）—— 档里的美术 / 老对象走这条，调用点零改动。
         * 脚本声明来的对象走九参那条（段头 = 资产名，{@code name} = 显示名）。
         */
        public AssetDef(String kind, String name, int w, int h, String px, JsonObject bp,
                String base, java.util.List<String> lore) {
            this(kind, name, w, h, px, bp, base, lore, name);
        }

        /** 引用它时写的名字（资产名；老条目没有就退回显示名）—— 脚本里的 `give` / 候选▾ / hand_asset 都用它。 */
        public String ref() {
            return id == null || id.isEmpty() ? name : id;
        }

        /**
         * 物品类资产？（**自定义物品 = 对原版/已有物品的改造** ⇒
         * 「基底物品 + 组件覆盖」，不是注册新物品。外观用原版、不另做贴图。）
         */
        public boolean isItem() {
            return base != null && !base.isEmpty();
        }

        /** 读 {@code lore} 字符串数组（缺失 / 坏值 → 空表）。 */
        public static java.util.List<String> readLore(JsonObject o) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (o != null && o.has("lore") && o.get("lore").isJsonArray()) {
                for (var el : o.getAsJsonArray("lore")) {
                    if (el.isJsonPrimitive()) {
                        out.add(el.getAsString());
                    }
                }
            }
            return out;
        }

        /** 是不是「导入的组件」引用（前缀 {@code @}）。 */
        public static boolean isImportedKey(String key) {
            return key != null && key.startsWith("@");
        }

        /**
         * 把 {@code @游戏名/资产名} 拆成 {@code [游戏名, 资产名]}；格式不对 → {@code null}。
         * （游戏名里不带 {@code /}，所以按**第一个**斜杠切。）
         */
        public static String[] splitImportedKey(String key) {
            if (!isImportedKey(key)) {
                return null;
            }
            String body = key.substring(1);
            int slash = body.indexOf('/');
            if (slash <= 0 || slash == body.length() - 1) {
                return null;
            }
            return new String[]{body.substring(0, slash), body.substring(slash + 1)};
        }
    }

    /**
     * 脚本里的「资产引用」写法 → **资产名**（脚本一律用资产名引用对象）。
     *
     * <p>认这四种写法：{@code key1} / {@code @key1} / {@code 游戏名/key1} / {@code @游戏名/key1}
     * —— 后两种是老写法（还有 `place_piece` 那套 `@资产名`），读到就照常认，
     * **不逼人改脚本**。四处引用口（蓝图 / give·chest_set·bag_set / spawn_mob）共用这一个口径。
     */
    public static String assetNameOf(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim();
        if (k.startsWith("@")) {
            k = k.substring(1);
        }
        int slash = k.indexOf('/');
        return slash >= 0 ? k.substring(slash + 1).trim() : k;
    }

    /**
     * 按 **id** 找一个区域（没有 → null）—— 宿主（幽灵要烘快照）与客户端（收包后查快照）共用这一处。
     *
     * <p>只按 id 认：区域没有「显示名」那一套（id 就是脚本里写的那个名字，见 {@code AreaViewScreen}）。
     */
    public static AreaDef areaOf(java.util.List<AreaDef> areas, String id) {
        if (areas == null || id == null) {
            return null;
        }
        for (AreaDef a : areas) {
            if (id.equals(a.id())) {
                return a;
            }
        }
        return null;
    }

    /** 按名字找一个已导入的组件（没有 → null）。 */
    public static AssetDef assetOf(java.util.List<AssetDef> assets, String name) {
        if (assets == null || name == null) {
            return null;
        }
        // 第一遍按**资产名**（引用口径 = 声明段头那个名字：stone1 / key1）
        for (AssetDef a : assets) {
            if (name.equals(a.id())) {
                return a;
            }
        }
        for (AssetDef a : assets) {
            if (name.equals(a.name())) {
                return a;
            }
        }
        // 第二遍：剥掉色码再比 —— 资产名常带 &e 这类色前缀（显示名 = 资产名的口径），
        // 脚本里引用时让人背色码太苛刻（资产名 &e红桃A筹码、引用 红桃A筹码 就发不出来）。
        // ⚠ 不能加「引用带色才比」的门槛 —— 翻车场景正是**引用不带色、资产名带色**。
        String bare = stripColor(name);
        for (AssetDef a : assets) {
            if (bare.equals(stripColor(a.name()))) {
                return a;
            }
        }
        return null;
    }

    /** 剥色码：&0-&9 / &a-&f / § 同类（显示时才由 ColorText.mask 转成 §）。 */
    public static String stripColor(String s) {
        if (s == null || s.indexOf('&') < 0 && s.indexOf('\u00a7') < 0) {
            return s;
        }
        return s.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "");
    }

    /**
     * 世界区域定义（#23，拍板）：**自包含方块快照**（非坐标引用）——跨存档可用的根基。
     *
     * @param id        区域 id（游戏内唯一；将来脚本 region/tp 类原语按它引用——稳定标识）
     * @param sizeX/Y/Z 区域尺寸（快照容量；捕获时按所选区间定）
     * @param palette   调色板：出现过的非空气方块状态去重表（同 PieceData 那套口径）
     * @param blocks    逐格下标表（-1 = 空气），行序 x→y→z；空表 = 还没捕获（空壳声明合法）
     * @param cells     语义格：标了角色的格子（空表 = 一个都没标）
     */
    public record AreaDef(String id, int sizeX, int sizeY, int sizeZ,
                          java.util.List<net.minecraft.world.level.block.state.BlockState> palette,
                          java.util.List<Integer> blocks,
                          java.util.List<CellMark> cells) {
        /** 老口径构造（没有语义格）：捕获与各处老调用点用它 —— cells = 空表。 */
        public AreaDef(String id, int sizeX, int sizeY, int sizeZ,
                       java.util.List<net.minecraft.world.level.block.state.BlockState> palette,
                       java.util.List<Integer> blocks) {
            this(id, sizeX, sizeY, sizeZ, palette, blocks, java.util.List.of());
        }

        /**
         * 语义格：快照里某一格被作者标成的**角色**（{@code i/j/k} = 格内坐标）。
         * 角色是**文本**不是枚举 —— 「结构里的哪个位置」由作者在视口里标，引擎不预设角色表
         * （锚点 / 核心槽 / 产出箱只是常用名，脚本想用别的也认）。
         */
        public record CellMark(String role, int i, int j, int k) { }

        /** 还没捕获过（只有声明）——编辑/摆放动作对空壳区域不可用。 */
        public boolean captured() {
            return blocks != null && !blocks.isEmpty();
        }

        /** 语义格（空表兜底：老档 / 手改出的 null 一律当「一个都没标」）。 */
        public java.util.List<CellMark> marks() {
            return cells == null ? java.util.List.of() : cells;
        }

        /** 通配格的角色名：图案里这一格「是方块就行」（空气也放行）。 */
        public static final String WILD = "通配";

        /** 那一格标的是哪个角色（没标 → 空串）。同一格只留一个角色（见 {@link #withMark}）。 */
        public static String markAt(AreaDef a, int i, int j, int k) {
            for (CellMark m : a.marks()) {
                if (m.i() == i && m.j() == j && m.k() == k) {
                    return m.role();
                }
            }
            return "";
        }

        /** 那个角色的第一格（没标 / 角色写空 → null）。一格一个角色，多个格子标同一角色时取先标的那个。 */
        public static CellMark markOf(AreaDef a, String role) {
            if (role == null || role.isEmpty()) {
                return null;
            }
            for (CellMark m : a.marks()) {
                if (role.equals(m.role())) {
                    return m;
                }
            }
            return null;
        }

        /**
         * 标一格 / 取消标记（{@code role} 空 = 取消）：同一格只留一个角色（换标 = 覆盖，不留两条）。
         * 越界 → 原样返回（信任边界，同 {@link #withBlock}）。标在空气格上也合法（锚点可以是空地）。
         */
        public static AreaDef withMark(AreaDef a, String role, int i, int j, int k) {
            int sx = a.sizeX(), sy = a.sizeY(), sz = a.sizeZ();
            if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) {
                return a;
            }
            java.util.List<CellMark> out = new java.util.ArrayList<>();
            for (CellMark m : a.marks()) {
                if (m.i() != i || m.j() != j || m.k() != k) {
                    out.add(m);
                }
            }
            if (role != null && !role.isEmpty()) {
                out.add(new CellMark(role, i, j, k));
            }
            return new AreaDef(a.id(), sx, sy, sz, a.palette(), a.blocks(), java.util.List.copyOf(out));
        }

        /**
         * 逐格比图案（`match_shape` 的判定核心，纯逻辑）。
         *
         * <p>{@code worldKeys} / {@code worldSolid} = 世界那一盒里**逐格**的文本键与「算不算实心」
         * （行序同 {@code blocks}，读不到的格给 null / false）。三条口径：
         * <ol>
         *   <li><b>通配格</b>（{@link #WILD}）→ 随便什么都算数（空气也算）；</li>
         *   <li><b>快照是空气</b> → 只要那格**不是实心**（宿主给的判据 = 没有碰撞箱：火把 / 草 / 水都放行
         *       —— 不然边上插根火把就把整座结构弄失效）；</li>
         *   <li><b>快照是方块</b> → 那格的**方块**必须一样（比 id，**不比属性 / 朝向** —— 换材质 → 不成形；
         *       ⚠ 从「逐位比状态」改过来的：手工摆的箱子 / 楼梯朝向由玩家看的方向定，比状态就永远判不过）；</li>
         * </ol>
         * 空壳 / 形状对不上 / 数组长度不对 → 假（读的口径：给不出就当没有，不抛）。
         * 键的写法只有一处（{@link GameStore#blockToString}），快照那侧的期望键与世界的键同源。
         */
        public static boolean shapeMatches(AreaDef a, String[] worldKeys, boolean[] worldSolid) {
            int n = a.sizeX() * a.sizeY() * a.sizeZ();
            if (!a.captured() || a.blocks().size() != n
                    || worldKeys == null || worldSolid == null
                    || worldKeys.length != n || worldSolid.length != n) {
                return false;
            }
            int sx = a.sizeX(), sy = a.sizeY();
            for (int idx = 0; idx < n; idx++) {
                if (WILD.equals(markAt(a, idx % sx, (idx / sx) % sy, idx / (sx * sy)))) {
                    continue;                                  // 通配：哪管那格是什么
                }
                int pid = a.blocks().get(idx);
                if (pid < 0) {
                    if (worldSolid[idx]) {
                        return false;                          // 该空的地方多了一块实心 ⇒ 多一块，判 0
                    }
                    continue;
                }
                String want = pid < a.palette().size()
                        ? GameStore.blockToString.apply(a.palette().get(pid)) : null;
                // 比的是**方块**（`id[属性]` 剥掉属性只留 id），不是**方块状态**：
                // 状态逐位比会把「手工摆的箱子朝向不同 / 楼梯转了个方向」也算成不成形 ——
                // 那之后手工搭的结构永远判不过（玩家手里的方块朝向由他看的方向定），而验收只要求「材质对」。
                if (want == null || worldKeys[idx] == null
                        || !blockKeyId(want).equals(blockKeyId(worldKeys[idx]))) {
                    return false;                              // 少一块 / 换材质 ⇒ 判 0
                }
            }
            return true;
        }

        /**
         * 逐格方块 → 区域快照（唯一打包实现：捕获与自检共用同一套编码，避免两处漂移）。
         * 行序 = {@code idx = (z * sizeY + y) * sizeX + x}（**x 走得最快**，与
         * {@code BlockPos.betweenClosed} 的迭代序、以及棋子渲染器 {@code VoxelData} 的行序三者一致
         * ——区域快照正是靠这条才能直接喂给棋子渲染管线画出来）。
         *
         * @param cells 按行序排好的逐格状态；空气不进调色板，下标记 -1
         */
        public static AreaDef of(String id, int sizeX, int sizeY, int sizeZ,
                                 java.util.List<net.minecraft.world.level.block.state.BlockState> cells) {
            java.util.LinkedHashMap<net.minecraft.world.level.block.state.BlockState, Integer> palette =
                    new java.util.LinkedHashMap<>();
            java.util.List<Integer> ids = new java.util.ArrayList<>(cells.size());
            for (var st : cells) {
                ids.add(st.isAir() ? -1 : palette.computeIfAbsent(st, k -> palette.size()));
            }
            return new AreaDef(id, sizeX, sizeY, sizeZ,
                    java.util.List.copyOf(palette.keySet()), java.util.List.copyOf(ids));
        }

        // ===== 方块状态的「文本键」：palette 的盘上编码（只写 id 会丢楼梯朝向 / 半砖上下）=====
        // ⚠ 老档里存的是**裸 id**（属性已经丢了，救不回来）—— 读侧照旧吃得下（当默认态），但要朝向就得**重新捕获**。

        /**
         * 方块状态的**稳定文本键**：没有属性就是裸 {@code minecraft:oak_stairs}，
         * 有属性就是 {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight]}。
         *
         * <p>属性**按名字升序**（同一状态永远同一个串 ⇒ 同一区域的两次捕获逐字节相同、git diff 也干净）。
         * 纯文本、纯逻辑：自检直接跑它（{@code blockFromString} 那半边要查注册表，跑不了）。
         */
        public static String blockKey(String id, java.util.List<String> props) {
            if (props == null || props.isEmpty()) return id;
            java.util.List<String> ps = new java.util.ArrayList<>(props);
            ps.sort(null);                                   // 字典序：定序只为一件事——可复现
            return id + "[" + String.join(",", ps) + "]";
        }

        /** 从文本键取回方块 id（{@code id[a=1]} → {@code id}；裸 id 原样；null → 空串）。 */
        public static String blockKeyId(String key) {
            if (key == null) return "";
            int b = key.indexOf('[');
            return b < 0 ? key : key.substring(0, b);
        }

        /** 从文本键取回属性对（{@code id[a=1,b=x]} → {@code ["a=1","b=x"]}；裸 id / 坏串 → 空表，不抛）。 */
        public static java.util.List<String> blockKeyProps(String key) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (key == null) return out;
            int b = key.indexOf('[');
            int e = key.lastIndexOf(']');
            if (b < 0 || e <= b) return out;
            for (String one : key.substring(b + 1, e).split(",")) {
                String t = one.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }

        /**
         * 只留**看得见**的格子：六邻全实心的内部格 → 记 -1（空气）。不做的话 32³ 实心区域要提交 3 万次方块模型 ⇒ 帧率塌。
         * 只减不增、长度与调色板下标都不变（同一份 palette 继续可用）。
         * ponytail: 只做六邻剔除，没做遮挡剔除 / 分块 —— 大面积裸露表面仍逐格提交；真卡顿了再上分块 + 视锥剔除。
         */
        public static java.util.List<Integer> visibleOnly(int sizeX, int sizeY, int sizeZ,
                                                          java.util.List<Integer> blocks) {
            int n = sizeX * sizeY * sizeZ;
            if (blocks == null || blocks.size() != n) {
                return blocks == null ? java.util.List.of() : blocks;      // 形状对不上：原样返回（别猜）
            }
            java.util.List<Integer> out = new java.util.ArrayList<>(blocks);
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int x = 0; x < sizeX; x++) {
                        int i = (z * sizeY + y) * sizeX + x;
                        if (out.get(i) < 0) {
                            continue;
                        }
                        if (solid(blocks, sizeX, sizeY, sizeZ, x - 1, y, z)
                                && solid(blocks, sizeX, sizeY, sizeZ, x + 1, y, z)
                                && solid(blocks, sizeX, sizeY, sizeZ, x, y - 1, z)
                                && solid(blocks, sizeX, sizeY, sizeZ, x, y + 1, z)
                                && solid(blocks, sizeX, sizeY, sizeZ, x, y, z - 1)
                                && solid(blocks, sizeX, sizeY, sizeZ, x, y, z + 1)) {
                            out.set(i, -1);                                    // 包在实心里的格子看不见
                        }
                    }
                }
            }
            return java.util.List.copyOf(out);
        }

        /**
         * 只留第 {@code from..to} 层（含两端）的格子，其余当空气（-1）—— 视口「分层查看」。
         * ⚠ **顺序很重要**：先 onlyLayers 再 {@link #visibleOnly}。反了会留下「原本是内部格」的空洞（那些格已改成 -1）。
         * 只减不增、下标不变；范围盖满 / 形状对不上 → 原样返回。
         */
        public static java.util.List<Integer> onlyLayers(int sizeX, int sizeY, int sizeZ,
                                                          java.util.List<Integer> blocks, int from, int to) {
            int n = sizeX * sizeY * sizeZ;
            if (blocks == null || blocks.size() != n) {
                return blocks == null ? java.util.List.of() : blocks;
            }
            if (from <= 0 && to >= sizeY - 1) {
                return blocks;                                    // 本来就全要：别抄一遍
            }
            java.util.List<Integer> out = new java.util.ArrayList<>(blocks);
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    if (y >= from && y <= to) {
                        continue;                                 // 这一层看得见
                    }
                    for (int x = 0; x < sizeX; x++) {
                        out.set((z * sizeY + y) * sizeX + x, -1);  // 藏起来 = 空气
                    }
                }
            }
            return java.util.List.copyOf(out);
        }

        /**
         * **视口适配**：把「水平扫掠 √(sx²+sz²) / 竖直扫掠 sy·cos|p| + 水平·sin|p|」装进 (panelW × panelH)，返回每世界格多少屏幕像素（已乘 zoom）。
         * 公式来自原版 {@code StructurePreviewWidget.recomputeScale}（双轴各比一遍取更紧的，填充 0.95）—— 比「按最大边长一刀切」准：
         * 俯角一大，扁平区域的**竖直**投影反而比水平还大，只按水平算会被上下切掉。
         * @param pitchDeg 俯角（度，符号无关）
         */
        public static float pxPerBlock(float panelW, float panelH,
                                       float sizeX, float sizeY, float sizeZ,
                                       float pitchDeg, float zoom) {
            float swept = Math.max(1.0F, (float) Math.hypot(sizeX, sizeZ));
            float p = (float) Math.toRadians(Math.abs(pitchDeg));
            float vert = Math.max(1.0F, (float) (sizeY * Math.cos(p) + swept * Math.sin(p)));
            return Math.min(panelW * 0.95F / swept, panelH * 0.95F / vert) * zoom;
        }

        /**
         * **改一格**（编辑操作的唯一实现）—— 换方块 / 删方块（传空气）都走它。新方块已在调色板里就复用（不重复入表），
         * 不在就追加 —— 反复编辑同一区域不会让 palette 无限膨胀。传空气 = 记 -1（不进表）。越界 / 形状不符 → 原样返回。
         */
        public static AreaDef withBlock(AreaDef a, int index, net.minecraft.world.level.block.state.BlockState st) {
            int n = a.sizeX() * a.sizeY() * a.sizeZ();
            if (index < 0 || index >= n || a.blocks().size() != n) {
                return a;
            }
            java.util.List<Integer> ids = new java.util.ArrayList<>(a.blocks());
            java.util.List<net.minecraft.world.level.block.state.BlockState> pal =
                    new java.util.ArrayList<>(a.palette());
            if (st == null || st.isAir()) {
                ids.set(index, -1);
            } else {
                int found = pal.indexOf(st);
                if (found < 0) {
                    pal.add(st);
                    found = pal.size() - 1;
                }
                ids.set(index, found);
            }
            return new AreaDef(a.id(), a.sizeX(), a.sizeY(), a.sizeZ(),
                    java.util.List.copyOf(pal), java.util.List.copyOf(ids), a.marks());
        }

        /**
         * **两角之间的长方体**里要收的下标（升序）—— 范围选择（点两个角）用它。两角顺序任意；越界自动钳到区域内。
         * @param solidOnly true = 只收**实心格**（视口「忽略空气」勾上时的行为，默认）；false = 每一格都收（含空气，给「整框填充 / 清空」留着）。
         */
        public static int[] boxCells(AreaDef a, int i1, int j1, int k1, int i2, int j2, int k2,
                boolean solidOnly) {
            int sx = a.sizeX(), sy = a.sizeY(), sz = a.sizeZ();
            int lo0 = Math.max(0, Math.min(i1, i2)), hi0 = Math.min(sx - 1, Math.max(i1, i2));
            int lo1 = Math.max(0, Math.min(j1, j2)), hi1 = Math.min(sy - 1, Math.max(j1, j2));
            int lo2 = Math.max(0, Math.min(k1, k2)), hi2 = Math.min(sz - 1, Math.max(k1, k2));
            if (lo0 > hi0 || lo1 > hi1 || lo2 > hi2 || a.blocks().size() != sx * sy * sz) {
                return new int[0];
            }
            java.util.List<Integer> out = new java.util.ArrayList<>();
            for (int k = lo2; k <= hi2; k++) {
                for (int j = lo1; j <= hi1; j++) {
                    for (int i = lo0; i <= hi0; i++) {
                        int idx = (k * sy + j) * sx + i;
                        if (!solidOnly || a.blocks().get(idx) >= 0) {
                            out.add(idx);
                        }
                    }
                }
            }
            int[] arr = new int[out.size()];
            for (int n = 0; n < arr.length; n++) {
                arr[n] = out.get(n);
            }
            return arr;
        }

        /** **范围框整体平移一格**（大小不变）：返新两角 {@code [角1, 角2]}。越界 → {@code null}（贴边再推没意义，让调用方提示）；
         *  单角状态（{@code c2 < 0}）照样挪，第二个仍是 -1。算术走 {@link #shiftCells}，不另写一份。 */
        public static int[] shiftBox(AreaDef a, int c1, int c2, int di, int dj, int dk) {
            int sx = a.sizeX(), sy = a.sizeY(), sz = a.sizeZ();
            if (c1 < 0 || c1 >= sx * sy * sz) {
                return null;
            }
            int[] mv = shiftCells(a, c2 >= 0 ? new int[] { c1, c2 } : new int[] { c1 }, di, dj, dk);
            return mv == null ? null : new int[] { mv[0], c2 >= 0 ? mv[1] : -1 };
        }

        /**
         * **一组格下标整体平移一格**（「移动选择」）：返新下标（保持入参顺序）。任一格出区域 → {@code null}（**整个选择都不动**：挪一半最糟）；空集返空集。
         * ⚠ 必须**逐格按 (i,j,k) 重算**再拼回线性下标 —— 直接加 delta 会让最右一列 +1 绕进下一行左端（跨行错位，看着像整体歪了一格）。
         */
        public static int[] shiftCells(AreaDef a, int[] cells, int di, int dj, int dk) {
            int sx = a.sizeX(), sy = a.sizeY(), sz = a.sizeZ();
            int[] out = new int[cells.length];
            for (int n = 0; n < cells.length; n++) {
                int c = cells[n];
                if (c < 0 || c >= sx * sy * sz) {
                    return null;
                }
                int i = c % sx + di, j = (c / sx) % sy + dj, k = c / (sx * sy) + dk;
                if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) {
                    return null;
                }
                out[n] = (k * sy + j) * sx + i;
            }
            return out;
        }

        /**
         * **体素坐标 → 模型变体种子**（稳定：同格同貌、邻格有别）。画每一格前把种子**重播**成「由该格坐标算出的值」——
         * {@code setSeed} 把 RandomSource 拉回同一起点 ⇒ 同格恒定、邻格不同；murmur 收尾做雪崩，保证相邻坐标低位也全然不同。
         */
        public static long voxelSeed(int x, int y, int z) {
            long h = x * 3129871L ^ z * 116129781L ^ y * 42317861L;
            h = h * 6364136223846793005L + 1442695040888963407L;
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;                  // murmur3 收尾常量（hex 字面量超 Long.MAX 时按位取值）
            h ^= h >>> 33;
            h *= 0xc4ceb9fe1a85ec53L;
            return h ^ (h >>> 33);
        }

        /**
         * **射线从哪个面进入 (i,j,k) 这格**：返回 {@code [轴, 方向]}（轴 0=x 1=y 2=z；
         * 方向 = 该轴上的**外侧邻格偏移**：-1 = 从该轴负侧进来、+1 = 正侧），没进入 = null。
         *
         * <p>用途：在**空位放方块**（点中实心格的某个面 ⇒ 就往那个面外的空格放，同原版放置手感）。
         * 做法 = 标准平板相交取「最晚被满足的那个约束」：沿轴求与格子两个界面的交点参数 t，
         * 取 t 最大者即**最后跨过**的那面 = 进入面；外侧邻格方向 = 射线在该轴的行进方向取反。
         */
        public static int[] entryFace(float ox, float oy, float oz, float dx, float dy, float dz,
                                      int i, int j, int k) {
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-6F) {
                return null;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            float[] o = { ox, oy, oz }, d = { dx, dy, dz };
            int[] c = { i, j, k };
            int axis = -1, sign = 0;
            float best = -Float.MAX_VALUE;
            for (int a = 0; a < 3; a++) {
                if (Math.abs(d[a]) < 1.0E-9F) {
                    continue;
                }
                // 沿该轴进入本格要走到的那个界面：往正方向走 = 从该格下界进，反之从上界进
                float boundary = d[a] > 0 ? c[a] : c[a] + 1;
                float t = (boundary - o[a]) / d[a];
                if (t > best) {
                    best = t;
                    axis = a;
                    sign = d[a] > 0 ? -1 : 1;
                }
            }
            if (axis < 0 || best < -1.0E-4F) {
                return null;                               // 格子在射线后面（或射线平行于所有界面）
            }
            return new int[] { axis, sign };
        }

        /**
         * 立方体的 6 个面：每项 = 4 个角下标（**环绕顺序**，用于填充/描边）+ 体素空间法线。
         * 角下标编码 {@code c = x | y<<1 | z<<2}（x/y/z ∈ {0,1}）。
         *
         * <p>放这里而不是屏里：它是纯数据，自检能卡住「角序写错」——角序一错，扫描线填充会画成
         * 蝴蝶结（自交），而那种错**肉眼看不出**（自检断言见 `立方体面表`）。
         */
        public static final int[][] CUBE_FACES = {
            { 0, 2, 6, 4, -1, 0, 0 },      // -X
            { 1, 5, 7, 3, +1, 0, 0 },      // +X
            { 0, 4, 5, 1, 0, -1, 0 },      // -Y
            { 2, 3, 7, 6, 0, +1, 0 },      // +Y
            { 0, 1, 3, 2, 0, 0, -1 },      // -Z
            { 4, 6, 7, 5, 0, 0, +1 },      // +Z
        };

        /**
         * **体素射线**：从 (ox,oy,oz) 沿 (dx,dy,dz) 走，返回**第一个实心体素**的线性下标；-1 = 没打中。
         *
         * <p>坐标是**体素空间**（体素 (i,j,k) 占 [i,i+1)³，i∈[0,sx)…）——视口那边把模型空间点先平移
         * (sx/2, 0, sz/2) 再调它，这样本函数与「渲染器怎么摆体素」解耦，纯逻辑、能进自检。
         * 起点取**最靠眼睛那一端**（相机空间 ζ 最小处），返回的第一个实心就是看得见的那个。
         *
         * <p>ponytail: 定步长 0.25 格采样——足够挑方块，斜射时理论上可能漏掉一个角上被切得极薄的体素；
         * 真遇到「偶尔点不中」再换 Amanatides-Woo 的体素 DDA。
         */
        public static int rayVoxel(float ox, float oy, float oz, float dx, float dy, float dz,
                                   float maxDist, int sx, int sy, int sz,
                                   java.util.List<Integer> blocks) {
            if (blocks == null || blocks.size() != sx * sy * sz) {
                return -1;
            }
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-6F) {
                return -1;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            for (float d = 0.0F; d <= maxDist; d += 0.25F) {
                int i = (int) Math.floor(ox + dx * d);
                int j = (int) Math.floor(oy + dy * d);
                int k = (int) Math.floor(oz + dz * d);
                if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) {
                    continue;                                   // 盒外continue而不是break：斜射出去还会再进来
                }
                int idx = (k * sy + j) * sx + i;
                if (blocks.get(idx) >= 0) {
                    return idx;
                }
            }
            return -1;
        }

        /** 越界当成「空气」（区域边界外的格子看得见——边界格不能被剔除）。 */
        private static boolean solid(java.util.List<Integer> blocks,
                                     int sizeX, int sizeY, int sizeZ, int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                return false;
            }
            return blocks.get((z * sizeY + y) * sizeX + x) >= 0;
        }
    }

    public record VarDef(String id, String name, String scope, String type, JsonElement init) {
        /** 列表槽位（含旧数据的 kind=pool，读档时已归一）。 */
        public boolean isList() {
            return "list".equals(type) || "pool".equals(type);
        }
    }


    // ===== 舞台（引擎 v0 第三段：stage）=====

    /**
     * 舞台（schema: stage）——「这游戏长什么样」+「用哪种形态承载」。
     *
     *
     * <p>ui = 屏幕画布（v0 唯一实现）。
     */
    public static final class StageDef {
        /** 形态：{@code hud} = 常驻屏幕上的小窗｜{@code full} = 全屏界面（要玩家输入时用）。 */
        public static final String MODE_HUD = "hud", MODE_FULL = "full";

        private StageUi ui;
        private String mode = MODE_HUD;
        /** 舞台（schema/3）：一块块独立舞台（全屏 / HUD），由宿主按脚本的 screen 块派生。 */
        private final java.util.List<StageView> views = new java.util.ArrayList<>();
        /**
         * 脚本 {@code show(…)} 指定的「现在显示哪一块」—— **线口径专用，盘上不存**。
         *
         * <p>{@code currentFull} 为 null = 全屏什么都不显示（开局显示哪块**全由脚本控制**）；
         * {@code currentHud} 为 null = 看板回落第一块（老档零变化）。
         */
        private String currentFull, currentHud;
        /** 脚本 {@code hide()} 的收起计数（线口径专用）：客户端比对它决定"关掉全屏屏"这一次动作。 */
        private int hideSeq;
        /** 最近一次收的是哪块（hide("名") 点名；null = 全收 hide()）—— 客户端据此只关该关的。 */
        private String hiddenName;

        public String hiddenName() {
            return hiddenName;
        }

        public void hiddenName(String v) {
            this.hiddenName = v;
        }

        public StageDef(StageUi ui) {
            this.ui = ui;
        }

        public StageUi ui() {
            return ui;
        }

        public String currentFull() {
            return currentFull;
        }

        public void currentFull(String v) {
            this.currentFull = v;
        }

        public String currentHud() {
            return currentHud;
        }

        public int hideSeq() {
            return hideSeq;
        }

        public void hideSeq(int v) {
            this.hideSeq = v;
        }

        public void currentHud(String v) {
            this.currentHud = v;
        }

        /** 所有舞台（schema/3）；空 = 还没编过舞台（第 5 步的编辑器往这里加）。 */
        public java.util.List<StageView> views() {
            return views;
        }


        /** 形态（缺字段/非法值 = {@code hud}：老文件里根本没有这个字段）。 */
        public String mode() {
            return mode == null || mode.isBlank() ? MODE_HUD : mode;
        }


        /** 全屏形态？（客户端据此开局自动开屏） */
        public boolean isFull() {
            return MODE_FULL.equals(mode());
        }


        /** 只认 hud / full 两档（怪值一律当 hud）。 */
        public void setMode(String m) {
            this.mode = MODE_FULL.equals(m) ? MODE_FULL : MODE_HUD;
        }

        /** 空舞台（没有界面 = 纯世界玩法，或还没编界面）。 */
        public static StageDef empty() {
            return new StageDef(StageUi.empty());
        }
    }


    /**
     * 舞台组件（schema/3）：类型 + 几何 + 专有字段（原样装在 {@link #p()}）+ 子组件（只有 group 有）。
     *
     * <p>专有字段：{@code text}→{@code text} · {@code value}→{@code ref} · {@code input}→{@code action}/{@code hint} ·
     * {@code button}→{@code label}/{@code action}/{@code args} · {@code draw}→{@code board} · {@code panel}→{@code bg}/{@code color}。
     * 坐标与尺寸是<b>所在舞台的画布坐标</b>（group 不改变子组件坐标系：先简单，要相对坐标再加）。
     */
    public record Component(String id, String type, double x, double y, double w, double h,
                            JsonObject p, java.util.List<Component> children) {
        public String s(String key, String dft) {
            JsonElement e = p == null ? null : p.get(key);
            return e == null || e.isJsonNull() ? dft : e.getAsString();
        }

        public int i(String key, int dft) {
            JsonElement e = p == null ? null : p.get(key);
            return e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() ? dft : e.getAsInt();
        }
    }

    /**
     * 一块舞台（schema/3）。
     *
     * @param hud       承载：{@code false} = 全屏（可交互）· {@code true} = 常驻叠加层（只显示）
     * @param w        画布宽（默认 320）
     * @param h        画布高（默认 180）
     * @param hudX     hud 承载时的屏幕横向位置（相对屏幕的比例 0~1）· {@code hudY} 同理
     * @param hudW     hud 承载时那块板占屏幕的宽（比例 0~1）· {@code hudH} 同理 —— 三个/四个都来自脚本里
     *                 {@code screen hud} 的 {@code place(…)}，没写就是缺省那组值（{@link #DEF_HUD_X} 等）
     * @param clickable hud 承载时：<b>打开聊天框（有指针）就能点</b>台上的按钮/输入
     * @param bg        舞台铺底那一层（脚本 {@code bg("…")}，加）：空串 = 没写（运行时用缺省 40% 黑）。
     *                  全屏那块 = 整块背景 · 看板那块 = 板的底色。颜色串后两位可带透明度。
     */
    public record StageView(String id, String name, String bg, boolean hud, double w, double h,
                            double hudX, double hudY, double hudW, double hudH,
                            boolean clickable, java.util.List<Component> components) {
        /** 看板那块没写 {@code place(…)} 时的缺省摆位（屏幕比例）—— 与旧的写死值逐字一致：不缺省就不变。 */
        public static final double DEF_HUD_X = 0.66, DEF_HUD_Y = 0.05, DEF_HUD_W = 0.45, DEF_HUD_H = 0.35;

    }


    /**
     * 屏幕画布：一块 {@code w×h} 像素的<b>逻辑</b>画布，框按坐标摆在上面。
     *
     *
     * @param w     画布宽（逻辑像素）
     * @param h     画布高
     * @param boxes 框列表（按顺序画：后面的盖前面的，v0 无显式层级）
     * @param bg    舞台铺底那一层（脚本的 {@code bg("…")}，加）：空串 = 没在脚本里指定，
     *              运行时就画缺省那一层 40% 黑（{@link #DEF_BG}）
     */
    public record StageUi(int w, int h, java.util.List<BoxDef> boxes, String bg) {
        /** 没写 {@code bg(…)} 时那块铺底 —— 与老版写死的值逐字一致（40% 黑）。 */
        public static final int DEF_BG = 0x40000000;

        /** 默认画布（320×180 = 16:9，够放题面 + 分数）。 */
        public static StageUi empty() {
            return new StageUi(320, 180, new java.util.ArrayList<>(), "");
        }

        /**
         * 铺底那一层的 ARGB：脚本写了就用它（颜色串后两位可带透明度），没写 / 认不出 = 缺省那层 40% 黑。
         *
         * <p>用 {@link BoxStyle#argbOr} 而不是 {@code argb}：后者会把「认不出」补成**不透明**，
         * 那块缺省的 40% 黑就变成纯黑了（铺上去世界全黑，一眼就看出不对）。
         */
        public int bgArgb() {
            return BoxStyle.argbOr(bg, DEF_BG);
        }
    }

    /**
     * 框（schema: stage.ui.boxes[]）——界面与棋盘<b>共用的唯一元素</b>。
     *
     * <p>操作模型照 PPT：在画布上拖/拉角定位置尺寸，右键改内容与样式。
     * v0 里框只显示不交互（没有 action：输入走 {@code /tablegame act}），
     * 也没有 z 层级/旋转——按列表顺序画即可。
     *
     * @param id      框 id，段内唯一
     * @param x       左上角 x（逻辑画布坐标）
     * @param y       左上角 y
     * @param w       宽
     * @param h       高
     * @param content 框里显示什么
     * @param style   底色与文字色
     */
    public record BoxDef(String id, double x, double y, double w, double h,
                         BoxContent content, BoxStyle style) {
    }

    /**
     * 框的内容。
     *
     * @param type  {@code none} = 空框（只用底色当面板）｜{@code text} = 固定文本｜
     *              {@code number} = 绑变量显示当前值｜{@code image} = 框内嵌<b>本局临时画板</b>｜
     *              {@code input} = <b>输入框</b>（全屏承载下是能打字的真输入框，见 StageScreen；
     *              HUD 承载收不到键盘，只画成静态占位）；{@code countdown} = <b>倒计时</b>（显示当前阶段的
     *              剩余秒数，第3步；不绑变量，没在计时显示 {@code --}）；{@code cardArea} 仍是预留类型
     * @param value type=text 时的文本内容；type=input 时 = 输入框的占位提示（可空）
     * @param var   type=number 时绑的变量 id（显示它的当前值，如分数 b2 → score）
     */
    public record BoxContent(String type, String value, String var) {
    }

    /**
     * 框的样式：颜色串 {@code #RRGGBB}（不带 alpha，照旧）或 {@code #RRGGBBAA}（后两位 = 透明度，CSS 习惯——
     * 在原色的基础上追加两位，不用改已经填好的 6 位写法）。
     *
     * @param bg    底色
     * @param color 文字色
     */
    public record BoxStyle(String bg, String color) {
        /**
         * 颜色串 → ARGB 整数（{@code fill} 直接能用）。
         *
         * <ul>
         *   <li>{@code #RRGGBB} —— 不透明（alpha 补 0xFF），兼容全部老数据
         *   <li>{@code #RRGGBBAA} —— 带透明度（AA=00 全透明 … FF 不透明）
         *   <li>其它（空串/长度不对/非法十六进制）—— 回兜底色（不透明）
         * </ul>
         *
         * <p>放在这里而不是 UI 工具类里：它是<b>样式字段的语义</b>（bg 这个串到底怎么读），
         * 而且纯逻辑——自检脚本能直接断言它。
         */
        public static int argb(String hex, int dflt) {
            Integer v = parse(hex);
            return v == null ? (0xFF000000 | dflt) : v;
        }

        /**
         * 同 {@link #argb}，但「没写 / 认不出」时**原样**给 {@code dfltArgb}（不硬补不透明）——
         * 给**本身带透明度的兜底色**用（舞台铺底那层缺省是 40% 黑，补成不透明就成纯黑了）。
         */
        public static int argbOr(String hex, int dfltArgb) {
            Integer v = parse(hex);
            return v == null ? dfltArgb : v;
        }

        /** 颜色串里的透明度（0~255）；没写 / 认不出 = 255（不透明）—— 属性栏那格「透」读它。 */
        public static int alphaOf(String hex) {
            Integer v = parse(hex);
            return v == null ? 255 : (v >>> 24);
        }

        /**
         * 换个透明度：{@code #RRGGBB} 或 {@code #RRGGBBAA} —— 属性栏那格「透」写回时用它。
         * {@code alpha} 给 255 就写回 6 位的老写法（别让满屏颜色串都挂一个没用的 {@code FF}）。
         */
        public static String withAlpha(String hex, int alpha) {
            Integer v = parse(hex);
            int rgb = v == null ? 0 : (v & 0xFFFFFF);
            int a = Math.max(0, Math.min(255, alpha));
            return a >= 255 ? String.format("#%06X", rgb) : String.format("#%06X%02X", rgb, a);
        }

        /** 颜色串 → ARGB 整数；没写 / 位数不对 / 非法十六进制 = {@code null}（兜什么由调用方定）。 */
        private static Integer parse(String hex) {
            if (hex == null) return null;
            String t = hex.trim();
            if (t.startsWith("#")) t = t.substring(1);
            try {
                if (t.length() == 6) return 0xFF000000 | Integer.parseInt(t, 16);
                if (t.length() == 8) {
                    // 前 6 位颜色 + 后 2 位 alpha（CSS 顺序）
                    int rgb = Integer.parseInt(t.substring(0, 6), 16);
                    int a = Integer.parseInt(t.substring(6, 8), 16);
                    return (a << 24) | rgb;
                }
            } catch (NumberFormatException ignored) {
                // 落到 null
            }
            return null;
        }
    }



}
