package com.tablegame.editor.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.tablegame.core.ItemText;

/**
 * 「选基底物品」视图 —— 可滚动 + 可搜索的原版物品清单。
 * 两个屏共用（组件库屏 与 编辑器「组件」页）：只做搜索 + 列表 + 回调，位置由宿主给。
 * ponytail: 候选表一次性全建（约 1500 个原版物品）常驻；不做分类筛选 / 拼音 / 排序，真需要再说。
 */
public final class ItemBasePicker {
    /** 行高 / 图标边长 / 搜索框高 / 列表上下留白。 */
    private static final int ROW_H = 20, ICON = 16, BOX_H = 18, HEAD = 23;

    /** 候选表（懒建一次：全原版物品）。四张表按下标对齐 —— 自绘时只按下标取。 */
    private static Item[] items;
    private static ItemStack[] stacks;
    private static String[] ids;
    private static String[] names;

    private ListScroll scroll = new ListScroll(6);
    /** 候选过滤（null = 全要）。方块挑选屏用它把「不能当方块的物品」滤掉。 */
    private java.util.function.Predicate<Item> filter;

    /** 当前搜索结果（候选表下标）。 */
    private final List<Integer> shown = new ArrayList<>();
    private String query = "";
    private EditBox box;
    private int x, y, w;

    /** 候选表（第一次用时建；隐藏 {@code minecraft:air}）。 */
    private static void ensure() {
        if (items != null) {
            return;
        }
        List<Item> it = new ArrayList<>();
        for (Item i : BuiltInRegistries.ITEM) {
            if (i == Items.AIR) {
                continue;                          // 拿不起来，不当基底
            }
            it.add(i);
        }
        int n = it.size();
        items = new Item[n];
        stacks = new ItemStack[n];
        ids = new String[n];
        names = new String[n];
        for (int k = 0; k < n; k++) {
            Item i = it.get(k);
            Identifier id = BuiltInRegistries.ITEM.getKey(i);
            items[k] = i;
            stacks[k] = new ItemStack(i);
            ids[k] = id == null ? "" : id.toString();
            names[k] = stacks[k].getHoverName().getString();     // 客户端语言里的名字
        }
    }

    /** 装过滤（换视图时先装再 {@link #reset()}）。 */
    public void setFilter(java.util.function.Predicate<Item> f) {
        this.filter = f;
    }

    /** 换数据源（进这个视图）时清搜索 + 回顶。 */
    public void reset() {
        query = "";
        shown.clear();
        refilter();
        scroll.setTotal(shown.size());
    }

    /** 重新过滤（query 变了就调）。 */
    private void refilter() {
        ensure();
        shown.clear();
        for (int k = 0; k < items.length; k++) {
            if ((filter == null || filter.test(items[k])) && ItemText.matches(query, ids[k], names[k])) {
                shown.add(k);
            }
        }
        scroll.setTotal(shown.size());
    }

    /**
     * 摆这一视图（只摆搜索框 —— 行是自绘的）。宿主在 {@code init()} 里、处于该视图时调。
     *
     * @param availH 清单可用高度（一行 20px，留出底部按钮与「滚轮翻页」小标）
     * @param onTyped 每次输入变化后调（这里自会重筛；传 null 也行）
     */
    public void build(int x, int y, int w, int availH, Consumer<AbstractWidget> add, Runnable onTyped) {
        ensure();
        this.x = x;
        this.y = y;
        this.w = w;
        this.scroll = new ListScroll(Math.max(1, Math.min(12, (availH - HEAD) / ROW_H)));
        this.scroll.setTotal(shown.size());                // 换了个滚动件 → 总数得重设（不然一行都不画）
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, BOX_H, Component.literal("搜索"));
        b.setMaxLength(48);
        b.setHint(Component.literal("搜名称或 id（如 纸 / minecraft:paper）"));
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

    /** 列表第一行的 y（宿主自绘时也用它）。 */
    private int listTop() {
        return y + HEAD;
    }

    /** 滚轮（宿主转发）：真动了返回 true。 */
    public boolean scroll(double dy) {
        return scroll.scroll(dy);
    }

    /** 顶端小标（「3-14 / 812」）。 */
    public String label() {
        return scroll.label();
    }

    /**
     * 点击（宿主在 {@code mouseClicked} 里转发）——点在行上 = 选中那条基底。
     *
     * @return true = 这一下被吃掉了（在清单区域内）
     */
    public boolean click(double mx, double my, Consumer<String> onPick) {
        if (mx < x || mx >= x + w || my < listTop()) {
            return false;
        }
        int row = (int) ((my - listTop()) / ROW_H);
        if (row < 0 || row >= scroll.rows()) {
            return true;                                   // 清单空白处：吃掉但不动
        }
        int idx = shown.get(scroll.index(row));
        String id = ids[idx];
        if (!id.isEmpty()) {
            onPick.accept(id);
        }
        return true;
    }

    /** 自绘（宿主在 {@code super.extractRenderState} 之后…不用，画在控件之下更自然，见调用点）。 */
    public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int top = listTop();
        if (shown.isEmpty()) {
            g.text(font, Component.literal("（没有匹配的物品）"), x + 4, top + 4, 0xFF909090);
            return;
        }
        for (int row = 0; row < scroll.rows(); row++) {
            int idx = shown.get(scroll.index(row));
            int ry = top + row * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
            g.fill(x, ry, x + w, ry + ROW_H, hover ? 0xFF2E3138 : 0xFF21242B);
            g.outline(x, ry, w, ROW_H, hover ? 0xFF8A9A6A : 0xFF3A3E4A);
            g.item(stacks[idx], x + 2, ry + 2);                        // 真图标（物品栏那一套渲染）
            g.text(font, names[idx], x + ICON + 8, ry + 6, 0xFFFFFFFF);
            String id = ids[idx];
            g.text(font, Component.literal(id), x + w - font.width(id) - 6, ry + 6, 0xFF7A8090);
        }
        String lb = label();
        if (!lb.isEmpty()) {
            g.text(font, Component.literal("滚轮翻页　" + lb), x, top + scroll.rows() * ROW_H + 4, 0xFF909090);
        }
    }
}
