package com.tablegame.piece;

import com.tablegame.TableGame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
/**
 * 蓝图客户端：线框渲染 + Shift+Enter 确认（弹命名窗口）。
 * 线框：手持蓝图且选区有效时用 26.x 的 Gizmos 画选区边框；颜色随状态变（选角中淡蓝、未定角2只画角1小框、
 * 超限变红、已锁定不画），线宽 3.0f 加粗。
 * 确认键：Shift+Enter 不直接发包——先开命名窗口 {@link BlueprintNameScreen}，玩家输入棋子名后由窗口发包。
 */
@EventBusSubscriber(modid = TableGame.MODID, value = Dist.CLIENT)
public final class BlueprintClient {
    private BlueprintClient() {}

    // 淡蓝（Create 蓝图风）与超限红。ARGB.colorFromFloat(不透明度, r, g, b)
    private static final int COLOR_SELECT = ARGB.colorFromFloat(1.0F, 0.45F, 0.75F, 1.0F);
    private static final int COLOR_TOO_BIG = ARGB.colorFromFloat(1.0F, 1.0F, 0.25F, 0.25F);
    /** 线宽：默认 1.0 太细，3.0 加粗醒目。 */
    private static final float LINE_WIDTH = 3.0F;

    // ===== 注册常驻线框渲染器（NF 扩展口；注册进来的渲染器无条件每帧 emitGizmos） =====

    @SubscribeEvent
    static void onRegisterDebugRenderers(RegisterDebugRenderersEvent event) {
        event.register((camX, camY, camZ, debug, frustum, partialTick) -> emitWireframe());
    }

    // ===== 每帧：手持蓝图 → 画当前选区线框 =====

    private static void emitWireframe() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof BlueprintItem)) {
            stack = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!(stack.getItem() instanceof BlueprintItem)) {
                return;
            }
        }
        PieceData data = PieceData.get(stack);
        if (data.isLocked() || data.selection() == null) {
            return; // 已锁定不画；连角1都没有也不画
        }
        PieceData.Selection sel = data.selection();
        if (!sel.hasCorner2()) {
            // 只定了角1：画一个 0.2 格小立方提示这个点是角1
            BlockPos c1 = sel.corner1();
            Gizmos.cuboid(new AABB(c1).inflate(0.1),
                    GizmoStyle.stroke(COLOR_SELECT, LINE_WIDTH));
            return;
        }
        BoundingBox box = BoundingBox.fromCorners(sel.corner1(), sel.corner2OrNull());
        boolean tooBig = box.getXSpan() > PieceData.MAX_SIZE
                || box.getYSpan() > PieceData.MAX_SIZE
                || box.getZSpan() > PieceData.MAX_SIZE;
        int color = tooBig ? COLOR_TOO_BIG : COLOR_SELECT;
        // 选区外扩 0.02 格：避免线框与方块面 z-fighting（共面闪烁）
        AABB aabb = new AABB(
                box.minX() - 0.02, box.minY() - 0.02, box.minZ() - 0.02,
                box.maxX() + 1.02, box.maxY() + 1.02, box.maxZ() + 1.02);
        Gizmos.cuboid(aabb, GizmoStyle.stroke(color, LINE_WIDTH));
    }

    // ===== Shift+Enter → 打开命名窗口（由窗口的「确认」真正发捕获包） =====

    @SubscribeEvent
    static void onKey(InputEvent.Key event) {
        // 只处理「按下」动作（GLFW_PRESS=1），抬起/重复不处理
        if (event.getAction() != 1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return; // 不在游戏内 / 正开着界面（聊天栏等）时不拦截
        }
        if (event.getKey() == 257 /* GLFW_KEY_ENTER */
                && (event.getModifiers() & 1) != 0 /* GLFW_MOD_SHIFT */) {
            ItemStack main = mc.player.getMainHandItem();
            ItemStack off = mc.player.getOffhandItem();
            InteractionHand hand = main.getItem() instanceof BlueprintItem ? InteractionHand.MAIN_HAND
                    : off.getItem() instanceof BlueprintItem ? InteractionHand.OFF_HAND : null;
            if (hand != null && !PieceData.get(hand == InteractionHand.MAIN_HAND ? main : off).isLocked()) {
                mc.setScreen(new BlueprintNameScreen(hand));
            }
        }
    }
}