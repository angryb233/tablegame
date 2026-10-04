package com.tablegame.piece;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 模型制作器 GUI 的模态浮层基类（命名窗口 / 导入列表两种用法）。
 *
 * <p>用浮层而不是新开屏：关 Screen 再开会重走一遍 openMenu 同步（槽位闪一下）；浮层盖在
 * {@link ModelMakerScreen} 上，关掉回原界面、槽位状态原样。
 *
 * <p>点击拦截：本类把所有鼠标事件吃掉返回 true，底下的槽位收不到点击。26.x 无容器贴图可借，
 * 自己 fill + outline 描框（outline 第3/4参是宽高不是角点）。
 */
public abstract class ModelMakerOverlayScreen extends Screen {
    protected final ModelMakerScreen parent;
    /** 面板矩形（居中）。 */
    protected int panelX, panelY, panelW = 200, panelH = 60;

    protected ModelMakerOverlayScreen(ModelMakerScreen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    /** Esc = 取消（不发任何包，直接回主界面）。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 关浮层回模型制作器界面（槽位状态原样，不重新开菜单）。 */
    protected void close() {
        minecraft.setScreen(parent);
    }

    /** 先让浮层控件收点击，再无条件吃掉剩余点击：防隔层点到下面的槽位/按钮。 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        super.mouseClicked(event, doubled);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 底图：遮罩 + 居中面板（fill+outline 自绘）。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xA0101020);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFFC6C6C6);   // 原版面板浅灰
        g.outline(panelX, panelY, panelW, panelH, 0xFF373737);                  // outline=(x,y,宽,高)
    }
}
