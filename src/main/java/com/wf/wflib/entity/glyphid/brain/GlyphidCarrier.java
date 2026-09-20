package com.wf.wflib.entity.glyphid.brain;

import net.minecraft.server.level.ServerLevel;

/** Something {@link GlyphidBrain} can drive: the live {@code EntityGlyphid} or the {@code SimGlyphid} record. */
public interface GlyphidCarrier {

    int carrierId();

    /**
     * @return false once the body is gone and should be skipped.
     */
    boolean carrierAlive();

    /** This body's AI memory. */
    GlyphidMind mind();

    /** Capture what the brain is allowed to see, sampling the world <em>now</em>. */
    GlyphidSnapshot snapshot(ServerLevel level);

    /** Carry out a finished plan. The only step that touches the world. */
    void apply(ServerLevel level, GlyphidPlan plan);

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
