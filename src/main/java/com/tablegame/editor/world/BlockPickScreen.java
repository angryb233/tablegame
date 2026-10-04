package com.tablegame.editor.world;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import com.tablegame.editor.item.ItemBasePicker;
/**
 * 「挑一个方块」屏：从区域视口右键「编辑方块」进来，选完回视口。
 * 清单复用 {@link ItemBasePicker}（带「只列能当方块的物品」过滤）；选中的 id 字符串交给回调，
 * 调用方用 {@code GameStore.blockFromString} 换回 BlockState。
 */
final class BlockPickScreen extends Screen {

    private final Screen back;
    /** 选中方块 id 后回调（回调里自己回去，本屏不管导航）。 */
    private final Consumer<String> onPick;
    private final ItemBasePicker picker = new ItemBasePicker();

    BlockPickScreen(Screen back, Consumer<String> onPick) {
        super(Component.literal("挑一个方块"));
        this.back = back;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        clearWidgets();
        int w = Math.min(360, width - 60);
        int x = (width - w) / 2;
        picker.setFilter(i -> i instanceof BlockItem);
        picker.reset();
        picker.build(x, 52, w, height - 52 - 46, this::addRenderableWidget, null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (picker.click(event.x(), event.y(), id -> {
            onPick.accept(id);
            net.minecraft.client.Minecraft.getInstance().setScreen(back);   // 选完回视口
        })) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (picker.scroll(dy)) {
            return true;                                  // 行是自绘的：滚完不用重建控件
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {                         // Esc = 放弃
            net.minecraft.client.Minecraft.getInstance().setScreen(back);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xE0101218);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, Component.literal("挑一个方块（点一行即应用 · Esc 放弃）"),
                width / 2, 22, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        picker.draw(g, font, mouseX, mouseY);
    }
}
