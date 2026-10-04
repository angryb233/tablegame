package com.tablegame.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import com.tablegame.core.ItemText;

/**
 * 「搜索 + 可滚动候选行」清单件。
 *
 * <p>纯文本行；显示名外面给（回调函数），拿不到中文名就返回 id 本身（这一格就按暗色画）。
 */
final class IdPickUi {

    private static final int ROW_H = 18;
    private static final int BOX_H = 18;
    private static final int HEAD = 23;

    private String[] ids = new String[0];
    private String[] names = new String[0];
    private final List<Integer> shown = new ArrayList<>();
    private String query = "";
    private String hintText = "搜索";
    private String emptyNote = "（没有匹配的）";
    private ListScroll scroll = new ListScroll(6);
    private EditBox box;
    private int x;
    private int y;
    private int w;

    /** 搜索框里那行提示。 */
    void hint(String h) {
        this.hintText = h;
    }

    /** 什么都没搜到时那句话。 */
    void emptyNote(String s) {
        this.emptyNote = s;
    }

    /** 换数据源（进屏时调一次）：「写进声明的 id」表 + 显示名函数。 */
    void setRows(String[] newIds, Function<String, String> nameOf) {
        this.ids = newIds;
        this.names = new String[newIds.length];
        for (int k = 0; k < newIds.length; k++) {
            String n = nameOf == null ? null : nameOf.apply(newIds[k]);
            names[k] = n == null || n.isEmpty() ? newIds[k] : n;
        }
        this.query = "";
        refilter();
    }

    private void refilter() {
        shown.clear();
        for (int k = 0; k < ids.length; k++) {
            if (ItemText.matches(query, ids[k], names[k])) {
                shown.add(k);
            }
        }
        scroll.setTotal(shown.size());
    }

    void build(int x, int y, int w, int availH, Consumer<AbstractWidget> add, Runnable onTyped) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.scroll = new ListScroll(Math.max(1, Math.min(14, (availH - HEAD) / ROW_H)));
        this.scroll.setTotal(shown.size());
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, BOX_H, Component.literal("搜索"));
        b.setMaxLength(48);
        b.setHint(Component.literal(hintText));
        b.setValue(query);
        b.setResponder(s -> {
            query = s;
            refilter();
            if (onTyped != null) {
                onTyped.run();
            }
        });
        this.box = b;
        add.accept(b);
    }

    /** 搜索框（宿主想恢复焦点时用）。 */
    EditBox box() {
        return box;
    }

    boolean scroll(double dy) {
        return scroll.scroll(dy);
    }

    /** @return true = 这一下被吃掉了（落在清单区域内） */
    boolean click(double mx, double my, Consumer<String> onPick) {
        int top = y + HEAD;
        if (mx < x || mx >= x + w || my < top) {
            return false;
        }
        int row = (int) ((my - top) / ROW_H);
        if (row < 0 || row >= scroll.rows()) {
            return true;                                     // 清单空白处：吃掉但不动
        }
        String id = ids[shown.get(scroll.index(row))];
        if (!id.isEmpty()) {
            onPick.accept(id);
        }
        return true;
    }

    void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int top = y + HEAD;
        if (shown.isEmpty()) {
            g.text(font, Component.literal(emptyNote), x + 4, top + 4, 0xFF909090);
            return;
        }
        for (int row = 0; row < scroll.rows(); row++) {
            int k = shown.get(scroll.index(row));
            int ry = top + row * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
            g.fill(x, ry, x + w, ry + ROW_H, hover ? 0xFF2E3138 : 0xFF21242B);
            g.outline(x, ry, w, ROW_H, hover ? 0xFF8A9A6A : 0xFF3A3E4A);
            boolean named = !names[k].equals(ids[k]);        // 有中文名的画亮、只有 id 的暗一号
            g.text(font, Component.literal(names[k]), x + 6, ry + 5, named ? 0xFFFFFFFF : 0xFFC8C8D0);
            String id = ids[k];
            if (named) {
                g.text(font, Component.literal(id), x + w - font.width(id) - 6, ry + 5, 0xFF7A8090);
            }
        }
        String lb = scroll.label();
        if (!lb.isEmpty()) {
            g.text(font, Component.literal("滚轮翻页　" + lb), x, top + scroll.rows() * ROW_H + 4, 0xFF909090);
        }
    }
}
