package com.tablegame.piece;

import com.tablegame.TableGame;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 模型制作器的容器 Menu —— 照原版切石机架构。
 *
 * <p>双端同构造、不同数据源：服务端包「真 BE」（槽位直接读写 BE，权威数据）；客户端包「空
 * SimpleContainer」，槽位显示靠原版容器同步协议按槽位下标对号入座 —— 客户端菜单只是镜子。
 *
 * <p>防刷：拖放走原版容器协议（服务端裁决）；快速移动用 moveItemStackTo 区间对扫；幽灵产出槽
 * mayPlace/mayPickup 全 false。
 *
 * <p>比例用 Menu 的 DataSlot 逐 tick 同步（原版熔炉进度条同款，零自定义包）；选比例 / 制作走
 * 自定义 C2S 包（ModelMakerPackets），服务端全量校验。
 */
public class ModelMakerMenu extends AbstractContainerMenu {

    /** BE 槽位数（蓝图+粘土），玩家背包从这之后开始。 */
    public static final int NUM_BE_SLOTS = 2;
    /** 幽灵产出槽的下标（加在 BE 槽之后）。 */
    public static final int GHOST_SLOT = NUM_BE_SLOTS;
    /** 玩家背包起始下标。 */
    public static final int INV_START = NUM_BE_SLOTS + 1;
    /** 总槽位数 = 2 BE + 1 幽灵 + 36 玩家格。 */
    public static final int TOTAL_SLOTS = INV_START + 36;

    /**
     * 客户端工厂：pos 从打开包的额外数据读回（服务端 openMenu 写入），容器空壳、内容靠同步协议。
     */
    public static ModelMakerMenu clientMenu(int containerId, Inventory playerInv,
            net.minecraft.network.RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        return new ModelMakerMenu(containerId, playerInv, pos,
                new SimpleContainer(NUM_BE_SLOTS), ContainerLevelAccess.NULL);
    }

    /** 服务端构造（ModelMakerBlock.useWithoutItem 调）：真 BE + 真坐标访问器。 */
    public ModelMakerMenu(int containerId, Inventory playerInv, BlockPos pos,
            Container beContainer, ContainerLevelAccess access) {
        super(TableGame.MODEL_MAKER_MENU.get(), containerId);
        this.access = access;
        this.worldPos = pos;

        // [0] 蓝图槽：空白/锁定蓝图都收（导入要空白、制作要锁定，服务端各有校验）
        this.addSlot(new Slot(beContainer, ModelMakerBlockEntity.SLOT_BLUEPRINT, 44, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof BlueprintItem;
            }
        });
        // [1] 粘土槽：只收粘土球
        this.addSlot(new Slot(beContainer, ModelMakerBlockEntity.SLOT_CLAY, 80, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(net.minecraft.world.item.Items.CLAY_BALL);
            }
        });
        // [2] 幽灵产出槽：永不放物、拿不出（产出只走「制作」按钮）
        this.addSlot(new Slot(new SimpleContainer(1), 0, 134, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });

        // 玩家背包 27 + 热键栏 9（原版标准布局，起点 (8, 84)）
        this.addStandardInventorySlots(playerInv, 8, 84);

        this.addDataSlot(this.scale);

        // 服务端：把 BE 比例拷进 DataSlot
        if (beContainer instanceof ModelMakerBlockEntity be) {
            this.scale.set(be.getScale());
        }
    }

    private final ContainerLevelAccess access;
    /** 方块坐标（C2S 包要用）。 */
    private final BlockPos worldPos;
    /** 比例的 DataSlot：Menu 逐 tick 自动同步到客户端。 */
    private final DataSlot scale = DataSlot.standalone();

    /** 方块坐标（Screen 发包用）。 */
    public BlockPos getWorldPos() {
        return worldPos;
    }

    /** 当前比例（Screen 画高亮用）。 */
    public int getScale() {
        return this.scale.get();
    }

    /** 每 tick 广播：把 BE 比例刷进 DataSlot（客户端 NULL 访问器跳过）。 */
    @Override
    public void broadcastChanges() {
        access.evaluate((level, pos) -> {
            if (level.getBlockEntity(pos) instanceof ModelMakerBlockEntity be
                    && this.scale.get() != be.getScale()) {
                this.scale.set(be.getScale());
            }
            return true;
        });
        super.broadcastChanges();
    }

    // ===== 快速移动（Shift+点）：切石机模式，区间对扫防刷 =====

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot from = this.slots.get(index);
        if (!from.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack src = from.getItem();
        ItemStack original = src.copy();

        if (index < INV_START) {
            // BE 槽 / 幽灵槽 → 玩家背包（幽灵槽 hasItem 已挡）
            if (!this.moveItemStackTo(src, INV_START, TOTAL_SLOTS, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 → BE 槽（mayPlace 过滤）
            if (!this.moveItemStackTo(src, 0, NUM_BE_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (src.isEmpty()) {
            from.setByPlayer(ItemStack.EMPTY);
        } else {
            from.setChanged();
        }
        return original;
    }

    // ===== GUI 开着的前置条件 =====
    // 服务端：方块还在 + 玩家 8 格内（判定失败自动关 GUI）；NULL 访问器默认放行。

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, TableGame.MODEL_MAKER.get());
    }
}