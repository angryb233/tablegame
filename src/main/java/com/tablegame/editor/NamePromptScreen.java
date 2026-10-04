package com.tablegame.editor;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 通用命名小窗（Enter 确认 / Esc 取消）：EditBox + 半透明遮罩。
 */
public class NamePromptScreen extends Screen {

    private final Screen parent;
    private final String hint;
    private final Consumer<String> onOk;
    private final String initial;
    private EditBox box;

    /**
     * @param parent  确认/取消后回哪一屏
     * @param title   窗口标题
     * @param hint    输入框提示（说明这个名字会用到哪）
     * @param initial 预填值（改名时填旧名）
     * @param onOk    确认回调（空串也会回调 —— 要不要拒由调用方定）
     */
    public NamePromptScreen(Screen parent, String title, String hint, String initial, Consumer<String> onOk) {
        super(Component.literal(title));
        this.parent = parent;
        this.hint = hint;
        this.initial = initial == null ? "" : initial;
        this.onOk = onOk;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        box = new EditBox(font, cx - 110, height / 2 - 10, 220, 20, title);
        box.setMaxLength(32);
        box.setValue(initial);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        setFocused(box);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("Enter 确认 · Esc 取消"), width / 2, height / 2 + 18, 0xFF909090);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257) {                    // Enter = 确认
            String v = box == null ? "" : box.getValue().trim();
            Minecraft.getInstance().setScreen(parent);
            onOk.accept(v);
            return true;
        }
        if (event.key() == 256) {                    // Esc = 取消
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
