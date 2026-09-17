package com.wf.wfballistics.drone.squad;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Where each member of a squad should sit relative to its leader. */
public interface Formation {

    /**
     * How far apart neighbouring slots sit by default, in blocks: the squad's slack, and the one number that
     * decides how tightly it flies.
     */
    double DEFAULT_SPACING = 12.0;

    /** The range a configured spacing is held to. */
    double MIN_SPACING = 4.0;
    double MAX_SPACING = 96.0;

    /**
     * @param index 0-based slot; 0 is the leader
     * @param forward the leader's horizontal heading (unit length)
     * @param spacing distance between neighbouring slots, in blocks
     * @return the world position this slot should hold
     */
    Vec3 slot(int index, Vec3 leaderPos, Vec3 forward, double spacing);

    String id();

    /**
     * @return the horizontal right-hand vector for a heading.
     */
    static Vec3 right(Vec3 forward) {
        return new Vec3(forward.z, 0.0, -forward.x);
    }

    /**
     * @return the direction a formation is built along, for a yaw in radians.
     */
    static Vec3 forward(float yaw) {
        return new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /**
     * @return {@code spacing} brought inside {@link #MIN_SPACING}..{@link #MAX_SPACING}, falling back to
     *      {@link #DEFAULT_SPACING} for a value that is not a number at all. Applied where a spacing enters the
     *      system rather than where it is used, so nothing downstream has to wonder whether it was checked.
     */
    static double clampSpacing(double spacing) {
        return Double.isFinite(spacing) ? Mth.clamp(spacing, MIN_SPACING, MAX_SPACING) : DEFAULT_SPACING;
    }
}
