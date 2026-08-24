package com.wf.wfballistics.entity.glyphid.brain;

/**
 * What one glyphid remembers between decisions: when it last repathed, how long it has been getting nowhere,
 * and when it may bite again. Lives here rather than on the movement goals so a warband record, which has no
 * goals, can own one too.
 *
 * <p>Mutable and owned by one carrier, since an immutable record rebuilt per tick would allocate once per
 * glyphid per tick. {@link GlyphidBrain} still only reads it and hands new values back in a
 * {@link GlyphidPlan}, so the decision stays a function of its inputs.
 */
public final class GlyphidMind {

    /** Ticks since the last repath attempt. */
    public int sinceRepath;
    /**
     * Ticks to wait before the next attempt. Doubles on failure to make progress, up to
     * {@link GlyphidBrain#REPATH_MAX}.
     */
    public int retryAfter = GlyphidBrain.REPATH_MIN;
    /** Ticks without covering ground. At {@link GlyphidBrain#STUCK_TICKS} it stops walking and starts eating. */
    public int stuckFor;
    public double lastX;
    public double lastZ;
    /** Ticks until this glyphid may bite again. */
    public int untilAttack;
    /** Where the target was when the hop was aimed. Re-aiming on less drift is what made vanilla repath. */
    public double aimedX;
    public double aimedZ;
    /** The errand the last decision was for. Stands in for the goals' {@code start()}/{@code stop()}. */
    public int errand = GlyphidBrain.ERRAND_NONE;

    /**
     * Whether this glyphid has given up walking round an obstruction and is eating it. A state and not a
     * one-shot bite: repathing stops while it is set, so a bug part-way through a wall finishes the wall.
     */
    public boolean chewing;
    public int chewX;
    public int chewY;
    public int chewZ;
    /** Ticks of chewing banked against this block. */
    public int chewProgress;
    /** Crack stage last sent to clients, or -1. Kept so the overlay packet only goes out on a real change. */
    public int chewStage = -1;
    /**
     * The block this breach started at. Widening is measured from here and not from the last block chewed,
     * or a glyphid tunnels sideways along a wall forever, always one block from finishing.
     */
    public int breachX;
    public int breachY;
    public int breachZ;

    /** Give up on the current block. {@link #chewStage} is the body's to clear, on its next tick. */
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
     * Seed a fresh mind. The repath counter is offset by entity id, or a warband that materialised in one
     * tick would repath in lockstep for the rest of its life.
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

    /** Force the next tick to repath, for a destination that moved out from under the current hop. */
    public void repathNow() {
        sinceRepath = retryAfter;
    }

    /**
     * Take another body's memory wholesale, for the tier boundary. The patience a glyphid has earned is what
     * tells it to start eating; reset on every crossing, a bug at a wall never accumulates enough to chew.
     */
    public void copyFrom(GlyphidMind other) {
        sinceRepath = other.sinceRepath;
        retryAfter = other.retryAfter;
        stuckFor = other.stuckFor;
        lastX = other.lastX;
        lastZ = other.lastZ;
        untilAttack = other.untilAttack;
        aimedX = other.aimedX;
        aimedZ = other.aimedZ;
        errand = other.errand;
        chewing = other.chewing;
        chewX = other.chewX;
        chewY = other.chewY;
        chewZ = other.chewZ;
        chewProgress = other.chewProgress;
        breachX = other.breachX;
        breachY = other.breachY;
        breachZ = other.breachZ;
        // Not chewStage: cracks are addressed by the entity id that put them there, and a record has none.
    }
}
