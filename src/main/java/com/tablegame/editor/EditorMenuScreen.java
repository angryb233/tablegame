package com.tablegame.editor;

import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.editor.pack.AssetLibraryScreen;
import com.tablegame.host.ClientGameHandler;
import com.tablegame.net.GamePackets;
import com.tablegame.table.GamesScreen;

/**
 * 桌游编辑器入口（/tablegame games 第一屏）：两个大分区 —— 【组件库】与【游戏列表】。
 *
 * <p>路由：服务端回游戏列表包时屏为空才开本屏（见 {@code ClientGameHandler.onGamesList}）。
 */
public class EditorMenuScreen extends Screen {
    public EditorMenuScreen() {
        super(Component.literal("桌游编辑器"));
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = height / 2 - 60;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 120, y, 240, 28,
                "组件库 — 打包 / 导入组件（无属性）",
                () -> Minecraft.getInstance().setScreen(new AssetLibraryScreen())));
        y += 38;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 120, y, 240, 28,
                "游戏列表 — 打开 / 新建游戏", this::openGames));
        y += 46;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 120, y, 240, 24, "返回", () ->
                Minecraft.getInstance().setScreen(null)));
    }

    /** 开游戏列表屏：先用缓存垫上，再要最新列表（包到刷新）。 */
    private void openGames() {
        ClientPacketDistributor.sendToServer(new GamePackets.RequestGamesPayload());
        Minecraft.getInstance().setScreen(new GamesScreen(ClientGameHandler.games()));
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Component.literal("桌游编辑器"), width / 2, 34, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("组件是积木（跨游戏复用），游戏是用积木搭出来的玩法定义"),
                width / 2, height / 2 - 76, 0xFF909090);
        g.centeredText(font, Component.literal("游戏定义存于 tablegame/games/<名>.json"),
                width / 2, height - 30, 0xFF909090);
    }
}
