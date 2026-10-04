package com.tablegame.drawboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 画板主菜单（/tablegame drawboard 进入）：
 * <ul>
 *   <li>新建项目 → 先要「我的组列表」，收到后进新建界面（含「创建到」组切换）
 *   <li>打开已有项目 → 请求项目列表 → {@link ProjectsScreen}
 *   <li>打开项目文件夹 → 资源管理器弹出本机 projects 目录
 *   <li>组管理 → 组列表 / 详情界面
 *   <li>制作卡牌 → 卡牌模式进新建项目（预填 30×42 卡面规格），画完存成画板项目，供玩法编辑器「添加卡牌」按 组/项目名 引用为卡面 / 卡背
 * </ul>
 */
public class DrawBoardMenuScreen extends Screen {
    /** 本次菜单是否从「制作卡牌」进入（true = 新建项目界面预填 30×42 卡面规格）。 */
    public boolean cardMode = false;

    public DrawBoardMenuScreen() {
        super(Component.literal("画板主菜单"));
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = height / 2 - 84;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 26, "新建项目", () ->
                ClientPacketDistributor.sendToServer(new BoardPackets.RequestGroupsPayload())));
        y += 36;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 26, "打开已有项目", () ->
                ClientPacketDistributor.sendToServer(new BoardPackets.RequestProjectsPayload())));
        y += 36;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 26, "打开项目文件夹", DrawBoardMenuUi::openLocalProjectsFolder));
        y += 36;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 26, "组管理", () ->
                ClientPacketDistributor.sendToServer(new BoardPackets.RequestGroupUiPayload())));
        y += 36;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, y, 220, 26, "制作卡牌（画卡面/卡背）", () -> {
            // 卡牌美术入口：自己变体带 cardMode 再要组列表 → 新建界面预填 30×42
            DrawBoardMenuScreen m = new DrawBoardMenuScreen();
            m.cardMode = true;
            Minecraft.getInstance().setScreen(m);
            ClientPacketDistributor.sendToServer(new BoardPackets.RequestGroupsPayload());
        }));
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
        g.centeredText(font, title, width / 2, height / 2 - 100, 0xFFFFFFFF);
        g.text(font, "项目文件存在游戏目录的 tablegame/projects/<组>/<项目名>.json",
                width / 2 - 160, height / 2 + 100, 0xFF909090);
    }
}
