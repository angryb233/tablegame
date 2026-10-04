package com.tablegame.drawboard;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.editor.ScrollBar;

/**
 * 「打开已有项目」列表界面：显示服务器返回的项目（按组分组排序），点「打开」进编辑器。
 * 每行右侧「删除」两次点击确认：真实组仅组长/管理员、单人组仅本人（服务端再校验）；
 * 删除处置在服务端：单机/局域网彻底删；专用服务器移入 tablegame/trash/。
 */
public class ProjectsScreen extends Screen {
    private final Screen previous;
    private final List<BoardPackets.ProjectInfoPayload> projects;
    private String armedKey = null;   // 正在等第二次点击确认删除的项目 key（null = 无）

    /** 列表滚动：一屏 14 行；滚轮换窗口后 init() 按 scroll.index(row) 重摆行。 */
    private final ListScroll scroll = new ListScroll(14);
    /** 滚动条：轨道贴行区右边。 */
    private int barX, listY, listH;

    /** 点在滚动条上 / 拖滑块：换 top 再重建（true = 这一下被条吃掉）。 */
    private boolean barDrag(double mx, double my) {
        if (!ScrollBar.hit(mx, my, barX, listY, listH, scroll)) return false;
        if (scroll.dragTo(listY, listH, ScrollBar.MIN_H, my)) {
            clearWidgets();
            init();
        }
        return true;
    }


    public ProjectsScreen(Screen previous, List<BoardPackets.ProjectInfoPayload> projects) {
        super(Component.literal("打开已有项目"));
        this.previous = previous;
        this.projects = projects;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = 70;
        if (projects.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 22,
                    "（还没有项目——先「新建项目」）", null));
            y += 30;
        } else {
            scroll.setTotal(projects.size());
            for (int row = 0; row < scroll.rows(); row++) {
                final BoardPackets.ProjectInfoPayload p = projects.get(scroll.index(row));
                final String key = p.group() + "/" + p.name();
                String label = p.group() + " / " + p.name()
                        + (p.canEdit() ? "" : " 【只读】")
                        + "   [" + p.width() + "×" + p.height() + " · " + p.ownerName() + "]";
                // 左：打开
                addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, p.canDelete() ? 200 : 280, 22, label, () ->
                        ClientPacketDistributor.sendToServer(new BoardPackets.OpenProjectPayload(key))));
                // 右：删除（canDelete=组长/管理员/单人组本人；两次点击确认防误删，单人组真删不可恢复）
                if (p.canDelete()) {
                    boolean armed = key.equals(armedKey);
                    addRenderableWidget(DrawBoardMenuUi.button(cx + 66, y, 74, 22, armed ? "再点确认" : "删除", () -> {
                        if (!armed) {
                            armedKey = key;
                            clearWidgets();
                            init();
                            return;
                        }
                        armedKey = null;
                        ClientPacketDistributor.sendToServer(new BoardPackets.DeleteProjectPayload(key));
                        // 服务端删完会重发项目列表 → 本屏自动刷新（列表里该行消失）
                    }));
                }
                y += 26;
            }
            if (!scroll.label().isEmpty()) {
                y += 4;
                addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y, 280,
                        "滚轮查看更多　" + scroll.label(), 0xFF707070));
                y += 26;
            }
        }
        y += 6;
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 24, "返回", () ->
                Minecraft.getInstance().setScreen(previous)));
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
        barX = Math.max(8, width - 12);
        listY = 70;
        listH = Math.max(ScrollBar.MIN_H, Math.min(height - 70 - 92, Math.max(1, scroll.rows()) * 26));
        ScrollBar.draw(g, barX, listY, listH, scroll, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, Component.literal(title.getString()
                + (scroll.label().isEmpty() ? "" : "　" + scroll.label())), width / 2, 40, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (barDrag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }
}
