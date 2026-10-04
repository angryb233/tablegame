package com.tablegame.editor;

import com.tablegame.drawboard.ListScroll;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 列表 / 网格右边缘的滚动条。
 *
 * <p>算术在 {@link ListScroll}（{@code shows} / {@code bar} / {@code dragTo}），这里只管画与命中。
 * 同一件吃两种滚动单位：列表 = 一行一格（{@code rows} = 一屏几行、{@code total} = 总共几行）；
 * 网格 = 一页一格（{@code rows = 1}、{@code total} = 总页数）⇒ 滑块长 = 轨道/页数，点轨道即跳页。
 *
 * <p>屏幕用法（各 4 行）：
 * <pre>
 *   extractRenderState: ScrollBar.draw(g, barX(), barTop(), barH(), 16, scroll, mouseX, mouseY);
 *   mouseClicked / mouseDragged / mouseReleased: 让 barHit(...) 先吃一下（点了就 dragTo + 重建行）
 * </pre>
 */
public final class ScrollBar {
    /** 条宽（轨道 + 滑块同宽）。 */
    public static final int W = 7;
    /** 滑块最小高度（不然页数一多就细成一根线，点不住）。 */
    public static final int MIN_H = 16;

    private ScrollBar() { }

    /** 鼠标在条上吗（x 落在条带里、y 落在轨道里；不满一屏 = 没条 = 永远 false）。 */
    public static boolean hit(double mx, double my, int trackX, int trackY, int trackH, ListScroll sc) {
        return sc.shows() && mx >= trackX && mx < trackX + W && my >= trackY && my < trackY + trackH;
    }

    /** 画轨道 + 滑块（鼠标悬停在条上时滑块提亮，好知道「这里能拖」）。 */
    public static void draw(GuiGraphicsExtractor g, int trackX, int trackY, int trackH,
            ListScroll sc, int mouseX, int mouseY) {
        if (!sc.shows()) return;
        int[] b = sc.bar(trackY, trackH, MIN_H);
        g.fill(trackX, trackY, trackX + W, trackY + trackH, 0x33FFFFFF);          // 轨道
        g.fill(trackX, b[0], trackX + W, b[0] + b[1],
                hit(mouseX, mouseY, trackX, trackY, trackH, sc) ? 0xFFE6E6E6 : 0xFF9C9C9C);   // 滑块
    }
}
