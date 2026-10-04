package com.tablegame.editor.world;

import java.util.HashMap;
import java.util.Map;

import com.tablegame.TableGame;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.piece.GamePieceEntity;
import com.tablegame.piece.PieceData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.tablegame.core.GameDefinition;
import com.tablegame.core.GameStore;
import com.tablegame.editor.AssetMenu;
import com.tablegame.editor.pack.AssetGrid;
import com.tablegame.net.AreaSyncPackets;

/**
 * 「世界」页签 → 右键「编辑」→ **区域 3D 离屏视口**（浏览 + 选中 + 编辑）—— 纯 GUI 离屏 3D（不开进世界、自搭投影拾取）：
 * 把区域快照当**一枚大棋子**喂给现成的棋子渲染管线（{@code GamePieceEntityRenderer} + {@code g.entity}），相机做轨道操作。
 * 四条规矩（改这条链前先读）：
 * ① **旋转链 = R_pitch(X) · R_yaw(Y) · flip(X·π)**（翻转**最内层**）—— 与思索者 {@code StructurePreviewWidget} 逐字同构。
 *    ⚠ 顺序不是小事：翻转乘在最**外**层 = 「先把模型倾斜、再绕世界竖轴转」⇒ 物体随 yaw 一起歪斜（roll），静止看就是斜的；
 *    正统轨道相机序下，物体竖边在屏幕上恒为竖直，任意 yaw 都不滚。
 * ② **俯仰符号：正 = 从上往下看**（默认 +30）。26.x PIP 的真实约定是 **-Z 朝观察者**，故「俯视」要求模型竖轴 u_z < 0；
 *    本链下 u = (0, -cos p, -sin p) ⇒ **p > 0** 才是俯视。位姿空间里 +X 屏幕向右、+Y 向下、+Z 朝观察者。
 * ③ **translation 在旋转之后应用、单位是世界格**（相机空间）⇒ 居中 = 把「模型自身包围盒中心」用同一个四元数转过去再取负（{@code t = -R·(0, h/2, 0)}），任何角度都居中。
 * ④ **scale = 屏幕像素/世界格**：双轴 fit —— 水平扫掠 {@code √(sx²+sz²)} 与竖直扫掠 {@code sy·cos|p| + 水平·sin|p|} 各自和面板两边比，取更紧的那个。
 * 右侧工具栏的【章】应用到世界（按**区域声明的盒**盖回世界，三档覆盖策略）· 【收】更新进编辑器（按声明盒重新捕获）——
 * 只在**从「区域」那一栏进来**时出现（那时屏幕知道自己是哪条区域的视窗）。
 */
public class AreaViewScreen extends Screen {

    /** 拖拽灵敏度（度/像素）——与大预览屏同款。 */
    private static final float YAW_PER_PX = 0.6F, PITCH_PER_PX = 0.4F;
    /** WASD 每次按键平移的屏幕像素（按住时 GLFW 重复触发 = 连续移动）。 */
    private static final float PAN_PX = 24.0F;
    /** 右侧工具栏：宽 / 按钮边长 / 按钮间距 / 顶部留白。 */
    private static final int TOOLBAR_W = 30, TB_BTN = 24, TB_GAP = 5, TB_TOP = 8;
    /** 工具栏的工具（下标 = tool 字段）：选择 = 单选/Ctrl 多选；范围选择 = 两次点出长方体；分层查看 = 只看某几层。 */
    private static final String[] TOOL_NAMES = { "选择", "范围选择", "分层查看" };
    private static final String[] TOOL_GLYPH = { "选", "范", "层" };

    /** 工具的选项弹框（**右键工具按钮**开关）：宽 / 内边距 / 移动按钮宽高与间距 / 移动按钮区起始 y（**画与点共用这一份布局**）。
     *  两个工具各一份：「范围选择」多一行「忽略空气」勾选（MOV_TOP 含它），「选择」没有那行（整节上移 24px）⇒ 弹框高度不同，别用固定常量。 */
    private static final int POP_W = 112, POP_PAD = 5, MOV_BTN_W = 49, MOV_BTN_H = 18, MOV_GAP = 4,
            MOV_TOP = 40;

    /** 默认偏航 = 45°（正对区域的南东角）。俯仰取 {@link AssetGrid#DEF_PITCH}（三处 3D 视图共用同一角度）。 */
    private static final float DEF_YAW = 45.0F;
    private static final float DEF_PITCH = AssetGrid.DEF_PITCH;

    /**
     * 视角/缩放/平移按「游戏/区域」持久（关了再开接着上次看的角度）——同大预览屏那套静态表。
     * 布局：[yaw, pitch, zoom, panX(世界格), panY(世界格)]。
     */
    private static final Map<String, float[]> CAM = new HashMap<>();

    /** Esc / 返回按钮回到哪（世界页签）。 */
    private final Screen back;
    /** 改完的区域写回编辑器内存模型（单一数据源在编辑器，点左栏「保存」才落盘）。 */
    private final java.util.function.Consumer<GameDefinition.AreaDef> sink;
    private final String key, title;
    /** 游戏名（动作要把「对哪个游戏的哪条区域」发给服务端）。 */
    private final String gameName;
    /** 这条视窗是哪条**区域**的（空串 = 不是在区域栏里进来的 → 不摆那两颗动作按钮）。 */
    private final String region;
    /** 当前区域快照（编辑操作换成新 record，所以不是 final）。 */
    private GameDefinition.AreaDef area;

    /** 右键菜单（屏层状态机，复用组件库那件——不开控件）。 */
    private final AssetMenu menu = new AssetMenu();
    /**
     * 选择集：逐格布尔（按线性下标）。用数组而不是 Set：命中/邻格判断全是 O(1) 且零装箱
     * （范围选择一次可能进去上千格，边画边查邻格正是热路径）。
     */
    private boolean[] sel = new boolean[0];
    /** 选择格数（免得每帧数一遍）。 */
    private int selCount;
    /** 主格 = 最后点中的那格：「编辑」「放置相邻」作用在它身上（其余操作作用于整个选择集）。 */
    private int primary = -1;
    /** 范围框的两个角（都 >= 0 时才构成一个长方体；-1 = 还没点/没定完）。 */
    private int rangeA = -1, rangeB = -1;
    /** 『忽略空气』勾选：范围框只收实心格（默认勾上 = 一直以来的行为）。关掉则整框含空气一起收。 */
    private boolean ignoreAir = true;
    /** 弹框开在哪个工具上（-1 = 没开）；两个工具的弹框内容不同，所以记住是哪一个。 */
    private int popupTool = -1;
    /**
     * 范围框里的**实心格**下标（定完第二个角时算一次）。
     * ⚠ 范围框**不进** {@link #sel}：它画成「一整个长方体」而不是 N 个被选中的格子。
     */
    private int[] rangeCells;
    /** 改过没有（标题上提示「未保存」，关屏时顺手刷新世界页签的列表）。 */
    private boolean dirty;
    /** 当前工具（0 = 选择 · 1 = 范围选择 · 2 = 分层查看）。 */
    private int tool;
    /**
     * 分层查看（工具「层」）：-1 = 全显示；否则只看一段层 —— 由 {@link #layerOnly} 决定是
     * 「累积 0..layer」（从下往上，像盖房子）还是「只看第 layer 层」（切片）。
     *
     * <p>只是**视图**：不动 {@code area} 本体、不动选择集、不落盘（性质同「忽略空气」勾选）。
     */
    private int layer = -1;
    /** 分层查看的模式：false = 累积（默认）· true = 只看这一层。 */
    private boolean layerOnly;
    /** **分层查看过滤后的逐格表**（藏起来的层 = 空气）。{@link #buildSnapshot()} 每次重烘时重建，渲染与 {@link #pick} 的射线**共用这一张**
     *  ⇒ 藏起来的层既能透过看见、也能穿过去点里面（用没过滤的原表 = 先撞上看不见的顶盖，永远到不了里面）。
     *  {@code null} = 还没烘过（或表不合法）→ 调用方回落用 {@code area.blocks()}。 */
    private java.util.List<Integer> shown;

    private GamePieceEntity pipEntity;
    private EntityRenderState rs;
    /** 世界格单位：模型高度（= sizeY，scale 取 1/16 ⇒ 每体素 1 格）。 */
    private float worldH = 1.0F;
    private int solidCount;

    AreaViewScreen(Screen back, String gameName, GameDefinition.AreaDef area,
            java.util.function.Consumer<GameDefinition.AreaDef> sink) {
        this(back, gameName, area, "", sink);
    }

    /**
     * @param region 这条视窗属于哪条**区域**（声明里的名字）；空串 = 不是在区域栏里进来的
     *               （老路径：从「场景」那一栏看一份快照）—— 那时不摆那两颗动作按钮（没有盒可依）
     */
    AreaViewScreen(Screen back, String gameName, GameDefinition.AreaDef area, String region,
            java.util.function.Consumer<GameDefinition.AreaDef> sink) {
        super(Component.literal("区域视口"));
        this.back = back;
        this.sink = sink;
        this.area = area;
        this.gameName = gameName;
        this.region = region == null ? "" : region;
        this.key = gameName + "/" + area.id();
        this.title = gameName + " / " + area.id();
    }

    @Override
    protected void init() {
        buildSnapshot();
    }

    /** 区域快照 → 棋子栈 → 实体渲染快照（每次开屏做一次；渲染每帧只换视角）。 */
    private void buildSnapshot() {
        shown = null;                                      // 先清：中途任何一处 return 都不会留上一份过滤表
        var level = Minecraft.getInstance().level;
        if (level == null || area == null || !area.captured()) {
            return;
        }
        // 信任边界 = 磁盘上的档：逐格表长度必须与三边长对得上，否则喂给渲染器会在 expand() 越界崩。
        // 对不上就当「没数据」画提示，别把客户端拖崩。
        if (area.blocks().size() != area.sizeX() * area.sizeY() * area.sizeZ()) {
            return;
        }
        // 分层查看先过滤（**顺序不能反**：先过滤再剔除，累积切到第 k 层看到的才是**实心面**；
        // 反过来先剔除后过滤，切面上会留下“原本是内部格”的空洞）。
        // 这张表**渲染与拾取共用**（见 {@link #shown}）：藏起来的层既能透过看见，也能穿过去点里面的格子。
        shown = GameDefinition.AreaDef.onlyLayers(area.sizeX(), area.sizeY(), area.sizeZ(),
                area.blocks(), layerFrom(), Math.min(layerTo(), area.sizeY() - 1));
        // 内部实心格不进渲染（六邻皆实 → 看不见）：32³ 实心区域原样喂进去是 3 万次方块提交，
        // 剔除后只剩外壳。ponytail: 只做六邻剔除，够用；真遇到大曲面场景再上遮挡剔除。
        var ids = GameDefinition.AreaDef.visibleOnly(
                area.sizeX(), area.sizeY(), area.sizeZ(), shown);
        PieceData.VoxelData vox = new PieceData.VoxelData(
                area.sizeX(), area.sizeY(), area.sizeZ(), 1.0F / 16.0F, area.palette(), ids);
        ItemStack stack = new ItemStack(TableGame.GAME_PIECE.get());
        PieceData.set(stack, new PieceData(null, vox, null));

        pipEntity = new GamePieceEntity(TableGame.GAME_PIECE_ENTITY.get(), level);
        pipEntity.setPos(0, 0, 0);
        pipEntity.setPieceStack(stack);
        EntityRenderer<? super GamePieceEntity, ?> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(pipEntity);
        rs = renderer.createRenderState(pipEntity, 0.0F);
        rs.shadowPieces.clear();                     // GUI 里不要影子
        rs.outlineColor = 0;                         // 不要描边（同原版背包屏那条）

        if (sel.length != area.blocks().size()) {
            sel = new boolean[area.blocks().size()];   // 换了区域/尺寸变了 → 从头来
            selCount = 0;
            primary = -1;
            rangeA = -1;
        }

        worldH = area.sizeY();                       // scale = 1/16 ⇒ 每体素 1 格
        solidCount = 0;
        for (int id : ids) {
            if (id >= 0) {
                solidCount++;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 相机 ====================

    private float[] cam() {
        return CAM.computeIfAbsent(key, k -> new float[] { DEF_YAW, DEF_PITCH, 1.0F, 0.0F, 0.0F });
    }

    /**
     * 视口内容区 = **整个窗口减掉右侧工具栏**（这个窗口是全屏的）。
     * 标题与底部提示改成**浮在三维画面上的半透明条**（不再是留白框），所以这里没有四边留白。
     */
    private int[] viewport() {
        return new int[] { 0, 0, Math.max(16, width - TOOLBAR_W), Math.max(16, height) };
    }

    private boolean inside(double mx, double my) {
        int[] v = viewport();
        return mx >= v[0] && mx <= v[2] && my >= v[1] && my <= v[3];
    }

    /** 右键 = 选中方块；对着**已选中**的再右键 = 弹菜单（右键选中、对已选中的右键出选项）。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (menu.click(event.x(), event.y())) {          // 菜单开着先喂菜单（点外面 = 关菜单）
            return true;
        }
        if (popupClick(event.x(), event.y(), event.button())) {
            return true;                                  // 弹框开着先喂弹框（点在框外自动放行）
        }
        int act = actionHit(event.x(), event.y());
        if (act >= 0) {
            int buttonY = actionBtnY(actionSlot(act));
            if (act == 2) {
                // 【框】框选：关视窗回世界，拿选取棒框一片 —— 框完服务端把 box / art 一起写上
                if (back instanceof WorldTabScreen wt) wt.startCaptureByName(area.id());
            } else if (act == 3) {
                // 【编】区域属性页（资产名 / 显示名 / 进出圈规则）—— 从视窗进，回也回世界页
                if (back instanceof WorldTabScreen wt) wt.openAreaEdit(region);
            } else {
                // 【章】【收】：**菜单贴着按钮弹**（左键右键都算，免得点上去没反应）——
                // 收这一档相当于原来的「确认位」：菜单本身就是选择。
                openActionMenu(act, buttonY);
            }
            return true;
        }
        int tb = toolbarHit(event.x(), event.y());
        if (tb >= 0) {
            popupTool = event.button() == 1 && popupTool != tb ? tb : -1;   // 右键工具按钮 = 开关它的弹框
            tool = tb;                                    // 点工具按钮 = 切工具
            return true;
        }
        if (event.button() == 1 && rs != null) {
            int hit = pick(event.x(), event.y());
            boolean ctrl = (event.modifiers() & 2) != 0;   // GLFW_MOD_CONTROL
            if (tool == 1) {
                pickRangeCorner(hit);                      // 范围选择：两次右键定两角
                return true;
            }
            if (hit < 0) {
                if (!ctrl) {
                    clearSel();                            // 点空处 = 清空（Ctrl 点空处不动选择）
                }
            } else if (ctrl) {
                toggleSel(hit);                            // Ctrl = 加/减一格
            } else if (isSel(hit)) {
                primary = hit;                             // 点已选中的格子 = 弹菜单（单选多选都一样）
                openBlockMenu();
            } else {
                clearSel();
                addSel(hit);                               // 普通点击 = 单选（替换掉原选择）
            }
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    // ==================== 范围选择的选项弹框 ====================

    /** 弹框左上角（贴在工具栏左侧，与「范围选择」按钮齐头）。 */
    private int popX() {
        return width - TOOLBAR_W - POP_W - 2;
    }

    private int popY() {
        return toolBtnY(Math.max(0, popupTool));           // 与开了弹框的那个工具按钮齐头
    }

    /**
     * 移动按钮区起始 y（「范围选择」带着勾选行；「选择」没有那行，整节上移 —— 留 8 上边距 + 5 行距，
     * 别贴着弹框边框）。
     */
    private int popMovTop() {
        return popupTool == 1 ? MOV_TOP : 21;
    }

    /** 小节标题 y。 */
    private int popLabelY() {
        return popupTool == 1 ? 29 : 8;
    }

    /** 弹框总高（随工具与有没有勾选行变）。 */
    private int popH() {
        return popupTool == 2 ? LAYER_POP_H
                : popMovTop() + 3 * MOV_BTN_H + 2 * MOV_GAP + POP_PAD;
    }

    /** 第 i 个移动按钮的 [x, y, w, h]（i = X−/X+/Y−/Y+/Z−/Z+）；画与点共用这一份。 */
    private int[] moveBtnRect(int i) {
        return new int[] {
            popX() + POP_PAD + (i % 2) * (MOV_BTN_W + MOV_GAP),
            popY() + popMovTop() + (i / 2) * (MOV_BTN_H + MOV_GAP),
            MOV_BTN_W, MOV_BTN_H };
    }

    /** 弹框里「移动」能不能用（= 按钮亮还是灰）：范围选择要有框，选择工具要有选中的格。 */
    private boolean canMove() {
        return popupTool == 1 ? rangeA >= 0 : totalSel() > 0;
    }

    /**
     * 「移动选择」（选择工具弹框）：把当前选择**整体**平移一格 —— 逐格集合与范围框一起挪。
     *
     * <p>先全部试算再落地：两半必须同时挪得动才动手（否则会出现「框挪了、格子没挪」的半截状态）。
     * 越界一律不动 + 提示（区域尺寸是捕获时定死的）。
     */
    private void moveSelection(int di, int dj, int dk) {
        int[] cells = selectedCells();
        int[] mvCells = GameDefinition.AreaDef.shiftCells(area, cells, di, dj, dk);
        int[] mvBox = rangeA < 0 ? null
                : GameDefinition.AreaDef.shiftBox(area, rangeA, rangeB, di, dj, dk);
        if (mvCells == null || (rangeA >= 0 && mvBox == null)) {
            DrawBoardMenuUi.msg("[世界] 选择已经贴到区域边界了（区域尺寸在捕获时定死）");
            return;
        }
        java.util.Arrays.fill(sel, false);
        selCount = 0;
        for (int c : mvCells) {
            addSel(c);
        }
        if (rangeA >= 0) {
            rangeA = mvBox[0];
            rangeB = mvBox[1];
        }
        primary = selCount > 0 ? firstSelected() : rangeA;
        refreshRangeCells();
    }

    /** 当前逐格选择集（升序下标）。 */
    private int[] selectedCells() {
        int[] out = new int[selCount];
        int n = 0;
        for (int i = 0; i < sel.length && n < selCount; i++) {
            if (sel[i]) {
                out[n++] = i;
            }
        }
        return out;
    }

    /** 弹框吃点击（点在里面就吃掉，免得穿到下面的拾取）；返回 false = 点在框外，交给后面的分支。
     *  「忽略空气」= 切换 {@link #ignoreAir} 并重算范围框收哪些格；六个移动按钮 = 整框平移一格，**没框时置灰**。
     *  **分层查看（工具 2）**是另一套内容：层−/层+ · 模式 · 全部（整行可点）；切完层要重烘快照 —— 分层是**颜滤 + 拾取**共用的一份口径。 */
    private boolean popupClick(double mx, double my, int button) {
        if (popupTool < 0) {
            return false;
        }
        int px = popX(), py = popY();
        if (mx < px || my < py || mx >= px + POP_W || my >= py + popH()) {
            return false;
        }
        if (popupTool == 2) {                              // 分层查看：层−/层+ · 模式 · 全部
            if (button != 0) {
                return true;
            }
            if (layerHit(0, 0, mx, my)) {
                stepLayer(-1);
            } else if (layerHit(0, 1, mx, my)) {
                stepLayer(1);
            } else if (layerHit(1, -1, mx, my)) {
                layerOnly = !layerOnly;                    // 累积 0..k ⇄ 只看第 k 层
            } else if (layerHit(2, -1, mx, my)) {
                layer = -1;                                // 全部（重置回完整结构）
            } else {
                return true;                               // 框内空白：吃掉，别穿到视口
            }
            buildSnapshot();                               // 切层重烘（一次遍历，量级够用）
            return true;
        }
        if (popupTool == 1 && my < py + 24) {              // 「忽略空气」勾选行（整行可点）
            if (button == 0) {
                ignoreAir = !ignoreAir;
                refreshRangeCells();
            }
            return true;
        }
        if (button == 0 && canMove()) {
            for (int i = 0; i < 6; i++) {
                int[] r = moveBtnRect(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    int di = i == 0 ? -1 : i == 1 ? 1 : 0;
                    int dj = i == 2 ? -1 : i == 3 ? 1 : 0;
                    int dk = i == 4 ? -1 : i == 5 ? 1 : 0;
                    if (popupTool == 1) {
                        moveRange(di, dj, dk);             // 范围框：两角一起挪
                    } else {
                        moveSelection(di, dj, dk);         // 选择：逐格集合 + 范围框一起挪
                    }
                    return true;
                }
            }
        }
        return true;
    }

    /**
     * 弹框本体：勾选行（自绘方框 + 勾中填芯，不依赖字体里有没有对勾字符）+ 小节标题 + 3×2 移动按钮。
     */
    private void drawPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (popupTool < 0) {
            return;
        }
        int px = popX(), py = popY(), ph = popH();
        g.fill(px, py, px + POP_W, py + ph, 0xF01A1D24);
        g.outline(px, py, POP_W, ph, 0xFF6A7080);
        if (popupTool == 1) {
            // 「忽略空气」（只有范围选择有这一行）
            g.outline(px + POP_PAD, py + 7, 12, 12, 0xFF8A92A2);
            if (ignoreAir) {
                g.fill(px + POP_PAD + 3, py + 10, px + POP_PAD + 9, py + 16, 0xFF8AC8E0);
            }
            g.text(font, "忽略空气", px + POP_PAD + 16, py + 9, 0xFFD8DEE8);
            g.fill(px + POP_PAD, py + 24, px + POP_W - POP_PAD, py + 25, 0xFF3A4050);   // 分隔线
        }
        if (popupTool == 2) {                              // 分层查看：状态行 + 三行控件（都长在同一份 layerBtnRect 上）
            g.text(font, layerText(), px + POP_PAD, py + 9, 0xFFD8DEE8);
            g.fill(px + POP_PAD, py + 22, px + POP_W - POP_PAD, py + 23, 0xFF3A4050);   // 分隔线
            drawLayerBtn(g, mouseX, mouseY, 0, 0, "层−");
            drawLayerBtn(g, mouseX, mouseY, 0, 1, "层+");
            // 模式行写的是**当前**模式（点一下切过去）—— 同「忽略空气」那行，一眼看得出现在的状态
            drawLayerBtn(g, mouseX, mouseY, 1, -1, layerOnly ? "只看这层" : "累积（从下往上）");
            drawLayerBtn(g, mouseX, mouseY, 2, -1, "全部（重置）");
            return;
        }
        boolean en = canMove();                            // 没得挪 → 标题与按钮一起灰
        g.text(font, popupTool == 1 ? "移动选择框" : "移动选择", px + POP_PAD, py + popLabelY(),
                en ? 0xFFD8DEE8 : 0xFF686E7A);
        String[] names = { "X−", "X+", "Y−", "Y+", "Z−", "Z+" };
        for (int i = 0; i < 6; i++) {
            int[] r = moveBtnRect(i);
            boolean hover = en && mouseX >= r[0] && mouseX < r[0] + r[2]
                    && mouseY >= r[1] && mouseY < r[1] + r[3];
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
                    en ? (hover ? 0xFF3A4050 : 0xFF242833) : 0xFF1E222A);
            g.outline(r[0], r[1], r[2], r[3], en ? 0xFF5A6070 : 0xFF2A2F3A);
            g.centeredText(font, names[i], r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2,
                    en ? 0xFFC8D0E0 : 0xFF686E7A);
        }
    }

    // ==================== 分层查看（工具「层」） ====================

    /** 分层查看弹框的高：状态行 24 + 三行控件（层−/层+ · 模式 · 全部）+ 下边距。 */
    private static final int LAYER_POP_H = 24 + 3 * (MOV_BTN_H + MOV_GAP) + MOV_GAP;

    /** 看得见的最低层（累积模式从 0 起，「只看这层」= layer）。（{@code layer < 0} = 全显示） */
    private int layerFrom() {
        return layer < 0 ? 0 : (layerOnly ? layer : 0);
    }

    /** 看得见的最高层（{@code layer < 0} = 不要上限）。 */
    private int layerTo() {
        return layer < 0 ? Integer.MAX_VALUE : layer;
    }

    /**
     * 这一层现在看得见吗。**只用于渲染侧**（不画藏起来那层的高亮/范围框）。
     *
     */
    private boolean layerVisible(int y) {
        return y >= layerFrom() && y <= layerTo();
    }

    /** 层 ±1：从「全显示」按 + 进最底层、按 − 进最顶层（-1 → 0 / sizeY-1），到头夹住不越界。 */
    private void stepLayer(int d) {
        if (area == null) {
            return;
        }
        int max = Math.max(0, area.sizeY() - 1);
        layer = layer < 0 ? (d > 0 ? 0 : max) : Math.max(0, Math.min(max, layer + d));
    }

    /**
     * 分层查看弹框里第 row 行控件的 [x, y, w, h]（col &lt; 0 = 整行宽）；**画与点共用这一份**
     * （项目惯例：弹框布局只留一处，否则鼠标点到的和你看见的会慢慢对不上）。
     */
    private int[] layerBtnRect(int row, int col) {
        boolean wide = col < 0;
        return new int[] {
            popX() + POP_PAD + (wide ? 0 : col * (MOV_BTN_W + MOV_GAP)),
            popY() + 24 + row * (MOV_BTN_H + MOV_GAP),
            wide ? MOV_BTN_W * 2 + MOV_GAP : MOV_BTN_W, MOV_BTN_H };
    }

    /** 分层查看弹框：点到了第 row 行那个控件吗（与 {@link #layerBtnRect} 同一份布局）。 */
    private boolean layerHit(int row, int col, double mx, double my) {
        int[] r = layerBtnRect(row, col);
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /** 画分层查看弹框里的一个按钮（悬停提亮；与 {@link #layerHit} 共用同一份布局）。 */
    private void drawLayerBtn(GuiGraphicsExtractor g, int mouseX, int mouseY, int row, int col,
            String label) {
        int[] r = layerBtnRect(row, col);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
                layerHit(row, col, mouseX, mouseY) ? 0xFF3A4050 : 0xFF242833);
        g.outline(r[0], r[1], r[2], r[3], 0xFF5A6070);
        g.centeredText(font, label, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, 0xFFC8D0E0);
    }

    /** 弹框里的状态行文字（「第几层」给作者看的是 1 起的层号，内部 0 起）。 */
    private String layerText() {
        if (area == null) {
            return "无数据";
        }
        return layer < 0 ? ("全部 " + area.sizeY() + " 层")
                : (layerOnly ? "只看第 " : "累积到第 ") + (layer + 1) + " / " + area.sizeY() + " 层";
    }

    /**
     * 选中方块的选项：**换方块 / 删方块 / 取消选中**（切片 5）。
     *
     */
    private void openBlockMenu() {
        menu.open(width, height, lastMx, lastMy,
                java.util.List.of("编辑", "放置相邻方块…", "删除方块（选中 " + totalSel() + " 格）",
                        "标为 锚点", "标为 核心槽", "标为 产出箱", "标为 任意方块（通配）", "取消标记",
                        "取消选中"),
                java.util.List.of(this::openBlockEdit, this::placeAdjacent, this::deleteSelected,
                        () -> setMark("锚点"), () -> setMark("核心槽"), () -> setMark("产出箱"),
                        () -> setMark(GameDefinition.AreaDef.WILD), () -> setMark(""),
                        this::clearSel));
    }

    /** **语义格**：把**主格**标成某个角色（空串 = 取消标记）—— 走方块编辑同一条路（改内存副本 + sink 回写，落盘仍走左栏「保存」）。
     *  角色是文本：锚点 / 核心槽 / 产出箱是常用名，{@code 通配} = 任意方块；脚本用 {@code shape_cell("区域名", "角色", x, y, z)} 取它。同一格只留一个角色。 */
    private void setMark(String role) {
        if (primary < 0 || area == null) {
            return;
        }
        int sx = area.sizeX(), sy = area.sizeY();
        applyEdit(GameDefinition.AreaDef.withMark(area, role,
                primary % sx, (primary / sx) % sy, primary / (sx * sy)));
        DrawBoardMenuUi.msg(role.isEmpty()
                ? "[世界] 已取消这一格的标记"
                : "[世界] 已标为「" + role + "」");
    }

    // ===== 选择集 =====

    private boolean isSel(int idx) {
        return idx >= 0 && idx < sel.length && sel[idx];
    }

    private void addSel(int idx) {
        if (idx >= 0 && idx < sel.length && !sel[idx]) {
            sel[idx] = true;
            selCount++;
        }
    }

    private void toggleSel(int idx) {
        if (idx < 0 || idx >= sel.length) {
            return;
        }
        sel[idx] = !sel[idx];
        selCount += sel[idx] ? 1 : -1;
        if (!sel[idx] && primary == idx) {
            primary = firstSelected();                     // 把主格撤了 → 让剩下的第一个顶上
        } else if (sel[idx]) {
            primary = idx;
        }
    }

    private void clearSel() {
        java.util.Arrays.fill(sel, false);
        selCount = 0;
        primary = -1;
        rangeA = -1;
        rangeB = -1;
        rangeCells = null;
    }

    private int firstSelected() {
        for (int i = 0; i < sel.length; i++) {
            if (sel[i]) {
                return i;
            }
        }
        return -1;
    }

    // ===== 范围选择（点两个角 → 长方体里的实心格全选）=====

    /**
     * 范围选择：**第一次点 = 立刻框选这一格**（看得见的反馈），第二次点 = 框选两点之间那个长方体
     * （点选第一个方块就先把这个方块框选，点选另一个方块就把整个范围框选）。
     */
    private void pickRangeCorner(int hit) {
        if (hit < 0) {
            rangeA = -1;                                   // 点空处 = 撤掉整个范围框（逐格选择集留着）
            rangeB = -1;
            refreshRangeCells();
            return;
        }
        if (rangeA < 0 || rangeB >= 0) {                   // 没起范围、或上一轮已定完 → 重新起一个
            rangeA = hit;
            rangeB = -1;
            primary = hit;                                 // 主格 = 角1（「编辑」作用在这格）
            refreshRangeCells();
            return;
        }
        rangeB = hit;
        refreshRangeCells();
    }

    /** 按当前两角 + 「忽略空气」重算范围框里收哪些格（定角、移动框、改勾选后都要重算）。 */
    private void refreshRangeCells() {
        if (rangeA < 0 || area == null) {
            rangeCells = null;
            return;
        }
        int sx = area.sizeX(), sy = area.sizeY();
        int b = rangeB >= 0 ? rangeB : rangeA;
        rangeCells = GameDefinition.AreaDef.boxCells(area,
                rangeA % sx, (rangeA / sx) % sy, rangeA / (sx * sy),
                b % sx, (b / sx) % sy, b / (sx * sy), ignoreAir);
    }

    /**
     * 把范围框整体平移一格（di/dj/dk ∈ {-1, 0, 1}）：两角一起挪 ⇒ 框的大小不变。
     * 算术在 {@link GameDefinition.AreaDef#shiftBox}（越界给 null）；越界就整框不动 + 提示
     * （区域尺寸是捕获时定死的，挪出去没有意义）。
     */
    private void moveRange(int di, int dj, int dk) {
        if (rangeA < 0) {
            return;                                        // 没框（按钮本来就是灰的，这里再兜一道）
        }
        int[] moved = GameDefinition.AreaDef.shiftBox(area, rangeA, rangeB, di, dj, dk);
        if (moved == null) {
            DrawBoardMenuUi.msg("[世界] 范围框已经贴到区域边界了（区域尺寸在捕获时定死）");
            return;
        }
        rangeA = moved[0];
        rangeB = moved[1];
        refreshRangeCells();
    }

    /** 「编辑」→ 方块编辑界面（本轮：换方块做实，其余占位）。 */
    private void openBlockEdit() {
        if (primary < 0) {
            return;
        }
        String id = currentBlockId();
        Minecraft.getInstance().setScreen(new BlockEditScreen(this, coordText(primary), id,
                newId -> {
                    var st = GameStore.blockFromString.apply(newId);
                    if (st != null) {
                        applyEdit(GameDefinition.AreaDef.withBlock(area, primary, st));
                    }
                }));
    }

    /**
     * 「放置相邻方块…」= 在**点中那个面外的一格空格**里放新方块（同原版放置手感：点顶面往上叠）。
     *
     */
    private void placeAdjacent() {
        if (primary < 0 || lastFace == null) {
            DrawBoardMenuUi.msg("[世界] 先右键点一个方块（放置要贴着它的某个面）");
            return;
        }
        int sx = area.sizeX(), sy = area.sizeY(), sz = area.sizeZ();
        int i = primary % sx, j = (primary / sx) % sy, k = primary / (sx * sy);
        int axis = lastFace[0], sign = lastFace[1];
        int ti = i + (axis == 0 ? sign : 0);
        int tj = j + (axis == 1 ? sign : 0);
        int tk = k + (axis == 2 ? sign : 0);
        if (ti < 0 || tj < 0 || tk < 0 || ti >= sx || tj >= sy || tk >= sz) {
            DrawBoardMenuUi.msg("[世界] 那个方向已经出区域了（区域尺寸在捕获时定死）");
            return;
        }
        int tidx = (tk * sy + tj) * sx + ti;
        if (tidx < area.blocks().size() && area.blocks().get(tidx) >= 0) {
            DrawBoardMenuUi.msg("[世界] 那个位置已经有方块了");
            return;
        }
        Minecraft.getInstance().setScreen(new BlockPickScreen(this, id -> {
            var st = GameStore.blockFromString.apply(id);
            if (st != null) {
                applyEdit(GameDefinition.AreaDef.withBlock(area, tidx, st));
                clearSel();
                addSel(tidx);                               // 选中挪到新格：连点即可继续叠
                primary = tidx;
            }
        }));
    }

    /**
     * 「删除方块」= **选择集里每一格**换成空气（多选/范围选择选了一整块时就是整块删）。
     *
     * <p>ponytail: 逐格调 {@code withBlock}（每次都复制整张逐格表）——一次性动作、量级够用；
     * 真出现「删几千格卡一下」再上批量版（一次遍历出一张新表）。
     */
    private void deleteSelected() {
        if (totalSel() == 0) {
            return;
        }
        var next = area;
        for (int idx = 0; idx < sel.length; idx++) {
            if (sel[idx]) {
                next = GameDefinition.AreaDef.withBlock(next, idx, null);
            }
        }
        if (rangeCells != null) {
            for (int idx : rangeCells) {
                next = GameDefinition.AreaDef.withBlock(next, idx, null);
            }
        }
        applyEdit(next);
        clearSel();
    }

    /** 合计选中格数 = 逐格选择集 + 范围框里的实心格。 */
    private int totalSel() {
        return selCount + (rangeCells == null ? 0 : rangeCells.length);
    }

    /** 选中格的方块 id（给编辑界面显示）。 */
    private String currentBlockId() {
        if (primary < 0 || primary >= area.blocks().size()) {
            return "?";
        }
        int pid = area.blocks().get(primary);
        return pid >= 0 && pid < area.palette().size()
                ? GameStore.blockToString.apply(area.palette().get(pid)) : "（空气）";
    }

    /** 范围选择工具的状态文案（提示下一步点哪儿）。 */
    private String rangeText() {
        if (rangeA >= 0 && rangeB < 0) {
            return "范围选择：角1 " + coordText(rangeA) + " —— 再右键点另一个方块，框出两点之间的长方体（点空处撤销）";
        }
        if (rangeA >= 0) {
            return "范围选择：已框出 " + totalSel() + " 格（"
                    + coordText(rangeA) + " → " + coordText(rangeB) + "）—— 再点一个方块重新起范围";
        }
        return "范围选择：右键点一个方块（先框它 · 再点另一个方块框出整个长方体）";
    }

    /** 选中集状态文案（单选报坐标 + 方块 id；多选报个数）。 */
    private String selText() {
        if (totalSel() == 0) {
            return "（没选中方块 —— 右键点一个 · Ctrl+右键 = 多选）";
        }
        if (selCount + (rangeCells == null ? 0 : 1) > 1) {
            return "已选中 " + totalSel() + " 格" + (rangeCells != null ? "（含范围框）" : "")
                    + "　—— 右键点已选中的格子出菜单";
        }
        return "已选中 1 格　" + coordText(primary) + "　" + currentBlockId() + markText(primary);
    }

    /** 这一格标的是哪个角色（没标 → 空串）—— 状态条上跟着坐标显示。 */
    private String markText(int idx) {
        if (area == null || idx < 0) {
            return "";
        }
        int sx = area.sizeX(), sy = area.sizeY();
        String r = GameDefinition.AreaDef.markAt(area, idx % sx, (idx / sx) % sy, idx / (sx * sy));
        return r.isEmpty() ? "" : "　【标记：" + r + "】";
    }

    /** 选中格坐标文案 "(x, y, z)"。 */
    private String coordText(int idx) {
        int sx = area.sizeX(), sy = area.sizeY();
        return "(" + (idx % sx) + ", " + ((idx / sx) % sy) + ", " + (idx / (sx * sy)) + ")";
    }

    /** 落一次编辑：换副本 → 写回编辑器 → 重建渲染快照（调色板/逐格表都变了）。 */
    private void applyEdit(GameDefinition.AreaDef next) {
        if (next == null || next == area) {
            return;                                        // 越界等被 withBlock 原样返回：什么都不做
        }
        area = next;
        dirty = true;
        if (sink != null) {
            sink.accept(area);
        }
        buildSnapshot();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (menu.drag(event.x(), event.y())) {
            return true;                              // 菜单滚动条在拖
        }
        if (event.button() == 0) {                       // 按住左键 = 轨道旋转
            float[] c = cam();
            c[0] += (float) (dx * YAW_PER_PX);
            c[1] = Math.max(-89.0F, Math.min(89.0F, c[1] + (float) (dy * PITCH_PER_PX)));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (menu.release(event.x(), event.y())) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menu.type(event.codepointAsString())) {
            return true;                              // 菜单检索行在吃输入
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (menu.scrollBy(dy)) {
            return true;
        }
        if (inside(x, y)) {
            float[] c = cam();
            c[2] = Math.max(0.1F, Math.min(16.0F, c[2] * (dy > 0 ? 1.15F : 1.0F / 1.15F)));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    /** WASD 平移（相机空间：A/D 左右、W/S 上下）；按住会因 GLFW 重复触发而连续移动。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (menu.isOpen() && event.key() == 259 && menu.backspace()) {
            return true;                              // 菜单检索行在退格
        }
        if (menu.isOpen() && (event.key() == 257 || event.key() == 335)) {
            return true;                              // 菜单开着：回车不落到底下的控件
        }
        float[] c = cam();
        // 平移量按「屏幕像素 ÷ 每像素多少世界格」换算 ⇒ 视觉速度不随缩放变化
        float step = PAN_PX / pxPerBlock();
        switch (event.key()) {
            case 87 -> { c[4] -= step; return true; }    // W 上
            case 83 -> { c[4] += step; return true; }    // S 下
            case 65 -> { c[3] -= step; return true; }    // A 左
            case 68 -> { c[3] += step; return true; }    // D 右
            case 82 -> { c[0] = DEF_YAW; c[1] = DEF_PITCH; c[2] = 1.0F; c[3] = 0.0F; c[4] = 0.0F; return true; }  // R 回正
            case 256 -> {                                 // Esc：弹框开着先收弹框，否则返回世界页签
                if (popupTool >= 0) {
                    popupTool = -1;                        // 弹框开着先收弹框
                    return true;
                }
                close();
                return true;
            }
            default -> { }
        }
        return super.keyPressed(event);
    }

    /** 回世界页签：改过就顺手让它重建（列表要显示新状态：方块数/未捕获标记）。 */
    private void close() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(back);
        if (dirty) {
            back.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        }
    }

    /** 当前视角四元数（渲染与拾取**共用**同一份——两处各写一份必然选偏）。 */
    private Quaternionf rot() {
        float[] c = cam();
        // 链序 = R_pitch · R_yaw · flip（翻转最内层），见类头说明
        return new Quaternionf()
                .rotateX((float) Math.toRadians(c[1]))
                .rotateY((float) Math.toRadians(c[0]))
                .rotateX((float) Math.PI);
    }

    /** 相机空间平移：模型包围盒中心 (0, 高/2, 0) 绕同一四元数转过去取负，再叠加 WASD 平移。 */
    private Vector3f trans(Quaternionf q) {
        float[] c = cam();
        return q.transform(new Vector3f(0.0F, worldH / 2.0F, 0.0F), new Vector3f()).negate()
                .add(c[3], c[4], 0.0F);
    }

    /** 模型空间点 → 面板内像素（与 PIP 同一条链：像素 = 面板中心 + S·(R·v + t)）。 */
    private float[] toPanel(Quaternionf q, Vector3f t, float mx, float my, float mz) {
        int[] v = viewport();
        float sc = pxPerBlock();
        var p = q.transform(new Vector3f(mx, my, mz), new Vector3f()).add(t);
        return new float[] {
            v[0] + (v[2] - v[0]) / 2.0F + p.x * sc,
            v[1] + (v[3] - v[1]) / 2.0F + p.y * sc
        };
    }

    /** 当前每世界格多少屏幕像素（公式在 {@link GameDefinition.AreaDef#pxPerBlock}，自检有断言）。 */
    private float pxPerBlock() {
        int[] v = viewport();
        return GameDefinition.AreaDef.pxPerBlock(v[2] - v[0], v[3] - v[1],
                area.sizeX(), area.sizeY(), area.sizeZ(), cam()[1], cam()[2]);
    }

    // ==================== 拾取 / 高亮（切片 4）====================

    /** 最近一次鼠标位置（菜单要开在指针处；mouseClicked 时才知道，先记下来）。 */
    private double lastMx, lastMy;
    /** 最近一次拾取：射线从哪个面进入选中格（[轴, 外侧偏移]，见 {@code AreaDef.entryFace}）——放置要用。 */
    private int[] lastFace;

    /**
     * 屏幕点 → 体素：把像素点反算成**相机空间**的一条射线（相机空间里视线沿 ζ 轴、-Z 朝观察者），
     * 再转回模型空间取第一个实心体素。变换与渲染共用 {@link #rot()}/{@link #trans}，所以选点必然对得上。
     */
    private int pick(double mx, double my) {
        if (area == null || !area.captured()) {
            return -1;
        }
        lastMx = mx;
        lastMy = my;
        int[] v = viewport();
        float sc = pxPerBlock();
        // 相机空间里，像素 → 平面上的一点（z 分量待定 = 射线参数）
        float ux = (float) ((mx - (v[0] + (v[2] - v[0]) / 2.0)) / sc);
        float uy = (float) ((my - (v[1] + (v[3] - v[1]) / 2.0)) / sc);
        var q = rot();
        var t = trans(q);
        var inv = new Quaternionf(q).conjugate();          // 单位四元数的逆 = 共轭
        // 射线两端点：ζ = -BIG（最靠眼睛）→ +BIG；体素空间要补渲染器的居中偏移 (sx/2, 0, sz/2)
        final float big = 128.0F;
        var near = inv.transform(new Vector3f(ux - t.x, uy - t.y, -big - t.z), new Vector3f())
                .add(area.sizeX() / 2.0F, 0.0F, area.sizeZ() / 2.0F);
        var far = inv.transform(new Vector3f(ux - t.x, uy - t.y, big - t.z), new Vector3f())
                .add(area.sizeX() / 2.0F, 0.0F, area.sizeZ() / 2.0F);
        var dir = far.sub(near);                           // 方向（长度 = 2·big）
        int idx = GameDefinition.AreaDef.rayVoxel(near.x, near.y, near.z, dir.x, dir.y, dir.z,
                dir.length(), area.sizeX(), area.sizeY(), area.sizeZ(),
                shown == null ? area.blocks() : shown);   // 射线走**过滤后**的表：藏起来的层当空气
        lastFace = null;
        // 分层查看为什么不是“命中后再否决一层”：那样射线先撞上看不见的顶盖就被否掉，
        // 再也走不到里面（火柴盒分层后选不中内部方块）。喂过滤表才能穿进去。
        if (idx >= 0) {
            int sx = area.sizeX(), sy = area.sizeY();
            lastFace = GameDefinition.AreaDef.entryFace(near.x, near.y, near.z, dir.x, dir.y, dir.z,
                    idx % sx, (idx / sx) % sy, idx / (sx * sy));
        }
        return idx;
    }

    private static final int SEL_FILL = 0x55FFE060, SEL_LINE = 0xFFFFE060, SEL_THICK = 2;
    /** 范围框：**蓝色**（与逐格选择的黄色分色，一眼能分清「一个框」和「一堆格子」）。 */
    private static final int BOX_FILL = 0x5060A0FF, BOX_LINE = 0xFF6FB4FF;

    /** 选中高亮 —— 两套并存，各画各的：① **范围框 = 一整个长方体（蓝）**（只画这一框，**不**逐格描 —— 它表达的是「这个范围」，
     *  不是「N 个各自被选中的方块」）② **逐格选择集（黄，Ctrl 多选）**：逐格画，多选时跳过共享面（只留外壳）。 */
    private void drawSelection(GuiGraphicsExtractor g, Quaternionf q, Vector3f t) {
        if (rangeA >= 0) {
            int sx = area.sizeX(), sy = area.sizeY(), sz = area.sizeZ();
            int ai = rangeA % sx, aj = (rangeA / sx) % sy, ak = rangeA / (sx * sy);
            int bi = ai, bj = aj, bk = ak;
            if (rangeB >= 0) {
                bi = rangeB % sx;
                bj = (rangeB / sx) % sy;
                bk = rangeB / (sx * sy);
            }
            // 分层查看：框只画看得见的几层（整段被藏起来就整块不画）—— 免得框孤零零飘在空气里
            int j0 = Math.max(Math.min(aj, bj), layerFrom());
            int j1 = Math.min(Math.max(aj, bj), Math.min(layerTo(), sy - 1));
            if (j0 <= j1) {
                drawCuboid(g, q, t,
                        Math.min(ai, bi) - sx / 2.0F, j0, Math.min(ak, bk) - sz / 2.0F,
                        Math.abs(bi - ai) + 1, j1 - j0 + 1, Math.abs(bk - ak) + 1,
                        BOX_FILL, BOX_LINE, false, 0, 0, 0);
            }
        }
        int sx = area.sizeX(), sy = area.sizeY();
        if (selCount > 1) {
            // 多选：逐格画，**跳过与另一个选中格共享的面**（内部面画了也看不见，还费）。
            // ponytail: 逐格遍历 + 逐面查邻格是 O(选中数×6)；32³ 全选的极端情况下约 3 千个可见面/帧，
            // 真要更狠就上「只画外壳」的分块缓存——等真卡了再说。
            for (int idx = 0; idx < sel.length; idx++) {
                if (sel[idx] && layerVisible((idx / sx) % sy)) {
                    drawVoxel(g, q, t, idx, SEL_FILL, SEL_LINE, true);
                }
            }
        } else if (selCount == 1) {
            int one = firstSelected();
            if (layerVisible((one / sx) % sy)) {              // 分层查看：藏起来的层不画高亮
                drawVoxel(g, q, t, one, SEL_FILL, SEL_LINE, false);
            }
        }
    }

    /**
     * **语义格高亮**：作者标过的格子按角色分色画一遍 —— 画在选中高亮之上，一眼看得出哪格是什么。
     * 分层查看时藏起来的层不画（同选中高亮；把层全开就看得见）。
     */
    private void drawMarks(GuiGraphicsExtractor g, Quaternionf q, Vector3f t) {
        if (area == null || area.marks().isEmpty()) {
            return;
        }
        int sx = area.sizeX(), sy = area.sizeY(), sz = area.sizeZ();
        for (GameDefinition.AreaDef.CellMark m : area.marks()) {
            if (m.i() < 0 || m.j() < 0 || m.k() < 0 || m.i() >= sx || m.j() >= sy || m.k() >= sz) {
                continue;                                  // 坏档（手改的 cells 越界）：跳过，别让渲染崩
            }
            if (!layerVisible(m.j())) {
                continue;
            }
            int line = markLine(m.role());
            drawVoxel(g, q, t, (m.k() * sy + m.j()) * sx + m.i(),
                    (line & 0x00FFFFFF) | 0x50000000, line, true);
        }
    }

    /** 语义格的分色：锚点=绿 · 核心槽=青 · 产出箱=金 · 通配=白 · 认不出的角色=紫（角色是文本，脚本可自定义）。 */
    private static int markLine(String role) {
        if (GameDefinition.AreaDef.WILD.equals(role)) {
            return 0xFFE8E8E8;
        }
        if ("锚点".equals(role)) {
            return 0xFF60FF80;
        }
        if ("核心槽".equals(role)) {
            return 0xFF60E0FF;
        }
        if ("产出箱".equals(role)) {
            return 0xFFFFC040;
        }
        return 0xFFB070FF;
    }

    /** 画一格的高亮（= 边长 1 的长方体）。 */
    private void drawVoxel(GuiGraphicsExtractor g, Quaternionf q, Vector3f t, int idx,
            int fill, int line, boolean skipInternal) {
        if (idx < 0 || idx >= area.blocks().size()) {
            return;
        }
        int sx = area.sizeX(), sz = area.sizeZ();
        int i = idx % sx, j = (idx / sx) % area.sizeY(), k = idx / (sx * area.sizeY());
        drawCuboid(g, q, t, i - sx / 2.0F, j, k - sz / 2.0F, 1.0F, 1.0F, 1.0F,
                fill, line, skipInternal, i, j, k);
    }

    /** 画一个轴对齐长方体的高亮（逐格 + 范围框共用）：8 角投影后只画**面向观察者**的那几面（填充 + 描边）——
     *  面法线用同一个四元数转到相机空间，{@code n.z < 0} 即正面（26.x PIP 里 -Z 才朝观察者，见类头）；背面的棱自然不画。
     *  @param x0,y0,z0 最小角（模型空间）· dx,dy,dz 三条边的长度（世界格）
     *  @param skipInternal true = 再跳过与另一个逐格选中格共享的面（多选只留外壳）；范围框传 false
     *  @param ci,cj,ck 该长方体所属格坐标（只有 skipInternal 用得上） */
    private void drawCuboid(GuiGraphicsExtractor g, Quaternionf q, Vector3f t,
            float x0, float y0, float z0, float dx, float dy, float dz,
            int fill, int line, boolean skipInternal, int ci, int cj, int ck) {
        float[][] scr = new float[8][];
        for (int c = 0; c < 8; c++) {
            scr[c] = toPanel(q, t, x0 + ((c & 1) == 0 ? 0.0F : dx),
                    y0 + ((c & 2) == 0 ? 0.0F : dy), z0 + ((c & 4) == 0 ? 0.0F : dz));
        }
        float[][] quad = new float[4][];
        for (int[] f : GameDefinition.AreaDef.CUBE_FACES) {
            var n = q.transform(new Vector3f(f[4], f[5], f[6]), new Vector3f());
            if (n.z >= 0.0F) {
                continue;                                  // 背对观察者（+Z 向里）
            }
            if (skipInternal && neighbourSelected(ci, cj, ck, f[4], f[5], f[6])) {
                continue;
            }
            for (int c = 0; c < 4; c++) {
                quad[c] = scr[f[c]];
            }
            fillQuad(g, quad, fill);
            for (int c = 0; c < 4; c++) {
                drawLine(g, quad[c], quad[(c + 1) % 4], line, SEL_THICK);
            }
        }
    }

    /** 该面外侧的邻格也在选中集里吗（多选时用来剔内部面）。 */
    private boolean neighbourSelected(int i, int j, int k, int nx, int ny, int nz) {
        int sx = area.sizeX(), sy = area.sizeY(), sz = area.sizeZ();
        int ni = i + nx, nj = j + ny, nk = k + nz;
        if (ni < 0 || nj < 0 || nk < 0 || ni >= sx || nj >= sy || nk >= sz) {
            return false;
        }
        return isSel((nk * sy + nj) * sx + ni);
    }


    /** 凸四边形扫描线填充（GUI 没有多边形原语，按行求与各边的交点取跨度）。 */
    private static void fillQuad(GuiGraphicsExtractor g, float[][] p, int color) {
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] v : p) {
            minY = Math.min(minY, v[1]);
            maxY = Math.max(maxY, v[1]);
        }
        for (int y = (int) Math.ceil(minY); y <= (int) maxY; y++) {
            float yc = y + 0.5F;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float[] a = p[i], b = p[(i + 1) % 4];
                if ((a[1] <= yc) == (b[1] <= yc)) {
                    continue;                              // 这条边不跨这一行
                }
                float x = a[0] + (yc - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            if (lo <= hi) {
                g.fill((int) lo, y, (int) Math.ceil(hi), y + 1, color);
            }
        }
    }

    /** 像素直线（GUI 没有画线原语，沿长轴步进逐格 fill）。 */
    private static void drawLine(GuiGraphicsExtractor g, float[] a, float[] b, int color, int thick) {
        int steps = (int) Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
        for (int i = 0; i <= steps; i++) {
            float f = i / (float) steps;
            int x = (int) (a[0] + (b[0] - a[0]) * f);
            int y = (int) (a[1] + (b[1] - a[1]) * f);
            g.fill(x, y, x + thick, y + thick, color);
        }
    }

    // ==================== 渲染 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xF00C0E14);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int[] v = viewport();
        // 三维**铺满**整块视口（全屏，不再有内框）；文字改成浮在画面上的半透明条，
        // 所以顺序必须是：先三维 → 再高亮 → 最后才写字（反了字会被三维盖住）。
        g.fill(v[0], v[1], v[2], v[3], 0xFF161920);                  // 视口底
        var q = rot();
        var t = trans(q);
        boolean noData = rs == null;
        if (!noData) {
            g.entity(rs, pxPerBlock(), t, q, null, v[0], v[1], v[2], v[3]);
            drawSelection(g, q, t);
            drawMarks(g, q, t);                            // 语义格（期4）画在选中高亮之上
        }

        // —— 顶部标题条 ——
        int cx = v[2] / 2;
        g.fill(0, 0, v[2], 20, 0x90101018);
        g.centeredText(font, Component.literal("区域 " + title + "　" + area.sizeX() + "×"
                + area.sizeY() + "×" + area.sizeZ() + "　方块 " + solidCount
                + (dirty ? "　⚠ 已改动（回世界页签点「保存」生效）" : "")),
                cx, 6, dirty ? 0xFFFFC060 : 0xFFFFFFFF);

        // —— 底部两条：选中状态 / 操作提示 ——
        g.fill(0, height - 30, v[2], height, 0x90101018);
        boolean boxTool = tool == 1;
        g.centeredText(font, Component.literal(boxTool ? rangeText() : selText()),
                cx, height - 27, boxTool && rangeA < 0 ? 0xFF8AC8E0
                        : totalSel() > 0 ? 0xFFFFE080 : 0xFF9098A8);
        g.centeredText(font, Component.literal(boxTool
                        ? "左键拖动旋转 · 滚轮缩放 · WASD 平移 · R 回正 · Esc 返回　|　右键点两角 = 选一个长方体（点空处撤销角1）· 右键工具栏「范」= 选项"
                        : tool == 2
                        ? "左键拖动旋转 · 滚轮缩放 · WASD 平移 · R 回正 · Esc 返回　|　分层查看：右键工具栏「层」= 层− / 层+ / 只看这层 / 全部（藏起来的层当空气：能透视，也能点进去选内部）"
                        : "左键拖动旋转 · 滚轮缩放 · WASD 平移 · R 回正 · Esc 返回　|　右键 = 选中 · Ctrl+右键 = 多选 · 点已选中的出菜单 · 右键工具栏「选」= 选项"),
                cx, height - 14, 0xFF8A92A2);

        if (noData) {
            g.centeredText(font, Component.literal("（这个区域还没捕获过 —— 先右键「选择区域」）"),
                    cx, height / 2, 0xFFFF8080);
        }
        drawToolbar(g, mouseX, mouseY);
        drawPopup(g, mouseX, mouseY);                     // 弹框浮在工具栏旁，压在最上层（菜单再压它）
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        menu.draw(g, font, mouseX, mouseY);               // 菜单画在最上层
    }

    // ==================== 右侧工具栏 ====================

    /** 第 i 个工具按钮的 x/y（按钮列自上而下）。 */
    private int toolBtnY(int i) {
        return TB_TOP + i * (TB_BTN + TB_GAP);
    }

    /** 命中哪个工具按钮（-1 = 没点中工具栏）。 */
    private int toolbarHit(double mx, double my) {
        if (mx < width - TOOLBAR_W + 2) {
            return -1;
        }
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            int by = toolBtnY(i);
            if (my >= by && my < by + TB_BTN) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 右侧工具栏：一列工具按钮（样式与画板那套一致——选中=亮底+白框、悬停=中亮底；图标用**首字**，
     * GUI 里不能贴图）+ 一条与视口的分隔亮线（多栏界面必须看得见边界，别只靠底色差）。
     */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x0 = width - TOOLBAR_W;
        g.fill(x0, 0, width, height, 0xFF14161C);
        g.fill(x0, 0, x0 + 2, height, 0xFF5A6070);                    // 分隔线
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            int bx = x0 + (TOOLBAR_W - TB_BTN) / 2;
            int by = toolBtnY(i);
            boolean act = tool == i;
            boolean hover = mouseX >= bx && mouseX < bx + TB_BTN && mouseY >= by && mouseY < by + TB_BTN;
            g.fill(bx, by, bx + TB_BTN, by + TB_BTN, act ? 0xFF5A6070 : hover ? 0xFF3A4050 : 0xFF242833);
            g.outline(bx, by, TB_BTN, TB_BTN, act ? 0xFFFFFFFF : 0xFF5A6070);
            g.centeredText(font, Component.literal(TOOL_GLYPH[i]), bx + TB_BTN / 2, by + (TB_BTN - 8) / 2,
                    act ? 0xFFFFFFFF : 0xFFC8D0E0);
        }
        // 两颗动作 + 【框】【编】（只在**区域**视窗里出现）—— 摆在工具下面一组，样式与工具一致
        if (!region.isEmpty()) {
            for (int i = 0; i < ACTION_GLYPH.length; i++) {
                if (!actionShown(i)) continue;
                int bx = x0 + (TOOLBAR_W - TB_BTN) / 2;
                int by = actionBtnY(actionSlot(i));
                boolean hover = mouseX >= bx && mouseX < bx + TB_BTN && mouseY >= by && mouseY < by + TB_BTN;
                g.fill(bx, by, bx + TB_BTN, by + TB_BTN, hover ? 0xFF3A4050 : 0xFF242833);
                g.outline(bx, by, TB_BTN, TB_BTN, 0xFF5A6070);
                g.centeredText(font, Component.literal(ACTION_GLYPH[i]), bx + TB_BTN / 2, by + (TB_BTN - 8) / 2,
                        0xFFC8D0E0);
            }
        }
    }

    /**
     * 动作按钮（只在**区域**视窗里摆）：0 = 应用到世界 · 1 = 捕获进编辑器 · 2 = 框选 · 3 = 编辑区域属性。
     *
     */
    private static final String[] ACTION_GLYPH = {"章", "收", "框", "编"};

    /**
     * 第 {@code i} 颗动作按钮摆不摆。
     *
     * <p>⚠ 【框】只在「区域名 == 这份内容的 id」时摆：框选确认是按**内容的 id**写声明的
     * （生成本条 {@code area} 的 box / art）—— 名字对不上时摆出来会去改别的声明，宁可不摆。
     */
    private boolean actionShown(int i) {
        if (region.isEmpty()) return false;
        if (i == 2) return region.equals(area.id());
        return true;
    }

    /** 第 {@code i} 颗动作按钮画在第几个槽位（跳过不摆的那些 —— 索引才不会被挤位）。 */
    private int actionSlot(int i) {
        int slot = 0;
        for (int k = 0; k < i; k++) if (actionShown(k)) slot++;
        return slot;
    }

    /** 动作按钮的 y（紧跟工具那一列，空一格）。 */
    private int actionBtnY(int slot) {
        return toolBtnY(TOOL_NAMES.length) + TB_GAP + slot * (TB_BTN + TB_GAP);
    }

    /** 命中哪颗动作按钮（-1 = 没点中；没有区域时永远 -1）。 */
    private int actionHit(double mx, double my) {
        if (region.isEmpty() || mx < width - TOOLBAR_W + 2) return -1;
        for (int i = 0; i < ACTION_GLYPH.length; i++) {
            if (!actionShown(i)) continue;
            int by = actionBtnY(actionSlot(i));
            if (my >= by && my < by + TB_BTN) return i;
        }
        return -1;
    }

    /** 动作按钮的菜单：**贴着按钮左边弹**，不跟鼠标跑。【章】【收】两颗的语义都是「选一种模式」，菜单里各给几档：
     *  章 = 应用到世界的三档覆盖策略 · 收 = 按声明盒重新捕获 / 按框选范围捕获。 */
    private void openActionMenu(int act, int buttonY) {
        int bx = Math.max(2, width - TOOLBAR_W + (TOOLBAR_W - TB_BTN) / 2 - 138);   // AssetMenu.W = 132 + 让开 6
        if (act == 1) {
            menu.open(width, height, bx, buttonY,
                    java.util.List.of("按声明盒重新捕获", "按框选范围捕获"),
                    java.util.List.of(
                            () -> sendSync("capture", ""),
                            () -> { if (back instanceof WorldTabScreen wt) wt.startCaptureByName(area.id()); }));
            return;
        }
        menu.open(width, height, bx, buttonY,
                java.util.List.of("不动已有方块", "全覆盖·空气也清场", "空气不覆盖",
                        "放置棒：复制（原处留着）", "放置棒：移动（原处清空）"),
                java.util.List.of(
                        () -> sendSync("place", "none"),
                        () -> sendSync("place", "all"),
                        () -> sendSync("place", "non_air"),
                        // 放置棒：**不直接盖章**，改成回世界拿工具手动放（幽灵预览 + 右键挪 + Shift+Enter）
                        // —— 复制就是全覆盖落地（原处留着），移动 = 落完再把源盒清空（服务端那份串行任务）。
                        () -> { if (back instanceof WorldTabScreen wt) wt.startPlaceByName(area.id(), "all"); },
                        () -> { if (back instanceof WorldTabScreen wt) wt.startPlaceByName(area.id(), "move"); }));
    }

    /**
     * 【收】更新进编辑器 / 【章】应用到世界：把「对哪个游戏的哪条区域做什么」发给服务端。
     * 盒在世界里长什么样由**服务端按声明解析**（客户端只说名字）—— 客户端不重复算一遍。
     */
    private void sendSync(String op, String mode) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new AreaSyncPackets.SyncPayload(gameName, region, op, mode));
        if (op.equals("capture")) {
            // 收完这份快照在服务端就变了：本屏手上那份已经过期 —— 回世界页看新的（不拿旧数据继续画）
            net.minecraft.client.Minecraft.getInstance().setScreen(back);
        }
    }
}
