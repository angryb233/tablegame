package com.tablegame.piece;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 蓝图命名窗口：Shift+Enter 确认后弹出，输入棋子名 → 发确认包。
 * 界面模式照抄 {@link com.tablegame.drawboard.NewProjectScreen}（EditBox + 半透明遮罩 + Enter 确认/Esc 取消）。
 * 名字为空 = 用默认名；确认后发送带名字的捕获包，服务端锁定蓝图。
 * 窗口打开时 BlueprintClient 的全局键监听因 {@code mc.screen != null} 自动失效，Enter 不会误触发。
 */
public class BlueprintNameScreen extends Screen {
    /** 名字长度上限（与物品名展示匹配，超长截断意义不大）。 */
    public static final int MAX_NAME = 32;

    private final InteractionHand hand;
    private EditBox nameBox;

    public BlueprintNameScreen(InteractionHand hand) {
        super(Component.translatable("screen.tablegame.blueprint_name.title"));
        this.hand = hand;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        nameBox = new EditBox(font, cx - 110, height / 2 - 10, 220, 20,
                Component.translatable("screen.tablegame.blueprint_name.title"));
        nameBox.setMaxLength(MAX_NAME);
        nameBox.setHint(Component.translatable("screen.tablegame.blueprint_name.hint"));
        addRenderableWidget(nameBox);
        setFocused(nameBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
        g.centeredText(font, Component.translatable("screen.tablegame.blueprint_name.help"),
                width / 2, height / 2 + 18, 0xFF909090);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257) {        // Enter → 发包确认
            confirm();
            return true;
        }
        if (event.key() == 256) {        // Esc → 取消（super 关窗口，选角保留可再调）
            return super.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    private void confirm() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            name = "棋子";
        }
        ClientPacketDistributor.sendToServer(new BlueprintPackets.ConfirmBlueprintPayload(
                hand == InteractionHand.OFF_HAND ? 1 : 0, name));
        Minecraft.getInstance().setScreen(null);
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
}