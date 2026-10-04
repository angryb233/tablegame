package com.tablegame.editor.world;

import java.util.function.Consumer;

import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 「编辑方块」界面：从区域视口右键「编辑」进来，编辑选中那一格。
 * 本轮只做实换方块（点「换方块…」开 {@link BlockPickScreen}，选中即应用回视口）；其余功能先占位（按钮写「（占位）」，点了只提示，不做假动作）。
 * 数据流：本屏不持有区域，改动由 {@code onChange} 回调交给视口（它持副本 + 写回编辑器内存模型）；「完成 / Esc」回同一个视口实例。
 */
final class BlockEditScreen extends Screen {

    private final Screen back;
    /** 被编辑格的位置文案（如 "(3, 1, 7)"）。 */
    private final String coord;
    /** 换方块回调：方块 id 字符串。 */
    private final Consumer<String> onChange;
    /** 当前方块 id（换完就地更新显示；视口那边才是真源）。 */
    private String current;

    private static final int ROW_H = 24, W = 240;

    BlockEditScreen(Screen back, String coord, String currentId, Consumer<String> onChange) {
        super(Component.literal("编辑方块"));
        this.back = back;
        this.coord = coord;
        this.current = currentId;
        this.onChange = onChange;
    }

    @Override
    protected void init() {
        clearWidgets();
        int x = width / 2 - W / 2;
        int y = 76;
        addRenderableWidget(DrawBoardMenuUi.button(x, y, W, 20, "换方块…", this::pickBlock));
        y += ROW_H;
        addRenderableWidget(DrawBoardMenuUi.button(x, y, W, 20, "方块朝向 / 属性（占位）",
                () -> DrawBoardMenuUi.msg("[世界] 方块朝向/属性：占位，方案定了再做")));
        y += ROW_H;
        addRenderableWidget(DrawBoardMenuUi.button(x, y, W, 20, "整片同款替换（占位）",
                () -> DrawBoardMenuUi.msg("[世界] 整片同款替换：占位，方案定了再做")));
        y += ROW_H;
        addRenderableWidget(DrawBoardMenuUi.button(x, y, W, 20, "复制此方块（占位）",
                () -> DrawBoardMenuUi.msg("[世界] 复制此方块：占位，方案定了再做")));
        y += ROW_H + 8;
        addRenderableWidget(DrawBoardMenuUi.button(x, y, W, 20, "完成", () -> Minecraft.getInstance().setScreen(back)));
    }

    /** 「换方块…」：开挑方块屏（复用组件库那件搜索清单），选中即应用回视口 + 更新本屏显示。 */
    private void pickBlock() {
        Minecraft.getInstance().setScreen(new BlockPickScreen(this, id -> {
            onChange.accept(id);
            current = id;
        }));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                     // Esc = 完成（回视口）
            Minecraft.getInstance().setScreen(back);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xE0101218);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, Component.literal("编辑方块　" + coord), width / 2, 24, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("当前：" + current), width / 2, 44, 0xFFC8D0E0);
        g.centeredText(font, Component.literal("（换了方块就回视口看效果 · 未保存的改动回世界页签点「保存」）"),
                width / 2, 58, 0xFF7A8090);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
