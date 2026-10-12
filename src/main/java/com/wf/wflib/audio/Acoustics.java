package com.wf.wflib.audio;

/** Sound timing closed forms. Metres = blocks, seconds. */
public final class Acoustics {
    /** Speed of sound, m/s; delay, Doppler and crack share it. */
    public static final double C = 343.0;
    /** {@link #C}, blocks/tick. */
    public static final float C_PER_TICK = (float) (C / 20.0);

    private Acoustics() {
    }

    public static double delay(double distance) {
        return distance / C;
    }

    /**
     * Segment {@code a -> b} vs point {@code p}: unclamped parameter of the closest point ({@code out[0]}) and the
     * distance at the clamped parameter ({@code out[1]}).
     */
    public static void closest(double ax, double ay, double az, double bx, double by, double bz, double px, double py,
                               double pz, double[] out) {
        double sx = bx - ax, sy = by - ay, sz = bz - az;
        double len2 = sx * sx + sy * sy + sz * sz;
        double f = len2 < 1e-12 ? 0 : ((px - ax) * sx + (py - ay) * sy + (pz - az) * sz) / len2;
        double k = Math.clamp(f, 0, 1);
        double dx = ax + sx * k - px, dy = ay + sy * k - py, dz = az + sz * k - pz;
        out[0] = f;
        out[1] = Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Supersonic pass at miss distance {@code d}, speed {@code v} m/s: first shock arrival after closest approach,
     * {@code d cos(theta) / C}, {@code sin(theta) = C / v}. Emitted {@link #emissionBack} before the closest point.
     */
    public static double crackAfterClosest(double d, double v) {
        double s = C / v;
        return d * Math.sqrt(1 - s * s) / C;
    }

    /** Distance along the track, before the closest point, of the shock that reaches the listener first: {@code d tan(theta)}. */
    public static double emissionBack(double d, double v) {
        double s = C / v;
        return d * s / Math.sqrt(1 - s * s);
    }

    /** Muzzle report vs crack at a listener {@code downrange} along the track and {@code d} off it; > 0 => crack first. */
    public static double crackLead(double downrange, double d, double v) {
        double report = Math.hypot(downrange, d) / C;
        return report - (downrange / v + crackAfterClosest(d, v));
    }

    /** Air absorption at {@code d} of reach {@code range}: {@code t = clamp((d/range - 0.2) / 0.8)}. */
    public static float absorption(double d, double range) {
        return (float) Math.clamp((d / range - 0.2) / 0.8, 0, 1);
    }
}
