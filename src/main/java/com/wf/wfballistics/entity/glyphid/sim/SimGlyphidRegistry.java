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

/**
 * Per-dimension store of the glyphids that are records rather than entities, mirroring
 * {@code SimDroneRegistry}. Data only: the decisions live in {@link SimGlyphidManager}.
 *
 * <p><b>Persisted, and it has to be.</b> A swarm three hundred strong is mostly records for most of its
 * march, and a shutdown that dropped them would delete an attack in transit without saying so — the class of
 * failure where the simulation quietly does less than it claims. Vanilla saves entities; this saves what
 * replaced them.
 *
 * <p><b>It is also the gate on the off-thread pass.</b> {@link SimGlyphidPass} hands this list to a worker for
 * the length of a level tick, and for that window nothing else may touch it. Rather than leaving that as a
 * rule for callers to remember, {@link #view()} joins the pass before handing the list over — so the
 * invariant holds by construction for the explosion that lands mid-tick, the command that counts records, and
 * every caller written after this one.
 */
public final class SimGlyphidRegistry extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = "wfballistics_sim_glyphids";

    private final List<SimGlyphid> glyphids = new ArrayList<>();
    /**
     * Next identity to hand out, counting down. Saved so a reload cannot reissue an id a live record still
     * holds, which on the client would be two glyphids sharing one slot in the draw list.
     */
    private int nextId = -1;
    /**
     * The pass currently allowed to mutate {@link #glyphids}, or null. Volatile only so a stale null cannot be
     * read: it is set and cleared on the world thread and never touched by the worker.
     */
    private volatile @Nullable Future<?> pass;
    /**
     * World-thread nanos spent waiting for the pass since this was last read. Accumulated here rather than at
     * the join because the join can happen anywhere — see {@link #await()}.
     */
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

    /**
     * The live list, once the off-thread pass has finished with it. Mutated in place by the manager's passes,
     * so callers that add or remove while iterating must copy first.
     */
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

    /**
     * Wait for the pass, if there is one.
     *
     * <p>Called from every route into the list rather than from one place in the tick, because the routes are
     * not all in the tick: an explosion damages records from inside the entity tick, and a command counts them
     * from outside the level tick entirely. A join that only happened at the scheduled point would leave those
     * racing a live worker, which is the kind of bug that shows up as one glyphid in a thousand having moved
     * twice.
     */
    public void await() {
        Future<?> running = pass;
        if (running == null) {
            return;
        }
        // A worker waiting on its own pass is a deadlocked server, and it is the one way this gate can be
        // worse than the rule it replaced. Nothing the pass runs reaches the registry today; this is so that
        // whatever gets added to it later fails loudly on the first tick rather than hanging the server.
        WorldThread.assertOn("waiting for the glyphid sim pass");
        long start = System.nanoTime();
        try {
            running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // The records keep last tick's positions and the next pass picks up from there. Logged rather
            // than rethrown: a swarm that stops walking is a bug report, a server that stops is an outage.
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
