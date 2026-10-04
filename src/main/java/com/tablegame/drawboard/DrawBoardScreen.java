package com.tablegame.drawboard;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 画板项目编辑器（26.x 新 GUI 体系）。
 *
 * <p>画布宽×高非正方形，尺寸新建时定死。右侧 rail 上为色板组、下为工具组（画笔/橡皮/填充/撤销/导出 + 粗细槽）；
 * 视口在固定方形显示区内：滚轮以鼠标格为锚缩放（1~96 px/格），中键拖动平移，右键按住 = 临时橡皮。
 *
 * <p>本地像素镜像改动先落本地再每 tick 分批发服务器（服务端权威、值相同幂等）；撤销/填充/导出只发请求等广播。
 * 渲染：透明格 2×2 棋盘底纹（cell&lt;3px 整板平灰），同行同色 run 合并矩形，只遍历可见行列。
 * // ponytail: 最坏每帧矩形数 ≈ 可见格数（棋盘相邻必异色无法合并）；卡顿再回纹理路径
 */
public class DrawBoardScreen extends Screen {
    private final String key;
    private final String group;
    private final String name;
    private final String ownerName;
    private final int boardWidth, boardHeight;
    private final boolean canEdit;
    private final int[] pixels;

    private static final int MAX_CELLS_PER_PACKET = 1500;
    /** 画笔/橡皮粗细上限（正方形边长，单位：格）。 */
    private static final int MAX_BRUSH = 8;
    private static final int MIN_CELL = 1;
    private static final int MAX_CELL = 96;

    // ===== 自定义颜色 =====
    // 「会话内保留」：本进程内跨画板都记住（static），退出游戏清零；固定 10 槽，0 = 空槽标记。
    private static final int MAX_CUSTOM = 10;
    private static final int[] CUSTOM_COLORS = new int[MAX_CUSTOM];

    // ===== 工具/绘制状态 =====
    // 画笔色与当前画布色分离：橡皮态 currentColor 锁定为透明，切回画笔从 penColor 恢复。
    private int penColor = 0xFF000000;
    private int currentColor = 0xFF000000;
    private boolean eraser;                 // false=画笔 true=橡皮（互斥工具态）
    private int penSize = 1, eraserSize = 1; // 画笔/橡皮各自的粗细记忆（格）
    private boolean pendingFill = false;    // 填充待命（一次）：图标高亮，点画布执行后自动复位
    private boolean rightErase = false;     // 右键按住=临时橡皮（不改工具态，只把落笔色强制透明）
    private long strokeId = 0;
    private boolean drawing = false;
    private int lastCellX = -1, lastCellY = -1;
    private final List<int[]> pendingCells = new ArrayList<>();

    // 自定义色选色面板（点自定义槽时弹出：RGB 滑杆 + hex 输入联动）
    private static final int PICKER_W = 210, PICKER_H = 150;
    private ColorPickerPanel picker;
    private EditBox hexBox;
    /** 正在编辑的槽位：-1 = 未打开面板。 */
    private int editSlot = -1;
    /** 面板当前预览色（滑杆/hex 实时写入）。 */
    private int editColor = 0xFF000000;

    // 视口：canvasX/Y = 显示区左上角，viewPx = 显示区边长；cell = px/格；offX/Y = 内容偏移(px)
    private int canvasX, canvasY, viewPx, cell;
    private double offX, offY;
    private boolean panning = false;
    private double panLastX, panLastY;

    // 个人镜像防抖
    private boolean mirrorDirty = false;
    private int mirrorCountdown = 0;
    private BoardStore mirrorStore;

    private static final int[] PALETTE = {
            0xFF000000, 0xFF404040, 0xFF808080, 0xFFB0B0B0, 0xFFE0E0E0, 0xFFFFFFFF,
            0xFF8B0000, 0xFFFF0000, 0xFFFF8C00, 0xFFFFD700, 0xFFFFFF00, 0xFFADFF2F,
            0xFF00FF00, 0xFF008000, 0xFF00FFFF, 0xFF00B0D0, 0xFF0000FF, 0xFF4B0082,
            0xFF8B00FF, 0xFFFF00FF, 0xFFFF69B4, 0xFFFA8072, 0xFFA0522D, 0xFF8B4513,
            0xFFD2B48C, 0xFFF5DEB3, 0xFF228B22, 0xFF2E8B57, 0xFF4682B4, 0xFF708090};

    // 空格（透明格）的棋盘底纹色与阈值在画布组件层 BoardCanvas（EMPTY_A / EMPTY_B / CHECKER_MIN_CELL）。

    public DrawBoardScreen(String key, String ownerName, int width, int height, byte[] fullCells, boolean canEdit) {
        super(Component.literal("画板 · " + key));
        this.key = key;
        int slash = key.indexOf('/');
        this.group = slash > 0 ? key.substring(0, slash) : key;
        this.name = slash > 0 && slash < key.length() - 1 ? key.substring(slash + 1) : key;
        this.ownerName = ownerName;
        this.canEdit = canEdit;
        this.boardWidth = width;
        this.boardHeight = height;
        this.pixels = new int[width * height];
        for (int off = 0; off + 8 <= fullCells.length; off += 8) {
            int x = Cells.u16(fullCells, off);
            int y = Cells.u16(fullCells, off + 2);
            if (x < width && y < height) pixels[y * width + x] = Cells.argbFromBytes(fullCells, off + 4);
        }
    }

    public String getKey() {
        return key;
    }

    public boolean matches(String boardKey) {
        return this.key.equals(boardKey);
    }

    /** 应用一段服务端广播的格子增量。 */
    public void applyCells(byte[] cells) {
        for (int off = 0; off + 8 <= cells.length; off += 8) {
            int x = Cells.u16(cells, off);
            int y = Cells.u16(cells, off + 2);
            if (x >= boardWidth || y >= boardHeight) continue;
            pixels[y * boardWidth + x] = Cells.argbFromBytes(cells, off + 4);
        }
        markMirrorDirty();
    }

    // ===== 工具状态（画笔/橡皮/粗细） =====

    /** 当前工具的正方形边长（格）。画笔、橡皮各自记忆，互不干扰。 */
    private int activeSize() {
        return eraser ? eraserSize : penSize;
    }

    /** 滑杆拖动写入：作用于「当前选中工具」的粗细。 */
    private void setActiveSize(int v) {
        v = Math.max(1, Math.min(MAX_BRUSH, v));
        if (eraser) eraserSize = v; else penSize = v;
    }

    /** 选中画笔：解除填充待命，恢复画笔色。 */
    private void selectPen() {
        eraser = false;
        pendingFill = false;
        currentColor = penColor;
    }

    /** 选中橡皮：解除填充待命，当前色锁定为透明（透明格=未画，服务端当擦除处理）。 */
    private void selectEraser() {
        eraser = true;
        pendingFill = false;
        currentColor = 0x00000000;
    }

    private void sendUndo() {
        ClientPacketDistributor.sendToServer(new BoardPackets.UndoPayload(key));
    }

    private void exportBoard() {
        ClientPacketDistributor.sendToServer(new BoardPackets.ExportPayload(key));
    }

    // ===== 视口（平移/缩放/映射） =====

    /** 内容整体像素尺寸。 */
    private double contentW() {
        return (double) cell * boardWidth;
    }

    private double contentH() {
        return (double) cell * boardHeight;
    }

    /** 平移/居中约束：内容可在视口内自由漫游 —— 内容比视口大时偏移限在 [视口-内容, 0]，
     *  不会把画板整个拖出视野；比视口小时可滑动但始终完整可见。默认位置为居中。 */
    private void clampView() {
        offX = clampOffset(offX, contentW());
        offY = clampOffset(offY, contentH());
    }

    /** 单轴约束：内容尺寸 size、视口边长 viewPx 时，合法偏移区间为
     *  [min(0, viewPx-size), max(0, viewPx-size)] —— size 大于视口时为负偏移（内容往左/上滑），
     *  小于时为正偏移（往右/下滑，仍完整可见），相等时只能是 0。 */
    private double clampOffset(double off, double size) {
        double lo = Math.min(0.0, viewPx - size);
        double hi = Math.max(0.0, viewPx - size);
        return Math.max(lo, Math.min(hi, off));
    }

    /** 滚轮缩放：以鼠标所在内容点为锚（该点屏幕位置不动）。 */
    private void zoomAt(double sx, double sy, double amount) {
        if (amount == 0) return;
        int oldCell = cell;
        int step = Math.max(1, oldCell / 8);
        cell = amount > 0 ? Math.min(MAX_CELL, oldCell + step) : Math.max(MIN_CELL, oldCell - step);
        if (cell == oldCell) return;
        // 锚点：缩放前鼠标下的内容坐标，缩放后仍应在鼠标下
        double anchorX = (sx - canvasX) - offX;
        double anchorY = (sy - canvasY) - offY;
        offX = (sx - canvasX) - anchorX * ((double) cell / oldCell);
        offY = (sy - canvasY) - anchorY * ((double) cell / oldCell);
        clampView();
    }

    private void panBy(double dx, double dy) {
        offX += dx;
        offY += dy;
        clampView();
    }

    /** 画布 widget 引用（Screen 层右键橡皮要直接驱动它的笔画状态机）。 */
    private CanvasWidget canvasWidget;

    /** 屏幕坐标 → 格坐标（取整；未命中返回越界值由调用方守卫）。 */
    private int cellAtX(double sx) {
        return (int) Math.floor((sx - canvasX - offX) / cell);
    }

    private int cellAtY(double sy) {
        return (int) Math.floor((sy - canvasY - offY) / cell);
    }

    /** 屏幕点是否落在画布视口内。 */
    private boolean overView(double sx, double sy) {
        return sx >= canvasX && sx < canvasX + viewPx && sy >= canvasY && sy < canvasY + viewPx;
    }

    // 中键拖动平移 + 右键临时橡皮（Screen 级拦截：中键/右键不走子控件的左键拖拽管线）
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 选色面板打开时模态拦截画布鼠标：hex 框区自聚焦，滑杆区调色，面板外取消。
        if (editSlot >= 0) {
            double bx = hexBox.getX(), by = hexBox.getY();
            boolean inHex = event.button() == 0
                    && event.x() >= bx && event.x() < bx + hexBox.getWidth()
                    && event.y() >= by && event.y() < by + hexBox.getHeight();
            if (inHex) {
                hexBox.setFocused(true);
                setFocused(hexBox);
                hexBox.mouseClicked(event, doubled);   // 交给 EditBox 自己：按点击处定位光标（内部会再聚焦，无副作用）
            } else {
                picker.mouseClicked(event, doubled);
                if (!(event.x() >= picker.getX() && event.x() < picker.getX() + PICKER_W
                        && event.y() >= picker.getY() && event.y() < picker.getY() + PICKER_H)) {
                    cancelPicker();
                }
            }
            return true;   // 面板打开期间不让点击落到画布（防误画/误平移）
        }
        if (event.button() == 2 && overView(event.x(), event.y())) {
            panning = true;
            panLastX = event.x();
            panLastY = event.y();
            return true;
        }
        // 右键 = 临时橡皮：直接驱动画布开一笔透明笔画（不回落到子控件）
        if (event.button() == 1 && overView(event.x(), event.y())) {
            canvasWidget.erasePress(event.x(), event.y());
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (editSlot >= 0) {
            picker.mouseDragged(event, dx, dy);   // 面板滑杆拖动（面板打开时画布不可拖）
            return true;
        }
        if (panning && event.button() == 2) {
            panBy(event.x() - panLastX, event.y() - panLastY);
            panLastX = event.x();
            panLastY = event.y();
            return true;
        }
        if (event.button() == 1 && rightErase) {
            canvasWidget.eraseDrag(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (panning && event.button() == 2) {
            panning = false;
            return true;
        }
        if (event.button() == 1 && rightErase) {
            canvasWidget.eraseRelease();
            return true;
        }
        return super.mouseReleased(event);
    }

    /** 键盘：选色面板打开时 Enter 应用 / Esc 取消；否则走默认。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editSlot >= 0) {
            if (event.key() == 257) {        // Enter
                applyPicker();
                return true;
            }
            if (event.key() == 256) {        // Esc
                cancelPicker();
                return true;
            }
            return hexBox.keyPressed(event); // 数字/hex 输入给 hex 框
        }
        return super.keyPressed(event);
    }

    // ===== 个人镜像 =====

    private void markMirrorDirty() {
        mirrorDirty = true;
        mirrorCountdown = 40;
    }

    private void saveMirror() {
        try {
            if (mirrorStore == null) {
                mirrorStore = new BoardStore(Minecraft.getInstance().gameDirectory.toPath());
            }
            Board copy = new Board(group, name, ownerName, boardWidth, boardHeight);
            System.arraycopy(pixels, 0, copy.pixels, 0, pixels.length);
            mirrorStore.save(copy);
        } catch (Exception e) {
            // 镜像只是个人备份，失败不影响主流程
        }
        mirrorDirty = false;
    }

    // ===== 每 tick 发包 =====

    @Override
    public void tick() {
        flushPending(false);
        if (mirrorDirty && --mirrorCountdown <= 0) saveMirror();
    }

    @Override
    public void removed() {
        if (drawing) {
            drawing = false;
            flushPending(true);
        }
        if (mirrorDirty) saveMirror();
        super.removed();
    }

    private void flushPending(boolean end) {
        while (!pendingCells.isEmpty()) {
            int n = Math.min(MAX_CELLS_PER_PACKET, pendingCells.size());
            byte[] cells = new byte[n * 8];
            for (int i = 0; i < n; i++) {
                int[] c = pendingCells.get(i);
                Cells.write(cells, i * 8, c[0], c[1], c[2]);
            }
            ClientPacketDistributor.sendToServer(new BoardPackets.StrokeCellsPayload(key, strokeId, cells, false));
            pendingCells.subList(0, n).clear();
        }
        if (end) {
            ClientPacketDistributor.sendToServer(new BoardPackets.StrokeCellsPayload(key, strokeId, new byte[0], true));
        }
    }

    // ===== 界面搭建（右侧工具栏） =====
    // railX = 画布右缘 + 18px：上色板组（当前色 + 30 色矩阵 + 自定义色区），下工具组（5 图标 + 粗细滑杆）；只读只剩「导出 + 只读提示」。

    /** 图标尺寸与间距（像素）。 */
    private static final int ICON = 20, ICON_GAP = 3;
    /** 工具槽高度（滑杆或提示行）。 */
    private static final int SLOT_H = 18;
    /** 图标行含 5 个工具时整行宽度（调色板 5 列排版也用这个宽度）。 */
    private static final int RAIL_W = 5 * ICON + 4 * ICON_GAP;

    @Override
    protected void init() {
        // 视口：固定方形显示区，整体随窗口尺寸自动适配
        int availW = Math.max(120, width - 190);
        int availH = Math.max(120, height - 90);
        viewPx = Math.max(100, Math.min(availW, Math.min(availH, 600)));
        canvasX = Math.max(8, (width - viewPx - 150) / 2);
        canvasY = Math.max(36, (height - viewPx) / 2);
        // 初始缩放：适配整板后再留 ~10% 边距 —— 恰好铺满视口时中键平移在几何上无空间，留边距后任何缩放级别都能拖。
        double fitPx = Math.min((double) viewPx / boardWidth, (double) viewPx / boardHeight);
        cell = Math.max(MIN_CELL, (int) (fitPx * 0.9));
        // 初始放居中（必在漫游区间内）；clampView 兜底
        offX = (viewPx - contentW()) / 2.0;
        offY = (viewPx - contentH()) / 2.0;
        clampView();

        clearWidgets();
        canvasWidget = new CanvasWidget();
        addRenderableWidget(canvasWidget);

        int railX = canvasX + viewPx + 18;
        int topY = Math.max(canvasY, 40);

        // rail 布局：色板组在上（当前色指示 → 30 色矩阵 → 自定义色区），工具图标行 + 粗细滑杆在下
        if (canEdit) {
            // 上：色板组
            addRenderableOnly(new ColorIndicator(railX, topY));
            int palY = topY + 20;              // 指示块（高18）下沿再留 2px 空隙
            int rows = PALETTE.length / 5;     // 30 色按 5 列排 → 6 行
            for (int i = 0; i < PALETTE.length; i++) {
                addRenderableWidget(new PaletteChip(railX + (i % 5) * 18, palY + (i / 5) * 18, PALETTE[i]));
            }
            // 自定义色区：30 色矩阵下方 4px 空隙，10 槽按 5 列 × 2 行排（点任意槽弹选色面板）
            int cusY = palY + rows * 18 + 4;
            for (int i = 0; i < MAX_CUSTOM; i++) {
                addRenderableWidget(new CustomColorCell(railX + (i % 5) * 18, cusY + (i / 5) * 18, i));
            }
            // 下：工具图标行 + 粗细滑杆（色板区整体下沿 + 8px 间距）
            int toolY = cusY + 2 * 18 + 8;
            addRenderableWidget(new ToolIconButton(railX, toolY, ToolKind.PEN));
            addRenderableWidget(new ToolIconButton(railX + ICON + ICON_GAP, toolY, ToolKind.ERASER));
            addRenderableWidget(new ToolIconButton(railX + 2 * (ICON + ICON_GAP), toolY, ToolKind.FILL));
            addRenderableWidget(new ToolIconButton(railX + 3 * (ICON + ICON_GAP), toolY, ToolKind.UNDO));
            addRenderableWidget(new ToolIconButton(railX + 4 * (ICON + ICON_GAP), toolY, ToolKind.EXPORT));
            addRenderableWidget(new ToolSlot(railX, toolY + ICON + 3));
        } else {
            // 只读：只有「导出」图标 + 【只读】提示（无色板可画，仍放 rail 顶部）
            addRenderableWidget(new ToolIconButton(railX, topY, ToolKind.EXPORT));
            addRenderableWidget(new ToolSlot(railX, topY + ICON + 3));
        }
        // 选色面板 + hex 输入框默认不显示（editSlot = -1），点自定义槽时浮在画布视口中央。
        int px = canvasX + viewPx / 2 - PICKER_W / 2;
        int py = canvasY + viewPx / 2 - PICKER_H / 2;
        picker = new ColorPickerPanel(px, py);
        addRenderableWidget(picker);
        hexBox = new EditBox(font, px + 44, py + 104, 96, 16, Component.literal("hex 颜色"));
        hexBox.setMaxLength(6);
        hexBox.setFilter(s -> s.length() <= 6 && s.matches("[0-9a-fA-F]{0,6}"));
        hexBox.setResponder(s -> {
            // 六位合法 hex → 实时同步预览色（输入中不完全时不动，避免滑杆跳）
            if (s.length() == 6) {
                try {
                    editColor = 0xFF000000 | (int) Long.parseLong(s, 16);
                } catch (NumberFormatException ignored) {
                }
            }
        });
        hexBox.setVisible(false);
        addRenderableWidget(hexBox);
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

    // ===== 工具图标 =====
    // 图标 = 外部 16×16 PNG（assets/tablegame/textures/gui/drawboard/）；按 26.x GUI 规则逐像素 fill（不 blit 纹理），透明像素跳过。

    /** 工具种类：画笔/橡皮/填充是可「选中」的工具，撤销/导出是瞬发动作。
     *  每个工具对应一张 16×16 图标 PNG 资源名。 */
    private enum ToolKind {
        PEN("画笔", "pen"), ERASER("橡皮", "eraser"), FILL("填充", "fill"),
        UNDO("撤销", "undo"), EXPORT("导出", "export");
        final String label;
        final String tex;   // assets/tablegame/textures/gui/drawboard/<tex>.png

        ToolKind(String label, String tex) {
            this.label = label;
            this.tex = tex;
        }
    }

    /** 图标 PNG 像素缓存：ToolKind → 16×16 ARGB 像素（懒加载）。 */
    private static final Map<ToolKind, int[]> ICON_PIXELS = new EnumMap<>(ToolKind.class);

    /** 加载某个工具的 16×16 图标像素（ABGR NativeImage → ARGB int 数组）。
     *  NativeImage.getPixel 已转 ARGB；读失败返回 null，由渲染方降级为纯色底（不该发生）。 */
    private static int[] loadIcon(ToolKind kind) {
        int[] cached = ICON_PIXELS.get(kind);
        if (cached != null) return cached;
        int[] px = null;
        try {
            Identifier path = Identifier.fromNamespaceAndPath(
                    "tablegame", "textures/gui/drawboard/" + kind.tex + ".png");
            Resource res = Minecraft.getInstance().getResourceManager().getResource(path).orElse(null);
            if (res != null) {
                try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
                    int w = img.getWidth(), h = img.getHeight();
                    px = new int[w * h];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            px[y * w + x] = img.getPixel(x, y);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 图标缺失不致命：落回 null，渲染端画占位块即可
        }
        ICON_PIXELS.put(kind, px == null ? new int[0] : px);
        return px == null ? new int[0] : px;
    }

    /** 按 1px/格逐像素画 16×16 图标（26.x：不 blit 纹理，纯色矩形逐格 fill）。 */
    private static void drawGlyph(GuiGraphicsExtractor g, int x, int y, ToolKind kind) {
        int[] px = loadIcon(kind);
        if (px.length == 0) {
            g.fill(x, y, x + 16, y + 16, 0xFF555555);   // 兜底占位块
            return;
        }
        int w = 16;
        for (int i = 0; i < px.length; i++) {
            int col = px[i];
            if ((col >>> 24) == 0) continue;   // 全透明像素跳过
            int cx = x + i % w, cy = y + i / w;
            g.fill(cx, cy, cx + 1, cy + 1, col);
        }
    }

    /** 工具图标按钮（20×20）。选中态/待命态 = 亮底 + 白框，悬停 = 中亮底。 */
    private class ToolIconButton extends AbstractWidget {
        private final ToolKind kind;

        ToolIconButton(int x, int y, ToolKind kind) {
            super(x, y, ICON, ICON, Component.literal(kind.label));
            this.kind = kind;
        }

        /** 当前是否处于「选中/待命」高亮态。 */
        private boolean active() {
            return switch (kind) {
                case PEN -> !eraser && !pendingFill;
                case ERASER -> eraser && !pendingFill;
                case FILL -> pendingFill;             // 填充待命一次：执行后由画布点击复位
                case UNDO, EXPORT -> false;           // 瞬发动作无选中态
            };
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean act = active();
            boolean hover = isHovered();
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    act ? 0xFF5A5A5A : hover ? 0xFF414141 : 0xFF2E2E2E);
            g.outline(getX(), getY(), getWidth(), getHeight(), act ? 0xFFFFFFFF : 0xFF5A5A5A);
            // 图元 16×16 画在按钮正中（20-16)/2 = +2
            drawGlyph(g, getX() + 2, getY() + 2, kind);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            switch (kind) {
                case PEN -> selectPen();
                case ERASER -> selectEraser();
                case FILL -> pendingFill = !pendingFill;   // 再点一次取消待命
                case UNDO -> {
                    pendingFill = false;
                    sendUndo();
                }
                case EXPORT -> {
                    pendingFill = false;
                    exportBoard();
                }
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    // ===== 工具槽（滑杆 + 数字 / 提示行） =====

    /** 工具槽：画笔/橡皮选中时画「粗细滑杆 + 数字」并接受拖动；
     *  填充待命或只读时只画提示文字、不响应鼠标。 */
    private class ToolSlot extends AbstractWidget {
        private static final int TRACK_W = 62;   // 滑杆轨道长（px）
        private static final int THUMB_W = 4;    // 滑块宽（px）

        ToolSlot(int x, int y) {
            super(x, y, RAIL_W, SLOT_H, Component.literal("粗细"));
        }

        /** 滑杆是否可见可拖：可编辑且不是填充待命（画笔/橡皮二态恒有一个选中）。 */
        private boolean sliderMode() {
            return canEdit && !pendingFill;
        }

        /** 鼠标 x → 粗细值（1..MAX_BRUSH）。轨道左端余 2px，右端与滑块宽度对齐。 */
        private int valueAt(double mx) {
            double f = (mx - (getX() + 2)) / (double) (TRACK_W - THUMB_W);
            f = Math.max(0.0, Math.min(1.0, f));
            return 1 + (int) Math.round(f * (MAX_BRUSH - 1));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            if (sliderMode()) {
                int v = activeSize();
                int y = getY();
                // 轨道（暗底）
                g.fill(getX() + 2, y + 7, getX() + 2 + TRACK_W, y + 11, 0xFF3A3A3A);
                // 滑块（白亮竖条），位置 = 值在 [1..MAX] 上的比例
                int tx = getX() + 2 + (int) Math.round((v - 1) / (double) (MAX_BRUSH - 1) * (TRACK_W - THUMB_W));
                g.fill(tx, y + 2, tx + THUMB_W, y + 14, 0xFFE8E8E8);
                // 数字（粗细值，格）
                g.text(font, v + "格", getX() + 2 + TRACK_W + 8, y + 4, 0xFFE0E0E0);
            } else if (!canEdit) {
                g.text(font, "【只读】", getX(), getY() + 4, 0xFF9A9A9A);
            } else {
                // 填充待命：提示点画布（黄色提醒，防忘了自己刚点的什么）
                g.text(font, "点画布填充", getX(), getY() + 4, 0xFFFFD75F);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (sliderMode()) setActiveSize(valueAt(event.x()));
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            if (event.button() == 0 && sliderMode()) setActiveSize(valueAt(event.x()));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    // ===== 画布 widget（视口） =====

    private class CanvasWidget extends AbstractWidget {
        CanvasWidget() {
            super(canvasX, canvasY, viewPx, viewPx, Component.literal("画布"));
        }

        // 格子的显示色已抽到画布组件层：BoardCanvas.displayColor（棋盘底纹规则在那边，与舞台 HUD 共用）

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            // 组/项目名（key = 组/名）居中于画布中轴正上方
            g.centeredText(font, key, canvasX + viewPx / 2, canvasY - 22, 0xFFFFFFFF);
            // 宽高 + 操作说明信息条：居中于画布正下方
            String sub = (canEdit ? "" : "【只读】")
                    + "宽 " + boardWidth + " × 高 " + boardHeight + "（" + cell + "px/格）"
                    + " ｜ 左键画/填充 ｜ 中键拖动平移 ｜ 滚轮缩放";
            g.centeredText(font, sub, canvasX + viewPx / 2, canvasY + viewPx + 8, 0xFFB0B0B0);

            // 视口底色（画布外框 + 视口纸色，画板四周露出的区域）
            g.fill(canvasX - 1, canvasY - 1, canvasX + viewPx + 1, canvasY + viewPx + 1, 0xFF181818);
            g.fill(canvasX, canvasY, canvasX + viewPx, canvasY + viewPx, 0xFF3A3A3A);

            // 板内容：交给画布组件层（棋盘底纹 / run 合并 / 平灰降级都在那里，与舞台 HUD 共用同一份）
            BoardCanvas.render(g, pixels, boardWidth, boardHeight,
                    canvasX, canvasY, viewPx, viewPx, offX, offY, cell, true);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (!canEdit) return;
            // 点画布时若选色面板开着：点击早被 Screen.mouseClicked 模态拦截（editSlot>=0 不落子控件），无需在此处理
            int cx = cellAtX(event.x());
            int cy = cellAtY(event.y());
            if (cx < 0 || cx >= boardWidth || cy < 0 || cy >= boardHeight) return;
            if (pendingFill) {
                pendingFill = false;   // 一次填充：执行完自动复位（工具回到画笔/橡皮原态）
                ClientPacketDistributor.sendToServer(new BoardPackets.FillPayload(key, cx, cy, currentColor));
                return;
            }
            strokeId = System.nanoTime();
            drawing = true;
            lastCellX = -1;
            paintLineTo(cx, cy);
        }

        // ===== 右键临时橡皮 =====
        // 右键被 Screen.mouseClicked 拦下后驱动这里（26.x 控件点击只分发左键）：与左键笔画共用状态机，
        // 仅把落笔色强制透明，不切换工具态——松开即还原。

        /** 右键按下：开一笔透明「擦除」笔画。 */
        void erasePress(double sx, double sy) {
            if (!canEdit) return;
            rightErase = true;
            pendingFill = false;         // 右键不触发填充待命
            int cx = cellAtX(sx);
            int cy = cellAtY(sy);
            if (cx < 0 || cx >= boardWidth || cy < 0 || cy >= boardHeight) return;
            strokeId = System.nanoTime();
            drawing = true;
            lastCellX = -1;
            paintLineTo(cx, cy);
        }

        /** 右键拖动：延续当前擦除笔画。 */
        void eraseDrag(double sx, double sy) {
            if (!canEdit || !rightErase || !drawing) return;
            int cx = cellAtX(sx);
            int cy = cellAtY(sy);
            if (cx >= 0 && cx < boardWidth && cy >= 0 && cy < boardHeight) paintLineTo(cx, cy);
        }

        /** 右键松开：结束笔画、清临时橡皮标志。 */
        void eraseRelease() {
            if (rightErase) {
                rightErase = false;
                if (drawing) {
                    drawing = false;
                    lastCellX = lastCellY = -1;
                    flushPending(true);
                }
            }
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            if (!canEdit || !drawing) return;
            int cx = cellAtX(event.x());
            int cy = cellAtY(event.y());
            if (cx >= 0 && cx < boardWidth && cy >= 0 && cy < boardHeight) paintLineTo(cx, cy);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            if (drawing) {
                drawing = false;
                lastCellX = lastCellY = -1;
                flushPending(true);
            }
        }

        /** 滚轮缩放（以鼠标位置为锚）。amount 用竖向（yAmount），无竖向时用横向。 */
        @Override
        public boolean mouseScrolled(double mx, double my, double xAmount, double yAmount) {
            zoomAt(mx, my, yAmount != 0 ? yAmount : xAmount);
            return true;
        }

        private void paintLineTo(int cx, int cy) {
            int fx = lastCellX >= 0 ? lastCellX : cx;
            int fy = lastCellY >= 0 ? lastCellY : cy;
            int steps = Math.max(Math.abs(cx - fx), Math.abs(cy - fy));
            for (int i = 0; i <= steps; i++) {
                int x = fx + (i * (cx - fx)) / Math.max(1, steps);
                int y = fy + (i * (cy - fy)) / Math.max(1, steps);
                paintBrushAt(x, y);
            }
            lastCellX = cx;
            lastCellY = cy;
        }

        /** 以 (cx,cy) 为中心盖一方块，边长 = activeSize() 格；偶数边长往 +x/+y 多偏 1 格，保证正好 n×n。
         *  落笔色：右键临时橡皮期间强制透明，否则用当前色。 */
        private void paintBrushAt(int cx, int cy) {
            int n = activeSize();
            int color = rightErase ? 0x00000000 : currentColor;
            int x0 = cx - (n - 1) / 2, x1 = cx + n / 2;
            int y0 = cy - (n - 1) / 2, y1 = cy + n / 2;
            for (int y = Math.max(0, y0); y <= Math.min(boardHeight - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(boardWidth - 1, x1); x++) {
                    pixels[y * boardWidth + x] = color;
                    pendingCells.add(new int[]{x, y, color});
                }
            }
            markMirrorDirty();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    // ===== 当前颜色指示 =====

    private class ColorIndicator extends AbstractWidget {
        ColorIndicator(int x, int y) {
            super(x, y, 72, 18, Component.literal("当前颜色"));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.fill(getX(), getY(), getX() + 14, getY() + 14, 0xFF606060);
            if ((currentColor & 0xFF000000) == 0) {
                // 透明（橡皮态）：白底 + 红斜杠
                g.fill(getX(), getY(), getX() + 14, getY() + 14, 0xFFFFFFFF);
                g.horizontalLine(getX(), getX() + 14, getY() + 7, 0xFFFF2020);
            } else {
                g.fill(getX(), getY(), getX() + 14, getY() + 14, currentColor);
            }
            g.text(font, Cells.toHex8(currentColor), getX() + 18, getY() + 3, 0xFFE0E0E0);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    // ===== 调色板色块 =====

    private class PaletteChip extends AbstractWidget {
        private final int color;

        PaletteChip(int x, int y, int color) {
            super(x, y, 16, 16, Component.literal("颜色 " + Cells.toHex8(color)));
            this.color = color;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF000000);
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, color);
            if (currentColor == color) {
                g.outline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, 0xFFFFFFFF);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            // 选色 = 切回画笔并更新笔色（不打断填充待命）
            selectColor(color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    // ===== 自定义色槽 =====

    /** 选色统一入口：更新画笔色并切回画笔（不打断填充待命）。 */
    private void selectColor(int color) {
        penColor = color;
        eraser = false;
        currentColor = color;
    }

    /**
     * 自定义色格：0..MAX_CUSTOM-1 对应 {@link #CUSTOM_COLORS} 槽位。点任意槽（空或有色）都打开选色面板：
     * 空槽从 0xFF808080 起步，有色槽预填该色；面板确认后写入该槽。
     */
    private class CustomColorCell extends AbstractWidget {
        private final int index;

        CustomColorCell(int x, int y, int index) {
            super(x, y, 16, 16, Component.literal("自定义颜色"));
            this.index = index;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int color = CUSTOM_COLORS[index];
            boolean filled = color != 0;
            g.fill(getX(), getY(), getX() + 16, getY() + 16, 0xFF000000);
            g.fill(getX() + 1, getY() + 1, getX() + 15, getY() + 15, filled ? color : 0xFF2A2A2A);
            if (filled) {
                if (currentColor == color) g.outline(getX() - 1, getY() - 1, 18, 18, 0xFFFFFFFF);
            } else if (isHovered()) {
                g.outline(getX(), getY(), 16, 16, 0xFF666666);   // 悬停空槽提示可点
            }
            if (editSlot == index) g.outline(getX() - 2, getY() - 2, 20, 20, 0xFFFFD75F);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            int start = CUSTOM_COLORS[index] != 0 ? CUSTOM_COLORS[index] : 0xFF808080;
            openPicker(index, start);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    // ===== 选色面板（RGB 滑杆 + hex 输入 + 预览） =====
    // 模态浮层：打开时吃掉画布上的鼠标（点面板外 = 取消不落笔），Esc 取消，Enter 应用。

    private class ColorPickerPanel extends AbstractWidget {
        private static final int TRACK_W = 130;   // 滑杆轨道长
        private static final int THUMB_W = 5;

        ColorPickerPanel(int x, int y) {
            super(x, y, PICKER_W, PICKER_H, Component.literal("选色"));
        }

        private int rowY(int r) {
            return getY() + 26 + r * 26;          // R/G/B 三行起始
        }

        /** 滑杆 x → 通道值 0..255。 */
        private int valueAt(double mx) {
            double f = (mx - (getX() + 8)) / (double) TRACK_W;
            return Math.max(0, Math.min(255, (int) Math.round(f * 255)));
        }

        private int channel(int c) {
            return switch (c) {
                case 0 -> (editColor >> 16) & 0xFF;
                case 1 -> (editColor >> 8) & 0xFF;
                default -> editColor & 0xFF;
            };
        }

        private void setChannel(int c, int v) {
            int r = (editColor >> 16) & 0xFF, g = (editColor >> 8) & 0xFF, b = editColor & 0xFF;
            if (c == 0) r = v; else if (c == 1) g = v; else b = v;
            setEditColor(0xFF000000 | (r << 16) | (g << 8) | b);
        }



        /** 命中第几行滑杆（-1 = 没中）。 */
        private int hitRow(double sy) {
            for (int r = 0; r < 3; r++) {
                if (sy >= rowY(r) && sy < rowY(r) + 18) return r;
            }
            return -1;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            if (editSlot < 0) return;   // 面板未打开不画
            // 底板 + 边框
            g.fill(getX(), getY(), getX() + PICKER_W, getY() + PICKER_H, 0xF0202020);
            g.outline(getX(), getY(), PICKER_W, PICKER_H, 0xFFB0B0B0);
            g.text(font, "自定义色 #" + (editSlot + 1), getX() + 8, getY() + 6, 0xFFFFFFFF);
            // 三根滑杆（R/G/B）+ 当前值
            for (int r = 0; r < 3; r++) {
                int y = rowY(r);
                int v = channel(r);
                g.fill(getX() + 8, y + 7, getX() + 8 + TRACK_W, y + 11, 0xFF3A3A3A);
                int tx = getX() + 8 + (int) Math.round(v / 255.0 * (TRACK_W - THUMB_W));
                g.fill(tx, y + 2, tx + THUMB_W, y + 14, 0xFFE8E8E8);
                g.text(font, (r == 0 ? "R" : r == 1 ? "G" : "B") + " " + v, getX() + 8 + TRACK_W + 8, y + 2, 0xFFE0E0E0);
            }
            // hex 输入提示
            g.text(font, "hex #", getX() + 8, getY() + 108, 0xFF9A9A9A);
            // 预览色块
            g.fill(getX() + 150, getY() + 102, getX() + 194, getY() + 130, 0xFF000000);
            g.fill(getX() + 152, getY() + 104, getX() + 192, getY() + 128, editColor);
            // 操作提示
            g.text(font, "Enter 应用 · Esc 取消", getX() + 8, getY() + PICKER_H - 14, 0xFF9A9A9A);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
            if (editSlot < 0) return false;
            if (!(event.x() >= getX() && event.x() < getX() + PICKER_W
                    && event.y() >= getY() && event.y() < getY() + PICKER_H)) return false;
            if (event.button() == 0) {
                int r = hitRow(event.y());
                if (r >= 0) setChannel(r, valueAt(event.x()));
            }
            return true;   // 面板内任意点击都吃掉，不落到画布
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            if (editSlot < 0) return;
            int r = hitRow(event.y());
            if (r >= 0) setChannel(r, valueAt(event.x()));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    /** 预览色改动统一入口（滑杆/外层共用）：写 editColor 并同步 hex 框文本；不经过 responder 防环。 */
    private void setEditColor(int c) {
        editColor = c;
        hexBox.setValue(Cells.toHex8(c).substring(2));   // 只要 RRGGBB 六位
    }

    /** 打开选色面板编辑某槽。 */
    private void openPicker(int slot, int startColor) {
        editSlot = slot;
        setEditColor(startColor);
        hexBox.setVisible(true);       // hexBox 是独立 widget，渲染受 visible 门控，开面板才显示
        hexBox.setFocused(true);
        setFocused(hexBox);            // 唯一键盘输入点：聚焦它，charTyped（字母/数字）经容器焦点链直达
    }

    /** Esc / 点面板外：取消（不改槽）。 */
    private void cancelPicker() {
        editSlot = -1;
        hexBox.setVisible(false);
        setFocused(null);
    }

    /** Enter：把面板色写入槽并选中。 */
    private void applyPicker() {
        if (editSlot >= 0) {
            CUSTOM_COLORS[editSlot] = editColor;
            selectColor(editColor);
        }
        editSlot = -1;
        hexBox.setVisible(false);
        setFocused(null);
    }
}
