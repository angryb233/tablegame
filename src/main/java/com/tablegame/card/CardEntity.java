package com.tablegame.card;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import com.tablegame.TableGame;

/**
 * 卡牌实体（tablegame:card_entity）——一张卡在世界里的隐形承载体。
 *
 * <p>照 {@code GamePieceEntity} 的先例：外观全由渲染器画、无碰撞、可选中、打不掉、存档随实体走。
 *
 * <p>同步数据：牌面资产名 / 背面样式 / 尺寸，用原版 STRING/STRING/FLOAT 三个序列化器，无自定义协议。
 * 朝向 = 实体 yRot（正面朝持有者 = 从持有者指向牌的方向），不需要额外的正反面开关。
 */
public class CardEntity extends Entity {

    private static final EntityDataAccessor<String> DATA_ART =
            SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_BACK =
            SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.STRING);
    /** 尺寸分母：值越小牌越大（同棋子的「尺寸」口径），1 = 原尺寸。 */
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.FLOAT);
    /**
     * 自转（绕 Z，度）—— 实体画面组件的 {@code rot}。倾角（绕 X）直接用原版 xRot（随实体旋转包
     * 同步落盘）；绕 Z 原版没有，自开一个同步字段。
     */
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.FLOAT);

    public CardEntity(EntityType<? extends CardEntity> type, Level level) {
        super(type, level);
    }

    public String art() {
        return this.entityData.get(DATA_ART);
    }

    public String back() {
        return this.entityData.get(DATA_BACK);
    }

    public float scale() {
        return this.entityData.get(DATA_SCALE);
    }

    /** 换牌：牌面资产名 / 背面样式 / 尺寸（宿主 place_card 时一次写全）。 */
    public void setCard(String art, String back, float scale) {
        this.entityData.set(DATA_ART, art == null ? "" : art);
        this.entityData.set(DATA_BACK, back == null || back.isEmpty() ? "blue" : back);
        this.entityData.set(DATA_SCALE, scale <= 0f ? 1f : scale);
    }

    /** 翻面：yRot 转 180 度（原版替我们同步与落盘）。 */
    public void flip() {
        setYRot(getYRot() + 180f);
    }

    /** 倾角 / 自转（度）—— 实体画面组件 `rot` 的 x / z 两轴；`0 0` = 直着放（老行为）。 */
    public void setTilt(float pitch, float roll) {
        setXRot(pitch);
        this.entityData.set(DATA_ROLL, roll);
    }

    /** 自转角度（绕 Z，度）。倾角读原版的 {@code getXRot()}。 */
    public float roll() {
        return this.entityData.get(DATA_ROLL);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ART, "");
        builder.define(DATA_BACK, "blue");
        builder.define(DATA_SCALE, 1f);
        builder.define(DATA_ROLL, 0f);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        setCard(input.getStringOr("Art", ""), input.getStringOr("Back", "blue"),
                input.getFloatOr("Scale", 1f));
        setTilt(getXRot(), input.getFloatOr("Roll", 0f));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        if (!art().isEmpty()) output.putString("Art", art());
        output.putString("Back", back());
        output.putFloat("Scale", scale());
        if (roll() != 0f) output.putFloat("Roll", roll());          // 缺省不写（老档一字不变）
    }

    /** 牌打不掉（与棋子同一口径：防误删；移除只走局末收起 / 脚本）。 */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 准星可选中（隐形实体不覆写就永远点不到）。 */
    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return false;
    }

    /**
     * 右键这一版不拦（返回 PASS 走原版）；客户端回 SUCCESS 让原版发包（与服务端分工同棋子）。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitLocation) {
        return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** 工厂：宿主 place_card 用（pos = 牌中心，yRot = 牌面朝向）。 */
    public static CardEntity place(ServerLevel level, double x, double y, double z, float yRot,
            String art, String back, float scale) {
        CardEntity e = new CardEntity(com.tablegame.TableGame.CARD_ENTITY.get(), level);
        e.setPos(x, y, z);
        e.setYRot(yRot);
        e.yRotO = yRot;                                      // 初值同步，避免首帧插值抖动
        e.setCard(art, back, scale);
        level.addFreshEntity(e);
        return e;
    }
}
