package com.wf.wflib.build;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads Litematica's {@code .litematic}. */
public final class LitematicaFormat implements BlueprintFormat {

    public static final LitematicaFormat INSTANCE = new LitematicaFormat();

    private static final int MIN_SCHEMA = 4;
    private static final int MAX_SCHEMA = 7;
    /** Most sub-regions one file may have. */
    private static final int MAX_REGIONS = 64;

    private LitematicaFormat() {
    }

    @Override
    public String id() {
        return "litematica";
    }

    @Override
    public String extension() {
        return "litematic";
    }

    @Override
    public Blueprint read(CompoundTag root, HolderGetter<Block> blocks, String name)
            throws BlueprintException {
        if (!root.contains("Regions", Tag.TAG_COMPOUND)) {
            throw new BlueprintException("not a litematica schematic: no Regions");
        }
        int schema = root.getInt("Version");
        if (schema < MIN_SCHEMA || schema > MAX_SCHEMA) {
            throw new BlueprintException("litematica schema version " + schema
                    + " is not supported (need " + MIN_SCHEMA + ".." + MAX_SCHEMA + ")");
        }
        CompoundTag regionsTag = root.getCompound("Regions");
        if (regionsTag.isEmpty()) {
            throw new BlueprintException("schematic has no regions");
        }
        if (regionsTag.size() > MAX_REGIONS) {
            throw new BlueprintException("schematic has " + regionsTag.size() + " regions, limit is "
                    + MAX_REGIONS);
        }
        int dataVersion = root.getInt("MinecraftDataVersion");

        List<Region> regions = new ArrayList<>(regionsTag.size());
        for (String key : regionsTag.getAllKeys()) {
            Region region = Region.of(regionsTag.getCompound(key));
            if (region != null) {
                regions.add(region);
            }
        }
        if (regions.isEmpty()) {
            throw new BlueprintException("schematic has no regions with any volume");
        }

        // Every region is placed into one array, so the blueprint is the box that encloses all of them.
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Region region : regions) {
            minX = Math.min(minX, region.minX);
            minY = Math.min(minY, region.minY);
            minZ = Math.min(minZ, region.minZ);
            maxX = Math.max(maxX, region.minX + region.width - 1);
            maxY = Math.max(maxY, region.minY + region.height - 1);
            maxZ = Math.max(maxZ, region.minZ + region.length - 1);
        }
        Vec3i size = new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > Blueprint.MAX_VOLUME) {
            throw new BlueprintException("schematic is " + size.getX() + "x" + size.getY() + "x" + size.getZ()
                    + " = " + volume + " cells, limit is " + Blueprint.MAX_VOLUME);
        }

        PaletteReader reader = new PaletteReader(blocks, dataVersion);
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        palette.put(Blocks.AIR.defaultBlockState(), 0);
        int[] cells = new int[(int) volume];
        int blockEntities = 0;

        for (Region region : regions) {
            int[] remap = region.remap(reader, palette);
            if (remap.length == 0) {
                continue;
            }
            blockEntities += region.blockEntities;
            int bits = PackedIndices.bitsFor(remap.length);
            for (int y = 0; y < region.height; y++) {
                for (int z = 0; z < region.length; z++) {
                    for (int x = 0; x < region.width; x++) {
                        int packed = PackedIndices.get(region.states,
                                (y * region.length + z) * region.width + x, bits);
                        int mapped = packed >= 0 && packed < remap.length ? remap[packed] : 0;
                        if (mapped == 0) {
                            continue;
                        }
                        int bx = region.minX - minX + x;
                        int by = region.minY - minY + y;
                        int bz = region.minZ - minZ + z;
                        cells[(by * size.getZ() + bz) * size.getX() + bx] = mapped;
                    }
                }
            }
        }

        return new Blueprint(displayName(root, name), size, List.copyOf(palette.keySet()), cells,
                blockEntities, reader.unresolved());
    }

    private static String displayName(CompoundTag root, String fallback) {
        String named = root.getCompound("Metadata").getString("Name");
        return named.isBlank() ? fallback : named;
    }

    /**
     * One sub-region, with its signs already resolved into a minimum corner and positive extents.
     */
    private static final class Region {
        final int minX;
        final int minY;
        final int minZ;
        final int width;
        final int height;
        final int length;
        final ListTag palette;
        final long[] states;
        final int blockEntities;

        private Region(int minX, int minY, int minZ, int width, int height, int length,
                       ListTag palette, long[] states, int blockEntities) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.length = length;
            this.palette = palette;
            this.states = states;
            this.blockEntities = blockEntities;
        }

        static Region of(CompoundTag tag) {
            CompoundTag pos = tag.getCompound("Position");
            CompoundTag size = tag.getCompound("Size");
            int sx = size.getInt("x");
            int sy = size.getInt("y");
            int sz = size.getInt("z");
            if (sx == 0 || sy == 0 || sz == 0) {
                return null;
            }
            ListTag palette = tag.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) {
                return null;
            }
            return new Region(min(pos.getInt("x"), sx), min(pos.getInt("y"), sy), min(pos.getInt("z"), sz),
                    Math.abs(sx), Math.abs(sy), Math.abs(sz), palette, tag.getLongArray("BlockStates"),
                    tag.getList("TileEntities", Tag.TAG_COMPOUND).size());
        }

        /**
         * @return the lowest coordinate the region covers on one axis. A negative extent runs back from
         *      {@code position}, so {@code position} is then the <em>last</em> cell rather than the first
         */
        private static int min(int position, int extent) {
            return extent > 0 ? position : position + extent + 1;
        }

        /**
         * Resolve this region's palette and fold it into the blueprint's shared one.
         *
         * @return region palette index to blueprint palette index, with every air entry mapped to 0
         */
        int[] remap(PaletteReader reader, Map<BlockState, Integer> shared) throws BlueprintException {
            int[] remap = new int[this.palette.size()];
            for (int i = 0; i < this.palette.size(); i++) {
                BlockState state = reader.read(this.palette.getCompound(i));
                if (state.isAir()) {
                    remap[i] = 0;
                    continue;
                }
                Integer existing = shared.get(state);
                if (existing != null) {
                    remap[i] = existing;
                    continue;
                }
                if (shared.size() >= Blueprint.MAX_PALETTE) {
                    throw new BlueprintException("schematic has more than " + Blueprint.MAX_PALETTE
                            + " distinct block states");
                }
                remap[i] = shared.size();
                shared.put(state, remap[i]);
            }
            return remap;
        }
    }
}
