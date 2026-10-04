package com.tablegame.stage;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.gui.GuiLayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.tablegame.core.GameDefinition;
import com.tablegame.host.ClientGameHandler;
import com.tablegame.net.GamePackets;

/**
 * 舞台 HUD：把编辑器里摆的「框」画到玩家屏幕上。
 * 用 HUD 层（{@link GuiLayer}）而不是全屏 Screen：HUD 渲染与 screen 无关，玩家开画板屏 / 聊天栏时舞台照常显示；
 * 代价是收不到鼠标事件（需要输入时走全屏 {@link StageScreen}，舞台同一份）。
 * 数据来自 {@link ClientGameHandler} 的快照（服务端已按可见性过滤）；舞台定义开局发一次，变量值每次变化发一次。
 * 位置/大小读 view 的 hudX/hudY（屏幕比例）夹进屏幕；画布 320×180，缩放上限 ≤45% 宽 / ≤35% 高。
 * 形态互斥：全屏界面开着时本层不画。
 */
public final class StageHudLayer implements GuiLayer {

    /** 上一次报给服务端的「看向格」（去抖：同格不重发）。 */
    private BlockPos lookSent;
    /** 上一次报的「看向的玩家名」（换了人也要发）。 */
    private String lookPlayerSent = "";

    /**
     * 「看向哪格 / 看向哪个玩家」（世界事件 {@code on look} 的客户端侧）。
     * 每帧跑；命中变了才发，视线离开（看天空 / 超距）就把去抖账清掉，免得看回来没反应。
     * 射线认方块与实体（{@code mc.hitResult} 谁近取谁）：指向玩家时带上他的名字，坐标照带。
     * 只报名字与坐标，方块 id 由服务端自己查（客户端报的不可信）。
     * ponytail：变化即发（扫视时每秒几个包）；要更省再加最小间隔。
     */
    private void reportLook() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof HitResult hr) || hr.getType() == HitResult.Type.MISS) {
            lookSent = null;
            lookPlayerSent = null;
            return;
        }
        // 命中格：方块命中 = 那格；实体命中 = 实体脚下那格（事件值 bx/by/bz 保持「格」的口径）
        BlockPos bp = hr.getType() == HitResult.Type.BLOCK
                ? ((BlockHitResult) hr).getBlockPos()
                : BlockPos.containing(((net.minecraft.world.phys.EntityHitResult) hr).getEntity().position());
        // 看向的玩家：实体命中且那实体是玩家 → 他的名字（26.x：getPlainTextName 直取）
        String lp = "";
        if (hr.getType() == HitResult.Type.ENTITY
                && ((net.minecraft.world.phys.EntityHitResult) hr).getEntity() instanceof net.minecraft.world.entity.player.Player pl) {
            lp = pl.getPlainTextName();
        }
        if (!bp.equals(lookSent) || !lp.equals(lookPlayerSent)) {
            lookSent = bp.immutable();
            lookPlayerSent = lp;
            ClientPacketDistributor.sendToServer(new GamePackets.LookPayload(bp.getX(), bp.getY(), bp.getZ(), lp));
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker dt) {
        reportLook();
        // HUD 承载的那块画布（脚本写了 screen hud 才有）—— 内容与全屏屏共用同一份渲染
        GameDefinition.StageView view = ClientGameHandler.boundView(true);
        GameDefinition.StageUi ui = view == null ? null : StageRenderer.uiOf(view);
        if (ui == null || ui.boxes().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.screen instanceof StageScreen) return;      // 全屏承载着这份舞台 → 不重复显示

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // 位置与大小：hudX/Y/W/H 都是屏幕比例（宿主从脚本 place(…) 派生，没写用缺省）——画面两轴各算各的填满板。
        int pw = Math.max(24, (int) Math.round(view.hudW() * sw));
        int ph = Math.max(24, (int) Math.round(view.hudH() * sh));
        int px = clamp((int) Math.round(view.hudX() * sw), 0, sw - pw);
        int py = clamp((int) Math.round(view.hudY() * sh), 0, sh - ph);
        double sx = pw / (double) ui.w();
        double sy = ph / (double) ui.h();

        // 板底：脚本 bg("…") 优先（可带透明度），没写用缺省淡黑（标出游戏屏幕边界）
        g.fill(px, py, px + pw, py + ph, ui.bgArgb());

        // 框由共享渲染器画（与全屏界面同一份实现；两轴缩放，文字不缩放）
        StageRenderer.draw(g, ui, px, py, sx, sy);
    }

    /** 夹进 [lo, hi]（hi < lo 时按 lo 算：窗口太小时别把画面推到屏幕外）。 */
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }

}
