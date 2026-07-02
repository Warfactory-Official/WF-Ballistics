package com.wf.wfballistics.sim;

import net.minecraft.world.phys.Vec3;

public final class RayMath {
    private RayMath() {
    }


    public static double segmentSphereEntry(Vec3 p0, Vec3 p1, Vec3 center, double radius) {
        Vec3 d = p1.subtract(p0);
        double len = d.length();
        Vec3 m = p0.subtract(center);
        double r2 = radius * radius;

        if (len < 1.0E-9) {
            return m.lengthSqr() <= r2 ? 0.0 : Double.NaN;
        }

        Vec3 u = d.scale(1.0 / len);
        double c = m.lengthSqr() - r2;
        if (c <= 0.0) {
            return 0.0;
        }

        double b = m.dot(u);
        double disc = b * b - c;
        if (disc < 0.0) {
            return Double.NaN;
        }

        double tHit = -b - Math.sqrt(disc);
        if (tHit < 0.0 || tHit > len) {
            return Double.NaN;
        }
        return tHit / len;
    }
}
