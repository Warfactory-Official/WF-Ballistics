package com.wf.wfballistics.entity.glyphid.nav;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One shared answer to "which way from here", for every glyphid walking to the same place. Three hundred
 * converging glyphids were three hundred A* searches over the same terrain — 212 µs and 4843 block reads
 * each. A breadth-first flood outward from the destination pays for it once, after which a navigation
 * decision is an array index and eight comparisons.
 *
 * <p>Usable before it is finished: the flood solves the columns nearest the destination first, which are the
 * ones a converging swarm is in, and a glyphid in an unfilled column falls back to pathfinding.
 *
 * <p>Floors are sampled by the flood rather than up front, so a field around a walled base costs the inside
 * of the wall and stops. No field value means no route, which is the cue the digging already reads.
 *
 * <p>Not thread-safe: built and read on the world thread. Dense arrays and no block reads at lookup time, so
 * moving the build to a worker later is a scheduling change.
 */
public final class GlyphidFlowField {

    /**
     * Columns from the centre to the edge. 48 covers the last 96 blocks, where a swarm has converged enough
     * for its searches to be duplicates; further out the routes genuinely differ.
     */
    public static final int RADIUS = 48;
    public static final int SIZE = RADIUS * 2 + 1;
    private static final int COLUMNS = SIZE * SIZE;

    /** How far above and below the plane a floor is looked for: a hillside, but not the bottom of a ravine. */
    private static final int VERTICAL_REACH = 12;
    /** Clearance a glyphid needs to stand somewhere. The tallest caste is 1.5 blocks, so two. */
    private static final int CLEARANCE = 2;
    /**
     * Biggest rise between neighbouring columns that still counts as connected. Eight, not the one block a
     * walker could step, because glyphids climb: on a walled compound, pathfinding got 237 of 300 inside by
     * going over the wall while a walk-only field sent 91 the long way to the gap.
     *
     * <p>Charged as one step, which is a small lie -- but a weighted flood's honest weight is still far below
     * the cost of walking around the building.
     */
    private static final int CLIMB_UP = 8;
    /** Biggest drop that still counts as connected. A route that will not go downhill goes round the hill. */
    private static final int DROP = 8;

    private static final int NO_FLOOR = Integer.MIN_VALUE;
    private static final int UNSAMPLED = Integer.MIN_VALUE + 1;
    static final short UNREACHED = -1;

    private final int centreX;
    private final int centreY;
    private final int centreZ;

    /** Floor height per column: {@link #NO_FLOOR} where nothing can stand, {@link #UNSAMPLED} unvisited. */
    private final int[] floor = new int[COLUMNS];
    /** Steps from the destination, {@link #UNREACHED} until the flood arrives. Short: no route is longer. */
    private final short[] distance = new short[COLUMNS];

    private final IntArrayFIFOQueue frontier = new IntArrayFIFOQueue();
    private int filled;
    private boolean complete;

    public GlyphidFlowField(ServerLevel level, int x, int y, int z) {
        this.centreX = x;
        this.centreY = y;
        this.centreZ = z;
        java.util.Arrays.fill(floor, UNSAMPLED);
        java.util.Arrays.fill(distance, UNREACHED);

        int origin = index(x, z);
        if (origin >= 0 && sampleFloor(level, x, z, origin) != NO_FLOOR) {
            distance[origin] = 0;
            frontier.enqueue(origin);
            filled = 1;
        } else {
            // A destination nobody can stand on, usually inside a machine. The flood has nowhere to start,
            // so every glyphid falls back to pathfinding.
            complete = true;
        }
    }

    public int centreX() {
        return centreX;
    }

    public int centreY() {
        return centreY;
    }

    public int centreZ() {
        return centreZ;
    }

    public boolean complete() {
        return complete;
    }

    public int filled() {
        return filled;
    }

    /**
     * Spend up to {@code budget} columns of flood. Returns the number actually expanded, which is zero once
     * the field is complete.
     */
    public int build(ServerLevel level, int budget) {
        int spent = 0;
        while (spent < budget && !frontier.isEmpty()) {
            int cell = frontier.dequeueInt();
            spent++;
            expand(level, cell);
        }
        if (frontier.isEmpty()) {
            complete = true;
        }
        return spent;
    }

    private void expand(ServerLevel level, int cell) {
        int cx = cell % SIZE;
        int cz = cell / SIZE;
        int here = floor[cell];
        short next = (short) (distance[cell] + 1);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) {
                    continue;
                }
                int neighbour = nz * SIZE + nx;
                if (distance[neighbour] != UNREACHED) {
                    continue;
                }
                int blockX = centreX + nx - RADIUS;
                int blockZ = centreZ + nz - RADIUS;
                int floorY = floor[neighbour] == UNSAMPLED
                        ? sampleFloor(level, blockX, blockZ, neighbour)
                        : floor[neighbour];
                if (floorY == NO_FLOOR || floorY - here > CLIMB_UP || here - floorY > DROP) {
                    continue;
                }
                distance[neighbour] = next;
                filled++;
                frontier.enqueue(neighbour);
            }
        }
    }

    /**
     * The height a glyphid would stand at in this column, searched outward from the destination plane rather
     * than down from the sky — a column under an overhang has two floors, and the one level with its
     * neighbours is the one that matters.
     */
    private int sampleFloor(ServerLevel level, int blockX, int blockZ, int cell) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int offset = 0; offset <= VERTICAL_REACH; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = centreY + offset * sign;
                if (offset == 0 && sign < 0) {
                    continue;
                }
                if (standable(level, pos, blockX, y, blockZ)) {
                    floor[cell] = y;
                    return y;
                }
            }
        }
        floor[cell] = NO_FLOOR;
        return NO_FLOOR;
    }

    private static boolean standable(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        pos.set(x, y - 1, z);
        BlockState below = level.getBlockState(pos);
        if (!below.isSolidRender(level, pos)) {
            return false;
        }
        for (int h = 0; h < CLEARANCE; h++) {
            pos.set(x, y + h, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the centre of the neighbouring column a glyphid at this position should walk into, or null if
     * this position is off the field, in an unreached column, or already at the destination.
     */
    public double[] step(double x, double y, double z) {
        int cell = index(Math.floor(x), Math.floor(z));
        if (cell < 0 || distance[cell] == UNREACHED) {
            return null;
        }
        int cx = cell % SIZE;
        int cz = cell / SIZE;
        short best = distance[cell];
        int bestCell = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) {
                    continue;
                }
                int neighbour = nz * SIZE + nx;
                short d = distance[neighbour];
                if (d != UNREACHED && d < best) {
                    best = d;
                    bestCell = neighbour;
                }
            }
        }
        if (bestCell < 0) {
            return null;
        }
        return new double[]{
                centreX + bestCell % SIZE - RADIUS + 0.5,
                floor[bestCell],
                centreZ + bestCell / SIZE - RADIUS + 0.5};
    }

    private int index(double blockX, double blockZ) {
        int dx = (int) blockX - centreX + RADIUS;
        int dz = (int) blockZ - centreZ + RADIUS;
        if (dx < 0 || dx >= SIZE || dz < 0 || dz >= SIZE) {
            return -1;
        }
        return dz * SIZE + dx;
    }

    /**
     * @return true if this block is inside the square this field covers.
     */
    public boolean covers(int blockX, int blockZ) {
        return index(blockX, blockZ) >= 0;
    }
}
