package com.tablegame.table;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.ListScroll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.drawboard.BoardManager;
import com.tablegame.editor.ScrollBar;
import com.tablegame.net.GamePackets;

/**
 * 游戏列表屏（/tablegame games）：新建（名字输入框）+ 全服游戏列表（打开/删除）。
 * 删除 = 两次点击确认（单机真删不可恢复，与画板列表同款）。服务端每次操作后回发列表 → 就地刷新。
 */
public class GamesScreen extends Screen {
    private List<GamePackets.GameInfoPayload> games;
    private EditBox nameBox;
    private String armedName = null;   // 等第二次点击确认删除的游戏名（null = 无）

    /** 列表滚动：一屏 9 行；滚轮换窗口后 init() 按 scroll.index(row) 重摆行。 */
    private final ListScroll scroll = new ListScroll(9);
    /** 滚动条：轨道几何在 init 里算好。 */
    private int barX, listY, listH;

    public GamesScreen(List<GamePackets.GameInfoPayload> games) {
        super(Component.literal("游戏列表"));
        this.games = games == null ? new ArrayList<>() : games;
    }

    /** 快照刷新：服务端列表到达后就地重建（惯例同 ProjectsScreen）。 */
    public void applyData(List<GamePackets.GameInfoPayload> games) {
        this.games = games;
        this.armedName = null;
        clearWidgets();
        init();
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = 52;

        // 新建行：名字输入框 + 创建按钮
        nameBox = new EditBox(font, cx - 140, y, 160, 20, Component.literal("游戏名"));
        nameBox.setMaxLength(com.tablegame.drawboard.BoardManager.MAX_NAME);
        nameBox.setHint(Component.literal("新游戏名（≤40 字符）"));
        addRenderableWidget(nameBox);
        addRenderableWidget(DrawBoardMenuUi.button(cx + 28, y, 112, 20, "新建游戏", this::create));
        y += 32;

        if (games.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22, "（还没有游戏——上面输入名字创建）", null));
            y += 30;
        } else {
            scroll.setTotal(games.size());
            barX = cx + 146;                               // 轨道贴行区右边（行宽 280 = cx±140）
            listY = y;
            listH = Math.max(ScrollBar.MIN_H, scroll.rows() * 26 - 4);
            for (int row = 0; row < scroll.rows(); row++) {
                int i = scroll.index(row);
                GamePackets.GameInfoPayload info = games.get(i);
                String label = info.name() + "   [" + info.cardCount() + " 卡 · " + info.deckCount() + " 牌组]";
                addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 200, 22, label, () ->
                        ClientPacketDistributor.sendToServer(new GamePackets.OpenGamePayload(info.name()))));
                boolean armed = info.name().equals(armedName);
                addRenderableWidget(DrawBoardMenuUi.button(cx + 66, y, 74, 22, armed ? "再点确认" : "删除", () -> {
                    if (!armed) {
                        armedName = info.name();
                        clearWidgets();
                        init();
                        return;
                    }
                    armedName = null;
                    ClientPacketDistributor.sendToServer(new GamePackets.DeleteGamePayload(info.name()));
                    // 服务端删完回发列表 → applyData 自动刷新
                }));
                y += 26;
            }
        }
        y += 6;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 24, "返回", () ->
                Minecraft.getInstance().setScreen(null)));
    }

    private void create() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            DrawBoardMenuUi.msg("[游戏] 请先填游戏名");
            return;
        }
        ClientPacketDistributor.sendToServer(new GamePackets.CreateGamePayload(name));
        // 成功后服务端发详情包（进详情屏）+ 回发列表（本屏若还在就刷新）
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        return barDrag(event.x(), event.y()) || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        return barDrag(event.x(), event.y()) || super.mouseDragged(event, dx, dy);
    }

    /** 点在滚动条上 / 拖滑块：换算成 top 再重建行（true = 这一下被条吃掉，别穿透到后面的按钮）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            clearWidgets();
            init();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (scroll.scroll(dy)) {          // 滚轮换一屏窗口 → 重建行（屏小、行少，重建最省事）
            clearWidgets();
            init();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
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
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        g.centeredText(font, Component.literal(title.getString()
                + (scroll.label().isEmpty() ? "" : "　" + scroll.label())), width / 2, 30, 0xFFFFFFFF);
        g.text(font, "定义存于 tablegame/games/<名>.json · 卡面美术引用画板项目",
                width / 2 - 150, height - 28, 0xFF909090);
    }
}
