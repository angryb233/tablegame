package com.tablegame.piece;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tablegame.TableGame;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.DataComponents;

/**
 * 棋子/蓝图的数据核心。
 *
 * <p>一个 record 承载蓝图三态，用「哪些字段为 null」区分：空白（selection==null 且 voxels==null）/
 * 选角中（selection!=null 且 voxels==null）/ 已锁定（selection==null 且 voxels!=null，确认时清除选角）。
 *
 * <p>本类同时是物品数据组件（DATA_COMPONENT_TYPE），存档 / 网络同步 / 拖拽复制由原版机制处理。
 * 体素编码 = 调色板压缩：palette 是非空气状态去重表，paletteIds 是逐格下标（空气 = -1）。
 */
public record PieceData(Selection selection, VoxelData voxels, Meta meta) {

    /** 蓝图元数据：作者 + 制作日期（锁定时写入，悬停提示展示；可 null = 旧数据）。 */
    public record Meta(String author, String date) {
        public static final Codec<Meta> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("author").forGetter(Meta::author),
                Codec.STRING.fieldOf("date").forGetter(Meta::date)
        ).apply(i, Meta::new));

        public static final StreamCodec<ByteBuf, Meta> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Meta::author,
                ByteBufCodecs.STRING_UTF8, Meta::date,
                Meta::new);
    }

    // PieceData 本体的编解码：三个字段都可能缺失（空白蓝图/旧存档），用 Optional 桥接 null
    public static final Codec<PieceData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Selection.CODEC.optionalFieldOf("selection").forGetter(s -> Optional.ofNullable(s.selection())),
            VoxelData.CODEC.optionalFieldOf("voxels").forGetter(s -> Optional.ofNullable(s.voxels())),
            Meta.CODEC.optionalFieldOf("meta").forGetter(s -> Optional.ofNullable(s.meta()))
    ).apply(i, (sel, vox, meta) -> new PieceData(sel.orElse(null), vox.orElse(null), meta.orElse(null))));

    public static final StreamCodec<ByteBuf, PieceData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(Selection.STREAM_CODEC), d -> Optional.ofNullable(d.selection()),
            ByteBufCodecs.optional(VoxelData.STREAM_CODEC), d -> Optional.ofNullable(d.voxels()),
            ByteBufCodecs.optional(Meta.STREAM_CODEC), d -> Optional.ofNullable(d.meta()),
            (sel, vox, meta) -> new PieceData(sel.orElse(null), vox.orElse(null), meta.orElse(null)));

    // ==================== 选角状态 ====================

    /** 两个角点；corner2 用 Optional 表达「还没定角2」（原版 record+Optional 标准编码姿势）。 */
    public record Selection(BlockPos corner1, Optional<BlockPos> corner2) {
        public static final Codec<Selection> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("corner1").forGetter(Selection::corner1),
                BlockPos.CODEC.optionalFieldOf("corner2").forGetter(Selection::corner2)
        ).apply(i, Selection::new));

        public static final StreamCodec<ByteBuf, Selection> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Selection::corner1,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Selection::corner2,
                Selection::new);

        public Selection withCorner2(BlockPos c2) {
            return new Selection(corner1, Optional.of(c2));
        }

        public boolean hasCorner2() {
            return corner2.isPresent();
        }

        /** 判空取用的便捷形式。 */
        public BlockPos corner2OrNull() {
            return corner2.orElse(null);
        }
    }

    // ==================== 体素数据 ====================

    /**
     * 调色板压缩后的方块快照。palette.get(paletteIds[i]) = 第 i 格的方块状态（-1 = 空气）。
     *
     * <p><b>scale（规格）</b>：体素密度固定 1 方块 = 1px（先缩 1/16 基准），规格档 1/2、1/4、1/8 =
     * 基准上放大 2/4/8 倍，每块边长 = 规格/16 格。scale 存在 VoxelData 里而非制作器方块上，
     * 故同一张蓝图在不同制作器可用不同比例产出（数据随形）。字段带默认值（x2）编码。
     */
    public record VoxelData(int sizeX, int sizeY, int sizeZ, float scale,
            List<BlockState> palette, List<Integer> paletteIds) {
        public static final Codec<VoxelData> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("sizeX").forGetter(VoxelData::sizeX),
                Codec.INT.fieldOf("sizeY").forGetter(VoxelData::sizeY),
                Codec.INT.fieldOf("sizeZ").forGetter(VoxelData::sizeZ),
                // scale 是浮点：棋子编辑页的「尺寸」要能填小数（0.25 = 放大 4 倍）；
                // 旧蓝图里的整数照旧读得回来（数字 codec 互通）。
                Codec.FLOAT.optionalFieldOf("scale", 2.0F).forGetter(VoxelData::scale),
                BlockState.CODEC.listOf().fieldOf("palette").forGetter(VoxelData::palette),
                Codec.INT.listOf().fieldOf("paletteIds").forGetter(VoxelData::paletteIds)
        ).apply(i, VoxelData::new));

        public static final StreamCodec<ByteBuf, VoxelData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, VoxelData::sizeX,
                ByteBufCodecs.VAR_INT, VoxelData::sizeY,
                ByteBufCodecs.VAR_INT, VoxelData::sizeZ,
                ByteBufCodecs.FLOAT, VoxelData::scale,
                ByteBufCodecs.fromCodec(BlockState.CODEC).apply(ByteBufCodecs.list(4096)), VoxelData::palette,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(4096)), VoxelData::paletteIds,
                VoxelData::new);

        public int volume() {
            return sizeX * sizeY * sizeZ;
        }

        /** 展开成逐格数组（行序 x→y→z），渲染阶段遍历用。 */
        public BlockState[] expand() {
            BlockState[] out = new BlockState[volume()];
            for (int i = 0; i < out.length; i++) {
                int id = paletteIds.get(i);
                out[i] = id >= 0 && id < palette.size() ? palette.get(id) : Blocks.AIR.defaultBlockState();
            }
            return out;
        }
    }

    // ==================== 常量 / 工厂 / 判定 ====================

    /** 选区最大边长（含），16³ = 4096 块。 */
    public static final int MAX_SIZE = 16;

    public static PieceData empty() {
        return new PieceData(null, null, null);
    }

    public boolean isBlank() {
        return selection == null && voxels == null;
    }

    public boolean isLocked() {
        return voxels != null;
    }

    /**
     * 从世界中捕获选区 → 生成锁定的体素数据。
     *
     * @return 成功 = 含体素数据的新 PieceData；失败 = null（选区超限）。
     */
    public static PieceData capture(Level level, BlockPos corner1, BlockPos corner2) {
        BoundingBox box = BoundingBox.fromCorners(corner1, corner2);
        int sx = box.getXSpan(), sy = box.getYSpan(), sz = box.getZSpan();
        if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
            return null;
        }

        // 调色板编码：LinkedHashMap 保持插入序（输出稳定，存档 diff 友好），value = 下标
        Map<BlockState, Integer> paletteMap = new LinkedHashMap<>();
        List<Integer> ids = new ArrayList<>(sx * sy * sz);
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())) {
            BlockState st = level.getBlockState(p);
            // 空气直接记 -1 不进调色板（选区里大部分格子是空气，这是压缩的大头）
            if (st.isAir()) {
                ids.add(-1);
            } else {
                ids.add(paletteMap.computeIfAbsent(st, k -> paletteMap.size()));
            }
        }
        return new PieceData(null, new VoxelData(sx, sy, sz, 2,
                new ArrayList<>(paletteMap.keySet()), ids), null);
    }

    // ==================== 物品数据组件注册 ====================

    // 字段类型必须是 DataComponents 子类（registerComponentType 定义在它上面）
    public static final DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, TableGame.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PieceData>> PIECE_DATA =
            DATA_COMPONENTS.registerComponentType("piece_data", b -> b
                    .persistent(PieceData.CODEC)
                    .networkSynchronized(PieceData.STREAM_CODEC));

    // ==================== 组件读写助手 ====================

    public static PieceData get(ItemStack stack) {
        PieceData d = stack.get(PIECE_DATA.get());
        return d != null ? d : empty();
    }

    public static void set(ItemStack stack, PieceData data) {
        stack.set(PIECE_DATA.get(), data);
    }
}