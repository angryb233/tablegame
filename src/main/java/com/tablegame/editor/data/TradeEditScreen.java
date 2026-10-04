package com.tablegame.editor.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.script.edit.ScriptEdit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.pack.AssetStore;

/**
 * 一条村民交易的表单。
 *
 * <p><b>提交模型</b>：改完点【完成】才一次写回（走 {@link GameEditorScreen#applyTrade}：它既用原版 codec
 * 校验又落盘）；Esc / 取消 = 丢掉。写回是就地改那几格，手写的别的字段（如 {@code additional_wants} /
 * {@code merchant_predicate}）一律原样留着（不把未做 UI 的数据搞丢）。
 *
 * <p>职业 / 等级 / 两边物品都有 ▾ 候选：交易里写本项目的资产名是合法的（编译期换成基底 + 组件）。
 */
public class TradeEditScreen extends Screen {
    private static final int ROW = 24;
    private static final int LBL = 96;              // 标签列宽
    private static final int BOX_W = 220;

    private final Screen back;                     // 返回目标（交易清单）
    private final GameEditorScreen parent;
    private final String name0;                    // 进屏时的旧名（空 = 新建）

    private String name;
    private String profession;
    private int level;
    private final JsonObject trade;                // **副本**：校验不过就不该动真身

    private EditBox nameBox, wantsBox, wantsCountBox, givesBox, givesCountBox, usesBox, xpBox, discBox;
    private final AssetMenu menu = new AssetMenu();
    private String status = "";
    private boolean armed;                         // 【删除】二次确认

    TradeEditScreen(Screen back, GameEditorScreen parent, String name) {
        super(Component.literal("村民交易"));
        this.back = back;
        this.parent = parent;
        this.name0 = name == null ? "" : name;
        this.name = name0;
        JsonElement e = parent.def.trades().get(name0);
        JsonObject entry = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        this.trade = (entry == null ? new JsonObject() : TradeEdit.tradeOf(entry)).deepCopy();
        this.profession = entry == null ? TradeEdit.DEFAULT_PROFESSION : TradeEdit.professionOf(entry);
        this.level = entry == null ? TradeEdit.DEFAULT_LEVEL : TradeEdit.levelOf(entry);
        if (entry == null && !trade.has("gives")) {           // 新建：先把缺省那条形状填进去（界面能直接改）
            JsonObject blank = com.google.gson.JsonParser.parseString(TradeEdit.BLANK).getAsJsonObject();
            for (String k : blank.keySet()) trade.add(k, blank.get(k));
        }
    }

    @Override
    protected void init() {
        final int x = EditorRail.LEFT;
        final int bx = x + LBL;
        nameBox = box(bx, 46, BOX_W, name, 32);
        int y = 70;
        // ⚠ 给 lambda 用的坐标先取成局部 final（捕获可变局部编译不过 —— 工程里踩过）
        final int yProf = y;
        addRenderableWidget(DrawBoardMenuUi.button(bx, yProf, BOX_W, 18,
                "职业：" + TradeEdit.professionCn(profession) + " ▾", () -> pickProfession(bx, yProf)));
        y += ROW;
        final int yLvl = y;
        addRenderableWidget(DrawBoardMenuUi.button(bx, yLvl, BOX_W, 18,
                "等级：" + level + " ▾（原版只有 1~5）", () -> pickLevel(bx, yLvl)));
        y += ROW;
        final int yWants = y;
        wantsBox = box(bx, yWants, BOX_W - 28, TradeEdit.idText(trade, "wants"), 64);
        addRenderableWidget(DrawBoardMenuUi.button(bx + BOX_W - 24, yWants, 24, 16, "▾",
                () -> pickItem(bx + BOX_W - 24, yWants, wantsBox)));
        y += ROW;
        wantsCountBox = box(bx, y, 60, blank1(TradeEdit.countText(trade, "wants")), 8);
        y += ROW;
        final int yGives = y;
        givesBox = box(bx, yGives, BOX_W - 28, TradeEdit.idText(trade, "gives"), 64);
        addRenderableWidget(DrawBoardMenuUi.button(bx + BOX_W - 24, yGives, 24, 16, "▾",
                () -> pickItem(bx + BOX_W - 24, yGives, givesBox)));
        y += ROW;
        givesCountBox = box(bx, y, 60, blank1(TradeEdit.countText(trade, "gives")), 8);
        y += ROW;
        usesBox = box(bx, y, 60, TradeEdit.numText(trade, "max_uses"), 8);
        y += ROW;
        xpBox = box(bx, y, 60, TradeEdit.numText(trade, "xp"), 8);
        y += ROW;
        discBox = box(bx, y, 60, TradeEdit.numText(trade, "reputation_discount"), 8);

        addRenderableWidget(DrawBoardMenuUi.button(x, height - 46, 84, 20, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(x + 90, height - 46, 84, 20, "取消", this::leave));
        if (!name0.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x + 180, height - 24, 84, 20,
                    armed ? "确认删除" : "删除", this::delete));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 24, 84, 20, "‹ 交易清单", this::leave));
    }

    private EditBox box(int x, int y, int w, String value, int max) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.literal(""));
        b.setMaxLength(max);
        b.setValue(value == null ? "" : value);
        addRenderableWidget(b);
        return b;
    }

    private static String blank1(String s) {
        return s == null || s.isEmpty() ? "1" : s;
    }

    // ---------- 候选 ▾ ----------

    private void pickProfession(int bx, int by) {
        List<String> ls = new ArrayList<>();
        List<Runnable> as = new ArrayList<>();
        for (String p : TradeEdit.PROFESSIONS) {
            ls.add(TradeEdit.professionCn(p) + "  (" + p + ")");
            as.add(() -> {
                profession = p;
                menu.close();
                refresh();
            });
        }
        menu.open(width, height, bx, by + 18, ls, as);
    }

    private void pickLevel(int bx, int by) {
        List<String> ls = new ArrayList<>();
        List<Runnable> as = new ArrayList<>();
        for (int l = TradeEdit.MIN_LEVEL; l <= TradeEdit.MAX_LEVEL; l++) {
            int lv = l;
            ls.add(lv + " 级");
            as.add(() -> {
                level = lv;
                menu.close();
                refresh();
            });
        }
        menu.open(width, height, bx, by + 18, ls, as);
    }

    private void pickItem(int bx, int by, EditBox target) {
        List<String> cs = itemCandidates();
        if (cs.isEmpty()) {
            status = "没有候选 —— 直接手打原版 id（minecraft:diamond）或本项目资产名";
            return;
        }
        List<Runnable> as = new ArrayList<>();
        for (String v : cs) {
            as.add(() -> {
                target.setValue(v);
                menu.close();
            });
        }
        menu.open(width, height, bx, by + 16, cs, as);
    }

    /** 物品候选：本项目**声明过**的物品资产名（与脚本里写的一致）+ 手打原版 id。 */
    private List<String> itemCandidates() {
        List<String> cs = new ArrayList<>();
        var as = parent.def.assets();
        if (as == null) return cs;
        var decl = ScriptEdit.declaredNames(script());
        for (GameDefinition.AssetDef a : as) {
            if (AssetStore.KIND_ITEM.equals(a.kind()) && a.ref() != null && !a.ref().isEmpty()
                    && decl.contains(a.ref()) && !cs.contains(a.ref())) {
                cs.add(a.ref());
            }
        }
        return cs;
    }

    private String script() {
        return parent.def.script() == null ? "" : parent.def.script();
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    // ---------- 提交 ----------

    private void commit() {
        String nm = nameBox == null ? "" : nameBox.getValue().trim();
        if (nm.isEmpty()) {
            status = "交易名不能空";
            return;
        }
        if (!nm.equals(name0) && parent.def.trades().has(nm)) {
            status = "已经有这条交易了：" + nm;
            return;
        }
        setCost("wants", wantsBox, wantsCountBox);
        setCost("gives", givesBox, givesCountBox);
        putNum("max_uses", usesBox);
        putNum("xp", xpBox);
        putNum("reputation_discount", discBox);
        String err = parent.applyTrade(nm, TradeEdit.entry(profession, level, trade).toString());
        if (!err.isEmpty()) {
            status = err;                       // 不落盘：改的东西还在屏上，接着改
            return;
        }
        if (!name0.isEmpty() && !nm.equals(name0)) parent.applyTrade(name0, "");   // 改名 = 写新的 + 删旧的
        leave();
    }

    /** 收 / 给那一格（id + 数量）；id 空 = 整格删掉（原版那条字段是可省的）。 */
    private void setCost(String key, EditBox idBox, EditBox countBox) {
        String id = idBox == null ? "" : idBox.getValue().trim();
        if (id.isEmpty()) {
            trade.remove(key);
            return;
        }
        JsonObject o = trade.has(key) && trade.get(key).isJsonObject()
                ? trade.getAsJsonObject(key) : new JsonObject();
        o.addProperty("id", id);
        o.addProperty("count", intOf(countBox, 1));
        trade.add(key, o);
    }

    private void putNum(String key, EditBox b) {
        String raw = b == null ? "" : b.getValue().trim();
        if (raw.isEmpty()) {
            trade.remove(key);
            return;
        }
        try {
            trade.addProperty(key, Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            // 不是数字：留着原值（不把一条写得好的交易改成坏 JSON）
        }
    }

    private static int intOf(EditBox b, int def) {
        try {
            return Integer.parseInt(b == null ? "" : b.getValue().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void delete() {
        if (!armed) {
            armed = true;
            status = "再点一次「确认删除」就删掉这条交易";
            refresh();
            return;
        }
        parent.applyTrade(name0, "");
        leave();
    }

    private void leave() {
        Minecraft.getInstance().setScreen(back);
    }

    // ---------- 画面 ----------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EditorRail.drawBackdrop(g, height);
        int x = EditorRail.LEFT;
        int bx = x + LBL;
        int y = 46;
        g.centeredText(font, Component.literal("原版数据 · 村民交易 · " + (name0.isEmpty() ? "新建" : name0)),
                EditorRail.cx(this), 12, 0xFFFFFFFF);
        g.text(font, "交易名", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "挂给谁", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        y += ROW;
        g.text(font, "收什么（买）", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "收几个", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "给什么（卖）", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "给几个", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "最多用几次", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "经验", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "折扣", x, y + 5, 0xFFC0C0C0);
        g.text(font, "物品写原版 id（minecraft:diamond）或本项目资产名；存盘后服务端同步进存档数据包",
                x, height - 68, 0xFF909090);
        g.text(font, "⚠ 原版按 trade_set 的 amount 从整池随机抽 ⇒ 我们这条不保证出现", x, height - 80, 0xFFB08060);
        if (!status.isEmpty()) {
            g.text(font, status, bx, height - 58, 0xFFE0C060);
        }
        menu.draw(g, font, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) return true;
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) return true;
        if (event.key() == 256) {                       // Esc = 丢掉改动回清单
            leave();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menu.type(event.codepointAsString())) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (menu.isOpen() && menu.click(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (menu.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (menu.release(event.x(), event.y())) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (menu.scrollBy(sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }
}
