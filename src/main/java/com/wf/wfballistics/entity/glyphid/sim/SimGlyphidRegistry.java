package com.wf.wfballistics.entity.glyphid.sim;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.drone.WorldThread;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Per-dimension store of the glyphids that are records rather than entities. */
public final class SimGlyphidRegistry extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = "wfballistics_sim_glyphids";

    private final List<SimGlyphid> glyphids = new ArrayList<>();
    /** Next identity, counting down. Saved so a reload cannot reissue an id a live record still holds. */
    private int nextId = -1;
    /** The pass allowed to mutate {@link #glyphids}, or null. Volatile only so a stale null cannot be read. */
    private volatile @Nullable Future<?> pass;
    /** World-thread nanos spent waiting for the pass. Accumulated here, since the join can happen anywhere. */
    private long stallNanos;

    public static SimGlyphidRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SimGlyphidRegistry::new, (tag, reg) -> SimGlyphidRegistry.load(tag)),
                NAME);
    }

    public static SimGlyphidRegistry load(CompoundTag tag) {
        SimGlyphidRegistry registry = new SimGlyphidRegistry();
        ListTag list = tag.getList("Glyphids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            registry.glyphids.add(SimGlyphid.load(list.getCompound(i)));
        }
        registry.nextId = tag.contains("nextId") ? tag.getInt("nextId") : -1;
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        await();
        ListTag list = new ListTag();
        for (SimGlyphid glyphid : glyphids) {
            list.add(glyphid.save());
        }
        tag.put("Glyphids", list);
        tag.putInt("nextId", nextId);
        return tag;
    }

    /** The live list, once the pass is done with it. Mutated in place, so copy before removing while walking. */
    public List<SimGlyphid> view() {
        await();
        return glyphids;
    }

    public int count() {
        await();
        return glyphids.size();
    }

    /**
     * Hand the list to a pass. Only {@link SimGlyphidPass} may call this, and only with nothing in flight.
     */
    void dispatch(Future<?> running) {
        pass = running;
    }

    /** Wait for the pass, if there is one. */
    public void await() {
        Future<?> running = pass;
        if (running == null) {
            return;
        }
        WorldThread.assertOn("waiting for the glyphid sim pass");
        long start = System.nanoTime();
        try {
            running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.error("[wfballistics] the glyphid sim pass failed", e.getCause());
        } finally {
            pass = null;
            stallNanos += System.nanoTime() - start;
        }
    }

    /**
     * @return world-thread nanos spent waiting since this was last asked, and reset.
     */
    long takeStall() {
        long spent = stallNanos;
        stallNanos = 0L;
        return spent;
    }

    public int claimId() {
        setDirty();
        return nextId--;
    }

    public void add(SimGlyphid glyphid) {
        glyphids.add(glyphid);
        setDirty();
    }

    public void remove(SimGlyphid glyphid) {
        glyphids.remove(glyphid);
        setDirty();
    }
}
