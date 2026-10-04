package com.tablegame.area;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域落地的规划（纯逻辑，不碰世界）：规划（纯 int）与执行（{@code AreaPlacer}，真写方块）分家。
 *
 * <p>「能自检的类不引 MC」是分层约定，而覆盖策略判对没、每格是否恰好属于一相这两件事必须能断（schema-check
 * 的 stub 没引 BlockPos）。照投影先例：{@link Replace} = {@code ReplaceBehavior}，{@link #phases} 按 chunk 分相。
 */
public final class AreaPlanner {
    private AreaPlanner() { }

    /** 覆盖策略（照投影 {@code ReplaceBehavior} 的三值，名字也跟它对齐）。 */
    public enum Replace {
        /** 不动已有方块：目标非空气就跳过（最安全，不拆别人建筑）。 */
        NONE,
        /** 全覆盖：区域里的空气也写下去（会清掉落点处的建筑）。 */
        ALL,
        /** 空气不覆盖：只会盖上去，不会抹掉东西（但边角会留残留）。 */
        NON_AIR
    }

    /** 「跳过不碰」—— 相里那一格这一轮不动。 */
    public static final int SKIP = -1;
    /** 「写空气」—— 区域里那一格是空气，只有全覆盖模式才会走到。 */
    public static final int AIR = -2;

    /**
     * 这一格该怎么办（纯逻辑）。
     *
     * @param wantIdx     区域里那一格的下标（{@code <0} = 空气，{@code >=0} = 调色板下标）
     * @param targetIsAir 目标位置现在是空气吗
     * @param mode        覆盖策略
     * @return {@link #SKIP} = 不碰；{@link #AIR} = 写空气（清场）；否则 = 要写的调色板下标
     */
    public static int decide(int wantIdx, boolean targetIsAir, Replace mode) {
        if (wantIdx < 0) {
            // 区域里是空气：只有「全覆盖」才拿它清场；NONE / NON_AIR 都跳过
            return mode == Replace.ALL ? AIR : SKIP;
        }
        // 目标已有方块：NONE = 不动它；ALL / NON_AIR 都盖上去
        if (!targetIsAir && mode == Replace.NONE) return SKIP;
        return wantIdx;
    }

    /**
     * 这一格在不在那个「禁区盒」里（纯逻辑）—— 清源时用它绕开新位置，避免把刚落下的方块又清掉。
     *
     * @param g       {@code {minX, minY, minZ, maxX, maxY, maxZ}}（{@code null} = 没有禁区）
     * @param x,y,z   要判的那一格
     */
    public static boolean inside(int[] g, int x, int y, int z) {
        return g != null
                && x >= g[0] && x <= g[3]
                && y >= g[1] && y <= g[4]
                && z >= g[2] && z <= g[5];
    }

    /**
     * 格坐标属于哪个 chunk 列（纯逻辑）：把 {@code (x>>4, z>>4)} 打包成一个 int，当分相的键。
     */
    public static int chunkKey(int x, int z) {
        return ((x >> 4) & 0xFFFF) << 16 | ((z >> 4) & 0xFFFF);
    }

    /**
     * 按 chunk 列把区域切成「相」（一相 = 一个 chunk 列里那些格）。
     *
     * <p>相序按 {@link #chunkKey} 升序（稳定：反复盖章顺序一致）。每格 {@code {dx, dy, dz, 调色板下标}}，
     * 相对落点的偏移（落点在原点角上）；行序 {@code idx = (z*sizeY + y)*sizeX + x}。
     *
     * @return null = 形状对不上（区域尺寸与逐格表长度不符）—— 调用侧当「落不了」
     */
    public static List<List<int[]>> phases(int sx, int sy, int sz, int originX, int originZ,
            List<Integer> blocks) {
        if (sx <= 0 || sy <= 0 || sz <= 0 || blocks == null || blocks.size() != sx * sy * sz) return null;
        Map<Integer, List<int[]>> byKey = new LinkedHashMap<>();
        for (int dz = 0; dz < sz; dz++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dx = 0; dx < sx; dx++) {
                    int idx = blocks.get((dz * sy + dy) * sx + dx);
                    int key = chunkKey(originX + dx, originZ + dz);
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{dx, dy, dz, idx});
                }
            }
        }
        return order(byKey);
    }

    /** 相序（纯逻辑）：按 chunkKey 升序 —— 反复跑顺序一致（可复现）。 */
    private static List<List<int[]>> order(Map<Integer, List<int[]>> byKey) {
        List<Integer> keys = new ArrayList<>(byKey.keySet());
        keys.sort(Integer::compareTo);
        List<List<int[]>> out = new ArrayList<>();
        for (int k : keys) out.add(byKey.get(k));
        return out;
    }

    /**
     * 捕获侧的相规划：把一个盒按 chunk 列切成相，每格 {@code {dx, dy, dz, 快照下标}}。
     *
     * <p>下标与 {@link GameDefinition.AreaDef#of} 的行序同一套（{@code idx = (dz*sizeY + dy)*sizeX + dx}），
     * 捕获时直接 {@code cells[idx] = 那格状态}，收完不必再排序（排错 = 整份快照错位，也不报错）。
     * 与 {@link #phases} 分法相同，差别只在第 4 位：那边是「要写出去的调色板下标」，这边是「该放进哪一个下标」。
     *
     * @return null = 尺寸非法
     */
    public static List<List<int[]>> capturePhases(int sx, int sy, int sz, int originX, int originZ) {
        if (sx <= 0 || sy <= 0 || sz <= 0) return null;
        Map<Integer, List<int[]>> byKey = new LinkedHashMap<>();
        for (int dz = 0; dz < sz; dz++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dx = 0; dx < sx; dx++) {
                    int idx = (dz * sy + dy) * sx + dx;
                    int key = chunkKey(originX + dx, originZ + dz);
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{dx, dy, dz, idx});
                }
            }
        }
        return order(byKey);
    }
}

