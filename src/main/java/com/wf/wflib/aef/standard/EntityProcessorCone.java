package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Entity stage for a shaped charge: the counterpart to {@link BlockAllocatorShapedCharge}. */
public class EntityProcessorCone extends EntityProcessorCross {

    protected final Vec3 axis;
    protected final double cosHalfAngle;
    protected final double sinHalfAngle;

    public EntityProcessorCone(Vec3 direction, float halfAngleDeg) {
        this(direction, halfAngleDeg, 0);
    }

    /**
     * @param direction jet axis (round's travel direction); {@code (0,-1,0)} if zero-length
     * @param halfAngleDeg cone half-angle, degrees, clamped to [1, 89]
     * @param nodeDist LOS sample spacing, forwarded to {@link EntityProcessorCross}
     */
    public EntityProcessorCone(Vec3 direction, float halfAngleDeg, double nodeDist) {
        super(nodeDist);
        this.axis = direction == null || direction.lengthSqr() < 1.0e-8 ? new Vec3(0, -1, 0) : direction.normalize();
        double halfAngle = Math.toRadians(Mth.clamp(halfAngleDeg, 1.0F, 89.0F));
        this.cosHalfAngle = Math.cos(halfAngle);
        this.sinHalfAngle = Math.sin(halfAngle);
    }

    /**
     * Cone = union over t >= 0 of balls B(o + t*axis, t*sin(half)); box meets cone <=> min_t dist(box, o + t*axis) -
     * t*sin(half) <= 0. Convex in t => ternary search over [0, farthest corner]. Aim = box point nearest the minimising
     * ball's centre (inside both).
     */
    @Nullable
    @Override
    protected Vec3 shapeAim(ExplosionAEF explosion, Entity entity, double x, double y, double z) {
        AABB box = entity.getBoundingBox();
        double fx = Math.max(Math.abs(box.minX - x), Math.abs(box.maxX - x));
        double fy = Math.max(Math.abs(box.minY - y), Math.abs(box.maxY - y));
        double fz = Math.max(Math.abs(box.minZ - z), Math.abs(box.maxZ - z));
        double lo = 0.0;
        double hi = Math.sqrt(fx * fx + fy * fy + fz * fz);
        for (int i = 0; i < 48; i++) {
            double a = lo + (hi - lo) / 3.0;
            double b = hi - (hi - lo) / 3.0;
            if (gap(box, x, y, z, a) <= gap(box, x, y, z, b)) {
                hi = b;
            } else {
                lo = a;
            }
        }
        double t = (lo + hi) * 0.5;
        if (gap(box, x, y, z, t) > 1.0e-6) {
            return null;
        }
        return new Vec3(Mth.clamp(x + this.axis.x * t, box.minX, box.maxX),
                Mth.clamp(y + this.axis.y * t, box.minY, box.maxY),
                Mth.clamp(z + this.axis.z * t, box.minZ, box.maxZ));
    }

    private double gap(AABB box, double x, double y, double z, double t) {
        double px = x + this.axis.x * t;
        double py = y + this.axis.y * t;
        double pz = z + this.axis.z * t;
        double dx = Math.max(0.0, Math.max(box.minX - px, px - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - py, py - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - pz, pz - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - t * this.sinHalfAngle;
    }

    @Override
    protected boolean isWithinBlastShape(ExplosionAEF explosion, double px, double py, double pz,
                                         double x, double y, double z) {
        Vec3 toTarget = new Vec3(px - x, py - y, pz - z);
        double lenSqr = toTarget.lengthSqr();
        if (lenSqr < 1.0e-6) return true; // zero-length: direction undefined, treat as inside
        return toTarget.dot(axis) / Math.sqrt(lenSqr) >= cosHalfAngle;
    }
}
