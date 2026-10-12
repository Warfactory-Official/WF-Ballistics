package com.wf.wflib.round.pen;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RicochetTest {

    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    private static final int N = 20000;

    private static Vec3 at(double deg, double speed) {
        double a = Math.toRadians(deg);
        return new Vec3(Math.cos(a), -Math.sin(a), 0.0).scale(speed);
    }

    private static double rate(double deg, float critical) {
        int n = 0;
        for (int s = 0; s < N; s++) {
            if (Ricochet.bounce(at(deg, 40.0), UP, critical, false, s) != null) {
                n++;
            }
        }
        return n / (double) N;
    }

    /** {@code P = ramp x (1 - 0.5 ratio)}: ratio 0.25 => 0.875, 1 => 0.25, 0.75 => 0.625, >= 1.25 => 0. */
    @Test
    void probabilityFollowsTheRampAndBreakUp() {
        assertEquals(0.875, rate(5.0, 20.0f), 0.01);
        assertEquals(0.625, rate(15.0, 20.0f), 0.01);
        assertEquals(0.25, rate(20.0, 20.0f), 0.01);
        assertEquals(0.0, rate(25.0, 20.0f));
        assertEquals(0.0, rate(5.0, 0.0f), "critical 0 never");
    }

    @Test
    void slowRoundsAndRisingRoundsNeverSkip() {
        for (int s = 0; s < 1000; s++) {
            assertNull(Ricochet.bounce(at(2.0, Ricochet.MIN_SPEED * 0.99), UP, 30.0f, false, s));
            assertNull(Ricochet.bounce(at(-2.0, 40.0), UP, 30.0f, false, s));
        }
    }

    /** Before scatter: {@code |out| = |(MU v_t, E v_n)|}; leaves >= MIN_OUT; flatter than it came. */
    @Test
    void outgoingKeepsTangentLosesNormal() {
        Vec3 v = at(10.0, 40.0);
        double vt = 40.0 * Math.cos(Math.toRadians(10.0)), vn = 40.0 * Math.sin(Math.toRadians(10.0));
        double want = Math.hypot(Ricochet.MU * vt, Ricochet.E * vn);
        int flatter = 0;
        for (int s = 0; s < N; s++) {
            Vec3 out = Ricochet.off(v, UP, s);
            assertEquals(want, out.length(), 1.0e-9);
            assertTrue(out.y >= out.length() * Math.sin(Ricochet.MIN_OUT) - 1.0e-9, "into the surface: " + out);
            if (out.y / out.length() < Math.sin(Math.toRadians(10.0))) {
                flatter++;
            }
        }
        assertTrue(flatter > N * 0.99, "flatter " + flatter);
    }

    @Test
    void seededAndRepeatable() {
        Vec3 v = at(3.0, 40.0);
        for (int s = 0; s < 1000; s++) {
            assertEquals(Ricochet.bounce(v, UP, 20.0f, false, s), Ricochet.bounce(v, UP, 20.0f, false, s));
        }
    }
}
