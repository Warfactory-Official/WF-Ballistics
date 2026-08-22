package com.wf.wfballistics.entity.glyphid.brain;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link GlyphidBrain} decided one glyphid should do this tick, as data.
 *
 * <p>Applying it is the only step that touches the world. Everything here is either a value the brain computed
 * or a value it read out of a {@link GlyphidSnapshot}, so a plan can be produced anywhere and carried to
 * wherever the body happens to be.
 *
 * @param move       how to move, if at all
 * @param hopX       for {@link Move#PATH}: the waypoint to search to. Deliberately not the destination — see
 *                   {@link GlyphidBrain#HOP}
 * @param sampleY    true when {@link #hopY} is a guess the applier should replace with a real heightmap read.
 *                   The read is the applier's job because it is a world read; choosing whether it is worth
 *                   doing is the brain's
 * @param destination for {@link Move#CHARGE} and {@link Move#CHEW}: what to steer at or eat toward
 * @param lookAt     what to point the head at, or null to leave it alone
 * @param nextTask   a task to switch into, or {@link #KEEP_TASK}
 * @param elapsed    ticks this decision covered, handed back to {@link GlyphidBrain#resolve} so the stuck
 *                   accounting does not have to be recomputed from a second reading of the mind
 * @param moved      blocks covered since the last decision, on the same errand
 */
public record GlyphidPlan(
        Move move,
        int hopX,
        int hopY,
        int hopZ,
        boolean sampleY,
        @Nullable Vec3 destination,
        @Nullable Vec3 lookAt,
        boolean bite,
        int nextTask,
        int elapsed,
        double moved) {

    /**
     * Sentinel for {@link #nextTask}: leave the orders alone.
     */
    public static final int KEEP_TASK = -1;

    /**
     * Nothing to do. Shared because an idle swarm is the common case and this carries no per-glyphid state.
     */
    public static final GlyphidPlan IDLE =
            new GlyphidPlan(Move.NONE, 0, 0, 0, false, null, null, false, KEEP_TASK, 0, 0.0);

    public enum Move {
        /**
         * Leave the navigator alone. Either there is nowhere to be, or a perfectly good path is being walked.
         */
        NONE,
        /**
         * Search to {@link #hopX}/{@link #hopY}/{@link #hopZ} and walk it.
         */
        PATH,
        /**
         * Skip the pathfinder and drive the move control straight at {@link #destination}.
         */
        CHARGE,
        /**
         * Keep eating the block the mind is chewing. Movement planning is suspended while this runs.
         */
        CHEW,
        /**
         * Drop the current path. Issued when a glyphid runs out of reasons to be going anywhere, so it does not
         * keep walking to where its dead target used to be.
         */
        STOP
    }

    /**
     * A plan that only moves the head and possibly bites: the every-tick case for a glyphid already walking a
     * path it is happy with.
     */
    public static GlyphidPlan hold(@Nullable Vec3 lookAt, boolean bite) {
        return new GlyphidPlan(Move.NONE, 0, 0, 0, false, null, lookAt, bite, KEEP_TASK, 0, 0.0);
    }
}
