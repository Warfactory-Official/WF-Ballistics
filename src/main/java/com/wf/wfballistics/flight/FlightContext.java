package com.wf.wfballistics.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Per-tick guidance inputs a {@link com.wf.wfballistics.MissileEntity} computes once and hands to its active
 * {@link FlightStage}. Immutable snapshot of the derived values every stage needs; the missile's own mutable
 * state (position, velocity, cruise-altitude memory) is read/written through the entity.
 *
 * @param position       the missile's current position
 * @param target         the aim point
 * @param horizontalDist horizontal distance to the target
 * @param nx             normalised X of the horizontal heading toward the target (0 when on top of it)
 * @param nz             normalised Z of the horizontal heading toward the target
 * @param safeAltitude   altitude the missile should hold (terrain-follow scan result, or the fixed cruise height)
 */
public record FlightContext(Vec3 position, Vec3 target, double horizontalDist,
                            double nx, double nz, double safeAltitude) {
}
