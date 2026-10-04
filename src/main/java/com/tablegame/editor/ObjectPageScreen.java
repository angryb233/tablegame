package com.tablegame.editor;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;
import com.tablegame.script.edit.ScriptEdit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.card.CardEditScreen;
import com.tablegame.editor.card.CardGenScreen;
import com.tablegame.editor.item.ItemBasePicker;
import com.tablegame.editor.item.ItemEditScreen;
import com.tablegame.editor.pack.AssetStore;
import com.tablegame.net.AssetPackets;
import com.tablegame.piece.PieceEditScreen;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;

/**
 * 「对象」工具页 —— 编辑器里所有**能被脚本叫出名字的东西**的统一分类入口：**卡牌 / 棋子 / 物品 / 方块 / 实体**
 * （模型归棋子 —— 它是棋子的外观，不是独立对象）。
 * 三条纪律：① **真源不动** —— 卡牌 / 棋子仍是脚本声明（{@code card 名 { }} / {@code piece 名 { }}）、物品 / 方块 / 实体是项目档 {@code assets} 段里那条；本页**不存第二份**，只「统一列出 + 打开各类自己的编辑屏」。
 * ② **旧页的去向**：「卡牌」「棋子」两页已从左栏退休并删除，本页接管（加卡 / 批量生成 / 生成牌堆变量 / 删除带引用守卫都在列表行与底部按钮上；单条编辑走各自的编辑屏）。
 * ③ **零新数据、零引擎改动**。
 */
public class ObjectPageScreen extends Screen implements EditorToolScreen {
    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    private static final String KIND_CARD = "卡牌", KIND_PIECE = "棋子", KIND_ITEM = "物品",
            KIND_BLOCK = "方块", KIND_ENTITY = "实体", KIND_TEXT = "文本";   // 文本对象期7 片4（无基底）
    /** 第二列页签（分类口径就在这里）。 */
    private static final String[] KINDS = {KIND_CARD, KIND_PIECE, KIND_ITEM, KIND_BLOCK, KIND_ENTITY, KIND_TEXT};

    /** 玩法编辑器（包私有：同包工具屏直接读写 def）。 */
    final GameEditorScreen parent;
    /** 当前种类页签。 */
    private String kind = KIND_CARD;
    /** 行的命中框 + 该行右键菜单的开法（每行一个）—— 控件收不到右键，得在 mouseClicked 里自己命中。 */
    private final java.util.List<int[]> rowRects = new java.util.ArrayList<>();
    private final java.util.List<Runnable> rowMenus = new java.util.ArrayList<>();
    /** 对象行滚动（与卡牌 / 棋子页共用同一件：一屏 8 行）。 */
    private final ListScroll scroll = new ListScroll(8);
    /** 「挑基底方块」视图：可滚动 + 可搜索的原版方块清单（复用组件库那件）。 */
    private final ItemBasePicker basePicker = new ItemBasePicker();
    private boolean pickingBase;
    /** 「挑基底」这次挑哪一类（方块 / 生物 / 原版物品三条都从这一个清单件挑）。 */
    private static final int PICK_BLOCK = 0, PICK_ENTITY = 1, PICK_ITEM = 2;
    private int pickMode = PICK_BLOCK;
    /** 对象行的**右键选项**菜单（复用组件库那件右键菜单件）：编辑 / 删除（每行的两项在这里现开）。 */
    private final AssetMenu menu = new AssetMenu();

    public ObjectPageScreen(GameEditorScreen parent) {
        super(Component.literal("对象"));
        this.parent = parent;
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        clearWidgets();
        rowRects.clear();
        rowMenus.clear();
        // 第一列：编辑器左栏（当前页「对象」高亮）；第二列：对象自己的种类页签
        EditorRail.build(parent, "对象", this::addRenderableWidget);
        EditorRail.buildGenericAt(EditorRail.LEFT, KINDS, kind, k -> {
            kind = k;
            pickingBase = false;                          // 换类 = 关掉「挑基底」视图（不然新类上盖着那张清单）
            pickMode = PICK_BLOCK;
            scroll.setTotal(0);
            clearWidgets();
            init();
        }, this::addRenderableWidget);

        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 12);
        if (pickingBase) {
            buildBasePicker(x, w);                           // 「挑基底方块」视图（走它时下面的一列类别不摆）
        } else {
            switch (kind) {
                case KIND_CARD -> buildCards(x, w);
                case KIND_PIECE -> buildPieces(x, w);
                case KIND_ITEM -> buildItems(x, w);
                case KIND_BLOCK -> buildBlocks(x, w);
                case KIND_ENTITY -> buildEntities(x, w);
                case KIND_TEXT -> buildTexts(x, w);          // 文本对象（期7 片4：没有基底）
            }
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, Math.min(w, 200), 22, "返回总览", this::back));
    }

    /** 滚动条：轨道 = 行区右边那一条。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建行（true = 这一下被条吃掉，别穿透到后面的按钮）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            clearWidgets();
            init();
        }
        return true;
    }
    private void buildCards(int x, int w) {
        var cards = parent.def.cards();
        scroll.setTotal(cards.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            int idx = scroll.index(row);
            GameDefinition.CardDef c = cards.get(idx);
            // #19 对账：这一行就写着「脚本里几处在用」（与退休的卡牌页同一口径，搬过来不丢）
            var refs = ScriptEdit.declRefs(parent.def.script(), "card", c.id());
            // 显示口：**有 name 用 name、没有退回 id**；显示名与资产名不同时两个都带上
            String label = c.display() + (c.display().equals(c.id()) ? "" : " · " + c.id())
                    + (c.art().isBlank() ? "（无卡面）" : " · " + c.art())
                    + (refs.isEmpty() ? "" : "　· 在用 " + refs.size() + " 处");
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new CardEditScreen(this, parent, idx)),
                    () -> deleteDecl("card", c.id(), "卡牌"));
            y += 24;
        }
        if (cards.isEmpty()) {
            empty(x, w, y, "（还没有卡牌 —— 点下面「＋ 添加卡牌」，或脚本里写 card 名字 { }）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加卡牌", this::addCard));
        addRenderableWidget(DrawBoardMenuUi.button(x + 144, y, 136, 24, "批量生成…", () ->
                Minecraft.getInstance().setScreen(new CardGenScreen(parent, this))));
        addRenderableWidget(DrawBoardMenuUi.button(x, y + 30, Math.min(w, 280), 20, "生成牌堆变量（这批卡）",
                this::genDeckVar));
    }

    /**
     * 棋子（真源 = 脚本 {@code piece 声明}；外观 = 模型美术，归这里不单列一栏）：
     * 点行进编辑屏；行右「删除」= 删那段声明（两击确认 + 引用守卫）；底部「＋ 添加棋子」（从退休的棋子页搬来）。
     */
    private void buildPieces(int x, int w) {
        var pieces = parent.def.pieces();
        scroll.setTotal(pieces.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            int idx = scroll.index(row);
            GameDefinition.PieceDef pc = pieces.get(idx);
            var refs = ScriptEdit.declRefs(parent.def.script(), "piece", pc.id());
            String label = (pc.name().isBlank() ? pc.blueprint() : pc.name() + " · " + pc.blueprint())
                    + (refs.isEmpty() ? "" : "　· 在用 " + refs.size() + " 处");
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new PieceEditScreen(this, parent, idx)),
                    () -> deleteDecl("piece", pc.id(), "棋子"));
            y += 24;
        }
        if (pieces.isEmpty()) {
            empty(x, w, y, "（还没有棋子 —— 点下面「＋ 添加棋子」，或脚本里写 piece 名字 { }）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加棋子", this::addPiece));
    }

    /**
     * 物品（真源 = 项目档 {@code assets} 段，kind = 自定义物品；库名写 {@code @游戏名} = 项目自己那份）。
     *
     */
    private void buildItems(int x, int w) {
        var items = assetsOfKind(AssetStore.KIND_ITEM);
        scroll.setTotal(items.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            GameDefinition.AssetDef a = items.get(scroll.index(row));
            String label = a.name() + (a.ref().equals(a.name()) ? "" : " · " + a.ref())
                    + (a.base() == null || a.base().isEmpty() ? "" : " · " + a.base());
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new ItemEditScreen(this,
                    "@" + parent.gameName(), a.ref(), a.base(), a.name(), a.lore(), parent)),
                    () -> deleteAsset("item", "物品", a));
            y += 24;
        }
        if (items.isEmpty()) {
            empty(x, w, y, "（本项目还没有自定义物品 —— 点下面「＋ 添加物品」挑一个原版物品当基底）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加物品", this::pickItemBase));
    }

    /** 方块：真源 = 项目档 {@code assets} 段（kind = 自定义方块）。行 = 显示名 · 基底方块 id，点行进**同一个编辑屏**（{@link ItemEditScreen} 按基底 id 认「方块 / 物品」）；
     *  行右「删除」= 从项目档删掉这一条。创建入口长在这里（组件页没有方块栏）—— 对象页是方块对象**唯一**的家。 */
    private void buildBlocks(int x, int w) {
        var blocks = assetsOfKind(AssetStore.KIND_BLOCK);
        scroll.setTotal(blocks.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            GameDefinition.AssetDef a = blocks.get(scroll.index(row));
            String label = a.name() + (a.ref().equals(a.name()) ? "" : " · " + a.ref())
                    + (a.base() == null || a.base().isEmpty() ? "" : " · " + a.base());
            // 「触发…」搬进编辑页的【条件 / 事件】栏—— 行上不再单占一枚按钮（口径一个不少）
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new ItemEditScreen(this,
                    "@" + parent.gameName(), a.ref(), a.base(), a.name(), a.lore(), parent)),
                    () -> deleteAsset("block", "方块", a));
            y += 24;
        }
        if (blocks.isEmpty()) {
            empty(x, w, y, "（本项目还没有自定义方块 —— 点下面「＋ 添加方块」挑一个原版方块当基底）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加方块", this::pickBlockBase));
    }

    /** 实体：真源 = 项目档 {@code assets} 段（kind = 自定义实体）。行 = 显示名 · 基底原版实体 id，点行进**同一个编辑屏**。
     *  脚本侧用它 = 动作卡【放原版生物】生成 {@code spawn_mob("@游戏名/资产名", x, y, z)} —— 一条原语兼管「生成 + 挪位置」，宿主按名字记账，局末收掉。 */
    private void buildEntities(int x, int w) {
        var ents = assetsOfKind(AssetStore.KIND_ENTITY);
        scroll.setTotal(ents.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            GameDefinition.AssetDef a = ents.get(scroll.index(row));
            String label = a.name() + (a.ref().equals(a.name()) ? "" : " · " + a.ref())
                    + (a.base() == null || a.base().isEmpty() ? "" : " · " + a.base());
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new ItemEditScreen(this,
                    "@" + parent.gameName(), a.ref(), a.base(), a.name(), a.lore(), parent)),
                    () -> deleteAsset("entity", "实体", a));
            y += 24;
        }
        if (ents.isEmpty()) {
            empty(x, w, y, "（本项目还没有实体对象 —— 点下面「＋ 添加实体」挑一种原版生物当基底）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加实体", this::pickEntityBase));
    }

    /** 文本对象：真源 = 脚本 {@code text 资产名 { … }}（kind = 自定义文本）。行 = 显示名 · 资产名 · 正文首行（有标记就写「点它 → pick 标记」），双击进同一个编辑页（摆【正文】【标记】两栏）。
     *  ⚠ **文本没有基底**（它不是物品、也不摆进世界）⇒ 创建走不要求基底的 {@link #addText()}。脚本侧用它 = {@code say_mark(谁, "资产名")} 发一条可点提示，
     *  玩家点它回投一次 {@code on pick}（内建值 {@code pick} = 这条文本的 {@code mark}）。 */
    private void buildTexts(int x, int w) {
        var texts = assetsOfKind(AssetStore.KIND_TEXT);
        scroll.setTotal(texts.size());
        int y = 52;
        for (int row = 0; row < scroll.rows(); row++) {
            GameDefinition.AssetDef a = texts.get(scroll.index(row));
            String body = a.lore().isEmpty() ? "（还没写正文）" : a.lore().get(0);
            String label = a.name() + (a.ref().equals(a.name()) ? "" : " · " + a.ref()) + " · " + body
                    + (markOf(a).isEmpty() ? "" : "　· 点它 → pick " + markOf(a));
            row(x, w, y, label, () -> Minecraft.getInstance().setScreen(new ItemEditScreen(this,
                    "@" + parent.gameName(), a.ref(), "", a.name(), a.lore(), parent, markOf(a))),
                    () -> deleteAsset("text", "文本", a));
            y += 24;
        }
        if (texts.isEmpty()) {
            empty(x, w, y, "（本项目还没有文本对象 —— 点下面「＋ 添加文本」建一条，正文 / 标记在编辑页里写）");
            y += 24;
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, y, 136, 24, "+ 添加文本", this::addText));
    }

    /** 一条文本对象的**点击标记**（声明里的 {@code mark}，收在 bp 里；没写 = 空串 = 发出去也不可点）。 */
    private static String markOf(GameDefinition.AssetDef a) {
        return a.bp() != null && a.bp().has("mark") && a.bp().get("mark").isJsonPrimitive()
                ? a.bp().get("mark").getAsString() : "";
    }

    /**
     * 【＋ 添加文本】= **不要求基底**的创建路径（文本对象本就没基底，硬塞一个假 base 是假动作）：
     * 建一条 {@code text tN { } }，显示名先给资产名（有 name 用 name、没 name 退回 id），
     * 正文 / 标记留空 → 直接开编辑页去写。
     */
    private void addText() {
        String id = freshName("t");            // 默认 t / t2 / t3…（“text”自己就是声明动词，当名字容易看糊）
        String code = parent.def.script();
        ScriptEdit.Result r = ScriptEdit.addDecl(code, "text", id);
        if (r.text().equals(code)) {
            DrawBoardMenuUi.msg("[文本] " + r.note());
            return;
        }
        code = ScriptEdit.setDeclField(r.text(), "text", id, "name", ScriptEdit.strCode(id)).text();
        String err = parent.applyScript(code);
        if (!err.isEmpty()) {
            DrawBoardMenuUi.msg("[文本] " + err);
            return;
        }
        Minecraft.getInstance().setScreen(new ItemEditScreen(this, "@" + parent.gameName(), id, "", id,
                java.util.List.of(), parent, ""));
    }

    /** 【＋ 添加实体】第一步：进「挑基底生物」视图（同一个清单件，候选换成刷怪蛋）。 */
    private void pickEntityBase() {
        pickingBase = true;
        pickMode = PICK_ENTITY;
        clearWidgets();
        init();
    }

    /** 【＋ 添加物品】第一步：进「挑基底物品」视图（同一个清单件，**不滤** = 原版物品全集）。 */
    private void pickItemBase() {
        pickingBase = true;
        pickMode = PICK_ITEM;
        clearWidgets();
        init();
    }

    /** 挑基底清单点中一行 → 按这次挑的是哪一类分流（方块 / 生物 / 物品）。 */
    private void addBase(String baseId) {
        switch (pickMode) {
            case PICK_ENTITY -> addEntity(baseId);
            case PICK_ITEM -> addItem(baseId);
            default -> addBlock(baseId);
        }
    }

    /** 挑好一种原版生物 → 建一条**自定义实体**（进项目自己那份，外观 / 行为用原版）。清单里给的是**刷怪蛋**物品 id，基底要的是**实体 id** ——
     *  原版约定就是「id 去掉 {@code _spawn_egg}」（26.x 刷怪蛋的实体类型走数据组件，直接取要多绕一层）。
     *  反推出来**当场验一次**（{@code EntityType.byString}）—— 验不过就提示换一个，**不静默建一个假对象**。 */
    private void addEntity(String eggId) {
        String base = ScriptEdit.entityIdOfEgg(eggId);       // 纯函数：刷怪蛋 id → 生物 id（认不出给空串）
        if (base.isEmpty() || net.minecraft.world.entity.EntityType.byString(base).isEmpty()) {
            DrawBoardMenuUi.msg("[实体] 这个刷怪蛋认不出对应生物（" + eggId + "）—— 换一个");
            return;
        }
        createObject("entity", base);                     // 不再弹命名框：直接建默认名的声明 + 开编辑页
    }

    /** 【＋ 添加方块】第一步：进「挑基底方块」视图（复用组件库那件搜索清单，只列能当方块的物品）。 */
    private void pickBlockBase() {
        pickingBase = true;
        pickMode = PICK_BLOCK;
        clearWidgets();
        init();
    }

    /** 「挑基底」视图里说「原版 X」的那个 X（也是标题的来源）。 */
    private String pickWord() {
        return switch (pickMode) {
            case PICK_ENTITY -> "生物";
            case PICK_ITEM -> "物品";
            default -> "方块";
        };
    }

    /** 「挑基底」视图的标题（按这次挑哪一类）。 */
    private String pickTitle() {
        return "挑基底" + pickWord();
    }

    /** 「挑基底」视图：只摆搜索框（行自绘，见 {@link ItemBasePicker}）。 */
    private void buildBasePicker(int x, int w) {
        // 候选按这次挑哪一类走同一个清单件：生物 = **刷怪蛋**（原版图标 + 可搜索，一个蛋对着一种生物）；
        // 方块 = 能当方块的物品（水/火这类拿不到手的滤掉）；物品 = **不滤**（原版物品全集，与旧「组件」页同口径）。
        switch (pickMode) {
            case PICK_ENTITY -> basePicker.setFilter(i -> i instanceof net.minecraft.world.item.SpawnEggItem);
            case PICK_ITEM -> basePicker.setFilter(null);
            default -> basePicker.setFilter(i -> i instanceof BlockItem);
        }
        basePicker.reset();
        basePicker.build(x, 40, w, height - 40 - 44 - 20, this::addRenderableWidget, null);
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 40, Math.max(80, w), 22, "取消（回列表）", () -> {
            pickingBase = false;
            pickMode = PICK_BLOCK;
            clearWidgets();
            init();
        }));
    }

    /** 挑好基底 → 直接建一条默认名的声明，然后开编辑页去改（不弹命名框）。 */
    private void addBlock(String baseId) {
        createObject("block", baseId);
    }

    /** 物品同一条路（新建物品也从原版物品做基底）。 */
    private void addItem(String baseId) {
        createObject("item", baseId);
    }

    /**
     * 建一条对象声明（**新建直开编辑页**）—— 默认资产名 = 基底短名（占了就短名2 / 短名3…）、
     * 显示名同默认；建完立刻开编辑页，资产名 / 显示名 / 描述都在那一页里设（不再弹两层命名框）。
     */
    private void createObject(String kind, String baseId) {
        String what = kind.equals("block") ? "方块" : kind.equals("entity") ? "实体" : "物品";
        String shortId = baseId.substring(baseId.indexOf(':') + 1);
        String id = freshName(shortId);
        String code = parent.def.script();
        ScriptEdit.Result r = ScriptEdit.addDecl(code, kind, id);
        if (r.text().equals(code)) {
            DrawBoardMenuUi.msg("[" + what + "] " + r.note());
            return;
        }
        code = r.text();
        code = ScriptEdit.setDeclField(code, kind, id, "name", ScriptEdit.strCode(shortId)).text();
        code = ScriptEdit.setDeclField(code, kind, id, "base", ScriptEdit.strCode(baseId)).text();
        String err = parent.applyScript(code);
        if (!err.isEmpty()) {
            DrawBoardMenuUi.msg("[" + what + "] " + err);
            return;
        }
        pickingBase = false;
        pickMode = PICK_BLOCK;
        Minecraft.getInstance().setScreen(new ItemEditScreen(this, "@" + parent.gameName(), id, baseId,
                shortId, java.util.List.of(), parent));
    }

    /**
     * 删一行对象：**脚本声明优先** —— 声明里有它就走删声明（带引用守卫，同卡牌 / 棋子那套）；
     * 声明里没有它（组件库导入进来的老档条目）才走老的档删除包。
     * 两边都走对，删的才真是你看见的那一条。
     */
    private void deleteAsset(String kind, String what, GameDefinition.AssetDef a) {
        String src = parent.def.script();
        var refs = ScriptEdit.declRefs(src, kind, a.ref());
        if (!refs.isEmpty()) {
            DrawBoardMenuUi.msg("[" + what + "] 脚本里还有 " + refs.size() + " 处在用「" + a.ref()
                    + "」（第 " + refs.get(0) + " 行…），先去脚本里改掉再删");
            return;
        }
        ScriptEdit.Result r = ScriptEdit.removeDecl(src, kind, a.ref());
        if (!r.text().equals(src)) {
            String err = parent.applyScript(r.text());
            DrawBoardMenuUi.msg("[" + what + "] " + (err.isEmpty() ? r.note() : err));
            return;
        }
        ClientPacketDistributor.sendToServer(new AssetPackets.DeleteAssetPayload(
                "@" + parent.gameName(), a.name()));
    }

    /** 一个还没被占用的资产名（基底名 / 基底名2 / 基底名3…）—— 免得两条对象撞同一个名字。 */
    private String freshName(String base) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (GameDefinition.AssetDef a : parent.def.assets()) {
            used.add(a.ref());
        }
        String n = base;
        int i = 2;
        while (used.contains(n)) {
            n = base + i++;
        }
        return n;
    }

    /** 一个对象行（样式照卡牌 / 棋子页的行）：**双击**进它自己的编辑屏；**右键**开选项（编辑 / 删除）。 */
    private void row(int x, int w, int y, String label, Runnable onEdit, Runnable onDelete) {
        int rw = Math.min(w, 320);
        long[] last = new long[1];                       // 这一行「上次点击的时刻」（双击判定：两下都在 400ms 内）
        addRenderableWidget(DrawBoardMenuUi.button(x, y, rw, 20,
                ColorText.mask(DrawBoardMenuUi.ellipsis(label, 28)), () -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] <= 400) {
                        last[0] = 0;
                        onEdit.run();                    // 双击 → 进编辑页
                    } else {
                        last[0] = now;                   // 单击 → 只是选中一下（点一下不会误进编辑页）
                    }
                }));
        if (onDelete != null) {
            int[] r = {x, y, rw, 20};
            rowRects.add(r);
            // 右键菜单开在行**下面**一点：开在行上会盖住刚点的那一行
            rowMenus.add(() -> menu.open(width, height, x, y + 22, java.util.List.of("编辑", "删除"),
                    java.util.List.of(onEdit, onDelete)));
        }
    }

    /** 这一类还空着（占位行：说清去哪儿建它）。 */
    private void empty(int x, int w, int y, String text) {
        addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.min(w, 420), 20, text, null));
    }

    /** 删一条声明（真源 = 脚本）：还被脚本引用着 → **拒收并说明**（#23 真源纪律，与退休的两页同款）。 */
    private void deleteDecl(String verb, String id, String kindLabel) {
        var refs = ScriptEdit.declRefs(parent.def.script(), verb, id);
        if (!refs.isEmpty()) {
            DrawBoardMenuUi.msg("[" + kindLabel + "] 脚本里还有 " + refs.size() + " 处在用「" + id
                    + "」（第 " + refs.get(0) + " 行…），先去脚本里改掉再删");
            return;
        }
        String err = parent.applyScript(ScriptEdit.removeDecl(parent.def.script(), verb, id).text());
        if (!err.isEmpty()) {
            DrawBoardMenuUi.msg("[" + kindLabel + "] " + err);
        }
    }

    /** 加一张卡 = 往脚本里加一段 {@code card cN { }}（**不弹命名框**，直接进编辑页改名）。 */
    private void addCard() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (GameDefinition.CardDef c : parent.def.cards()) ids.add(c.id());
        int n = 1;
        while (ids.contains("c" + n)) n++;
        int idx = ids.size();                            // 声明追加在末尾 ⇒ 新卡的下标就是原条数
        ScriptEdit.Result r = ScriptEdit.addDecl(parent.def.script(), "card", "c" + n);
        String err = parent.applyScript(r.text());
        DrawBoardMenuUi.msg("[卡牌] " + (err.isEmpty() ? r.note() : err));
        if (err.isEmpty()) {
            Minecraft.getInstance().setScreen(new CardEditScreen(this, parent, idx));
        }
    }

    /** 加一枚棋子 = 往脚本里加一段 {@code piece pN { }}（蓝图先空着，**直接进单棋子编辑页**挑）。 */
    private void addPiece() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (GameDefinition.PieceDef p : parent.def.pieces()) ids.add(p.id());
        int n = 1;
        while (ids.contains("p" + n)) n++;
        int idx = ids.size();                            // 声明追加在末尾 ⇒ 新棋子的下标就是原条数
        ScriptEdit.Result r = ScriptEdit.addDecl(parent.def.script(), "piece", "p" + n);
        String err = parent.applyScript(r.text());
        DrawBoardMenuUi.msg("[棋子] " + (err.isEmpty() ? r.note() : err));
        if (err.isEmpty()) {
            Minecraft.getInstance().setScreen(new PieceEditScreen(this, parent, idx));
        }
    }

    /** 这批卡 → 顶层 {@code var 名 = ["…", …]}（生进脚本，不留第二份真源）。 */
    private void genDeckVar() {
        var cards = parent.def.cards();
        if (cards.isEmpty()) {
            DrawBoardMenuUi.msg("[卡牌] 一张卡都没有，先加卡或批量生成");
            return;
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (GameDefinition.CardDef c : cards) ids.add(c.id());
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "生成牌堆变量",
                "英文标识符（脚本里 var 名只能用英文）", "deck", nm -> {
                    if (nm == null || nm.isBlank()) return;
                    ScriptEdit.Result r = ScriptEdit.addDeckVar(parent.def.script(), nm.strip(), ids);
                    String err = parent.applyScript(r.text());
                    DrawBoardMenuUi.msg("[卡牌] " + (err.isEmpty() ? r.note() : err));
                    clearWidgets();
                    init();
                }));
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
        parent.rebuildFromDef();
    }

    /** 当前类有几条（标题计数用）。 */
    private int count() {
        return switch (kind) {
            case KIND_CARD -> parent.def.cards().size();
            case KIND_PIECE -> parent.def.pieces().size();
            case KIND_ITEM -> assetsOfKind(AssetStore.KIND_ITEM).size();
            case KIND_BLOCK -> assetsOfKind(AssetStore.KIND_BLOCK).size();
            case KIND_ENTITY -> assetsOfKind(AssetStore.KIND_ENTITY).size();
            case KIND_TEXT -> assetsOfKind(AssetStore.KIND_TEXT).size();
            default -> 0;
        };
    }

    /**
     * 某一类对象 —— **只列脚本声明的那批**（组件只在组件库里出现，对象列表里只出现声明）。
     */
    private java.util.List<GameDefinition.AssetDef> assetsOfKind(String kd) {
        var all = parent.def.assets();
        if (all == null) {
            return java.util.List.of();
        }
        java.util.Set<String> declared = declaredAssetNames();
        return all.stream()
                .filter(a -> kd.equals(a.kind()) && declared.contains(a.ref()))
                .toList();
    }

    /** 脚本里声明的对象资产名（脚本是半成品/解析不了 → 空集：对象页空着，比列一堆重复的老条目清楚）。 */
    private java.util.Set<String> declaredAssetNames() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (com.tablegame.script.Ast.AssetDecl ad
                    : com.tablegame.script.Parser.parse(parent.def.script()).assetDecls()) {
                out.add(ad.name());
            }
        } catch (RuntimeException ignored) {
            // 半成品脚本：不列（编辑页自己的脚本页会报错）
        }
        return out;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;                              // 菜单开着：先滚菜单（含到头也吃掉）
        }
        if (pickingBase) {
            basePicker.scroll(dy);            // 行是自绘的：滚完不用重建控件
            return true;
        }
        if (scroll.scroll(dy)) {          // 滚轮换一屏窗口 → 重建行（屏小、行少，重建最省事）
            clearWidgets();
            init();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    /** 挑基底视图的清单行是自绘的 —— 命中了归本屏转发（与组件库那件同一个口径）。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        if (menu.click(event.x(), event.y())) {
            return true;                                     // 行选项菜单开着 → 吃掉这一下（别连带触发背后的行）
        }
        if (event.button() == 1) {                            // 右键：命中了哪一行就开哪一行的选项（编辑 / 删除）
            for (int i = 0; i < rowRects.size(); i++) {
                int[] r = rowRects.get(i);
                if (event.x() >= r[0] && event.x() < r[0] + r[2]
                        && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                    rowMenus.get(i).run();
                    return true;
                }
            }
            return false;
        }
        if (super.mouseClicked(event, doubled)) {
            return true;                                     // 搜索框是控件：它优先
        }
        return pickingBase && basePicker.click(event.x(), event.y(), this::addBase);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        barX = Math.max(EditorRail.LEFT2, width - 12);
        listY = 52;                                                   // 各类的行都从 y=52 起排
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 52 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        // 第二列底板（与编辑器「组件」页逐像素同款：亮分割线 + 右边界线）
        g.fill(EditorRail.W + 10, 0, EditorRail.LEFT2 - 8, height, 0xFF14161C);
        g.fill(EditorRail.W + 9, 0, EditorRail.W + 10, height, 0xFF5A6070);
        g.fill(EditorRail.W + 10, 0, EditorRail.W + 11, height, 0xFF5A6070);
        g.fill(EditorRail.LEFT2 - 8, 0, EditorRail.LEFT2 - 7, height, 0xFF3A3E4A);

        int cx = (EditorRail.LEFT2 + width) / 2;
        g.centeredText(font, Component.literal("对象 · " + (pickingBase ? pickTitle() : kind)
                + (pickingBase ? "" : "（" + count() + " 条）· 双击行进编辑 · 右键出选项")
                + (scroll.label().isEmpty() ? "" : "　" + scroll.label())), cx, 30, 0xFFFFFFFF);
        g.text(font, Component.literal("对象 = 能被脚本叫出名字的东西 · 分类口径只有这一处（第二列）"),
                EditorRail.LEFT2, height - 62, 0xFF909090);
        g.text(font, Component.literal(pickingBase
                        ? "点一行 = 拿这个原版" + pickWord() + "当基底（可搜名称或 id）· 取消 = 回列表"
                        : "真源不动：卡牌 / 棋子 = 脚本声明，方块 / 物品 = 项目档 assets 段 —— 这里只列出与进编辑"),
                EditorRail.LEFT2, height - 50, 0xFF909090);
        if (pickingBase) {
            basePicker.draw(g, font, mouseX, mouseY);      // 清单自绘（搜索框由 super 画在上层）
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);                // 「触发…」菜单画在最上层
    }

    // ===== 菜单检索/拖动转发（AssetMenu）=====

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menu.type(event.codepointAsString())) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) {
            return true;                              // 菜单检索行在退格
        }
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) {
            return true;                              // 菜单开着：回车不落到底下的控件
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
        if (menu.drag(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (menu.release(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }
}
