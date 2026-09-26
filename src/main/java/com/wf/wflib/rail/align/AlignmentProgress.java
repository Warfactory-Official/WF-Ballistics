package com.wf.wflib.rail.align;

import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turning track laid in the world into build progress on a route.
 *
 * <p>This is what keeps {@link RouteStatus#BUILT} honest. A status somebody has to remember to set is a
 * label; a status the act of building sets is a fact. A track layer knows where it just put a rail and
 * nothing else, so the work here is the other direction: given a position, which route is this and how
 * far along it are we.</p>
 *
 * <p>The projection is the expensive part and it is cached per route, keyed by revision so a re-aligned
 * route rebuilds and an unchanged one does not. A machine works along a route rather than jumping about
 * it, so each lookup starts from where the last one landed and only falls back to a full scan when that
 * fails. Without that, a fast layer on a 20 km main line would scan five thousand samples per rail.</p>
 */
public final class AlignmentProgress {

    /** How finely a route is sampled for projection. Below the width of the formation. */
    private static final double PROJECT_STEP = 4.0;

    /** How far either side of the last hit to look before giving up and scanning the whole route. */
    private static final int LOCAL_WINDOW = 64;

    /** How many routes keep a projection. More than any one machine works on at once. */
    private static final int CACHE_SIZE = 16;

    /** Half the length of route one report marks built. Enough that consecutive reports join up. */
    public static final double REPORT_HALF_WINDOW = 4.0;

    private static final Map<UUID, Projection> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Projection> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private AlignmentProgress() {
    }

    /** A route flattened into points at known chainages, with the last place anything was found on it. */
    private static final class Projection {
        private final int revision;
        private final double[] xs;
        private final double[] zs;
        private final double[] chainage;
        private int lastHit = -1;

        private Projection(int revision, Centreline centreline) {
            double length = centreline.length();
            int count = Math.max(2, (int) Math.ceil(length / PROJECT_STEP) + 1);
            this.revision = revision;
            this.xs = new double[count];
            this.zs = new double[count];
            this.chainage = new double[count];
            for (int i = 0; i < count; i++) {
                double s = Math.min(length, length * i / (count - 1));
                AlignElement.Sample sample = centreline.at(s);
                this.xs[i] = sample.x();
                this.zs[i] = sample.z();
                this.chainage[i] = s;
            }
        }

        /** @return the index of the nearest sample, or -1 when nothing is within {@code tolerance}. */
        private int nearest(double x, double z, double tolerance) {
            double limit = tolerance * tolerance;
            int found = this.lastHit >= 0
                    ? scan(x, z, limit, Math.max(0, this.lastHit - LOCAL_WINDOW),
                            Math.min(this.xs.length - 1, this.lastHit + LOCAL_WINDOW))
                    : -1;
            if (found < 0) {
                found = scan(x, z, limit, 0, this.xs.length - 1);
            }
            if (found >= 0) {
                this.lastHit = found;
            }
            return found;
        }

        private double distance(int index, double x, double z) {
            return Math.hypot(this.xs[index] - x, this.zs[index] - z);
        }

        private double length() {
            return this.chainage[this.chainage.length - 1];
        }

        private int scan(double x, double z, double limit, int from, int to) {
            int best = -1;
            double bestDist = limit;
            for (int i = from; i <= to; i++) {
                double dx = this.xs[i] - x;
                double dz = this.zs[i] - z;
                double d = dx * dx + dz * dz;
                if (d <= bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return best;
        }
    }

    private static Projection projectionOf(Alignment alignment) {
        Projection cached = CACHE.get(alignment.id());
        if (cached != null && cached.revision == alignment.revision().number()) {
            return cached;
        }
        Projection built = new Projection(alignment.revision().number(),
                alignment.compile().centreline());
        CACHE.put(alignment.id(), built);
        return built;
    }

    /**
     * How far along a route a position is.
     *
     * @return the chainage, or {@code Double.NaN} when the position is further than {@code tolerance}
     *         from the route
     */
    public static double chainageAt(Alignment alignment, double x, double z, double tolerance) {
        Projection projection = projectionOf(alignment);
        int index = projection.nearest(x, z, tolerance);
        return index < 0 ? Double.NaN : projection.chainage[index];
    }

    /**
     * Report track laid or removed at a position, on whichever route is nearest.
     *
     * <p>Meant to be called by whatever actually changes the world: a track layer as it works, or a
     * block break handler when rails come up. It is safe to call with a position on no route at all.</p>
     *
     * <p>Nothing is sent to anyone here. The store is written and {@link AlignmentService#tick} carries
     * it out within three seconds, because a machine laying ten rails a second must not push a packet to
     * every player ten times a second to say the built length grew by four blocks.</p>
     *
     * @param faction the faction whose routes to consider, or null for every route in the level
     * @param minTolerance how far from a route still counts as on it, in blocks. Each route uses this
     *                     or its own clearance width, whichever is larger, so a main line is forgiving
     *                     of a rail laid off the exact centreline and a yard throat is not.
     * @param laid true for track down, false for track gone
     * @return the route that was updated, or null when the position was on none of them
     */
    public static UUID report(ServerLevel level, UUID faction, double x, double z, double minTolerance,
                              boolean laid) {
        Hit hit = nearest(level, faction, x, z, minTolerance);
        if (hit == null) {
            return null;
        }
        AlignmentStore store = AlignmentStore.of(level);
        Alignment updated = AlignmentEdits.applyBuild(hit.alignment(),
                hit.chainage() - REPORT_HALF_WINDOW, hit.chainage() + REPORT_HALF_WINDOW, laid,
                hit.length());
        if (!updated.equals(hit.alignment())) {
            // No revision bump: a machine reporting its own work is not an edit anyone can conflict
            // with, and bumping it would rebuild the projection cache on every rail laid.
            store.put(updated);
        }
        return hit.alignment().id();
    }

    /**
     * Which route runs under a position, and where along it.
     *
     * @param length the whole route's length, which the caller usually needs alongside the chainage
     */
    public record Hit(Alignment alignment, double chainage, double distance, double length) {
    }

    /**
     * The route nearest a position.
     *
     * @param faction whose routes to consider, or null for every route in the level
     * @param minTolerance a floor on how far off a route still counts; each route widens it to its own
     *                     clearance width
     */
    public static Hit nearest(ServerLevel level, UUID faction, double x, double z, double minTolerance) {
        AlignmentStore store = AlignmentStore.of(level);
        Hit best = null;
        for (Alignment alignment : faction == null ? store.all() : store.byFaction(faction)) {
            if (alignment.points().size() < 2) {
                continue;
            }
            Projection projection = projectionOf(alignment);
            double tolerance = Math.max(minTolerance, alignment.designClass().clearanceWidth());
            int index = projection.nearest(x, z, tolerance);
            if (index < 0) {
                continue;
            }
            double distance = projection.distance(index, x, z);
            if (best == null || distance < best.distance()) {
                best = new Hit(alignment, projection.chainage[index], distance, projection.length());
            }
        }
        return best;
    }

    /** Drop every cached projection. Called when a world unloads. */
    public static void clearCache() {
        CACHE.clear();
    }
}
