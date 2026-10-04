package com.tablegame.core;

/**
 * 彩色命名：把**能打出来的** {@code &} 转义符译成原版的 {@code §} 格式代码。
 * 两条边角规则：{@code &&} = 字面一个 {@code &}；{@code &} 后面不是合法码（含大写，原版 {@code getByCode} 也只认小写）= **原样保留**，不吞字符。
 * 纯字符串逻辑 ⇒ 进自检。用法：任何**按 String 渲染**的文本点，画之前套一层（舞台文本 / 列表标签 / 物品显示名…）。
 */
public final class ColorText {

    /** 原版 {@code §} 认的全部码（ChatFormatting 里带 code 的那些：16 色 + 6 种格式）。 */
    private static final String CODES = "0123456789abcdefklmnor";

    private ColorText() {
    }

    /** {@code &} 转义 → {@code §} 格式码；{@code &&} → 字面 {@code &}；非法码原样保留。 */
    public static String mask(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '&') {                       // && = 字面 &
                    out.append('&');
                    i++;
                    continue;
                }
                if (CODES.indexOf(next) >= 0) {          // &c → §c（其余交给原版管线）
                    out.append('\u00a7').append(next);
                    i++;
                    continue;
                }
            }
            out.append(ch);                              // 普通字符 / 打错的 & → 原样
        }
        return out.toString();
    }
}
