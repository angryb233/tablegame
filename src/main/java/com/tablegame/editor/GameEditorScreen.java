package com.tablegame.editor;

import com.google.gson.JsonParser;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.EditHistory;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GameStore;
import com.tablegame.editor.data.LootEdit;
import com.tablegame.editor.data.RecipeEdit;
import com.tablegame.editor.data.TagEdit;
import com.tablegame.editor.data.TradeEdit;
import com.tablegame.editor.data.VanillaJson;
import com.tablegame.net.GamePackets;

/**
 * 全屏编辑视窗：一个视窗 + 左侧工具栏 + 右侧总览。
 * 左栏（{@link EditorRail}）常驻，四个工具（脚本 / 卡牌 / 棋子 / 数值）随时切换，不用先回本屏；本屏是「没选工具」时的总览页（游戏名 + 各段计数 + 工具用途一览）。
 * 编辑模型：构造时把服务端 JSON 解析成 {@link GameDefinition}（内存单份），所有工具屏共享改它（单一数据源）—— 所以本类把成员包私有暴露给同包屏。
 * 保存 = 内存模型序列化成 JSON 整包上传（{@code SaveGamePayload}），服务端规范化落盘后回发 {@code GameDataPayload}，路由就地刷新。
 */
public class GameEditorScreen extends Screen {
    /** 编辑中的定义（内存单份，所有工具屏共享改它）。 */
    public GameDefinition def;
    /** 是否解析失败（true = 只能返回，不能编辑保存）。 */
    private boolean broken;
    /**
     * 上次保存时规则段的第一条错误（null = 没问题）。
     * 脚本屏拿它把哪一行错了显出来（{@link ScriptEditScreen}）。
     */
    public String scriptError;
    /** 总览页显示用：脚本统计（init 里算一次，别每帧解析）。 */
    private String scriptStat = "（空）";
    /** 总览页显示用：脚本里有哪几块画布（{@code screen} 块）。 */
    private String screenStat = "（空）";
    /** 总览页的「简介」输入框（null = 还没 init / 定义坏了）；保存时它说了算。 */
    private net.minecraft.client.gui.components.MultiLineEditBox descBox;

    /** 改动历史（段6）：只存脚本文本，见 {@link EditHistory}。 */
    private final EditHistory history = new EditHistory();
    /** 撤销 / 重做自己触发的写回不许再压栈。 */
    private boolean inHistory;

    public GameEditorScreen(String name, String json) {
        super(Component.literal("玩法编辑"));
        try {
            this.def = GameStore.fromJson(name, JsonParser.parseString(json).getAsJsonObject());
            this.broken = false;
        } catch (Exception e) {
            this.broken = true;
        }
    }

    /** 服务端保存成功回发规范化版本时就地刷新（快照刷新惯例）。 */
    // 原「静态 open 登记 + onClose 配对清」已整删：登记就是滞留状态，
    // 清理时机修两轮（removed 误伤 / 挪 onClose 漏路径）都治不断根 —— 「第二次点同一个游戏打不开」。
    // 现在回执路由只看此刻屏上的真指针（ClientGameHandler.onGameData：本体 / 工具屏 parent），无登记可滞留。

    public void applyData(String json) {
        try {
            this.def = GameStore.fromJson(def.name(), JsonParser.parseString(json).getAsJsonObject());
            this.broken = false;
            // 放宽后：服务端收下 ≠ 脚本能跑 —— 错由**本地解析**当场算（它就是屏上要显示的那份）
            this.scriptError = syntaxErrorOf(this.def.script());
            clearWidgets();
            init();
        } catch (Exception ignored) {}
    }

    public String gameName() {
        return def == null ? "" : def.name();
    }

    @Override
    protected void init() {
        clearWidgets();
        // 解析失败：def 为 null，任何工具屏构造都会炸 → 不接左栏，只显示提示（Esc 退出）
        if (broken || def == null) return;
        // 脚本统计（总览页只显示个数；解析失败也不炸，就把错显出来）
        try {
            Ast.Script sc = Parser.parse(def.script());
            scriptStat = sc.lines() + " 行 / " + sc.handlers().size() + " 个事件入口 / " + sc.funcs().size() + " 个函数";
            // 「界面」页那一行的摘要：脚本里有哪几块画布（真源就是这些 screen 块，没有第二份数据）
            screenStat = sc.screens().isEmpty()
                    ? "还没有 screen 块 —— 去「界面」页新建"
                    : "画布 " + String.join(" + ", sc.screens().keySet());
        } catch (Ast.ScriptError e) {
            scriptStat = "解析失败 —— " + e.getMessage();
            screenStat = "脚本解析不了，先去脚本页修";
        }
        EditorRail.build(this, EditorRail.OVERVIEW, this::addRenderableWidget);   // 总览页：栏在，「总览」高亮

        // 简介输入框：**底部通栏** —— 上面那张表（40 起、5 行 ×26 → 底 170）横跨整个
        // 内容区，右侧放框会压字；这里从「表格底 + 16」起，跟着窗口高走（改完点左栏「保存」）。
        int boxY = Math.max(170 + 16, height - 52);
        int boxH = Math.max(24, height - boxY - 8);
        descBox = net.minecraft.client.gui.components.MultiLineEditBox.builder()
                .setX(EditorRail.LEFT)
                .setY(boxY)
                .setPlaceholder(Component.literal("简介：简单说说这游戏怎么玩（会显示在游戏台总览页）"))
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(font, Math.max(120, width - EditorRail.LEFT - 16), boxH, Component.literal("简介"));
        descBox.setCharacterLimit(600);
        descBox.setValue(def.desc() == null ? "" : def.desc());
        addRenderableWidget(descBox);
    }

    /** 总览页那行「简介」的当前文本（保存时用；控件不在就给 null = 别动这个字段）。 */
    public String descDraft() {
        return descBox == null ? null : descBox.getValue();
    }

    /** 保存：内存模型 → JSON 整包上传（左栏「保存」也走这里；服务端落盘后回发刷新）。 */
    public void saveToServer() {
        saveToServer(false);
    }

    /**
     * **写回脚本的唯一出口**（画布 / 界面页 / 节点编辑页共用）。规矩（真源即文本，谁都不许绕）：先 {@code Parser.parse} →
     * 过不了就**拒收**（返回原因，文本一字节不动）→ 过了才换进 {@code def} 并**静默**保存（底栏自己显示出去了什么，不刷聊天栏）。
     * @return 空串 = 成功；否则 = 失败原因
     */
    public String applyScript(String text) {
        // 放宽（「保存放宽一点，就算是语法错也可以保存」）：
        // 写回**不再拒收** —— 半成品 / 打错字的草稿都存得下，错只记进 scriptError（「脚本」页显示），
        // 真拦在开局那一步（Parser.parseForPlay，带行号）。查错按钮留下一批。
        scriptError = syntaxErrorOf(text);
        String before = def.script();
        if (!inHistory && !before.equals(text)) {
            history.push(before);                  // 段6：记一笔「改之前」——一处记账 = 处处可撤销
        }
        def = def.withScript(text);
        saveToServer(true);
        return "";
    }

    /** 战利品表改完那条出口：表住在 {@code def.lootTables()} 这个可变 JSON 上（**原版格式**），改完就地落盘 —— 走同一条整份档上传（{@link #saveToServer}）。
     *  保存前用**原版 codec 试解析**（同 {@link #syntaxErrorOf} 那套先例）：解不过 = **拒收**（表一字节不动）+ 返回原因 —— 别等开局才发现表是坏的。
     *  @return 空串 = 成功；否则 = 失败原因 */
    public String applyLoot(String name, String json) {
        if (def == null) return "[战利品表] 没有打开的项目";
        if (json == null || json.isBlank()) {                          // 空 = 删这张表
            def.lootTables().remove(name);
            saveToServer(true);
            return "";
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (Exception e) {
            return "[战利品表] 不是合法 JSON：" + e.getMessage();
        }
        if (!parsed.isJsonObject()) return "[战利品表] 表的最外层必须是 JSON 对象";
        String err = lootErrorOf(json, def);
        if (!err.isEmpty()) return err;
        def.lootTables().add(name, parsed);
        saveToServer(true);
        return "";
    }

    /**
     * 用**原版 codec**试解析一张表（只为报错，不落盘）：客户端有 registryAccess；
     * 主菜单里 {@code level} 为 null 就跳过、不误报（与 {@link #syntaxErrorOf} 同一口径）。
     */
    public static String lootErrorOf(String json, GameDefinition def) {
        if (json == null || json.isBlank()) return "";
        var lv = Minecraft.getInstance().level;
        if (lv == null) return "";
        try {
            // ⚠ 校验的是**编译后的表**（表里写资产名是合法的，编译成 base 之后原版才认）——口径要和引擎那条一模一样
            var vanilla = LootEdit.toVanilla(
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                    def == null ? java.util.List.of() : def.assets());
            net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess()),
                    vanilla).getOrThrow();
            return "";
        } catch (Exception e) {
            return "[战利品表] 不是合法原版 loot_table：" + e.getMessage();
        }
    }

    /**
     * 配方改完那条出口：配方住在 {@code def.recipes()} 那个可变 JSON 上（**原版格式**），
     * 改完就地落盘 —— 走的是同一条整份档上传（{@link #saveToServer}）；校验口径同 {@link #applyLoot}。
     *
     * @return 空串 = 成功；否则 = 失败原因
     */
    public String applyRecipe(String name, String json) {
        if (def == null) return "[配方] 没有打开的项目";
        if (json == null || json.isBlank()) {                          // 空 = 删这张配方
            def.recipes().remove(name);
            saveToServer(true);
            return "";
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (Exception e) {
            return "[配方] 不是合法 JSON：" + e.getMessage();
        }
        if (!parsed.isJsonObject()) return "[配方] 最外层必须是 JSON 对象";
        String err = recipeErrorOf(json, def);
        if (!err.isEmpty()) return err;
        def.recipes().add(name, parsed);
        saveToServer(true);
        return "";
    }

    /**
     * 用**原版 codec**试解析一张配方（只为报错，不落盘）。
     *
     * <p>⚠ 解析的是 {@link RecipeEdit#toVanilla} **编译后的**那一份（写资产名是合法的，
     * 换成基底 id + 组件之后原版才认）—— 口径要和引擎落盘那一份一模一样。
     */
    public static String recipeErrorOf(String json, GameDefinition def) {
        if (json == null || json.isBlank()) return "";
        var lv = Minecraft.getInstance().level;
        if (lv == null) return "";
        try {
            var vanilla = RecipeEdit.toVanilla(
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                    def == null ? java.util.List.of() : def.assets());
            net.minecraft.world.item.crafting.Recipe.CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess()),
                    vanilla).getOrThrow();
            return "";
        } catch (Exception e) {
            return "[配方] 不是合法原版 recipe：" + e.getMessage();
        }
    }

    /** 村民交易改完那条出口：条目住在 {@code def.trades()} 那个可变 JSON（{@code {profession, level, trade}}），改完就地落盘 —— 同一条整份档上传（{@link #saveToServer}）。
     *  保存时服务端把它同步进存档的数据包（配方 / 交易 / 标签共用一条通道）。@return 空串 = 成功；否则 = 失败原因 */
    public String applyTrade(String name, String json) {
        if (def == null) return "[村民交易] 没有打开的项目";
        if (json == null || json.isBlank()) {                          // 空 = 删这条交易
            def.trades().remove(name);
            saveToServer(true);
            return "";
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (Exception e) {
            return "[村民交易] 不是合法 JSON：" + e.getMessage();
        }
        if (!parsed.isJsonObject()) return "[村民交易] 最外层必须是 JSON 对象";
        String err = tradeErrorOf(json, def);
        if (!err.isEmpty()) return err;
        def.trades().add(name, parsed);
        saveToServer(true);
        return "";
    }

    /** 用**原版 codec** 试解析一条村民交易（只为报错，不落盘）。⚠ 解的是 {@link TradeEdit#toVanilla} **编译后的**那一份（写本项目的资产名合法，换成基底 id + 组件后原版才认）——
     *  口径要和引擎落盘那份一模一样；解的是条目里的 {@code trade} 那一层（职业 / 等级不是原版字段，不参与校验）。 */
    public static String tradeErrorOf(String json, GameDefinition def) {
        if (json == null || json.isBlank()) return "";
        var lv = Minecraft.getInstance().level;
        if (lv == null) return "";
        try {
            com.google.gson.JsonObject entry = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            var vanilla = TradeEdit.toVanilla(TradeEdit.tradeOf(entry),
                    def == null ? java.util.List.of() : def.assets());
            net.minecraft.world.item.trading.VillagerTrade.CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess()),
                    vanilla).getOrThrow();
            return "";
        } catch (Exception e) {
            return "[村民交易] 不是合法原版 villager_trade：" + e.getMessage();
        }
    }

    /** 标签改完那条出口：条目住在 {@code def.tags()} 那个可变 JSON（{@code {type, values, replace}}），改完就地落盘 —— 同一条整份档上传（{@link #saveToServer}）。
     *  保存时服务端把它同步进存档的数据包（配方 / 交易 / 标签共用一条通道）。@return 空串 = 成功；否则 = 失败原因 */
    public String applyTag(String name, String json) {
        if (def == null) return "[标签] 没有打开的项目";
        if (json == null || json.isBlank()) {                          // 空 = 删这个标签
            def.tags().remove(name);
            saveToServer(true);
            return "";
        }
        // ⚠ 名字**同时就是原版 id**（不改写）：非法名落下去 = 引用它的配方 / 交易整条报废
        //   删除那一支**不拦** —— 手写档里那个非法名的标签还得删得掉。
        if (!TagEdit.isName(name)) {
            return "[标签] 名字同时是原版 id：只许 a-z 0-9 _ . - /（例 flint_materials）";
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (Exception e) {
            return "[标签] 不是合法 JSON：" + e.getMessage();
        }
        if (!parsed.isJsonObject()) return "[标签] 最外层必须是 JSON 对象";
        String err = tagErrorOf(json);
        if (!err.isEmpty()) return err;
        def.tags().add(name, parsed);
        saveToServer(true);
        return "";
    }

    /** 用**原版 {@code TagFile} codec** 试解析一个标签（只为报错，不落盘）。⚠ 解的是 {@link TagEdit#toVanilla} 之后那份（我们那个 {@code type} 是挂载信息，原版不认它）；口径要和引擎落包那份一模一样。
     *  values 可以是 {@code "minecraft:oak_planks"} 或 {@code "#minecraft:logs"}（原版两种都收）；不接 registryAccess —— values 只是一串 id，纯 JsonOps 就够。 */
    public static String tagErrorOf(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            com.google.gson.JsonObject entry = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String type = TagEdit.typeOf(entry);
            if (!TagEdit.isType(type)) {
                return "[标签] 类型「" + type + "」原版没有这个注册表（" + String.join(" / ", TagEdit.TYPES) + "）";
            }
            net.minecraft.tags.TagFile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                    TagEdit.toVanilla(entry)).getOrThrow();
            return "";
        } catch (Exception e) {
            return "[标签] 不是合法原版 tags 文件：" + e.getMessage();
        }
    }

    /**
     * 段B 三条改完那条出口：进度 / 魔咒 / 伤害类型 —— 条目住在
     * {@code def.vanillaSection(key)} 上（原版 JSON 原样），改完就地落盘（整份档上传）。
     *
     * @return 空串 = 成功；否则 = 失败原因
     */
    public String applyVanilla(String key, String name, String json) {
        String cn = VanillaJson.cnOf(key);
        if (def == null) return "[" + cn + "] 没有打开的项目";
        if (json == null || json.isBlank()) {                          // 空 = 删这一份
            def.vanillaSection(key).remove(name);
            saveToServer(true);
            return "";
        }
        // ⚠ 名字**同时就是原版 id**（别的文件 / 指令 / 组件按它引用）⇒ 非法名落下去 = 引用它的那条整条报废
        //   （段A 的标签踩过一次）。删除那一支**不拦**。
        if (!VanillaJson.isName(name)) {
            return "[" + cn + "] 名字同时是原版 id：只许 a-z 0-9 _ . - /（例 my_charm）";
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(json);
        } catch (Exception e) {
            return "[" + cn + "] 不是合法 JSON：" + e.getMessage();
        }
        if (!parsed.isJsonObject()) return "[" + cn + "] 最外层必须是 JSON 对象";
        String err = vanillaErrorOf(key, json);
        if (!err.isEmpty()) return err;
        def.vanillaSection(key).add(name, parsed);
        saveToServer(true);
        return "";
    }

    /**
     * 用**原版 codec** 试解析一份进度 / 魔咒 / 伤害类型（只为报错，不落盘）。
     *
     * <p>三条都要 RegistryOps（里面有引用注册表的字段：物品谓词 / item 集合 / 效果）；
     * 口径要和引擎落包那一份**一模一样**（落包是原样进包，所以校验的就是那一份）。
     */
    public static String vanillaErrorOf(String key, String json) {
        String cn = VanillaJson.cnOf(key);
        if (json == null || json.isBlank()) return "";
        var lv = Minecraft.getInstance().level;
        if (lv == null) return "";
        try {
            var parsed = com.google.gson.JsonParser.parseString(json);
            var ops = net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, lv.registryAccess());
            if ("advancement".equals(key)) {
                net.minecraft.advancements.Advancement.CODEC.parse(ops, parsed).getOrThrow();
            } else if ("enchantment".equals(key)) {
                net.minecraft.world.item.enchantment.Enchantment.DIRECT_CODEC.parse(ops, parsed).getOrThrow();
            } else if ("damage_type".equals(key)) {
                net.minecraft.world.damagesource.DamageType.DIRECT_CODEC.parse(ops, parsed).getOrThrow();
            } else if ("dialog".equals(key)) {
                net.minecraft.server.dialog.Dialog.DIRECT_CODEC.parse(ops, parsed).getOrThrow();
            }
            return "";
        } catch (Exception e) {
            return "[" + cn + "] 不是合法原版数据：" + e.getMessage();
        }
    }

    /**
     * 本地解析一遍**只为显示错误**（不拦保存）：返回 null = 没问题，否则是带行号的错。
     *
     */
    public static String syntaxErrorOf(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Ast.Script ast = Parser.parse(text);
            String comp = componentErrorOf(ast);
            return comp;
        } catch (Ast.ScriptError e) {
            return e.getMessage();
        }
    }

    /**
     * 脚本里每条对象的 {@code components { … }} 能不能被**原版**解析（不能 → 返回一行人话，否则 null）。
     *
     * <p>与 {@code HostManager.componentPatch} 同一条路（RegistryOps + 原版 codec）——
     * 编辑期与开局期不会一个认一个不认。
     */
    private static String componentErrorOf(Ast.Script ast) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || ast.assetDecls().isEmpty()) {
            return null;                                  // 不在世界里（主菜单）：验不了，不误报
        }
        var ops = net.minecraft.resources.RegistryOps.create(
                com.mojang.serialization.JsonOps.INSTANCE, level.registryAccess());
        for (Ast.AssetDecl d : ast.assetDecls()) {
            GameDefinition.AssetDef a = GameStore.toAssetDef(d);
            if (a.bp() == null || !a.bp().has("components")) {
                continue;
            }
            var res = net.minecraft.core.component.DataComponentPatch.CODEC.parse(ops, a.bp().get("components"));
            if (res.error().isPresent()) {
                return "对象「" + d.name() + "」的 components 原版认不出：" + res.error().get().message();
            }
        }
        return null;
    }

    // ---- 段6 手感：撤销 / 重做（栈在 EditHistory，出口只有 applyScript 上面那一处）----

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /**
     * 撤销：退回上一份脚本 —— **仍然走 {@link #applyScript}**（过真源解析、走同一条保存路子）。
     * 万一被拒（比如那份脚本解析不过）就把那一份还回栈里，不把历史吃掉。
     */
    public void undo() {
        String back = history.undo(def.script());
        if (back == null) {
            DrawBoardMenuUi.msg("[游戏] 没有可撤销的了");
            return;
        }
        inHistory = true;
        String err = applyScript(back);
        inHistory = false;
        if (!err.isEmpty()) {
            history.giveBack(back);
            DrawBoardMenuUi.msg("[游戏] 撤销不了：" + err);
            return;
        }
        DrawBoardMenuUi.msg("[游戏] 已撤销");
    }

    /** 重做：与 {@link #undo} 对称。 */
    public void redo() {
        String fwd = history.redo(def.script());
        if (fwd == null) {
            DrawBoardMenuUi.msg("[游戏] 没有可重做的了");
            return;
        }
        inHistory = true;
        String err = applyScript(fwd);
        inHistory = false;
        if (!err.isEmpty()) {
            history.giveBackRedo(fwd);
            DrawBoardMenuUi.msg("[游戏] 重做不了：" + err);
            return;
        }
        DrawBoardMenuUi.msg("[游戏] 已重做");
    }

    /**
     * 各工具屏在自己的 {@code keyPressed} 里转发一句（Ctrl+Z 撤销 · Ctrl+Y 或 Ctrl+Shift+Z 重做）。
     *
     */
    public boolean handleHistoryKey(int key, boolean ctrl, boolean shift, int z, int y) {
        if (!ctrl) return false;
        if (key == z) {
            if (shift) redo();
            else undo();
            return true;
        }
        if (key == y) {
            redo();
            return true;
        }
        return false;
    }

    public void saveToServer(boolean quiet) {
        if (def == null) return;
        // 简介框里那一份先收进模型：改完不点别处直接「保存」也算数
        String draft = descDraft();
        if (draft != null) def = def.withDesc(draft.trim());
        // 放宽：脚本有语法错**也照样发**（草稿态保存），只提示 + 记进 scriptError
        // （「脚本」页打开时会把它显示出来）。真拦在开局那一步。
        scriptError = syntaxErrorOf(def.script());
        if (scriptError != null) {
            DrawBoardMenuUi.msg("[游戏] 已保存；脚本有语法错：" + scriptError + "（去「脚本」页修）");
        }
        ClientPacketDistributor.sendToServer(
                new GamePackets.SaveGamePayload(def.name(), GameStore.toJson(def).toString(), quiet));
    }

    /** 左栏「返回」：退出编辑器，回游戏列表（服务端回包时屏为空 → 路由开编辑器菜单）。 */
    public void backToList() {
        Minecraft.getInstance().setScreen(null);
        ClientPacketDistributor.sendToServer(new GamePackets.RequestGamesPayload());
    }

    /** 工具屏改完内存模型后调用：总览页重建，计数跟着变。 */
    public void rebuildFromDef() {
        clearWidgets();
        init();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        if (broken) {
            g.centeredText(font, Component.literal("定义解析失败——按 Esc 退出"), width / 2, height / 2, 0xFFFFA0A0);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }
        int cx = EditorRail.cx(this);
        int left = EditorRail.LEFT;
        g.centeredText(font, Component.literal("玩法编辑 · " + def.name()), cx, 12, 0xFFFFFFFF);

        // 工具一览（左：工具名+当前计数；右：它管什么——第一次进编辑器的人靠这张表找到入口）
        // 「界面」页 = 舞台的脚本视图（画布没有自己的数据：画的就是 screen 块的展开，改的是脚本文本）。
        String[][] rows = {
                { "脚本", scriptStat, "规则真源 = 这段文本：什么时候抽题、加分、结束，全在里面" },
                { "界面", screenStat, "舞台可视化：画的是脚本 screen 块的真实展开（新建 / 拖动 → 回写那一行）" },
                { "卡牌", def.cards().size() + " 张 / " + def.decks().size() + " 牌组", "卡面美术 + 牌组来源（牌类游戏用）" },
                { "棋子", def.pieces().size() + " 个", "3D 棋子实例（棋类游戏用）" },
                { "变量", GameStore.slotNames(def).size() + " 个（见「变量」页）",
                  "一局里的数据（分数/题面池）；真源 = 脚本的 var 声明，本页只对账" },
        };
        int y = 40;
        for (String[] r : rows) {
            g.text(font, r[0], left, y, 0xFFE0C060);
            g.text(font, r[1], left + 34, y, 0xFFE0E0E0);
            g.text(font, r[2], left + 34, y + 11, 0xFF909090);
            y += 26;
        }
        g.text(font, "点左栏工具开始编辑 · 「保存」落盘到 tablegame/games/" + def.name()
                        + ".json · 下面那行是简介",
                left, y + 1, 0xFF909090);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
