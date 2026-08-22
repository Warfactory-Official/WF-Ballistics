package com.wf.wfballistics.entity.glyphid.brain;

import net.minecraft.server.level.ServerLevel;

/**
 * Something {@link GlyphidBrain} can drive. Implemented by the live {@code EntityGlyphid} today and by the
 * off-world sim record next, which is the whole reason the brain was taken out of the movement goals: a
 * warband record cannot run a {@code Goal}, but it can fill in a {@link GlyphidSnapshot} and act on a
 * {@link GlyphidPlan}.
 *
 * <p>Note the identity is an {@code int}, not the {@code UUID} the drone side uses. Drones number in the
 * tens and each one is a thing a player built and named; glyphids number in the thousands and are
 * interchangeable, and a warband record does not track who is in it. There is no per-glyphid identity to
 * preserve across a materialisation, so paying for one would be paying for nothing.
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

    /**
     * Capture what the brain is allowed to see, sampling the world <em>now</em>.
     */
    GlyphidSnapshot snapshot(ServerLevel level);

    /**
     * Carry out a finished plan. The only step that touches the world.
     */
    void apply(ServerLevel level, GlyphidPlan plan);
}
