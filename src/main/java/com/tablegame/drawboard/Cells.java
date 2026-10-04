package com.tablegame.drawboard;

/**
 * 单元格字节流编解码工具。
 *
 * <p>全 mod 共用同一种「格子」线上格式：每格固定 <b>8 字节小端</b>：
 * <pre>
 *   [0..1] u16 x   [2..3] u16 y   [4..7] i32 ARGB 颜色
 * </pre>
 * 笔迹增量、撤销还原、整板同步都是这种字节流。顺带提供 ARGB 格式化与 ABGR 转换（NativeImage 是 ABGR 序）。
 */
public final class Cells {
    private Cells() {}

    /** 读 i32 ARGB（小端）。 */
    public static int argbFromBytes(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /** 读 u16（x 或 y，小端）。 */
    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** 写一格 (x, y, ARGB)。 */
    public static void write(byte[] out, int off, int x, int y, int argb) {
        out[off]     = (byte) x;
        out[off + 1] = (byte) (x >>> 8);
        out[off + 2] = (byte) y;
        out[off + 3] = (byte) (y >>> 8);
        out[off + 4] = (byte) argb;
        out[off + 5] = (byte) (argb >>> 8);
        out[off + 6] = (byte) (argb >>> 16);
        out[off + 7] = (byte) (argb >>> 24);
    }

    /** 整板像素 → 全量格子字节流（打开/刷新画板时一次性下发用）。 */
    public static byte[] fullBoardCells(int width, int height, int[] pixels) {
        byte[] out = new byte[pixels.length * 8];
        int off = 0;
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                write(out, off, x, y, pixels[base + x]);
                off += 8;
            }
        }
        return out;
    }

    /** ARGB int → 8 位十六进制（aarrggbb，存档/导出用）。 */
    public static String toHex8(int argb) {
        String s = Integer.toHexString(argb);
        return "00000000".substring(s.length()) + s;
    }

    /** 8 位十六进制 → ARGB int。 */
    public static int fromHex8(String hex) {
        return (int) Long.parseLong(hex, 16);
    }
}
