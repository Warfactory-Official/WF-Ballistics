package com.wf.wfballistics.entity.glyphid.nav;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One shared answer to "which way from here", for every glyphid walking to the same place.
 *
 * <p>Three hundred glyphids converging on a base were three hundred A* searches over the same terrain to the
 * same destination — 212 µs and 4843 block reads each, re-deriving the identical route because nothing shared
 * it. A flow field pays for the terrain once: a breadth-first flood outward from the destination over walkable
 * columns, after which a glyphid's entire navigation decision is one array index and eight comparisons.
 *
 * <p><b>Built outward from the goal, and usable before it is finished.</b> The flood starts at the destination,
 * so the columns nearest it are solved first, which are the ones a converging swarm is standing in. A glyphid
 * in an unfilled column simply falls back to pathfinding for another second. That is what makes an incremental
 * build honest rather than a way of hiding a stall: at no point is anything waiting on it.
 *
 * <p><b>Floors are sampled by the flood, not up front.</b> Only columns the flood actually reaches are ever
 * looked at, so a field around a walled base costs the inside of the wall and stops — and a destination that
 * is genuinely sealed off costs a handful of columns rather than the whole square. That also gives the digging
 * its cue for free: no field value means no route, which is the same signal the stuck timer already reads.
 *
 * <p>Not thread-safe and not meant to be: it is built and read on the world thread. The structure is the one
 * §11.10 wanted for the off-thread case — dense arrays, no block reads at lookup time — so moving the build
 * to a worker later is a scheduling change rather than a rewrite.
 */
public final class GlyphidFlowField {

    /**
     * Columns from the centre to the edge. 48 covers the last 96 blocks of an approach, which is where a
     * swarm has converged enough for its searches to be duplicates of each other; further out they are
     * spread across different terrain and their routes genuinely differ.
     */
    public static final int RADIUS = 48;
    public static final int SIZE = RADIUS * 2 + 1;
    private static final int COLUMNS = SIZE * SIZE;

    /**
     * How far above and below the destination plane a floor is looked for. Deep enough for a hillside,
     * shallow enough that a column over a ravine finds nothing rather than finding its bottom.
     */
    private static final int VERTICAL_REACH = 12;
    /**
     * Clearance a glyphid needs to stand somewhere. The tallest caste is 1.5 blocks, so two.
     */
    private static final int CLEARANCE = 2;
    /**
     * Biggest rise between neighbouring columns that still counts as connected.
     *
     * <p>Eight, not the one block a walker could step up, because <b>glyphids climb</b>. Anything they bump
     * into becomes a ladder, and a field that models them as walkers is not modelling them: measured on an
     * obsidian compound with one gap in it, pathfinding got 237 of 300 inside in a minute by walking into the
     * wall and going over it, while a field that only knew about walking sent them the long way round to the
     * gap and got 91 in. The cheapest route over a four-block wall is over it.
     *
     * <p>Charged as one step like any other move, which is a small lie — climbing is slower than walking —
     * but the alternative is a weighted flood, and the honest version of the weight is still far below the
     * cost of walking around a building.
     */
    private static final int CLIMB_UP = 8;
    /**
     * Biggest drop between neighbouring columns that still counts as connected. Glyphids take falls that
     * would hurt a player, and a route that refuses to go downhill is a route around the whole hill.
     */
    private static final int DROP = 8;

    private static final int NO_FLOOR = Integer.MIN_VALUE;
    private static final int UNSAMPLED = Integer.MIN_VALUE + 1;
    static final short UNREACHED = -1;

    private final int centreX;
    private final int centreY;
    private final int centreZ;

    /**
     * Absolute floor height per column, {@link #NO_FLOOR} where a glyphid cannot stand and
     * {@link #UNSAMPLED} where the flood has not looked yet.
     */
    private final int[] floor = new int[COLUMNS];
    /**
     * Steps from the destination, {@link #UNREACHED} until the flood arrives. Short because a field this
     * size cannot hold a longer route than 32767 steps and the array is read far more often than written.
     */
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
            // A destination nobody can stand on -- inside a machine, usually. The flood has nowhere to start,
            // so the field is finished before it began and every glyphid falls back to pathfinding, which is
            // the behaviour that got them to the wall in the first place.
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
     * Find the height a glyphid would stand at in this column, searching outward from the destination plane.
     *
     * <p>Outward rather than downward from the sky: a column under an overhang has two floors and the one
     * that matters is the one level with everything around it. Searching from the plane finds that one, and
     * finds it in a handful of reads rather than a hundred.
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
