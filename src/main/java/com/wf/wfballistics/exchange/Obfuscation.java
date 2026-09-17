package com.wf.wfballistics.exchange;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.random.RandomGenerator;

/** The geometry of not being followed home. */
public final class Obfuscation {

    /** How likely the true home bearing is to be chosen, relative to the bearing directly opposite it. */
    public static final double APPROACH_FLOOR = 0.08;
    /** How far out the staging point sits: the corner the dogleg turns at. */
    public static final double STAGING_MIN = 90.0;
    public static final double STAGING_MAX = 190.0;
    /** Attempts before the rejection sampler gives up and takes what it has. */
    private static final int SAMPLE_ATTEMPTS = 12;

    /** Nearest a rendezvous may be placed to either station. */
    public static final double RENDEZVOUS_STANDOFF = 150.0;
    public static final double RENDEZVOUS_MIN_OFFSET = 80.0;
    public static final double RENDEZVOUS_MAX_OFFSET = 320.0;

    private Obfuscation() {
    }

    /**
     * Choose the bearing a drone should arrive on.
     *
     * @param homeBearing the direction from the drop point back to the sender, radians
     * @return the bearing, measured at the drop point, that the drone should come in from
     */
    public static double approachBearing(RandomGenerator rng, double homeBearing) {
        double offset = 0.0;
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            offset = (rng.nextDouble() * 2.0 - 1.0) * Math.PI;
            // Peaks at 1 opposite home, falls to APPROACH_FLOOR at home.
            double weight = APPROACH_FLOOR + (1.0 - APPROACH_FLOOR) * (1.0 - Math.cos(offset)) * 0.5;
            if (rng.nextDouble() < weight) {
                break;
            }
        }
        return wrap(homeBearing + offset);
    }

    /**
     * @return the point a drone should fly to before turning in, so that its final leg arrives at
     *      {@code target} on {@code bearing}.
     */
    public static Vec3 staging(Vec3 target, double bearing, double distance) {
        return new Vec3(target.x + Math.sin(bearing) * distance, target.y,
                target.z + Math.cos(bearing) * distance);
    }

    /**
     * The dogleg out: fly wide, then turn in on a bearing that is a poor guide back to {@code origin}.
     *
     * @return waypoints to visit before the destination
     */
    public static List<Vec3> approachLegs(RandomGenerator rng, Vec3 origin, Vec3 target) {
        double homeBearing = bearing(target, origin);
        double approach = approachBearing(rng, homeBearing);
        return List.of(staging(target, approach, stagingDistance(rng)));
    }

    /**
     * The dogleg home, and the half of this that is easy to forget.
     *
     * @return waypoints to visit before the exfil point
     */
    public static List<Vec3> egressLegs(RandomGenerator rng, Vec3 dropPoint, Vec3 home) {
        double homeBearing = bearing(dropPoint, home);
        double departure = approachBearing(rng, homeBearing);
        return List.of(staging(dropPoint, departure, stagingDistance(rng)));
    }

    private static double stagingDistance(RandomGenerator rng) {
        return STAGING_MIN + rng.nextDouble() * (STAGING_MAX - STAGING_MIN);
    }

    /** Pick where two stations should meet. */
    public static Vec3 rendezvous(RandomGenerator rng, Vec3 a, Vec3 b) {
        Vec3 midpoint = a.add(b).scale(0.5);
        double separation = horizontal(a, b);
        double spread = Mth.clamp(separation * 0.35, RENDEZVOUS_MIN_OFFSET, RENDEZVOUS_MAX_OFFSET);
        double direction = rng.nextDouble() * 2.0 * Math.PI;
        double reach = spread * (0.5 + rng.nextDouble());

        Vec3 point = staging(midpoint, direction, reach);
        for (int i = 0; i < 16; i++) {
            if (horizontal(point, a) >= RENDEZVOUS_STANDOFF && horizontal(point, b) >= RENDEZVOUS_STANDOFF) {
                break;
            }
            reach += RENDEZVOUS_STANDOFF * 0.5;
            point = staging(midpoint, direction, reach);
        }
        return point;
    }

    /**
     * @return the horizontal bearing from one point to another, radians, in the same convention the drones
     *      use for heading ({@code atan2(dx, dz)}).
     */
    public static double bearing(Vec3 from, Vec3 to) {
        return Math.atan2(to.x - from.x, to.z - from.z);
    }

    public static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * @return the smallest angle between two bearings, radians, always in {@code [0, PI]}.
     */
    public static double angleBetween(double a, double b) {
        return Math.abs(wrap(a - b));
    }

    public static double wrap(double angle) {
        double twoPi = Math.PI * 2.0;
        angle %= twoPi;
        if (angle >= Math.PI) {
            angle -= twoPi;
        } else if (angle < -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }
}
