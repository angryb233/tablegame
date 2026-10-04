package com.tablegame.editor.stage;

import com.tablegame.script.Ast;
import com.tablegame.script.Parser;
import com.tablegame.script.edit.ScriptEdit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.tablegame.core.LineRaster;
import com.tablegame.editor.EditorToolScreen;
import com.tablegame.editor.GameEditorScreen;
import com.tablegame.editor.script.ScriptEditScreen;

    /**
     * 实体画面全屏可视化编辑页：正交 3D 视口（相机链同 {@link AreaViewScreen}），拖拽转视角 /
     * 滚轮缩放 / WASD 平移 / R 回正；右侧工具栏选 / 移 / 转 / 缩 / ＋新建，右键组件出菜单。
     * 坐标 = 锚点局部系（同组件 {@code at}）：原点站盔甲架，X 右 · Y 上 · Z 前；
     * 写了 rot 是朝自身的斜立体框，没写是朝向观察者的矩形。
     * ⚠ 组件只画示意框 + 显示名 + 类型色，真模型另走纹理请求 / NBT 写入两条链。
     */
public class EntityVisualScreen extends Screen implements EditorToolScreen {

    /** 回执路由用：交出背后的编辑器（见 {@link EditorToolScreen}）。 */
    @Override
    public GameEditorScreen editor() {
        return back.editor();
    }

    /** 右侧工具栏：宽 / 按钮边长 / 间距 / 顶部留白。 */
    private static final int TOOLBAR_W = 30, TB_BTN = 24, TB_GAP = 5, TB_TOP = 8;
    /** 工具栏五颗：四个模式 + 一颗动作「＋新建组件」。 */
    private static final String[] TOOL_GLYPH = { "选", "移", "转", "缩", "＋" };
    /** 悬浮提示 / 底栏回话用的人话。 */
    private static final String[] TOOL_TIP = { "选择", "移动（拖 X/Y/Z 轴）", "旋转（拖三个环）", "缩放", "新建组件" };
    /** 「＋」是**动作**不是模式（不占 tool 值）。 */
    private static final int TOOL_NEW = 4;
    /** 手柄：轴长 / 旋转环半径（像素）· 命中半径 · 环采样段数（画与命中共用）。 */
    private static final float GIZMO_PX = 44.0F, RING_PX = 34.0F, HIT_PX = 8.0F;
    private static final int RING_SEG = 24;

    /** 拖拽灵敏度（度/像素）。 */
    private static final float YAW_PER_PX = 0.6F, PITCH_PER_PX = 0.4F, PAN_PX = 24.0F;
    private static final float DEF_YAW = 35.0F, DEF_PITCH = 22.0F, DEF_ZOOM = 42.0F;
    /** 相机焦点高度（局部系）：盔甲架半身高。 */
    private static final float FOCUS_Y = 1.0F;
    /** 地面参考网格半径（格）。 */
    private static final int GRID = 6;
    /** 相机按「档 + 画面」记：切回来还是那个角度。 */
    private static final Map<String, float[]> CAM = new HashMap<>();

    private static final int C_BG = 0xFF161920, C_BAR = 0x90101018, C_GRID = 0xFF2A3038,
            C_AXIS_X = 0xFFB05050, C_AXIS_Y = 0xFF50A050, C_AXIS_Z = 0xFF5060B0,
            C_TEXT = 0xFF8AC8E0, C_CARD = 0xFFE0A860, C_ITEM = 0xFFA88AE0,
            C_SEL = 0xFFFFE080, C_WHITE = 0xFFFFFFFF, C_DIM = 0xFF9098A8, C_BAD = 0xFFFF8080,
            /** 盔甲架的「正面」标记（亮绿：脚下往前那条箭头）。 */
            C_FRONT = 0xFF6CE06C;

    private final StageDirScreen back;
    /** 画面资产名。 */
    private final String scr;
    /** 相机键（一份档里按画面名分开记）。 */
    private final String camKey;
    /** 这份声明（组件都在里面）；解析失败 / 找不到 = null。 */
    private Ast.Screen decl;
    /** 上次解析用的脚本文本；变了就重来。 */
    private String builtFrom = "";
    /** 底栏那一行。 */
    private String status = "";
    private boolean statusBad;
    /** 选中的组件下标（-1 = 没选）。 */
    private int sel = -1;
    /** 当前工具：0 选 · 1 移 · 2 转 · 3 缩。 */
    private int tool;
    /** 手柄拖动中；松手才写回脚本，拖动途中每帧发全档太重。 */
    private Drag drag;
    /** 这一帧鼠标压在哪根轴上（悬停高亮；-1 = 没有）。 */
    private int hoverAxis = -1;
    /** 右键菜单。 */
    private boolean menuOpen;
    private double menuX, menuY;
    private final List<String> menuLabels = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    /** 盔甲架（客户端假实体 + 它的渲染快照）；null = 还没建 / 没有世界。 */
    private net.minecraft.world.entity.decoration.ArmorStand pipStand;
    private net.minecraft.client.renderer.entity.state.EntityRenderState standRs;

    public EntityVisualScreen(StageDirScreen back, String screen) {
        super(Component.literal("实体画面"));
        this.back = back;
        this.scr = screen == null || screen.isEmpty() ? "" : screen;
        this.camKey = back.editor().def.name() + "/" + this.scr;
    }

    // ==================== 构建 ====================

    @Override
    protected void init() {
        clearWidgets();
        String src = back.editor().def.script();
        if (decl == null || !src.equals(builtFrom)) {
            try {
                decl = Parser.parse(src).screens().get(scr);
            } catch (Ast.ScriptError e) {
                decl = null;                                  // 脚本有错：页还画，改回脚本再来
            }
            builtFrom = src;
            sel = -1;                                         // 脚本换了 ⇒ 旧下标指到别的组件上了
        }
        if (decl == null || !decl.entityView()) {
            say("脚本里找不到这块实体画面（或被改成别的类别了）", true);
        }
        buildStand();
    }

    /** 建一只客户端假盔甲架 + 它的渲染快照（只建一次；没有世界就跳过）。 */
    private void buildStand() {
        if (standRs != null) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        pipStand = new net.minecraft.world.entity.decoration.ArmorStand(level, 0.0, 0.0, 0.0);
        standRs = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(pipStand)
                .createRenderState(pipStand, 0.0F);
        standRs.shadowPieces.clear();                         // GUI 里不要影子
        standRs.outlineColor = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 相机 / 投影 ====================

    private float[] cam() {
        return CAM.computeIfAbsent(camKey, k -> new float[] { DEF_YAW, DEF_PITCH, DEF_ZOOM, 0.0F, 0.0F });
    }

    /** 视口 = 整个窗口减掉右侧工具栏。 */
    private int[] viewport() {
        return new int[] { 0, 0, Math.max(16, width - TOOLBAR_W), Math.max(16, height) };
    }

    /** 相机四元数：R_pitch · R_yaw · flip，翻转最内层。 */
    private Quaternionf rot() {
        float[] c = cam();
        return new Quaternionf()
                .rotateX((float) Math.toRadians(c[1]))
                .rotateY((float) Math.toRadians(c[0]))
                .rotateX((float) Math.PI);
    }

    /** 相机平移：焦点用同一个四元数转过去取负，再叠 WASD 平移。 */
    private Vector3f trans(Quaternionf q) {
        float[] c = cam();
        return q.transform(new Vector3f(0.0F, FOCUS_Y, 0.0F), new Vector3f()).negate()
                .add(c[3], c[4], 0.0F);
    }

    /** 每格多少屏幕像素（滚轮直接调它）。 */
    private float px() {
        return cam()[2];
    }

    /** 局部系点 → 屏幕像素：面板中心 + S·(R·v + t)。 */
    private float[] toPanel(Quaternionf q, Vector3f t, float x, float y, float z) {
        int[] v = viewport();
        var p = q.transform(new Vector3f(x, y, z), new Vector3f()).add(t);
        return new float[] {
            v[0] + (v[2] - v[0]) / 2.0F + p.x * px(),
            v[1] + (v[3] - v[1]) / 2.0F + p.y * px()
        };
    }

    // ==================== 画 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xF00C0E14);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int[] v = viewport();
        g.fill(v[0], v[1], v[2], v[3], C_BG);                        // 视口底
        var q = rot();
        var t = trans(q);
        drawGrid(g, q, t);
        drawAnchor(g, q, t);
        hoverAxis = tool != 0 && sel >= 0 && decl != null && sel < decl.comps().size() && drag == null
                ? gizmoHit(mouseX, mouseY, q, t, valsOf(decl.comps().get(sel)))
                : -1;                                              // 悬停高亮（每帧现算，便宜）
        drawComps(g, q, t);

        // —— 顶部标题条 ——
        int cx = v[2] / 2;
        g.fill(0, 0, v[2], 20, C_BAR);
        String label = decl == null ? scr : (decl.label() == null || decl.label().isEmpty() ? scr : decl.label());
        g.centeredText(font, Component.literal("实体画面 " + scr + "　" + label + "　"
                + (decl == null ? "?" : decl.comps().size()) + " 个组件　（原点 = 盔甲架 = 锚点局部系）"),
                cx, 6, C_WHITE);

        // —— 底部两条：状态 / 操作提示 ——
        g.fill(0, height - 30, v[2], height, C_BAR);
        g.centeredText(font, Component.literal(sel >= 0 && decl != null
                        ? "选中：" + decl.comps().get(sel).label() + "（" + decl.comps().get(sel).asset() + "，"
                                + decl.comps().get(sel).kind() + "）　右键 = 菜单"
                        : configLine()),
                cx, height - 27, status.isEmpty() ? (sel >= 0 ? C_SEL : C_DIM) : (statusBad ? C_BAD : 0xFF8AC8E0));
        g.centeredText(font, Component.literal(status.isEmpty()
                        ? "左键拖动旋转 · 滚轮缩放 · WASD 平移 · R 回正 · Esc 返回　|　左键点组件 = 选中 · 右键 = 菜单"
                                + "　|　E/S/W/N = 世界方位（按锚点朝南画）· 绿箭头 = 盔甲架正面（+Z）"
                                + "　|　工具栏：选 / 移 / 转 / 缩 / ＋（捏手柄拖动 = 改数值，松手才存）"
                        : status),
                cx, height - 15, status.isEmpty() ? C_DIM : (statusBad ? C_BAD : 0xFF8AC8E0));

        drawToolbar(g, mouseX, mouseY);
        if (menuOpen) {
            drawMenu(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** 锚点参数那一行（这里只读，改在脚本里）。 */
    private String configLine() {
        if (decl == null || decl.anchor().isEmpty()) {
            return "锚点参数：全部缺省（follow 1 · face 0 · dz 2）";
        }
        StringBuilder b = new StringBuilder("锚点参数（期2 只看不改，改在脚本里）：");
        for (Ast.EntityParam p : decl.anchor()) {
            b.append(" ").append(p.key());
        }
        return b.toString();
    }

    /** 地面参考网格 + 三色轴 + 东南西北方位。 */
    private void drawGrid(GuiGraphicsExtractor g, Quaternionf q, Vector3f t) {
        for (int i = -GRID; i <= GRID; i++) {
            drawLine(g, toPanel(q, t, -GRID, 0, i), toPanel(q, t, GRID, 0, i), C_GRID, 1);
            drawLine(g, toPanel(q, t, i, 0, -GRID), toPanel(q, t, i, 0, GRID), C_GRID, 1);
        }
        float[] o = toPanel(q, t, 0, 0, 0);
        drawLine(g, o, toPanel(q, t, 2, 0, 0), C_AXIS_X, 2);          // X 右
        drawLine(g, o, toPanel(q, t, 0, 2, 0), C_AXIS_Y, 2);          // Y 上
        drawLine(g, o, toPanel(q, t, 0, 0, 2), C_AXIS_Z, 2);          // Z 前
        // 世界方位：MC 里 +X = 东 · −X = 西 · +Z = 南 · −Z = 北；本视口画锚点局部系，
        // 方位按「锚点朝南」标。
        dirLabel(g, q, t, GRID + 0.9F, 0, "E", C_AXIS_X);
        dirLabel(g, q, t, -GRID - 0.9F, 0, "W", C_AXIS_X);
        dirLabel(g, q, t, 0, GRID + 0.9F, "S", C_AXIS_Z);
        dirLabel(g, q, t, 0, -GRID - 0.9F, "N", C_AXIS_Z);
    }

    /** 方位字母：画在网格外圈，附一小段从网格边指出的引线。 */
    private void dirLabel(GuiGraphicsExtractor g, Quaternionf q, Vector3f t, float x, float z, String s,
            int color) {
        float k = GRID / (GRID + 1.0F);                               // 引线起点（网格边那一点）
        float[] from = toPanel(q, t, x * k, 0, z * k);
        float[] p = toPanel(q, t, x, 0, z);
        drawLine(g, from, p, color, 1);
        g.centeredText(font, Component.literal(s), (int) p[0], (int) p[1] - 3, color);
    }

    /**
     * 原点那只盔甲架（真模型，走实体渲染快照）+ 正面标记。
     *
     * <p>盔甲架朝 +Z = 南 = 前：脚下往前画一条亮线 + 箭头 + 「正面」，反方向是背面。
     */
    private void drawAnchor(GuiGraphicsExtractor g, Quaternionf q, Vector3f t) {
        int[] v = viewport();
        if (standRs == null) {
            float[] p = toPanel(q, t, 0, 0, 0);
            g.centeredText(font, Component.literal("（没有世界，盔甲架画不出来）"), (int) p[0], (int) p[1], C_DIM);
            return;
        }
        g.entity(standRs, px(), t, q, null, v[0], v[1], v[2], v[3]);
        float y = 0.03F;
        float[] o = toPanel(q, t, 0, y, 0);
        float[] f = toPanel(q, t, 0, y, 1.7F);
        drawLine(g, o, f, C_FRONT, 2);
        drawLine(g, f, toPanel(q, t, 0.3F, y, 1.2F), C_FRONT, 2);      // 箭头（两条斜边）
        drawLine(g, f, toPanel(q, t, -0.3F, y, 1.2F), C_FRONT, 2);
        g.centeredText(font, Component.literal("正面"), (int) f[0], (int) f[1] + 4, C_FRONT);
        float[] p = toPanel(q, t, 0, 2.3F, 0);
        g.centeredText(font, Component.literal("锚点（原点）"), (int) p[0], (int) p[1], C_DIM);
    }

    /** 所有组件：示意框 + 显示名 + 类型色；选中的描黄 + 出手柄。 */
    private void drawComps(GuiGraphicsExtractor g, Quaternionf q, Vector3f t) {
        if (decl == null) {
            return;
        }
        List<Ast.EntityComp> cs = decl.comps();
        for (int i = 0; i < cs.size(); i++) {
            Ast.EntityComp c = cs.get(i);
            Vals v = valsOf(c);
            int col = c.kind().equals("card") ? C_CARD : c.kind().equals("item") ? C_ITEM : C_TEXT;
            float[] p = toPanel(q, t, (float) v.x(), (float) v.y(), (float) v.z());
            if (v.hasRot()) {
                drawRotBox(g, q, t, c, v, i == sel ? C_SEL : col);    // 写了 rot = 斜着的立体框
            } else {
                drawPlate(g, p, plateW(c, v), plateH(c, v), i == sel ? C_SEL : col);
            }
            String tag = c.label().isEmpty() ? c.asset() : c.label();
            g.text(font, Component.literal(tag), (int) p[0] + 6, (int) p[1] - 4, i == sel ? C_SEL : col);
            if (i == sel) {
                drawGizmo(g, q, t, v);                                // 选中的那个才出手柄
            }
        }
    }

    /** 组件此刻显示用的数值（拖动中是**临时值**，松手才写回脚本）。 */
    private record Vals(double x, double y, double z, double rx, double ry, double rz, double scale,
            boolean hasRot) { }

    /** 一次手柄拖动：哪个组件 / 哪根轴 / 起手鼠标与起手数值。 */
    private static final class Drag {
        final String asset;
        final int axis;                                               // 0 = X · 1 = Y · 2 = Z
        final double mx0, my0;                                        // 起手鼠标
        final double cx, cy;                                          // 起手时组件中心的屏幕位置（旋转用它算角）
        final double[] start;                                         // 起手数值（移 xyz / 转 rxryrz / 缩 scale）
        Vals live;

        Drag(String asset, int axis, double mx0, double my0, double cx, double cy, double[] start, Vals v) {
            this.asset = asset;
            this.axis = axis;
            this.mx0 = mx0;
            this.my0 = my0;
            this.cx = cx;
            this.cy = cy;
            this.start = start;
            this.live = v;
        }
    }

    /** 这个组件此刻的数值：正被拖就取临时值。 */
    private Vals valsOf(Ast.EntityComp c) {
        if (drag != null && drag.asset.equals(c.asset())) {
            return drag.live;
        }
        return new Vals(c.ax(), c.ay(), c.az(), c.rx(), c.ry(), c.rz(), c.scale(), c.hasRot());
    }

    /** 手柄：移动 / 缩放 = 三根轴；旋转 = 三个环。 */
    private void drawGizmo(GuiGraphicsExtractor g, Quaternionf q, Vector3f t, Vals v) {
        if (tool == 0) {
            return;
        }
        if (tool == 2) {
            for (int ax = 0; ax < 3; ax++) {
                int col = ax == dragAxis() ? C_SEL : axisColor(ax);
                float[] prev = null;
                for (int i = 0; i <= RING_SEG; i++) {
                    double[] pt = ringPoint(ax, i * 2 * Math.PI / RING_SEG, RING_PX / px());
                    float[] sc = toPanel(q, t, (float) (v.x() + pt[0]), (float) (v.y() + pt[1]),
                            (float) (v.z() + pt[2]));
                    if (prev != null) {
                        drawLine(g, prev, sc, col, 1);
                    }
                    prev = sc;
                }
            }
            return;
        }
        for (int ax = 0; ax < 3; ax++) {
            double[] d = axisDir(ax, GIZMO_PX / px());
            float[] o = toPanel(q, t, (float) v.x(), (float) v.y(), (float) v.z());
            float[] e = toPanel(q, t, (float) (v.x() + d[0]), (float) (v.y() + d[1]), (float) (v.z() + d[2]));
            int col = ax == dragAxis() || ax == hoverAxis ? C_SEL : axisColor(ax);
            drawLine(g, o, e, col, 2);
            g.centeredText(font, Component.literal("XYZ".substring(ax, ax + 1)), (int) e[0],
                    (int) e[1] - 3, col);
        }
    }

    /** 轴向（局部系单位向量 × d）。 */
    private static double[] axisDir(int ax, double d) {
        return switch (ax) {
            case 0 -> new double[] { d, 0, 0 };
            case 1 -> new double[] { 0, d, 0 };
            default -> new double[] { 0, 0, d };
        };
    }

    /** 绕某轴的环上一点（半径 r）：绕 X 在 YZ 平面、绕 Y 在 XZ、绕 Z 在 XY。 */
    private static double[] ringPoint(int ax, double a, double r) {
        return switch (ax) {
            case 0 -> new double[] { 0, Math.sin(a) * r, Math.cos(a) * r };
            case 1 -> new double[] { Math.cos(a) * r, 0, Math.sin(a) * r };
            default -> new double[] { Math.cos(a) * r, Math.sin(a) * r, 0 };
        };
    }

    private static int axisColor(int ax) {
        return ax == 0 ? C_AXIS_X : ax == 1 ? C_AXIS_Y : C_AXIS_Z;
    }

    private int dragAxis() {
        return drag == null ? -1 : drag.axis;
    }

    /** 组件的示意尺寸（格）：文字一块板、卡牌一张牌、物品一个小方块；都按 `scale` 放大（值越小越大）。 */
    private float compK(Ast.EntityComp c, Vals v) {
        return (float) (1.0 / Math.max(0.05, v.scale()));
    }

    private float plateW(Ast.EntityComp c, Vals v) {
        return (c.kind().equals("card") ? 0.5F : c.kind().equals("item") ? 0.42F : 1.4F) * compK(c, v) * px();
    }

    private float plateH(Ast.EntityComp c, Vals v) {
        return (c.kind().equals("card") ? 0.7F : c.kind().equals("item") ? 0.42F : 0.32F) * compK(c, v) * px();
    }

    private void drawPlate(GuiGraphicsExtractor g, float[] p, float w, float h, int col) {
        int x0 = (int) (p[0] - w / 2), y0 = (int) (p[1] - h / 2);
        int x1 = (int) (p[0] + w / 2), y1 = (int) (p[1] + h / 2);
        g.outline(x0, y0, x1 - x0, y1 - y0, col);
    }

    /** 写了 rot 的组件：8 个角绕自身 XYZ 转过去，画 12 条棱（立体感靠它）。 */
    private void drawRotBox(GuiGraphicsExtractor g, Quaternionf q, Vector3f t, Ast.EntityComp c, Vals v,
            int col) {
        float k = compK(c, v);
        float hx = (c.kind().equals("card") ? 0.25F : c.kind().equals("item") ? 0.21F : 0.7F) * k;
        float hy = (c.kind().equals("card") ? 0.35F : c.kind().equals("item") ? 0.21F : 0.16F) * k;
        float hz = 0.02F;
        Quaternionf cq = new Quaternionf()
                .rotateY((float) Math.toRadians(v.ry()))
                .rotateX((float) Math.toRadians(v.rx()))
                .rotateZ((float) Math.toRadians(v.rz()));
        float[][] scr = new float[8][];
        int n = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    var d = cq.transform(new Vector3f(sx * hx, sy * hy, sz * hz), new Vector3f());
                    scr[n++] = toPanel(q, t, (float) v.x() + d.x, (float) v.y() + d.y, (float) v.z() + d.z);
                }
            }
        }
        int[][] edges = { { 0, 1 }, { 0, 2 }, { 0, 4 }, { 1, 3 }, { 1, 5 }, { 2, 3 }, { 2, 6 }, { 3, 7 },
                { 4, 5 }, { 4, 6 }, { 5, 7 }, { 6, 7 } };
        for (int[] e : edges) {
            drawLine(g, scr[e[0]], scr[e[1]], col, 1);
        }
    }

    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x0 = width - TOOLBAR_W;
        g.fill(x0, 0, width, height, 0xFF14161C);
        g.fill(x0, 0, x0 + 2, height, 0xFF5A6070);
        for (int i = 0; i < TOOL_GLYPH.length; i++) {
            int bx = x0 + (TOOLBAR_W - TB_BTN) / 2;
            int by = TB_TOP + i * (TB_BTN + TB_GAP);
            boolean act = i < TOOL_NEW && tool == i;
            boolean hover = mouseX >= bx && mouseX < bx + TB_BTN && mouseY >= by && mouseY < by + TB_BTN;
            g.fill(bx, by, bx + TB_BTN, by + TB_BTN, act ? 0xFF5A6070 : hover ? 0xFF3A4050 : 0xFF242833);
            g.outline(bx, by, TB_BTN, TB_BTN, act ? C_WHITE : 0xFF5A6070);
            g.centeredText(font, Component.literal(TOOL_GLYPH[i]), bx + TB_BTN / 2, by + (TB_BTN - 8) / 2,
                    act ? C_WHITE : 0xFFC8D0E0);
            if (hover) {
                g.text(font, Component.literal(TOOL_TIP[i]), bx - font.width(TOOL_TIP[i]) - 8, by + 8, 0xFFC8D0E0);
            }
        }
    }

    /** 工具栏命中（下标；-1 = 没点到）。 */
    private int toolbarHit(double mx, double my) {
        int x0 = width - TOOLBAR_W;
        int bx = x0 + (TOOLBAR_W - TB_BTN) / 2;
        if (mx < bx || mx >= bx + TB_BTN) {
            return -1;
        }
        for (int i = 0; i < TOOL_GLYPH.length; i++) {
            int by = TB_TOP + i * (TB_BTN + TB_GAP);
            if (my >= by && my < by + TB_BTN) {
                return i;
            }
        }
        return -1;
    }

    private void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = 132, rowH = 12, h = menuLabels.size() * rowH + 6;
        int mx = (int) menuX, my = (int) menuY;
        g.fill(mx, my, mx + w, my + h, 0xF01A1D24);
        g.outline(mx, my, w, h, 0xFF5A6070);
        for (int i = 0; i < menuLabels.size(); i++) {
            boolean hov = mouseX >= mx && mouseX < mx + w && mouseY >= my + 3 + i * rowH
                    && mouseY < my + 3 + (i + 1) * rowH;
            if (hov) {
                g.fill(mx + 1, my + 3 + i * rowH, mx + w - 1, my + 3 + (i + 1) * rowH, 0xFF2A3040);
            }
            g.text(font, Component.literal(menuLabels.get(i)), mx + 6, my + 5 + i * rowH, 0xFFE0E6F0);
        }
    }

    /**
     * 像素直线（GUI 没有画线原语）—— 光栅化全在 {@link LineRaster}（裁剪 + 沿次轴扫描 + 限流，
     * 自带自检闸门，见 {@link LineRaster#BUDGET}）。
     */
    private void drawLine(GuiGraphicsExtractor g, float[] a, float[] b, int color, int thick) {
        int[] v = viewport();
        for (int[] r : LineRaster.fills(a, b, v[0], v[1], v[2], v[3], thick, LineRaster.BUDGET)) {
            g.fill(r[0], r[1], r[2], r[3], color);
        }
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (menuOpen) {
            int row = menuRowAt(event.x(), event.y());
            menuOpen = false;
            if (row >= 0) {
                menuActions.get(row).run();
                return true;
            }
            return true;                                       // 点菜单外面 = 收菜单
        }
        int ti = toolbarHit(event.x(), event.y());
        if (ti >= 0) {
            if (ti == TOOL_NEW) {
                openNewCompMenu(event.x(), event.y());
            } else {
                tool = ti;
                say("工具：" + TOOL_TIP[ti], false);
            }
            return true;
        }
        int[] v = viewport();
        if (event.x() < v[0] || event.x() > v[2] || event.y() < v[1] || event.y() > v[3]) {
            return super.mouseClicked(event, doubled);
        }
        var q = rot();
        var t = trans(q);
        // 手柄先吃（压在手柄上就别当成点组件）
        if (tool != 0 && sel >= 0 && decl != null && sel < decl.comps().size() && event.button() == 0) {
            Ast.EntityComp cs = decl.comps().get(sel);
            Vals cv = valsOf(cs);
            int axis = gizmoHit(event.x(), event.y(), q, t, cv);
            if (axis >= 0) {
                float[] ctr = toPanel(q, t, (float) cv.x(), (float) cv.y(), (float) cv.z());
                double[] start = tool == 1 ? new double[] { cv.x(), cv.y(), cv.z() }
                        : tool == 2 ? new double[] { cv.rx(), cv.ry(), cv.rz() }
                        : new double[] { cv.scale() };
                drag = new Drag(cs.asset(), axis, event.x(), event.y(), ctr[0], ctr[1], start, cv);
                say("拖 " + "XYZ".substring(axis, axis + 1) + " 轴" + (tool == 2 ? "转" : tool == 3 ? "缩放" : ""),
                        false);
                return true;
            }
        }
        int hit = pickComp(event.x(), event.y(), q, t);
        if (event.button() == 1) {                              // 右键：点中组件 = 开菜单
            if (hit >= 0) {
                sel = hit;
                openCompMenu(event.x(), event.y(), hit);
            }
            return true;
        }
        sel = hit;                                              // 左键：选中 / 点空处取消
        say(hit < 0 ? "" : "选中 " + decl.comps().get(hit).asset(), false);
        return true;
    }

    /** 手柄命中：屏幕距离 ≤ {@link #HIT_PX} 的那根轴（旋转模式判环上采样点）；没有 = -1。 */
    private int gizmoHit(double mx, double my, Quaternionf q, Vector3f t, Vals v) {
        int best = -1;
        double bd = HIT_PX;
        if (tool == 2) {
            for (int ax = 0; ax < 3; ax++) {
                for (int i = 0; i <= RING_SEG; i++) {
                    double[] pt = ringPoint(ax, i * 2 * Math.PI / RING_SEG, RING_PX / px());
                    float[] sc = toPanel(q, t, (float) (v.x() + pt[0]), (float) (v.y() + pt[1]),
                            (float) (v.z() + pt[2]));
                    double d = Math.hypot(sc[0] - mx, sc[1] - my);
                    if (d < bd) {
                        bd = d;
                        best = ax;
                    }
                }
            }
            return best;
        }
        float[] o = toPanel(q, t, (float) v.x(), (float) v.y(), (float) v.z());
        for (int ax = 0; ax < 3; ax++) {
            double[] d = axisDir(ax, GIZMO_PX / px());
            float[] e = toPanel(q, t, (float) (v.x() + d[0]), (float) (v.y() + d[1]), (float) (v.z() + d[2]));
            double dist = segDist(mx, my, o, e);
            if (dist < bd) {
                bd = dist;
                best = ax;
            }
        }
        return best;
    }

    /** 点到线段的距离（屏幕空间）。 */
    private static double segDist(double mx, double my, float[] a, float[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        if (len2 < 1e-6) {
            return Math.hypot(mx - a[0], my - a[1]);
        }
        double u = ((mx - a[0]) * dx + (my - a[1]) * dy) / len2;
        u = Math.max(0.0, Math.min(1.0, u));
        return Math.hypot(mx - (a[0] + u * dx), my - (a[1] + u * dy));
    }

    /**
     * 拖动中改临时值（不写脚本）：移动 = 鼠标位移投影到该轴屏幕方向换算成格；
     * 缩放 = 同一位移映射成倍率（往外拖 = 更大 = 值更小）；旋转 = 绕组件中心的角变化。
     */
    private void updateDrag(double mx, double my) {
        Drag d = drag;
        if (d == null) {
            return;
        }
        Vals v = d.live;
        if (tool == 1 || tool == 3) {
            var q = rot();
            var t = trans(q);
            double[] dir = axisDir(d.axis, 1.0);
            float[] o = toPanel(q, t, (float) v.x(), (float) v.y(), (float) v.z());
            float[] e = toPanel(q, t, (float) (v.x() + dir[0]), (float) (v.y() + dir[1]), (float) (v.z() + dir[2]));
            double ux = e[0] - o[0], uy = e[1] - o[1];
            double ul2 = ux * ux + uy * uy;
            double k = ul2 < 1e-4 ? 0.0
                    : ((mx - d.mx0) * ux + (my - d.my0) * uy) / ul2;   // 屏幕像素 → 格
            if (tool == 1) {
                double x = d.start[0], y = d.start[1], z = d.start[2];
                if (d.axis == 0) x += k;
                if (d.axis == 1) y += k;
                if (d.axis == 2) z += k;
                d.live = new Vals(x, y, z, v.rx(), v.ry(), v.rz(), v.scale(), v.hasRot());
            } else {
                double sc = Math.max(0.05, Math.min(20.0, d.start[0] * (1.0 - k * 0.35)));
                d.live = new Vals(v.x(), v.y(), v.z(), v.rx(), v.ry(), v.rz(), sc, v.hasRot());
            }
            return;
        }
        double a0 = Math.atan2(d.my0 - d.cy, d.mx0 - d.cx);
        double a1 = Math.atan2(my - d.cy, mx - d.cx);
        double deg = Math.toDegrees(a1 - a0);
        while (deg > 180) deg -= 360;
        while (deg < -180) deg += 360;
        double rx = d.start[0], ry = d.start[1], rz = d.start[2];
        if (d.axis == 0) rx += deg;
        if (d.axis == 1) ry += deg;
        if (d.axis == 2) rz += deg;
        d.live = new Vals(v.x(), v.y(), v.z(), rx, ry, rz, v.scale(), true);   // 一拖就有角度 ⇒ 走「写了 rot」
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag != null) {
            commitDrag();                                       // 松手才写回脚本（一次）
            return true;
        }
        return super.mouseReleased(event);
    }

    /** 松手：临时值 → 脚本里那个组件的 `at` / `rot` / `scale` → 走 applyScript 唯一出口。 */
    private void commitDrag() {
        Drag d = drag;
        drag = null;
        if (d == null || decl == null) {
            return;
        }
        Vals v = d.live;
        String src = back.editor().def.script();
        ScriptEdit.Result r = switch (tool) {
            case 1 -> ScriptEdit.setEntityCompNums(src, scr, d.asset, "at", v.x(), v.y(), v.z());
            case 2 -> ScriptEdit.setEntityCompNums(src, scr, d.asset, "rot", v.rx(), v.ry(), v.rz());
            default -> ScriptEdit.setEntityCompNums(src, scr, d.asset, "scale", v.scale());
        };
        if (r.text().equals(src)) {
            say(r.note(), true);
            return;
        }
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        rebuildKeep(d.asset);
        say(r.note(), false);
    }

    /** 「＋新建组件」：先挑类型（三种），骨架落在**盔甲架顶部**。 */
    private void openNewCompMenu(double mx, double my) {
        menuLabels.clear();
        menuActions.clear();
        menuLabels.add("文字");
        menuActions.add(() -> newComp("text", ""));
        menuLabels.add("卡牌");
        menuActions.add(() -> newComp("card", firstCardAsset()));
        menuLabels.add("物品（先给石头，回头改 base）");
        menuActions.add(() -> newComp("item", "minecraft:stone"));
        menuX = Math.min(mx, Math.max(4, width - TOOLBAR_W - 150));
        menuY = Math.min(my, Math.max(4, height - menuLabels.size() * 12 - 6));
        menuOpen = true;
    }

    private void newComp(String kind, String base) {
        if (kind.equals("card") && (base == null || base.isEmpty())) {
            say("档里还没有卡牌：先去对象页建一张卡，再回来新建卡牌组件", true);
            return;
        }
        String src = back.editor().def.script();
        ScriptEdit.Result r = ScriptEdit.addEntityComp(src, scr, kind, base);
        if (r.text().equals(src)) {
            say(r.note(), true);
            return;
        }
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        rebuild();
        say(r.note(), false);
    }

    /** 档里第一张卡的资产名（新建卡牌组件的默认 base）；一张都没有 = 空串。 */
    private String firstCardAsset() {
        var cards = back.editor().def.cards();
        return cards == null || cards.isEmpty() ? "" : cards.get(0).id();
    }

    /** 改完脚本重建，但保住选中的那一个（按资产名找回下标）。 */
    void rebuildKeep(String asset) {
        rebuild();
        if (decl == null) {
            return;
        }
        for (int i = 0; i < decl.comps().size(); i++) {
            if (decl.comps().get(i).asset().equals(asset)) {
                sel = i;
                return;
            }
        }
    }

    /** 离得最近的组件（屏幕距离 ≤ 26px）。 */
    private int pickComp(double mx, double my, Quaternionf q, Vector3f t) {
        if (decl == null) {
            return -1;
        }
        int best = -1;
        double bd = 26.0 * 26.0;
        List<Ast.EntityComp> cs = decl.comps();
        for (int i = 0; i < cs.size(); i++) {
            Ast.EntityComp c = cs.get(i);
            Vals v = valsOf(c);
            float[] p = toPanel(q, t, (float) v.x(), (float) v.y(), (float) v.z());
            double d = (p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private int menuRowAt(double mx, double my) {
        int w = 132, rowH = 12;
        if (mx < menuX || mx >= menuX + w || my < menuY) {
            return -1;
        }
        int row = (int) ((my - menuY - 3) / rowH);
        return row >= 0 && row < menuLabels.size() ? row : -1;
    }

    private void openCompMenu(double mx, double my, int idx) {
        Ast.EntityComp c = decl.comps().get(idx);
        menuLabels.clear();
        menuActions.clear();
        menuLabels.add("编辑组件…");
        menuActions.add(() -> Minecraft.getInstance().setScreen(new EntityCompScreen(this, scr, c.asset())));
        menuLabels.add("跳到脚本第 " + c.line() + " 行");
        menuActions.add(() -> Minecraft.getInstance().setScreen(
                new ScriptEditScreen(back.editor(), c.line())));
        menuLabels.add("删除组件 " + c.asset());
        menuActions.add(() -> deleteComp(c));
        menuX = mx;
        menuY = my;
        menuOpen = true;
    }

    /** 删一个组件：走 {@link ScriptEdit} 改文本 → {@link GameEditorScreen#applyScript} 那一个出口。 */
    private void deleteComp(Ast.EntityComp c) {
        ScriptEdit.Result r = ScriptEdit.removeEntityComp(back.editor().def.script(), scr, c.asset());
        String err = back.editor().applyScript(r.text());
        if (!err.isEmpty()) {
            say(err, true);
            return;
        }
        sel = -1;
        rebuild();
        say(r.note(), false);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (drag != null) {                                     // 捏着手柄 = 改数值（不转视角）
            updateDrag(event.x(), event.y());
            return true;
        }
        if (event.button() == 0) {                              // 按住左键 = 轨道旋转
            float[] c = cam();
            c[0] += (float) (dx * YAW_PER_PX);
            c[1] = Math.max(-89.0F, Math.min(89.0F, c[1] + (float) (dy * PITCH_PER_PX)));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        float[] c = cam();
        c[2] = Math.max(6.0F, Math.min(160.0F, c[2] * (dy > 0 ? 1.15F : 1.0F / 1.15F)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (back.editor().handleHistoryKey(event.key(), event.hasControlDownWithQuirk(),
                event.hasShiftDown(), 90, 89)) {
            rebuild();
            return true;
        }
        float[] c = cam();
        float step = PAN_PX / c[2];
        switch (event.key()) {
            case 87 -> { c[4] -= step; return true; }            // W 上
            case 83 -> { c[4] += step; return true; }            // S 下
            case 65 -> { c[3] -= step; return true; }            // A 左
            case 68 -> { c[3] += step; return true; }            // D 右
            case 82 -> {                                         // R 回正
                c[0] = DEF_YAW;
                c[1] = DEF_PITCH;
                c[2] = DEF_ZOOM;
                c[3] = 0.0F;
                c[4] = 0.0F;
                return true;
            }
            case 261 -> {                                        // Delete：删掉选中的组件
                if (sel >= 0 && decl != null && sel < decl.comps().size()) {
                    deleteComp(decl.comps().get(sel));
                    return true;
                }
            }
            case 256 -> {                                        // Esc 回目录
                if (menuOpen) {
                    menuOpen = false;
                    return true;
                }
                Minecraft.getInstance().setScreen(back);
                return true;
            }
            default -> { }
        }
        return super.keyPressed(event);
    }

    /** 脚本文本变了（撤销 / 删除）⇒ 重新解析 + 清选区。 */
    private void rebuild() {
        decl = null;
        builtFrom = "";
        init();
    }

    private void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
    }
}
