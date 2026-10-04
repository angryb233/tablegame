package com.tablegame.host;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import com.tablegame.TableGame;
import com.tablegame.stage.StageHudLayer;

/** 游戏功能客户端引导：把 ClientGameHandler 的收包注册挂到客户端事件（模式同 TableGameClient）。 */
@EventBusSubscriber(modid = com.tablegame.TableGame.MODID, value = Dist.CLIENT)
public final class GameClientSetup {
    @SubscribeEvent
    static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
        ClientGameHandler.register(event);
    }

    /**
     * 注册舞台 HUD 层。
     * {@code registerAboveAll} = 画在所有原版 HUD 之上；HUD 层恒渲染，显不显示由层自己判（不在局里就不画）。
     */
    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(com.tablegame.TableGame.MODID, "stage"),
                new StageHudLayer());
    }
}
