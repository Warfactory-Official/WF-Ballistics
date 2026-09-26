package com.wf.wflib.missile;

import net.minecraft.world.phys.Vec3;

/** Constant-speed intercept of a straight-line mover. */
public final class Lead {

    private Lead() {
    }

    /**
     * Smallest t > 0 with |d + vt*t| = speed*t, where d = target - shooter; -1 = no timed intercept (target
     * outruns, or opening).
     */
    public static double time(Vec3 d, Vec3 vt, double speed) {
        double a = vt.lengthSqr() - speed * speed;
        double b = 2.0 * d.dot(vt);
        double c = d.lengthSqr();
        double t;
        if (Math.abs(a) < 1.0E-6) {
            t = Math.abs(b) < 1.0E-6 ? -1.0 : -c / b;
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc < 0.0) {
                return -1.0;
            }
            double sq = Math.sqrt(disc);
            double t1 = (-b - sq) / (2.0 * a);
            double t2 = (-b + sq) / (2.0 * a);
            t = t1 > 0.0 && t2 > 0.0 ? Math.min(t1, t2) : t1 > 0.0 ? t1 : t2 > 0.0 ? t2 : -1.0;
        }
        return t > 0.0 && Double.isFinite(t) ? t : -1.0;
    }
}
