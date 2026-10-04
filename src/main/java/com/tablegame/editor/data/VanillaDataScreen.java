package com.tablegame.editor.data;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;
import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.NamePromptScreen;
import com.tablegame.editor.ScrollBar;

/**
 * 【原版数据】页：原版能自定义的那几样东西的统一入口；版式照【对象】【组件】两页（左栏一级页签 → 内容区第二列页签 → 类别自己的目录 → 点一行进它自己的视图）。
 */
public class VanillaDataScreen extends Screen implements EditorToolScreen {
    /** 左栏页签名（要和 {@link EditorRail} 里那条一致，页签才高亮）。 */
    public static final String RAIL = "原版数据";
    private static final String TAB_LOOT = "战利品表";
    /** 配方页签（公开：画布的「‹ 配方清单」要回到这一页）。 */
    public static final String TAB_RECIPE = "配方";
    /** 村民交易页签（公开：交易编辑屏的「取消 / 返回」要回到这一页）。 */
    public static final String TAB_TRADE = "村民交易";
    /** 标签页签（公开：标签编辑屏的「‹ 标签清单」要回到这一页）。 */
    public static final String TAB_TAG = "标签";
    private static final String[] TABS = buildTabs();
    /** 新建表的默认形状：一张空的原版方块表（type 与池都能在画布/参数卡里改）。 */
    static final String BLANK_TABLE = "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1,\"entries\":[]}]}";

    private final GameEditorScreen parent;
    private String tab = TAB_LOOT;
    private final ListScroll scroll = new ListScroll(10);
    /** 滚动条：轨道贴行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            refresh();
        }
        return true;
    }

    private String armDelete = "";                     // 二次确认：先点一下记名，再点才真删（两个页共用）
    private String status = "";

    public VanillaDataScreen(GameEditorScreen parent) {
        this(parent, TAB_LOOT);
    }

    /** 指定落在哪个页签（画布的「‹ 清单」用它回自己那一页）。 */
    public VanillaDataScreen(GameEditorScreen parent, String startTab) {
        super(Component.literal(RAIL));
        this.parent = parent;
        this.tab = startTab;
    }

    /** 原地重画（切页签 / 滚轮 / 删完 / 改完名都用它）。⚠ 切页签别「新开一块这个屏」：新屏的 {@code tab} 回到字段初值 ⇒ 又弹回战利品表。 */
    private void refresh() {
        clearWidgets();
        init();
    }

    /** 页签顺序 = 方向文档里的接单次序（做好的与占位的都在这一行里，顺序不动）。 */
    private static String[] buildTabs() {
        List<String> all = new ArrayList<>();
        all.add(TAB_LOOT);
        all.add(TAB_RECIPE);
        all.add(TAB_TRADE);
        all.add("进度");          // 段B 三条（顺序照原占位表）
        all.add(TAB_TAG);
        all.add("魔咒");
        all.add("伤害类型");
        all.add(VanillaJson.cnOf("dialog"));   // 段C：表里加一行 ⇒ 这行自己跟上
        return all.toArray(new String[0]);
    }

    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isLoot() {
        return TAB_LOOT.equals(tab);
    }

    private boolean isRecipe() {
        return TAB_RECIPE.equals(tab);
    }

    private boolean isTrade() {
        return TAB_TRADE.equals(tab);
    }

    private boolean isTag() {
        return TAB_TAG.equals(tab);
    }

    /** 这一页是不是段B 那三条（是 → 返回段键，否则空串）。 */
    private static String keyOf(String t) {
        for (String[] s : VanillaJson.SECTIONS) {
            if (s[1].equals(t)) return s[0];
        }
        return "";
    }

    @Override
    protected void init() {
        // 第一列：编辑器左栏（当前页「原版数据」高亮）；第二列：这一类自己的页签
        EditorRail.build(parent, RAIL, this::addRenderableWidget);
        EditorRail.buildGenericAt(EditorRail.LEFT, TABS, tab, this::pickTab, this::addRenderableWidget);
        if (isLoot()) {
            buildLootRows();
        } else if (isRecipe()) {
            buildRecipeRows();
        } else if (isTrade()) {
            buildTradeRows();
        } else if (isTag()) {
            buildTagRows();
        } else if (!keyOf(tab).isEmpty()) {
            buildVanillaRows(keyOf(tab));
        }
        // 占位页：只有页签栏，内容区写一句话（见 extractRenderState）
    }

    private void pickTab(String t) {
        // 占位：不换页，给一句话（做好了的那几页都在这个名单里）
        if (!TAB_LOOT.equals(t) && !TAB_RECIPE.equals(t) && !TAB_TRADE.equals(t) && !TAB_TAG.equals(t)
                && keyOf(t).isEmpty()) {
            DrawBoardMenuUi.msg(t + "：占位，还没做");
            return;
        }
        tab = t;
        status = "";
        armDelete = "";
        scroll.setTop(0);
        refresh();
    }

    // ================= 战利品表页 =================

    /** 战利品表页：一行一张表 + 顶部新建。 */
    private void buildLootRows() {
        List<String> names = tableNames();
        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 14);
        addRenderableWidget(DrawBoardMenuUi.button(x, 30, 110, 20, "＋ 新建表", this::createTable));
        if (names.isEmpty()) {
            status = "还没有战利品表 —— 点「＋ 新建表」，或在这个项目里手工放 loot/<表名>.json";
            return;
        }
        int rows = Math.max(1, (height - 96) / 24);
        scroll.setRows(rows);
        scroll.setTotal(names.size());
        int y = 56;
        for (int i = 0; i < rows; i++) {
            int idx = scroll.index(i);
            if (idx < 0 || idx >= names.size()) break;
            String name = names.get(idx);
            addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.max(80, w - 150), 20, rowLabel(name),
                    () -> openTable(name)));
            boolean armed = name.equals(armDelete);
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 144, y, 64, 20, "改名", () -> renameTable(name)));
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 76, y, 76, 20, armed ? "确认删除" : "删除",
                    () -> deleteTable(name)));
            y += 24;
        }
    }

    private List<String> tableNames() {
        List<String> out = new ArrayList<>(parent.def.lootTables().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /** 一行：表名 · 原版 type · 被几个对象挂着（一眼看出这张表还有没有用）。 */
    private String rowLabel(String name) {
        var t = parent.def.lootTables().get(name);
        String type = "?";
        if (t != null && t.isJsonObject() && t.getAsJsonObject().has("type")) {
            type = t.getAsJsonObject().get("type").getAsString();
            int c = type.indexOf(':');
            if (c >= 0) type = type.substring(c + 1);
        }
        return name + "　· " + type + "　· 挂 " + refCount(name) + " 个对象";
    }

    /** 脚本里写了几处 {@code loot "这张表"}（表被谁引用；0 = 还没挂）。 */
    private int refCount(String name) {
        int n = 0;
        try {
            for (Ast.AssetDecl d : Parser.parse(parent.def.script()).assetDecls()) {
                for (Ast.Field f : d.fields()) {
                    if ("loot".equals(f.key()) && f.value() instanceof Ast.Str sv && name.equals(sv.v())) n++;
                }
            }
        } catch (Exception ignored) {
            // 脚本有语法错 = 引用数看不到，不影响编辑表本身
        }
        return n;
    }

    private void openTable(String name) {
        Minecraft.getInstance().setScreen(new LootCanvasScreen(this, parent, name));
    }

    private void createTable() {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "新建战利品表",
                "表名（字母数字下划线，如 diamond_ore）", "",
                name -> {
                    String nm = name == null ? "" : name.trim();
                    if (nm.isEmpty()) return;
                    if (parent.def.lootTables().has(nm)) {
                        DrawBoardMenuUi.msg("已经有这张表了：" + nm);
                        return;
                    }
                    String err = parent.applyLoot(nm, BLANK_TABLE);
                    if (!err.isEmpty()) {
                        DrawBoardMenuUi.msg(err);
                        return;
                    }
                    Minecraft.getInstance().setScreen(new LootCanvasScreen(this, parent, nm));
                }));
    }

    private void renameTable(String name) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "表改名",
                "新表名（脚本里那格 loot \"…\" 要跟着改）", name,
                to -> {
                    String nm = to == null ? "" : to.trim();
                    if (nm.isEmpty() || nm.equals(name)) return;
                    var t = parent.def.lootTables().get(name);
                    if (t == null || parent.def.lootTables().has(nm)) {
                        DrawBoardMenuUi.msg("改不了：" + nm + (parent.def.lootTables().has(nm) ? "（重名）" : ""));
                        return;
                    }
                    String err = parent.applyLoot(nm, t.toString());
                    if (err.isEmpty()) parent.applyLoot(name, "");
                    else DrawBoardMenuUi.msg(err);
                    refresh();
                }));
    }

    private void deleteTable(String name) {
        if (!name.equals(armDelete)) {                  // 第一次点 = 上膛
            armDelete = name;
            status = "再点一次「确认删除」就删掉「" + name + "」";
            refresh();
            return;
        }
        parent.applyLoot(name, "");
        refresh();
    }

    // ================= 配方页 =================

    /** 配方页：一行一张配方 + 顶部两个新建（有序 / 无序 —— 形状新建时定，之后改就重建）。 */
    private void buildRecipeRows() {
        List<String> names = recipeNames();
        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 14);
        addRenderableWidget(DrawBoardMenuUi.button(x, 30, 120, 20, "＋ 新建·有序", () -> createRecipe(0)));
        addRenderableWidget(DrawBoardMenuUi.button(x + 126, 30, 120, 20, "＋ 新建·无序", () -> createRecipe(1)));
        if (names.isEmpty()) {
            status = "还没有配方 —— 点「＋ 新建」，或在这个项目里手工放 recipe/<配方名>.json（原版格式）";
            return;
        }
        int rows = Math.max(1, (height - 96) / 24);
        scroll.setRows(rows);
        scroll.setTotal(names.size());
        int y = 56;
        for (int i = 0; i < rows; i++) {
            int idx = scroll.index(i);
            if (idx < 0 || idx >= names.size()) break;
            String name = names.get(idx);
            addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.max(80, w - 150), 20, recipeRowLabel(name),
                    () -> openRecipe(name)));
            boolean armed = name.equals(armDelete);
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 144, y, 64, 20, "改名", () -> renameRecipe(name)));
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 76, y, 76, 20, armed ? "确认删除" : "删除",
                    () -> deleteRecipe(name)));
            y += 24;
        }
    }

    private List<String> recipeNames() {
        List<String> out = new ArrayList<>(parent.def.recipes().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /** 一行：配方名 · 形状（工作台·有序…）· 出什么（写的是资产名就显示资产名）。 */
    private String recipeRowLabel(String name) {
        var e = parent.def.recipes().get(name);
        if (e == null || !e.isJsonObject()) return name + "　·（坏）";
        var r = e.getAsJsonObject();
        return name + "　· " + RecipeEdit.typeLabel(r) + "　· 出 " + RecipeEdit.resultText(r);
    }

    private void openRecipe(String name) {
        Minecraft.getInstance().setScreen(new RecipeCanvasScreen(this, parent, name));
    }

    private void createRecipe(int typeIndex) {
        String blank = RecipeEdit.TYPES[typeIndex].json();
        String label = RecipeEdit.TYPES[typeIndex].label();
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "新建配方（" + label + "）",
                "配方名（字母数字下划线，如 催化剂）—— 它也是落盘的文件名", "",
                name -> {
                    String nm = name == null ? "" : name.trim();
                    if (nm.isEmpty()) return;
                    if (parent.def.recipes().has(nm)) {
                        DrawBoardMenuUi.msg("已经有这张配方了：" + nm);
                        return;
                    }
                    String err = parent.applyRecipe(nm, blank);
                    if (!err.isEmpty()) {
                        DrawBoardMenuUi.msg(err);
                        return;
                    }
                    Minecraft.getInstance().setScreen(new RecipeCanvasScreen(this, parent, nm));
                }));
    }

    private void renameRecipe(String name) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "配方改名",
                "新配方名（清单里那行会跟着改；脚本不引用它，不用改别处）", name,
                to -> {
                    String nm = to == null ? "" : to.trim();
                    if (nm.isEmpty() || nm.equals(name)) return;
                    var e = parent.def.recipes().get(name);
                    if (e == null || parent.def.recipes().has(nm)) {
                        DrawBoardMenuUi.msg("改不了：" + nm + (parent.def.recipes().has(nm) ? "（重名）" : ""));
                        return;
                    }
                    String err = parent.applyRecipe(nm, e.toString());
                    if (err.isEmpty()) parent.applyRecipe(name, "");
                    else DrawBoardMenuUi.msg(err);
                    refresh();
                }));
    }

    private void deleteRecipe(String name) {
        if (!name.equals(armDelete)) {                  // 第一次点 = 上膛
            armDelete = name;
            status = "再点一次「确认删除」就删掉「" + name + "」（下次保存后工作台里就合不出来了）";
            refresh();
            return;
        }
        parent.applyRecipe(name, "");
        refresh();
    }

    // ================= 村民交易页 =================

    /** 村民交易页：一行一条交易 + 顶部新建。点一行进 {@link TradeEditScreen} 表单（一条交易 = 职业 / 等级 / 收买给 / 次数 / 经验 / 折扣的扁平记录）。 */
    private void buildTradeRows() {
        List<String> names = tradeNames();
        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 14);
        addRenderableWidget(DrawBoardMenuUi.button(x, 30, 130, 20, "＋ 新建交易", () -> createTrade()));
        if (names.isEmpty()) {
            status = "还没有村民交易 —— 点「＋ 新建交易」，或在这个项目里手工放 villager_trade/<交易名>.json";
            return;
        }
        int rows = Math.max(1, (height - 96) / 24);
        scroll.setRows(rows);
        scroll.setTotal(names.size());
        int y = 56;
        for (int i = 0; i < rows; i++) {
            int idx = scroll.index(i);
            if (idx < 0 || idx >= names.size()) break;
            String name = names.get(idx);
            addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.max(80, w - 150), 20, tradeRowLabel(name),
                    () -> openTrade(name)));
            boolean armed = name.equals(armDelete);
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 144, y, 64, 20, "改名", () -> renameTrade(name)));
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 76, y, 76, 20, armed ? "确认删除" : "删除",
                    () -> deleteTrade(name)));
            y += 24;
        }
    }

    private List<String> tradeNames() {
        List<String> out = new ArrayList<>(parent.def.trades().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /** 一行：交易名 · 挂给哪个职业哪一级 · 收什么 → 给什么。 */
    private String tradeRowLabel(String name) {
        var e = parent.def.trades().get(name);
        if (e == null || !e.isJsonObject()) return name + "　·（坏）";
        return TradeEdit.rowLabel(name, e.getAsJsonObject());
    }

    private void openTrade(String name) {
        Minecraft.getInstance().setScreen(new TradeEditScreen(this, parent, name));
    }

    /** 新建：直接进编辑屏（名字在屏里填；点【完成】才真正落盘 —— 与别处的提交模型一致）。 */
    private void createTrade() {
        Minecraft.getInstance().setScreen(new TradeEditScreen(this, parent, ""));
    }

    private void renameTrade(String name) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "交易改名",
                "新交易名（数据包里的那条 id 也跟着换；脚本不引用它）", name,
                to -> {
                    String nm = to == null ? "" : to.trim();
                    if (nm.isEmpty() || nm.equals(name)) return;
                    var e = parent.def.trades().get(name);
                    if (e == null || parent.def.trades().has(nm)) {
                        DrawBoardMenuUi.msg("改不了：" + nm + (parent.def.trades().has(nm) ? "（重名）" : ""));
                        return;
                    }
                    String err = parent.applyTrade(nm, e.toString());
                    if (err.isEmpty()) parent.applyTrade(name, "");
                    else DrawBoardMenuUi.msg(err);
                    refresh();
                }));
    }

    private void deleteTrade(String name) {
        if (!name.equals(armDelete)) {                  // 第一次点 = 上膛
            armDelete = name;
            status = "再点一次「确认删除」就删掉「" + name + "」（下次保存后那一级的池子里就没有它了）";
            refresh();
            return;
        }
        parent.applyTrade(name, "");
        refresh();
    }

    // ================= 标签页 =================

    /** 标签页：一行一个标签 + 顶部新建。点一行进 {@link TagEditScreen} 表单（一个标签 = 挂到哪个注册表 + 一列 id；值写原版 id 或 `#别的标签`；存盘后同步进存档）。 */
    private void buildTagRows() {
        List<String> names = tagNames();
        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 14);
        addRenderableWidget(DrawBoardMenuUi.button(x, 30, 130, 20, "＋ 新建标签", this::createTag));
        if (names.isEmpty()) {
            status = "还没有标签 —— 点「＋ 新建标签」，或在这个项目里手工放 tags/<标签名>.json";
            return;
        }
        int rows = Math.max(1, (height - 96) / 24);
        scroll.setRows(rows);
        scroll.setTotal(names.size());
        int y = 56;
        for (int i = 0; i < rows; i++) {
            int idx = scroll.index(i);
            if (idx < 0 || idx >= names.size()) break;
            String name = names.get(idx);
            addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.max(80, w - 150), 20, tagRowLabel(name),
                    () -> openTag(name)));
            boolean armed = name.equals(armDelete);
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 144, y, 64, 20, "改名", () -> renameTag(name)));
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 76, y, 76, 20, armed ? "确认删除" : "删除",
                    () -> deleteTag(name)));
            y += 24;
        }
    }

    private List<String> tagNames() {
        List<String> out = new ArrayList<>(parent.def.tags().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /** 一行：标签名 · 类型 · 几个值。 */
    private String tagRowLabel(String name) {
        var e = parent.def.tags().get(name);
        if (e == null || !e.isJsonObject()) return name + "　·（坏）";
        return TagEdit.rowLabel(name, e.getAsJsonObject());
    }

    private void openTag(String name) {
        Minecraft.getInstance().setScreen(new TagEditScreen(this, parent, name));
    }

    /** 新建：直接进编辑屏（名字在屏里填；点【完成】才真正落盘 —— 与别处的提交模型一致）。 */
    private void createTag() {
        Minecraft.getInstance().setScreen(new TagEditScreen(this, parent, ""));
    }

    private void renameTag(String name) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, "标签改名",
                "新标签名（数据包里的那个 id 也跟着换；别处写的是 #tablegame:<名>）", name,
                to -> {
                    String nm = to == null ? "" : to.trim();
                    if (nm.isEmpty() || nm.equals(name)) return;
                    var e = parent.def.tags().get(name);
                    if (e == null || parent.def.tags().has(nm)) {
                        DrawBoardMenuUi.msg("改不了：" + nm + (parent.def.tags().has(nm) ? "（重名）" : ""));
                        return;
                    }
                    String err = parent.applyTag(nm, e.toString());
                    if (err.isEmpty()) parent.applyTag(name, "");
                    else DrawBoardMenuUi.msg(err);
                    refresh();
                }));
    }

    private void deleteTag(String name) {
        if (!name.equals(armDelete)) {                  // 第一次点 = 上膛
            armDelete = name;
            status = "再点一次「确认删除」就删掉「" + name + "」（下次保存后原版就不认这个标签了）";
            refresh();
            return;
        }
        parent.applyTag(name, "");
        refresh();
    }

    // ================= 段B：进度 / 魔咒 / 伤害类型（三段共用一套）=================

    /** 清单：一行一份（点一行进 {@link VanillaJsonScreen}，屏里编原版 JSON 原文）。 */
    private void buildVanillaRows(String key) {
        String cn = VanillaJson.cnOf(key);
        List<String> names = vanillaNames(key);
        int x = EditorRail.LEFT2;
        int w = Math.max(120, width - x - 14);
        addRenderableWidget(DrawBoardMenuUi.button(x, 30, 140, 20, "＋ 新建" + cn, () -> createVanilla(key)));
        if (names.isEmpty()) {
            status = "还没有" + cn + " —— 点「＋ 新建" + cn + "」，或在这个项目里手工放 " + key + "/<名字>.json（原版格式）";
            return;
        }
        int rows = Math.max(1, (height - 96) / 24);
        scroll.setRows(rows);
        scroll.setTotal(names.size());
        int y = 56;
        for (int i = 0; i < rows; i++) {
            int idx = scroll.index(i);
            if (idx < 0 || idx >= names.size()) break;
            String name = names.get(idx);
            addRenderableWidget(DrawBoardMenuUi.button(x, y, Math.max(80, w - 150), 20, vanillaRowLabel(key, name),
                    () -> openVanilla(key, name)));
            boolean armed = name.equals(armDelete);
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 144, y, 64, 20, "改名", () -> renameVanilla(key, name)));
            addRenderableWidget(DrawBoardMenuUi.button(x + w - 76, y, 76, 20, armed ? "确认删除" : "删除",
                    () -> deleteVanilla(key, name)));
            y += 24;
        }
    }

    private List<String> vanillaNames(String key) {
        List<String> out = new ArrayList<>(parent.def.vanillaSection(key).keySet());
        java.util.Collections.sort(out);
        return out;
    }

    private String vanillaRowLabel(String key, String name) {
        var e = parent.def.vanillaSection(key).get(name);
        if (e == null || !e.isJsonObject()) return name + "　·（坏）";
        return VanillaJson.rowLabel(key, name, e.getAsJsonObject());
    }

    private void openVanilla(String key, String name) {
        Minecraft.getInstance().setScreen(new VanillaJsonScreen(this, parent, key, name));
    }

    /** 新建：直接进编辑屏（名字在屏里填、内容预填模板；点【完成】才落盘）。 */
    private void createVanilla(String key) {
        Minecraft.getInstance().setScreen(new VanillaJsonScreen(this, parent, key, ""));
    }

    private void renameVanilla(String key, String name) {
        Minecraft.getInstance().setScreen(new NamePromptScreen(this, VanillaJson.cnOf(key) + "改名",
                "新名字（= 原版 id，只许 a-z 0-9 _ . - /）；数据包里的 id 也跟着换", name,
                to -> {
                    String nm = to == null ? "" : to.trim();
                    if (nm.isEmpty() || nm.equals(name)) return;
                    if (!VanillaJson.isName(nm) || parent.def.vanillaSection(key).has(nm)) {
                        DrawBoardMenuUi.msg("改不了：" + nm
                                + (VanillaJson.isName(nm) ? "（重名）" : "（名字只许 a-z 0-9 _ . - /）"));
                        return;
                    }
                    var e = parent.def.vanillaSection(key).get(name);
                    String err = parent.applyVanilla(key, nm, e == null ? "" : e.toString());
                    if (err.isEmpty()) parent.applyVanilla(key, name, "");
                    else DrawBoardMenuUi.msg(err);
                    refresh();
                }));
    }

    private void deleteVanilla(String key, String name) {
        if (!name.equals(armDelete)) {                  // 第一次点 = 上膛
            armDelete = name;
            status = "再点一次「确认删除」就删掉「" + name + "」（下次保存后原版就不认它了）";
            refresh();
            return;
        }
        parent.applyVanilla(key, name, "");
        refresh();
    }

    // ================= 画面 =================

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if ((isLoot() || isRecipe() || isTrade() || isTag() || !keyOf(tab).isEmpty()) && scroll.scroll(sy) != false) {
            refresh();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        barX = Math.max(8, width - 12);
        listY = 56;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 56 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        EditorRail.drawBackdrop(g, height);
        g.centeredText(font, Component.literal("原版数据 · " + tab), EditorRail.cx(this), 12, 0xFFFFFFFF);
        int x = EditorRail.LEFT2;
        if (isLoot()) {
            g.text(font, "战利品表 = 原版 loot_table（表本体住在项目档 loot/<表名>.json；点一行进画布）",
                    x, 18, 0xFF909090);
            if (!status.isEmpty()) {
                g.text(font, status, x, height - 40, 0xFFE0C060);
            }
            g.text(font, "滚轮翻页　" + scroll.label(), x, height - 22, 0xFF909090);
        } else if (isRecipe()) {
            g.text(font, "配方 = 原版 recipe（住在项目档 recipe/<配方名>.json）；存盘后服务端把它同步进存档的数据包",
                    x, 18, 0xFF909090);
            if (!status.isEmpty()) {
                g.text(font, status, x, height - 40, 0xFFE0C060);
            }
            g.text(font, "滚轮翻页　" + scroll.label(), x, height - 22, 0xFF909090);
        } else if (isTrade()) {
            g.text(font, "村民交易 = 原版 villager_trade（一条交易挂到某个职业的某一级；存盘后同步进存档的数据包）",
                    x, 18, 0xFF909090);
            g.text(font, "⚠ 原版按 trade_set 的 amount 从整池随机抽 ⇒ 我们这条**不保证出现**；要必出用 entity_data 写村民的 Offers",
                    x, 30, 0xFFB08060);
            if (!status.isEmpty()) {
                g.text(font, status, x, height - 40, 0xFFE0C060);
            }
            g.text(font, "滚轮翻页　" + scroll.label(), x, height - 22, 0xFF909090);
        } else if (isTag()) {
            g.text(font, "标签 = 原版 tags/<类型>/<路径>.json（一个标签一个文件；值写原版 id 或 #别的标签）",
                    x, 18, 0xFF909090);
            g.text(font, "存盘后服务端把它同步进存档数据包；别处引用它写 #tablegame:<名>",
                    x, 30, 0xFF909090);
            if (!status.isEmpty()) {
                g.text(font, status, x, height - 40, 0xFFE0C060);
            }
            g.text(font, "滚轮翻页　" + scroll.label(), x, height - 22, 0xFF909090);
        } else if (!keyOf(tab).isEmpty()) {
            String k = keyOf(tab);
            g.text(font, VanillaJson.cnOf(k) + " = 原版 " + k + "（一物一文件住在项目档 " + k + "/<名字>.json；存盘后同步进存档数据包）",
                    x, 18, 0xFF909090);
            g.text(font, "名字 = 原版 id（只许 a-z 0-9 _ . - /）；内容写原版 JSON，保存时用原版 codec 校验",
                    x, 30, 0xFF909090);
            if (!status.isEmpty()) {
                g.text(font, status, x, height - 40, 0xFFE0C060);
            }
            g.text(font, "滚轮翻页　" + scroll.label(), x, height - 22, 0xFF909090);
        } else {
            g.text(font, "「" + tab + "」占位：还没做（原版数据这一类现在做了战利品表 / 配方 / 村民交易 / 标签 / 进度 / 魔咒 / 伤害类型）。",
                    x, 44, 0xFF909090);
        }
        g.text(font, "内容一律从 " + EditorRail.LEFT2 + " 起排 · Esc 返回", 4, height - 22, 0xFF707070);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
}
