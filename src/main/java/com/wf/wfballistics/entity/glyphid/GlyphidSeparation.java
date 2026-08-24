package com.wf.wfballistics.entity.glyphid;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidCarrier;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidRegistry;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Set;

/**
 * Keeps a swarm spread out, without asking the world what is nearby.
 *
 * <p>Vanilla push is suppressed ({@link EntityGlyphid#pushEntities}) because it was the only measured cost
 * that grew faster than the swarm: 1.8% of the tick at a hundred, 15.5% at three hundred. The cost was the
 * broadphase, not the push — a per-entity inflated-box query walking entity sections every tick. So the push
 * stays and the query goes: one uniform grid per level tick from {@link GlyphidTracker}, each bug compared
 * against the nine cells around it. Each pair is visited once and pushed both ways, so the result is
 * symmetric.
 *
 * <p>Not collision: an impulse is added to the velocity {@code move()} will spend, and a pair still
 * overlapping at the end of the tick simply pushes again.
 *
 * <p>Both tiers go in one grid. They walk the same flow field to the same column, so separating only the
 * entities would give a spread front rank followed by two hundred records in one block.
 */
public final class GlyphidSeparation {

    /** Grid pitch. Sized to the widest pair (a behemoth is 2.5), so nine cells hold everything that matters. */
    private static final double CELL = 3.0;
    /** Fraction of touching distance a pair holds. Below 1, so a column can still funnel through a doorway. */
    private static final double SPACING = 0.8;
    /**
     * Hardest push one glyphid takes in a tick, summed over every neighbour. Comparable to a walk (~0.2
     * blocks a tick) on purpose: collision is off, so a push weaker than the inward drive only slows the
     * compression — at a fifth of a walk the pile still closed to 0.59 blocks a bug.
     *
     * <p>Clamped per glyphid rather than per pair, which is what makes a number this large safe: only the sum
     * can tell six pushes one way from six pushing outward from a ring.
     */
    private static final double MAX_PUSH = 0.15;
    /** Most neighbours compared per tick. A bug in a pile has dozens; the nearest few are the ones on it. */
    private static final int MAX_NEIGHBOURS = 6;

    private GlyphidSeparation() {
    }

    /** One pass over the swarm. Impulses land on the next {@code move()}, where vanilla push spent them. */
    public static void tick(ServerLevel level) {
        if (!SwarmBench.separation) {
            return;
        }
        Set<EntityGlyphid> bodies = GlyphidTracker.glyphids(level);
        List<SimGlyphid> records = SimGlyphidRegistry.get(level).view();
        if (bodies.size() + records.size() < 2) {
            return;
        }
        long t = SwarmProfiler.begin();
        Grid grid = Grid.of(bodies, records);
        for (int i = 0; i < grid.count; i++) {
            grid.separate(i);
        }
        grid.apply();
        SwarmProfiler.end(SwarmProfiler.Phase.SEPARATE, t);
    }

    /**
     * @return {@code {mean nearest-neighbour distance, closest pair, how many are inside half a body width
     * of another}}. Diagnostic only.
     */
    public static double[] density(ServerLevel level) {
        Set<EntityGlyphid> bodies = GlyphidTracker.glyphids(level);
        List<SimGlyphid> records = SimGlyphidRegistry.get(level).view();
        if (bodies.size() + records.size() < 2) {
            return new double[]{0.0, 0.0, 0.0};
        }
        Grid grid = Grid.of(bodies, records);

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
     * The swarm as parallel arrays in a uniform grid — arrays rather than a map of lists, since the point of
     * dropping the entity query is to touch memory in order. Each cell is a linked list threaded through
     * {@link #next} from a head in {@link #heads}, so the allocation is fixed however the swarm is spread.
     */
    private static final class Grid {

        private static final int KEY_SHIFT = 32;
        private static final int EMPTY = -1;

        private final GlyphidCarrier[] bugs;
        private final double[] x;
        private final double[] z;
        private final double[] width;
        private final int[] next;
        /**
         * Impulse accumulated this tick, applied once at the end so the clamp sees the sum. A bug in the
         * middle of a ring should go nowhere, which is only true if opposing pushes are added before the
         * limit.
         */
        private final double[] dvx;
        private final double[] dvz;
        private final Long2IntOpenHashMap heads;
        private int count;

        private Grid(int capacity) {
            bugs = new GlyphidCarrier[capacity];
            x = new double[capacity];
            z = new double[capacity];
            width = new double[capacity];
            next = new int[capacity];
            dvx = new double[capacity];
            dvz = new double[capacity];
            heads = new Long2IntOpenHashMap(capacity);
            heads.defaultReturnValue(EMPTY);
        }

        static Grid of(Set<EntityGlyphid> bodies, List<SimGlyphid> records) {
            Grid grid = new Grid(bodies.size() + records.size());
            int i = 0;
            for (EntityGlyphid bug : bodies) {
                // The tracker is concurrent, so the count it reported is a hint rather than a promise.
                if (i >= grid.bugs.length) {
                    break;
                }
                i = grid.place(i, bug);
            }
            for (int r = 0; r < records.size() && i < grid.bugs.length; r++) {
                i = grid.place(i, records.get(r));
            }
            grid.count = i;
            return grid;
        }

        /**
         * @return the next free slot, unchanged if this body is not one that can be shoved.
         */
        private int place(int i, GlyphidCarrier bug) {
            if (!bug.carrierPushable()) {
                return i;
            }
            bugs[i] = bug;
            x[i] = bug.carrierX();
            z[i] = bug.carrierZ();
            width[i] = bug.carrierWidth();
            next[i] = heads.put(key(x[i], z[i]), i);
            return i + 1;
        }

        private static long key(double x, double z) {
            return cellKey((int) Math.floor(x / CELL), (int) Math.floor(z / CELL));
        }

        private static long cellKey(int cellX, int cellZ) {
            return ((long) cellX << KEY_SHIFT) ^ (cellZ & 0xFFFFFFFFL);
        }

        /** Push one glyphid off the neighbours it is standing in. */
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
                // Exactly stacked, as a materialised warband starts. Any direction will do so long as the
                // two disagree, so it comes off the id -- a random source would re-pick and leave them
                // shivering.
                double angle = bugs[i].carrierId() * 2.399963;
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
         * Spend the accumulated impulses, clamped. A chewing glyphid is exempt — shoving it off the block it
         * committed to throws the banked progress away — but it still pushes, so the queue behind spreads
         * along the wall rather than stacking.
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
                bugs[i].carrierPush(dvx[i] * scale, dvz[i] * scale);
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
