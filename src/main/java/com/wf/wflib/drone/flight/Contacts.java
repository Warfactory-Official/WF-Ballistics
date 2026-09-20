package com.wf.wflib.drone.flight;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** What happens when two airframes end up in the same piece of sky. */
public final class Contacts {

    /** The share of an overlap a drone clears on its own account. */
    public static final double SHARE = 0.5;

    /** Cap on how far one tick of contact may move a drone. */
    public static final double MAX_ESCAPE = 0.5;

    private Contacts() {
    }

    /**
     * @param tieBreakPositive which way to go when the two are exactly concentric and the geometry has no
     *      opinion. The caller passes opposite values to the two drones, comparing their
     *      entity ids does it, so they part instead of both choosing the same way and
     *      travelling as a stack for the rest of the flight.
     * @return the shortest displacement that takes {@code self} clear of {@code other}, or zero if they are
     *      not overlapping in the first place.
     */
    public static Vec3 escape(AABB self, AABB other, boolean tieBreakPositive) {
        double x = Math.min(self.maxX, other.maxX) - Math.max(self.minX, other.minX);
        double y = Math.min(self.maxY, other.maxY) - Math.max(self.minY, other.minY);
        double z = Math.min(self.maxZ, other.maxZ) - Math.max(self.minZ, other.minZ);
        if (x <= 0.0 || y <= 0.0 || z <= 0.0) {
            return Vec3.ZERO;
        }
        if (y <= x && y <= z) {
            return new Vec3(0.0, y * away(self.minY + self.maxY, other.minY + other.maxY, tieBreakPositive), 0.0);
        }
        if (x <= z) {
            return new Vec3(x * away(self.minX + self.maxX, other.minX + other.maxX, tieBreakPositive), 0.0, 0.0);
        }
        return new Vec3(0.0, 0.0, z * away(self.minZ + self.maxZ, other.minZ + other.maxZ, tieBreakPositive));
    }

    /**
     * @return which way along an axis is away from {@code other}, from the two centres: doubled, since only
     *      their difference is wanted and halving both would change nothing.
     */
    private static double away(double selfCentre, double otherCentre, boolean tieBreakPositive) {
        double delta = selfCentre - otherCentre;
        if (Math.abs(delta) < 1.0E-9) {
            return tieBreakPositive ? 1.0 : -1.0;
        }
        return delta > 0.0 ? 1.0 : -1.0;
    }

    /**
     * @return {@code escape} shortened to one tick's worth of movement: this drone's share of it, and never
     *      more than {@link #MAX_ESCAPE}.
     */
    public static Vec3 step(Vec3 escape) {
        double length = escape.length();
        if (length < 1.0E-9) {
            return Vec3.ZERO;
        }
        return escape.scale(Math.min(SHARE, MAX_ESCAPE / length));
    }

}
