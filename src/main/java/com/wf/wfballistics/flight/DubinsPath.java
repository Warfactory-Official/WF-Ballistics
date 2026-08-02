package com.wf.wfballistics.flight;


public final class DubinsPath {

    private final double r;
    private final double x0;
    private final double z0;
    private final double th0;
    private final char[] types;
    private final double[] seg;
    private final double length;

    private DubinsPath(double r, double x0, double z0, double th0, char[] types, double[] seg) {
        this.r = r;
        this.x0 = x0;
        this.z0 = z0;
        this.th0 = th0;
        this.types = types;
        this.seg = seg;
        this.length = seg[0] + seg[1] + seg[2];
    }

    public double length() {
        return this.length;
    }

    private static double mod2pi(double a) {
        double twoPi = Math.PI * 2.0;
        double m = a % twoPi;
        return m < 0.0 ? m + twoPi : m;
    }

    public static DubinsPath plan(double x0, double z0, double th0,
                                  double x1, double z1, double th1, double r) {
        if (!(r > 1.0E-6) || !Double.isFinite(r)) {
            return null;
        }
        double dx = x1 - x0;
        double dz = z1 - z0;
        double d = Math.sqrt(dx * dx + dz * dz) / r;
        double theta = mod2pi(Math.atan2(dz, dx));
        double a = mod2pi(th0 - theta);
        double b = mod2pi(th1 - theta);

        double sa = Math.sin(a);
        double ca = Math.cos(a);
        double sb = Math.sin(b);
        double cb = Math.cos(b);
        double cab = Math.cos(a - b);

        double best = Double.POSITIVE_INFINITY;
        char[] bestTypes = null;
        double[] bestTpq = null;

        double p2 = 2 + d * d - 2 * cab + 2 * d * (sa - sb);
        if (p2 >= 0) {
            double tmp = Math.atan2(cb - ca, d + sa - sb);
            double t = mod2pi(-a + tmp);
            double p = Math.sqrt(p2);
            double q = mod2pi(b - tmp);
            if (t + p + q < best) {
                best = t + p + q;
                bestTypes = new char[]{'L', 'S', 'L'};
                bestTpq = new double[]{t, p, q};
            }
        }

        p2 = 2 + d * d - 2 * cab + 2 * d * (sb - sa);
        if (p2 >= 0) {
            double tmp = Math.atan2(ca - cb, d - sa + sb);
            double t = mod2pi(a - tmp);
            double p = Math.sqrt(p2);
            double q = mod2pi(-b + tmp);
            if (t + p + q < best) {
                best = t + p + q;
                bestTypes = new char[]{'R', 'S', 'R'};
                bestTpq = new double[]{t, p, q};
            }
        }

        p2 = -2 + d * d + 2 * cab + 2 * d * (sa + sb);
        if (p2 >= 0) {
            double p = Math.sqrt(p2);
            double tmp = Math.atan2(-ca - cb, d + sa + sb) - Math.atan2(-2.0, p);
            double t = mod2pi(-a + tmp);
            double q = mod2pi(-b + tmp);
            if (t + p + q < best) {
                best = t + p + q;
                bestTypes = new char[]{'L', 'S', 'R'};
                bestTpq = new double[]{t, p, q};
            }
        }

        p2 = -2 + d * d + 2 * cab - 2 * d * (sa + sb);
        if (p2 >= 0) {
            double p = Math.sqrt(p2);
            double tmp = Math.atan2(ca + cb, d - sa - sb) - Math.atan2(2.0, p);
            double t = mod2pi(a - tmp);
            double q = mod2pi(b - tmp);
            if (t + p + q < best) {
                bestTypes = new char[]{'R', 'S', 'L'};
                bestTpq = new double[]{t, p, q};
            }
        }

        if (bestTypes == null) {
            return null;
        }
        double[] seg = new double[]{bestTpq[0] * r, bestTpq[1] * r, bestTpq[2] * r};
        return new DubinsPath(r, x0, z0, th0, bestTypes, seg);
    }

    public double[] sample(double s) {
        s = Math.max(0.0, Math.min(s, this.length));
        double x = this.x0;
        double z = this.z0;
        double th = this.th0;
        for (int i = 0; i < 3; i++) {
            double take = Math.min(s, this.seg[i]);
            double[] pose = step(x, z, th, this.types[i], take);
            x = pose[0];
            z = pose[1];
            th = pose[2];
            s -= take;
            if (s <= 1.0E-9) {
                break;
            }
        }
        return new double[]{x, z};
    }

    private double[] step(double x, double z, double th, char type, double len) {
        if (type == 'S') {
            return new double[]{x + len * Math.cos(th), z + len * Math.sin(th), th};
        }
        double phi = len / this.r;
        if (type == 'L') {
            double nth = th + phi;
            return new double[]{x - this.r * Math.sin(th) + this.r * Math.sin(nth),
                    z + this.r * Math.cos(th) - this.r * Math.cos(nth), nth};
        }
        double nth = th - phi;
        return new double[]{x + this.r * Math.sin(th) - this.r * Math.sin(nth),
                z - this.r * Math.cos(th) + this.r * Math.cos(nth), nth};
    }
}
