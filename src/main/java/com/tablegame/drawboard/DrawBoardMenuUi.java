package com.tablegame.drawboard;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 画板菜单类界面的共享小部件（客户端专用）。
 */
public final class DrawBoardMenuUi {
    private DrawBoardMenuUi() {}

    /** 超长文本截断（行按钮放不下时用；按字符数粗截，界面够用）。 */
    public static String ellipsis(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) return s == null ? "" : s;
        return s.substring(0, maxChars) + "…";
    }

    /**
     * 文本按像素宽度折行（不省略——框放不下就让它溢出，框有多宽就排多宽）。
     * 中文没有空格，按字符断行；单字符本身超宽时自己占一行。手改 JSON 里可能有 {@code \n}，也当强制换行。
     */
    public static java.util.List<String> wrap(Font font, String text, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        if (maxW <= 0) {
            out.add(text);
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(line.toString());
                line.setLength(0);
                continue;
            }
            if (line.length() > 0 && font.width(line.toString() + c) > maxW) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** 菜单/管理界面用的静态文本行（无交互，只画一行字）。 */
    public static AbstractWidget label(int x, int y, int w, String text, int color) {
        return new AbstractWidget(x, y, w, 12, Component.literal(text)) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor gr, int mx, int my, float pt) {
                gr.text(Minecraft.getInstance().font, getMessage().getString(), getX(), getY(), color);
            }

            @Override
            protected void updateWidgetNarration(NarrationElementOutput out) {}
        };
    }

    /** 菜单用纯矩形按钮（无原版贴图）。 */
    public static AbstractWidget button(int x, int y, int w, int h, String label, Runnable onPress) {
        return new AbstractWidget(x, y, w, h, Component.literal(label)) {
            @Override
            protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
                g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                        isHovered() ? 0xFF4A6B4A : 0xFF303030);
                g.text(Minecraft.getInstance().font, getMessage().getString(),
                        getX() + 6, getY() + (getHeight() - 9) / 2, 0xFFE0E0E0);
                g.outline(getX(), getY(), getWidth(), getHeight(), 0xFF707070);
            }

            @Override
            public void onClick(MouseButtonEvent event, boolean doubled) {
                if (onPress != null) onPress.run();
            }

            @Override
            protected void updateWidgetNarration(NarrationElementOutput out) {
                defaultButtonNarrationText(out);
            }
        };
    }

    /**
     * 可改标签/可换回调的按钮（循环选择器用：点击后换文字，如属性类型 数字→文本→布尔→枚举）。
     * onPress 非 final——循环按钮在回调里同时 setMessage（换标签）并按需替换行为。
     */
    public static class WrappedButton extends AbstractWidget {
        private Runnable onPress;
        public WrappedButton(int x, int y, int w, int h, String label, Runnable onPress) {
            super(x, y, w, h, Component.literal(label));
            this.onPress = onPress == null ? () -> {} : onPress;
        }
        /** 替换点击回调（循环选择器接线用）。 */
        public void setOnPress(Runnable r) { this.onPress = r; }
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    isHovered() ? 0xFF4A6B4A : 0xFF303030);
            g.text(Minecraft.getInstance().font, getMessage().getString(),
                    getX() + 6, getY() + (getHeight() - 9) / 2, 0xFFE0E0E0);
            g.outline(getX(), getY(), getWidth(), getHeight(), 0xFF707070);
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

    /**
     * 下拉浮层选择器（编辑器里的枚举一律用下拉，不用循环按钮）。
     *
     * <p>「半自动」控件——宿主屏要接三处：
     * <pre>
     * // ① init：正常 add（位置随意）
     * // ② mouseClicked：在 super 之前喂点击
     * for (Dropdown d : drops) if (d.handleClick(mx, my)) return true;
     * // ③ extractRenderState：在 super 之后画列表（否则被后画的兄弟控件遮住）
     * for (Dropdown d : drops) d.renderOverlay(g, mouseX, mouseY);
     * </pre>
     * ② 保证展开的列表最先拿到点击，③ 保证它在最上层。⚠ 本类全工程暂无调用，但保留（等「枚举用下拉」落地）——别当死代码删。
     *
     * <p>列表贴屏幕底部时自动改成向上弹出。
     *
     * <p>ponytail: 没做「点开一个下拉自动收起另一个」的全局面板管理器——本项目最多同时一两个下拉，
     * 等真出现「多个下拉互相盖」的场景再抽统一浮层层。
     */
    public static class Dropdown extends AbstractWidget {
        private static final int ROW = 16;
        private final String[] options;
        private final IntConsumer onPick;
        /** 固定标签（非 null = 按钮面永远显示它，不显示选中项——「候选▾」这种动作菜单用）。 */
        private final String fixedLabel;
        private int index;
        private boolean open;

        public Dropdown(int x, int y, int w, int h, String[] options, int index, IntConsumer onPick) {
            this(x, y, w, h, null, options, index, onPick);
        }

        /**
         * 带固定标签的重载：按钮面显示 {@code fixedLabel}，展开的列表仍是 {@code options}。
         * 用在「这是个动作菜单，不是取值显示」的地方（如值旁边那个「候选▾」——真正的值在输入框里）。
         */
        public Dropdown(int x, int y, int w, int h, String fixedLabel, String[] options, int index,
                        IntConsumer onPick) {
            super(x, y, w, h, Component.literal(options == null || options.length == 0 ? ""
                    : options[Math.max(0, Math.min(index, options.length - 1))]));
            this.options = options == null ? new String[0] : options;
            this.index = Math.max(0, Math.min(index, Math.max(0, this.options.length - 1)));
            this.onPick = onPick;
            this.fixedLabel = fixedLabel;
        }


        /** 换选中项（不改回调；标签同步）。 */
        public void setIndex(int i) {
            this.index = Math.max(0, Math.min(i, Math.max(0, options.length - 1)));
            setMessage(Component.literal(options.length == 0 ? "" : options[this.index]));
        }

        /** 点击接管（见类注释）。返回 true = 这一下已被吃掉。 */
        public boolean handleClick(double mx, double my) {
            if (open) {
                int row = rowAt(mx, my);
                open = false;
                if (row >= 0) {
                    setIndex(row);
                    if (onPick != null) onPick.accept(row);
                    return true;
                }
                // 点在自己身上 = 收起（吃掉这一下，免得立刻又展开）
                return inside(mx, my);
            }
            if (inside(mx, my)) {
                open = true;
                return true;
            }
            return false;
        }

        private boolean inside(double mx, double my) {
            return mx >= getX() && mx < getX() + getWidth() && my >= getY() && my < getY() + getHeight();
        }

        /** 展开列表的左上行 y（贴底时向上弹出）。 */
        private int listY() {
            int below = getY() + getHeight() + 1;
            int h = options.length * ROW + 2;
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            return below + h <= screenH ? below : Math.max(0, getY() - h - 1);
        }

        /** 命中展开列表第几行（-1 = 未命中）。 */
        private int rowAt(double mx, double my) {
            if (!open || options.length == 0) return -1;
            int ly = listY();
            if (mx < getX() || mx >= getX() + getWidth() || my < ly + 1) return -1;
            int row = (int) ((my - ly - 1) / ROW);
            return row >= 0 && row < options.length ? row : -1;
        }

        /** 控件本体：只画按钮面（标签 + 箭头）。展开的列表不在这里画——见 renderOverlay。 */
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
            var font = Minecraft.getInstance().font;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    open ? 0xFF4A6B4A : (isHovered() ? 0xFF3A4A3A : 0xFF303030));
            g.text(font, ellipsis(fixedLabel != null ? fixedLabel : getMessage().getString(), 18),
                    getX() + 6, getY() + (getHeight() - 9) / 2, 0xFFE0E0E0);
            g.text(font, "v", getX() + getWidth() - 9, getY() + (getHeight() - 9) / 2, 0xFFA0A0C0);
            g.outline(getX(), getY(), getWidth(), getHeight(), 0xFF707070);
        }

        /**
         * 展开的选项列表：宿主屏在 {@code super.extractRenderState} <b>之后</b>调，
         * 保证它在所有控件之上（见类注释 ③）。
         */
        public void renderOverlay(GuiGraphicsExtractor g, int mx, int my) {
            if (!open || options.length == 0) return;
            var font = Minecraft.getInstance().font;
            int ly = listY();
            int h = options.length * ROW + 2;
            g.fill(getX(), ly, getX() + getWidth(), ly + h, 0xF0181828);
            g.outline(getX(), ly, getWidth(), h, 0xFF8890C0);
            int hover = rowAt(mx, my);
            for (int i = 0; i < options.length; i++) {
                int ry = ly + 1 + i * ROW;
                if (i == hover) g.fill(getX() + 1, ry, getX() + getWidth() - 1, ry + ROW, 0xFF34345C);
                g.text(font, ellipsis(options[i], 20), getX() + 6, ry + 4,
                        i == index ? 0xFFE0C060 : 0xFFE0E0E0);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubled) {
            open = true;   // 走不到（handleClick 已在 Screen 层先吃掉），保留以防别的屏直接靠控件分发
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /**
     * 「打开项目文件夹」：用系统资源管理器弹出<b>本机</b>的 projects 目录（不存在就创建）。
     * 优先 java.awt.Desktop；不支持/失败时退回系统命令（explorer.exe / open / xdg-open）；
     * 全失败才在聊天栏给出规范化绝对路径。联机时弹的是自己电脑上的个人镜像目录。
     */
    public static void openLocalProjectsFolder() {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("tablegame").resolve("projects")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            msg("[画板] 无法创建项目目录: " + e.getMessage());
            return;
        }
        // 1) java.awt.Desktop（多数桌面环境支持）
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ignored) {
            // 落到系统命令兜底
        }
        // 2) 系统命令兜底
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", dir.toString()).start();
            }
            return;
        } catch (Exception ignored) {
            // 落到文字提示
        }
        msg("[画板] 无法自动打开文件夹，项目目录在: " + dir);
    }

    /** 给玩家发一条聊天消息（客户端本地提示）。 */
    public static void msg(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }
}
