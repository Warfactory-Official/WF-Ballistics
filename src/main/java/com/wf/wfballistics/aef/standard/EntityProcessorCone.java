package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Entity stage for a shaped charge — the counterpart to {@link BlockAllocatorShapedCharge}. It reuses all of
 * {@link EntityProcessorCross}'s falloff, line-of-sight and knockback logic but gates it to a forward cone:
 * only entities whose bearing from the charge lies within {@code halfAngle} of the jet {@code axis} are hit.
 * Anything beside or behind the charge is spared, so the jet is lethal in a line while the blast ignores the
 * mob standing off to the side.
 *
 * <p>Wired up by {@link ExplosionAEF#makeShapedCharge(Vec3, float, float)}; use the same {@code direction} and
 * {@code halfAngle} you gave the block allocator so the terrain crater and the casualty cone agree.
 */
public class EntityProcessorCone extends EntityProcessorCross {

    protected final Vec3 axis;
    protected final double cosHalfAngle;

    public EntityProcessorCone(Vec3 direction, float halfAngleDeg) {
        this(direction, halfAngleDeg, 0);
    }

    /**
     * @param direction    the jet axis (round's travel/impact direction); {@code (0,-1,0)} if zero-length
     * @param halfAngleDeg cone half-angle in degrees, clamped to [1, 89]
     * @param nodeDist     line-of-sight sample spacing, forwarded to {@link EntityProcessorCross}
     */
    public EntityProcessorCone(Vec3 direction, float halfAngleDeg, double nodeDist) {
        super(nodeDist);
        this.axis = direction == null || direction.lengthSqr() < 1.0e-8 ? new Vec3(0, -1, 0) : direction.normalize();
        this.cosHalfAngle = Math.cos(Math.toRadians(Mth.clamp(halfAngleDeg, 1.0F, 89.0F)));
    }

    @Override
    protected boolean isWithinBlastShape(ExplosionAEF explosion, Entity entity, double x, double y, double z) {
        // Bearing from the charge to the entity's centre is inside the cone iff its angle to the axis is within
        // the half-angle — i.e. the normalised dot with the axis is at least cos(halfAngle).
        Vec3 toEntity = entity.position().add(0, entity.getBbHeight() * 0.5, 0).subtract(x, y, z);
        double lenSqr = toEntity.lengthSqr();
        if (lenSqr < 1.0e-6) return true; // point-blank on the charge: always caught
        return toEntity.dot(axis) / Math.sqrt(lenSqr) >= cosHalfAngle;
    }
}
