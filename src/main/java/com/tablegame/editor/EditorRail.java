package com.tablegame.editor;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.editor.data.VanillaDataScreen;
import com.tablegame.editor.pack.AssetPickerScreen;
import com.tablegame.editor.script.ScriptEditScreen;
import com.tablegame.editor.script.VarListScreen;
import com.tablegame.editor.stage.StageDirScreen;
import com.tablegame.editor.world.WorldTabScreen;

/**
 * 编辑器左栏（全屏编辑视窗的工具栏）——玩法编辑器内每个屏共用同一条栏。
 *
 * <pre>
 * int cx = EditorRail.cx(this);                              // 内容区中心（避开左栏）
 * EditorRail.drawBackdrop(g, height);                        // 渲染：画在 super 之前
 * EditorRail.build(parent, "变量", this::addRenderableWidget);  // init：把栏加进本屏
 * </pre>
 * add 是「把控件加进本屏」的回调（{@code Screen.addRenderableWidget} 是 protected，本类调不到宿主实例方法）。
 *
 * <p>⚠ 画布类屏须在 {@code extractRenderState} 里先画画布再 super，否则画布盖掉左栏（控件由 super 绘制，后画的在上层）。
 *
 * <p>ponytail: 栏没做折叠/图标/拖拽，就一列文字页签；窗口窄到装不下再加折叠。
 */
public final class EditorRail {
    /** 栏宽（逻辑像素）。页签名最长 3 个汉字，52 够用。 */
    public static final int W = 52;
    /** 内容区左边界（工具屏的提示行、画布都从这右边开始）。 */
    public static final int LEFT = W + 12;
    /** 第二列左边界（两级页签：「组件」页那种）。屏里 {@link #buildGenericAt} 传 {@link #LEFT}，内容区从 {@code LEFT2} 起排。 */
    public static final int LEFT2 = LEFT + W + 8;

    private static final int X = 4, ROW_H = 18, GAP = 2, TOP = 26;
    /** 总览页签名（点它回总览屏）。 */
    public static final String OVERVIEW = "总览";

    private EditorRail() {}

    /** 页签：栏上文字 + 开屏方式（每次点击新开一屏，共享同一份内存 def）。 */
    private record Tool(String label, Function<GameEditorScreen, Screen> open) {}

    /** 总览在最上（工具之前），下面才是一排工具。 */
    private static final List<Tool> TOOLS = List.of(
            new Tool(OVERVIEW, e -> e),
            new Tool("脚本", ScriptEditScreen::new),
            new Tool("舞台", StageDirScreen::new),
            // 「对象」：所有对象（卡牌 / 棋子 / 物品 / 方块 / 实体）的统一分类入口。
            new Tool("对象", ObjectPageScreen::new),
            new Tool("世界", WorldTabScreen::new),
            new Tool("变量", VarListScreen::new),
            new Tool("组件", AssetPickerScreen::new),
            // 【原版数据】：原版能自定义的那几样。
            new Tool(VanillaDataScreen.RAIL, VanillaDataScreen::new));

    /** 内容区中心 x（左栏右边那一块的中心）。 */
    public static int cx(Screen s) {
        return (LEFT + s.width) / 2;
    }

    /** 左栏底板：画在 super 之前（把栏下的画布遮掉，视觉上分出一个工作区）。 */
    public static void drawBackdrop(GuiGraphicsExtractor g, int height) {
        g.fill(0, 0, W + 8, height, 0xFF14161C);
        g.fill(W + 8, 0, W + 9, height, 0xFF3A3E4A);
    }

    /**
     * 把栏加进宿主屏（在 {@code init} 末尾调）。
     *
     * @param editor 编辑器（共享 def 的那个源；null 则不加栏）
     * @param active 当前页签名（总览 或四件工具之一）；null = 全部不高亮
     * @param add    宿主屏的控件注册回调（通常传 {@code this::addRenderableWidget}）
     */
    public static void build(GameEditorScreen editor, String active, Consumer<AbstractWidget> add) {
        if (editor == null) return;
        int y = TOP;
        for (Tool t : TOOLS) {
            boolean on = t.label().equals(active);
            // 当前页签不再可点（点了也是重开同一屏，白抖一下）
            add.accept(new Tab(X, y, W, ROW_H, t.label(), on, on ? null : () -> {
                Screen target = t.open().apply(editor);
                Minecraft.getInstance().setScreen(target);
                if (target == editor) editor.rebuildFromDef();   // 回总览：计数重建
            }));
            y += ROW_H + GAP;
        }
        y += 6;
        add.accept(new Tab(X, y, W, ROW_H, "保存", false, editor::saveToServer));
        add.accept(new Tab(X, y + ROW_H + GAP, W, ROW_H, "返回", false, editor::backToList));
    }

    /**
     * 通用左栏（不绑定游戏编辑器）——给不属于某个游戏的工作台用；视觉与编辑器左栏一致（共用 {@link Tab}）；底部【返回】由调用方自己加。
     *
     * @param labels 页签文字（顺序 = 从上到下）
     * @param active 当前高亮项（高亮项不再可点）；null = 全不高亮
     * @param onPick 点页签回调
     * @param add    宿主屏的控件注册回调（通常传 {@code this::addRenderableWidget}）
     * @return 栏底部的 y（调用方接着往下摆自己的按钮）
     */
    public static int buildGeneric(String[] labels, String active, Consumer<String> onPick, Consumer<AbstractWidget> add) {
        return buildGenericAt(X, labels, active, onPick, add);
    }

    /** 同 {@link #buildGeneric}，但页签栏摆在指定 x（做「第二列」用）。 */
    public static int buildGenericAt(int x, String[] labels, String active,
            Consumer<String> onPick, Consumer<AbstractWidget> add) {
        int y = TOP;
        for (String label : labels) {
            boolean on = label.equals(active);
            final String pick = label;
            add.accept(new Tab(x, y, W, ROW_H, label, on, on ? null : () -> onPick.accept(pick)));
            y += ROW_H + GAP;
        }
        return y;
    }

    /** 左栏页签（带 active 高亮：底色变 + 左侧亮条）。 */
    public static class Tab extends AbstractWidget {
        private final boolean active;
        private final Runnable onPress;

        Tab(int x, int y, int w, int h, String label, boolean active, Runnable onPress) {
            super(x, y, w, h, Component.literal(label));
            this.active = active;
            this.onPress = onPress;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            int bg = active ? 0xFF37402E : (isHovered() ? 0xFF2E3138 : 0xFF21242B);
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            if (active) g.fill(getX(), getY(), getX() + 3, getY() + getHeight(), 0xFFE0C060);
            g.text(font, getMessage().getString(), getX() + 8, getY() + (getHeight() - 9) / 2,
                    active ? 0xFFFFFFFF : 0xFFC8C8D0);
            g.outline(getX(), getY(), getWidth(), getHeight(), active ? 0xFF6A7A4A : 0xFF3A3E4A);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (onPress != null) onPress.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
