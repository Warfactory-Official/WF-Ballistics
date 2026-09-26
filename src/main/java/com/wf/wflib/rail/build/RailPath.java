package com.wf.wflib.rail.build;

import com.wf.wflib.rail.excavate.CarveVolume;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks a route's track actually occupies, and which way each piece of rail lies.
 *
 * <p>A surveyed centreline is a smooth curve through continuous space; track is a chain of blocks, each
 * of which connects to exactly two of its four neighbours. Turning one into the other is this class, and
 * it is the whole difference between a line on a map and something a train can run on.</p>
 *
 * <p>Two rules make it work. <b>Every step is cardinal:</b> a curve that moves a block diagonally is
 * walked as two steps, because a rail that connects diagonally does not exist. And <b>the shape of a
 * piece is decided by the pieces either side of it</b> rather than by the direction of travel through
 * it, which is what puts a corner where the line turns instead of a straight rail pointing into a
 * wall.</p>
 *
 * <p>The whole path is worked out in one go even when the track is laid a few blocks at a time. A piece
 * laid before its successor exists would have to guess its own shape and be corrected later, and a
 * corrected rail is one that was briefly wrong in front of whoever was watching.</p>
 */
public record RailPath(List<Cell> cells, double length) {

    /** How finely the centreline is walked. Half a block cannot step over a cell of any width. */
    private static final double STEP = 0.5;

    public RailPath {
        cells = List.copyOf(cells);
    }

    /**
     * One block of track.
     *
     * @param chainage how far along the route this piece is, which is what the machine laying it knows
     */
    public record Cell(int x, int z, RailShape shape, double chainage) {

        /** @return whether this piece is a straight, which is all a powered rail is allowed to be. */
        public boolean straight() {
            return this.shape == RailShape.NORTH_SOUTH || this.shape == RailShape.EAST_WEST;
        }
    }

    public boolean isEmpty() {
        return this.cells.isEmpty();
    }

    /** Lay the track out along a corridor's own snapped centreline. */
    public static RailPath along(CarveVolume.Corridor path) {
        List<int[]> cells = new ArrayList<>();
        List<Double> at = new ArrayList<>();
        double length = path.length();
        for (double s = 0.0; s < length; s += STEP) {
            double[] p = path.pointAt(s);
            walk(cells, at, (int) Math.floor(p[0]), (int) Math.floor(p[1]), s);
        }
        double[] end = path.pointAt(length);
        walk(cells, at, (int) Math.floor(end[0]), (int) Math.floor(end[1]), length);

        List<Cell> out = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            int[] prev = i > 0 ? cells.get(i - 1) : null;
            int[] next = i < cells.size() - 1 ? cells.get(i + 1) : null;
            out.add(new Cell(cells.get(i)[0], cells.get(i)[1], shapeOf(prev, cells.get(i), next),
                    at.get(i)));
        }
        return new RailPath(out, length);
    }

    /**
     * Step from the last cell to this one, one cardinal move at a time.
     *
     * <p>A sample that lands a block diagonally away is not one step but two, and which of the two
     * intermediate cells the curve really passed through is decided by whichever axis has further to
     * go. Picking the other one would put the corner on the wrong side of the block, which on a long
     * shallow curve reads as the track wandering off the centreline and back.</p>
     */
    private static void walk(List<int[]> cells, List<Double> at, int x, int z, double s) {
        if (cells.isEmpty()) {
            cells.add(new int[]{x, z});
            at.add(s);
            return;
        }
        while (true) {
            int[] last = cells.get(cells.size() - 1);
            int dx = x - last[0];
            int dz = z - last[1];
            if (dx == 0 && dz == 0) {
                return;
            }
            int nx = last[0];
            int nz = last[1];
            if (Math.abs(dx) >= Math.abs(dz)) {
                nx += Integer.signum(dx);
            } else {
                nz += Integer.signum(dz);
            }
            // A curve that grazes the corner of a block can sample its way back into the cell before
            // last. Track that doubles back is not track, so the wobble is dropped rather than laid.
            if (cells.size() >= 2) {
                int[] before = cells.get(cells.size() - 2);
                if (before[0] == nx && before[1] == nz) {
                    return;
                }
            }
            cells.add(new int[]{nx, nz});
            at.add(s);
        }
    }

    /** The shape of a piece with these neighbours, either of which may be absent at the ends. */
    static RailShape shapeOf(int[] prev, int[] cell, int[] next) {
        Direction back = prev == null ? null : towards(cell, prev);
        Direction on = next == null ? null : towards(cell, next);
        if (back == null && on == null) {
            return RailShape.EAST_WEST;
        }
        if (back == null) {
            return straight(on);
        }
        if (on == null) {
            return straight(back);
        }
        return back.getAxis() == on.getAxis() ? straight(on) : corner(back, on);
    }

    private static RailShape straight(Direction along) {
        return along.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
    }

    /** The corner joining two sides. Named for the two directions it connects, like the enum is. */
    private static RailShape corner(Direction a, Direction b) {
        boolean north = a == Direction.NORTH || b == Direction.NORTH;
        boolean east = a == Direction.EAST || b == Direction.EAST;
        if (north) {
            return east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
        }
        return east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
    }

    private static Direction towards(int[] from, int[] to) {
        if (to[0] != from[0]) {
            return to[0] > from[0] ? Direction.EAST : Direction.WEST;
        }
        return to[1] > from[1] ? Direction.SOUTH : Direction.NORTH;
    }
}
