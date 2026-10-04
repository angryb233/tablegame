package com.tablegame.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

    /**
     * 座位实体（tablegame:seat）——椅子自带的骑乘点，与椅子强绑定。
     *
     * <p>椅子放置时同步生成；每 tick 对齐到椅子方块中心并检查椅子还在，椅子没了就自我销毁（乘客自动脱离）。
     * 可存档（记住所属椅子坐标，退重进后仍在）。Shift 起身走船式脱离落点，见
     * {@link #getDismountLocationForPassenger}。
     */
public class SeatEntity extends Entity {

    // 所属椅子的方块坐标：存进存档，每 tick 用它判活 + 对齐
    private BlockPos chairPos = BlockPos.ZERO;

    /**
     * 这个座位属于哪把椅子（{@link ChairBlock#findSeat} 靠它认领，别用「附近有没有座位」代替 ——
     * 并排的椅子会互相抢）。
     */
    public BlockPos chairPos() {
        return chairPos;
    }

    /**
     * 刚才是按 shift 起身的那个乘客（null = 没有）。
     *
     * <p>原版 {@code Player.rideTick()} 在 shift 起身后会把服务端潜行标志清成 false，客户端又不再发输入包
     * ⇒ 服务端一直以为他没潜行（潜行右键椅子仍能坐上去、且被当成「放置方块」）。{@link #tick()} 里补回这一 bit。
     */
    private LivingEntity shiftDismount;

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 由 ChairBlock 在生成时调用：记住椅子坐标，并把实体放到椅子中心。
     * y +0.6：骑乘附件偏移 ≈ -0.6，臀部 ≈ 脚底 + 0.7 → 落在座面顶（10.4/16 = 0.65）附近。
     */
    public void bindToChair(BlockPos chairPos) {
        this.chairPos = chairPos.immutable();
        setPos(chairPos.getX() + 0.5, chairPos.getY() + 0.6, chairPos.getZ() + 0.5);
    }

    // ===== Entity 的四个抽象方法 =====

    // 无同步数据（synched data），空实现。
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    // 存档读取：恢复椅子坐标
    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        chairPos = new BlockPos(
                input.getIntOr("chair_x", 0),
                input.getIntOr("chair_y", 0),
                input.getIntOr("chair_z", 0));
    }

    // 存档写入：保存椅子坐标
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("chair_x", chairPos.getX());
        output.putInt("chair_y", chairPos.getY());
        output.putInt("chair_z", chairPos.getZ());
    }

    // 座位无敌：纯骑乘点，不吃伤害
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    /**
     * 每 tick：椅子方块没了 → {@link #discard()} 自我销毁（乘客自动脱离）；否则把座位对位到椅子中心。
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || isRemoved()) {
            return;
        }
        if (!(level().getBlockState(chairPos).getBlock() instanceof ChairBlock)) {
            discard();
            return;
        }
        setPos(chairPos.getX() + 0.5, chairPos.getY() + 0.6, chairPos.getZ() + 0.5);
        // 补回「他还在按 shift」这件事（见 shiftDismount 字段）：起身那一刻他确实按着，
        // vanilla 随后清掉了、客户端又不再发同一输入 ⇒ 这里补上。
        if (shiftDismount != null && getPassengers().isEmpty()) {
            shiftDismount.setShiftKeyDown(true);
            shiftDismount = null;
        }
    }

    /**
     * 船式脱离落点：椅子前方那一格能站人（脚下有支撑、身体两格无碰撞）→ 落在前方地面；
     * 否则（墙/悬崖堵着）→ 落在椅面上（座面顶部 y+0.65）。
     * 脱离流程在坐骑身上调它：LivingEntity#dismountVehicle 里 vehicle.getDismountLocationForPassenger(rider)。
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity rider) {
        // 记账：这次起身是「按 shift」触发的吗（vanilla 正是在这条路上清掉服务端的潜行标志）。
        // ⚠ 不能用 Player.wantsToStopRiding()：它是 protected、跨包调不到；此刻它等价于 isShiftKeyDown()
        //   （就是它在 Player.rideTick 里触发这次起身，随后才被清成 false）——
        if (rider.isShiftKeyDown()) {
            shiftDismount = rider;
        }
        Direction facing = level().getBlockState(chairPos).getValue(ChairBlock.FACING);
        BlockPos front = chairPos.relative(facing);
        if (canStandAt(level(), front)) {
            return Vec3.atBottomCenterOf(front);
        }
        // 前方站不了 → 站到椅面上（座面顶部 10.4/16 = 0.65）
        return Vec3.atBottomCenterOf(chairPos).add(0, 0.65, 0);
    }

    // 判断某格能否站人：脚下那一格有碰撞（可支撑），身体两格内无碰撞（不卡头不穿墙）
    private static boolean canStandAt(Level level, BlockPos feetPos) {
        BlockState below = level.getBlockState(feetPos.below());
        if (below.getCollisionShape(level, feetPos.below()).isEmpty()) {
            return false;
        }
        return level.getBlockState(feetPos).getCollisionShape(level, feetPos).isEmpty()
                && level.getBlockState(feetPos.above()).getCollisionShape(level, feetPos.above()).isEmpty();
    }
}
