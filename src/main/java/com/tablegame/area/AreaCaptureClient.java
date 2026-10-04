package com.tablegame.area;

import com.tablegame.TableGame;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.net.AreaCapturePackets;

/**
 * 区域捕获的客户端：线框渲染 + 右键定角 + Shift+Enter 确认。
 * 整套动作以「手里拿着那枚区域工具」为前提：拿着才画状态条 / 拦右键 / Shift+Enter 生效；不拿就整段走原版交互，并在下一 tick 自动退出模式（见 {@link #onClientTick}）。
 * 选角账在服务端（AreaCaptureServer），客户端只记一份镜像画线框，画框零延迟。
 * 右键定角走客户端发包（拦 {@code InputEvent.InteractionKeyMappingTriggered}），免得触发原版交互（如开箱子）；服务端只管记账。
 */
@EventBusSubscriber(modid = TableGame.MODID, value = Dist.CLIENT)
public final class AreaCaptureClient {
    private AreaCaptureClient() {}

    /** 捕获会话（客户端镜像）：给哪个游戏哪个区域 + 两角。 */
    private static String game, area;
    private static net.minecraft.core.BlockPos corner1, corner2;
    private static boolean active;
    /** 进模式后已经拿到过那枚工具了吗 —— 拿到之前不判「不拿就退出」（工具要等一个网络往返）。 */
    private static boolean armed;
    /** 等工具到手的 tick 数：超时兜底（服务端没给 = 权限不够 / 档不对）。 */
    private static int waitTicks;

    // 线框颜色：淡蓝（选角中）/ 绿（两角齐，可确认）。
    private static final int COLOR_SELECT = ARGB.colorFromFloat(1.0F, 0.45F, 0.75F, 1.0F);
    private static final int COLOR_READY = ARGB.colorFromFloat(1.0F, 0.4F, 1.0F, 0.4F);

    private static final float LINE_WIDTH = 3.0F;

    /** 进入捕获模式（世界页签「选择区域」时调）：关编辑器屏、记会话、提示操作。 */
    public static void begin(String game, String area) {
        AreaCaptureClient.game = game;
        AreaCaptureClient.area = area;
        corner1 = null;
        corner2 = null;
        active = true;
        armed = false;                                 // 工具还在服务端手里：先别判「不拿就退出」
        waitTicks = 0;
        Minecraft.getInstance().setScreen(null);   // 关编辑器屏，回世界（提示看屏幕上方那条 SEEK_BANNER）
    }

    // ===== 屏幕上方常驻状态条（不在聊天栏弹提示）=====

    /** 挂在屏幕上方中间的一条选取状态（恒注册的 GuiLayer，不处于选取模式就不画）。 */
    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(TableGame.MODID, "area_capture"),
                AreaCaptureClient::drawBanner);
    }

    private static void drawBanner(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!active || !holding()) return;          // 手里没拿着工具 = 这一帧什么也不画（走原版观感）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var font = mc.font;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;

        String l1 = "世界选取模式 · " + game + " / " + area;
        String l2 = "角1 " + posText(corner1) + "    角2 " + posText(corner2)
                + (corner1 != null && corner2 != null ? "    尺寸 " + sizeText() : "");
        String l3 = "右键定角1 · Shift+右键定角2 · Shift+Enter 确认";

        int w = Math.max(font.width(l1), Math.max(font.width(l2), font.width(l3))) + 16;
        int top = 6;
        g.fill(cx - w / 2, top, cx + w / 2, top + 42, 0x90101018);
        g.outline(cx - w / 2, top, w, 42, 0xFF5A6070);
        g.centeredText(font, net.minecraft.network.chat.Component.literal(l1), cx, top + 5, 0xFFFFFFFF);
        g.centeredText(font, net.minecraft.network.chat.Component.literal(l2), cx, top + 17, 0xFFC8D0E0);
        g.centeredText(font, net.minecraft.network.chat.Component.literal(l3), cx, top + 29, 0xFF9098A8);
    }

    private static String posText(net.minecraft.core.BlockPos p) {
        return p == null ? "（未定）" : p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static String sizeText() {
        var box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(corner1, corner2);
        return box.getXSpan() + "×" + box.getYSpan() + "×" + box.getZSpan();
    }


    public static boolean active() {
        return active;
    }

    /** 手里拿着**这一场**（选取 + 这款游戏 + 这条区域）的区域工具吗 —— 决定画不画条、拦不拦手势。 */
    private static boolean holding() {
        return AreaToolClient.holding(AreaToolKit.CAPTURE, game, area);
    }

    /** 背包里还有这枚工具吗 —— 决定**模式还在不在**（换手不算结束，丢掉 / 被收走才算）。 */
    private static boolean hasTool() {
        return AreaToolClient.inInventory(AreaToolKit.CAPTURE, game, area);
    }

    /** 取消：清镜像，发取消包让服务端也清账。 */
    private static void cancel() {
        active = false;
        corner1 = null;
        corner2 = null;
        ClientPacketDistributor.sendToServer(new AreaCapturePackets.CancelPayload());
    }

    // ===== 线框：每帧画当前选区 =====

    @SubscribeEvent
    static void onRegisterDebugRenderers(RegisterDebugRenderersEvent event) {
        event.register((camX, camY, camZ, debug, frustum, partialTick) -> emitWireframe());
    }

    private static void emitWireframe() {
        if (!active || !holding() || corner1 == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (corner2 == null) {
            Gizmos.cuboid(new AABB(corner1).inflate(0.1),
                    GizmoStyle.stroke(COLOR_SELECT, LINE_WIDTH));
            return;
        }
        BoundingBox box = BoundingBox.fromCorners(corner1, corner2);
        AABB aabb = new AABB(
                box.minX() - 0.02, box.minY() - 0.02, box.minZ() - 0.02,
                box.maxX() + 1.02, box.maxY() + 1.02, box.maxZ() + 1.02);
        Gizmos.cuboid(aabb, GizmoStyle.stroke(COLOR_READY, LINE_WIDTH));
    }

    // ===== 键盘：Shift+Enter 确认 =====

    @SubscribeEvent
    static void onKey(InputEvent.Key event) {
        if (!active || event.getAction() != 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!holding()) return;                            // 手里没拿着工具 = 这一下不归模式管
        boolean shift = (event.getModifiers() & 1) != 0;      // GLFW_MOD_SHIFT
        if (!shift) return;
        if (event.getKey() == 257 /* ENTER */) {
            if (corner1 != null && corner2 != null) {
                ClientPacketDistributor.sendToServer(
                        new AreaCapturePackets.ConfirmPayload(game, area));
                active = false;                                // 确认后服务端回执刷新编辑器
                corner1 = corner2 = null;
            }
        }
    }

    /**
     * 每客户端 tick：手里没拿着工具就退出模式（换手 / 丢掉 / 拿了别的物品），并发 Cancel 清服务端选角账。
     * 这是「不手持时走原版交互」的落实处，也是唯一的退出方式。
     *
     * <p>⚠ 判定用「背包里有没有」而非「手里拿没拿」（换手不结束，丢掉才算）；⚠ 但拿到工具前不能判 ——
     * 工具要等一个网络往返，那期间一判就当场自杀（被弹回总览）。先等 {@code armed}，等不到就超时收场。
     */
    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!active) return;
        if (!armed) {
            if (hasTool()) {
                armed = true;                  // 工具到手了：从这里开始「不在背包 = 结束」才作数
                return;
            }
            if (++waitTicks > 60) cancel();    // 3 秒还没到手 → 服务端没给，收场别挂着
            return;
        }
        if (!hasTool()) cancel();              // 工具**离开背包**（丢掉 / 被服务端收走）= 模式结束；换手不算
    }

    // ===== 右键定角：拦原版交互 + 发包（服务端记账裁决） =====

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active || !holding()) return;                 // 手里没拿着工具 = 右键照原版走
        // ⚠ 这个事件对「攻击 / 使用 / 拾取方块」三种键映射都触发，不判就左键也会定角。
        if (!event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // 只拦「攻击键映射触发的使用」（右键=use）。Miss = 对空气（同蓝图 use 的 pick 逻辑）。
        var hit = mc.player.pick(5.0, 1.0F, false);
        // 定角位置 = 你点中的那一格方块（整数，六个面一致），别拿命中点浮点坐标 floor：
        // 命中点带 ε（south 面 z 本该 91.0，算出来 90.99999999999999），floor 会掷骰子 —— 位置跑到隔壁那格。
        var pos = hit instanceof net.minecraft.world.phys.BlockHitResult bh
                ? bh.getBlockPos()                                        // 真命中：点中的那格（整数，六个面一致）
                : net.minecraft.core.BlockPos.containing(hit.getLocation());   // 打空：保持原样（射线末端那格）
        boolean shift = mc.player.isSecondaryUseActive();
        if (shift) {
            if (corner1 == null) return;                       // 没角1时 Shift+右键不拦（放行潜行交互）
            corner2 = pos;                                     // 尺寸不判（不设上限）
        } else {
            corner1 = pos;
            corner2 = null;                                    // 重定角1 = 角2 作废
        }
        // 把这次的角同步给服务端记账（提示全在状态条上）。
        ClientPacketDistributor.sendToServer(new AreaCapturePackets.CornerPayload(
                pos.getX(), pos.getY(), pos.getZ(), shift));
        event.setCanceled(true);                               // 吃掉原版交互（防开箱子等误操作）
    }
}
