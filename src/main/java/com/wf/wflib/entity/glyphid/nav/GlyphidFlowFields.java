package com.wf.wflib.entity.glyphid.nav;

import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The few flow fields a level has going, and the budget they are built inside. */
public final class GlyphidFlowFields {

    /** Destinations are rounded to this many blocks before a field is looked up. */
    private static final int GRAIN = 8;
    /** Columns of flood spent per level per tick, across all fields. */
    private static final int BUDGET = 768;
    /** Fields kept per level: one per squad, since that is the most destinations wanted at once. */
    private static final int MAX_FIELDS = 4;
    /** Ticks a completed field is trusted before it is rebuilt. */
    private static final int MAX_AGE = 400;
    /** Ticks a field with no readers is kept before it is dropped. */
    private static final int IDLE_TIMEOUT = 200;

    private static final Map<ResourceKey<Level>, List<Entry>> BY_LEVEL = new HashMap<>();

    private GlyphidFlowFields() {
    }

    private static final class Entry {
        final int keyX;
        final int keyZ;
        /** What readers get. */
        GlyphidFlowField field;
        @Nullable GlyphidFlowField building;
        long builtAtTick;
        long lastReadTick;
        boolean stale;

        Entry(int keyX, int keyZ, GlyphidFlowField field, long tick) {
            this.keyX = keyX;
            this.keyZ = keyZ;
            this.field = field;
            this.builtAtTick = tick;
            this.lastReadTick = tick;
        }
    }

    /**
     * @return the field for this destination, building one if the level has room. Null when flow navigation
     *      is switched off, or while a brand new field has not yet flooded as far as the caller.
     */
    public static @Nullable GlyphidFlowField fieldFor(ServerLevel level, int x, int y, int z) {
        if (!SwarmBench.flowField) {
            return null;
        }
        List<Entry> entries = BY_LEVEL.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
        int keyX = Math.floorDiv(x, GRAIN);
        int keyZ = Math.floorDiv(z, GRAIN);
        long now = level.getGameTime();

        for (Entry entry : entries) {
            if (entry.keyX == keyX && entry.keyZ == keyZ) {
                entry.lastReadTick = now;
                return entry.field;
            }
        }

        if (entries.size() >= MAX_FIELDS) {
            Entry coldest = entries.get(0);
            for (Entry entry : entries) {
                if (entry.lastReadTick < coldest.lastReadTick) {
                    coldest = entry;
                }
            }
            if (now - coldest.lastReadTick < IDLE_TIMEOUT) {
                // Every field is in use. Better to pathfind for a while than to thrash four floods.
                return null;
            }
            entries.remove(coldest);
        }

        Entry entry = new Entry(keyX, keyZ, new GlyphidFlowField(level, x, y, z), now);
        entries.add(entry);
        return entry.field;
    }

    /** Spend this level's build budget, retire fields nobody is reading, and rebuild the stale ones. */
    public static void tick(ServerLevel level) {
        List<Entry> entries = BY_LEVEL.get(level.dimension());
        if (entries == null || entries.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        long t = SwarmProfiler.begin();

        entries.removeIf(entry -> now - entry.lastReadTick > IDLE_TIMEOUT);

        int budget = BUDGET;
        for (Entry entry : entries) {
            if (budget <= 0) {
                break;
            }
            if (entry.building == null && entry.field.complete()
                    && (entry.stale || now - entry.builtAtTick > MAX_AGE)) {
                entry.building = new GlyphidFlowField(level, entry.field.centreX(), entry.field.centreY(),
                        entry.field.centreZ());
                entry.stale = false;
            }

            GlyphidFlowField flooding = entry.building != null ? entry.building : entry.field;
            budget -= flooding.build(level, budget);

            if (entry.building != null && entry.building.complete()) {
                entry.field = entry.building;
                entry.building = null;
                entry.builtAtTick = now;
            }
        }
        SwarmProfiler.end(SwarmProfiler.Phase.FLOW, t);
    }

    /** Note that terrain changed at a position, so any field covering it floods again. */
    public static void invalidate(ServerLevel level, BlockPos pos) {
        List<Entry> entries = BY_LEVEL.get(level.dimension());
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry.field.covers(pos.getX(), pos.getZ())) {
                entry.stale = true;
            }
        }
    }

    /**
     * @return one line per live field, for {@code /wflib swarmbench flowfields}.
     */
    public static List<String> report(ServerLevel level) {
        List<Entry> entries = BY_LEVEL.get(level.dimension());
        List<String> lines = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            lines.add("No flow fields." + (SwarmBench.flowField ? "" : " (switched off)"));
            return lines;
        }
        long now = level.getGameTime();
        for (Entry entry : entries) {
            lines.add(String.format(java.util.Locale.ROOT,
                    "  field at (%d, %d): %d columns, %s%s, read %d ticks ago",
                    entry.field.centreX(), entry.field.centreZ(), entry.field.filled(),
                    entry.field.complete() ? "complete" : "building",
                    entry.stale ? ", stale" : "", now - entry.lastReadTick));
        }
        return lines;
    }

    public static void clear() {
        BY_LEVEL.clear();
    }
}
