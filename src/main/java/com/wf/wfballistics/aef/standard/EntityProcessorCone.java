package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Entity stage for a shaped charge: the counterpart to {@link BlockAllocatorShapedCharge}. */
public class EntityProcessorCone extends EntityProcessorCross {

    protected final Vec3 axis;
    protected final double cosHalfAngle;

    public EntityProcessorCone(Vec3 direction, float halfAngleDeg) {
        this(direction, halfAngleDeg, 0);
    }

    /**
     * @param direction the jet axis (round's travel/impact direction); {@code (0,-1,0)} if zero-length
     * @param halfAngleDeg cone half-angle in degrees, clamped to [1, 89]
     * @param nodeDist line-of-sight sample spacing, forwarded to {@link EntityProcessorCross}
     */
    public EntityProcessorCone(Vec3 direction, float halfAngleDeg, double nodeDist) {
        super(nodeDist);
        this.axis = direction == null || direction.lengthSqr() < 1.0e-8 ? new Vec3(0, -1, 0) : direction.normalize();
        this.cosHalfAngle = Math.cos(Math.toRadians(Mth.clamp(halfAngleDeg, 1.0F, 89.0F)));
    }

    @Override
    protected boolean isWithinBlastShape(ExplosionAEF explosion, double px, double py, double pz,
                                         double x, double y, double z) {
        Vec3 toTarget = new Vec3(px - x, py - y, pz - z);
        double lenSqr = toTarget.lengthSqr();
        if (lenSqr < 1.0e-6) return true; // point-blank on the charge: always caught
        return toTarget.dot(axis) / Math.sqrt(lenSqr) >= cosHalfAngle;
    }
}
