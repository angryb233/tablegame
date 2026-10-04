package com.tablegame.core;

import java.util.ArrayList;
import java.util.List;

/** 自定义物品用到的**纯文本口径**（零 MC 依赖 ⇒ 进自检）：{@link #matches} 选基底物品时的**搜索匹配**（名称或 id、不区分大小写、部分匹配）·
 * {@link #splitLore} 玩家输入的**描述** → 组件 {@code minecraft:lore} 的行表。 */
public final class ItemText {

    private ItemText() {}

    /** 搜索匹配：名称或 id，不区分大小写、部分匹配；空 query = 全过。 */
    public static boolean matches(String query, String id, String name) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase();
        return (id != null && id.toLowerCase().contains(q))
                || (name != null && name.toLowerCase().contains(q));
    }

    /** 描述文本 → lore 行表：按**换行**拆行（老写法 {@code |} 也认）、去空白、丢空行。 */
    public static List<String> splitLore(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.split("[\\n|]")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}
