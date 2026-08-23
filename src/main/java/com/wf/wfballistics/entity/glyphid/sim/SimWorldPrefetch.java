package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowFields;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * The world as much of it as the off-thread pass needs, read on the world thread a tick ahead.
 *
 * <p>Holds no {@code Level}, no {@code ChunkAccess} and no {@code Entity}, so a worker given one of these has
 * nothing to reach through — see {@link SimWorld}. The world reads it serves were all made on the world
 * thread, by {@link #refill}, before the pass that reads them was dispatched.
 *
 * <p><b>It answers questions it was asked last tick, and it is asked what to fetch by being missed.</b> There
 * is no scan of the swarm to decide what to prefetch, which matters because such a scan would be O(n) of
 * exactly the work being moved off the thread. Instead a miss returns {@link SimWorld#UNKNOWN} and notes the
 * column; {@link #refill} fills the noted columns next tick; the record uses the height it had for that one
 * tick. That is not a compromise bolted on to make threading work — it is the model the tier already had. A
 * record approaches a new floor at {@code CLIMB_RATE} rather than snapping to it and keeps its last height
 * wherever the chunk is not loaded, so it is already, by design, a tick or more behind the ground under it.
 *
 * <p><b>Columns are cleared every tick; destinations are not.</b> A record caches the floor of the column it
 * is standing in for as long as it stands there, so a column only has to survive from the fill to the one read
 * that follows it — clearing bounds the map at the columns actually changed hands last tick, about a seventh
 * of the swarm. A destination has no such cache: its height is read once per repath and kept in {@code taskY}
 * for twenty ticks, so it would miss every single time. Destinations are therefore kept as a working set,
 * re-resolved every tick from whatever the last pass asked for — which is also what keeps the flow field's
 * idle timer fresh and picks up a field that has finished rebuilding.
 *
 * <p><b>Threading.</b> Nothing here is synchronised and nothing needs to be: {@link #height} and
 * {@link #destination} are called only by the pass, {@link #refill} only by the world thread with no pass in
 * flight, and the {@code Future} the two hand across is the happens-before edge in both directions. The one
 * thing that would break it is running the pass on more than one worker, which is why it does not.
 */
public final class SimWorldPrefetch implements SimWorld {

    /**
     * Surface height per column, valid for this tick only.
     */
    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();

    {
        // A missing column has to read as "not known" rather than as sea level, which is what fastutil's own
        // default of zero would say.
        heights.defaultReturnValue(UNKNOWN);
    }
    /**
     * Columns missed since the last refill. Written by the pass, read by the world thread.
     */
    private final LongOpenHashSet wanted = new LongOpenHashSet();
    /**
     * Destinations resolved at the last refill, in the order they were first asked for. A linear scan, because
     * a level has at most {@code GlyphidFlowFields.MAX_FIELDS} of them and a hash lookup on four entries costs
     * more than four comparisons.
     */
    private final List<Resolved> resolved = new ArrayList<>();
    /**
     * Destinations asked for during the pass, which become the next refill's working set. Deliberately a
     * fresh list rather than a diff: a destination nobody marches to any more simply stops being asked for
     * and drops out.
     */
    private final List<Resolved> asked = new ArrayList<>();

    private static final class Resolved {
        final int x;
        final int y;
        final int z;
        Destination what;

        Resolved(int x, int y, int z, Destination what) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.what = what;
        }
    }

    /**
     * Chunks looked up but not loaded on the last refill, purely for the report: a swarm walking over
     * unloaded terrain keeps its last height, which is correct and silent, and silent is the thing worth
     * being able to see.
     */
    private int unloaded;

    @Override
    public int height(int columnX, int columnZ) {
        long column = key(columnX, columnZ);
        int known = heights.get(column);
        if (known == UNKNOWN) {
            wanted.add(column);
        }
        return known;
    }

    @Override
    public Destination destination(int x, int y, int z) {
        for (int i = 0; i < resolved.size(); i++) {
            Resolved entry = resolved.get(i);
            if (entry.x == x && entry.y == y && entry.z == z) {
                remember(entry.x, entry.y, entry.z, entry.what);
                return entry.what;
            }
        }
        // Not resolved yet: the record pathfinds towards the destination for one tick and has the field on
        // the next. One tick of hopping along the bearing instead of following the field is a mode the tier
        // is in whenever a field has not flooded this far anyway.
        remember(x, y, z, Destination.NONE);
        return Destination.NONE;
    }

    private void remember(int x, int y, int z, Destination what) {
        for (int i = 0; i < asked.size(); i++) {
            Resolved entry = asked.get(i);
            if (entry.x == x && entry.y == y && entry.z == z) {
                return;
            }
        }
        asked.add(new Resolved(x, y, z, what));
    }

    /**
     * Do this tick's world reads, on the world thread, before the pass that will read them is dispatched.
     *
     * <p>The only place in the tier that touches a chunk, and the reason the rest of it can run anywhere. Its
     * cost is the same reads the pass used to make inline — one heightmap lookup per column a glyphid walked
     * into — just made from here instead.
     */
    public void refill(ServerLevel level) {
        WorldThread.assertOn("the glyphid sim tier's terrain prefetch");
        heights.clear();
        unloaded = 0;
        for (LongIterator it = wanted.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            int columnX = (int) (column >> 32);
            int columnZ = (int) column;
            if (!level.hasChunk(columnX >> 4, columnZ >> 4)) {
                unloaded++;
                continue;
            }
            heights.put(column,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, columnX, columnZ));
        }
        wanted.clear();

        resolved.clear();
        for (int i = 0; i < asked.size(); i++) {
            Resolved entry = asked.get(i);
            entry.what = new Destination(
                    GlyphidFlowFields.fieldFor(level, entry.x, entry.y, entry.z),
                    level.hasChunk(entry.x >> 4, entry.z >> 4)
                            ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, entry.x, entry.z)
                            : UNKNOWN);
            resolved.add(entry);
        }
        asked.clear();
    }

    /**
     * Drop everything, for a tier that has been switched off or a level that has gone away.
     */
    public void clear() {
        heights.clear();
        wanted.clear();
        resolved.clear();
        asked.clear();
        unloaded = 0;
    }

    /**
     * @return {@code {columns held, columns wanted, destinations resolved, columns in unloaded chunks}}, for
     * {@code swarmbench simthread}.
     */
    public int[] stats() {
        return new int[]{heights.size(), wanted.size(), resolved.size(), unloaded};
    }

    private static long key(int columnX, int columnZ) {
        return ((long) columnX << 32) ^ (columnZ & 0xFFFFFFFFL);
    }
}
