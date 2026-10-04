package com.tablegame.piece;

import com.tablegame.TableGame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 模型制作器的方块实体（BE）。
 *
 * <p>它自己就是一个 {@link Container}（2 格：0=蓝图槽，1=粘土槽），Menu 的 {@code Slot} 直接
 * 包住它 —— 原版切石机/熔炉同套路。不用 ItemStackHandler（那是给管道/自动化能力系统的），
 * 两格手写 Container 代码更少。
 *
 * <p>蓝图槽只收已锁定的蓝图；粘土槽每次制作消耗 1 个粘土球；scale 是规格记忆（2/4/8）。
 *
 * <p>26.x 存档签名 {@code saveAdditional(ValueOutput)} / {@code loadAdditional(ValueInput)}，
 * 物品栏用 {@code ContainerHelper.saveAllItems/loadAllItems}。
 */
public class ModelMakerBlockEntity extends BlockEntity implements Container {

    /** 槽位下标：Menu 与 BE 共用同一套编号。 */
    public static final int SLOT_BLUEPRINT = 0;
    public static final int SLOT_CLAY = 1;
    public static final int NUM_SLOTS = 2;

    /** 允许的规格档位（GUI 按钮 & 服务端校验都用它）。 */
    public static final int[] ALLOWED_SCALES = {2, 4, 8};
    public static final int DEFAULT_SCALE = 2;

    private final NonNullList<ItemStack> items = NonNullList.withSize(NUM_SLOTS, ItemStack.EMPTY);

    /** 规格记忆，随存档持久化（分母 2/4/8，越小越微缩）。 */
    private int scale = DEFAULT_SCALE;

    /** 玩家右键方块时由 ModelMakerBlock.newBlockEntity 创建。 */
    public ModelMakerBlockEntity(BlockPos pos, BlockState state) {
        super(TableGame.MODEL_MAKER_BE.get(), pos, state);
    }

    // ===== 比例 =====

    public int getScale() {
        return scale;
    }

    /** 设置比例（仅接受 ALLOWED_SCALES 里的值，防伪造包塞任意数）。 */
    public boolean setScale(int value) {
        for (int s : ALLOWED_SCALES) {
            if (s == value) {
                if (this.scale != value) {
                    this.scale = value;
                    setChanged(); // 标脏 → 存档时落盘
                }
                return true;
            }
        }
        return false;
    }

    // ===== 便捷取用（Menu/Screen 显示用） =====

    public ItemStack getBlueprint() {
        return items.get(SLOT_BLUEPRINT);
    }

    public ItemStack getClay() {
        return items.get(SLOT_CLAY);
    }

    /** 蓝图槽里的 PieceData（null = 槽空）。 */
    public PieceData getBlueprintData() {
        ItemStack bp = getBlueprint();
        return bp.isEmpty() ? null : PieceData.get(bp);
    }

    /** 清空所有槽位（Clearable 的抽象方法）。 */
    @Override
    public void clearContent() {
        items.clear();
    }

    // ===== Container 实现（2 格的最小实现） =====

    @Override
    public int getContainerSize() {
        return NUM_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        // 原版标准「取走 n 个并置空」逻辑
        ItemStack taken = ContainerHelper.removeItem(items, slot, amount);
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        // 上限钳制：蓝图不可堆叠(1)，粘土球 16
        if (stack.getCount() > getMaxStackSize(stack)) {
            stack.setCount(getMaxStackSize(stack));
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        // 方块还在 + 玩家 8 格内（方块被挖掉会自动关界面）
        return Container.stillValidBlockEntity(this, player);
    }

    /**
     * 槽位过滤（漏斗/管道也走这里）：蓝图槽只收 BlueprintItem（空白/锁定都收）；粘土槽只收粘土球。
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_BLUEPRINT) {
            return stack.getItem() instanceof BlueprintItem;   // 空白/锁定都收：导入要空白、制作要锁定（各自服务端校验）
        }
        if (slot == SLOT_CLAY) {
            return stack.is(Items.CLAY_BALL);
        }
        return false;
    }

    // ===== 存档（26.x 新签名：ValueOutput / ValueInput，不再是 CompoundTag） =====

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items); // 存物品栏（"Items" 键）
        output.putInt("Scale", scale);               // 存比例记忆
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ContainerHelper.loadAllItems(input, items);
        this.scale = input.getIntOr("Scale", DEFAULT_SCALE);
    }
}
