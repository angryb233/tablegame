package com.tablegame.card;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;

import com.tablegame.editor.card.CardBacks;
import com.tablegame.host.ClientGameHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

    /**
     * 卡牌实体渲染器 —— 把隐形承载体画成一张双面纸牌。
     *
     * <p>两枚四边形（正面卡面贴图、背面卡背贴图），各自朝一边（绕序相反 + CULL 版 RenderType），
     * 哪面可见由站的方位决定。贴图：卡面像素走 ClientGameHandler.requestFace/face 注成 DynamicTexture，
     * 卡背在代码里（{@link CardBacks}）；都按 key 缓存只建一次。
     *
     * <p>尺寸：高 = 0.5 格 / 尺寸（值越小牌越大），宽按贴图长宽比；牌中心 = 实体 pos。
     * 26.x 提交阶段拿不到实体实例，先在 {@link #extractRenderState} 把同步字段快照进 {@link CardRenderState}。
     */
public class CardEntityRenderer extends EntityRenderer<CardEntity, CardEntityRenderer.CardRenderState> {

    /** 贴图缓存：key（卡面资产名 / 卡背样式）→ 贴图 id + 像素尺寸。 */
    private static final Map<String, Tex> TEX = new HashMap<>();

    private record Tex(Identifier id, int w, int h) { }

    private static final float BASE_HEIGHT = 0.5f;      // 尺寸 1 = 半格高（自由数字调它）

    public CardEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public CardRenderState createRenderState() {
        return new CardRenderState();
    }

    @Override
    public void extractRenderState(CardEntity entity, CardRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.art = entity.art();
        state.back = entity.back();
        state.scale = entity.scale();
        state.yRot = entity.getYRot();          // EntityRenderState 基类没有 yRot，自己快照
        state.pitch = entity.getXRot();         // 倾角（实体画面组件的 rot x；平放时 0）
        state.roll = entity.roll();             // 自转（rot z）
    }

    @Override
    public void submit(CardRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        Tex face = faceTex(state.art);
        Tex back = backTex(state.back);
        if (face == null && back == null) {
            return;
        }
        // 高 = 半格 / 尺寸；宽按各面自己的长宽比（正反比例通常一致，不一致也不扭曲）
        float h = BASE_HEIGHT / Math.max(0.05f, state.scale);

        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f - state.yRot));  // 同原版实体朝向口径
        // 组件的 rot：先绕 Y（上面那条已含），再 X 倾角 / Z 自转（顺序与 display 的四元数同一套：Y→X→Z）
        // ponytail: 卡牌模型正面 = -Z，倾角/自转的**符号**若与文字/物品相反 ⇒ 这里各加一个负号即可（校对项）
        if (state.pitch != 0f) {
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(state.pitch));
        }
        if (state.roll != 0f) {
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(state.roll));
        }
        if (face != null) {
            quad(poseStack, collector, RenderTypes.entityCutoutCull(face.id()), state.lightCoords,
                    h * face.w() / face.h(), h, true);
        }
        if (back != null) {
            quad(poseStack, collector, RenderTypes.entityCutoutCull(back.id()), state.lightCoords,
                    h * back.w() / back.h(), h, false);
        }
        poseStack.popPose();
    }

    /**
     * 一枚居中在原点、立在 XY 平面的四边形。{@code front=true} 法线朝 +Z（正面），
     * {@code false} 朝 -Z（背面）—— 配合 CULL 版 RenderType，两面各自只在对应那侧可见。
     */
    private static void quad(PoseStack poseStack, SubmitNodeCollector collector, RenderType type,
            int light, float w, float h, boolean front) {
        float x0 = -w / 2f, x1 = w / 2f, y0 = -h / 2f, y1 = h / 2f;
        float nz = front ? 1f : -1f;
        collector.submitCustomGeometry(poseStack, type, (pose, buf) -> {
            if (front) {
                v(buf, pose, x0, y0, 0f, 0f, 1f, light, nz);
                v(buf, pose, x1, y0, 0f, 1f, 1f, light, nz);
                v(buf, pose, x1, y1, 0f, 1f, 0f, light, nz);
                v(buf, pose, x0, y1, 0f, 0f, 0f, light, nz);
            } else {
                v(buf, pose, x0, y0, 0f, 0f, 1f, light, nz);
                v(buf, pose, x0, y1, 0f, 0f, 0f, light, nz);
                v(buf, pose, x1, y1, 0f, 1f, 0f, light, nz);
                v(buf, pose, x1, y0, 0f, 1f, 1f, light, nz);
            }
        });
    }

    private static void v(com.mojang.blaze3d.vertex.VertexConsumer buf, PoseStack.Pose pose,
            float x, float y, float z, float u, float vv, int light, float nz) {
        buf.addVertex(pose, x, y, z).setColor(-1).setUv(u, vv)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, 0f, 0f, nz);
    }

    // ===== 贴图：拿到像素 → 注册成动态贴图 → 缓存 =====

    private static Tex faceTex(String art) {
        if (art == null || art.isEmpty()) return null;
        Tex cached = TEX.get("art:" + art);
        if (cached != null) return cached;
        ClientGameHandler.requestFace(art);                       // 拉一次（防抖在里面）
        ClientGameHandler.Face f = ClientGameHandler.face(art);
        if (f == null || f.w() <= 0 || f.h() <= 0 || f.px() == null) return null;   // 还没回包
        return register("art:" + art, f.px(), f.w(), f.h());
    }

    private static Tex backTex(String back) {
        String key = "back:" + (back == null || back.isEmpty() ? "blue" : back);
        Tex cached = TEX.get(key);
        if (cached != null) return cached;
        return register(key, CardBacks.pixels(back), CardBacks.CARD_W, CardBacks.CARD_H);
    }

    /** 像素（ARGB 一维数组）→ 动态贴图并注册（缓存里那份也会留）。 */
    private static Tex register(String key, int[] px, int w, int h) {
        if (px == null || px.length < w * h) return null;
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, px[y * w + x]);                // setPixel 吃 ARGB，已转内部 ABGR
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath("tablegame",
                "cardtex/" + Integer.toHexString(key.hashCode()));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> key, img));
        Tex t = new Tex(id, w, h);
        TEX.put(key, t);
        return t;
    }

    /** 渲染状态：牌面 / 背面 / 尺寸 / 朝向（字段 public 是 26.x RenderState 惯例）。 */
    public static class CardRenderState extends EntityRenderState {
        public String art = "";
        public String back = "blue";
        public float scale = 1f;
        public float yRot;
        /** 倾角（绕 X，度）—— 实体画面组件的 `rot x`。 */
        public float pitch;
        /** 自转（绕 Z，度）—— 实体画面组件的 `rot z`。 */
        public float roll;
    }
}
