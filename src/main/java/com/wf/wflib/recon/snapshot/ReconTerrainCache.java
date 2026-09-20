package com.wf.wflib.recon.snapshot;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/** Per-dimension store of condensed terrain, and the only place a {@link ReconTerrain} is made. */
public final class ReconTerrainCache {

    /** Field builds one dimension may do per tick. */
    public static final int BUILDS_PER_TICK = 1;
    /** Ticks before a sensor's field is rebuilt. */
    public static final int FIELD_MAX_AGE = 600;
    /** Hard cap on a field's side in cells. */
    public static final int MAX_CELLS = 512;

    private static final int CELLS_PER_CHUNK = 16 / ReconTerrain.CELL;
    private static final int CHUNK_CELLS = CELLS_PER_CHUNK * CELLS_PER_CHUNK;
    private static final long CHUNK_MAX_AGE = 600L;
    private static final int MAX_CHUNKS = 16384;

    private static final Map<ResourceKey<Level>, ReconTerrainCache> BY_LEVEL = new HashMap<>();

    private final Map<Long, Entry> byChunk = new HashMap<>();
    private int buildsThisTick;

    private ReconTerrainCache() {
    }

    public static ReconTerrainCache get(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new ReconTerrainCache());
    }

    /**
     * Reset the per-tick build allowance. Called once per dimension from {@code ReconNet.tick}.
     */
    public static void beginTick(ServerLevel level) {
        get(level).buildsThisTick = 0;
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    public boolean canBuild() {
        return buildsThisTick < BUILDS_PER_TICK;
    }

    /**
     * Condense the ground around a sensor into a field the detection pass can take away with it.
     *
     * @return the field, or null if this dimension has already spent its build allowance this tick.
     */
    public ReconTerrain build(ServerLevel level, double centreX, double centreZ, double radius) {
        WorldThread.assertOn("recon terrain condensing");
        if (!canBuild()) {
            return null;
        }
        buildsThisTick++;

        int cells = Math.min(MAX_CELLS, Math.max(1, (int) Math.ceil(2.0 * radius / ReconTerrain.CELL) + 1));
        int originX = Math.floorDiv((int) Math.floor(centreX - radius), ReconTerrain.CELL) * ReconTerrain.CELL;
        int originZ = Math.floorDiv((int) Math.floor(centreZ - radius), ReconTerrain.CELL) * ReconTerrain.CELL;

        short[] height = new short[cells * cells];
        short[] seabed = new short[cells * cells];
        byte[] material = new byte[cells * cells];
        byte[] climate = new byte[cells * cells];
        for (int cz = 0; cz < cells; cz++) {
            int worldZ = originZ + cz * ReconTerrain.CELL;
            for (int cx = 0; cx < cells; cx++) {
                int worldX = originX + cx * ReconTerrain.CELL;
                int i = cz * cells + cx;
                short[] cached = chunkCells(level, SectionPos.blockToSectionCoord(worldX),
                        SectionPos.blockToSectionCoord(worldZ));
                if (cached == null) {
                    height[i] = ReconTerrain.UNKNOWN;
                    seabed[i] = ReconTerrain.UNKNOWN;
                    material[i] = ReconTerrain.MAT_UNKNOWN;
                    climate[i] = ReconTerrain.CLIMATE_UNKNOWN;
                    continue;
                }
                int local = (Math.floorMod(worldZ, 16) / ReconTerrain.CELL) * CELLS_PER_CHUNK
                        + Math.floorMod(worldX, 16) / ReconTerrain.CELL;
                height[i] = cached[local];
                material[i] = (byte) cached[CHUNK_CELLS + local];
                climate[i] = (byte) cached[CHUNK_CELLS * 2 + local];
                seabed[i] = cached[CHUNK_CELLS * 3 + local];
            }
        }
        return new ReconTerrain(originX, originZ, cells, cells, height, seabed, material, climate,
                level.getMinBuildHeight());
    }

    /**
     * @return one chunk condensed to a height, a surface material, a climate and a seabed per cell, or null if
     *      it is not loaded.
     */
    private short[] chunkCells(ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        long now = level.getGameTime();
        Entry cached = byChunk.get(key);
        if (cached != null && now - cached.stamp() < CHUNK_MAX_AGE) {
            return cached.cells();
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return cached != null ? cached.cells() : null;
        }

        short[] cells = new short[CHUNK_CELLS * 4];
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int cz = 0; cz < CELLS_PER_CHUNK; cz++) {
            for (int cx = 0; cx < CELLS_PER_CHUNK; cx++) {
                int highest = Integer.MIN_VALUE;
                int lowest = Integer.MAX_VALUE;
                int highX = 0;
                int highZ = 0;
                for (int dz = 0; dz < ReconTerrain.CELL; dz++) {
                    for (int dx = 0; dx < ReconTerrain.CELL; dx++) {
                        int x = (chunkX << 4) + cx * ReconTerrain.CELL + dx;
                        int z = (chunkZ << 4) + cz * ReconTerrain.CELL + dz;
                        int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        if (h > highest) {
                            highest = h;
                            highX = x;
                            highZ = z;
                        }
                        // OCEAN_FLOOR is the top of what blocks motion, so under water it is the seabed.
                        lowest = Math.min(lowest, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
                    }
                }
                int i = cz * CELLS_PER_CHUNK + cx;
                cells[i] = (short) highest;
                cells[CHUNK_CELLS * 3 + i] = (short) Math.min(lowest, highest);
                probe.set(highX, highest - 1, highZ);
                cells[CHUNK_CELLS + i] = classify(chunk, probe);
                Biome biome = chunk.getNoiseBiome(QuartPos.fromBlock(highX), QuartPos.fromBlock(highest),
                        QuartPos.fromBlock(highZ)).value();
                Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
                cells[CHUNK_CELLS * 2 + i] =
                        ReconTerrain.packClimate(climate.temperature(), climate.downfall());
            }
        }

        if (byChunk.size() >= MAX_CHUNKS) {
            byChunk.clear();
        }
        byChunk.put(key, new Entry(cells, now));
        return cells;
    }

    /** Surface material, coarsely, for seismic path composition. */
    private static byte classify(LevelChunk chunk, BlockPos pos) {
        BlockState state = chunk.getBlockState(pos);
        MapColor colour = state.getMapColor(chunk, pos);
        if (colour == MapColor.STONE || colour == MapColor.DEEPSLATE || colour == MapColor.QUARTZ) {
            return ReconTerrain.MAT_ROCK;
        }
        if (colour == MapColor.SAND || colour == MapColor.COLOR_LIGHT_GRAY) {
            return ReconTerrain.MAT_SAND;
        }
        if (colour == MapColor.WATER || colour == MapColor.ICE) {
            return ReconTerrain.MAT_WATER;
        }
        if (colour == MapColor.WOOD || colour == MapColor.PODZOL || colour == MapColor.PLANT) {
            return ReconTerrain.MAT_WOOD;
        }
        if (colour == MapColor.METAL || colour == MapColor.COLOR_GRAY) {
            return ReconTerrain.MAT_METAL;
        }
        if (colour == MapColor.NONE) {
            return ReconTerrain.MAT_UNKNOWN;
        }
        return ReconTerrain.MAT_SOIL;
    }

    private record Entry(short[] cells, long stamp) {
    }
}
