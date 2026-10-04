package com.tablegame.editor.stage;

import com.tablegame.drawboard.DrawBoardMenuUi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.tablegame.core.GameDefinition;

/**
 * 「编」打开的舞台单独编辑页：一块舞台的名字与背景同屏改。
 *
 * <p>口径：
 * <ul>
 *   <li>资产名 = 脚本引用用的名字（{@code show("名")}）：改了 = 全链改名，填空/没变 = 不动；</li>
 *   <li>显示名：留空 = 去掉显示名；</li>
 *   <li>背景色 {@code #RRGGBB} + 右边一格透明度（0~255，合成 {@code #RRGGBBAA}）：颜色留空 =
 *       去掉背景，透明度留空 = 缺省 40% 黑；</li>
 *   <li>名字不合法由写回那一步拒收，本页只把字符串交回去。</li>
 * </ul>
 */
public class StageEditScreen extends Screen {

    /** 三字符串回调（Java 没有三参 Consumer，就近声明，只此一处用）。 */
    @FunctionalInterface
    public interface Fields {
        void accept(String asset, String label, String bg);
    }

    private final Screen parent;
    private final String asset;
    private final String label;
    private final String bg;
    private final Fields onOk;
    private EditBox assetBox, labelBox, bgBox, alphaBox;
    /** 本页把关提示（透明度填错时不关页，就地报）。 */
    private String err = "";

    /**
     * @param parent 返回 / 保存后回哪一屏（预览屏）
     * @param asset  现在的资产名（预填）
     * @param label  现在的显示名（预填，可能空）
     * @param bg     现在的背景串（{@code #RRGGBB} 或 {@code #RRGGBBAA}；空 = 没写）
     * @param onOk   保存回调：交回（资产名, 显示名, 背景串）三个**已 strip** 的字符串；写回由调用方做
     */
    public StageEditScreen(Screen parent, String asset, String label, String bg, Fields onOk) {
        super(Component.literal("编辑舞台"));
        this.parent = parent;
        this.asset = asset == null ? "" : asset;
        this.label = label == null ? "" : label;
        this.bg = bg == null ? "" : bg;
        this.onOk = onOk;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        assetBox = new EditBox(font, cx - 110, height / 2 - 26, 220, 20, Component.literal("资产名"));
        assetBox.setMaxLength(32);
        assetBox.setValue(asset);
        addRenderableWidget(assetBox);
        labelBox = new EditBox(font, cx - 110, height / 2 + 14, 220, 20, Component.literal("显示名"));
        labelBox.setMaxLength(32);
        labelBox.setValue(label);
        labelBox.setHint(Component.literal("留空 = 不要显示名"));
        addRenderableWidget(labelBox);
        // 背景：颜色一格 + 透明度一格（合成 #RRGGBBAA）。没写过背景时透明度填 64（缺省 40% 黑）。
        bgBox = new EditBox(font, cx - 110, height / 2 + 54, 150, 20, Component.literal("背景色"));
        bgBox.setMaxLength(16);
        bgBox.setValue(bg.isEmpty() ? "" : GameDefinition.BoxStyle.withAlpha(bg, 255));
        bgBox.setHint(Component.literal("#RRGGBB · 留空 = 缺省 40% 黑"));
        addRenderableWidget(bgBox);
        alphaBox = new EditBox(font, cx + 48, height / 2 + 54, 62, 20, Component.literal("透"));
        alphaBox.setMaxLength(3);
        alphaBox.setValue(String.valueOf(bg.isEmpty() ? 0x40 : GameDefinition.BoxStyle.alphaOf(bg)));
        alphaBox.setHint(Component.literal("0~255"));
        addRenderableWidget(alphaBox);
        addRenderableWidget(DrawBoardMenuUi.button(cx - 110, height / 2 + 86, 104, 20, "保存", this::save));
        addRenderableWidget(DrawBoardMenuUi.button(cx + 6, height / 2 + 86, 104, 20, "返回", this::back));
        setFocused(assetBox);
    }

    /** 交回三样（写回在调用方，本页不碰脚本）。 */
    private void save() {
        String a = assetBox == null ? "" : assetBox.getValue().strip();
        String l = labelBox == null ? "" : labelBox.getValue().strip();
        String c = bgBox == null ? "" : bgBox.getValue().strip();
        String out = "";
        if (!c.isEmpty()) {
            int alpha;
            try {
                alpha = Integer.parseInt(alphaBox.getValue().strip());
            } catch (NumberFormatException e) {
                err = "「透」要填 0~255 的整数";
                return;                                      // 不关页：就地报，改完再按保存
            }
            if (alpha < 0 || alpha > 255) {
                err = "「透」只能填 0~255";
                return;
            }
            out = GameDefinition.BoxStyle.withAlpha(c, alpha);   // 合成 #RRGGBBAA（255 就退回 6 位写法）
        }
        back();
        onOk.accept(a, l, out);
    }

    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xC0101020);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int lx = width / 2 - 110;
        g.centeredText(font, Component.literal("编辑舞台 · 名字与背景"), width / 2, height / 2 - 56, 0xFFFFFFFF);
        g.text(font, "资产名＝脚本里用它（show(\"名\")）· 改了会连 show 一起改", lx, height / 2 - 38, 0xFF909090);
        g.text(font, "显示名＝只给人看（目录 / 标题显示它）· 留空 = 去掉", lx, height / 2 + 2, 0xFF909090);
        g.text(font, "背景＝舞台铺底那一层（全屏整块 / 看板板底）· 颜色留空 = 回缺省 40% 黑", lx, height / 2 + 42, 0xFF909090);
        if (err.isEmpty()) {
            g.centeredText(font, Component.literal("Enter 保存 · Esc 返回"), width / 2, height / 2 + 112, 0xFF909090);
        } else {
            g.centeredText(font, Component.literal(err), width / 2, height / 2 + 112, 0xFFFF9090);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257) {                    // Enter = 保存
            save();
            return true;
        }
        if (event.key() == 256) {                    // Esc = 返回
            back();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
