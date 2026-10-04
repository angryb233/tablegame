package com.tablegame;

import com.tablegame.drawboard.ClientBoardHandler;
import com.tablegame.piece.GamePieceEntityRenderer;
import com.tablegame.piece.GamePieceSpecialRenderer;
import com.tablegame.piece.ModelMakerScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import com.tablegame.block.SeatRenderer;
import com.tablegame.card.CardEntityRenderer;
import com.tablegame.piece.ModelMakerClient;

// 客户端专用类：只在客户端（单人游戏/联机客户端）加载，专用服务器（Dedicated Server）不会加载。
// 所以这里可以放心使用 net.minecraft.client 下面的代码（如 Minecraft.getInstance()）。
@Mod(value = TableGame.MODID, dist = Dist.CLIENT)
// @EventBusSubscriber = 自动把本类里所有带 @SubscribeEvent 的静态方法注册到事件总线，
// 不用手动 register。value = Dist.CLIENT 表示只在客户端生效。
@EventBusSubscriber(modid = TableGame.MODID, value = Dist.CLIENT)
public class TableGameClient {
    // 客户端初始化事件（进入主菜单之前触发），只在客户端执行。
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 注册座位实体的隐形渲染器：客户端必须为每个实体类型提供渲染器，
        // 否则加载该实体时报错崩溃。SeatRenderer 什么都不画（详见其注释）。
        EntityRenderers.register(TableGame.SEAT.get(), SeatRenderer::new);

        // 注册棋子实体渲染器：把隐形承载体画成体素棋子
        //（逐体素提交复用 GamePieceSpecialRenderer，详见其注释）。
        EntityRenderers.register(TableGame.GAME_PIECE_ENTITY.get(), GamePieceEntityRenderer::new);
        EntityRenderers.register(TableGame.CARD_ENTITY.get(), com.tablegame.card.CardEntityRenderer::new);

        TableGame.LOGGER.info("HELLO FROM CLIENT SETUP");
        TableGame.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    // 容器 GUI 注册：MenuType → Screen 的绑定。
    // 服务端 openMenu 后客户端收到打开包，原版按这张表 new 对应 Screen。
    // Screen 构造签名统一是 (Menu, Inventory, Component)。
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TableGame.MODEL_MAKER_MENU.get(), ModelMakerScreen::new);
    }

    // 客户端载荷处理器注册（26.x 新事件：与服务端隔离，防止客户端代码进专用服务器 classpath）
    @SubscribeEvent
    static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
        ClientBoardHandler.register(event);
        com.tablegame.piece.ModelMakerClient.register(event);   // 蓝图库 S2C
        // 游戏台的台状态 S2C 在 ClientGameHandler.register 里统一注册（开屏 + 就地刷新的唯一入口）
    }

    // 特殊物品渲染器注册：棋子物品栏 3D 渲染。
    // 注册 "tablegame:game_piece" → GamePieceSpecialRenderer.Unbaked；
    // 之后任何 items/*.json 里写 {"type":"minecraft:special","model":{"type":"tablegame:game_piece"}}
    // 的物品模型都会用它渲染（原版盾牌/床同机制）。
    @SubscribeEvent
    static void onRegisterSpecialRenderers(net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent event) {
        event.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                TableGame.MODID, "game_piece"), GamePieceSpecialRenderer.Unbaked.CODEC);
    }
}