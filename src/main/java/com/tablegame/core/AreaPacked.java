package com.tablegame.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 区域方块的**紧凑编码**：盘上形态 = 一个 base64 字符串（直接当 {@code blocks} 字段的值），整段 gzip + base64。
 * <pre>
 *   [0..1]  magic 'A' 'P'   ← 认得出是不是这个格式（vs 老档的 JSON 数组）
 *   [2] version = 1 · [3] bitsPerEntry · [4..7] count（格子数，大端 int）· [8..] 位打包的 long[]（大端）
 * </pre>
 * **值的映射**：格子值只有「空气 = -1」与「调色板下标 0..n-1」；把空气映成 0、下标映成 {@code idx + 1} ⇒ 取值范围 {@code 0..paletteSize}
 * （全空气的格子在位平面里是长串 0，gzip 一压就没）。
 * **纯逻辑、零 MC 依赖**（只碰 {@code List<Integer>}）⇒ 自检能直接跑。**内存形态不变**（{@code AreaDef} 照旧 palette + blocks），视口 / 捕获 / 落地那几处一行不用改。
 */
public final class AreaPacked {
    private AreaPacked() { }

    private static final byte MAGIC_A = 'A';
    private static final byte MAGIC_P = 'P';
    private static final byte VERSION = 1;

    /**
     * 一格占几位：{@code max(2, ⌈log2(paletteSize + 1)⌉)}（+1 = 给空气留的那个 0）。
     *
     * <p>下限 2 照投影（{@code Validate.inclusiveBetween(1, 32, bitsPerEntry)} + 它调用处写 {@code Math.max(2, …)}）——
     * 1 位的打包在跨字边界上容易写错且省不了几个字节，不值。
     */
    public static int bitsFor(int paletteSize) {
        int need = paletteSize + 1;                       // 0..paletteSize 共 paletteSize+1 种取值
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, need - 1));   // ⌈log2(need)⌉
        return Math.max(2, Math.min(32, bits));
    }

    /**
     * 逐格表 → base64 串。形状对不上 / 表里有认不出的值时返回 <b>null</b>（调用方当「这区域没快照」）。
     *
     * @param blocks 逐格调色板下标，长度必须是 {@code sizeX*sizeY*sizeZ}；空气 = -1
     */
    public static String pack(List<Integer> blocks, int sizeX, int sizeY, int sizeZ, int paletteSize) {
        if (blocks == null || paletteSize <= 0) return null;
        int count = sizeX * sizeY * sizeZ;
        if (count <= 0 || blocks.size() != count) return null;
        int bits = bitsFor(paletteSize);
        long[] words = new long[(count * bits + 63) / 64];
        for (int i = 0; i < count; i++) {
            Integer b = blocks.get(i);
            int v = b == null || b < 0 ? 0 : b + 1;
            if (v > paletteSize) return null;              // 下标越界：档坏了，别打一份错的包
            setWord(words, i, bits, v);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(8 + words.length * 8);
        raw.write(MAGIC_A);
        raw.write(MAGIC_P);
        raw.write(VERSION);
        raw.write(bits);
        for (int s = 24; s >= 0; s -= 8) raw.write((count >>> s) & 0xFF);       // count 大端
        for (long w : words) {
            for (int s = 56; s >= 0; s -= 8) raw.write((int) ((w >>> s) & 0xFF));
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(raw.toByteArray());
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;                                   // 压缩失败（内存不够这类）：当没快照，不抛
        }
    }

    /**
     * base64 串 → 逐格表。认不出（魔数不对 / 版本不认识 / 解压失败 / 格子数对不上 / 下标越界）
     * → <b>null</b>：调用方当「这区域没快照」，<b>不抛、不崩</b>（读坏一份档不该抬掉整个编辑器）。
     */
    public static List<Integer> unpack(String b64, int expectCount, int paletteSize) {
        if (b64 == null || b64.isEmpty() || expectCount <= 0) return null;
        byte[] data;
        try {
            data = Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            return null;
        }
        byte[] raw;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            raw = gz.readAllBytes();
        } catch (Exception e) {
            return null;
        }
        if (raw.length < 8 || raw[0] != MAGIC_A || raw[1] != MAGIC_P || raw[2] != VERSION) return null;
        int bits = raw[3] & 0xFF;
        if (bits < 1 || bits > 32) return null;
        int count = ((raw[4] & 0xFF) << 24) | ((raw[5] & 0xFF) << 16) | ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        if (count != expectCount) return null;              // 与 sizeX/Y/Z 对不上 = 档坏了
        int nWords = (count * bits + 63) / 64;
        if (raw.length < 8 + nWords * 8) return null;
        long[] words = new long[nWords];
        for (int i = 0; i < nWords; i++) {
            long w = 0;
            for (int j = 0; j < 8; j++) w = (w << 8) | (raw[8 + i * 8 + j] & 0xFFL);
            words[i] = w;
        }
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int v = getWord(words, i, bits);
            if (v > paletteSize) return null;               // 下标越界（档里那份 palette 比打包时小）
            out.add(v == 0 ? -1 : v - 1);                   // 0 = 空气
        }
        return out;
    }

    // ===== 位打包（照 {@code LitematicaBitArray.setAt/getAt} 的跨字写法）=====

    private static void setWord(long[] words, int index, int bits, int value) {
        long mask = (1L << bits) - 1L;
        long startOffset = (long) index * bits;
        int startIdx = (int) (startOffset >> 6);
        int endIdx = (int) (((index + 1L) * bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        words[startIdx] = words[startIdx] & ~(mask << startBit) | ((long) value & mask) << startBit;
        if (startIdx != endIdx) {
            int endOffset = 64 - startBit;
            int rest = bits - endOffset;
            words[endIdx] = words[endIdx] >>> rest << rest | ((long) value & mask) >> endOffset;
        }
    }

    private static int getWord(long[] words, int index, int bits) {
        long mask = (1L << bits) - 1L;
        long startOffset = (long) index * bits;
        int startIdx = (int) (startOffset >> 6);
        int endIdx = (int) (((index + 1L) * bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startIdx == endIdx) return (int) (words[startIdx] >>> startBit & mask);
        int endOffset = 64 - startBit;
        return (int) ((words[startIdx] >>> startBit | words[endIdx] << endOffset) & mask);
    }
}
