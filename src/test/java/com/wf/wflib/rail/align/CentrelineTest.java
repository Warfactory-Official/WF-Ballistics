package com.wf.wflib.rail.align;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutting a centreline up and turning it round.
 *
 * <p>Both are what a journey over several routes is made of, and both are wrong in ways that are
 * invisible from the outside: a reversed arc that reverses its <em>angles</em> rather than its
 * direction of travel still starts and ends in the right places, and a clipped straight that re-fits
 * itself still joins its neighbours. What gives either away is a train leaving the track a hundred
 * blocks later, so they are checked against the line they came from here instead.</p>
 */
class CentrelineTest {

    /** A line that bends: a straight, a left hand curve, a straight, a right hand curve. */
    private static Centreline line() {
        return Centreline.of(List.of(
                new AlignElement.Tangent(0.0, 0.0, 100.0, 0.0),
                new AlignElement.Curve(100.0, 80.0, 80.0, -Math.PI / 2.0, 1.0),
                new AlignElement.Tangent(167.3, 123.2, 220.0, 200.0),
                new AlignElement.Curve(300.0, 145.0, 60.0, 2.2, -0.9)));
    }

    @Test
    @DisplayName("a reversed line runs over the same ground backwards")
    void reversed() {
        Centreline line = line();
        Centreline back = line.reversed();
        assertEquals(line.length(), back.length(), 1.0E-6);
        for (double s = 0.0; s <= line.length(); s += 3.0) {
            AlignElement.Sample there = line.at(s);
            AlignElement.Sample here = back.at(line.length() - s);
            assertEquals(there.x(), here.x(), 0.02, "x at " + s);
            assertEquals(there.z(), here.z(), 0.02, "z at " + s);
            double turn = Math.abs(TrackTurn.turn(there.heading() + Math.PI, here.heading()));
            assertTrue(turn < 0.02, "heading at " + s + " is off by " + turn + " radians");
        }
    }

    @Test
    @DisplayName("reversing twice is the line you started with")
    void reversedTwice() {
        Centreline line = line();
        Centreline round = line.reversed().reversed();
        for (double s = 0.0; s <= line.length(); s += 7.0) {
            assertEquals(line.at(s).x(), round.at(s).x(), 1.0E-6);
            assertEquals(line.at(s).z(), round.at(s).z(), 1.0E-6);
        }
    }

    @Test
    @DisplayName("a part of a line is that part of it, to the block")
    void part() {
        Centreline line = line();
        Centreline middle = line.part(60.0, 240.0);
        assertEquals(180.0, middle.length(), 0.5);
        for (double s = 0.0; s <= middle.length(); s += 3.0) {
            AlignElement.Sample cut = middle.at(s);
            AlignElement.Sample whole = line.at(60.0 + s);
            assertEquals(whole.x(), cut.x(), 0.02, "x at " + s);
            assertEquals(whole.z(), cut.z(), 0.02, "z at " + s);
        }
    }

    @Test
    @DisplayName("a part is clamped to the line rather than running off the end of it")
    void partOutside() {
        Centreline line = line();
        assertEquals(line.length(), line.part(-50.0, line.length() + 50.0).length(), 1.0E-6);
        assertTrue(line.part(10.0, 10.0).isEmpty());
        assertTrue(line.part(line.length() + 1.0, line.length() + 9.0).isEmpty());
    }

    /** The shortest way to the signed turn between two headings, which is not what this class tests. */
    private static final class TrackTurn {
        static double turn(double from, double to) {
            double delta = (to - from) % (Math.PI * 2.0);
            if (delta > Math.PI) {
                delta -= Math.PI * 2.0;
            } else if (delta < -Math.PI) {
                delta += Math.PI * 2.0;
            }
            return delta;
        }
    }
}
