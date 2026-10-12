package com.wf.wflib.round.pen;

import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Skip off a surface the round cannot perforate (block face entered from air: {@link #holds}; water surface; armour
 * by its owner). Grazing angle {@code a = asin(-d.n)}, critical {@code c}: {@code P = ramp(a; c(1 -+ RAMP)) x
 * (1 - FRAGMENT a / c)} (rest: as before, pierce / stop = break-up). Out = {@code MU v_t - E v_n}, scattered {@link #SCATTER}, leaving
 * >= {@link #MIN_OUT}. Ricocheted => tumbling: quadratic drag x {@link #TUMBLE_DRAG}, pen x {@link #TUMBLE_PEN}.
 * Seeded: client prediction agrees.
 */
public final class Ricochet {
    /** Per round. */
    public static final int MAX = 2;
    public static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    /** Restart this far off the surface: the next clip starts outside it. */
    public static final double OFF = 1.0e-3;
    /** blocks/tick (100 m/s); slower => stops. */
    public static final double MIN_SPEED = 5.0;
    /** Water surface critical grazing angle, deg. */
    public static final float WATER = 7.0f;
    public static final double TUMBLE_DRAG = 3.0;
    public static final float TUMBLE_PEN = 0.5f;
    static final double RAMP = 0.25;
    static final double FRAGMENT = 0.5;
    /** Solid: departure ~ incidence x E / MU = 0.31. */
    static final double MU = 0.8;
    static final double E = 0.25;
    static final double WATER_MU = 0.7;
    static final double WATER_E = 0.5;
    static final double SCATTER = Math.toRadians(3.0);
    static final double MIN_OUT = Math.toRadians(0.5);
    private static final long SALT = 0x5249434FL;

    private Ricochet() {
    }

    /**
     * @param n        outward unit face normal
     * @param critical grazing angle, deg ({@link PenTable#ricochet}, {@link #WATER})
     * @return outgoing velocity; null => no ricochet
     */
    @Nullable
    public static Vec3 bounce(Vec3 v, Vec3 n, float critical, boolean water, long seed) {
        double speed = v.length();
        if (critical <= 0.0f || speed < MIN_SPEED) {
            return null;
        }
        double sin = -v.dot(n) / speed;
        if (sin <= 0.0) {
            return null;
        }
        double ratio = Math.toDegrees(Math.asin(Math.min(sin, 1.0))) / critical;
        double p = Math.clamp((1.0 + RAMP - ratio) / (2.0 * RAMP), 0.0, 1.0) * (1.0 - FRAGMENT * ratio);
        long h = BlockPen.mix(seed ^ SALT);
        if (BlockPen.unit(h) >= p) {
            return null;
        }
        return off(v, n, water ? WATER_MU : MU, water ? WATER_E : E, BlockPen.mix(h));
    }

    /**
     * Grazing round cannot perforate the struck layer: {@code capacity < resistance x extent / sin a}, {@code extent} =
     * collision shape along the face normal. Entry face decides: a multi-block floor, not the first block's corner
     * sliver.
     */
    public static boolean holds(double capacity, float resistance, VoxelShape shape, Direction face, Vec3 v) {
        double sin = -v.dot(Vec3.atLowerCornerOf(face.getNormal())) / v.length();
        Direction.Axis axis = face.getAxis();
        return sin > 0.0 && capacity * sin < resistance * (shape.max(axis) - shape.min(axis));
    }

    /** Deflection decided by the caller (armour model): solid restitution. */
    public static Vec3 off(Vec3 v, Vec3 n, long seed) {
        return off(v, n, MU, E, seed);
    }

    private static Vec3 off(Vec3 v, Vec3 n, double mu, double e, long seed) {
        Vec3 vn = n.scale(v.dot(n));
        Vec3 out = v.subtract(vn).scale(mu).subtract(vn.scale(e));
        double speed = out.length();
        out = BlockPen.deflect(out.scale(1.0 / speed), SCATTER, seed);
        if (out.dot(n) < Math.sin(MIN_OUT)) {
            out = out.subtract(n.scale(out.dot(n))).normalize().scale(Math.cos(MIN_OUT))
                    .add(n.scale(Math.sin(MIN_OUT)));
        }
        return out.scale(speed);
    }

    /**
     * Water surface met entering {@code [from, end]} from air: fluid at or just before {@code end} (else none: two
     * lookups), then a fluid clip. Null => none.
     */
    @Nullable
    public static BlockHitResult water(BlockGetter level, Vec3 from, Vec3 end) {
        if (level.getFluidState(BlockPos.containing(end)).isEmpty() && level.getFluidState(BlockPos.containing(
                end.subtract(end.subtract(from).normalize().scale(OFF)))).isEmpty()) {
            return null;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && !level.getFluidState(hit.getBlockPos()).isEmpty() ? hit : null;
    }

    /** {@link KineticPreset#decay(double)}, tumbling => quadratic part x {@link #TUMBLE_DRAG}. */
    public static double decay(KineticPreset preset, double speed, boolean tumbling) {
        return preset.decay(tumbling ? speed * TUMBLE_DRAG : speed);
    }
}
