package com.tablegame.editor.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tablegame.drawboard.DrawBoardMenuUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.EditorRail;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.item.ComponentTextScreen;

    /**
     * 一个标签的表单。
     *
     * <p>提交模型（同 {@link TradeEditScreen}）：改完点【完成】才一次写回（走 {@link GameEditorScreen#applyTag}：
     * 既用原版 {@code TagFile} codec 校验又落盘）；Esc / 取消 = 丢掉。
     */
public class TagEditScreen extends Screen {
    private static final int ROW = 24;
    private static final int LBL = 96;              // 标签列宽
    private static final int BOX_W = 220;

    private final Screen back;                     // 返回目标（标签清单）
    private final GameEditorScreen parent;
    private final String name0;                    // 进屏时的旧名（空 = 新建）

    private String name;
    private String type;
    private List<String> values;                   // **副本**：校验不过就不该动真身

    private EditBox nameBox;
    private final AssetMenu menu = new AssetMenu();
    private String status = "";
    private boolean armed;                         // 【删除】二次确认

    TagEditScreen(Screen back, GameEditorScreen parent, String name) {
        super(Component.literal("标签"));
        this.back = back;
        this.parent = parent;
        this.name0 = name == null ? "" : name;
        this.name = name0;
        JsonElement e = parent.def.tags().get(name0);
        JsonObject entry = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        this.type = entry == null ? TagEdit.DEFAULT_TYPE : TagEdit.typeOf(entry);
        this.values = entry == null ? new ArrayList<>() : TagEdit.valuesOf(entry);
    }

    @Override
    protected void init() {
        final int x = EditorRail.LEFT;
        final int bx = x + LBL;
        nameBox = box(bx, 46, BOX_W, name, 32);
        int y = 70;
        // ⚠ 给 lambda 用的坐标先取成局部 final（捕获可变局部编译不过）
        final int yType = y;
        addRenderableWidget(DrawBoardMenuUi.button(bx, yType, BOX_W, 18,
                "类型：" + TagEdit.typeCn(type) + " ▾", () -> pickType(bx, yType)));
        y += ROW;
        addRenderableWidget(DrawBoardMenuUi.button(bx, y, BOX_W, 18,
                "值：" + values.size() + " 个 —— 【编辑值…】", this::editValues));

        addRenderableWidget(DrawBoardMenuUi.button(x, height - 46, 84, 20, "完成", this::commit));
        addRenderableWidget(DrawBoardMenuUi.button(x + 90, height - 46, 84, 20, "取消", this::leave));
        if (!name0.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(x + 180, height - 24, 84, 20,
                    armed ? "确认删除" : "删除", this::delete));
        }
        addRenderableWidget(DrawBoardMenuUi.button(x, height - 24, 84, 20, "‹ 标签清单", this::leave));
    }

    private EditBox box(int x, int y, int w, String value, int max) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.literal(""));
        b.setMaxLength(max);
        b.setValue(value == null ? "" : value);
        addRenderableWidget(b);
        return b;
    }

    // ---------- 类型 ▾ / 值的小窗 ----------

    private void pickType(int bx, int by) {
        List<String> ls = new ArrayList<>();
        List<Runnable> as = new ArrayList<>();
        for (String t : TagEdit.TYPES) {
            ls.add(TagEdit.typeCn(t) + "  (" + t + ")");
            as.add(() -> {
                type = t;
                menu.close();
                refresh();
            });
        }
        menu.open(width, height, bx, by + 18, ls, as);
    }

    /** 值 = 一列 id（一行一个；原版 id 或 `#别的标签`）—— 用现成的多行小窗改。 */
    private void editValues() {
        Minecraft.getInstance().setScreen(new ComponentTextScreen(this, "标签值（一行一个 id）",
                TagEdit.valuesText(values), text -> {
                    values = TagEdit.valuesFromText(text);
                    status = "";
                    refresh();
                }));
    }

    private void refresh() {
        clearWidgets();
        init();
    }

    // ---------- 提交 ----------

    private void commit() {
        String nm = nameBox == null ? "" : nameBox.getValue().trim();
        if (nm.isEmpty()) {
            status = "标签名不能空";
            return;
        }
        if (!TagEdit.isName(nm)) {                  // ⚠ 名字**同时就是原版 id**（不改写）—— 非法名落下去 = 引用它的配方 / 交易整条报废
            status = "标签名同时是原版 id：只许 a-z 0-9 _ . - /（例 flint_materials）—— 中文 / 大写 / 空格都不行";
            return;
        }
        if (!nm.equals(name0) && parent.def.tags().has(nm)) {
            status = "已经有这个标签了：" + nm;
            return;
        }
        String err = parent.applyTag(nm, TagEdit.entry(type, values).toString());
        if (!err.isEmpty()) {
            status = err;                       // 不落盘：改的东西还在屏上，接着改
            return;
        }
        if (!name0.isEmpty() && !nm.equals(name0)) parent.applyTag(name0, "");   // 改名 = 写新的 + 删旧的
        leave();
    }

    private void delete() {
        if (!armed) {
            armed = true;
            status = "再点一次「确认删除」就删掉这个标签";
            refresh();
            return;
        }
        parent.applyTag(name0, "");
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
        g.centeredText(font, Component.literal("原版数据 · 标签 · " + (name0.isEmpty() ? "新建" : name0)),
                EditorRail.cx(this), 12, 0xFFFFFFFF);
        g.text(font, "标签名", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "挂到哪类", x, y + 5, 0xFFC0C0C0);
        y += ROW;
        g.text(font, "内容", x, y + 5, 0xFFC0C0C0);
        String nm = nameBox == null ? name0 : nameBox.getValue().trim();
        if (!nm.isEmpty()) {
            g.text(font, "别处引用它写：" + TagEdit.refText(type, nm) + "　（包内文件 " + TagEdit.packFile(type, nm) + "）",
                    x, height - 92, 0xFF909090);
        }
        g.text(font, "值写原版 id（minecraft:oak_planks）或 #别的标签；存盘后服务端把它同步进存档数据包",
                x, height - 80, 0xFF909090);
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
