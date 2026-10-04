package com.tablegame.stage;

import java.util.ArrayList;
import java.util.List;

import com.tablegame.drawboard.BoardPackets;
import com.tablegame.drawboard.Cells;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.StageBoardCache;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.GameDefinition;
import com.tablegame.host.ClientGameHandler;
import com.tablegame.net.HostPackets;

    /**
     * 全屏舞台界面：舞台的第二种承载（另一种是常驻小窗 {@link StageHudLayer}）。
     * 与 HUD 的区别是能收鼠标键盘 —— 猜谜者可在画布上的输入框直接打字提交。
     * 界面完全由 {@code stage.ui} 布局决定：每个 {@code input} 框变成真输入框，其余不画。
     * 回车提交，走同一条 {@code HostManager.input}（校验局内人 + 正在等输入）。
     * {@code mode=full} 自动打开，{@code /tablegame ui} 手动开，{@code Esc} 关回 HUD，不暂停。
     */
public class StageScreen extends Screen {
    /** 上下两条信息带的高度预算（舞台只铺中间那一条）。 */
    private static final int TOP_H = 24, BOTTOM_H = 24;

    /** 布局里每个 input 框对应的真输入框（控件由 init 重建，列表跟着一起重建）。 */
    private final List<EditBox> inputs = new ArrayList<>();
    /** 与 {@link #inputs} 一一对应的**框 id**（宿主给的；提交时回传，脚本用它判 input_box）。 */
    private final List<String> inputIds = new ArrayList<>();
    /** 已建好的控件是给哪次「等输入」的（等输入开始/结束各一次，不是每帧）。 */
    private String builtFor = "";

    public StageScreen() {
        super(Component.literal("舞台"));
    }

    /** 现在在等哪个动作（空 = 不等输入）——输入框能不能打字由它决定。 */
    private static String waitAction() {
        HostPackets.HostStatePayload st = ClientGameHandler.hostState();
        return st == null || !st.inGame() ? "" : st.waitAction();
    }

    /** 全屏承载用哪份布局：当前那块 view 的组件（线口径带来的成品）；老档没有 view 时退回 {@code stage.ui}。 */
    private GameDefinition.StageUi layout() {
        GameDefinition.StageDef d = ClientGameHandler.stageDef();
        GameDefinition.StageView view = ClientGameHandler.boundView(false);
        if (view != null) return StageRenderer.uiOf(view);      // schema/3：画当前那块舞台的组件
        return d == null ? null : d.ui();                       // 老档：原样走旧布局
    }

    /** 舞台铺在屏幕上的位置与缩放：{ox, oy, scale}（init 摆控件与渲染画框共用，别写两份）。 */
    private double[] placement() {
        GameDefinition.StageUi ui = layout();
        int availW = Math.max(40, width - 12);
        int availH = Math.max(40, height - TOP_H - BOTTOM_H);
        double scale = StageRenderer.fitScale(ui, availW, availH);
        int cw = (int) Math.round(ui.w() * scale);
        int ch = (int) Math.round(ui.h() * scale);
        return new double[] { (width - cw) / 2, TOP_H + (availH - ch) / 2, scale };
    }

    /**
     * 把布局里的 {@code input} 框变成真输入框（位置/大小 = 框矩形 × 缩放）。
     * 只在「正在等输入」时可编辑，自动聚焦第一个。
     */
    @Override
    protected void init() {
        clearWidgets();
        inputs.clear();
        inputIds.clear();
        builtFor = waitAction();
        GameDefinition.StageUi ui = layout();
        if (ui == null) return;
        double[] p = placement();
        boolean canType = !builtFor.isEmpty();
        for (GameDefinition.BoxDef b : ui.boxes()) {
            if (b.content() == null || !"input".equals(b.content().type())) continue;
            int x = (int) Math.round(p[0] + b.x() * p[2]);
            int y = (int) Math.round(p[1] + b.y() * p[2]);
            int w = Math.max(24, (int) Math.round(b.w() * p[2]));
            int h = Math.max(10, (int) Math.round(b.h() * p[2]));
            EditBox box = new EditBox(font, x, y, w, h, Component.literal("输入"));
            box.setMaxLength(120);
            if (!b.content().value().isEmpty()) box.setHint(Component.literal(b.content().value()));
            box.setEditable(canType);
            inputs.add(box);
            inputIds.add(b.content().var() == null ? "" : b.content().var());
            addRenderableWidget(box);
        }
        // 组件树里的按钮：真控件（点一下就把它的动作提交上去，args 当固定参数）
        GameDefinition.StageView view = ClientGameHandler.boundView(false);
        if (view != null) {
            for (GameDefinition.Component c : flatten(view.components())) {
                if (!c.type().equals("button")) continue;
                int x = (int) Math.round(p[0] + c.x() * p[2]);
                int y = (int) Math.round(p[1] + c.y() * p[2]);
                int w = Math.max(24, (int) Math.round(c.w() * p[2]));
                int h = Math.max(10, (int) Math.round(c.h() * p[2]));
                String action = c.s("action", "");
                String args = c.s("args", "");
                addRenderableWidget(DrawBoardMenuUi.button(x, y, w, h, c.s("label", "按钮"),
                        () -> submitAction(action, args)));
            }
        }
        if (canType && !inputs.isEmpty()) setFocused(inputs.get(0));
    }

    /** 等输入状态一变就重建控件（不是每帧）——状态从服务端快照 pull，变了才动手。 */
    @Override
    public void tick() {
        super.tick();
        if (!builtFor.equals(waitAction())) {
            clearWidgets();
            init();
        }
    }

    // ===== 在 UI 里直接画：鼠标落在「绘画区」组件上就画 =====
    // 映射与 StageRenderer.drawArt 的等比居中同一套——渲染与输入同源，不然笔迹会偏。

    private boolean painting;
    private static int strokeSeq = 1000;      // 和画板屏的笔迹 id 错开，互不干扰

    // 画笔面板：右键绘画区 → 圆框菜单（色彩板 / 工具）→ 可拖动、可关闭的浮动面板
    private static final int COLS = 5, ROWS = 8, SW = 16, SGAP = 2;   // 色格：5 列 × 8 行（照参考图）
    /** 调色板（5×8，后 10 格留作深色备用，跟画板屏同一套观感）。 */
    private static final int[] PALETTE = {
            0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFE5E5E5,
            0xFFFFFFFF, 0xFF8B0000, 0xFFE01B1B, 0xFFFF8C00, 0xFFFFD400,
            0xFFFFFF00, 0xFF9ACD32, 0xFF3CD23C, 0xFF166B16, 0xFF00E5E5,
            0xFF00B4D8, 0xFF1464E0, 0xFF14146E, 0xFF8A2BE2, 0xFFFF00FF,
            0xFFFF6FA5, 0xFFFF8B7A, 0xFF8B4513, 0xFF6B3E11, 0xFFD2B48C,
            0xFFFFE9B8, 0xFF0B6B2E, 0xFF2E8B86, 0xFF4682B4, 0xFF708090,
            0xFF141414, 0xFF1E1E1E, 0xFF282828, 0xFF323232, 0xFF3C3C3C,
            0xFF464646, 0xFF505050, 0xFF5A5A5A, 0xFF646464, 0xFF6E6E6E };
    private static final String[] TOOL_NAMES = { "画笔", "橡皮", "填充", "撤回" };
    private int tool = 0;                      // 0 画笔 · 1 橡皮 · 2 填充 · 3 撤回
    private int color = 0xFF000000;
    private int brush = 1;                     // 笔粗（格）
    private static final int MAX_BRUSH = 4;
    private boolean toolMenu;                  // 右键：一个圆，上半=色彩板 下半=工具
    private boolean showColors, showTools;     // 两块浮动面板
    private int menuX, menuY;
    private double cX = -1, cY = -1, tX = -1, tY = -1;    // 面板位置（-1 = 还没摆过，首次打开时贴着画布右侧摆）
    private int dragPanel;                     // 1 色彩板 · 2 工具板
    private double dragPX, dragPY;

    private static final int R_MENU = 34, HEAD = 16;      // 圆菜单半径 / 面板标题条高
    private int colorW() { return COLS * (SW + SGAP) + 6; }
    private int colorH() { return ROWS * (SW + SGAP) + HEAD + 30; }   // 标题 + 色格 + 色值行
    private int toolW() { return 5 * 22 + 6; }
    private int toolH() { return HEAD + 26 + 30; }                    // 标题 + 按钮行 + 滑杆行
    private static boolean inPanel(double mx, double my, double x, double y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    /** 右上角的 ×（关掉面板）。 */
    private static boolean closeHit(double mx, double my, double x, double y, int w) {
        return mx >= x + w - 14 && mx < x + w - 2 && my >= y + 2 && my < y + 14;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;      // 输入框/按钮那些控件优先
        double mx = event.x(), my = event.y();
        if (toolMenu) {                                          // 一个圆：上半=色彩板 下半=工具
            if (Math.hypot(mx - menuX, my - menuY) <= R_MENU) {
                if (my < menuY) showColors = true;
                else showTools = true;
            }
            toolMenu = false;
            return true;
        }
        if (showColors && inPanel(mx, my, cX, cY, colorW(), colorH())) {
            if (closeHit(mx, my, cX, cY, colorW())) {             // 右上角 ×
                showColors = false;
                return true;
            }
            if (my < cY + HEAD) {                                 // 左上角拖动柄那一行 = 拖面板
                dragPanel = 1;
                dragPX = mx - cX;
                dragPY = my - cY;
                return true;
            }
            int cx = (int) ((mx - (cX + 3)) / (SW + SGAP));
            int cy = (int) ((my - (cY + HEAD)) / (SW + SGAP));
            if (cx >= 0 && cx < COLS && cy >= 0 && cy < ROWS) {
                color = PALETTE[cy * COLS + cx];
                return true;
            }
            dragPanel = 1;                                       // 拖面板
            dragPX = mx - cX;
            dragPY = my - cY;
            return true;
        }
        if (showTools && inPanel(mx, my, tX, tY, toolW(), toolH())) {
            if (closeHit(mx, my, tX, tY, toolW())) {              // 右上角 ×
                showTools = false;
                return true;
            }
            if (my < tY + HEAD) {
                dragPanel = 2;
                dragPX = mx - tX;
                dragPY = my - tY;
                return true;
            }
            double ry = my - (tY + HEAD);
            if (ry >= 0 && ry < 22) {                             // 一行方形工具按钮
                int col = (int) ((mx - (tX + 3)) / 22);
                if (col >= 0 && col < 4) {
                    tool = col;
                    if (col == 3) sendUndo();                     // 撤回当场生效
                    return true;
                }
                return true;
            }
            if (ry >= 26 && ry < 44) {                            // 笔粗滑杆（拖或点）
                brush = brushFromX(mx);
                dragPanel = 3;
                return true;
            }
            dragPanel = 2;
            dragPX = mx - tX;
            dragPY = my - tY;
            return true;
        }
        if (event.button() == 1) {                               // 右键绘画区 → 弹圆框菜单
            if (boardCellAt(mx, my) == null) return false;
            toolMenu = true;
            menuX = (int) mx;
            menuY = (int) my;
            return true;
        }
        if (event.button() == 0 && clickPick(mx, my)) return true;   // 点了可点的一份 → 发回宿主（舞台去模板化）

        if (event.button() != 0) return false;
        int[] cell = boardCellAt(mx, my);
        if (cell == null) return false;
        if (tool == 2) {                                         // 填充
            sendFill(cell[0], cell[1]);
            return true;
        }
        if (tool == 3) {                                         // 撤回
            sendUndo();
            return true;
        }
        painting = true;
        strokeSeq++;
        sendStroke(cell[0], cell[1]);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (super.mouseDragged(event, dx, dy)) return true;
        if (dragPanel == 1) {
            cX = event.x() - dragPX;
            cY = event.y() - dragPY;
            return true;
        }
        if (dragPanel == 2) {
            tX = event.x() - dragPX;
            tY = event.y() - dragPY;
            return true;
        }
        if (dragPanel == 3) {                                     // 拖笔粗滑杆
            brush = brushFromX(event.x());
            return true;
        }
        if (!painting) return false;
        int[] cell = boardCellAt(event.x(), event.y());
        if (cell != null) sendStroke(cell[0], cell[1]);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragPanel = 0;
        if (painting) {
            painting = false;
            ClientPacketDistributor.sendToServer(new BoardPackets.StrokeCellsPayload(
                    StageBoardCache.key(), strokeSeq, new byte[0], true));   // 一笔结束
            return true;
        }
        return super.mouseReleased(event);
    }

    /** 画笔 UI：右键的圆（上半 色彩板 / 下半 工具）+ 两块浮动面板（左上角拖动柄、右上角 ×）。 */
    private void drawBrushUi(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (cX < 0) {                                            // 首次打开：摆到画布右侧，别压住绘画区
            double[] p = placement();
            double right = p[0] + p[2] * 320 + 8;
            cX = Math.max(4, Math.min(width - colorW() - 4, right));
            cY = Math.max(4, p[1]);
            tX = Math.max(4, Math.min(width - toolW() - 4, right));
            tY = cY + colorH() + 8;
            if (tY + toolH() > height - 4) tY = Math.max(4, cY - toolH() - 8);
            if (cX + colorW() > width - 2) {                     // 右边塞不下就退到左上角（画布之外）
                cX = 4;
                tX = 4;
                cY = 4;
                tY = 4 + colorH() + 8;
            }
        }
        if (toolMenu) {
            circle(g, menuX, menuY, R_MENU, 0xF0181828, 0xFF8890C0);
            g.fill(menuX - R_MENU, menuY, menuX + R_MENU, menuY + 1, 0xFF8890C0);
            g.centeredText(font, Component.literal("色彩板"), menuX, menuY - 14, 0xFFE0E0E0);
            g.centeredText(font, Component.literal("工具"), menuX, menuY + 8, 0xFFE0E0E0);
        }
        if (showColors) {                                        // 色彩板
            g.fill((int) cX, (int) cY, (int) cX + colorW(), (int) cY + colorH(), 0xF0181828);
            border(g, (int) cX, (int) cY, colorW(), colorH(), 0xFF8890C0);
            g.fill((int) cX + 1, (int) cY + 1, (int) cX + colorW() - 1, (int) cY + HEAD, 0xFF26263A);
            grip(g, (int) cX + 3, (int) cY + 3);
            g.text(font, "色彩板", (int) cX + 16, (int) cY + 4, 0xFFE0E0E0);
            cross(g, (int) cX + colorW() - 13, (int) cY + 3);
            for (int i = 0; i < PALETTE.length; i++) {
                int x = (int) cX + 3 + (i % COLS) * (SW + SGAP);
                int y = (int) cY + HEAD + (i / COLS) * (SW + SGAP);
                g.fill(x, y, x + SW, y + SW, PALETTE[i]);
                if (PALETTE[i] == color) border(g, x - 1, y - 1, SW + 2, SW + 2, 0xFFFFFFFF);
            }
            int hy = (int) cY + HEAD + ROWS * (SW + SGAP) + 5;    // 色值行：当前色 + 十六进制
            g.fill((int) cX + 3, hy, (int) cX + 3 + SW, hy + SW, color);
            border(g, (int) cX + 3, hy, SW, SW, 0xFF8890C0);
            g.text(font, hex(color), (int) cX + 3 + SW + 6, hy + 5, 0xFFE0E0E0);
        }
        if (showTools) {                                         // 工具板
            g.fill((int) tX, (int) tY, (int) tX + toolW(), (int) tY + toolH(), 0xF0181828);
            border(g, (int) tX, (int) tY, toolW(), toolH(), 0xFF8890C0);
            g.fill((int) tX + 1, (int) tY + 1, (int) tX + toolW() - 1, (int) tY + HEAD, 0xFF26263A);
            grip(g, (int) tX + 3, (int) tY + 3);
            g.text(font, "工具", (int) tX + 16, (int) tY + 4, 0xFFE0E0E0);
            cross(g, (int) tX + toolW() - 13, (int) tY + 3);
            for (int i = 0; i < TOOL_NAMES.length; i++) {         // 一行方形按钮（照参考图；图标用首字）
                int x = (int) tX + 3 + i * 22, y = (int) tY + HEAD + 2;
                g.fill(x, y, x + 20, y + 20, i == tool ? 0xFF34345C : 0xFF26263A);
                border(g, x, y, 20, 20, i == tool ? 0xFFFFFFFF : 0xFF6A6A9A);
                g.centeredText(font, Component.literal(TOOL_NAMES[i].substring(0, 1)), x + 10, y + 6,
                        i == tool ? 0xFFE0C060 : 0xFFE0E0E0);
            }
            int sy = (int) tY + HEAD + 28;                        // 笔粗滑杆 + 「n格」
            g.fill((int) tX + 6, sy + 4, (int) tX + 66, sy + 8, 0xFF101018);
            int kx = (int) tX + 6 + (int) Math.round(60.0 * (brush - 1) / (MAX_BRUSH - 1));
            g.fill(kx - 2, sy, kx + 2, sy + 12, 0xFFE0E0E0);
            g.text(font, brush + "格", (int) tX + 72, sy + 2, 0xFFE0E0E0);
        }
    }

    /** 十六进制色值（照参考图的 {@code ff000000} 写法）。 */
    private static String hex(int argb) {
        return String.format("%08x", argb);
    }

    /** 方框边线（顶/底/左/右各一条 fill）。 */
    private static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    /** 左上角拖动柄（≡ 三条横杠）——明确告诉玩家"这里能拖"。 */
    private static void grip(GuiGraphicsExtractor g, int x, int y) {
        for (int i = 0; i < 3; i++) g.fill(x, y + 2 + i * 3, x + 9, y + 3 + i * 3, 0xFF9098B8);
    }

    /** 右上角关闭键（×）。 */
    private static void cross(GuiGraphicsExtractor g, int x, int y) {
        for (int i = 0; i < 9; i++) {
            g.fill(x + i, y + i, x + i + 2, y + i + 2, 0xFFE0A0A0);
            g.fill(x + 8 - i, y + i, x + 10 - i, y + i + 2, 0xFFE0A0A0);
        }
    }

    /** 实心圆（fill 拼：每行按弦长铺——26.x 无画圆原语）。 */
    private static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int fillColor, int borderColor) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, fillColor);
        }
        for (int a = 0; a < 360; a += 6) {
            double rad = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(rad) * r), y = cy + (int) Math.round(Math.sin(rad) * r);
            g.fill(x, y, x + 1, y + 1, borderColor);
        }
    }

    /** 笔粗滑杆：屏幕 x → 1..MAX_BRUSH 格（轨道 60px 宽）。 */
    private int brushFromX(double mx) {
        double rel = (mx - (tX + 6)) / 60.0;
        int n = (int) Math.round(rel * (MAX_BRUSH - 1)) + 1;
        return Math.max(1, Math.min(MAX_BRUSH, n));
    }

    private void sendStroke(int cx, int cy) {
        if (!StageBoardCache.ready()) return;
        int argb = tool == 1 ? 0x00000000 : color;                // 橡皮 = 透明色
        int n = Math.max(1, brush);
        int half = n / 2;
        byte[] cells = new byte[8 * n * n];                       // 笔粗 = n×n 一块格子（协议不变）
        int k = 0;
        for (int dy = -half; dy < n - half; dy++) {
            for (int dx = -half; dx < n - half; dx++) {
                Cells.write(cells, k, cx + dx, cy + dy, argb);
                k += 8;
            }
        }
        ClientPacketDistributor.sendToServer(new BoardPackets.StrokeCellsPayload(
                StageBoardCache.key(), strokeSeq, cells, false));
    }

    /** 填充（油漆桶）：服务端那条 FillPayload。 */
    private void sendFill(int cx, int cy) {
        if (!StageBoardCache.ready()) return;
        ClientPacketDistributor.sendToServer(new BoardPackets.FillPayload(
                StageBoardCache.key(), cx, cy, tool == 1 ? 0x00000000 : color));
    }

    /** 撤回：服务端那条 UndoPayload（撤的是自己上一笔）。 */
    private void sendUndo() {
        if (!StageBoardCache.ready()) return;
        ClientPacketDistributor.sendToServer(new BoardPackets.UndoPayload(StageBoardCache.key()));
    }

    /** 屏幕点 → 本局画板格子坐标（不在绘画区 / 还没收到板 = null）。 */
    private int[] boardCellAt(double sx, double sy) {
        GameDefinition.StageView view = ClientGameHandler.boundView(false);
        if (view == null || !StageBoardCache.ready()) return null;
        double[] p = placement();
        for (GameDefinition.Component c : flatten(view.components())) {
            if (!c.type().equals("draw")) continue;
            double x = p[0] + c.x() * p[2], y = p[1] + c.y() * p[2];
            double w = c.w() * p[2], h = c.h() * p[2];
            if (sx < x || sx >= x + w || sy < y || sy >= y + h) continue;
            int bw = StageBoardCache.width(), bh = StageBoardCache.height();
            double cell = Math.min(w / bw, h / bh);
            double ox = x + (w - bw * cell) / 2.0, oy = y + (h - bh * cell) / 2.0;
            int cx = (int) ((sx - ox) / cell), cy = (int) ((sy - oy) / cell);
            if (cx < 0 || cy < 0 || cx >= bw || cy >= bh) return null;
            return new int[]{cx, cy};
        }
        return null;
    }

    /**
     * 点到可点的框 → 把「哪个框」发回宿主（身份值由宿主查账，客户端只报框 id）。
     * {@code click} 键 = 可点，值是宿主给的框 id；命中从后往前找（后画的在上面）。
     */
    private boolean clickPick(double sx, double sy) {
        GameDefinition.StageView view = ClientGameHandler.boundView(false);
        if (view == null) return false;
        double[] p = placement();
        List<GameDefinition.Component> comps = flatten(view.components());
        for (int k = comps.size() - 1; k >= 0; k--) {
            GameDefinition.Component c = comps.get(k);
            String box = c.s("click", "");
            if (box.isEmpty()) continue;
            double x = p[0] + c.x() * p[2], y = p[1] + c.y() * p[2];
            double w = c.w() * p[2], h = c.h() * p[2];
            if (sx < x || sx >= x + w || sy < y || sy >= y + h) continue;
            ClientPacketDistributor.sendToServer(new HostPackets.ActPayload("", "", box));
            return true;
        }
        return false;
    }

    /** 按钮提交：动作 + 固定参数（args）直接发（「我画好了」「过」这类不需要打字）。 */
    private void submitAction(String action, String args) {
        ClientPacketDistributor.sendToServer(new HostPackets.ActPayload(action, args == null ? "" : args, ""));
    }

    /** 组件树拍平（含分组子组件）。 */
    private static List<GameDefinition.Component> flatten(List<GameDefinition.Component> list) {
        List<GameDefinition.Component> out = new ArrayList<>();
        for (GameDefinition.Component c : list) {
            out.add(c);
            out.addAll(flatten(c.children()));
        }
        return out;
    }

    /** 提交（回车唯一入口）：发给「当前在等的动作」，内容是焦点框里的字。 */
    private void submit() {
        String action = builtFor;
        EditBox target = focusedInput();
        if (action.isEmpty() || target == null) return;
        int at = Math.max(0, inputs.indexOf(target));
        String boxId = at < inputIds.size() ? inputIds.get(at) : "";      // 哪个框交的（宿主查资产名）
        ClientPacketDistributor.sendToServer(new HostPackets.ActPayload(action, target.getValue(), boxId));
        target.setValue("");            // 清空；等输入结束的快照一到，输入框自己变灰
    }

    /** 焦点所在的输入框（回车提交的就是它）；都没焦点就用第一个。 */
    private EditBox focusedInput() {
        for (EditBox b : inputs) if (b.isFocused()) return b;
        return inputs.isEmpty() ? null : inputs.get(0);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 && !builtFor.isEmpty()) {   // Enter = 提交（Esc 走基类关屏回 HUD）
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * 玩家按 Esc 关屏时（基类走到这儿），告诉 {@link ClientGameHandler}「玩家自己关掉了屏」——
     * 它据此忘掉上一次的全屏块，脚本再喊同一个 {@code show("名")} 还能把屏唤回来。
     */
    @Override
    public void onClose() {
        ClientGameHandler.stageClosedByPlayer();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 全屏承载：整块屏幕就是舞台，不要底板/模糊（与编辑器那种「工作台」观感区分开）
        g.fill(0, 0, width, height, 0xB0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        GameDefinition.StageUi ui = layout();
        HostPackets.HostStatePayload st = ClientGameHandler.hostState();
        if (ui == null) {
            g.centeredText(font, Component.literal("不在对局中（/tablegame start <游戏名> 开局）"),
                    width / 2, height / 2, 0xFFC8C8D0);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            drawBrushUi(g, mouseX, mouseY);
            return;
        }
        // 舞台：等比铺满中间那一条，居中；框由共享渲染器画（与 HUD 同一份实现）
        double[] p = placement();
        int ox = (int) p[0], oy = (int) p[1];
        int cw = (int) Math.round(ui.w() * p[2]), ch = (int) Math.round(ui.h() * p[2]);
        g.fill(ox, oy, ox + cw, oy + ch, ui.bgArgb());   // 铺底那一层：脚本 bg("…") 优先，没写 = 缺省 40% 黑
        StageRenderer.draw(g, ui, ox, oy, p[2]);

        // 上下信息带：全屏承载负责把「现在是哪个阶段」说清（HUD 上没有这两行）
        if (st != null && st.inGame()) {
            g.text(font, st.nodeTitle(), 6, 4, 0xFFFFFFFF);
        }
        if (builtFor.isEmpty() && !inputs.isEmpty()) {
            g.text(font, "现在不用输入（等提示出现时输入框会亮起来）", 6, height - 14, 0xFF909090);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawBrushUi(g, mouseX, mouseY);
    }
}
