package com.wf.wfballistics.entity.mist;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a wall-respecting gas cloud out of many static {@link MistEntity} box cells.
 *
 * <p>Rather than one big cloud entity that bleeds through walls, the cloud is <em>reconstructed</em>:
 * <ol>
 *   <li><b>Flood fill</b> the reachable air from the detonation point ({@link #floodFill}) — a 6-connected
 *       BFS bounded by full-cube blocks, so gas can't cross a wall. To keep the per-cell block lookups cheap
 *       the fill reads solidity through a {@link ChunkView} that caches the current chunk (a "copied chunk
 *       buffer"), rather than resolving a chunk from the level on every one of thousands of samples.</li>
 *   <li><b>Decompose</b> the filled voxels into a small set of maximal axis-aligned boxes
 *       ({@link #decompose}), so a big volume becomes a handful of cells instead of thousands.</li>
 *   <li><b>Spawn</b> one {@link MistEntity#spawnBox box-mode gas entity} per box.</li>
 * </ol>
 *
 * <p>The result is cheap to sustain — a handful of static entities that never move or path (often a single
 * box in the open, a few conforming to a room) — at the cost of the one-time spawn calculation done here.
 */
public final class GasCloud {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Flood-fill reach (blocks) from the origin along each axis; the cloud is at most a (2r+1) cube. */
    public static final int DEFAULT_RADIUS = 6;
    /**
     * Safety cap only. The fill is normally bounded by the radius cube; this is kept well above
     * {@code (2*radius+1)^3} so an open-air burst fills its whole cube — which decomposes to a single box —
     * instead of a budget-carved ball (whose stepped surface needs dozens of boxes).
     */
    public static final int DEFAULT_MAX_CELLS = 8192;
    /** Cloud lifetime in ticks (~15s). */
    public static final int DEFAULT_DURATION = 300;
    /**
     * Each spawned cell processes entities every N ticks. Kept at 1: the sustain cost is the per-tick entity
     * scan, which is cheap over the small static cells, and a coarser interval can phase-skip effects whose
     * agents gate their damage on the victim's own tick count.
     */
    public static final int DEFAULT_EFFECT_INTERVAL = 1;

    /** Hard cap on spawned box entities; excess filled volume is left uncovered (and logged). */
    private static final int MAX_BOXES = 220;

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private GasCloud() {
    }

    /** Convenience: fill and spawn with the default tuning. */
    public static int spawn(Level level, Fluid fluid, Vec3 center) {
        return spawn(level, fluid, center, DEFAULT_RADIUS, DEFAULT_MAX_CELLS, DEFAULT_DURATION);
    }

    /**
     * Flood-fills reachable air from {@code center}, reconstructs it as box-mode gas cells and spawns them.
     *
     * @return the number of gas cell entities spawned
     */
    public static int spawn(Level level, Fluid fluid, Vec3 center, int radius, int maxCells, int duration) {
        if (level.isClientSide) {
            return 0;
        }
        Fill fill = floodFill(level, BlockPos.containing(center), radius, maxCells);
        if (fill == null || fill.count == 0) {
            return 0;
        }

        List<int[]> boxes = decompose(fill);
        if (boxes.size() > MAX_BOXES) {
            LOGGER.warn("Gas cloud at {} decomposed to {} boxes (> {} cap); spawning the largest {}.",
                    BlockPos.containing(center), boxes.size(), MAX_BOXES, MAX_BOXES);
            boxes.sort((p, q) -> Long.compare(volume(q), volume(p))); // keep the biggest, drop tiny leftovers
            boxes = boxes.subList(0, MAX_BOXES);
        }

        for (int[] b : boxes) {
            double wx = fill.minX + b[0];
            double wy = fill.minY + b[1];
            double wz = fill.minZ + b[2];
            float sx = b[3] - b[0] + 1;
            float sy = b[4] - b[1] + 1;
            float sz = b[5] - b[2] + 1;
            MistEntity.spawnBox(level, fluid, wx, wy, wz, sx, sy, sz, duration, DEFAULT_EFFECT_INTERVAL);
        }
        return boxes.size();
    }

    // --- flood fill -----------------------------------------------------------------------------------

    /** Result of a fill: the world min corner, the buffer dims, and the per-cell occupancy grid. */
    private static final class Fill {
        final int minX;
        final int minY;
        final int minZ;
        final int sx;
        final int sy;
        final int sz;
        final byte[] cell; // 0 = unknown, 1 = open/filled, 2 = wall
        final int count;

        Fill(int minX, int minY, int minZ, int sx, int sy, int sz, byte[] cell, int count) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cell = cell;
            this.count = count;
        }

        int index(int x, int y, int z) {
            return (x * sy + y) * sz + z;
        }
    }

    private static Fill floodFill(Level level, BlockPos origin, int radius, int maxCells) {
        int minX = origin.getX() - radius;
        int minZ = origin.getZ() - radius;
        int minY = Math.max(level.getMinBuildHeight(), origin.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, origin.getY() + radius);

        int sx = radius * 2 + 1;
        int sz = radius * 2 + 1;
        int sy = maxY - minY + 1;
        if (sy <= 0) {
            return null;
        }

        int n = sx * sy * sz;
        byte[] cell = new byte[n];
        int[] stack = new int[n];
        int sp = 0;

        ChunkView view = new ChunkView(level);

        // Seed at the origin column, stepping up until the first open cell (the missile may detonate against
        // the ground). Everything scanned as solid on the way up is recorded so BFS never revisits it.
        int ox = origin.getX() - minX;
        int oz = origin.getZ() - minZ;
        int startY = -1;
        for (int y = origin.getY() - minY; y < sy; y++) {
            int idx = (ox * sy + y) * sz + oz;
            if (view.isWall(minX + ox, minY + y, minZ + oz)) {
                cell[idx] = 2;
            } else {
                cell[idx] = 1;
                startY = y;
                break;
            }
        }
        if (startY < 0) {
            return null;
        }

        stack[sp++] = (ox * sy + startY) * sz + oz;
        int count = 1;

        while (sp > 0 && count < maxCells) {
            int idx = stack[--sp];
            int z0 = idx % sz;
            int t = idx / sz;
            int y0 = t % sy;
            int x0 = t / sy;

            for (int d = 0; d < 6 && count < maxCells; d++) {
                int nx = x0 + DX[d];
                int ny = y0 + DY[d];
                int nz = z0 + DZ[d];
                if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                    continue;
                }
                int nidx = (nx * sy + ny) * sz + nz;
                if (cell[nidx] != 0) {
                    continue; // already open or already known-wall
                }
                if (view.isWall(minX + nx, minY + ny, minZ + nz)) {
                    cell[nidx] = 2;
                } else {
                    cell[nidx] = 1;
                    stack[sp++] = nidx;
                    count++;
                }
            }
        }

        return new Fill(minX, minY, minZ, sx, sy, sz, cell, count);
    }

    // --- box decomposition ----------------------------------------------------------------------------

    /**
     * Covers the filled cells with axis-aligned boxes, then fuses adjacent ones. Per unused seed it grows a
     * maximal box (+x run, widen +z over the run, raise +y over the x-z rectangle), consumes it, and moves on;
     * {@link #mergeBoxes} then fuses any face-adjacent boxes with an identical cross-section.
     *
     * <p>The decomposition is already near-minimal for an exact cover, so the real box-count win comes from
     * the fill covering the whole wall-bounded region (see {@link #DEFAULT_MAX_CELLS}): an open-air burst is a
     * solid cube — one box — and a room is a handful, whereas a stepped ball needs dozens.
     *
     * @return boxes as {@code [x0, y0, z0, x1, y1, z1]} in buffer-local, inclusive coordinates
     */
    private static List<int[]> decompose(Fill f) {
        byte[] cell = f.cell;
        boolean[] used = new boolean[cell.length];
        List<int[]> boxes = new ArrayList<>();

        for (int x = 0; x < f.sx; x++) {
            for (int y = 0; y < f.sy; y++) {
                for (int z = 0; z < f.sz; z++) {
                    int idx = f.index(x, y, z);
                    if (cell[idx] != 1 || used[idx]) {
                        continue;
                    }
                    int x1 = x;
                    while (x1 + 1 < f.sx && free(cell, used, f.index(x1 + 1, y, z))) {
                        x1++;
                    }
                    int z1 = z;
                    while (z1 + 1 < f.sz && rowFree(cell, used, f, x, x1, y, z1 + 1)) {
                        z1++;
                    }
                    int y1 = y;
                    while (y1 + 1 < f.sy && rectFree(cell, used, f, x, x1, z, z1, y1 + 1)) {
                        y1++;
                    }
                    for (int xx = x; xx <= x1; xx++) {
                        for (int yy = y; yy <= y1; yy++) {
                            for (int zz = z; zz <= z1; zz++) {
                                used[f.index(xx, yy, zz)] = true;
                            }
                        }
                    }
                    boxes.add(new int[]{x, y, z, x1, y1, z1});
                }
            }
        }

        mergeBoxes(boxes);
        return boxes;
    }

    private static boolean free(byte[] cell, boolean[] used, int idx) {
        return cell[idx] == 1 && !used[idx];
    }

    /** All cells in x-span [x0,x1] at (y, z) free? */
    private static boolean rowFree(byte[] cell, boolean[] used, Fill f, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) {
            if (!free(cell, used, f.index(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    /** All cells in rectangle [x0,x1]×[z0,z1] at height y free? */
    private static boolean rectFree(byte[] cell, boolean[] used, Fill f, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!free(cell, used, f.index(x, y, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Repeatedly fuses face-adjacent boxes that share an identical cross-section, until none remain. */
    private static void mergeBoxes(List<int[]> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < boxes.size(); i++) {
                int[] a = boxes.get(i);
                for (int j = i + 1; j < boxes.size(); j++) {
                    int[] b = boxes.get(j);
                    if (canMerge(a, b)) {
                        a[0] = Math.min(a[0], b[0]);
                        a[1] = Math.min(a[1], b[1]);
                        a[2] = Math.min(a[2], b[2]);
                        a[3] = Math.max(a[3], b[3]);
                        a[4] = Math.max(a[4], b[4]);
                        a[5] = Math.max(a[5], b[5]);
                        boxes.remove(j);
                        changed = true;
                        j--;
                    }
                }
            }
        }
    }

    /** True if the two boxes touch along one axis and match exactly on the other two — so their union is a box. */
    private static boolean canMerge(int[] a, int[] b) {
        // Adjacent in x, identical y and z spans.
        if (a[1] == b[1] && a[4] == b[4] && a[2] == b[2] && a[5] == b[5]
                && (a[3] + 1 == b[0] || b[3] + 1 == a[0])) {
            return true;
        }
        // Adjacent in y, identical x and z spans.
        if (a[0] == b[0] && a[3] == b[3] && a[2] == b[2] && a[5] == b[5]
                && (a[4] + 1 == b[1] || b[4] + 1 == a[1])) {
            return true;
        }
        // Adjacent in z, identical x and y spans.
        return a[0] == b[0] && a[3] == b[3] && a[1] == b[1] && a[4] == b[4]
                && (a[5] + 1 == b[2] || b[5] + 1 == a[2]);
    }

    private static long volume(int[] b) {
        return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
    }

    // --- cached chunk access --------------------------------------------------------------------------

    /**
     * Reads block solidity while caching the current {@link LevelChunk}. The flood fill is spatially coherent,
     * so this turns thousands of level chunk resolutions into (mostly) a single field read per sample — the
     * "copied chunk buffer" that keeps the spawn calculation off the hot path. Unloaded chunks read as walls,
     * so the fill never expands into (or generates) ungenerated terrain.
     */
    private static final class ChunkView {
        private final Level level;
        private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        private long key = Long.MIN_VALUE;
        private LevelChunk chunk;

        ChunkView(Level level) {
            this.level = level;
        }

        boolean isWall(int wx, int wy, int wz) {
            int cx = wx >> 4;
            int cz = wz >> 4;
            long k = ChunkPos.asLong(cx, cz);
            if (k != key) {
                key = k;
                chunk = level.hasChunk(cx, cz) ? level.getChunk(cx, cz) : null;
            }
            if (chunk == null) {
                return true;
            }
            mp.set(wx, wy, wz);
            BlockState state = chunk.getBlockState(mp);
            // Full-cube blocks stop gas; air, plants, slabs, panes, fences, fluids etc. let it through.
            return state.isCollisionShapeFullBlock(level, mp);
        }
    }
}
