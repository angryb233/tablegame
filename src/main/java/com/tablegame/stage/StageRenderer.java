package com.tablegame.stage;

import com.tablegame.drawboard.BoardCanvas;
import com.tablegame.drawboard.DrawBoardMenuUi;
import com.tablegame.drawboard.StageBoardCache;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.card.CardBacks;
import com.tablegame.host.ClientGameHandler;

/**
 * 舞台渲染器 —— 把 {@link GameDefinition.StageUi} 画到屏幕一块矩形里的唯一实现。
 * HUD（{@link StageHudLayer}）与全屏（{@link StageScreen}）共用它，省得折行 / 画板自适应 / 颜色解析两处分叉。
 * ox/oy = 舞台左上角屏幕位置，scale = 逻辑画布像素 → 屏幕像素的缩放比。
 * 渲染全走 {@code fill} —— 26.x：GUI 里不 blit 自定义纹理；字体不随缩放变。
 */
public final class StageRenderer {
    private StageRenderer() {}

    /** 舞台整幅画进屏幕矩形（左上角 ox,oy + 缩放比）。铺底/裁剪由调用方负责（HUD 与全屏的底不同）。 */
    public static void draw(GuiGraphicsExtractor g, GameDefinition.StageUi ui, int ox, int oy, double scale) {
        draw(g, ui, ox, oy, scale, scale);
    }

    /**
     * 两轴各自缩放：{@code sx} 管横向、{@code sy} 管纵向。
     * HUD 板的宽高可各改各的，画面要填满板（等比缩放会留黑边）；文字不参与缩放（原版字体只 1:1 画）。
     */
    public static void draw(GuiGraphicsExtractor g, GameDefinition.StageUi ui, int ox, int oy, double sx, double sy) {
        if (ui == null) return;
        Font font = Minecraft.getInstance().font;
        for (GameDefinition.BoxDef b : ui.boxes()) {
            int x = ox + (int) Math.round(b.x() * sx);
            int y = oy + (int) Math.round(b.y() * sy);
            int w = (int) Math.round(b.w() * sx);
            int h = (int) Math.round(b.h() * sy);
            if (w <= 0 || h <= 0) continue;                  // 缩到看不见的框不画
            g.fill(x, y, x + w, y + h,
                    GameDefinition.BoxStyle.argb(b.style() == null ? "" : b.style().bg(), 0x223344));
            if (isArt(b)) {
                drawArt(g, x, y, w, h);      // 「画板（本局）」框：画的是本局的临时画板
                continue;                    // 画板框里不写字
            }
            // 舞台上的一张牌：正面 = 卡面画板像素 · 背面 = 卡背引用（没填用内置卡背）
            String ct = b.content() == null ? "" : b.content().type();
            if (ct.equals("card") || ct.equals("cardback") || ct.equals("img")) {
                drawCard(g, b, x, y, w, h);
                continue;                    // 牌面就是图，不写字
            }
            String text = textOf(b);
            if (!text.isEmpty()) {
                int color = GameDefinition.BoxStyle.argb(b.style() == null ? "" : b.style().color(), 0xFFFFFF);
                // 按框宽折行；放不下的行溢出框外（不省略、不缩字）
                int ly = y + 2;
                // 先译 & 转义再折行 —— 宽度按可见字算，格式码不占位
                for (String line : DrawBoardMenuUi.wrap(font, ColorText.mask(text), Math.max(8, w - 6))) {
                    g.text(font, line, x + 3, ly, color);
                    ly += font.lineHeight;
                }
            }
        }
    }

    /**
     * 把一块舞台（schema/3 组件树）摊成老的「框」列表，让折行 / 颜色 / 画板那套一份实现继续用。
     * 组件类型 → 框内容：{@code text}→text · {@code value}→number(槽位名) · {@code draw}→image ·
     * {@code input}→input(提示) · {@code button}→text(「▶ 标签」) · {@code panel}/{@code group}→none(只当底色)。
     */
    public static GameDefinition.StageUi uiOf(GameDefinition.StageView v) {
        java.util.List<GameDefinition.BoxDef> boxes = new java.util.ArrayList<>();
        if (v != null) collect(v.components(), boxes);
        return new GameDefinition.StageUi(v == null ? 320 : (int) v.w(), v == null ? 180 : (int) v.h(), boxes,
                v == null || v.bg() == null ? "" : v.bg());      // 舞台铺底那层（脚本 bg(…)）随视图下来
    }

    private static void collect(java.util.List<GameDefinition.Component> list,
                                java.util.List<GameDefinition.BoxDef> out) {
        for (GameDefinition.Component c : list) {
            out.add(new GameDefinition.BoxDef(c.id(), c.x(), c.y(), c.w(), c.h(), contentOf(c), styleOf(c)));
            if (!c.children().isEmpty()) collect(c.children(), out);
        }
    }

    private static GameDefinition.BoxContent contentOf(GameDefinition.Component c) {
        return switch (c.type()) {
            case "text" -> new GameDefinition.BoxContent("text", c.s("text", ""), "");
            // 引用显示：ref = 脚本里的名字（槽位名 / 内建值名）
            case "value" -> new GameDefinition.BoxContent("number", "", slotId(c.s("ref", "")));
            case "draw" -> new GameDefinition.BoxContent("image", "", "");
            // 输入框的第三字段（var）装的是框 id（宿主给的 inbox）：提交时原样回传，宿主拿它查资产名给脚本。不参与显示。
            case "input" -> new GameDefinition.BoxContent("input", c.s("hint", ""), c.s("inbox", ""));
            case "card" -> new GameDefinition.BoxContent("card", c.s("art", ""), "");        // 舞台上的牌（正面）
            case "cardback" -> new GameDefinition.BoxContent("cardback", c.s("art", ""), ""); // 同上（背面）
            case "img" -> new GameDefinition.BoxContent("img", c.s("art", ""), "");           // 图片框
            case "button" -> new GameDefinition.BoxContent("text", "▶ " + c.s("label", "按钮"), "");
            default -> new GameDefinition.BoxContent("none", "", "");
        };
    }

    /** 引用写法 → 槽位名（{@code var:x@actor[0]} → {@code x}）：兼顾老档带前缀的写法。 */
    private static String slotId(String ref) {
        String e = ref == null ? "" : ref;
        int c = e.indexOf(':');
        if (c > 0) e = e.substring(c + 1);
        int at = e.indexOf('@');
        if (at >= 0) e = e.substring(0, at);
        int br = e.indexOf('[');
        if (br >= 0) e = e.substring(0, br);
        return e.endsWith(".count") ? e.substring(0, e.length() - 6) : e;
    }

    private static GameDefinition.BoxStyle styleOf(GameDefinition.Component c) {
        String bg = switch (c.type()) {
            case "panel" -> c.s("bg", "#223344");
            case "button" -> c.s("bg", "#3A3A52");
            case "input" -> c.s("bg", "#202028");
            default -> c.s("bg", "#00000000");        // 文本/引用显示/分组：不出底色
        };
        return new GameDefinition.BoxStyle(bg, c.s("color", "#FFFFFF"));
    }

    /** 舞台等比缩进 availW×availH 的缩放比（画布尺寸为 0 时按 1 算，防除零）。 */
    public static double fitScale(GameDefinition.StageUi ui, double availW, double availH) {
        return Math.min(availW / Math.max(1, ui.w()), availH / Math.max(1, ui.h()));
    }

    /** 框里该显示什么：固定文本原样，绑变量的取当前值（不在这局里 = 空）。 */
    private static String textOf(GameDefinition.BoxDef b) {
        if (b.content() == null) return "";
        return switch (b.content().type()) {
            case "text" -> b.content().value();
            case "number" -> ClientGameHandler.varValue(b.content().var());   // 按槽位名取快照里的值
            case "image" -> "";              // 画板框：内容走 drawArt，不写字
            case "card", "cardback", "img" -> "";   // 舞台上的牌 / 图片框：内容走 drawCard，不写字
            // 输入框：静态显示占位提示；全屏下 EditBox 叠在上面自己画字，HUD 收不到键盘就停在提示上。
            case "input" -> b.content().value();
            // ⛔ 没有 countdown：剩余秒由脚本写进槽位（`on every(1) { ... }`），舞台框绑槽位名即可。
            default -> "";                    // none：空框只当面板
        };
    }

    /** 这是「画板（本局）」框？（content.type = image） */
    private static boolean isArt(GameDefinition.BoxDef b) {
        return b.content() != null && "image".equals(b.content().type());
    }

    /**
     * 把本局的临时画板画进这个框（整板等比铺满、居中）。
     * 渲染走 {@link BoardCanvas}，与画板屏同一份实现；还没收到板时压暗兜底，不静默留空。
     */
    private static void drawArt(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (!StageBoardCache.ready()) {
            g.fill(x, y, x + w, y + h, 0xFF202020);
            return;
        }
        int bw = StageBoardCache.width();
        int bh = StageBoardCache.height();
        if (bw <= 0 || bh <= 0) return;
        double cell = Math.min((double) w / bw, (double) h / bh);   // 整板等比铺满（可能是小数）
        double ox = (w - bw * cell) / 2.0;                          // 居中：没铺满的边留白
        double oy = (h - bh * cell) / 2.0;
        BoardCanvas.render(g, StageBoardCache.pixels(), bw, bh, x, y, w, h, ox, oy, cell, true);
    }

    /**
     * 舞台上的一张牌：正面 = 卡面的画板像素；背面 = 卡定义里的卡背引用，没填用内置卡背（{@link CardBacks}）。
     * 与 {@link #drawArt} 是两回事（牌引用卡定义里那份画板项目）；像素包在路上 / 引用空 → 压暗兜底，下帧 pull 补上。
     */
    private static void drawCard(GuiGraphicsExtractor g, GameDefinition.BoxDef b, int x, int y, int w, int h) {
        String art = b.content() == null ? "" : b.content().value();
        boolean back = b.content() != null && b.content().type().equals("cardback");
        if (back && (art == null || art.isBlank())) {
            drawPixels(g, CardBacks.pixels("blue"), CardBacks.CARD_W, CardBacks.CARD_H, x, y, w, h);
            return;
        }
        if (art != null && !art.isBlank()) {
            ClientGameHandler.requestFace(art);                 // pull：没缓存就拉一次（防抖在里面）
            ClientGameHandler.Face f = ClientGameHandler.face(art);
            if (f != null && f.w() > 0 && f.h() > 0) {
                drawPixels(g, f.px(), f.w(), f.h(), x, y, w, h);
                return;
            }
        }
        g.fill(x, y, x + w, y + h, 0xFF202020);
    }

    /** 一份像素等比铺满一块区域（居中留白）—— 画法与画板框同款。 */
    private static void drawPixels(GuiGraphicsExtractor g, int[] px, int bw, int bh, int x, int y, int w, int h) {
        if (px == null || bw <= 0 || bh <= 0) return;
        double cell = Math.min((double) w / bw, (double) h / bh);
        double ox = (w - bw * cell) / 2.0;
        double oy = (h - bh * cell) / 2.0;
        BoardCanvas.render(g, px, bw, bh, x, y, w, h, ox, oy, cell, true);
    }
}
