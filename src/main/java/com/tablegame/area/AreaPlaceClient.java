package com.tablegame.area;

import com.tablegame.TableGame;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.GameDefinition;
import com.tablegame.net.AreaPlacePackets;

/**
 * 区域落地的客户端：摆放模式。
 *
 * <p>交互（照投影「先放置 → 自由观看 → 再确定」）：幽灵跟准星走 → 右键 = 放置（钉住，可走开观看；右键别处 = 换位）
 * → Shift+Enter = 落地。整套动作以「手里拿着那枚区域工具」为前提：不拿即整段走原版交互，下一 tick 退出模式。
 *
 * <p>幽灵外观与落点动画在 {@link AreaGhostRenderer}、外框由 {@link Gizmos} 画。⚠ 区域不旋转（后期【世界】
 * 【区域】拿区域当坐标基准，旋转会搅乱口径）。
 */
@EventBusSubscriber(modid = TableGame.MODID, value = Dist.CLIENT)
public final class AreaPlaceClient {
    private AreaPlaceClient() {}

    private static final double REACH = 5.0;


    /** 外框：淡青细线（一眼分清「这是预览，不是已经放下的方块」）。 */
    private static final int COLOR_OUTLINE = ARGB.colorFromFloat(1.0F, 0.45F, 1.0F, 0.55F);
    private static final float LINE_WIDTH = 2.5F;

    private static String game;
    private static String mode;                 // "none" / "all" / "non_air"（原样回服务端）
    private static String modeName;             // 提示条上给人看的
    private static GameDefinition.AreaDef area;
    private static boolean pinned;              // 已「放置」：不再跟准星，可自由观看
    private static BlockPos origin;             // 目标落点（区域最小角，= 准星指的那格 / 上次放置的位置）
    private static boolean active;
    /** 进模式后**已经拿到过那枚工具**了吗 —— 拿到之前不判「不拿就退出」（工具要等一个网络往返才到手）。 */
    private static boolean armed;
    /** 进模式后等工具到手的 tick 数：超时兜底（服务端没给 = 权限不够 / 档不对，别把模式挂在那儿）。 */
    private static int waitTicks;

    /** 进入摆放模式（世界页右键「摆放（…）」时调）：关编辑器屏、烘预览（交给渲染器）、记会话。 */
    public static void begin(String game, GameDefinition.AreaDef area, String mode) {
        AreaPlaceClient.game = game;
        AreaPlaceClient.area = area;
        AreaPlaceClient.mode = mode;
        AreaPlaceClient.modeName = switch (mode == null ? "" : mode) {
            case "all" -> "全覆盖（空气也清场）";
            case "move" -> "移动（落完清原处）";
            case "non_air" -> "空气不覆盖";
            default -> "不动已有方块";
        };
        active = true;
        armed = false;                                 // 工具还在服务端手里：先别判「不拿就退出」
        waitTicks = 0;
        pinned = false;
        origin = null;
        BlockPos at = aimOrigin(Minecraft.getInstance());
        if (at == null && Minecraft.getInstance().player != null) {
            at = Minecraft.getInstance().player.blockPosition();
        }
        if (at != null) {
            origin = at;
            AreaGhostRenderer.show(area, at.getX(), at.getY(), at.getZ());   // 烘一份 + 落点瞬移到位
        }
        Minecraft.getInstance().setScreen(null);            // 关编辑器屏，回世界
        // 报一声「我进摆放模式了」：服务端据此发那枚区域工具（拿着它才在模式里；权限闸也在那边）
        ClientPacketDistributor.sendToServer(new AreaPlacePackets.ToolPayload(
                game, area.id(), mode == null ? "" : mode, "begin"));
    }

    /** 手里拿着**这一场**（摆放 + 这款游戏 + 这条区域）的区域工具吗 —— 决定画不画条、拦不拦手势。 */
    private static boolean holding() {
        return AreaToolClient.holding(AreaToolKit.PLACE, game, area == null ? "" : area.id());
    }

    /** 背包里还有这枚工具吗 —— 决定**模式还在不在**（换手不算结束，丢掉 / 被收走才算）。 */
    private static boolean hasTool() {
        return AreaToolClient.inInventory(AreaToolKit.PLACE, game, area == null ? "" : area.id());
    }

    public static boolean active() {
        return active;
    }

    /** 动画中的显示落点（外框线框用它，与幽灵永远同步 —— 真值在渲染器里）。 */
    public static Vec3 ghostPos() {
        return AreaGhostRenderer.pos();
    }


    /** 收摊：清预览 + 清会话（落地、取消、换手、退出世界都走它），并让服务端把那枚工具收走。 */
    private static void stop() {
        boolean was = active;                               // 只在「本来真在模式里」时报一声（退出世界那条路别乱发包）
        active = false;
        pinned = false;
        origin = null;
        AreaGhostRenderer.hide();
        if (was && game != null && area != null) {
            ClientPacketDistributor.sendToServer(new AreaPlacePackets.ToolPayload(
                    game, area.id(), mode == null ? "" : mode, "end"));
        }
    }

    // ===== 屏幕上方常驻状态条（与捕获同款：不在聊天栏弹提示）=====

    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(TableGame.MODID, "area_place"),
                AreaPlaceClient::drawBanner);
    }

    private static void drawBanner(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!active || area == null || !holding()) return;   // 手里没拿着工具 = 这一帧什么也不画
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var font = mc.font;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;

        String l1 = "区域摆放 · " + game + " / " + area.id()
                + "（" + area.sizeX() + "×" + area.sizeY() + "×" + area.sizeZ() + "）"
                + (pinned ? "  ·  已放置" : "  ·  选位置中");
        String l2 = "落点（最小角）" + (origin == null ? "（准星没对着方块）"
                : origin.getX() + ", " + origin.getY() + ", " + origin.getZ())
                + "    覆盖：" + modeName;
        String l3 = pinned
                ? "可以走开看 · 右键换个位置 · Shift+Enter 落地"
                : "右键放置预览 · Shift+Enter 直接落地";

        int w = Math.max(font.width(l1), Math.max(font.width(l2), font.width(l3))) + 16;
        int top = 6;
        g.fill(cx - w / 2, top, cx + w / 2, top + 42, 0x90101018);
        g.outline(cx - w / 2, top, w, 42, pinned ? 0xFF5A7A60 : 0xFF5A6070);
        g.centeredText(font, Component.literal(l1), cx, top + 5, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(l2), cx, top + 17, pinned ? 0xFFB8E8C0 : 0xFFC8D0E0);
        g.centeredText(font, Component.literal(l3), cx, top + 29, 0xFF9098A8);
    }

    // ===== 外框（每帧）：幽灵由 AreaGhostRenderer 画，这里只画「这是预览」的圈 =====

    @SubscribeEvent
    static void onRegisterDebugRenderers(RegisterDebugRenderersEvent event) {
        event.register((camX, camY, camZ, debug, frustum, partialTick) -> emitOutline());
    }

    private static void emitOutline() {
        if (!active || !holding() || area == null || origin == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!pinned) {
            BlockPos aim = aimOrigin(mc);
            if (aim != null) {                              // 目标跟着准星；显示位置由动画追上去
                origin = aim;
                AreaGhostRenderer.moveTo(aim.getX(), aim.getY(), aim.getZ());
            }
        }
        Vec3 at = AreaGhostRenderer.pos();
        int sx = area.sizeX(), sy = area.sizeY(), sz = area.sizeZ();
        Gizmos.cuboid(new AABB(
                at.x - 0.02, at.y - 0.02, at.z - 0.02,
                at.x + sx + 0.02, at.y + sy + 0.02, at.z + sz + 0.02),
                GizmoStyle.stroke(COLOR_OUTLINE, LINE_WIDTH));
    }

    /** 准星指到的那格（含方块；指不到东西就 null）。 */
    private static BlockPos aimOrigin(Minecraft mc) {
        if (mc.player == null) return null;
        var hit = mc.player.pick(REACH, 1.0F, false);
        if (hit == null || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return null;
        // 同上：要的是「点中的那格」（整数），别拿命中点浮点坐标 floor（正向面上会随 ε 落到隔壁）
        return hit instanceof net.minecraft.world.phys.BlockHitResult bh
                ? bh.getBlockPos() : BlockPos.containing(hit.getLocation());
    }

    // ===== 右键 = 放置（钉住，之后可自由走动观看；再右键换位置）=====

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active || !holding()) return;       // 手里没拿着工具 = 右键照原版走
        if (!event.isUseItem()) return;          // ⚠ 这个事件对攻击/使用/拾取都触发
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        BlockPos aim = aimOrigin(mc);
        if (aim != null) {                       // 动画会滑过去（就位动画）
            origin = aim;
            AreaGhostRenderer.moveTo(aim.getX(), aim.getY(), aim.getZ());
        }
        pinned = true;
        event.setCanceled(true);                 // 吃掉原版交互（别在选位置时开了箱子 / 放下手里方块）
    }

    // ===== 键盘：Shift+Enter 落地 =====

    @SubscribeEvent
    static void onKey(InputEvent.Key event) {
        if (!active || event.getAction() != 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!holding()) return;                                 // 手里没拿着工具 = 这一下不归模式管
        if ((event.getModifiers() & 1) == 0) return;            // GLFW_MOD_SHIFT
        if (event.getKey() != 257 /* ENTER */) return;
        if (origin == null) return;                             // 没定落点就不发
        ClientPacketDistributor.sendToServer(new AreaPlacePackets.PlacePayload(
                game, area.id(), origin.getX(), origin.getY(), origin.getZ(), mode));
        stop();                                                 // 真方块马上顶上来（服务端分相写）
    }

    /**
     * 每客户端 tick：手里没拿着工具就退出模式（换手 / 丢掉 / 拿了别的物品），退出走 {@link #stop}。
     *
     * <p>⚠ 但「拿到工具之前」不能判：工具要等服务端收到请求才发，有一个网络往返 —— 那期间一判就当场退出
     * （回执没人接 ⇒ 被弹回总览）。故先等 {@code armed}（见过一次工具）再谈「不拿就退出」，等不到就超时收场。
     */
    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!active) return;
        if (!armed) {
            if (hasTool()) {
                armed = true;                  // 工具到手了：从这里开始「不在背包 = 结束」才作数
                return;
            }
            if (++waitTicks > 60) stop();      // 3 秒还没到手 → 服务端没给，收场别挂着
            return;
        }
        if (!hasTool()) stop();                // 工具**离开背包**（丢掉 / 被收走）= 模式结束；换手不算
    }
}
