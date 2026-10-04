package com.tablegame.piece;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.function.Consumer;

/**
 * 棋子物品（tablegame:game_piece）。本体是「数据载体」：PieceData 组件决定它是什么棋子（模型制作器产出，
 * 也可用指令 set 组件）；注册时 stacksTo(16)。
 * 世界内放置：已锁定的棋子右键方块表面 → 服务端在点击面上生成 {@link GamePieceEntity} 隐形承载体
 * （体素由实体渲染器画，不放置真实方块）；Shift+右键棋子实体可取回。
 * 悬停提示：已锁定（有体素）→ 绿字显示尺寸；空数据 → 灰字提示未制作；26.x 新签名 appendHoverText(...)，
 * 自定义行无条件 out.accept(...)。
 */
public class GamePieceItem extends Item {

    public GamePieceItem(Properties props) {
        super(props);
    }

    // ===== 右键方块表面：放置棋子 =====

    /**
     * 双端分工：客户端只判状态返回 SUCCESS（原版据此把交互发包到服务端）；
     * 改世界/生成实体的逻辑全在服务端分支，服务端回 SUCCESS_SERVER。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (level.isClientSide()) {
            // 客户端：已锁定的棋子才响应右键 → SUCCESS 让原版发包给服务端
            return PieceData.get(stack).isLocked() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel) || player == null) {
            return InteractionResult.PASS;
        }
        PieceData data = PieceData.get(stack);
        if (!data.isLocked() || data.voxels() == null) {
            // 未制作（空蓝图数据）：右键无反应
            return InteractionResult.PASS;
        }

        // ===== 计算落点：直接用原版命中点（就在被点击的表面上）=====
        // 用原版命中点（raytrace 给的点就在被点击表面上，任何形状/表面都对），沿法线偏 0.01 防 z-fighting。
        // （手写六向 switch 会把不完整方块当整格算、侧面差一格。）
        Direction face = context.getClickedFace();
        net.minecraft.world.phys.Vec3 hit = context.getClickLocation();
        double x = hit.x + face.getStepX() * 0.01;
        double y = hit.y + face.getStepY() * 0.01;
        double z = hit.z + face.getStepZ() * 0.01;

        // ===== 放置朝向（原版头颅式）=====
        // 地面/顶面：按玩家水平视线 16 档（原版头颅同款 round(yRot/22.5)*22.5）；
        // 墙面：背贴墙（-face.getOpposite().toYRot()）。
        float yRot;
        if (face.getAxis() == Direction.Axis.Y) {
            // 去掉负号：实体 yRot 与玩家视线同语义
            yRot = Math.round(player.getYRot() / 22.5F) * 22.5F;
        } else {
            yRot = -face.getOpposite().toYRot();
        }

        GamePieceEntity.place(serverLevel, x, y, z, yRot, stack.copyWithCount(1));

        // 消耗物品：创造模式不扣（原版放置方块同规则）
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        // 放置音效：盔甲架放置声（方块类质感，音量小不吵）
        level.playSound(null, x, y, z, SoundEvents.ARMOR_STAND_PLACE, SoundSource.PLAYERS, 0.7f, 1.0f);
        return InteractionResult.SUCCESS_SERVER;
    }

    // ===== 悬停提示：一眼看出棋子尺寸 / 未制作 =====

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display,
            Consumer<Component> out, TooltipFlag flag) {
        PieceData data = PieceData.get(stack);
        if (data.isLocked() && data.voxels() != null) {
            PieceData.VoxelData v = data.voxels();
            out.accept(Component.translatable("tooltip.tablegame.piece.size",
                    v.sizeX(), v.sizeY(), v.sizeZ()).withStyle(ChatFormatting.GREEN));
        } else {
            out.accept(Component.translatable("tooltip.tablegame.piece.empty")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
