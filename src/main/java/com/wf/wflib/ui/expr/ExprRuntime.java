package com.wf.wflib.ui.expr;

/** Targets of compiled calls; public for the hidden classes. */
public final class ExprRuntime {
    private ExprRuntime() {
    }

    public static double round(double x) {
        return Math.floor(x + 0.5);
    }

    /** Towards zero, like a Java (int) cast. */
    public static double trunc(double x) {
        return x < 0 ? Math.ceil(x) : Math.floor(x);
    }

    public static double fract(double x) {
        return x - Math.floor(x);
    }

    /** Floored: result has the sign of {@code y}. */
    public static double mod(double x, double y) {
        return x - Math.floor(x / y) * y;
    }

    public static double step(double edge, double x) {
        return x < edge ? 0 : 1;
    }

    public static double nz(double x, double fallback) {
        return Double.isNaN(x) ? fallback : x;
    }

    public static double isnan(double x) {
        return Double.isNaN(x) ? 1 : 0;
    }

    public static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : x > hi ? hi : x;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double invlerp(double a, double b, double x) {
        return a == b ? 0 : (x - a) / (b - a);
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = clamp(invlerp(e0, e1, x), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Into [lo, hi). */
    public static double wrap(double x, double lo, double hi) {
        return lo + mod(x - lo, hi - lo);
    }

    public static double remap(double x, double a0, double a1, double b0, double b1) {
        return lerp(b0, b1, invlerp(a0, a1, x));
    }

    private static int channel(double v) {
        return (int) clamp(Math.floor(v + 0.5), 0, 255);
    }

    public static double rgb(double r, double g, double b) {
        return 0xFF000000L | (long) channel(r) << 16 | (long) channel(g) << 8 | channel(b);
    }

    public static double rgba(double r, double g, double b, double a) {
        return (long) channel(a) << 24 | (long) channel(r) << 16 | (long) channel(g) << 8 | channel(b);
    }

    /** Colour with alpha replaced by {@code a} in [0, 1]. */
    public static double alpha(double colour, double a) {
        long c = (long) colour & 0xFFFFFFL;
        return (long) channel(a * 255) << 24 | c;
    }

    /** Per-channel ARGB lerp. */
    public static double mixc(double c0, double c1, double t) {
        long a = (long) c0;
        long b = (long) c1;
        double u = clamp(t, 0, 1);
        long out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            double x = (a >>> shift & 0xFF) + ((b >>> shift & 0xFF) - (a >>> shift & 0xFF)) * u;
            out |= (long) channel(x) << shift;
        }
        return out;
    }

    /** True for the first half of each period. */
    public static double blink(double period, double time) {
        return period > 0 && mod(time, period) < period * 0.5 ? 1 : 0;
    }

    /** Exponential approach with time constant {@code tau} seconds; first call returns {@code x}. */
    public static double smooth(double[] state, int cell, double x, double tau, double dt) {
        double prev = state[cell];
        double next = Double.isNaN(prev) || tau <= 0 || Double.isNaN(x) ? x : prev + (x - prev) * (1 - Math.exp(-dt / tau));
        state[cell] = next;
        return next;
    }

    /** Per-second derivative; 0 on the first call and on a zero-length frame. */
    public static double rate(double[] state, int cell, double x, double dt) {
        double prev = state[cell];
        double last = state[cell + 1];
        state[cell] = x;
        if (Double.isNaN(prev) || dt <= 0) {
            state[cell + 1] = Double.isNaN(last) ? 0 : last;
            return state[cell + 1];
        }
        double r = (x - prev) / dt;
        state[cell + 1] = r;
        return r;
    }

    /** Seconds {@code condition} has held without a break; 0 while it does not. */
    public static double since(double[] state, int cell, double condition, double dt) {
        double held = condition != 0 && !Double.isNaN(state[cell]) ? state[cell] + dt : 0;
        state[cell] = condition != 0 ? held : Double.NaN;
        return held;
    }

    /** Seconds since {@code x} last changed value (NaN equals NaN); 0 on the first call. */
    public static double age(double[] state, int cell, double x, double dt) {
        double last = state[cell];
        boolean first = Double.isNaN(state[cell + 1]);
        if (first || Double.doubleToLongBits(last) != Double.doubleToLongBits(x)) {
            state[cell] = x;
            state[cell + 1] = 0;
            return 0;
        }
        state[cell + 1] += dt;
        return state[cell + 1];
    }
}
