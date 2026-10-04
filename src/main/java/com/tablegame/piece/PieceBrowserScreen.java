package com.tablegame.piece;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.drawboard.BoardManager;

/**
 * 蓝图库菜单（/tablegame pieces）：查看库里有哪些蓝图 / 删除 / 打开文件位置。
 * 数据 = 服务端扫「我的组库 + 导出目录」后整表下发（快照模式），收包在 {@link ModelMakerClient} 路由到 {@link #applyData}；
 * 删除后服务端无条件重发列表。「打开文件夹」仅单机/局域网集成服显示（专用服文件夹在服务器磁盘，客户端打不开）。
 */
public class PieceBrowserScreen extends Screen {
    /** 每页行数。 */
    private static final int ROWS = 8;

    /** 库条目快照（names/sources 平行表 → 合并行对象；dedicated 随包）。 */
    private record Row(String name, String source) {}

    private List<Row> rows = new ArrayList<>();
    private boolean dedicated = false;
    private int scroll = 0;
    /** 等第二次点击确认删除的行（绝对下标；-1 = 无）。 */
    private int armedIndex = -1;

    public PieceBrowserScreen() {
        super(Component.literal("蓝图库"));
    }

    /** 收到服务端列表快照（ModelMakerClient 路由进来）：重建，滚回顶部。 */
    public void applyData(List<String> names, List<String> sources, boolean dedicated) {
        rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            rows.add(new Row(names.get(i), i < sources.size() ? sources.get(i) : ""));
        }
        this.dedicated = dedicated;
        this.scroll = 0;
        this.armedIndex = -1;
        clearWidgets();
        init();
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = 52;

        int shown = Math.min(rows.size(), ROWS);
        for (int i = scroll; i < shown; i++) {
            Row r = rows.get(i);
            int idx = i;
            // 行：组/名 标签（导出目录条目无组概念）
            String label = "导出".equals(r.source()) ? r.name() + "（导出）" : r.source() + "/" + r.name();
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 5, 190, DrawBoardMenuUi.ellipsis(label, 24), 0xFFE0E0E0));
            boolean armed = idx == armedIndex;
            addRenderableWidget(DrawBoardMenuUi.button(cx + 56, y, 76, 20, armed ? "再点确认" : "删除", () -> {
                if (idx != armedIndex) {
                    armedIndex = idx;
                    clearWidgets();
                    init();
                    return;
                }
                Row row = rows.get(idx);
                ClientPacketDistributor.sendToServer(new ModelMakerPackets.BrowserDeletePayload(
                        "导出".equals(row.source()) ? "" : row.source(), row.name()));
                armedIndex = -1;
                // 列表刷新由服务端删后重发触发（快照刷新惯例），本地不清
            }));
            y += 24;
        }
        if (rows.size() > shown) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, y, 280, 20,
                    "...还有 " + (rows.size() - shown) + " 个（滚轮翻页）", null));
            y += 24;
        }
        if (rows.isEmpty()) {
            addRenderableWidget(DrawBoardMenuUi.label(cx - 140, y + 6, 280, "（库是空的：去模型制作器保存蓝图）", 0xFF909090));
            y += 24;
        }

        // 「打开文件夹」：仅单机/局域网集成服（专用服的文件夹在服务器磁盘上）
        if (!dedicated) {
            addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 66, 280, 22, "打开文件所在位置", this::openFolder));
        }
        addRenderableWidget(DrawBoardMenuUi.button(cx - 140, height - 38, 280, 24, "关闭", this::onClose));
    }

    /** 打开 pieces 库目录（组库 + export 导出目录的父目录 tablegame/pieces）。 */
    private void openFolder() {
        var lib = com.tablegame.drawboard.BoardManager.get() == null
                ? null : com.tablegame.drawboard.BoardManager.get().pieceLibrary();
        if (lib == null) return;
        try {
            var path = lib.piecesRoot();
            java.nio.file.Files.createDirectories(path);
            new ProcessBuilder("explorer.exe", path.toAbsolutePath().toString()).start();
        } catch (Exception e) {
            DrawBoardMenuUi.msg("[蓝图库] 打开文件夹失败: " + e.getMessage());
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() - ROWS);
        int ns = Math.max(0, Math.min(max, scroll - (scrollY > 0 ? 1 : -1)));
        if (ns != scroll) {
            scroll = ns;
            armedIndex = -1;
            clearWidgets();
            init();
        }
        return true;
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
        g.centeredText(font, Component.literal("蓝图库（" + rows.size() + "）"), width / 2, 30, 0xFFFFFFFF);
    }
}
