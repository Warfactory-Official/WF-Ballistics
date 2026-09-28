package com.wf.wflib.round;

import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.warhead.WarheadRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent recurrence; real {@code Rounds} step: {@code RoundGameTest.anArcPredictionIsTheFlight}. */
class RoundArcTest {

    private static KineticPreset preset(String id, double drag, double quadratic, double gravity) {
        return KineticPreset.builder(KineticPresetRegistry.rl(id), null, WarheadRegistry.rl("inert"))
                .speed(40.0).drag(drag).quadraticDrag(quadratic).gravity(gravity).life(300).build();
    }

    private static void assertBitEqual(KineticPreset p, double x, double y, double z, double vx, double vy,
                                       double vz, int life, double voidY, int ticks, int expectedSteps) {
        double[] out = new double[3 * ticks];
        int n = RoundArc.predict(p, x, y, z, vx, vy, vz, life, voidY, ticks, out);
        assertEquals(expectedSteps, n, "steps");
        for (int k = 0; k < n; k++) {
            x += vx;
            y += vy;
            z += vz;
            double decay = p.decay(Math.sqrt(vx * vx + vy * vy + vz * vz));
            vx *= decay;
            vy = vy * decay - p.gravity();
            vz *= decay;
            assertEquals(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(out[3 * k]), "x @" + k);
            assertEquals(Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(out[3 * k + 1]), "y @" + k);
            assertEquals(Double.doubleToRawLongBits(z), Double.doubleToRawLongBits(out[3 * k + 2]), "z @" + k);
        }
    }

    @Test
    void linearDragIsTheRecurrence() {
        assertBitEqual(preset("arc_linear", 0.01, 0.0, 0.05), 1.0e7 + 0.3, 80.1, -3.7, 40.0, 1.3, 0.7, 300,
                Double.NEGATIVE_INFINITY, 200, 200);
    }

    @Test
    void quadraticDragIsTheRecurrence() {
        assertBitEqual(preset("arc_quadratic", 0.002, 0.0011722, 0.03), 12.5, 64.0, 7.25, 31.0, 4.0, -9.0, 300,
                Double.NEGATIVE_INFINITY, 200, 200);
    }

    /** Server: {@code --life <= 0} before the move => life L leaves L - 1 steps. */
    @Test
    void lifeEndsTheArc() {
        assertBitEqual(preset("arc_life", 0.01, 0.0, 0.05), 0.0, 64.0, 0.0, 3.0, 0.0, 0.0, 17,
                Double.NEGATIVE_INFINITY, 200, 16);
    }

    /** Checked on the state before the step: the first step below the floor while descending is not taken. */
    @Test
    void theVoidEndsTheArc() {
        assertBitEqual(preset("arc_void", 0.0, 0.0, 0.0), 0.0, 10.0, 0.0, 0.0, -1.0, 0.0, 300, 0.0, 200, 11);
    }
}
