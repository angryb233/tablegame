package com.tablegame.piece;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 模型制作器客户端收包（蓝图库 S2C）。
 *
 * <p>列表/组列表收包按当前屏路由：浮层开着 → 数据就地灌进浮层重建；浮层已不在（中途 Esc）→ 直接丢弃。
 * 绝不在这里新开屏（会把玩家拽出当前界面）。
 */
public final class ModelMakerClient {
    private ModelMakerClient() {}

    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(ModelMakerPackets.MyGroupsPayload.TYPE, ModelMakerClient::onMyGroups);
        event.register(ModelMakerPackets.LibraryListPayload.TYPE, ModelMakerClient::onLibraryList);
        event.register(ModelMakerPackets.BrowserListPayload.TYPE, ModelMakerClient::onBrowserList);
    }

    private static void onMyGroups(ModelMakerPackets.MyGroupsPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SaveBlueprintOverlay save) {
            save.applyGroups(p.groups());
        }
        // 浮层不在 = 已取消，数据丢弃
    }

    private static void onLibraryList(ModelMakerPackets.LibraryListPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ImportBlueprintOverlay importOverlay) {
            if (p.names().size() != p.sources().size()) {
                return;   // 双列不齐 = 脏包，丢弃
            }
            java.util.List<PieceLibrary.Entry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < p.names().size(); i++) {
                entries.add(new PieceLibrary.Entry(p.names().get(i), p.sources().get(i)));
            }
            importOverlay.applyEntries(entries);
        }
    }

    /**
     * 蓝图库列表（/tablegame pieces）：库菜单开着 → 就地刷新（删除后服务端重发走这里）；
     * 不在 → 新开菜单（指令打开的唯一入口——服务端不会主动推这个包，只有请求/删除后回发）。
     */
    private static void onBrowserList(ModelMakerPackets.BrowserListPayload p, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PieceBrowserScreen browser) {
            browser.applyData(p.names(), p.sources(), p.dedicated());
        } else {
            PieceBrowserScreen s = new PieceBrowserScreen();
            mc.setScreen(s);
            s.applyData(p.names(), p.sources(), p.dedicated());   // 新屏 init 完成后再灌数据
        }
    }
}
