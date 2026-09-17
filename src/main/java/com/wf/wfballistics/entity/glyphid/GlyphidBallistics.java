package com.wf.wfballistics.entity.glyphid;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Launch solutions for the castes that throw something: acid at an arc, a brawler at a player, a block torn out of
 * the floor.
 */
public final class GlyphidBallistics {

    /** Ticks over which a caste samples its target's movement before leading it. */
    public static final int TRACK_INTERVAL = 20;

    private GlyphidBallistics() {
    }

    /**
     * Solve for a launch velocity that lands on {@code to}.
     *
     * @param loft high arc (over a wall, onto a roof) rather than the flat direct-fire solution
     * @return the launch velocity, or null when the target is simply out of range at this muzzle speed,
     *      which is the discriminant going negative, and is the only failure mode
     */
    public static @Nullable Vec3 solve(Vec3 from, Vec3 to, double muzzleSpeed, double gravity, boolean loft) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return null;
        }

        double v2 = muzzleSpeed * muzzleSpeed;
        double discriminant = v2 * v2 - gravity * (gravity * horizontal * horizontal + 2.0 * dy * v2);
        if (discriminant < 0.0) {
            return null;
        }

        double pitch = Math.atan((v2 + Math.sqrt(discriminant) * (loft ? 1.0 : -1.0)) / (gravity * horizontal));
        double forward = muzzleSpeed * Math.cos(pitch);
        return new Vec3(dx / horizontal * forward, muzzleSpeed * Math.sin(pitch), dz / horizontal * forward);
    }

    /** Where a target will be in {@code leadTicks}, given where it was {@link #TRACK_INTERVAL} ticks ago. */
    public static Vec3 lead(LivingEntity target, double lastX, double lastY, double lastZ,
                            boolean sameTarget, int leadTicks) {
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() / 2.0, target.getZ());
        if (!sameTarget) {
            return aim;
        }

        double vx = (target.getX() - lastX) / TRACK_INTERVAL;
        double vy = (target.getY() - lastY) / TRACK_INTERVAL;
        double vz = (target.getZ() - lastZ) / TRACK_INTERVAL;
        if (vx * vx + vy * vy + vz * vz > 1.0) {
            return aim;
        }
        return aim.add(vx * leadTicks, vy * leadTicks, vz * leadTicks);
    }
}
