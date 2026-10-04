package com.tablegame.drawboard;

import com.tablegame.TableGame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.tablegame.net.HostPackets;

/**
 * 画板功能的客户端收包处理（只在物理客户端注册/加载）。
 *
 * <ul>
 *   <li>{@link BoardPackets.OpenMenuPayload}   → 弹主菜单（新建/打开/打开文件夹）
 *   <li>{@link BoardPackets.OpenBoardPayload}  → 打开/刷新编辑器（整板全量；新建成功、打开、只读都走这里）
 *   <li>{@link BoardPackets.BoardCellsPayload} → 增量（回显/他人笔迹/填充/撤销）
 *   <li>{@link BoardPackets.ProjectsListPayload} → 项目列表 → 打开列表界面
 *   <li>{@link BoardPackets.GroupsListPayload} / {@link BoardPackets.GroupsUiPayload} → 组列表/组管理界面
 * </ul>
 */
public final class ClientBoardHandler {
    private ClientBoardHandler() {}

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(BoardPackets.OpenMenuPayload.TYPE, ClientBoardHandler::onOpenMenu);
        event.register(BoardPackets.GroupsListPayload.TYPE, ClientBoardHandler::onGroupsList);
        event.register(BoardPackets.GroupsUiPayload.TYPE, ClientBoardHandler::onGroupsUi);
        event.register(BoardPackets.OpenBoardPayload.TYPE, ClientBoardHandler::onOpenBoard);
        event.register(BoardPackets.BoardCellsPayload.TYPE, ClientBoardHandler::onBoardCells);
        event.register(com.tablegame.net.HostPackets.StageBoardPayload.TYPE, ClientBoardHandler::onStageBoard);
        event.register(BoardPackets.ProjectsListPayload.TYPE, ClientBoardHandler::onProjectsList);
    }

    /** 局内临时板全量：只存缓存，不弹屏（HUD 每帧自己拉）。 */
    private static void onStageBoard(com.tablegame.net.HostPackets.StageBoardPayload p, IPayloadContext ctx) {
        StageBoardCache.setFull(p.key(), p.width(), p.height(), p.cells());
        TableGame.LOGGER.debug("[对局] 收到临时画板 {} ({}x{})", p.key(), p.width(), p.height());
    }

    private static void onOpenMenu(BoardPackets.OpenMenuPayload p, IPayloadContext ctx) {
        Minecraft.getInstance().setScreen(new DrawBoardMenuScreen());
    }

    private static void onGroupsList(BoardPackets.GroupsListPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        // 只有主菜单会发 RequestGroups；收到即带着组列表进新建界面（cardMode = 制作卡牌入口，预填 30×42）
        if (mc.screen instanceof DrawBoardMenuScreen menu) {
            mc.setScreen(new NewProjectScreen(menu, p.groups(), menu.cardMode));
        }
    }

    private static void onGroupsUi(BoardPackets.GroupsUiPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        // 路由：组管理界面已开着 → 就地刷新；否则（主菜单点按钮/指令进入）→ 打开组列表
        if (mc.screen instanceof GroupsScreen gs) {
            gs.applyData(p.mine(), p.joinable());
        } else if (mc.screen instanceof GroupDetailScreen gd) {
            gd.applyData(p.mine(), p.joinable());   // 组若已消失会自己退回列表
        } else {
            Screen prev = mc.screen instanceof DrawBoardMenuScreen m ? m : null;
            mc.setScreen(new GroupsScreen(prev, p.mine(), p.joinable()));
        }
    }

    private static void onOpenBoard(BoardPackets.OpenBoardPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DrawBoardScreen(p.key(), p.ownerName(), p.width(), p.height(), p.cells(), p.canEdit()));
        TableGame.LOGGER.info("[画板] 打开项目 {} ({}x{})", p.key(), p.width(), p.height());
    }

    private static void onBoardCells(BoardPackets.BoardCellsPayload p, IPayloadContext ctx) {
        StageBoardCache.applyCells(p.key(), p.cells());   // HUD 的 art 框：没开画板屏的人也要看到笔迹
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen instanceof DrawBoardScreen boardScreen && boardScreen.matches(p.key())) {
            boardScreen.applyCells(p.cells());
        }
    }

    private static void onProjectsList(BoardPackets.ProjectsListPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        Screen prev = mc.screen instanceof DrawBoardMenuScreen m ? m : new DrawBoardMenuScreen();
        mc.setScreen(new ProjectsScreen(prev, p.projects()));
    }
}
