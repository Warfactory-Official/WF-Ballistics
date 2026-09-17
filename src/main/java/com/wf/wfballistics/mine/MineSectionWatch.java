package com.wf.wfballistics.mine;

import com.wf.wfballistics.WFBallistics;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A revision counter for the subchunks that something is resting on. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MineSectionWatch {

    /** The revision of a section nothing is watching. Never equal to a live revision. */
    public static final int UNWATCHED = Integer.MIN_VALUE;

    private static final Map<Level, Sections> BY_LEVEL = new ConcurrentHashMap<>();

    /** Non-zero when any section anywhere is watched: the first thing every block write in the game asks. */
    private static volatile int watched = 0;

    private MineSectionWatch() {
    }

    /** @return true if any section is being watched at all. */
    public static boolean isWatching() {
        return watched != 0;
    }

    /**
     * Starts watching the section containing {@code pos}, or takes another reference on it.
     *
     * @return the section's current revision, to be compared against later
     */
    public static int watch(ServerLevel level, long section) {
        Sections sections = BY_LEVEL.computeIfAbsent(level, key -> new Sections());
        int version = sections.versions.get(section);
        if (sections.refs.addTo(section, 1) == 0) {
            sections.versions.put(section, version = 0);
            watched++;
        }
        return version;
    }

    /** Drops a reference taken by {@link #watch}. */
    public static void unwatch(ServerLevel level, long section) {
        Sections sections = BY_LEVEL.get(level);
        if (sections == null) {
            return;
        }
        int refs = sections.refs.get(section);
        if (refs <= 0) {
            return;
        }
        if (refs == 1) {
            sections.refs.remove(section);
            sections.versions.remove(section);
            watched--;
            if (sections.refs.isEmpty()) {
                BY_LEVEL.remove(level, sections);
            }
        } else {
            sections.refs.put(section, refs - 1);
        }
    }

    /** @return the section's current revision, or {@link #UNWATCHED} if nothing is watching it. */
    public static int version(ServerLevel level, long section) {
        Sections sections = BY_LEVEL.get(level);
        if (sections == null) {
            return UNWATCHED;
        }
        return sections.refs.containsKey(section) ? sections.versions.get(section) : UNWATCHED;
    }

    /** @return the key {@link #watch} and {@link #version} take for the section containing {@code pos}. */
    public static long key(BlockPos pos) {
        return SectionPos.asLong(pos);
    }

    /** Every block write in a loaded chunk lands here. Gated on {@link #isWatching} by the caller. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        Sections sections = BY_LEVEL.get(level);
        if (sections == null) {
            return;
        }
        long section = SectionPos.asLong(pos);
        int version = sections.versions.get(section);
        if (version != sections.versions.defaultReturnValue()) {
            sections.versions.put(section, version + 1);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        Sections sections = BY_LEVEL.remove(event.getLevel());
        if (sections != null) {
            watched -= sections.refs.size();
        }
    }

    private static final class Sections {

        private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
        private final Long2IntOpenHashMap refs = new Long2IntOpenHashMap();

        private Sections() {
            versions.defaultReturnValue(UNWATCHED);
            refs.defaultReturnValue(0);
        }
    }
}
