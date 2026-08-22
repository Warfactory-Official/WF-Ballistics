package com.wf.wfballistics.entity.glyphid.brain;

/**
 * What one glyphid remembers between decisions: when it last repathed, how long it has been getting nowhere,
 * and when it may bite again.
 *
 * <p>This used to live in fields on the movement {@link net.minecraft.world.entity.ai.goal.Goal}s, which is
 * why there was no way to run glyphid behaviour without an {@code Entity} to hang a goal off. A warband record
 * has no goals and never will, so the memory had to come out of them and into something a record can own too.
 *
 * <p>Mutable and owned by one carrier, deliberately. The obvious alternative — an immutable record rebuilt each
 * tick — allocates once per glyphid per tick, and at three hundred glyphids the point of this subsystem is not
 * to add a per-entity per-tick allocation to save a field write. {@link GlyphidBrain} still never mutates it:
 * it reads, decides, and hands the new values back in a {@link GlyphidPlan} for the applier to write, so the
 * decision stays a function of its inputs and can be moved to a worker later without becoming shared state.
 */
public final class GlyphidMind {

    /**
     * Ticks since the last repath attempt.
     */
    public int sinceRepath;
    /**
     * Ticks to wait before the next attempt. Doubles on failure to make progress, up to
     * {@link GlyphidBrain#REPATH_MAX}.
     */
    public int retryAfter = GlyphidBrain.REPATH_MIN;
    /**
     * Ticks accumulated without covering ground. At {@link GlyphidBrain#STUCK_TICKS} the glyphid stops trying
     * to walk round the obstruction and starts eating it.
     */
    public int stuckFor;
    public double lastX;
    public double lastZ;
    /**
     * Ticks until this glyphid may bite again.
     */
    public int untilAttack;
    /**
     * Where the target was when the current hop was aimed. Re-aiming on smaller drift than a whole hop is what
     * made vanilla's melee goal repath every few ticks.
     */
    public double aimedX;
    public double aimedZ;
    /**
     * Which errand the last decision was made for, so a change of errand can restart the walk. Stands in for
     * the {@code start()}/{@code stop()} boundary the movement goals used to get from the goal selector.
     */
    public int errand = GlyphidBrain.ERRAND_NONE;

    /**
     * Whether this glyphid has given up on walking round the obstruction and is eating it.
     *
     * <p>A state rather than a one-shot bite, because chewing now takes time proportional to the material.
     * While it is set the glyphid stops repathing entirely: a bug that is a third of the way through a wall
     * should finish the wall, not throw the work away every time a search slot comes round.
     */
    public boolean chewing;
    public int chewX;
    public int chewY;
    public int chewZ;
    /**
     * Ticks of chewing banked against this block.
     */
    public int chewProgress;
    /**
     * Crack stage last sent to clients, or -1 when nothing is outstanding. Kept so the overlay packet only
     * goes out when the picture actually changes — at three hundred glyphids, sending it per tick would be
     * three hundred packets a tick to every player in range.
     */
    public int chewStage = -1;
    /**
     * The block this breach started at, which is the one the glyphid actually walked into.
     *
     * <p>Widening is measured from here rather than from whichever block was chewed last. Stepping outward
     * from the last one would let a glyphid tunnel sideways along a wall forever, always one block from
     * finishing; anchored, the doorway can only ever be the handful of positions around where it first hit.
     */
    public int breachX;
    public int breachY;
    public int breachZ;

    /**
     * Give up on the current block, leaving {@link #chewStage} alone: the body owns the overlay's lifetime
     * and clears it on the next tick it sees no chewing.
     */
    public void stopChewing() {
        chewing = false;
        chewProgress = 0;
    }

    public void beginChewing(int x, int y, int z) {
        chewing = true;
        chewX = x;
        chewY = y;
        chewZ = z;
        chewProgress = 0;
    }

    public boolean chewingAt(int x, int y, int z) {
        return chewing && chewX == x && chewY == y && chewZ == z;
    }

    /**
     * Seed a fresh mind. The repath counter is offset by entity id rather than starting at zero: a warband
     * materialises inside one tick and would otherwise repath in lockstep for the rest of its life.
     */
    public void reset(int id, double x, double z) {
        sinceRepath = GlyphidBrain.REPATH_MIN - Math.floorMod(id, GlyphidBrain.REPATH_MIN);
        retryAfter = GlyphidBrain.REPATH_MIN;
        stuckFor = 0;
        lastX = x;
        lastZ = z;
        untilAttack = 0;
        aimedX = x;
        aimedZ = z;
        stopChewing();
    }

    /**
     * Force the next tick to repath, for a destination that moved out from under the current hop.
     */
    public void repathNow() {
        sinceRepath = retryAfter;
    }
}
