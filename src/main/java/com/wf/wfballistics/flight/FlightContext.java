package com.wf.wfballistics.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Per-tick guidance inputs a {@link com.wf.wfballistics.MissileEntity} computes once and hands to its active {@link
 * FlightStage}.
 *
 * @param position the missile's current position
 * @param target the aim point
 * @param horizontalDist horizontal distance to the target
 * @param nx normalised X of the horizontal heading toward the target (0 when on top of it)
 * @param nz normalised Z of the horizontal heading toward the target
 * @param safeAltitude altitude the missile should hold (terrain-follow scan result, or the fixed cruise height)
 * @param safeCeiling the highest altitude it may hold, or {@link Double#POSITIVE_INFINITY} when nothing caps
 *      it. An aircraft has no ceiling; a torpedo's is the underside of the water surface, and
 *      crossing it is a broach (see {@link com.wf.wfballistics.MissileEntity.Medium}).
 */
public record FlightContext(Vec3 position, Vec3 target, double horizontalDist,
                            double nx, double nz, double safeAltitude, double safeCeiling) {

    /**
     * A context for a vehicle with no upper bound on its altitude, which is every missile that flies in air.
     */
    public FlightContext(Vec3 position, Vec3 target, double horizontalDist,
                         double nx, double nz, double safeAltitude) {
        this(position, target, horizontalDist, nx, nz, safeAltitude, Double.POSITIVE_INFINITY);
    }

    /**
     * @return {@code y} clamped into the corridor this context describes. With no ceiling this is a floor-only
     *      clamp; where the corridor has closed (shallow water: the seabed clearance is above the surface margin)
     *      it collapses to the middle of it, which is the deepest a torpedo can run without either grounding or
     *      broaching: both bounds are violated, so it sits equally far from each instead of committing to one.
     */
    public double clampToCorridor(double y) {
        if (Double.isInfinite(this.safeCeiling)) {
            return y;
        }
        if (this.safeAltitude > this.safeCeiling) {
            return (this.safeAltitude + this.safeCeiling) * 0.5;
        }
        return Math.min(Math.max(y, this.safeAltitude), this.safeCeiling);
    }
}
