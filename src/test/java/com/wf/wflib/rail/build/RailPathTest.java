package com.wf.wflib.rail.build;

import com.wf.wflib.rail.excavate.CarveVolume;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a surveyed curve into track a train can actually run down.
 *
 * <p>Almost every test here is really the same test: <b>each piece joins the next</b>. A rail whose
 * shape does not point at the rail beside it is a gap, and a gap is where a train stops. It cannot be
 * seen from a screenshot of a finished tunnel either - the track looks continuous from any distance a
 * person stands at, and only a cart finds out.</p>
 */
class RailPathTest {

    /** Section width, which is what the corridor snaps its vertices to. Even: a block boundary. */
    private static final int WIDTH = 8;

    private static CarveVolume.Corridor corridor(double[] xs, double[] zs) {
        return CarveVolume.corridor(xs, zs, WIDTH, 0, 6);
    }

    /** Whether a rail of this shape has a connection on this side. */
    private static boolean connects(RailShape shape, Direction side) {
        return switch (shape) {
            case NORTH_SOUTH -> side == Direction.NORTH || side == Direction.SOUTH;
            case EAST_WEST -> side == Direction.EAST || side == Direction.WEST;
            case SOUTH_EAST -> side == Direction.SOUTH || side == Direction.EAST;
            case SOUTH_WEST -> side == Direction.SOUTH || side == Direction.WEST;
            case NORTH_WEST -> side == Direction.NORTH || side == Direction.WEST;
            case NORTH_EAST -> side == Direction.NORTH || side == Direction.EAST;
            default -> false;
        };
    }

    private static Direction towards(RailPath.Cell from, RailPath.Cell to) {
        if (to.x() != from.x()) {
            return to.x() > from.x() ? Direction.EAST : Direction.WEST;
        }
        return to.z() > from.z() ? Direction.SOUTH : Direction.NORTH;
    }

    /** The whole invariant: every step is cardinal, and both pieces of it point at each other. */
    private static void assertRunnable(RailPath path) {
        List<RailPath.Cell> cells = path.cells();
        assertTrue(cells.size() > 1, "a route should produce more than one piece of track");
        for (int i = 0; i < cells.size() - 1; i++) {
            RailPath.Cell here = cells.get(i);
            RailPath.Cell next = cells.get(i + 1);
            int steps = Math.abs(next.x() - here.x()) + Math.abs(next.z() - here.z());
            assertEquals(1, steps, "piece " + i + " to " + (i + 1) + " is not one cardinal step:"
                    + " (" + here.x() + ", " + here.z() + ") to (" + next.x() + ", " + next.z() + ")");
            assertTrue(connects(here.shape(), towards(here, next)),
                    "piece " + i + " (" + here.shape() + ") does not join the one after it");
            assertTrue(connects(next.shape(), towards(next, here)),
                    "piece " + (i + 1) + " (" + next.shape() + ") does not join the one before it");
        }
    }

    @Test
    @DisplayName("a straight run is one piece per block, all lying the same way")
    void straightRun() {
        RailPath path = RailPath.along(corridor(new double[]{0.0, 40.0}, new double[]{0.0, 0.0}));
        assertRunnable(path);
        assertEquals(41, path.cells().size());
        for (RailPath.Cell cell : path.cells()) {
            assertEquals(RailShape.EAST_WEST, cell.shape());
            assertTrue(cell.straight(), "a straight run should take boosters anywhere");
        }
    }

    @Test
    @DisplayName("a right angle turns on exactly one piece, and that piece is a corner")
    void rightAngle() {
        RailPath path = RailPath.along(corridor(
                new double[]{0.0, 40.0, 40.0}, new double[]{0.0, 0.0, 40.0}));
        assertRunnable(path);
        long corners = path.cells().stream().filter(cell -> !cell.straight()).count();
        assertEquals(1, corners, "one bend should make one corner piece");
        RailPath.Cell corner = path.cells().stream().filter(cell -> !cell.straight()).findFirst()
                .orElseThrow();
        // In from the west, out to the south.
        assertEquals(RailShape.SOUTH_WEST, corner.shape());
        assertFalse(corner.straight(), "a corner can never carry a powered rail");
    }

    @Test
    @DisplayName("a curve never steps diagonally and never doubles back")
    void curve() {
        int points = 33;
        double[] xs = new double[points];
        double[] zs = new double[points];
        for (int i = 0; i < points; i++) {
            double angle = Math.PI / 2.0 * i / (points - 1);
            xs[i] = 60.0 * Math.sin(angle);
            zs[i] = 60.0 - 60.0 * Math.cos(angle);
        }
        RailPath path = RailPath.along(corridor(xs, zs));
        assertRunnable(path);
        // A quarter circle of radius 60 is 94 blocks of arc, and the staircase that approximates it is
        // the two sides of the square: no more than 60 of each.
        assertTrue(path.cells().size() <= 125,
                "a quarter circle should not need " + path.cells().size() + " pieces");
    }

    @Test
    @DisplayName("chainage runs forwards along the route and never past its end")
    void chainageIsOrdered() {
        CarveVolume.Corridor path = corridor(
                new double[]{0.0, 40.0, 40.0}, new double[]{0.0, 0.0, 40.0});
        RailPath rails = RailPath.along(path);
        double last = -1.0;
        for (RailPath.Cell cell : rails.cells()) {
            assertTrue(cell.chainage() >= last, "chainage went backwards at " + cell.chainage());
            assertTrue(cell.chainage() <= path.length() + 1.0E-9,
                    cell.chainage() + " is past the end of a " + path.length() + " block route");
            last = cell.chainage();
        }
    }

    @Test
    @DisplayName("a route that doubles back on itself still lays joined track")
    void hairpin() {
        RailPath path = RailPath.along(corridor(
                new double[]{0.0, 30.0, 30.0, 0.0}, new double[]{0.0, 0.0, 12.0, 12.0}));
        assertRunnable(path);
        assertEquals(2, path.cells().stream().filter(cell -> !cell.straight()).count(),
                "two bends should make two corner pieces");
    }
}
