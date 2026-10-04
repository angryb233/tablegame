package com.tablegame.editor.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tablegame.core.ColorText;
import com.tablegame.core.GameDefinition;
import com.tablegame.editor.data.LootEdit;

    /**
     * 「我们那条物品」的组件 JSON —— 全仓唯一一份口径。
     *
     * <p>物品身份 = 基底原版物品 + 这几个组件：名字（带色）/ 描述 / 声明里玩家写的 {@code components} 层 /
     * 身份标签 {@link LootEdit#ASSET_TAG}。
     *
     * <p>写法交回原版：这里是纯 JSON，宿主喂 {@code DataComponentPatch.CODEC} 贴上栈 —— 原版组件一个都不用我们写代码。
     */
public final class AssetItem {
    private AssetItem() {
    }

    /**
     * 一条资产的组件层（键省 {@code minecraft:} 前缀，原版 codec 也认）。
     *
     * <p>顺序：名字 / 描述 → 玩家写的 components 层 → 身份标签最后写（我们自己的账本，不许被上面那层冲掉）。
     * 键互不重叠时顺序无影响，重叠时「后写赢」。
     */
    public static JsonObject componentsOf(GameDefinition.AssetDef a) {
        JsonObject c = new JsonObject();
        if (a == null) return c;
        if (a.name() != null && !a.name().isEmpty()) {
            c.add("item_name", text(ColorText.mask(a.name())));
        }
        if (a.lore() != null && !a.lore().isEmpty()) {
            JsonArray lore = new JsonArray();
            for (String ln : a.lore()) lore.add(text(ColorText.mask(ln)));
            c.add("lore", lore);
        }
        if (a.bp() != null && a.bp().has("components") && a.bp().get("components").isJsonObject()) {
            for (var e : a.bp().getAsJsonObject("components").entrySet()) {
                c.add(e.getKey(), e.getValue());
            }
        }
        JsonObject own = new JsonObject();
        own.addProperty(LootEdit.ASSET_TAG, a.ref());
        c.add("custom_data", own);
        return c;
    }

    /** 一段文本 → 原版 Component JSON（与 {@code Component.literal(文本)} 等价）。 */
    private static JsonObject text(String s) {
        JsonObject o = new JsonObject();
        o.addProperty("text", s);
        return o;
    }
}
