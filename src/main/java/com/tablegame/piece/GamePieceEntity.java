package com.tablegame.piece;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import com.tablegame.TableGame;

/**
 * 棋子实体（tablegame:game_piece_entity）——棋子在世界里的「承载体」。
 * 隐形实体 + 体素渲染：不占方块格、无碰撞、几乎零尺寸，外形由 {@link GamePieceEntityRenderer} 逐格提交方块模型画出。
 * 客户端渲染要 PieceData：实体只 syncher 一个 {@link ItemStack}（复用原版序列化器），把整个棋子物品塞进去，渲染端从中读组件。
 * 存档：同一个 stack 序列化进存档（ValueOutput.store + ItemStack.CODEC），退出重进后带数据回来。
 */
public class GamePieceEntity extends Entity {

    // ===== 同步数据：承载的棋子物品（客户端从它读 PieceData 去渲染） =====

    /**
     * 数据访问器 = 「第 N 号同步字段」的句柄；defineId 必须在类加载期注册一次。
     */
    private static final EntityDataAccessor<ItemStack> DATA_STACK =
            SynchedEntityData.defineId(GamePieceEntity.class, EntityDataSerializers.ITEM_STACK);

    public GamePieceEntity(EntityType<? extends GamePieceEntity> type, Level level) {
        super(type, level);
    }

    /** 放置时由 GamePieceItem 调用：把棋子物品（含 PieceData 组件）交给实体承载。 */
    public void setPieceStack(ItemStack stack) {
        this.entityData.set(DATA_STACK, stack);
    }

    /** 渲染端 / 存档用的读取口：承载的棋子物品。 */
    public ItemStack getPieceStack() {
        return this.entityData.get(DATA_STACK);
    }

    // ===== Entity 抽象方法（26.x 四件套） =====

    /**
     * 声明同步字段及初值（空 stack = 没有棋子，不渲染）；服务端 set 后原版自动同步给追踪客户端。
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STACK, ItemStack.EMPTY);
    }

    /**
     * 存档读取：用 ItemStack.CODEC 原样读回（PieceData 随 ItemStack 整体序列化，无需单独存）。
     */
    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.read("Piece", ItemStack.CODEC).ifPresent(this::setPieceStack);
    }

    /**
     * 存档写入：承载的棋子原样落盘（数据组件 → NBT 全自动）；空 stack 不写，省存档体积。
     */
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        ItemStack stack = getPieceStack();
        if (!stack.isEmpty()) {
            output.store("Piece", ItemStack.CODEC, stack);
        }
    }

    /**
     * 棋子无敌（防误删）：攻击/爆炸/火焰都打不掉；唯一移除途径是 Shift+右键取回或 /kill。
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    // ===== 行为修饰：隐形承载体要「三无」 =====

    // 无碰撞：玩家/实体可以直接穿过（一格放多个的前提）。
    @Override
    public boolean isPushable() {
        return false;
    }

    // 准星可选中：Shift+右键取回走原版「实体交互」管线，只有 isPickable()=true 才派发 interact。
    // （也吃攻击射线，但 hurtServer 返回 false，无副作用。）
    @Override
    public boolean isPickable() {
        return true;
    }

    // 其他实体不能撞它（碰撞箱不挡任何东西，一格放多个的前提）。
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.Entity other) {
        return false;
    }

    /**
     * 取回交互：Shift+右键棋子实体 → 物品原样回来。26.x 签名 interact(Player, InteractionHand, Vec3)。
     * 逻辑全在服务端分支，客户端只回 SUCCESS 让原版发包；服务端回 SUCCESS_SERVER。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 hitLocation) {
        Level level = level();
        if (level.isClientSide()) {
            // 客户端：返回 SUCCESS = 告诉原版「交互有效，发包给服务端」；实际物品发放/实体删除在服务端（防伪造）。
            return InteractionResult.SUCCESS;
        }
        // 服务端：Shift 按住 = 取回意图（isShiftKeyDown 是已认可判定方案）
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS; // 不按 Shift：无反应（棋子不能徒手拆）
        }
        ItemStack carried = getPieceStack();
        if (carried.isEmpty()) {
            discard(); // 数据坏档兜底：空棋子实体没有存在意义，清掉
            return InteractionResult.FAIL;
        }
        // 给回物品：copy 一份给玩家（背包满则保留实体，玩家腾出格子后再取）
        if (!player.getInventory().add(carried.copy())) {
            return InteractionResult.FAIL;
        }
        // 放置音效原样放一遍（音高略高 = 取回感，零成本反馈）
        level().playSound(null, getX(), getY(), getZ(),
                net.minecraft.sounds.SoundEvents.ARMOR_STAND_PLACE,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7f, 1.3f);
        discard(); // 实体使命完成，销毁（不落掉落物——物品已直接进背包）
        return InteractionResult.SUCCESS_SERVER;
    }

    // 隐形承载实体没有逻辑要跑：不 tick 重力/移动，位置永远不变（存档恢复也按保存位置精确还原）。

    /**
     * 工厂：GamePieceItem 服务端放置分支用。pos = 棋子底面中心，yRot = 头颅式放置朝向（见 GamePieceItem）。
     * yRot 由 Entity 基类自动序列化进存档，无需手动存。
     */
    public static GamePieceEntity place(ServerLevel level, double x, double y, double z,
            float yRot, ItemStack stack) {
        GamePieceEntity entity = new GamePieceEntity(com.tablegame.TableGame.GAME_PIECE_ENTITY.get(), level);
        entity.setPos(x, y, z);
        entity.setYRot(yRot);
        entity.yRotO = yRot; // 初值同步，避免首帧插值抖动
        entity.setPieceStack(stack);
        level.addFreshEntity(entity);
        return entity;
    }
}
