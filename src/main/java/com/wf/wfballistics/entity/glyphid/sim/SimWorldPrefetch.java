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

/** As much of the world as the off-thread pass needs, read on the world thread a tick ahead. */
public final class SimWorldPrefetch implements SimWorld {

    /** Surface height per column, valid for this tick only. */
    private final Long2IntOpenHashMap heights = new Long2IntOpenHashMap();

    {
        // A missing column must read as "not known" rather than as sea level, which zero would say.
        heights.defaultReturnValue(UNKNOWN);
    }
    /** Columns missed since the last refill. Written by the pass, read by the world thread. */
    private final LongOpenHashSet wanted = new LongOpenHashSet();
    /** Destinations resolved at the last refill. */
    private final List<Resolved> resolved = new ArrayList<>();
    /** Destinations asked for during the pass. A fresh list, so one nobody marches to drops out by itself. */
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

    /** Chunks looked up but not loaded, for the report: keeping the last height is correct but silent. */
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

    /** Do this tick's world reads, on the world thread, before the pass is dispatched. */
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

    /** Drop everything, for a tier that has been switched off or a level that has gone away. */
    public void clear() {
        heights.clear();
        wanted.clear();
        resolved.clear();
        asked.clear();
        unloaded = 0;
    }

    /**
     * @return {@code {columns held, columns wanted, destinations resolved, columns in unloaded chunks}}, for
     *      {@code swarmbench simthread}.
     */
    public int[] stats() {
        return new int[]{heights.size(), wanted.size(), resolved.size(), unloaded};
    }

    private static long key(int columnX, int columnZ) {
        return ((long) columnX << 32) ^ (columnZ & 0xFFFFFFFFL);
    }
}
