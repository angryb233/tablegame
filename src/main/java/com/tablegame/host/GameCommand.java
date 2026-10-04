package com.tablegame.host;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.tablegame.drawboard.BoardManager;
import com.tablegame.piece.ModelMakerPackets;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import com.tablegame.net.GamePackets;

/**
 * /tablegame 指令树：
 * <ul>
 *   <li>{@code games} —— 弹编辑器入口屏（先发空列表包把屏开起来，再发真数据）
 *   <li>{@code edit <游戏项目>} —— 直接进那个项目的编辑器（与列表里点【打开】走同一处）
 *   <li>{@code pieces} —— 弹蓝图库菜单
 *   <li>{@code start <游戏名>} —— 开局（引擎 v0 运行时入口；开局的人 = 自己 + 16 格内玩家）
 *   <li>{@code draw} —— 打开本局的临时画板（不落盘）
 *   <li>{@code ui} —— 打开全屏舞台界面（Esc 关回 HUD）
 *   <li>{@code abort} —— 中断自己所在的那一局
 *   <li>{@code pick <标记>} —— 文本对象的点击回投：玩家点 say_mark 那条消息时客户端跑它 → 跑 {@code on pick}
 * </ul>
 *
 * <p>⚠ Brigadier 的 {@code greedyString()} 只能当末位参数（游戏名可能有空格），所以它排最后；开局/输入/中断都在服务端直接处理。
 */
public class GameCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("tablegame")
                .then(literal("games")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            // 先弹屏（客户端收到列表包时屏已开着，路由就地刷新/开菜单）
                            PacketDistributor.sendToPlayer(player, new GamePackets.GamesListPayload(java.util.List.of()));
                            GameManager gm = GameManager.get();
                            if (gm != null) gm.sendGamesList(player);
                            return 1;
                        }))
                .then(literal("edit")
                        .then(argument("游戏名", StringArgumentType.greedyString())
                                .executes(ctx -> {                           // edit <名>   直接进那个项目的编辑器
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    GameManager gm = GameManager.get();
                                    if (gm == null) {
                                        player.sendSystemMessage(Component.literal("[游戏] 服务端还没就绪"));
                                        return 0;
                                    }
                                    // 与游戏列表里点「打开」走**同一处**（读定义 → 发全文 → 客户端进编辑屏）
                                    gm.openGame(player, StringArgumentType.getString(ctx, "游戏名"));
                                    return 1;
                                })))
                .then(literal("pieces")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (BoardManager.get() == null) {
                                player.sendSystemMessage(Component.literal("[蓝图库] 未初始化"));
                                return 0;
                            }
                            ModelMakerPackets.requestBrowserList(player);
                            return 1;
                        }))
                // ===== 运行时（引擎 v0）=====
                .then(literal("start")
                        .then(argument("游戏名", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    HostManager hm = HostManager.get();
                                    if (hm == null) {
                                        player.sendSystemMessage(Component.literal("[对局] 服务端还没就绪"));
                                        return 0;
                                    }
                                    hm.start(player, StringArgumentType.getString(ctx, "游戏名"));
                                    return 1;
                                })))
                // 文本对象的点击回投：玩家点 say_mark 那条消息 → 客户端跑 "tablegame pick <标记>" →
                // 这里喂给现成的 acceptPick（与舞台 click 同一入口）：跑 on pick，内建值 pick = 标记（无空格，用 word）。
                .then(literal("pick")
                        .then(argument("标记", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    HostManager hm = HostManager.get();
                                    if (hm == null) return 0;
                                    hm.pickMark(player, StringArgumentType.getString(ctx, "标记"));
                                    return 1;
                                })))
                .then(literal("draw")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            HostManager hm = HostManager.get();
                            if (hm == null) return 0;
                            hm.openStageBoard(player);   // 打开本局的临时画板来画（不落盘）
                            return 1;
                        }))
                .then(literal("ui")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            HostManager hm = HostManager.get();
                            if (hm == null) return 0;
                            hm.ui(player, true);      // 打开全屏舞台界面（Esc 关回 HUD）
                            return 1;
                        }))
                // ===== 世界玩法（存档级）=====
                // 一个存档只服务一款游戏项目：开了后进这个存档 = 自动进这个玩法；绑定在存档里（<存档>/data/tablegame/world.json），要求 op 2 级（控制台/命令方块也能用）。
                .then(literal("world")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // 26.x：op 2 级（原版游戏主管）
                        .executes(ctx -> {                                   // world        看状态
                            HostManager hm = HostManager.get();
                            if (hm == null) return 0;
                            hm.worldStatus(ctx.getSource());
                            return 1;
                        })
                        .then(literal("off")
                                .executes(ctx -> {                           // world off    关掉
                                    HostManager hm = HostManager.get();
                                    if (hm == null) return 0;
                                    hm.worldDisable(ctx.getSource());
                                    return 1;
                                }))
                        .then(argument("游戏名", StringArgumentType.greedyString())
                                .executes(ctx -> {                           // world <名>   开 / 换绑
                                    HostManager hm = HostManager.get();
                                    if (hm == null) return 0;
                                    hm.worldEnable(ctx.getSource(), StringArgumentType.getString(ctx, "游戏名"));
                                    return 1;
                                })))
                .then(literal("abort")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            HostManager hm = HostManager.get();
                            if (hm == null) return 0;
                            hm.abort(player);
                            return 1;
                        })));
    }
}
