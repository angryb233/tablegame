package com.tablegame.core;

/**
 * 「往容器里塞东西」的**纯算法**（不引 MC ⇒ 能进自检真跑；MC 那层只把它算出来的逐槽增量如实写进原版容器）。
 * 两副输入由调用侧算好（「同款」只有宿主答得了：原版 {@code ItemStack.isSameItemSameComponents}）：
 * {@code mergeRoom[i]} = 第 i 槽**同款堆**还能加几件（不是同款 / 空槽 → 0）· {@code emptyRoom[i]} = 第 i 槽是**空槽**时能收几件（非空 → 0）。
 * 纯逻辑只认这两组「还能收几件」的数 —— 这就是分层的位置。
 */
public final class ChestFill {
    private ChestFill() { }

    /**
     * 算「每槽各加几件」（塞同一种物品，总量最多 {@code want}）：**先扫一遍并堆**（按槽号）→ **再扫一遍空槽**（按槽号）。
     * @return 与输入等长的逐槽增量表（和 = 实际塞进去几件，≤ want）
     */
    public static int[] plan(int[] mergeRoom, int[] emptyRoom, int want) {
        if (mergeRoom.length != emptyRoom.length) {
            throw new IllegalArgumentException("两个房间表的长度不一致："
                    + mergeRoom.length + " / " + emptyRoom.length);
        }
        int[] add = new int[mergeRoom.length];
        int left = Math.max(0, want);
        for (int i = 0; i < add.length && left > 0; i++) {
            int put = Math.min(Math.max(0, mergeRoom[i]), left);
            add[i] = put;
            left -= put;
        }
        for (int i = 0; i < add.length && left > 0; i++) {
            int put = Math.min(Math.max(0, emptyRoom[i]), left);
            add[i] += put;
            left -= put;
        }
        return add;
    }

    /** 逐槽增量表 → 一共塞进去几件。 */
    public static int total(int[] add) {
        int n = 0;
        for (int v : add) n += v;
        return n;
    }
}
