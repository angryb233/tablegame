package com.tablegame.piece;

import com.tablegame.TableGame;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 模型制作器 GUI（原版风格自绘）。
 *
 * <p>26.x GUI 是「提交阶段」：每帧覆写 {@code extractRenderState(GuiGraphicsExtractor,...)}
 * 填渲染状态。面板用 fill 自绘原版容器配色（C6C6C6 + 3D 边框，槽位 8B8B8B），不用
 * inventory.png（背包贴图自带合成格图案，与自定义槽位对不上）。
 *
 * <p>布局：标题(6) → 规格按钮行(16) → 槽位行(36)+箭头 → 制作按钮(55) → 背包。
 * 槽位坐标必须与 {@link ModelMakerMenu} 里 addSlot 的 x/y 一致。
 *
 * <p>按钮走自定义 C2S 包（SetScale/Craft，都带方块坐标，服务端全量校验）；规格显示来自
 * DataSlot，伪造请求改不了 BE。
 */
public class ModelMakerScreen extends AbstractContainerScreen<ModelMakerMenu> {

    // ===== 原版容器配色（工作台/熔炉同款浅灰系） =====
    private static final int PANEL_BG = 0xFFC6C6C6;
    private static final int BORDER_LIGHT = 0xFFFFFFFF; // 面板高光（上/左边）
    private static final int BORDER_DARK = 0xFF555555;  // 面板阴影（下/右边）
    private static final int SLOT_BG = 0xFF8B8B8B;      // 原版槽位底色
    private static final int SLOT_DARK = 0xFF373737;    // 槽位内阴影（上/左）
    private static final int SLOT_LIGHT = 0xFFFFFFFF;   // 槽位高光（下/右）
    private static final int TEXT_COLOR = 0xFF404040;   // 原版标题深灰
    private static final int ARROW_COLOR = 0xFF8B8B8B;  // 流程箭头
    /** 当前规格按钮的描边绿。 */
    private static final int COLOR_ACTIVE = 0xFF54FB54;

    private Button[] scaleButtons;
    private Button craftButton;
    /** 蓝图库三按钮：保存 / 导入 / 导出。 */
    private Button saveButton, importButton, exportButton;

    public ModelMakerScreen(ModelMakerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title); // imageWidth/Height 用父类默认 176×166
    }

    @Override
    protected void init() {
        super.init(); // 父类算 leftPos/topPos（面板居中）、注册槽位鼠标行为

        // ===== 规格按钮行：y=16，3 个 32×16，整行居中 =====
        // 规格语义：体素先缩到 1/16 基准，规格 1/2、1/4、1/8 = 在基准上放大 2/4/8 倍；
        // 按钮显示分数，内部 scale = 分母（2/4/8）。3×32 + 2×8 = 112 宽，起点 (176-112)/2 = 32。
        int[] scales = ModelMakerBlockEntity.ALLOWED_SCALES;
        scaleButtons = new Button[scales.length];
        for (int i = 0; i < scales.length; i++) {
            final int idx = i;
            scaleButtons[i] = addRenderableWidget(Button.builder(
                    Component.literal("1/" + scales[idx]),
                    b -> {
                        ClientPacketDistributor.sendToServer(
                                new ModelMakerPackets.SetScalePayload(menu.getWorldPos(), scales[idx]));
                        // 点后本按钮被禁用，禁用组件持有焦点会画白色描框——把焦点还回窗口。
                        this.setFocused(null);
                    })
                    .bounds(leftPos + 32 + i * 40, topPos + 16, 32, 16)
                    .build());
        }

        // ===== 功能按钮行（槽位行下方 y=55，4 个 40×16 并排居中）=====
        // 库按钮并入制作按钮行 y=55：面板 166 高被背包占满，顶部没有额外一行空间。
        int rowY = topPos + 55;
        int bw = 40, gap = 4; // 4×40 + 3×4 = 172，起点 (176-172)/2 = 2
        craftButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.tablegame.model_maker.craft"),
                b -> ClientPacketDistributor.sendToServer(
                        new ModelMakerPackets.CraftPayload(menu.getWorldPos())))
                .bounds(leftPos + 2, rowY, bw, 16)
                .build());
        // 保存：弹命名+选组浮层（落盘在服务端）
        saveButton = addRenderableWidget(Button.builder(
                Component.literal("保存蓝图"),
                b -> openOverlay(new SaveBlueprintOverlay(this)))
                .bounds(leftPos + 2 + (bw + gap), rowY, bw, 16)
                .build());
        // 导入：弹列表浮层（数据由服务端扫盘发回）
        importButton = addRenderableWidget(Button.builder(
                Component.literal("导入蓝图"),
                b -> openOverlay(new ImportBlueprintOverlay(this)))
                .bounds(leftPos + 2 + (bw + gap) * 2, rowY, bw, 16)
                .build());
        // 导出：直接发包（用蓝图现有名）
        exportButton = addRenderableWidget(Button.builder(
                Component.literal("导出蓝图"),
                b -> ClientPacketDistributor.sendToServer(new ModelMakerPackets.ExportBlueprintPayload()))
                .bounds(leftPos + 2 + (bw + gap) * 3, rowY, bw, 16)
                .build());
    }

    /** 打开模态浮层（关掉回本屏，槽位状态原样）。 */
    private void openOverlay(ModelMakerOverlayScreen overlay) {
        this.setFocused(null);   // 别把按钮焦点带进浮层（防回来时白框）
        minecraft.setScreen(overlay);
    }

    /** 自绘面板：底板、3D 边框、槽位框、箭头。槽位坐标与 ModelMakerMenu.addSlot 一致。 */
    private void drawPanel(GuiGraphicsExtractor g) {
        // 面板底 + 原版 3D 边框：上/左白高光、下/右深灰阴影（原版容器同款立体感）
        // ⚠ outline/fill 的坐标语义：fill 是 (x1,y1,x2,y2) 角点；outline 是 (x,y,宽,高)。
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_BG);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, BORDER_LIGHT);
        g.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, BORDER_LIGHT);
        g.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, BORDER_DARK);
        g.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER_DARK);

        // 标题（原版深灰）
        g.text(font, title, leftPos + 8, topPos + 6, TEXT_COLOR);

        // 所有槽位的框（机器槽 + 背包 36 格）：遍历 Menu 槽位表画，坐标永远与 addSlot 一致
        //（原版把背包格画在 inventory.png 底图里，自绘面板就得自己画）。
        for (Slot s : menu.slots) {
            drawSlotBox(g, s.x, s.y);
        }

        // 箭头：粘土槽 → 产出槽
        int ax = leftPos + 104, ay = topPos + 44;
        g.fill(ax, ay, ax + 20, ay + 2, ARROW_COLOR);          // 箭身
        g.fill(ax + 20, ay - 2, ax + 24, ay + 4, ARROW_COLOR); // 箭头块
        g.fill(ax + 24, ay, ax + 25, ay + 2, ARROW_COLOR);     // 箭尖
    }

    /**
     * 单个 18×18 原版槽位框：灰底 + 上/左内阴影 + 下/右高光（凹进去的立体感）。
     * fill 用角点对，outline 参数 = (x, y, 宽, 高, 颜色)。
     */
    private void drawSlotBox(GuiGraphicsExtractor g, int slotX, int slotY) {
        int x = leftPos + slotX - 1, y = topPos + slotY - 1;
        g.fill(x, y, x + 18, y + 18, SLOT_BG);
        g.fill(x, y, x + 18, y + 1, SLOT_DARK);
        g.fill(x, y, x + 1, y + 18, SLOT_DARK);
        g.fill(x, y + 17, x + 18, y + 18, SLOT_LIGHT);
        g.fill(x + 17, y, x + 18, y + 18, SLOT_LIGHT);
    }

    /** 底板：在 super 遮罩之上画自绘面板，位于槽位/物品之下。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        drawPanel(g);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick); // 槽位+手持物+悬停提示

        // 当前规格按钮描绿框 + 其余禁用（active=false 自动变灰）
        int current = menu.getScale();
        int[] scales = ModelMakerBlockEntity.ALLOWED_SCALES;
        for (int i = 0; i < scaleButtons.length; i++) {
            Button b = scaleButtons[i];
            b.active = scales[i] != current;
            // 禁用的按钮不许持有焦点，否则会画出白描框
            if (!b.active && b.isFocused()) {
                this.setFocused(null);
            }
            if (scales[i] == current) {
                // outline = (x, y, 宽, 高, 颜色)，外扩 1px
                g.outline(b.getX() - 1, b.getY() - 1,
                        b.getWidth() + 2, b.getHeight() + 2,
                        COLOR_ACTIVE);
            }
        }

        // 按钮启用门：保存/导出要已锁定蓝图；导入要空白蓝图（有数据的会被覆盖 = 刷物品）。
        Slot bpSlot = menu.slots.get(ModelMakerBlockEntity.SLOT_BLUEPRINT);
        boolean locked = false, blank = false;
        if (bpSlot.hasItem()) {
            PieceData d = PieceData.get(bpSlot.getItem());
            locked = d.isLocked();
            blank = d.isBlank();
        }
        saveButton.active = locked;
        exportButton.active = locked;
        importButton.active = blank;
        // 每帧焦点卫生（同规格按钮组）
        for (Button b : new Button[]{saveButton, importButton, exportButton}) {
            if (!b.active && b.isFocused()) {
                this.setFocused(null);
            }
        }
    }

    /** 幽灵产出槽预览：蓝图已锁定时画一个「会产出的棋子」图标。 */
    @Override
    public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractContents(g, mouseX, mouseY, partialTick);

        Slot ghost = menu.slots.get(ModelMakerMenu.GHOST_SLOT);
        Slot blueprintSlot = menu.slots.get(ModelMakerBlockEntity.SLOT_BLUEPRINT);
        PieceData data = blueprintSlot.hasItem() ? PieceData.get(blueprintSlot.getItem()) : null;
        if (data != null && data.isLocked() && ghost.getItem().isEmpty()) {
            ItemStack preview = new ItemStack(TableGame.GAME_PIECE.get());
            PieceData.set(preview, data);
            g.item(preview, ghost.x, ghost.y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // 标题已在 drawPanel 画过，这里只画背包标签（super 会连标题一起画，重复）
        g.text(font, this.playerInventoryTitle, leftPos + 8, topPos + imageHeight - 94, TEXT_COLOR);
    }
}
