package com.tablegame.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tablegame.TableGame;
import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * **节点图画布**（可复用基类，从 {@link ScriptCanvasScreen} 拆出来）。
 * 住这里的都是与数据源无关的机件：视口（zoom + 偏移，锚点缩放）· 点/拖节点 · 中键平移 · 拉线与重接预览
 * · 连线（含条件虚线 + 线上小字）· 节点卡片 · 右键菜单框架 · 坐标持久化（旁路 JSON，不进档）。
 * 图从哪来 / 菜单里写什么 / 节点什么颜色由子类交出（见下方五个抽象方法）。
 */
public abstract class GraphCanvas extends Screen implements EditorToolScreen {
    /** 节点框尺寸（画布坐标）。 */
    protected static final double NW = 190, NH = 36;
    protected static final double MIN_ZOOM = 0.3, MAX_ZOOM = 3.0;
    /** 右键菜单：宽与每行高（屏幕像素）。 */
    protected static final int MENU_W = 240, MENU_ROW = 16;

    /** 玩法编辑器（包私有：同包工具屏直接读写 def）。 */
    protected final GameEditorScreen parent;
    /** 日志 / 提示的前缀（子类给，例如「脚本」）。 */
    private final String tag;

    /** 画面上的节点（每次 init 从数据源重建）。 */
    public static final class Node {
        public final String key;
        public final String label;
        public final int line;        // 对应数据源第几行（0 = 没有自己那一段）
        public final int kind;
        public final double x, y;
        public double px, py;         // 可拖的位置（画布坐标）
        public Node(String key, String label, int line, int kind, double x, double y) {
            this.key = key; this.label = label; this.line = line; this.kind = kind;
            this.x = x; this.y = y; this.px = x; this.py = y;
        }
    }

    /**
     * 一条连线：源节点键 → 目标节点键。{@code cond}=画虚线，{@code condText}=线上条件原文，
     * {@code line}=这条边在数据源里的行号（拖端点重接靠它当锚点，对不上要拒收）。
     */
    public record Edge(String from, String to, boolean cond, String condText, int line) { }

    protected final List<Node> nodes = new ArrayList<>();
    protected final List<Edge> edges = new ArrayList<>();

    /** 视口（画布坐标 → 屏幕坐标）：zoom + 偏移，锚点缩放。 */
    protected double zoom = 0.9, offX, offY;
    private boolean panning;
    private double panDX, panDY;
    /** 正在拖的节点键（null = 没在拖）；拖动位移（鼠标 - 节点原点，画布坐标）。 */
    private String dragging;
    protected double dragDX, dragDY;
    /** 选中的节点键（点一下高亮 + 底栏显示行号）。 */
    protected String picked;
    /** 拉线中：源节点键（null = 没在拉）+ 当前鼠标（屏幕坐标，画预览线用）。 */
    private String linking;
    private double linkX, linkY;
    /** 重接中：正被拖末端的那条线（null = 没在重接）。重接 = 只换目标，源与行号都不动。 */
    private Edge relink;
    /** 右键菜单：开着吗 + 屏幕位置 + 每行的字与动作（内容由 {@link #fillMenu} 填）。 */
    protected boolean menuOpen;
    protected double menuX, menuY;
    protected final List<String> menuLabels = new ArrayList<>();
    protected final List<Runnable> menuActions = new ArrayList<>();
    /** 破坏性动作的二次确认：记「键」（第一个点只改成“再点一次”，第二个点才真干）。 */
    protected String arm = "";

    protected GraphCanvas(Component title, GameEditorScreen parent, String tag) {
        super(title);
        this.parent = parent;
        this.tag = tag;
    }

    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===== 数据那一半：子类交出来的五件 =====

    /** 从数据源重建 {@link #nodes} / {@link #edges}（每次 init 调一次）。 */
    protected abstract void rebuildGraph();
    /** 坐标存哪个文件（旁路 JSON，不进档）。 */
    protected abstract Path layoutFile();
    /** 节点顶边那条色带的颜色（按 kind 分）。 */
    protected abstract int colorOf(int kind);
    /** 填右键菜单：往 menuLabels / menuActions 里放；位置与命中由框架管。 */
    protected abstract void fillMenu(double mx, double my);
    /** 双击（左键）：接住了返回 true（节点上 / 空白处各干什么由子类定）。 */
    protected abstract boolean onDoubleClick(double mx, double my);

    // ===== 子类可以改的几处（默认 = 通用画布的行为）=====

    /** 拖一个节点：世界坐标允许为负（画布可缩放/平移，原点不是墙）。 */
    protected void dragNode(Node n, double mx, double my) {
        n.px = toWorldX(mx) - dragDX;
        n.py = toWorldY(my) - dragDY;
    }
    /** 菜单上方那行小字（没有 = null）。 */
    protected String menuHeader() { return null; }
    /** 节点没有源行时副标题写什么（默认空着）。 */
    protected String noLineLabel() { return ""; }
    /** 拉线落地（数据相关：由子类写回）。 */
    protected void droppedLink(String fromKey, Node target) { }
    /** 重接落地（数据相关：由子类写回）。 */
    protected void droppedRelink(Edge e, Node target) { }

    // ===== 构建 =====

    @Override
    protected void init() {
        clearWidgets();
        nodes.clear();
        edges.clear();
        linking = null;                            // 重建期间不许留着上一张图的拖拽状态（会指到旧行号）
        relink = null;
        rebuildGraph();
        loadLayout();
    }

    /** 开菜单：先清空，交给子类填，再记位置（子类的「再点一次确认」也走这里重开）。 */
    protected void openMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        fillMenu(mx, my);
        menuOpen = true;
        menuX = mx;
        menuY = my;
    }

    // ===== 布局存盘（本地文件，不进档）=====

    protected void loadLayout() {
        offX = EditorRail.LEFT + 24;
        offY = 96;
        try {
            Path f = layoutFile();
            if (!Files.exists(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (o.has("view")) {
                JsonArray v = o.getAsJsonArray("view");
                zoom = v.get(0).getAsDouble(); offX = v.get(1).getAsDouble(); offY = v.get(2).getAsDouble();
            }
            if (o.has("nodes")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("nodes").entrySet()) {
                    JsonArray p = e.getValue().getAsJsonArray();
                    double px = p.get(0).getAsDouble(), py = p.get(1).getAsDouble();
                    for (Node n : nodes) if (n.key.equals(e.getKey())) { n.px = px; n.py = py; }
                }
            }
        } catch (Exception e) {
            TableGame.LOGGER.warn("[{}] 布局读不了（当没存过）: {}", tag, e.toString());
        }
    }

    protected void saveLayout() {
        try {
            Path f = layoutFile();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            JsonArray v = new JsonArray();
            v.add(zoom); v.add(offX); v.add(offY);
            o.add("view", v);
            JsonObject ns = new JsonObject();
            for (Node n : nodes) {
                JsonArray p = new JsonArray();
                p.add(n.px); p.add(n.py);
                ns.add(n.key, p);
            }
            o.add("nodes", ns);
            Files.writeString(f, o.toString());
        } catch (Exception e) {
            DrawBoardMenuUi.msg("[" + tag + "] 布局存不了：" + e);
        }
    }

    /** 重排：丢掉已存的坐标，回到自动布局（存盘）。提示语由子类补。 */
    protected void resetLayout() {
        for (Node n : nodes) { n.px = n.x; n.py = n.y; }
        offX = EditorRail.LEFT + 24;
        offY = 96;
        zoom = 0.9;
        saveLayout();
    }

    // ===== 交互 =====

    protected double toScreenX(double wx) { return wx * zoom + offX; }
    protected double toScreenY(double wy) { return wy * zoom + offY; }
    protected double toWorldX(double sx) { return (sx - offX) / zoom; }
    protected double toWorldY(double sy) { return (sy - offY) / zoom; }

    private void zoomAt(double sx, double sy, double amount) {
        double old = zoom;
        zoom = amount > 0 ? Math.min(MAX_ZOOM, old * 1.15) : Math.max(MIN_ZOOM, old / 1.15);
        if (zoom == old) return;
        double ax = (sx - offX) / old, ay = (sy - offY) / old;
        offX = sx - ax * zoom;
        offY = sy - ay * zoom;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        zoomAt(x, y, scrollY != 0 ? scrollY : scrollX);
        saveLayout();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;      // 按钮/左栏优先
        double mx = event.x(), my = event.y();
        if (menuOpen) {                                          // 菜单开着：点哪行干哪行；点别处就关掉
            int row = menuRowAt(mx, my);
            menuOpen = false;
            if (row >= 0) {
                menuActions.get(row).run();
                return true;
            }
            return true;
        }
        if (event.button() == 2) {                                // 中键 = 拖画布
            panning = true;
            panDX = mx - offX;
            panDY = my - offY;
            return true;
        }
        if (event.button() == 1) {                                // 右键 = 菜单（内容由子类填）
            openMenu(mx, my);
            return true;
        }
        if (doubled && event.button() == 0 && onDoubleClick(mx, my)) return true;   // 双击：子类接
        if (event.button() == 0) {
            Edge rl = edgeEndAt(mx, my);                          // ① 线的末端圆点 = 拖它换接的目标（重接）
            if (rl != null) {
                relink = rl;
                linkX = mx;
                linkY = my;
                return true;
            }
            Node from = portAt(mx, my);                           // ② 右侧端口 = 拉一条新线
            if (from != null) {
                linking = from.key;
                linkX = mx;
                linkY = my;
                return true;
            }
            Node n = nodeAt(mx, my);
            picked = n == null ? null : n.key;
            if (n != null) {                                      // 开始拖节点（空白处点一下 = 取消选中）
                dragging = n.key;
                dragDX = toWorldX(mx) - n.px;
                dragDY = toWorldY(my) - n.py;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (super.mouseDragged(event, dx, dy)) return true;
        double mx = event.x(), my = event.y();
        if (linking != null || relink != null) {                   // 拉线 / 重接中：预览线跟着鼠标
            linkX = mx;
            linkY = my;
            return true;
        }
        if (panning) {
            offX = mx - panDX;
            offY = my - panDY;
            return true;
        }
        Node n = byKey(dragging);
        if (n != null) {
            dragNode(n, mx, my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (linking != null) {
            droppedLink(linking, nodeAt(event.x(), event.y()));
            linking = null;
        }
        if (relink != null) {
            droppedRelink(relink, nodeAt(event.x(), event.y()));
            relink = null;
        }
        dragging = null;
        panning = false;
        saveLayout();                                             // 松手存一次（拖完/平移完/缩放完）
        return super.mouseReleased(event);
    }

    protected Node byKey(String key) {
        if (key == null) return null;
        for (Node n : nodes) if (n.key.equals(key)) return n;
        return null;
    }

    /** 点到了哪个节点（后画的在上，所以从后往前找）。 */
    protected Node nodeAt(double sx, double sy) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node n = nodes.get(i);
            double x = toScreenX(n.px), y = toScreenY(n.py);
            if (sx >= x && sx < x + NW * zoom && sy >= y && sy < y + NH * zoom) return n;
        }
        return null;
    }

    /** 端口命中：节点右边缘那条窄带（拉线从这里起手）。 */
    private Node portAt(double sx, double sy) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node n = nodes.get(i);
            double right = toScreenX(n.px) + NW * zoom, y = toScreenY(n.py);
            if (sx >= right - 16 && sx <= right + 6 && sy >= y && sy < y + NH * zoom) return n;
        }
        return null;
    }

    /**
     * 点到哪条连线了：拿线的中点判（命中半径 10 像素）。
     * ponytail: 近似判中点，够用且便宜；真嫌难点再换「点到线段距离」。
     */
    protected Edge edgeAt(double sx, double sy) {
        for (Edge e : edges) {
            Node a = byKey(e.from()), b = byKey(e.to());
            if (a == null || b == null) continue;
            double mx = (toScreenX(a.px) + NW * zoom + toScreenX(b.px)) / 2;
            double my = (toScreenY(a.py) + toScreenY(b.py) + NH * zoom) / 2;
            if (Math.abs(sx - mx) <= 10 && Math.abs(sy - my) <= 10) return e;
        }
        return null;
    }

    /** 点到哪条连线的末端圆点了（= 拖它换目标）。ponytail: 命中半径 ±6 像素，多条线重叠时先到先得。 */
    private Edge edgeEndAt(double sx, double sy) {
        for (Edge e : edges) {
            Node b = byKey(e.to());
            if (b == null) continue;
            double bx = toScreenX(b.px), by = toScreenY(b.py) + NH * zoom / 2;
            if (Math.abs(sx - bx) <= 6 && Math.abs(sy - by) <= 6) return e;
        }
        return null;
    }

    protected int menuRowAt(double sx, double sy) {
        if (sx < menuX || sx >= menuX + MENU_W || sy < menuY) return -1;
        int row = (int) ((sy - menuY) / MENU_ROW);
        return row >= 0 && row < menuLabels.size() ? row : -1;
    }

    // ===== 画 =====

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    /** 画布机体：连线 → 节点 → 拉线/重接预览 → 右键菜单（子类在底色与底栏之间调它）。 */
    protected void drawCanvasBody(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // ①b 连线（画在节点下面）
        for (Edge e : edges) {
            Node a = byKey(e.from()), b = byKey(e.to());
            if (a == null || b == null) continue;
            double ax = toScreenX(a.px) + NW * zoom, ay = toScreenY(a.py) + NH * zoom / 2;
            double bx = toScreenX(b.px), by = toScreenY(b.py) + NH * zoom / 2;
            if (e.cond()) {
                dashLine(g, ax, ay, bx, by);
                condTextOn(g, e.condText(), (ax + bx) / 2, (ay + by) / 2);      // 条件原文写在线上
            } else {
                line(g, ax, ay, bx, by, 0xFF5A7FA0);
            }
            dot(g, bx, by, 0xFF5A7FA0);
        }

        // ② 节点
        for (Node n : nodes) {
            double sx = toScreenX(n.px), sy = toScreenY(n.py);
            double w = NW * zoom, h = NH * zoom;
            boolean sel = n.key.equals(picked);
            g.fill((int) sx, (int) sy, (int) (sx + w), (int) (sy + h), sel ? 0xFF2A3A52 : 0xFF1B1F28);
            g.fill((int) sx, (int) sy, (int) (sx + w), (int) (sy + 2), colorOf(n.kind));
            double ph = Math.min(10, h);                        // 右边缘那个小方块 = 拉线的「端口」
            g.fill((int) (sx + w - 4), (int) (sy + h / 2 - ph / 2), (int) (sx + w), (int) (sy + h / 2 + ph / 2),
                    0xFF7FB0D0);
            if (w >= 96) {                                        // 太小就不画字（免得溢出得难看）
                g.text(font, DrawBoardMenuUi.ellipsis(n.label, 24), (int) sx + 5, (int) sy + 7, 0xFFFFFFFF);
                g.text(font, n.line > 0 ? "第 " + n.line + " 行" : noLineLabel(),
                        (int) sx + 5, (int) sy + 21, 0xFF909090);
            }
        }

        // ③ 拉线预览：从源节点右边缘拉到鼠标
        Node src = byKey(linking);
        if (src != null) {
            line(g, toScreenX(src.px) + NW * zoom, toScreenY(src.py) + NH * zoom / 2, linkX, linkY, 0xFF80D0A0);
            dot(g, linkX, linkY, 0xFF80D0A0);
        }
        // ③b 重接预览：源不动、末端跟着鼠标（黄色，与拉新线的绿色分开）
        if (relink != null) {
            Node ra = byKey(relink.from());
            if (ra != null) {
                line(g, toScreenX(ra.px) + NW * zoom, toScreenY(ra.py) + NH * zoom / 2, linkX, linkY, 0xFFE0C060);
                dot(g, linkX, linkY, 0xFFE0C060);
            }
        }

        // ④ 右键菜单（画在节点之上、按钮之下）
        if (menuOpen) {
            String head = menuHeader();
            if (head != null) {
                g.text(font, DrawBoardMenuUi.ellipsis(head, 44), (int) menuX, (int) menuY - 34, 0xFFA0C0A0);
            }
            int w = MENU_W, h = MENU_ROW * menuLabels.size() + 4;
            g.fill((int) menuX, (int) menuY, (int) menuX + w, (int) menuY + h, 0xF0181C24);
            g.fill((int) menuX, (int) menuY, (int) (menuX + w), (int) menuY + 1, 0xFF606878);
            for (int i = 0; i < menuLabels.size(); i++) {
                g.text(font, DrawBoardMenuUi.ellipsis(menuLabels.get(i), 36), (int) menuX + 6,
                        (int) menuY + 4 + i * MENU_ROW, i == menuRowAt(mouseX, mouseY) ? 0xFFFFE080 : 0xFFD0D0D0);
            }
        }
    }

    /** 条件原文写在虚线中间（垫一层深色底衬，免得压在线上看不清）。 */
    protected void condTextOn(GuiGraphicsExtractor g, String text, double mx, double my) {
        if (text == null || text.isEmpty()) return;
        String t = DrawBoardMenuUi.ellipsis(text, 26);
        if (t.isEmpty()) return;
        int w = font.width(t);
        g.fill((int) mx - w / 2 - 3, (int) my - 5, (int) mx + w / 2 + 3, (int) my + 5, 0xD0181C24);
        g.text(font, t, (int) mx - w / 2, (int) my - 4, 0xFFE0C080);
    }

    /** 逐格步进画线（与画布/画板同一套土办法：fill 不打滑）。 */
    protected void line(GuiGraphicsExtractor g, double ax, double ay, double bx, double by, int color) {
        int steps = (int) Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) (ax + (bx - ax) * t), y = (int) (ay + (by - ay) * t);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** 虚线（条件边）：每步长 3 画 2。 */
    private void dashLine(GuiGraphicsExtractor g, double ax, double ay, double bx, double by) {
        int steps = (int) Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i++) {
            if (i % 5 > 2) continue;
            double t = (double) i / steps;
            int x = (int) (ax + (bx - ax) * t), y = (int) (ay + (by - ay) * t);
            g.fill(x, y, x + 1, y + 1, 0xFFB08850);
        }
    }

    private void dot(GuiGraphicsExtractor g, double x, double y, int color) {
        g.fill((int) x - 2, (int) y - 2, (int) x + 2, (int) y + 2, color);
    }
}
