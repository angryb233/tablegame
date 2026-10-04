package com.tablegame.area;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;


/**
 * 区域捕获的服务端账本 ——「谁在给哪个游戏的哪个区域选角」。
 * 账记在服务端 Map（跟着人走，与手里拿什么无关）；玩家断线 = 账里没人 = 自动退出捕获。
 * 裁决全在服务端：定角（拦对方块右键）、确认（校验两角）、写档（唯一真源 GameStore）。
 */
public final class AreaCaptureServer {
    private AreaCaptureServer() {}

    /** 一份进行中的选角。 */
    public record Sel(BlockPos corner1, BlockPos corner2) {
        public boolean hasTwo() {
            return corner2 != null;
        }
    }

    /** 玩家 UUID → 选角状态。 */
    private static final Map<java.util.UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 一场捕获会话：给哪个游戏哪个区域选角。 */
    public record Session(String game, String area, Sel sel) {}


    /**
     * 进入捕获模式（关屏回世界由客户端自己处理——它发的这个包就代表它要回世界）。
     * 例行提示（怎么操作 / 两角 / 尺寸）不发聊天栏，客户端有常驻状态条承担；
     * 服务端只报「拒绝」类的话（游戏/区域不存在、先定角1）。
     */
    public static void begin(ServerPlayer sp, String game, String area) {
        SESSIONS.put(sp.getUUID(), new Session(game, area, null));
    }

    /** 定/重定角1。 */
    public static void corner1(ServerPlayer sp, BlockPos pos) {
        Session s = SESSIONS.get(sp.getUUID());
        if (s == null) return;
        SESSIONS.put(sp.getUUID(), new Session(s.game(), s.area(), new Sel(pos, null)));
    }

    /** 定角2（须已有角1）。 */
    public static void corner2(ServerPlayer sp, BlockPos pos) {
        Session s = SESSIONS.get(sp.getUUID());
        if (s == null) return;
        if (s.sel() == null) {
            sp.sendSystemMessage(Component.literal("[世界] 先右键定角1"));
            return;
        }
        // 尺寸不判：盒子多大都收，捕获侧分 tick 慢慢收

        SESSIONS.put(sp.getUUID(), new Session(s.game(), s.area(), new Sel(s.sel().corner1(), pos)));
    }

    /** 当前选角（客户端线框请求用；null = 不在捕获或没角1）。 */
    public static Session sessionOf(ServerPlayer sp) {
        return SESSIONS.get(sp.getUUID());
    }

    /** 结束（确认成功 / 取消 / 玩家退出时）。 */
    public static Session end(java.util.UUID uuid) {
        return SESSIONS.remove(uuid);
    }

    /** 玩家退出：清账（残留会话无害但没必要留）。 */
    public static void drop(java.util.UUID uuid) {
        SESSIONS.remove(uuid);
    }
}
