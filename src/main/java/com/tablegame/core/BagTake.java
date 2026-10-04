package com.tablegame.core;

/** 「从背包里取走」的**纯算法**（不引 MC ⇒ 进自检真跑；宿主只负责「哪几槽装着这种物品、各有几件」与「把算出来的数如实扣下去」）。 */
public final class BagTake {
    private BagTake() { }

    /** 算「每槽各扣几件」。@param have 每槽**认得的**件数（空槽 / 不是这种物品 → 0）· @param want 要几件 · @return 与输入等长的逐槽扣件表；和 = 实际拿走几件（不够 ⇒ 全是 0） */
    public static int[] plan(int[] have, int want) {
        int[] take = new int[have.length];
        if (want <= 0) return take;
        int sum = 0;
        for (int v : have) sum += Math.max(0, v);
        if (sum < want) return take;                        // 不够：一件不动
        int left = want;
        for (int i = 0; i < take.length && left > 0; i++) {
            int n = Math.min(Math.max(0, have[i]), left);
            take[i] = n;
            left -= n;
        }
        return take;
    }

    /** 逐槽扣件表 → 一共拿走几件。 */
    public static int total(int[] take) {
        int n = 0;
        for (int v : take) n += v;
        return n;
    }
}
