package com.tablegame.block;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * 座位实体渲染器 —— 什么都不画（隐形）。
 *
 * <p>每个实体类型都必须有渲染器，否则加载时报错崩溃；座位是纯逻辑实体（「骑乘点」），不该有外观。
 * 26.x 的 {@link EntityRenderer} 走「渲染状态」模式，返回空 {@link EntityRenderState} 即可，默认 render 不画。
 * 本类只用 {@code net.minecraft.client} 类，客户端专属（只在 TableGameClient 注册，专用服务器不加载）。
 */
public class SeatRenderer extends EntityRenderer<SeatEntity, EntityRenderState> {

    public SeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    // 空渲染状态：座位没有需要渲染的数据。
    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
