package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;

/**
 * Keeps a swarm spread out, without asking the world what is nearby.
 *
 * <p>Glyphids do not shove each other — {@link EntityGlyphid#pushEntities} suppresses it, because vanilla
 * push was the only cost measured that grew faster than the swarm did: 1.8% of the tick at a hundred and
 * 15.5% at three hundred packed. The price paid for that was a swarm that converges to a point and stays
 * there, three hundred bodies standing in the space of one.
 *
 * <p><b>The cost was never the push.</b> It was the broadphase: every glyphid asking the level for an
 * inflated-box entity query every tick, which walks entity sections and runs a generic predicate over
 * everything in them. The arithmetic that follows is a handful of subtractions. So this keeps the push and
 * throws away the query — one uniform grid built per level tick from {@link GlyphidTracker}, which already
 * holds every live glyphid, and each bug compares itself only against the bugs in the nine cells around it.
 * The build is O(n) and the comparisons are O(n·k) for a small bounded k, against a per-entity query whose
 * constant is a section walk.
 *
 * <p>Each pair is visited once and pushed both ways, which halves the work and makes the result symmetric:
 * two glyphids cannot disagree about which way they are separating.
 *
 * <p>Nothing here is collision. There is no sweep, no bounding-box intersection and no cramming — an impulse
 * is added to velocity that the {@code move()} already running will spend, and a pair that ends the tick
 * still overlapping simply pushes again. The visible effect is a crowd rather than a column.
 */
public final class GlyphidSeparation {

    /**
     * Grid pitch, in blocks. Sized to the largest separation any pair can ask for — a behemoth is 2.5 wide —
     * so the nine cells around a glyphid are guaranteed to hold every bug close enough to matter. No wider,
     * because every extra block of pitch is more candidates to test per bug.
     */
    private static final double CELL = 3.0;
    /**
     * Fraction of the touching distance a pair holds. Below 1 they are allowed to overlap a little, which is
     * what stops a packed swarm from setting into a rigid lattice and lets a column still funnel through a
     * two-block doorway.
     */
    private static final double SPACING = 0.8;
    /**
     * Hardest push one glyphid takes in one tick, in blocks per tick, summed over every neighbour.
     *
     * <p>Comparable to a walk — a glyphid moves about 0.2 blocks a tick — and that is the point. A swarm
     * converging on one place is three hundred bodies all driving inward at walking pace, and nothing else
     * stops them: entity collision is off, so a push weaker than the drive does not hold any spacing at all,
     * it just slows the compression down. Measured at a fifth of a walk the pile still closed to 0.59 blocks
     * a bug.
     *
     * <p>Clamped per glyphid rather than per pair, which is what makes a number this large safe. Six pairs
     * all pushing the same way is a shove across the map; six pairs pushing outward from a ring is nothing
     * at all, and only the sum knows the difference.
     */
    private static final double MAX_PUSH = 0.15;
    /**
     * Most neighbours one glyphid is compared against per tick. A bug in the middle of a pile has dozens,
     * and the nearest few are the only ones actually touching it.
     */
    private static final int MAX_NEIGHBOURS = 6;

    private GlyphidSeparation() {
    }

    /**
     * One pass over the swarm, from the level tick. Impulses land on the next tick's {@code move()}, which
     * is where vanilla's own push would have spent them too.
     */
    public static void tick(ServerLevel level) {
        if (!SwarmBench.separation) {
            return;
        }
        Set<EntityGlyphid> swarm = GlyphidTracker.glyphids(level);
        if (swarm.size() < 2) {
            return;
        }
        long t = SwarmProfiler.begin();
        Grid grid = Grid.of(swarm);
        for (int i = 0; i < grid.count; i++) {
            grid.separate(i);
        }
        grid.apply();
        SwarmProfiler.end(SwarmProfiler.Phase.SEPARATE, t);
    }

    /**
     * @return {@code {mean nearest-neighbour distance, closest pair, how many are inside half a body width
     * of another}}. Diagnostic only: "the swarm bunches up" is not a number until something says so.
     */
    public static double[] density(ServerLevel level) {
        Set<EntityGlyphid> swarm = GlyphidTracker.glyphids(level);
        if (swarm.size() < 2) {
            return new double[]{0.0, 0.0, 0.0};
        }
        Grid grid = Grid.of(swarm);

        double total = 0.0;
        double closest = Double.MAX_VALUE;
        int overlapping = 0;
        int measured = 0;
        for (int i = 0; i < grid.count; i++) {
            double nearestSq = grid.nearestSq(i);
            if (nearestSq == Double.MAX_VALUE) {
                continue;
            }
            double nearest = Math.sqrt(nearestSq);
            total += nearest;
            closest = Math.min(closest, nearest);
            if (nearest < grid.width[i] * 0.5) {
                overlapping++;
            }
            measured++;
        }
        return new double[]{measured == 0 ? 0.0 : total / measured,
                closest == Double.MAX_VALUE ? 0.0 : closest, overlapping};
    }

    /**
     * The swarm laid out as parallel arrays in a uniform grid.
     *
     * <p>Arrays rather than a map of lists, because the whole point of replacing the entity query is to touch
     * memory in order. Each cell is a singly-linked list threaded through {@link #next} with its head in
     * {@link #heads} — the standard trick, and it allocates a fixed number of arrays for the whole swarm
     * however it happens to be spread out.
     */
    private static final class Grid {

        private static final int KEY_SHIFT = 32;
        private static final int EMPTY = -1;

        private final EntityGlyphid[] bugs;
        private final double[] x;
        private final double[] z;
        private final double[] width;
        private final int[] next;
        /**
         * Impulse accumulated this tick, applied once at the end. Accumulated rather than pushed as it is
         * found so that the clamp sees the sum: a bug in the middle of a ring is pushed from every side and
         * should end up going nowhere, which is only true if the opposing pushes are added before the limit.
         */
        private final double[] dvx;
        private final double[] dvz;
        private final Long2IntOpenHashMap heads;
        private int count;

        private Grid(int capacity) {
            bugs = new EntityGlyphid[capacity];
            x = new double[capacity];
            z = new double[capacity];
            width = new double[capacity];
            next = new int[capacity];
            dvx = new double[capacity];
            dvz = new double[capacity];
            heads = new Long2IntOpenHashMap(capacity);
            heads.defaultReturnValue(EMPTY);
        }

        static Grid of(Set<EntityGlyphid> swarm) {
            Grid grid = new Grid(swarm.size());
            int i = 0;
            for (EntityGlyphid bug : swarm) {
                // The tracker is a concurrent set and the level tick is not the only thing that touches it,
                // so the count it reported is a hint rather than a promise.
                if (i >= grid.bugs.length) {
                    break;
                }
                if (!bug.isAlive() || bug.isPassenger() || bug.isAirborne()) {
                    continue;
                }
                grid.bugs[i] = bug;
                grid.x[i] = bug.getX();
                grid.z[i] = bug.getZ();
                grid.width[i] = bug.getBbWidth();
                grid.next[i] = grid.heads.put(key(grid.x[i], grid.z[i]), i);
                i++;
            }
            grid.count = i;
            return grid;
        }

        private static long key(double x, double z) {
            return cellKey((int) Math.floor(x / CELL), (int) Math.floor(z / CELL));
        }

        private static long cellKey(int cellX, int cellZ) {
            return ((long) cellX << KEY_SHIFT) ^ (cellZ & 0xFFFFFFFFL);
        }

        /**
         * Push one glyphid off the neighbours it is standing in.
         */
        void separate(int i) {
            int cellX = (int) Math.floor(x[i] / CELL);
            int cellZ = (int) Math.floor(z[i] / CELL);
            int pushed = 0;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int j = heads.get(cellKey(cellX + dx, cellZ + dz)); j != EMPTY; j = next[j]) {
                        // Each pair once: the higher index is the one that does the work for both.
                        if (j <= i) {
                            continue;
                        }
                        if (push(i, j) && ++pushed >= MAX_NEIGHBOURS) {
                            return;
                        }
                    }
                }
            }
        }

        /**
         * @return true if the pair was close enough to have been pushed apart.
         */
        private boolean push(int i, int j) {
            double desired = (width[i] + width[j]) * 0.5 * SPACING;
            double dx = x[j] - x[i];
            double dz = z[j] - z[i];
            double distSq = dx * dx + dz * dz;
            if (distSq >= desired * desired) {
                return false;
            }

            double dist = Math.sqrt(distSq);
            if (dist < 1.0E-4) {
                // Exactly stacked, which is how a materialised warband starts. Any direction will do as long
                // as the two of them disagree about it, so it comes off the entity id rather than off a
                // random source that would pick a different one every tick and leave them shivering.
                double angle = bugs[i].getId() * 2.399963;
                dx = Math.cos(angle);
                dz = Math.sin(angle);
            } else {
                dx /= dist;
                dz /= dist;
            }

            // Proportional to how far inside each other they are, so a light touch is a light push.
            double strength = MAX_PUSH * (1.0 - dist / desired);
            dvx[j] += dx * strength;
            dvz[j] += dz * strength;
            dvx[i] -= dx * strength;
            dvz[i] -= dz * strength;
            return true;
        }

        /**
         * Spend the accumulated impulses, clamped.
         *
         * <p>A glyphid chewing is left out. It has committed to a block it can only reach from where it is
         * standing, and being shoved off it by the queue behind throws away the progress banked against that
         * block — but it still pushes, so the queue spreads out along the wall instead of stacking behind
         * the one bug that got there first.
         */
        void apply() {
            for (int i = 0; i < count; i++) {
                double magnitudeSq = dvx[i] * dvx[i] + dvz[i] * dvz[i];
                if (magnitudeSq <= 0.0 || bugs[i].mind().chewing) {
                    continue;
                }
                double scale = magnitudeSq > MAX_PUSH * MAX_PUSH
                        ? MAX_PUSH / Math.sqrt(magnitudeSq)
                        : 1.0;
                bugs[i].push(dvx[i] * scale, 0.0, dvz[i] * scale);
            }
        }

        /**
         * @return squared distance to the nearest other glyphid, or {@link Double#MAX_VALUE} if there is
         * none in the nine cells around this one.
         */
        double nearestSq(int i) {
            int cellX = (int) Math.floor(x[i] / CELL);
            int cellZ = (int) Math.floor(z[i] / CELL);
            double best = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int j = heads.get(cellKey(cellX + dx, cellZ + dz)); j != EMPTY; j = next[j]) {
                        if (j == i) {
                            continue;
                        }
                        double ddx = x[j] - x[i];
                        double ddz = z[j] - z[i];
                        best = Math.min(best, ddx * ddx + ddz * ddz);
                    }
                }
            }
            return best;
        }
    }
}
