package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;

/** {@link Rounds} flight continued dry, without collision: bit-equal to its steps in open air. */
public final class RoundArc {

    private RoundArc() {
    }

    /**
     * Positions after each of up to {@code ticks} steps from {@code (x, v)} with {@code life} left, into
     * {@code out[3k..3k+2]}; stops where {@link Rounds} expires the round (life, or below {@code voidY} descending).
     * @return steps written
     */
    public static int predict(KineticPreset preset, double x, double y, double z, double vx, double vy, double vz,
                              int life, double voidY, int ticks, double[] out) {
        int n = Math.min(ticks, out.length / 3);
        float gravity = preset.gravity();
        for (int k = 0; k < n; k++) {
            if (--life <= 0 || y < voidY && vy <= 0.0) {
                return k;
            }
            x += vx;
            y += vy;
            z += vz;
            out[3 * k] = x;
            out[3 * k + 1] = y;
            out[3 * k + 2] = z;
            double decay = preset.decay(Math.sqrt(vx * vx + vy * vy + vz * vz));
            vx = vx * decay;
            vy = vy * decay - gravity;
            vz = vz * decay;
        }
        return n;
    }
}
