package com.tablegame.piece;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 「导入蓝图」浮层：一列滚动展示服务端发来的库列表（我的组库 + 导出目录），点选一条发
 * {@link ModelMakerPackets.ImportBlueprintPayload}。
 * 列表收包由 ModelMakerClient 路由到 {@link #applyEntries} 就地重建（不关浮层、不重开菜单）。
 * 真正写 PieceData 在服务端（客户端只发「选了第几条」）。
 */
public class ImportBlueprintOverlay extends ModelMakerOverlayScreen {
    /** 每页显示条数（面板高度由此反推）。 */
    private static final int ROWS = 5;

    private List<PieceLibrary.Entry> entries = List.of();
    /** 当前滚动起点（滚轮翻页）。 */
    private int scroll = 0;

    public ImportBlueprintOverlay(ModelMakerScreen parent) {
        super(parent, Component.literal("导入蓝图（选择一条）"));
        this.panelH = 34 + ROWS * 18;
    }

    /** 服务端列表到了（ModelMakerClient 路由进来）：重建列表行，滚回顶部。 */
    public void applyEntries(List<PieceLibrary.Entry> entries) {
        this.entries = entries;
        this.scroll = 0;
        rebuildRows();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        // 打开浮层就向服务端要列表（组过滤在服务端做——防伪造包扫别人的组）
        ClientPacketDistributor.sendToServer(new ModelMakerPackets.RequestLibraryPayload());
        rebuildRows();
    }

    /** 把当前可见范围的行建成可点 widget（翻页/换数据后重建）。 */
    private void rebuildRows() {
        // 只摘掉列表行：clearWidgets 会把 init 里加的都清掉，这里面板小、全清重建也无妨
        clearWidgets();
        // ⚠ clearWidgets 后 groupButton/nameBox 等不存在于本类；本浮层只有行 + 标题自绘
        int y = panelY + 22;
        int end = Math.min(entries.size(), scroll + ROWS);
        for (int i = scroll; i < end; i++) {
            final PieceLibrary.Entry e = entries.get(i);
            addRenderableWidget(new AbstractWidget(panelX + 10, y, panelW - 20, 16,
                    Component.literal(e.toString())) {
                @Override
                public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
                    // 只发「选了哪条」，服务端按名重扫文件读内容（条目本身可能已过期）。
                    // 组库条目带组前缀「组/名」（服务端二次校验组员资格）；导出条目裸名。
                    String payloadName = e.source().equals("导出") ? e.name() : e.source() + "/" + e.name();
                    ClientPacketDistributor.sendToServer(new ModelMakerPackets.ImportBlueprintPayload(
                            e.source().equals("导出"), payloadName));
                    // 点完直接关浮层回主界面；成败由服务端提示（蓝图槽物品同步刷新）
                    close();
                }

                @Override
                protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
                    // 自绘行：灰底 + 悬停高亮 + 文本（不用原版 Button，样式可控）
                    boolean hover = isHovered();
                    g.fill(getX(), getY(), getX() + width, getY() + height,
                            hover ? 0xFF8CB0E8 : 0xFFB0B0B0);
                    g.text(font, getMessage().getString(), getX() + 4, getY() + 4, 0xFF202020);
                }

                @Override
                protected void updateWidgetNarration(NarrationElementOutput out) {}
            });
            y += 18;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        // 基类把滚轮吃了防漏到底层，这里浮层自己翻页
        int max = Math.max(0, entries.size() - ROWS);
        int ns = Math.max(0, Math.min(max, scroll - (scrollY > 0 ? 1 : -1)));
        if (ns != scroll) {
            scroll = ns;
            rebuildRows();
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, title, panelX + 10, panelY + 8, 0xFF404040);
        if (entries.isEmpty()) {
            g.text(font, "（本组库和导出目录都是空的）", panelX + 10, panelY + 26, 0xFF707070);
        }
    }
}
