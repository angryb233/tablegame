package com.tablegame.editor.card;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import com.tablegame.host.ClientGameHandler;

    /**
     * 卡牌渲染控件：卡背（内置像素）或卡面（画板像素，按 art 键拉缓存）。
     *
     * <p>pull 模式：控件持有 art 引用，每帧渲染自查 {@link ClientGameHandler} 缓存 —— 像素包晚到也不用通知，
     * 下一帧自动显示。点击只翻 {@link #wantFace} 布尔。
     */
public class CardWidget extends AbstractWidget {
    /** true = 想看正面（像素没到时仍显示卡背，到了自动变正面）。 */
    private boolean wantFace = false;
    /** 卡面美术引用（"组/项目名"），null/空 = 这张卡没有正面美术。 */
    private String art;
    /** 卡背美术引用（"组/项目名"），null/空 = 用默认内置 blue 卡背。 */
    private String backArt;

    /** @param scale 整数放大倍数（2 = 60×84 屏幕像素） */
    public CardWidget(int x, int y, int scale) {
        super(x, y, CardBacks.CARD_W * scale, CardBacks.CARD_H * scale, Component.literal("卡牌"));
    }

    /** 绑定卡面美术引用（init 时一次）。 */
    public void setFaceArt(String art) {
        this.art = art;
    }

    /** 绑定卡背美术引用（空/null = 默认 blue）。 */
    public void setBackArt(String back) {
        this.backArt = back;
    }

    /** 点一下：翻面/翻背。 */
    public void toggleFace() {
        wantFace = !wantFace;
    }

    /** 当前是否在画卡背（想看正面但像素未到/无美术 = 回退卡背）。 */
    public boolean showingBack() {
        return !wantFace || !hasFacePixels();
    }

    private boolean hasFacePixels() {
        if (art == null || art.isBlank()) return false;
        ClientGameHandler.Face f = ClientGameHandler.face(art);
        return f != null && f.px().length > 0;
    }

    /** 自定义卡背像素已到缓存 = true（空引用/没到/找不到 = false，回落内置 blue）。 */
    private boolean hasBackPixels() {
        if (backArt == null || backArt.isBlank()) return false;
        ClientGameHandler.Face f = ClientGameHandler.face(backArt);
        return f != null && f.px().length > 0;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int scale = getWidth() / CardBacks.CARD_W;
        if (!showingBack()) {
            ClientGameHandler.Face f = ClientGameHandler.face(art);
            // 画板尺寸可能 ≠ 30×42：按卡框取样，超边裁掉（ponytail: 不做居中缩放，锁 30×42 后消失）
            drawPixels(g, f.px(), f.w(), f.h(),
                    Math.min(f.w(), CardBacks.CARD_W), Math.min(f.h(), CardBacks.CARD_H), scale);
        } else if (hasBackPixels()) {
            // 自定义卡背：与卡面同一条 pull 管线（像素包到了自动显示），超边同款裁切
            ClientGameHandler.Face f = ClientGameHandler.face(backArt);
            drawPixels(g, f.px(), f.w(), f.h(),
                    Math.min(f.w(), CardBacks.CARD_W), Math.min(f.h(), CardBacks.CARD_H), scale);
        } else {
            drawPixels(g, CardBacks.pixels("blue"), CardBacks.CARD_W, CardBacks.CARD_H,
                    CardBacks.CARD_W, CardBacks.CARD_H, scale);
        }
    }

    /** 逐像素 fill，同色 run 合并；只画 cols×rows 范围（裁切），透明跳过。 */
    private void drawPixels(GuiGraphicsExtractor g, int[] px, int srcW, int srcH, int cols, int rows, int scale) {
        for (int y = 0; y < rows; y++) {
            int base = y * srcW;
            int dstY = getY() + y * scale;
            int runColor = 0;
            int runStart = 0;
            for (int x = 0; x < cols; x++) {
                int c = px[base + x];
                if (x == 0) {
                    runColor = c;
                } else if (c != runColor) {
                    flushRun(g, runColor, runStart, x, dstY, scale);
                    runColor = c;
                    runStart = x;
                }
            }
            flushRun(g, runColor, runStart, cols, dstY, scale);
        }
    }

    /** 一段同色横排 → 一次 fill。透明（画板空格）跳过。 */
    private void flushRun(GuiGraphicsExtractor g, int color, int x0, int x1, int y, int scale) {
        if (color == 0) return;
        g.fill(getX() + x0 * scale, y, getX() + x1 * scale, y + scale, color | 0xFF000000);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}
