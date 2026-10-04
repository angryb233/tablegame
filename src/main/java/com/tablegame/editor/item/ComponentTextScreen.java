package com.tablegame.editor.item;

import java.util.function.Consumer;

import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 「改一个属性的值」小窗：半透明遮罩 + 居中 + Esc 取消，只是把 NamePromptScreen 的单行框换成多行框。
 *
 * <p>复杂组件（附魔 / 属性修饰符 / 食物…）的值本来就是一整棵树，单行框装不下也看不出层次；回车 = 换行。
 * 点【完成】把框里的原文交回调用方，它只改那一行的值，真正落盘还是编辑页【完成】那一次（唯一出口）。
 */
public class ComponentTextScreen extends Screen {

    private final Screen parent;
    private final String initial;
    private final Consumer<String> onOk;
    private MultiLineEditBox box;
    private final int limit;

    /**
     * @param parent  完成 / 取消后回哪一屏
     * @param title   窗口标题（一般是「组件：中文名（id）」）
     * @param initial 预填值（就是那一行现在的原文）
     * @param onOk    完成回调（原文，可能多行）
     */
    public ComponentTextScreen(Screen parent, String title, String initial, Consumer<String> onOk) {
        this(parent, title, initial, 2000, onOk);
    }

    /** 大容量版：原版 JSON 原文框那一类要用 —— 上限太小会把长 JSON 截断后写回 = 静默丢数据，所以上限做成参数。 */
    public ComponentTextScreen(Screen parent, String title, String initial, int limit, Consumer<String> onOk) {
        super(Component.literal(title));
        this.parent = parent;
        this.initial = initial == null ? "" : initial;
        this.onOk = onOk;
        this.limit = limit;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int w = Math.min(460, width - 60);
        int h = 90;
        box = MultiLineEditBox.builder()
                .setX(cx - w / 2)
                .setY(height / 2 - 44)
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(font, w, h, Component.literal(title.getString()));
        box.setCharacterLimit(limit);
        box.setValue(initial);
        addRenderableWidget(box);
        setFocused(box);
        addRenderableWidget(DrawBoardMenuUi.button(cx - 64, height / 2 + 56, 60, 18, "完成", this::ok));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 4, height / 2 + 56, 60, 18, "取消", this::cancel));
    }

    private void ok() {
        String v = box == null ? initial : box.getValue();
        Minecraft.getInstance().setScreen(parent);
        onOk.accept(v);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                    // Esc = 取消（回车是多行框的换行，不拦）
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 62, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("回车 = 换行 · 点【完成】写回"), width / 2, height / 2 + 80, 0xFF909090);
    }
}
