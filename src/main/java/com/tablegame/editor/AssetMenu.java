package com.tablegame.editor;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 条目上的右键菜单 / 参数下拉（全工程共用：13 个屏）。
 *
 * <ul>
 *   <li><b>检索行</b>：菜单顶部自绘输入行，打字即筛（不区分大小写；清空 = 全部）。行不能做成控件
 *       （每敲一个字重建控件会丢输入中的 EditBox 光标/焦点），打字由宿主屏转发给 {@link #type}/{@link #backspace}。</li>
 *   <li><b>右侧滚动条</b>：条目多时（1 屏行高 228px）出现，可拖、可点轨道跳页；滚轮仍可用。</li>
 *   <li><b>空态</b>：筛不到条目时给一行「没有匹配的」。</li>
 * </ul>
 *
 * <p>宿主在 mouseClicked 开头先问 {@link #click}（开着就吃掉这一下），渲染在 super 之后调 {@link #draw}；
 * {@link #type}/{@link #backspace}、{@link #scrollBy}、{@link #drag}/{@link #release} 由宿主转发，
 * 都返回 true = 已处理。
 */
public final class AssetMenu {
    /** 菜单宽 / 行高（屏幕像素）。检索行 20px（与条目行同高更整齐）。 */
    private static final int W = 132, ROW = 16, SEARCH_H = 20, BAR_W = 6;

    private boolean open;
    private double x, y;
    /** 全部条目（open 时不动）。 */
    private final List<String> labels = new ArrayList<>();
    /** 与 labels 平行的第二检索串（如资产名条目的显示名；null = 没有，只按 label 筛）。 */
    private final List<String> hints = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    /** 筛中的条目在 labels 里的下标。 */
    private final List<Integer> hits = new ArrayList<>();
    /** 检索串（空 = 全部）。 */
    private StringBuilder query = new StringBuilder();
    /** 条目区第一行（hits 下标）。 */
    private int top;
    /** 拖滚动条时的会话锚（-1 = 没在拖）。 */
    private double dragY = -1;

    public boolean isOpen() {
        return open;
    }

    /** 兼容旧签名（无检索提示串）。 */
    public void open(int screenW, int screenH, double mx, double my, List<String> ls, List<Runnable> as) {
        open(screenW, screenH, mx, my, ls, as, null);
    }

    /**
     * 在 (mx, my) 开菜单（贴边时收回屏内，防菜单画到屏幕外）。
     *
     * @param hs 与 ls 平行的**第二检索串**（打「镐」也能筛出 pickaxe* 那个需求：label 是资产名、hint 是显示名）；
     *            条数对不上 / null = 只按 label 筛
     */
    public void open(int screenW, int screenH, double mx, double my, List<String> ls, List<Runnable> as, List<String> hs) {
        labels.clear();
        labels.addAll(ls);
        hints.clear();
        if (hs != null && hs.size() == ls.size()) {
            hints.addAll(hs);
        }
        actions.clear();
        actions.addAll(as);
        query = new StringBuilder();
        top = 0;
        dragY = -1;
        filter();
        int h = SEARCH_H + ROW * rows() + 4 + (bar() ? 2 : 0);
        x = Math.min(mx, Math.max(0, screenW - W - 2));
        y = Math.min(my, Math.max(0, screenH - h - 2));
        open = true;
    }

    public void close() {
        open = false;
        dragY = -1;
    }

    // ---------- 检索 ----------

    private void filter() {
        hits.clear();
        String q = query.toString().toLowerCase();
        for (int i = 0; i < labels.size(); i++) {
            if (q.isEmpty() || match(labels.get(i), q) || (i < hints.size() && match(hints.get(i), q))) {
                hits.add(i);
            }
        }
        top = Math.max(0, Math.min(top, Math.max(0, hits.size() - rows())));
    }

    private static boolean match(String s, String q) {
        return s != null && s.toLowerCase().contains(q);
    }

    /** 打进一段字符（宿主屏 charTyped 转发，收 codepointAsString；true = 已处理）。菜单没开恒 false，别拦正常输入。 */
    public boolean type(String s) {
        if (!open || s == null || s.isEmpty()) {
            return open;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c) && query.length() < 32) {
                query.append(c);
            }
        }
        top = 0;
        filter();
        return true;
    }

    /** 退格（宿主屏 keyPressed 转发；true = 已处理）。菜单没开恒 false。 */
    public boolean backspace() {
        if (!open) {
            return false;
        }
        if (query.length() > 0) {
            query.deleteCharAt(query.length() - 1);
            top = 0;
            filter();
        }
        return true;
    }

    // ---------- 滚动 ----------

    /** 本屏条目行数。 */
    private int rows() {
        return Math.min(14, Math.max(1, hits.size()));
    }

    private int maxTop() {
        return Math.max(0, hits.size() - rows());
    }

    private boolean bar() {
        return hits.size() > rows();
    }

    /** 滚轮（宿主屏转发；true = 已处理）。 */
    public boolean scrollBy(double dy) {
        if (!open) {
            return false;
        }
        int step = (int) Math.signum(dy);
        int next = Math.max(0, Math.min(maxTop(), top - step));
        if (next != top) {
            top = next;
            return true;
        }
        return dy != 0 && hits.size() > 0;               // 菜单开着：滚轮到头也吃掉，别去滚背后的屏
    }

    private int rowAt(double mx, double my) {
        if (mx < x || mx >= x + W - (bar() ? BAR_W + 1 : 0) || my < y + SEARCH_H) {
            return -1;
        }
        int row = (int) ((my - y - SEARCH_H) / ROW);
        return row >= 0 && row < rows() && row < hits.size() - top ? row : -1;
    }

    // ---------- 点击 / 拖 ----------

    /**
     * 屏的 {@code mouseClicked} 开头调：菜单开着就**吃掉这一下**（点某行 = 干那一行；点别处 = 关掉）。
     *
     * @return true = 这一下已经处理完，宿主别再往下传（否则会连带触发菜单背后那个条目）
     */
    public boolean click(double mx, double my) {
        if (!open) {
            return false;
        }
        if (bar() && mx >= x + W - BAR_W - 1 && mx < x + W && my >= y + SEARCH_H) {
            // 点轨道：把中心对到点击处，拖动会话同步开起来（拖了就能继续拽）
            double frac = (my - y - SEARCH_H - thumbH() / 2) / Math.max(1, trackH() - thumbH());
            top = (int) Math.round(Math.max(0, Math.min(1, frac)) * maxTop());
            dragY = my;
            return true;
        }
        int row = rowAt(mx, my);
        open = false;
        if (row >= 0) {
            actions.get(hits.get(top + row)).run();
        }
        return true;
    }

    /** 拖滚动条（宿主屏 mouseDragged 转发；true = 已处理）。 */
    public boolean drag(double mx, double my) {
        if (!open || dragY < 0) {
            return false;
        }
        double frac = (my - y - SEARCH_H - thumbH() / 2) / Math.max(1, trackH() - thumbH());
        top = (int) Math.round(Math.max(0, Math.min(1, frac)) * maxTop());
        return true;
    }

    /** 松手（宿主屏 mouseReleased 转发；正在拖就吃掉这一下）。 */
    public boolean release(double mx, double my) {
        if (!open || dragY < 0) {
            return false;
        }
        dragY = -1;
        return true;
    }

    private int trackH() {
        return ROW * rows() + 4;
    }

    private double thumbH() {
        return Math.max(12, trackH() * (double) rows() / Math.max(1, hits.size()));
    }

    private double thumbY() {
        return y + SEARCH_H + (trackH() - thumbH()) * ((double) top / Math.max(1, maxTop()));
    }

    // ---------- 画 ----------

    /** 画在最上层（宿主在 {@code super.extractRenderState} 之后调）。 */
    public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        int rows = rows();
        int h = SEARCH_H + ROW * rows + 4 + (bar() ? 2 : 0);
        g.fill((int) x, (int) y, (int) x + W, (int) y + h, 0xF0181C24);
        g.outline((int) x, (int) y, W, h, 0xFF606878);
        // 检索行：底色与光标（1Hz 闪，600ms 周期里的前 2/3 亮）
        g.fill((int) x + 2, (int) y + 2, (int) x + W - 2, (int) y + SEARCH_H - 1, 0xFF10131A);
        String q = query.toString();
        String shown = q.isEmpty() ? "检索…" : q;
        int qc = q.isEmpty() ? 0xFF707890 : 0xFFE8E8F0;
        g.text(font, DrawBoardMenuUi.ellipsis(shown, 15), (int) x + 6, (int) y + 6, qc);
        if ((System.currentTimeMillis() / 600) % 3 < 2) {
            int cw = font.width(q.isEmpty() ? "" : q) + 6;
            g.fill((int) x + Math.min(cw, W - 10) + 1, (int) y + 4, (int) x + Math.min(cw, W - 10) + 2,
                    (int) y + SEARCH_H - 3, 0xFFE8E8F0);
        }
        // 条目
        if (hits.isEmpty()) {
            g.text(font, "没有匹配的", (int) x + 6, (int) y + SEARCH_H + 4, 0xFF909090);
        }
        int hover = rowAt(mouseX, mouseY);
        for (int r = 0; r < rows && top + r < hits.size(); r++) {
            int idx = hits.get(top + r);
            g.text(font, DrawBoardMenuUi.ellipsis(labels.get(idx), 16), (int) x + 6,
                    (int) y + SEARCH_H + 4 + r * ROW, r == hover ? 0xFFFFE080 : 0xFFD0D0D0);
        }
        // 滚动条
        if (bar()) {
            int tx = (int) x + W - BAR_W - 1;
            g.fill(tx, (int) y + SEARCH_H, tx + BAR_W, (int) y + SEARCH_H + trackH(), 0xFF2A2E3A);
            boolean on = mouseX >= tx && mouseX < tx + BAR_W && mouseY >= thumbY();
            g.fill(tx, (int) thumbY(), tx + BAR_W, (int) (thumbY() + thumbH()), dragY >= 0 || on ? 0xFF9AA6C0 : 0xFF606878);
        }
    }
}
