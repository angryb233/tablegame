package com.tablegame.drawboard;

/**
 * 列表滚动的小公共件：滚轮一行一行 · 边界夹取 · 顶栏「3-10 / 12」小标。
 *
 * <p>只做状态 + 算术，摆行/重建仍由屏自己干（列表行是 {@link DrawBoardMenuUi} 自绘风格按钮，
 * 换原版 {@code ObjectSelectionList} 会改掉各屏布局）：
 * <pre>
 *   private final ListScroll scroll = new ListScroll(8);      // 一屏 8 行
 *   init():  scroll.setTotal(size);
 *            for (int row = 0; row &lt; scroll.rows(); row++) { int i = scroll.index(row); … }
 *   滚轮:    if (scroll.scroll(dy)) { clearWidgets(); init(); }   // widget 屏重建最省事
 * </pre>
 *
 * <p>纯整数逻辑、零 MC 依赖，能进自检。
 */
public final class ListScroll {

    /** 一屏最多摆几行（由屏自己按布局定）。 */
    private int rows;                                        // 数值页的掉落池那行会展开成多行，需调小
    /** 数据总行数。 */
    private int total;
    /** 当前第一行的数据下标。 */
    private int top;

    public ListScroll(int rows) {
        this.rows = rows;
    }

    /** 每次 init 前调：告知总数，并把 top 夹回合法范围（删行/换数据后必需）。 */
    public void setTotal(int n) {
        total = Math.max(0, n);
        top = clamp(top);
    }

    /** 本屏要摆几行（≤ rows，末尾不足一屏时更少）。 */
    public int rows() {
        return Math.min(rows, Math.max(0, total - top));
    }

    /** 一屏放几行（默认构造时那个数）。数值页的**掉落池**那行会展开成多行，所以要按内容高度调小。 */
    public void setRows(int n) {
        rows = Math.max(1, n);
    }

    /** 第 row 行（0 = 本屏第一行）对应的数据下标。 */
    public int index(int row) {
        return top + row;
    }

    public int top() {
        return top;
    }

    /** 跳到第 t 行（重建控件时用来保住滚动位置；自动夹到合法范围）。 */
    public void setTop(int t) {
        top = clamp(t);
    }

    /** 滚一格（正 = 往下滚 = 看后面的行）；真动了才返回 true（调用方据此重建行）。 */
    public boolean scroll(double dy) {
        int step = (int) Math.signum(dy);
        if (step == 0) {
            return false;
        }
        int next = clamp(top - step);
        if (next == top) {
            return false;
        }
        top = next;
        return true;
    }

    /** 内容超一屏 = 该画滚动条（不满一屏就别画，画了是骗人）。 */
    public boolean shows() {
        return total > rows;
    }

    /**
     * 滑块在轨道里的 {@code [y, h]} —— **画条与拖条共用这一条算术**（纯整数/浮点，可自检）。
     * 长度按「一屏 / 总数」，位置按 {@code top} 的占比；不满一屏 = 整条当滑块。
     */
    public int[] bar(int trackY, int trackH, int minH) {
        if (!shows()) return new int[] { trackY, trackH };
        int h = Math.max(minH, Math.round(trackH * (float) rows / total));
        int span = Math.max(0, trackH - h);
        int maxTop = Math.max(1, total - rows);
        return new int[] { trackY + Math.round(span * (top / (float) maxTop)), h };
    }

    /** 鼠标在轨道里的 y → 换 top（点轨道 = 直接跳过去，拖滑块同理）。真动了才 true。 */
    public boolean dragTo(int trackY, int trackH, int minH, double mouseY) {
        if (!shows()) return false;
        int[] b = bar(trackY, trackH, minH);
        int span = Math.max(0, trackH - b[1]);
        if (span <= 0) return false;
        double f = Math.max(0, Math.min(1, (mouseY - trackY - b[1] / 2.0) / span));
        int next = clamp((int) Math.round(f * Math.max(0, total - rows)));
        if (next == top) return false;
        top = next;
        return true;
    }

    /** 顶栏小标（不超一屏 → 空串，屏上就别画了）。 */
    public String label() {
        return total <= rows ? "" : (top + 1) + "-" + (top + rows()) + " / " + total;
    }

    private int clamp(int t) {
        return Math.max(0, Math.min(Math.max(0, total - rows), t));
    }
}
