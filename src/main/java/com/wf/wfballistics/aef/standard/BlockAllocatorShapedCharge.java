package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** Directional <b>shaped-charge</b> allocator: the Munroe / HEAT effect. */
public class BlockAllocatorShapedCharge extends BlockAllocatorStandard {

    /** Default cone half-angle in degrees: a fairly tight jet. */
    public static final float DEFAULT_HALF_ANGLE_DEG = 22.0F;
    /** Default on-axis power multiplier (penetration depth vs. a same-{@code size} sphere). */
    public static final float DEFAULT_JET_POWER = 4.0F;
    /** Default power fraction (of {@code size}) at the very rim of the cone. */
    public static final float DEFAULT_EDGE_POWER = 0.6F;

    protected final Vec3 axis;
    protected final float halfAngleRad;
    protected final float jetPower;
    protected final float edgePower;
    protected final int rings;
    protected final int radial;
    protected final int jetRays;

    public BlockAllocatorShapedCharge(Vec3 direction) {
        this(direction, DEFAULT_HALF_ANGLE_DEG, DEFAULT_JET_POWER);
    }

    public BlockAllocatorShapedCharge(Vec3 direction, float halfAngleDeg, float jetPower) {
        this(direction, halfAngleDeg, jetPower, DEFAULT_EDGE_POWER, 6, 12, 9);
    }

    /**
     * @param direction the jet axis (round's travel/impact direction); {@code (0,-1,0)} if zero-length
     * @param halfAngleDeg cone half-angle in degrees, clamped to [1, 89]
     * @param jetPower on-axis power multiplier applied to {@code size}: the penetration knob
     * @param edgePower power multiplier at the cone rim (usually &lt; 1, a shallow lip)
     * @param rings number of concentric cone rings sampled from axis out to the rim
     * @param radial rays on the outermost ring (inner rings scale down proportionally)
     * @param jetRays rays bundled on-axis to carve the penetrating channel a few blocks wide
     */
    public BlockAllocatorShapedCharge(Vec3 direction, float halfAngleDeg, float jetPower, float edgePower,
                                      int rings, int radial, int jetRays) {
        this.axis = normalizeOrDown(direction);
        this.halfAngleRad = (float) Math.toRadians(Mth.clamp(halfAngleDeg, 1.0F, 89.0F));
        this.jetPower = jetPower;
        this.edgePower = edgePower;
        this.rings = Math.max(1, rings);
        this.radial = Math.max(3, radial);
        this.jetRays = Math.max(1, jetRays);
    }

    @Override
    public Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        Set<BlockPos> affected = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        com.wf.wfballistics.debug.ExplosionTrace.cone(axis, (float) Math.toDegrees(halfAngleRad));

        Vec3 up = Math.abs(axis.y) < 0.999 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = up.cross(axis).normalize();
        Vec3 v = axis.cross(u); // unit already: axis & u are orthonormal

        for (int n = 0; n < jetRays; n++) {
            double theta = halfAngleRad * 0.12 * (n / (double) jetRays);
            double phi = n * 2.399963229728653; // golden angle: even spread with no RNG
            Vec3 dir = coneDir(u, v, theta, phi);
            march(explosion, level, x, y, z, dir, size * jetPower * powerRoll(level), affected, cursor);
        }

        for (int r = 1; r <= rings; r++) {
            double t = r / (double) rings;                 // 0 (axis) .. 1 (rim)
            double theta = halfAngleRad * t;
            float ringPower = Mth.lerp((float) t, jetPower, edgePower);
            int count = Math.max(3, (int) Math.ceil(radial * t));
            for (int j = 0; j < count; j++) {
                double phi = (j / (double) count) * Math.PI * 2.0;
                Vec3 dir = coneDir(u, v, theta, phi);
                march(explosion, level, x, y, z, dir, size * ringPower * powerRoll(level), affected, cursor);
            }
        }
        return affected;
    }

    /**
     * March one ray of pre-computed starting {@code power} outward from the centre, draining it by each block's
     * resistance and collecting every destructible block it passes.
     */
    private void march(ExplosionAEF explosion, Level level, double x, double y, double z,
                       Vec3 dir, float power, Set<BlockPos> out, BlockPos.MutableBlockPos cursor) {
        double cx = x, cy = y, cz = z;
        boolean broke = false;
        for (float step = 0.3F; power > 0.0F; power -= step * 0.75F) {
            cursor.set(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir()) {
                power -= (blockResistance(explosion, level, cursor, state, power) + 0.3F) * step;
                if (power > 0.0F && canDestroy(explosion, level, cursor, state, power)) {
                    out.add(cursor.immutable());
                    broke = true;
                }
            }
            cx += dir.x * step;
            cy += dir.y * step;
            cz += dir.z * step;
        }
        com.wf.wfballistics.debug.ExplosionTrace.ray(x, y, z, cx, cy, cz, broke);
    }

    /** Ray direction at polar angle {@code theta} off the axis and azimuth {@code phi} around it. */
    private Vec3 coneDir(Vec3 u, Vec3 v, double theta, double phi) {
        double st = Math.sin(theta), ct = Math.cos(theta);
        return axis.scale(ct)
                .add(u.scale(st * Math.cos(phi)))
                .add(v.scale(st * Math.sin(phi)));
    }

    /** Vanilla-style per-ray power jitter so the crater edge isn't unnaturally smooth. */
    private float powerRoll(Level level) {
        return 0.7F + level.random.nextFloat() * 0.6F;
    }

    private static Vec3 normalizeOrDown(Vec3 dir) {
        return dir == null || dir.lengthSqr() < 1.0e-8 ? new Vec3(0, -1, 0) : dir.normalize();
    }
}
