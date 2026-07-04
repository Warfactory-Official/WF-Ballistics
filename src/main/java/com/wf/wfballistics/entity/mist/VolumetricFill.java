package com.wf.wfballistics.entity.mist;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared voxel machinery for wall-respecting area effects: a chunk-cached flood fill of reachable air from a
 * point, and a decomposition of the filled cells into a small set of maximal axis-aligned boxes. Used by
 * {@link GasCloud} (fills the whole volume) and {@link com.wf.wfballistics.fire.FireUtil} (keeps only the
 * floor shell so fire drapes over the reachable terrain).
 */
public final class VolumetricFill {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private VolumetricFill() {
    }

    public static Fill flood(Level level, BlockPos origin, int radius, int maxCells, boolean spherical) {
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

        int ox = origin.getX() - minX;
        int oy = origin.getY() - minY;
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
                int hdx = nx - ox;
                int hdz = nz - oz;
                int horiz = hdx * hdx + hdz * hdz;
                if (spherical) {
                    int hdy = ny - oy;
                    if (horiz + hdy * hdy > radius * radius) {
                        continue;
                    }
                } else if (horiz > radius * radius) {
                    continue;
                }
                int nidx = (nx * sy + ny) * sz + nz;
                if (cell[nidx] != 0) {
                    continue;
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

    public static List<int[]> decompose(Fill f) {
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

    private static boolean rowFree(byte[] cell, boolean[] used, Fill f, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) {
            if (!free(cell, used, f.index(x, y, z))) {
                return false;
            }
        }
        return true;
    }

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

    private static boolean canMerge(int[] a, int[] b) {
        if (a[1] == b[1] && a[4] == b[4] && a[2] == b[2] && a[5] == b[5]
                && (a[3] + 1 == b[0] || b[3] + 1 == a[0])) {
            return true;
        }
        if (a[0] == b[0] && a[3] == b[3] && a[2] == b[2] && a[5] == b[5]
                && (a[4] + 1 == b[1] || b[4] + 1 == a[1])) {
            return true;
        }
        return a[0] == b[0] && a[3] == b[3] && a[1] == b[1] && a[4] == b[4]
                && (a[5] + 1 == b[2] || b[5] + 1 == a[2]);
    }

    public static long volume(int[] b) {
        return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
    }

    /**
     * A filled region: world min corner, buffer dims, and the per-cell grid (0 = unknown, 1 = open, 2 = wall).
     */
    public record Fill(int minX, int minY, int minZ, int sx, int sy, int sz, byte[] cell, int count) {

        public int index(int x, int y, int z) {
            return (x * sy + y) * sz + z;
        }

        /**
         * A derived fill keeping only the floor shell: open cells whose cell directly below is a wall (or the
         * buffer floor). Decomposing this gives the terrain surface within the reachable region.
         */
        public Fill floorView() {
            byte[] fc = new byte[cell.length];
            int c = 0;
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        int idx = index(x, y, z);
                        if (cell[idx] != 1) {
                            continue;
                        }
                        if (y == 0 || cell[index(x, y - 1, z)] == 2) {
                            fc[idx] = 1;
                            c++;
                        }
                    }
                }
            }
            return new Fill(minX, minY, minZ, sx, sy, sz, fc, c);
        }
    }

    /**
     * Reads block solidity while caching the current chunk, so a spatially coherent fill mostly reads a single
     * field instead of resolving a chunk per sample. Unloaded chunks read as walls, so the fill never expands
     * into (or generates) ungenerated terrain.
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
            return state.isCollisionShapeFullBlock(level, mp);
        }
    }
}
