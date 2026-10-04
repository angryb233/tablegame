package com.tablegame.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕空间直线的**光栅化**（纯逻辑，进自检）—— GUI 没有画线原语，而 3D 视口的地面网格全是斜线（逐像素打点是每帧上万次 {@code fill}，缩放越大越惨）。
 * 两条纪律（改这条链前先读）：① **先按视口矩形裁剪**（Liang–Barsky）——屏外那一大截一个 fill 都不该花；② **沿次轴扫描 + 限流**：
 * 横线逐「行」、竖线逐「列」，每格填一个覆盖该格的跨度矩形（线看着仍是 1px 连续）；主轴长度超 {@code budget} 时按比例跳格 ⇒ **单线 fill 数有上界，且与缩放无关**。
 * 纯逻辑 ⇒ 自检能直接编译它并**当场数出 fill 个数**（回归闸门：网格总 fill 超上限就红）。
 */
public final class LineRaster {

    private LineRaster() {
    }

    /** 单线每帧的 fill 预算（默认值；自检按它钉「网格总 fill ≤ 1200」这条回归线）。 */
    public static final int BUDGET = 96;

    /**
     * 线段按矩形裁剪（Liang–Barsky）：返回 {@code [x0, y0, x1, y1]}；整段在外 = {@code null}。
     */
    public static float[] clip(float[] a, float[] b, int rx0, int ry0, int rx1, int ry1) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float t0 = 0.0F, t1 = 1.0F;
        float[] p = { -dx, dx, -dy, dy };
        float[] q = { a[0] - rx0, rx1 - a[0], a[1] - ry0, ry1 - a[1] };
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0F) {
                if (q[i] < 0.0F) {
                    return null;                                   // 平行且在框外
                }
                continue;
            }
            float r = q[i] / p[i];
            if (p[i] < 0.0F) {
                if (r > t1) return null;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return null;
                if (r < t1) t1 = r;
            }
        }
        return new float[] { a[0] + t0 * dx, a[1] + t0 * dy, a[0] + t1 * dx, a[1] + t1 * dy };
    }

    /**
     * 光栅化一条线：返回若干 {@code {x0, y0, x1, y1}} 填充矩形（已裁剪、已限流、已按 {@code thick} 加粗）。
     * 屏外 = 空表。
     */
    public static List<int[]> fills(float[] a, float[] b, int rx0, int ry0, int rx1, int ry1,
            int thick, int budget) {
        List<int[]> out = new ArrayList<>();
        float[] s = clip(a, b, rx0, ry0, rx1, ry1);
        if (s == null) {
            return out;
        }
        float dx = s[2] - s[0], dy = s[3] - s[1];
        int ax = (int) Math.abs(Math.round(dx)), ay = (int) Math.abs(Math.round(dy));
        int major = Math.max(ax, ay);
        if (major < 1) {                                           // 一个点
            out.add(new int[] { (int) Math.floor(s[0]), (int) Math.floor(s[1]),
                    (int) Math.floor(s[0]) + thick, (int) Math.floor(s[1]) + thick });
            return out;
        }
        int step = Math.max(1, (major + budget - 1) / budget);      // 限流：单线 ≤ budget 个 fill
        boolean byRow = ay <= ax;                                   // 横线逐行；竖线逐列
        int span = byRow ? ay : ax;
        int other = byRow ? ax : ay;
        for (int i = 0; i <= span; i += step) {
            float t = span == 0 ? 0.0F : i / (float) span;
            float px = s[0] + dx * t, py = s[1] + dy * t;
            float cover = (float) other / Math.max(1, span) * step; // 这一步覆盖到的那点宽度
            if (byRow) {
                float x1 = px + (dx < 0 ? -cover : cover);
                out.add(new int[] { (int) Math.floor(Math.min(px, x1)), (int) Math.round(py),
                        (int) Math.ceil(Math.max(px, x1)), (int) Math.round(py) + thick });
            } else {
                float y1 = py + (dy < 0 ? -cover : cover);
                out.add(new int[] { (int) Math.round(px), (int) Math.floor(Math.min(py, y1)),
                        (int) Math.round(px) + thick, (int) Math.ceil(Math.max(py, y1)) });
            }
        }
        return out;
    }
}
