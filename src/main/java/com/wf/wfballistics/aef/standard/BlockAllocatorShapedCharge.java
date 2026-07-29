package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Directional <b>shaped-charge</b> allocator — the Munroe / HEAT effect. Where {@link BlockAllocatorStandard}
 * sprays rays over a whole sphere, this one confines them to a forward cone about a jet {@code axis} and
 * concentrates the power on-axis, so the blast punches a deep, narrow channel (the "jet") with only a shallow
 * crater at the cone's mouth. It is the terrain half of a shaped charge; pair it with {@link EntityProcessorCone}
 * for the matching entity behaviour (see {@link ExplosionAEF#makeShapedCharge(Vec3, float, float)}).
 *
 * <p>The ray-march itself is identical to the standard allocator (0.3-block steps, power drained by each block's
 * explosion resistance — {@link #blockResistance}/{@link #canDestroy} are inherited unchanged), so it honours
 * exploder overrides and WarForge claim filtering exactly like a normal blast. Only the ray <em>directions</em>
 * and their starting <em>power</em> differ:
 *
 * <ul>
 *   <li><b>The jet</b> — {@code jetRays} rays clustered right on the axis, each carrying {@code size * jetPower}.
 *       {@code jetPower} &gt; 1 is what lets the tip drill through high-resistance blocks a same-{@code size}
 *       sphere could never break, giving the signature deep hole.</li>
 *   <li><b>The cone spray</b> — concentric rings out to {@code halfAngle}, their power tapering linearly from
 *       {@code jetPower} on-axis down to {@code edgePower} at the rim, so the charge widens the mouth without
 *       wasting energy sideways.</li>
 * </ul>
 *
 * <p>Everything beside or behind the charge is untouched — pass the round's travel/impact direction as
 * {@code direction} (e.g. straight down for a top-attack warhead). A zero/near-zero direction defaults to
 * straight down.
 */
public class BlockAllocatorShapedCharge extends BlockAllocatorStandard {

    /** Default cone half-angle in degrees — a fairly tight jet. */
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
     * @param direction   the jet axis (round's travel/impact direction); {@code (0,-1,0)} if zero-length
     * @param halfAngleDeg cone half-angle in degrees, clamped to [1, 89]
     * @param jetPower    on-axis power multiplier applied to {@code size} — the penetration knob
     * @param edgePower   power multiplier at the cone rim (usually &lt; 1, a shallow lip)
     * @param rings       number of concentric cone rings sampled from axis out to the rim
     * @param radial      rays on the outermost ring (inner rings scale down proportionally)
     * @param jetRays     rays bundled on-axis to carve the penetrating channel a few blocks wide
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

        // Orthonormal basis (u, v) spanning the plane perpendicular to the jet axis, so a (theta, phi) pair
        // maps to a world-space ray direction (see coneDir).
        Vec3 up = Math.abs(axis.y) < 0.999 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = up.cross(axis).normalize();
        Vec3 v = axis.cross(u); // unit already: axis & u are orthonormal

        // 1) The jet — a tight golden-angle spiral of near-axis rays at full penetrating power. This bundle
        //    (rather than a single ray) makes the channel a few blocks wide instead of a 1-pixel line.
        for (int n = 0; n < jetRays; n++) {
            double theta = halfAngleRad * 0.12 * (n / (double) jetRays);
            double phi = n * 2.399963229728653; // golden angle: even spread with no RNG
            Vec3 dir = coneDir(u, v, theta, phi);
            march(explosion, level, x, y, z, dir, size * jetPower * powerRoll(level), affected, cursor);
        }

        // 2) The cone spray — rings from just off-axis out to the rim, power tapering jet -> edge. Outer rings
        //    get more rays (their circumference is larger) so the crater's edge stays evenly sampled.
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
     * resistance and collecting every destructible block it passes. Mirrors the standard allocator's inner loop.
     */
    private void march(ExplosionAEF explosion, Level level, double x, double y, double z,
                       Vec3 dir, float power, Set<BlockPos> out, BlockPos.MutableBlockPos cursor) {
        double cx = x, cy = y, cz = z;
        for (float step = 0.3F; power > 0.0F; power -= step * 0.75F) {
            cursor.set(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir()) {
                power -= (blockResistance(explosion, level, cursor, state, power) + 0.3F) * step;
                if (power > 0.0F && canDestroy(explosion, level, cursor, state, power)) {
                    out.add(cursor.immutable());
                }
            }
            cx += dir.x * step;
            cy += dir.y * step;
            cz += dir.z * step;
        }
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
