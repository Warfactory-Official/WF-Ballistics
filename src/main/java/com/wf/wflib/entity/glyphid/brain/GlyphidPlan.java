package com.wf.wflib.entity.glyphid.brain;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link GlyphidBrain} decided one glyphid should do this tick, as data.
 *
 * @param move how to move, if at all
 * @param hopX for {@link Move#PATH}: the waypoint to search to, not the destination; see
 *      {@link GlyphidBrain#HOP}
 * @param sampleY true when {@link #hopY} is a guess the applier should replace with a real heightmap read
 * @param destination for {@link Move#CHARGE} and {@link Move#CHEW}: what to steer at or eat toward
 * @param lookAt what to point the head at, or null to leave it alone
 * @param nextTask a task to switch into, or {@link #KEEP_TASK}
 * @param elapsed ticks this decision covered, handed back to {@link GlyphidBrain#resolve}
 * @param moved blocks covered since the last decision, on the same errand
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

    /** Sentinel for {@link #nextTask}: leave the orders alone. */
    public static final int KEEP_TASK = -1;

    /** Nothing to do. Shared, since it carries no per-glyphid state and an idle swarm is the common case. */
    public static final GlyphidPlan IDLE =
            new GlyphidPlan(Move.NONE, 0, 0, 0, false, null, null, false, KEEP_TASK, 0, 0.0);

    public enum Move {
        /** Leave the navigator alone: nowhere to be, or a good path already being walked. */
        NONE,
        /** Search to {@link #hopX}/{@link #hopY}/{@link #hopZ} and walk it. */
        PATH,
        /** Skip the pathfinder and drive the move control straight at {@link #destination}. */
        CHARGE,
        /** Step into the next column the shared flow field points at. Separate from {@link #CHARGE} only so
         * the profiler can tell them apart and so the field can be switched off in one branch. */
        FLOW,
        /** Keep eating the block the mind is chewing. Movement planning is suspended while this runs. */
        CHEW,
        /** Drop the current path, so a glyphid does not keep walking to where its dead target used to be. */
        STOP
    }

    /** Move the head and possibly bite: the every-tick case for a glyphid already walking a good path. */
    public static GlyphidPlan hold(@Nullable Vec3 lookAt, boolean bite) {
        return new GlyphidPlan(Move.NONE, 0, 0, 0, false, null, lookAt, bite, KEEP_TASK, 0, 0.0);
    }
}
