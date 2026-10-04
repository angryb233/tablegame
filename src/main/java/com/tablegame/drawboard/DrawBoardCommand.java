package com.tablegame.drawboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /tablegame 指令树：
 * <ul>
 *   <li>{@code /tablegame drawboard} —— 弹画板主菜单（新建/打开/打开文件夹）
 *   <li>{@code /tablegame group create/invite/join/leave/rule/readonly/admin/kick/disband/list <…>} —— 组管理
 *       管理员=组长任命的协管（可邀请/踢普通成员/设只读）；解散须组内无项目
 * </ul>
 * 指令只做参数搬运，校验与持久化在 {@link BoardManager}（服务端主线程）。
 */
public class DrawBoardCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("tablegame")
                .then(literal("drawboard")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            // 客户端收到后弹出主菜单（新建/打开/打开文件夹）
                            PacketDistributor.sendToPlayer(player, new BoardPackets.OpenMenuPayload());
                            return 1;
                        }))
                .then(literal("group")
                        .executes(ctx -> {   // /tablegame group（无子指令）= 弹组管理 GUI
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BoardManager bm = BoardManager.get();
                            if (bm != null) bm.sendGroupUi(player);
                            return 1;
                        })
                        .then(literal("create")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, (bm, p) -> bm.createGroup(p, s(ctx, "name"))))))
                        .then(literal("invite")
                                .then(argument("group", StringArgumentType.word())
                                        .then(argument("player", StringArgumentType.word())
                                                .executes(ctx -> run(ctx, (bm, p) -> bm.inviteGroup(p, s(ctx, "group"), s(ctx, "player")))))))
                        .then(literal("join")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, (bm, p) -> bm.joinGroup(p, s(ctx, "name"))))))
                        .then(literal("leave")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, (bm, p) -> bm.leaveGroup(p, s(ctx, "name"))))))
                        .then(literal("rule")
                                .then(argument("group", StringArgumentType.word())
                                        .then(literal("open")
                                                .executes(ctx -> run(ctx, (bm, p) -> bm.setGroupOpen(p, s(ctx, "group"), true))))
                                        .then(literal("invite")
                                                .executes(ctx -> run(ctx, (bm, p) -> bm.setGroupOpen(p, s(ctx, "group"), false))))))
                        .then(literal("readonly")
                                .then(argument("group", StringArgumentType.word())
                                        .then(argument("player", StringArgumentType.word())
                                                .then(literal("true")
                                                        .executes(ctx -> run(ctx, (bm, p) -> bm.setReadOnly(p, s(ctx, "group"), s(ctx, "player"), true))))
                                                .then(literal("false")
                                                        .executes(ctx -> run(ctx, (bm, p) -> bm.setReadOnly(p, s(ctx, "group"), s(ctx, "player"), false)))))))
                        .then(literal("admin")
                                .then(argument("group", StringArgumentType.word())
                                        .then(argument("player", StringArgumentType.word())
                                                .then(literal("true")
                                                        .executes(ctx -> run(ctx, (bm, p) -> bm.setGroupAdmin(p, s(ctx, "group"), s(ctx, "player"), true))))
                                                .then(literal("false")
                                                        .executes(ctx -> run(ctx, (bm, p) -> bm.setGroupAdmin(p, s(ctx, "group"), s(ctx, "player"), false)))))))
                        .then(literal("kick")
                                .then(argument("group", StringArgumentType.word())
                                        .then(argument("player", StringArgumentType.word())
                                                .executes(ctx -> run(ctx, (bm, p) -> bm.kickMember(p, s(ctx, "group"), s(ctx, "player")))))))
                        .then(literal("disband")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, (bm, p) -> bm.disbandGroup(p, s(ctx, "name"))))))
                        .then(literal("list")
                                .executes(ctx -> run(ctx, BoardManager::listMyGroups)))));
    }

    /** 取字符串参数。 */
    private static String s(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    /** 统一执行壳：取玩家 → 取管理器 → 调组操作。 */
    private static int run(CommandContext<CommandSourceStack> ctx, GroupOp op) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BoardManager bm = BoardManager.get();
        if (bm != null) op.run(bm, player);
        return 1;
    }

    @FunctionalInterface
    private interface GroupOp {
        void run(BoardManager bm, ServerPlayer player);
    }
}
