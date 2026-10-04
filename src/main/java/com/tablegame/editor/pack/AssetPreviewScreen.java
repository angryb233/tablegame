package com.tablegame.editor.pack;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 模型美术的大预览屏：右键模型格 →「预览」→ 整屏居中画一个大号 3D 模型。
 * 数据零新协议：直接画 {@link AssetGrid} 已缓存的渲染快照，视角共用它的 yaw/pitch 表。
 * 交互：按住左键拖动 = 旋转，点外部 / Esc = 关。
 */
public class AssetPreviewScreen extends Screen {

    /** 拖拽灵敏度（度/像素）—— 与 Ponderer / AssetGrid 同款。 */
    private static final float YAW_PER_PX = 0.6F, PITCH_PER_PX = 0.4F;
    /** 预览图边长：屏幕短边打 7 折（大而不出屏）。 */
    private static final float SIZE_FRAC = 0.7F;

    private final Screen parent;
    /** 预览哪条（画标题 + 取缓存用）。 */
    private final String pack, id, name;
    /** AssetGrid 的共享缓存（快照 + 视角表）。 */
    private final Map<String, EntityRenderState> rsCache;
    private final Map<String, Float> rsFit;
    private final Map<String, Float> rsYaw;
    private final Map<String, Float> rsPitch;
    private final Map<String, Float> rsZoom;
    /** 模型自身的世界高度（居中用：包围盒中心 = (0, h/2, 0)）。 */
    private final Map<String, Float> rsWorldH;

    AssetPreviewScreen(Screen parent, String pack, String id, String name,
            Map<String, EntityRenderState> rsCache, Map<String, Float> rsFit,
            Map<String, Float> rsYaw, Map<String, Float> rsPitch, Map<String, Float> rsZoom,
            Map<String, Float> rsWorldH) {
        super(Component.literal("预览"));
        this.parent = parent;
        this.pack = pack;
        this.id = id;
        this.name = name;
        this.rsCache = rsCache;
        this.rsFit = rsFit;
        this.rsYaw = rsYaw;
        this.rsPitch = rsPitch;
        this.rsZoom = rsZoom;
        this.rsWorldH = rsWorldH;
    }

    private String key() {
        return pack + "/" + id;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 交互 ====================

    /** 按下落在预览区外 = 直接关（「点外部关闭」的「外部」以预览方块为准）。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (!inside(event.x(), event.y())) {
            close();
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (inside(event.x(), event.y())) {
            float yaw = rsYaw.getOrDefault(key(), AssetGrid.DEF_YAW) + (float) (dx * YAW_PER_PX);
            float pitch = rsPitch.getOrDefault(key(), AssetGrid.DEF_PITCH) + (float) (dy * PITCH_PER_PX);
            rsYaw.put(key(), yaw);
            rsPitch.put(key(), Math.max(-89.0F, Math.min(89.0F, pitch)));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                  // Esc
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    private boolean inside(double mx, double my) {
        float s = size();
        return Math.abs(mx - width / 2.0) <= s / 2 && Math.abs(my - height / 2.0) <= s / 2;
    }

    /** 滚轮缩放（只在预览区内；0.85 倍/格，钳在 0.2~8）。 */
    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (inside(x, y)) {
            float z = rsZoom.getOrDefault(key(), 1.0F);
            z = Math.max(0.2F, Math.min(8.0F, z * (dy > 0 ? 1.15F : 1.0F / 1.15F)));
            rsZoom.put(key(), z);
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    private float size() {
        return Math.min(width, height) * SIZE_FRAC;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        EntityRenderState rs = rsCache.get(key());
        float s = size();
        int x0 = (int) (width / 2.0 - s / 2), y0 = (int) (height / 2.0 - s / 2);

        g.fill(x0, y0, x0 + (int) s, y0 + (int) s, 0xFF1A1C22);                 // 预览底
        g.outline(x0, y0, (int) s, (int) s, 0xFF606878);

        g.centeredText(font, Component.literal(name + "（拖动旋转 · 滚轮缩放 · 点外部或 Esc 关闭）"),
                width / 2, Math.max(10, y0 - 14), 0xFFFFFFFF);

        if (rs == null) {                        // 快照还没到（理论上点开前已在格子里拉过）→ 提示即可
            g.centeredText(font, Component.literal("（加载中…）"), width / 2, height / 2, 0xFF909090);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }
        // 适配：等比缩放按 fit（AssetGrid.rsFit = max(水平扫掠, 高度)）取大的方向缩，模型不管扁平/瘦高都装得下；
        // g.entity 的 scale = 「屏幕像素 / 世界格数」，所以 scale = 像素边长 ÷ fit × 缩放。
        float fit = Math.max(0.0625F, rsFit.getOrDefault(key(), 0.5F));
        float yaw = rsYaw.getOrDefault(key(), AssetGrid.DEF_YAW);
        float pitch = rsPitch.getOrDefault(key(), AssetGrid.DEF_PITCH);
        // 链序 = 俯仰(X) · 偏航(Y) · 翻转(X·π)，翻转在最内层（把 Y-up 快照转成 PIP 期望的 Y-down），
        // 再套轨道视角；与「世界区域视口」同一套。
        var q = new Quaternionf()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.PI);
        // 居中 = 模型自身包围盒中心绕同一个四元数转过去取负。
        float wh = Math.max(0.0625F, rsWorldH.getOrDefault(key(), fit));
        var t = q.transform(new Vector3f(0.0F, wh / 2.0F, 0.0F), new Vector3f()).negate();
        float scale = s * 0.95F / fit * rsZoom.getOrDefault(key(), 1.0F);
        g.entity(rs, scale, t, q, null, x0, y0, x0 + (int) s, y0 + (int) s);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
