package com.wf.wfballistics.attitude;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How a flying model orients itself to its heading — its "attitude". Different airframes rotate differently:
 * a rocket points its nose straight along the velocity and doesn't care about roll, whereas a winged drone
 * keeps its belly down and wings level and only banks into turns. Implementations turn a heading into a
 * target orientation.
 *
 * <p>This is the single source of truth for airframe pose, used by BOTH sides: {@link
 * com.wf.wfballistics.MissileVisual} smooths toward it (and adds cosmetic banking) for rendering, and
 * {@code MissileEntity.refreshObb()} uses it to orient the hitbox OBB. That shared use is why this lives in a
 * side-agnostic package rather than {@code client.render}: implementations MUST NOT touch client-only classes.
 *
 * <p>Model convention (shared with the flight/OBB code): the model's nose/long axis is local {@code +Y}.
 * Pick a model's attitude by id via {@link MissileAttitudeRegistry}; assign it per model in
 * {@code MissileModels}. Adding a new way to fly = one new implementation + a {@code register(...)} call.
 */
public interface MissileAttitude {

    /**
     * @param heading the (normalized) direction of travel in world space
     * @return the target orientation mapping model space (nose = +Y) to world space
     */
    Quaternionf orientation(Vector3f heading);
}
