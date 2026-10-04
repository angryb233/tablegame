package com.tablegame.area;

import com.tablegame.TableGame;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

    /**
     * 「区域工具」的客户端读取：手里那枚工具的标记是什么。
     *
     * <p>选取 / 摆放两套模式都靠它判「他还在模式里吗」——读法只有这一处（主手优先、副手兜底），
     * 免得两边各写一遍走偏。
     *
     * <p>形态是「拿着工具」而非「客户端开关」：进模式时发一枚工具，手里拿着它才画状态条 / 拦手势 /
     * Shift+Enter 才有用；不拿（换手、丢掉、拿别的）就整段走原版交互，于是「退出模式」不需要按键。
     */
public final class AreaToolClient {
    private AreaToolClient() {}

    /**
     * 手里（主手优先、副手兜底）那枚区域工具的标记；没拿 / 不是区域工具 / 没有标记 → 空串。
     * 标记怎么解由 {@link AreaToolKit} 说了算（这里只管「读出来」）。
     */
    public static String handMarker() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        for (ItemStack st : new ItemStack[]{mc.player.getMainHandItem(), mc.player.getOffhandItem()}) {
            if (st.getItem() != TableGame.AREA_TOOL.get()) continue;
            var cd = st.get(DataComponents.CUSTOM_DATA);
            if (cd == null) continue;
            String tag = cd.copyTag().getStringOr(AreaToolKit.TAG, "");
            if (!tag.isEmpty()) return tag;
        }
        return "";
    }

    /** 手里拿着**这一场**（这个模式 + 这款游戏 + 这条区域）的工具吗。 */
    public static boolean holding(String mode, String game, String area) {
        return AreaToolKit.matches(handMarker(), mode, game, area);
    }

    /**
     * 背包里（任意槽位）还有这枚工具吗。
     *
     * <p>与「手里拿着」分成两个判定：「手里拿着」只决定画不画状态条 / 拦不拦手势；「背包里有没有」才决定
     * 模式还在不在 —— 换手 / 切格子不该把模式弄没，只有丢掉或选取完服务端收走才算结束。
     */
    public static boolean inInventory(String mode, String game, String area) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.getItem() != TableGame.AREA_TOOL.get()) continue;
            var cd = st.get(DataComponents.CUSTOM_DATA);
            if (cd == null) continue;
            if (AreaToolKit.matches(cd.copyTag().getStringOr(AreaToolKit.TAG, ""), mode, game, area)) return true;
        }
        return false;
    }
}
