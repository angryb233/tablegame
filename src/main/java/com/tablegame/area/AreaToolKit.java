package com.tablegame.area;

/**
 * 「区域工具」物品的标记格式：进选取/摆放模式时发给玩家，手里拿着它才在模式里（画状态条 / 拦手势 / Shift+Enter）。
 * 标记在两处拼出（服务端发放 + 客户端判定），这里是唯一真源；纯逻辑，进 run.sh 的 javac 清单 + 一条往返断言。
 *
 * <p>形状：{@code <模式>|<游戏名>|<区域名>|<覆盖档>} —— 例：{@code "cap|diamond|r1|"}（选取）·
 * {@code "put|diamond|r1|non_air"}（摆放）。模式 cap=选取 / put=摆放；覆盖档只有摆放用（none|all|non_air）。
 */
public final class AreaToolKit {
    private AreaToolKit() {}

    /** 物品栈上那个标签的键（写在原版 {@code CUSTOM_DATA} 里的一条）。 */
    public static final String TAG = "tg_area_tool";

    /** 模式：选取（框选抓快照）。 */
    public static final String CAPTURE = "cap";
    /** 模式：摆放（盖章）。 */
    public static final String PLACE = "put";

    /** 拼一个标记（**唯一**的拼法）。 */
    public static String marker(String mode, String game, String area, String cover) {
        return nz(mode) + "|" + nz(game) + "|" + nz(area) + "|" + nz(cover);
    }

    /** 标记里某一格（0 = 模式 · 1 = 游戏 · 2 = 区域 · 3 = 覆盖档；认不出 → 空串）。 */
    public static String part(String marker, int i) {
        if (marker == null) return "";
        String[] p = marker.split("\\|", -1);
        return i >= 0 && i < p.length ? p[i] : "";
    }

    /**
     * 这枚标记是不是「这一场」（同模式 + 同游戏 + 同区域）。覆盖档不参与判定（它是动作参数，换档不该踢人出模式）。
     */
    public static boolean matches(String marker, String mode, String game, String area) {
        return nz(mode).equals(part(marker, 0)) && nz(game).equals(part(marker, 1))
                && nz(area).equals(part(marker, 2));
    }

    /** 提示条 / 物品名上给人看的那半句（如「选取：diamond / r1」）。 */
    public static String label(String mode, String game, String area) {
        return (CAPTURE.equals(mode) ? "选取：" : "摆放：") + nz(game) + " / " + nz(area);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
