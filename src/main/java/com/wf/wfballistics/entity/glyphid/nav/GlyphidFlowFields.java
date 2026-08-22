package com.wf.wfballistics.entity.glyphid.nav;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The few flow fields a level has going, and the budget they are built inside.
 *
 * <p>One field per place a swarm is walking to, not one per glyphid — which is the entire saving. Destinations
 * are rounded to {@link #GRAIN} blocks before they are looked up, so a squad whose objective drifts by a block
 * keeps the field it already has rather than throwing away a flood and starting another.
 *
 * <p><b>Budgeted, and deliberately small.</b> A complete field is around nine thousand columns; flooding it in
 * one tick would be a two-millisecond spike on the tick it landed, which is precisely the shape of stutter
 * §11.8 says matters more than the mean. {@link #BUDGET} columns a tick spreads it over a second or so, and
 * because the flood grows outward from the destination the glyphids nearest it are served first.
 *
 * <p><b>Invalidated by digging, not by a timer alone.</b> A swarm chewing a wall open changes what is walkable,
 * and a field that did not notice would keep steering everyone at a hole that is no longer the only way in.
 * {@link #invalidate} is called from the block-breaking path; the age limit is the backstop for everything
 * else that can change terrain.
 */
public final class GlyphidFlowFields {

    /**
     * Destinations are rounded to this many blocks before a field is looked up.
     */
    private static final int GRAIN = 8;
    /**
     * Columns of flood spent per level per tick, across all fields.
     */
    private static final int BUDGET = 768;
    /**
     * Fields kept per level. Four squads is the most a swarm splits into, so four destinations is the most
     * that can be wanted at once; a fifth evicts whichever has gone longest without a reader.
     */
    private static final int MAX_FIELDS = 4;
    /**
     * Ticks a completed field is trusted before it is rebuilt from scratch.
     *
     * <p>The backstop, not the mechanism. Glyphids chewing report their own holes and glyphids that get stuck
     * following the field report that it is wrong, which between them cover everything that happens because
     * of the swarm. This covers what happens to it: a player bricking up a doorway, a piston, a
     * {@code /fill}. Twenty seconds is the longest a swarm should walk at a wall that is no longer a way in.
     */
    private static final int MAX_AGE = 400;
    /**
     * Ticks a field with no readers is kept before it is dropped.
     */
    private static final int IDLE_TIMEOUT = 200;

    private static final Map<ResourceKey<Level>, List<Entry>> BY_LEVEL = new HashMap<>();

    private GlyphidFlowFields() {
    }

    private static final class Entry {
        final int keyX;
        final int keyZ;
        /**
         * What readers get. Never replaced by a half-flooded one: a rebuild floods {@link #building}
         * alongside it and only swaps when that is finished, so terrain changing under a swarm never leaves
         * it with no field at all — which is worse than a slightly stale one, because it puts three hundred
         * glyphids back on the pathfinder for the dozen ticks the reflood takes.
         */
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
     * is switched off, or while a brand new field has not yet flooded as far as the caller.
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

    /**
     * Spend this level's build budget, retire fields nobody is reading, and rebuild the stale ones.
     */
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

    /**
     * Note that terrain changed at a position, so any field covering it floods again.
     */
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
     * @return one line per live field, for {@code /wfballistics swarmbench flowfields}.
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
