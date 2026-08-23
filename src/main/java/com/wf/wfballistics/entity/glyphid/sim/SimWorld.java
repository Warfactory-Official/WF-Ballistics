package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowField;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a {@link SimGlyphid} is allowed to know about the world, and the only way it may ask.
 *
 * <p>Three questions, which is the whole of the tier's terrain model: how high the ground is under a column,
 * how high it is at the destination, and which way the shared flow field points. Everything else a record
 * does is arithmetic on its own fields.
 *
 * <p><b>The interface exists so the pass can run off the world thread.</b> {@code ServerLevel} is not
 * thread-safe and is not meant to be: {@code getHeight} goes through the chunk source, whose last-chunk cache
 * is written without synchronisation and which will load a chunk if asked for one that is not there. A worker
 * holding a level would work under test and corrupt a chunk under load, which is the failure mode
 * {@code WorldThread} exists to prevent. So the level does not cross the line at all — the worker is handed a
 * {@link SimWorldPrefetch}, which holds no world reference and could not read a chunk if it wanted to. That is
 * a compile-time guarantee rather than a convention, the same one {@code DroneSnapshot} gives the drone
 * planner.
 *
 * <p>{@link SimWorldLive} is the same three questions asked straight through to the level, for the pass run
 * synchronously. Both implementations answer identically; only the thread differs, which is what makes
 * {@code swarmbench simasync on|off} a fair comparison rather than two different simulations.
 */
public interface SimWorld {

    /**
     * Returned wherever the answer is not available: no chunk, or a column the prefetch has not been asked to
     * fill yet. Callers keep whatever they had, which is what the tier already does over unloaded terrain.
     */
    int UNKNOWN = Integer.MIN_VALUE;

    /**
     * @return surface height in a column, or {@link #UNKNOWN}.
     */
    int height(int columnX, int columnZ);

    /**
     * @return what is known about a march destination. Never null; the fields inside may be absent.
     */
    Destination destination(int x, int y, int z);

    /**
     * The two things a destination is worth knowing: the field that leads to it and how high its ground is.
     *
     * <p>One lookup rather than two because they expire together. A destination's height has no per-record
     * cache to hide behind — it is read once per repath and kept in {@code taskY} for twenty ticks — so it
     * cannot ride on the column cache, which is cleared every tick. Keyed by the same triple as the field,
     * and there are at most a handful of destinations in a level however large the swarm is.
     */
    record Destination(@Nullable GlyphidFlowField field, int height) {

        public static final Destination NONE = new Destination(null, UNKNOWN);
    }
}
