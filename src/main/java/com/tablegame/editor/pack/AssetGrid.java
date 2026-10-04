package com.tablegame.editor.pack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.piece.GamePieceEntity;
import com.tablegame.piece.PieceLibrary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.tablegame.TableGame;
import com.tablegame.core.ColorText;
import com.tablegame.net.AssetPackets;
import com.tablegame.piece.PieceData;

/**
 * 美术资源的缩略图网格（组件库屏 与 编辑器「组件」页 共用）。
 * <ul>
 *   <li>卡牌美术：画库里的像素（{@code .px}），按 30:42 等比放大居中
 *   <li>模型美术：画棋子实体渲染快照 + {@code g.entity(...)}（真 3D、可真放大）
 * </ul>
 * 只请求可见格的数据（滚到才拉），拉过的记下不重复发包；回执由宿主屏转发进
 * {@link #onPixels} / {@link #onBlueprintData}。
 */
public final class AssetGrid {

    /** 缩略图/预览的默认视角：偏航 225°（正对棋子的北西角）+ 俯仰 +30°（正号 = 从上往下看）。 */
    public static final float DEF_YAW = 225.0F, DEF_PITCH = 30.0F;

    /** 缩略图与大预览共用的视角四元数：链序 = 俯仰(X) · 偏航(Y) · 翻转(X·π)，翻转在最内层。 */
    private static final Quaternionf ISO = new Quaternionf()
            .rotateX((float) Math.toRadians(DEF_PITCH))
            .rotateY((float) Math.toRadians(DEF_YAW))
            .rotateX((float) Math.PI);

    private final Map<String, AssetStore.Pixels> pxCache = new HashMap<>();
    private final Map<String, EntityRenderState> rsCache = new HashMap<>();
    private final Map<String, Float> rsWorldH = new HashMap<>();
    /** 每个模型的预览视角：yaw 自由、pitch 钳制 ±89°；默认 = 原等轴视角。static = Cell 直接读。 */
    public static final Map<String, Float> rsYaw = new HashMap<>();
    public static final Map<String, Float> rsPitch = new HashMap<>();
    /** 每个模型的预览缩放（大预览滚轮改；1 = 自适应适配格子的基准）。 */
    public static final Map<String, Float> rsZoom = new HashMap<>();
    /** 每个模型的水平扫掠包围（√(sx²+sz²) 的世界尺寸）—— 转到侧面也不裁边，自适应缩放用。 */
    private final Map<String, Float> rsSwept = new HashMap<>();
    /** 适配基准 = max(水平扫掠, 高度) —— 等比缩放下两个方向都装得进（大预览/格子共用）。 */
    private final Map<String, Float> rsFit = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    /** 最近一次右键的**屏幕坐标**（宿主据此把菜单开在指针处）。 */
    private int rightX, rightY;
    /** 造 render state 用的离屏棋子实体（客户端本地，只为提取渲染状态）。 */
    private GamePieceEntity pipEntity;
    /** 造快照时的 partialTick（0 = 静态，缩略图不需要动画）。 */
    private static final float PT = 0.0F;

    /** 最近一次右键的屏幕坐标（宿主开菜单用）。 */
    public int rightX() {
        return rightX;
    }

    // ==== 缓存的只读访问器（大预览屏 AssetPreviewScreen 直接取快照与尺寸，零新协议） ====

    public Map<String, EntityRenderState> rsCache() {
        return rsCache;
    }

    public Map<String, Float> rsWorldH() {
        return rsWorldH;
    }

    public Map<String, Float> rsSwept() {
        return rsSwept;
    }

    public Map<String, Float> rsFit() {
        return rsFit;
    }

    public int rightY() {
        return rightY;
    }

    /** 换数据源（换库/换页）时清缓存。 */
    public void reset() {
        pxCache.clear();
        rsCache.clear();
        rsWorldH.clear();
        rsYaw.clear();
        rsPitch.clear();
        rsZoom.clear();
        rsFit.clear();
        rsSwept.clear();
        requested.clear();
    }

    // ==================== 回执 ====================

    public void onPixels(String pack, String id, int w, int h, byte[] argb) {
        if (w <= 0 || h <= 0 || argb == null || argb.length != w * h * 4) {
            return;                                       // 没像素/坏数据：格子里留空底
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int o = i * 4;
            px[i] = ((argb[o] & 0xFF) << 24) | ((argb[o + 1] & 0xFF) << 16)
                    | ((argb[o + 2] & 0xFF) << 8) | (argb[o + 3] & 0xFF);
        }
        pxCache.put(pack + "/" + id, new AssetStore.Pixels(w, h, px));
    }

    public void onBlueprintData(String pack, String id, String json) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ItemStack stack = PieceLibrary.pieceStackFromJson(json, level.registryAccess());
        if (stack.isEmpty()) {
            return;
        }
        var data = com.tablegame.piece.PieceData.get(stack);
        if (data.voxels() == null) {
            return;
        }
        if (pipEntity == null) {
            pipEntity = new GamePieceEntity(com.tablegame.TableGame.GAME_PIECE_ENTITY.get(), level);
            pipEntity.setPos(0, 0, 0);
        }
        pipEntity.setPieceStack(stack);
        EntityRenderer<? super GamePieceEntity, ?> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(pipEntity);
        EntityRenderState rs = renderer.createRenderState(pipEntity, PT);
        rs.shadowPieces.clear();                          // GUI 里不要影子
        rs.outlineColor = 0;                              // 不要描边（同原版背包屏那条）
        String key = pack + "/" + id;
        rsCache.put(key, rs);
        rsWorldH.put(key, data.voxels().sizeY() / (16.0F * data.voxels().scale()));
        // 水平扫掠 = 转一圈时最大的水平占地（对角线），自适应缩放按它算（Ponderer 同款公式）
        float sc = 16.0F * data.voxels().scale();
        rsSwept.put(key, (float) Math.hypot(data.voxels().sizeX(), data.voxels().sizeZ()) / sc);
        rsYaw.putIfAbsent(key, DEF_YAW);   // 初始视角（拖动过就保留）
        rsPitch.putIfAbsent(key, DEF_PITCH);
        // 适配基准 = 思索者《StructurePreviewWidget.recomputeScale》的双轴口径：取水平扫掠与竖直扫掠
        // （高·cos|俯仰| + 水平扫掠·sin|俯仰|）中更大的当分母 ⇒ 任何俯角都装得下。
        float h = data.voxels().sizeY() / sc;
        float swept = rsSwept.getOrDefault(key, h);
        float pr = (float) Math.toRadians(Math.abs(DEF_PITCH));
        float vert = (float) (h * Math.cos(pr) + swept * Math.sin(pr));
        rsFit.put(key, Math.max(swept, vert));
    }

    // ==================== 摆格 ====================

    /**
     * 把可见的条目摆成网格（每个条目一个 {@link Cell} 控件），并对缺数据的格子发一次请求。
     *
     * @param entries   条目（已按页签过滤好）
     * @param model     true = 模型美术（拉蓝图原文画棋子）；false = 卡牌美术（拉像素）
     * @param from      第一条可见条目的下标
     * @param visible   可见格数
     * @param x0,y0     网格左上角
     * @param cw,ch     格子宽高
     * @param cols      每行几格
     * @param selected  选中的 key（{@code pack/id}）
     * @param onPick    单击（选中）
     * @param onDouble  双击（组件库屏 = 命名 / 项目「组件」页 = 导入；null = 不支持）
     * @param onRight   右键（宿主在指针处开菜单：编辑 / 删除）——指针位置记进 {@link #rightX()}
     * @param add       宿主屏的控件注册回调
     */
    public void place(List<AssetPackets.Entry> entries, boolean model, int from, int visible,
            int x0, int y0, int cw, int ch, int cols, String selected,
            Consumer<AssetPackets.Entry> onPick, Consumer<AssetPackets.Entry> onDouble,
            Consumer<AssetPackets.Entry> onRight, Consumer<AbstractWidget> add) {
        for (int i = 0; i < visible && from + i < entries.size(); i++) {
            AssetPackets.Entry e = entries.get(from + i);
            String key = e.pack() + "/" + e.id();
            ItemStack ico = itemIcon(e);                   // 物品类：基底原版物品（没有像素/蓝图可拉）
            if (ico == null && requested.add(key)) {       // 只拉一次（滚到才拉）
                ClientPacketDistributor.sendToServer(model
                        ? new AssetPackets.RequestBlueprintDataPayload(e.pack(), e.id())
                        : new AssetPackets.RequestAssetPixelsPayload(e.pack(), e.id()));
            }
            int cx = x0 + (i % cols) * (cw + 8);
            int cy = y0 + (i / cols) * (ch + 11 + 8);
            AssetPackets.Entry entry = e;
            add.accept(new Cell(cx, cy, cw, ch, ColorText.mask(e.name()),
                    key, pxCache.get(key), rsCache.get(key), rsWorldH.getOrDefault(key, 0.5F), ico,
                    key.equals(selected),
                    () -> onPick.accept(entry),
                    onDouble == null ? null : () -> onDouble.accept(entry),
                    onRight == null ? null : ev -> {          // 记下指针位置：宿主据此把菜单开在指针处
                        rightX = (int) ev.x();
                        rightY = (int) ev.y();
                        onRight.accept(entry);
                    }, this));
        }
    }

    /** 物品类资产的图标 = 基底原版物品（这类没有像素/蓝图，网格不发那两条请求）；非物品类 → null。 */
    private static ItemStack itemIcon(AssetPackets.Entry e) {
        if (e.base() == null || e.base().isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(e.base());
        Item it = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        return it == null ? null : new ItemStack(it);
    }

    /** 一个缩略格：卡牌画像素、模型画棋子实体快照、物品画原版图标；名字在格子下方，选中 = 黄框 + 顶亮条。
     * 鼠标：左键单击 = 选中 · 左键双击 = 次动作（命名/导入）· 右键 = 开右键菜单；大图预览在 {@link AssetPreviewScreen}。 */
    private static class Cell extends AbstractWidget {
        private final int cw;
        private final int ch;
        private final AssetStore.Pixels px;
        private final EntityRenderState rs;
        /** 模型 key（读 rsFit 适配基准用；非模型格无用）。 */
        private final String key;
        private final float worldH;
        /** 物品类的图标（非物品类 null）。 */
        private final ItemStack ico;
        private final boolean selected;
        private final Runnable onSingle;
        private final Runnable onDouble;
        /** 右键：把**事件**交给宿主（宿主拿它的坐标把菜单开在指针处）。 */
        private final Consumer<MouseButtonEvent> onRight;
        /** 宿主 grid（读 rsFit）。 */
        private final AssetGrid grid;

        public Cell(int x, int y, int cw, int ch, String name, String key, AssetStore.Pixels px, EntityRenderState rs,
                float worldH, ItemStack ico, boolean selected, Runnable onSingle, Runnable onDouble,
                Consumer<MouseButtonEvent> onRight, AssetGrid grid) {
            super(x, y, cw, ch, Component.literal(DrawBoardMenuUi.ellipsis(name, cw / 10)));
            this.cw = cw;
            this.ch = ch;
            this.px = px;
            this.rs = rs;
            this.key = key;
            this.worldH = worldH;
            this.ico = ico;
            this.selected = selected;
            this.onSingle = onSingle;
            this.onDouble = onDouble;
            this.onRight = onRight;
            this.grid = grid;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            int x = getX(), y = getY();
            g.fill(x, y, x + cw, y + ch, 0xFF1A1C22);                            // 底
            if (rs != null) {
                // 模型：世界那套棋子渲染（真 3D）画进格子，静态等轴视角；缩放分母 = rsFit，视角 = 与视口共用的 ISO。
                float h = Math.max(0.0625F, grid.rsFit.getOrDefault(key, worldH));
                var q = new Quaternionf(ISO);
                // 居中 = 模型自身包围盒中心绕同一个四元数转过去取负（任意俯仰/偏航都居中）
                var t = q.transform(new Vector3f(0.0F, Math.max(0.0625F, worldH) / 2.0F, 0.0F),
                        new Vector3f()).negate();
                g.entity(rs, Math.min(cw, ch) * 0.85F / h, t, q, null, x, y, x + cw, y + ch);
            }
            if (px != null && px.w() > 0 && px.h() > 0) {
                int sc = Math.max(1, Math.min(cw / px.w(), ch / px.h()));        // contain：整数倍最清晰
                int ox = x + (cw - px.w() * sc) / 2;
                int oy = y + (ch - px.h() * sc) / 2;
                for (int py = 0; py < px.h(); py++) {
                    for (int pxx = 0; pxx < px.w(); pxx++) {
                        int argb = px.px()[py * px.w() + pxx];
                        if ((argb >>> 24) == 0) {
                            continue;
                        }
                        g.fill(ox + pxx * sc, oy + py * sc, ox + pxx * sc + sc, oy + py * sc + sc, argb);
                    }
                }
            }
            if (ico != null) {
                // 物品类：原版物品图标（恒 16×16，居中；与物品栏那一套渲染一致）
                g.item(ico, x + (cw - 16) / 2, y + (ch - 16) / 2);
            }
            int border = selected ? 0xFFE0C060 : (isHovered() ? 0xFF8A9A6A : 0xFF3A3E4A);
            g.outline(x, y, cw, ch, border);
            if (selected) {
                g.fill(x, y, x + cw, y + 2, 0xFFE0C060);
            }
            g.text(Minecraft.getInstance().font, getMessage().getString(),
                    x, y + ch + 2, selected ? 0xFFFFFFFF : 0xFFB0B0C0);
        }

        /** 右键也算有效点击：原版 {@code isValidClickButton} 只认左键，不覆写右键就进不到 {@link #onClick}。 */
        @Override
        protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || (buttonInfo.button() == 1 && onRight != null);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            if (event.button() == 1) {                  // 右键 = 交给宿主开菜单（编辑 / 删除）
                if (onRight != null) {
                    onRight.accept(event);
                }
            } else if (doubled && onDouble != null) {
                onDouble.run();
            } else if (onSingle != null) {
                onSingle.run();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** 「+」添加格：点它 = 去选来源。组件库屏 = 往选中的库里加；编辑器「组件」页 = 往项目自己那份里加。 */
    public static class AddCell extends AbstractWidget {
        private final Runnable onPress;
        private final int cw, ch;

        public AddCell(int x, int y, int cw, int ch, Runnable onPress) {
            super(x, y, cw, ch, Component.literal("+"));
            this.cw = cw;
            this.ch = ch;
            this.onPress = onPress;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            int x = getX(), y = getY();
            g.fill(x, y, x + cw, y + ch, isHovered() ? 0xFF23262E : 0xFF14161C);
            g.outline(x, y, cw, ch, isHovered() ? 0xFF8A9A6A : 0xFF3A3E4A);
            int cx = x + cw / 2, cy = y + ch / 2;
            int arm = Math.min(cw, ch) / 4;
            g.fill(cx - arm, cy - 1, cx + arm, cy + 1, 0xFFC8C8D0);         // 横
            g.fill(cx - 1, cy - arm, cx + 1, cy + arm, 0xFFC8C8D0);         // 竖
            g.text(Minecraft.getInstance().font, getMessage().getString(), x, y + ch + 2, 0xFF909090);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            onPress.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
