package com.tablegame.drawboard;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 画布组件层 —— 「一维像素数组 → 屏幕矩形」的唯一渲染实现（客户端专用）。
 *
 * <p>抽出来是为让舞台 HUD（{@code StageHudLayer} 的「本局画板」框）与本屏共用同一份
 * （棋盘底纹色、平灰降级阈值、run 合并边界）。
 *
 * <p>只遍历区域内可见行列；已画格按「同行同显示色」run 合并成一条矩形；每格不足 {@link #CHECKER_MIN_CELL}
 * 像素时棋盘不可辨 → 整板平灰 + 跳过空格。⚠ 26.x：GUI 里不 blit 自定义纹理，全部走 {@code fill} 矩形。
 */
public final class BoardCanvas {
    private BoardCanvas() {}

    /** 空格（透明像素）的棋盘底纹色 A —— 2×2 格同色一块，(块行 ^ 块列) 奇偶分色。 */
    public static final int EMPTY_A = 0xFF808080;
    /** 棋盘底纹色 B。 */
    public static final int EMPTY_B = 0xFFC0C0C0;
    /** 每格小于这么多像素时，棋盘底纹不可辨 → 整板平灰。 */
    public static final int CHECKER_MIN_CELL = 3;

    /** 一格该显示什么色：已画格 → 本色；空格（alpha=0）→ 棋盘块色。 */
    public static int displayColor(int x, int y, int raw) {
        if ((raw & 0xFF000000) != 0) return raw;
        return (((x >> 1) ^ (y >> 1)) & 1) == 0 ? EMPTY_A : EMPTY_B;
    }

    /**
     * 把一整块板画进一个屏幕矩形。
     *
     * @param g      绘制目标
     * @param pixels 像素真源（长度 = bw*bh，ARGB；下标 = y*bw + x）
     * @param bw     板宽（格）
     * @param bh     板高（格）
     * @param ax     区域左上角 x（屏幕坐标）
     * @param ay     区域左上角 y
     * @param aw     区域宽（像素）
     * @param ah     区域高（像素）
     * @param offX   内容左边缘相对区域左边缘的偏移（画板屏 = 平移量；HUD = 居中留边）
     * @param offY   同上、纵向
     * @param cell   每格像素数（画板屏 = 缩放级；HUD = 自适应算出，可以是小数）
     * @param clip   true = 裁到区域内（画板屏：视口外不溢出到工具栏）｜false = 不裁
     */
    public static void render(GuiGraphicsExtractor g, int[] pixels, int bw, int bh,
                              int ax, int ay, int aw, int ah,
                              double offX, double offY, double cell, boolean clip) {
        if (pixels == null || bw <= 0 || bh <= 0 || aw <= 0 || ah <= 0 || cell <= 0) return;
        if (clip) g.enableScissor(ax, ay, ax + aw, ay + ah);

        int ox0 = ax + (int) Math.round(offX);        // 内容左上角屏幕坐标
        int oy0 = ay + (int) Math.round(offY);

        // 只遍历区域内可见的行列：可见边界 = 区域边界换算回内容坐标（±1 格容差吸收取整误差）
        int colA = Math.max(0, (int) Math.floor((-offX) / cell) - 1);
        int colB = Math.min(bw, (int) Math.ceil((aw - offX) / cell) + 1);
        int rowA = Math.max(0, (int) Math.floor((-offY) / cell) - 1);
        int rowB = Math.min(bh, (int) Math.ceil((ah - offY) / cell) + 1);

        // 板底先整块铺 A 色：棋盘模式下每格随后都会被覆盖，此铺底只服务平灰模式
        boolean checker = cell >= CHECKER_MIN_CELL;
        g.fill(ox0, oy0, ox0 + (int) Math.round(bw * cell), oy0 + (int) Math.round(bh * cell), EMPTY_A);

        // 逐格绘制：空格（透明）→ 棋盘块色，已画格 → 本色；同行同色合并成一条矩形
        for (int gy = rowA; gy < rowB; gy++) {
            int base = gy * bw;
            int rx = colA;
            while (rx < colB) {
                int raw = pixels[base + rx];
                if ((raw & 0xFF000000) == 0 && !checker) {
                    rx++;   // 平灰模式：空格已被整板铺底盖住，无需逐格画
                    continue;
                }
                int color = displayColor(rx, gy, raw);
                int runEnd = rx + 1;
                while (runEnd < colB && displayColor(runEnd, gy, pixels[base + runEnd]) == color) runEnd++;
                g.fill(ox0 + (int) (rx * cell), oy0 + (int) (gy * cell),
                        ox0 + (int) (runEnd * cell), oy0 + (int) ((gy + 1) * cell), color);
                rx = runEnd;
            }
        }
        if (clip) g.disableScissor();
    }
}
