package com.wf.wfballistics.entity.glyphid.brain;

import net.minecraft.server.level.ServerLevel;

/**
 * Something {@link GlyphidBrain} can drive: the live {@code EntityGlyphid} or the {@code SimGlyphid} record.
 * A record cannot run a {@code Goal}, but it can fill in a {@link GlyphidSnapshot} and act on a
 * {@link GlyphidPlan}, which is why the brain lives outside the movement goals.
 *
 * <p>Every method is called on the world thread.
 */
public interface GlyphidCarrier {

    int carrierId();

    /**
     * @return false once the body is gone and should be skipped.
     */
    boolean carrierAlive();

    /**
     * This body's AI memory. Owned by the carrier so it survives between decisions and so the brain never has
     * to hold state of its own.
     */
    GlyphidMind mind();

    /** Capture what the brain is allowed to see, sampling the world <em>now</em>. */
    GlyphidSnapshot snapshot(ServerLevel level);

    /** Carry out a finished plan. The only step that touches the world. */
    void apply(ServerLevel level, GlyphidPlan plan);

    // --- where the body is, for the level passes that work on a swarm rather than on a decision ---
    // Separation is the caller, and it needs both tiers in one grid or half the swarm ends up stacked.

    double carrierX();

    double carrierY();

    double carrierZ();

    /** Body width, which is what a pair of glyphids is spaced against. */
    double carrierWidth();

    /**
     * @return false for a body that must not be shoved: in the air, riding something, or already gone.
     */
    boolean carrierPushable();

    /** Add a horizontal impulse, in blocks per tick. Spent by whatever moves this body next. */
    void carrierPush(double dx, double dz);
}
