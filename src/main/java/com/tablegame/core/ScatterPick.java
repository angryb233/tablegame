package com.tablegame.core;

import java.util.Arrays;
import java.util.Random;

/**
 * 撒矿里「从候选格里随机挑 N 个<b>不重复</b>的下标」这一半 —— 部分 Fisher–Yates。
 * 纯逻辑（无 MC 依赖），自检真跑；宿主那一层只负责凑候选（已加载 + 方块在可替换清单里），挑谁归这里。
 */
public final class ScatterPick {
    private ScatterPick() { }

    /**
     * 从 n 个候选里挑 k 个不重复的下标。
     *
     * @return 长度 = min(k, n)（k ≤ 0 / n ≤ 0 → 空表）；每个下标都在 [0, n) 且互不相同
     */
    public static int[] pick(int n, int k, Random rnd) {
        if (n <= 0 || k <= 0) return new int[0];
        int m = Math.min(k, n);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = 0; i < m; i++) {                 // 前 m 位洗出来 = 抽中的（剩下的不用管）
            int j = i + rnd.nextInt(n - i);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return Arrays.copyOf(a, m);
    }
}
