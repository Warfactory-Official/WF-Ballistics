package com.wf.wfballistics.drone.nav;

import com.wf.wfballistics.drone.WorldThread;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-dimension store of coarse terrain heights, and the only place a {@link TerrainField} is made.
 *
 * <p>Reading the height map is cheap; reading it for every cell of every route of every drone, every time one
 * wants to plan, is not. So a chunk is condensed once, into {@link #CELL}-block cells holding the highest
 * ground in each, and every field built over it afterwards is an array copy. A squad replanning together
 * pays for the terrain once between them, and a drone flying the same corridor home pays nothing at all.
 *
 * <p><b>World thread only.</b> Everything here touches chunk storage. The whole point of condensing terrain
 * into a {@link TerrainField} is that the field, unlike this, can leave the thread.
 *
 * <p>Chunks are never forced to load: an unloaded column is left {@link TerrainField#UNKNOWN} rather than
 * dragging world generation onto the tick to answer a question about ground a drone has not reached yet.
 */
public final class TerrainCache {

    /**
     * Blocks per cell. Four is a good match for the airframe: fine enough to find a valley, coarse enough
     * that a long route is still only a few thousand cells.
     */
    public static final int CELL = 4;
    /**
     * How many field builds one dimension may do per tick. Each is a few array copies plus whatever chunks it
     * is the first to condense, and drones replan rarely, so this is a ceiling on a spike rather than a
     * quota anyone hits in normal flight.
     */
    public static final int BUILDS_PER_TICK = 3;

    private static final int CELLS_PER_CHUNK = 16 / CELL;
    private static final int CHUNK_CELLS = CELLS_PER_CHUNK * CELLS_PER_CHUNK;
    /**
     * Ticks before a condensed chunk is re-read. This mod rearranges terrain with some enthusiasm, and a
     * drone routing over a crater that is no longer there is a slow, silent kind of wrong.
     */
    private static final long MAX_AGE = 600L;
    /**
     * Condensed chunks kept before the store is dropped wholesale. At 16 shorts each this is well under a
     * megabyte, and rebuilding is cheap, so an occasional clear beats tracking an eviction order.
     */
    private static final int MAX_CHUNKS = 8192;

    private static final Map<ResourceKey<Level>, TerrainCache> BY_LEVEL = new HashMap<>();

    private final Map<Long, Entry> byChunk = new HashMap<>();
    private int buildsThisTick;

    private TerrainCache() {
    }

    public static TerrainCache get(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level.dimension(), k -> new TerrainCache());
    }

    /**
     * Reset the per-tick build allowance. Called once per dimension from the AI scheduler.
     */
    public static void beginTick(ServerLevel level) {
        get(level).buildsThisTick = 0;
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    /**
     * @return true if this dimension can still afford to condense a route this tick.
     */
    public boolean canBuild() {
        return buildsThisTick < BUILDS_PER_TICK;
    }

    /**
     * Condense the terrain around a route into a field the planner can take away with it.
     *
     * @param from     where the drone is
     * @param to       where it is going; the field is boxed around the segment
     * @param pad      blocks of margin around the segment, so the planner has room to route <em>around</em>
     *                 an obstacle rather than being confined to a corridor through it
     * @param maxCells hard cap on either side of the field, in cells
     * @param fallback height to assume for ground that was not loaded
     * @return the field, or null if this dimension has already spent its build allowance this tick
     */
    public TerrainField build(ServerLevel level, Vec3 from, Vec3 to, double pad, int maxCells, double fallback) {
        WorldThread.assertOn("terrain condensing");
        if (!canBuild()) {
            return null;
        }
        buildsThisTick++;

        int minX = (int) Math.floor(Math.min(from.x, to.x) - pad);
        int minZ = (int) Math.floor(Math.min(from.z, to.z) - pad);
        int maxX = (int) Math.ceil(Math.max(from.x, to.x) + pad);
        int maxZ = (int) Math.ceil(Math.max(from.z, to.z) + pad);

        int originX = Math.floorDiv(minX, CELL) * CELL;
        int originZ = Math.floorDiv(minZ, CELL) * CELL;
        int width = Math.min(maxCells, Math.max(1, (maxX - originX) / CELL + 1));
        int depth = Math.min(maxCells, Math.max(1, (maxZ - originZ) / CELL + 1));

        originX = shiftToCover(originX, width, from.x);
        originZ = shiftToCover(originZ, depth, from.z);

        short[] tops = new short[width * depth];
        int fallbackTop = (int) Math.floor(fallback);
        for (int cz = 0; cz < depth; cz++) {
            int worldZ = originZ + cz * CELL;
            for (int cx = 0; cx < width; cx++) {
                int worldX = originX + cx * CELL;
                tops[cz * width + cx] = cellTop(level, worldX, worldZ);
            }
        }
        return new TerrainField(originX, originZ, CELL, width, depth, tops, fallbackTop);
    }

    /**
     * Single-cell lookup, for the per-tick look-ahead that does not need a whole field.
     *
     * @param fallback height to report where the column is not loaded
     * @return the highest ground in the cell containing this position
     */
    public int topAt(ServerLevel level, double worldX, double worldZ, int fallback) {
        short top = cellTop(level, (int) Math.floor(worldX), (int) Math.floor(worldZ));
        return top == TerrainField.UNKNOWN ? fallback : top;
    }

    /**
     * Slide a field's origin so a point of interest falls inside it.
     */
    private static int shiftToCover(int origin, int cells, double worldCoordinate) {
        int span = cells * CELL;
        int point = (int) Math.floor(worldCoordinate);
        if (point < origin) {
            return Math.floorDiv(point, CELL) * CELL;
        }
        if (point >= origin + span) {
            return Math.floorDiv(point - span + CELL, CELL) * CELL;
        }
        return origin;
    }

    /**
     * @return the highest ground in one cell, from the condensed chunk it belongs to.
     */
    private short cellTop(ServerLevel level, int worldX, int worldZ) {
        int chunkX = SectionPos.blockToSectionCoord(worldX);
        int chunkZ = SectionPos.blockToSectionCoord(worldZ);
        short[] cells = chunkCells(level, chunkX, chunkZ);
        if (cells == null) {
            return TerrainField.UNKNOWN;
        }
        int localX = (Math.floorMod(worldX, 16)) / CELL;
        int localZ = (Math.floorMod(worldZ, 16)) / CELL;
        return cells[localZ * CELLS_PER_CHUNK + localX];
    }

    /**
     * @return this chunk condensed to one height per cell, or null if it is not loaded.
     */
    private short[] chunkCells(ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        long now = level.getGameTime();
        Entry cached = byChunk.get(key);
        if (cached != null && now - cached.stamp() < MAX_AGE) {
            return cached.tops();
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return cached != null ? cached.tops() : null;
        }

        short[] tops = new short[CHUNK_CELLS];
        for (int cz = 0; cz < CELLS_PER_CHUNK; cz++) {
            for (int cx = 0; cx < CELLS_PER_CHUNK; cx++) {
                int highest = Integer.MIN_VALUE;
                for (int dz = 0; dz < CELL; dz++) {
                    for (int dx = 0; dx < CELL; dx++) {
                        int x = (chunkX << 4) + cx * CELL + dx;
                        int z = (chunkZ << 4) + cz * CELL + dz;
                        highest = Math.max(highest,
                                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                    }
                }
                tops[cz * CELLS_PER_CHUNK + cx] = (short) highest;
            }
        }

        if (byChunk.size() >= MAX_CHUNKS) {
            byChunk.clear();
        }
        byChunk.put(key, new Entry(tops, now));
        return tops;
    }

    private record Entry(short[] tops, long stamp) {
    }
}
