package com.tablegame.drawboard;

/**
 * 局内临时画板的客户端像素缓存（HUD 的「画板（本局）」框用它，不开屏）。
 * 按「收到的最新一块板」存，key 对不上就丢掉笔迹（换了板别把旧笔迹画上去）。
 *
 * <p>纯逻辑（不碰任何 MC 类型），自检脚本能直接跑。
 */
public final class StageBoardCache {
    private StageBoardCache() {}

    private static String key = "";
    private static int width, height;
    private static int[] pixels;

    /** 全量：换了一块板就整块重建（开局/重开都走这里）。 */
    public static void setFull(String newKey, int w, int h, byte[] cells) {
        key = newKey == null ? "" : newKey;
        width = w;
        height = h;
        pixels = (w > 0 && h > 0) ? new int[w * h] : null;
        apply(cells);
    }

    /** 增量：只认当前这块板的笔迹。 */
    public static void applyCells(String cellKey, byte[] cells) {
        if (pixels == null || cellKey == null || !cellKey.equals(key)) return;
        apply(cells);
    }

    /** 对局结束：连板一起忘掉（HUD 下一帧就不画这个框了）。 */
    public static void clear() {
        key = "";
        width = 0;
        height = 0;
        pixels = null;
    }

    /** 本局画板的 key（发笔迹时要带上；空 = 还没收到板）。 */
    public static String key() {
        return key;
    }

    public static boolean ready() {
        return pixels != null;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    /** 像素真源（没板时 null）。 */
    public static int[] pixels() {
        return pixels;
    }

    /** 格子字节流 → 像素；越界格直接丢（包尺寸与当前板不符时不能越界写）。 */
    private static void apply(byte[] cells) {
        if (pixels == null || cells == null) return;
        for (int off = 0; off + 8 <= cells.length; off += 8) {
            int x = Cells.u16(cells, off);
            int y = Cells.u16(cells, off + 2);
            if (x >= width || y >= height) continue;
            pixels[y * width + x] = Cells.argbFromBytes(cells, off + 4);
        }
    }
}
