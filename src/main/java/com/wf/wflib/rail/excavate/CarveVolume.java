package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** The shape a carve removes, in world block coordinates. */
public interface CarveVolume {

    /** @return the block-space box enclosing this volume, used to pick which chunks and sections to visit. */
    BoundingBox bounds();

    /** @return whether this block position is inside the volume. */
    boolean contains(int x, int y, int z);

    /** A capsule: every block within {@code radius} of the segment from {@code from} to {@code to}. */
    static CarveVolume capsule(Vec3 from, Vec3 to, double radius) {
        return new Capsule(from, to, radius);
    }

    /** A cuboid: everything inside the box. */
    static CarveVolume box(BoundingBox box) {
        return new Box(box);
    }

    /**
     * A corridor: a rectangular cross-section swept along a polyline, with a flat floor and a flat roof.
     *
     * <p>The shape a railway tunnel actually is. A capsule is the wrong profile for one: it gives a
     * round bore whose floor is a single block wide, so there is nowhere to lay track and the clearance
     * the design class asked for is only met on the centreline.</p>
     *
     * @param width cells across, measured horizontally and perpendicular to the route
     * @param floorY y of the lowest cell inside the corridor
     * @param height cells tall
     */
    static Corridor corridor(double[] xs, double[] zs, int width, int floorY, int height) {
        return new Corridor(xs, zs, width, floorY, height);
    }

    /** Everything in {@code outer} that is not in {@code inner}: the skin of a volume. */
    static CarveVolume difference(CarveVolume outer, CarveVolume inner) {
        return new Difference(outer, inner);
    }

    /** Everything in any of them, for a volume made of several separate pieces. */
    static CarveVolume union(java.util.List<CarveVolume> parts) {
        return new Union(java.util.List.copyOf(parts));
    }

    /** One course of a volume: everything in it at exactly this height and nothing above or below. */
    static CarveVolume atLevel(CarveVolume volume, int y) {
        return new Level(volume, y);
    }

    /** Exactly these block positions and nothing else, for work that is a list rather than a shape. */
    static CarveVolume positions(java.util.Collection<net.minecraft.core.BlockPos> positions) {
        return new Positions(positions);
    }

    /** @see #corridor */
    final class Corridor implements CarveVolume {

        /** Most a corridor's end is pushed out past its surveyed end, in blocks. */
        private static final double MAX_END_REACH = 1.5;

        private final double[] xs;
        private final double[] zs;
        /** Per-segment bounds, so a cell rejects most of a long route in a few comparisons. */
        private final double[] segMinX;
        private final double[] segMaxX;
        private final double[] segMinZ;
        private final double[] segMaxZ;
        private final double halfWidth;
        private final double halfWidthSq;
        /** Chainage at each vertex, so a point's distance along the whole corridor is one lookup. */
        private final double[] cumulative;
        private final int width;
        private final int floorY;
        private final int height;
        private final BoundingBox bounds;

        Corridor(double[] xs, double[] zs, int width, int floorY, int height) {
            if (xs.length != zs.length || xs.length < 2) {
                throw new IllegalArgumentException("a corridor needs at least two points");
            }
            // The two end vertices are pushed outwards, and nothing else is moved at all.
            //
            // Outwards, because a corridor whose end cross-section sits exactly on the surveyed end
            // leaves the route's own last fraction of a block projecting past it, where the sweep calls
            // it end wall rather than tunnel: no track can be laid there and a train has nowhere to
            // stand. Rounding the end out to the cell grid is what covers it, and costs at most a block
            // of extra tunnel.
            //
            // But **along the route**, not towards the nearest grid point in each axis separately.
            // Rounding each coordinate moves the vertex sideways as well as outwards, which tilts the
            // end segment; and the same rounding applied to the vertices in between, which are samples
            // of the surveyed curve every few blocks, was a quiet disaster on any route not running
            // along an axis. Each one moves up to half a block sideways, turning a straight diagonal
            // line into a zigzag whose segments sit fourteen degrees either side of the true heading.
            // The section is swept along each segment in turn, so the tunnel wanders from side to side
            // around track that runs straight down the middle, because the track is built from the
            // centreline and not from this. From inside that is a tunnel a little too narrow with a
            // course of wall missing down one side, changing sides every few blocks.
            //
            // Nothing is lost by leaving the middle alone. What decides how many cells across a
            // corridor is is the distance from its axis, and a band of even width holds exactly that
            // many block centres whether the axis runs along a block boundary or down the middle of
            // one.
            double snap = (width & 1) == 0 ? 0.0 : 0.5;
            int last = xs.length - 1;
            this.xs = xs.clone();
            this.zs = zs.clone();
            extendEnd(this.xs, this.zs, 0, 1, snap);
            extendEnd(this.xs, this.zs, last, last - 1, snap);

            this.width = width;
            this.halfWidth = width / 2.0;
            this.halfWidthSq = this.halfWidth * this.halfWidth;
            this.floorY = floorY;
            this.height = height;

            int segments = xs.length - 1;
            this.cumulative = new double[segments + 1];
            for (int i = 0; i < segments; i++) {
                this.cumulative[i + 1] = this.cumulative[i]
                        + Math.hypot(this.xs[i + 1] - this.xs[i], this.zs[i + 1] - this.zs[i]);
            }
            this.segMinX = new double[segments];
            this.segMaxX = new double[segments];
            this.segMinZ = new double[segments];
            this.segMaxZ = new double[segments];
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < segments; i++) {
                this.segMinX[i] = Math.min(this.xs[i], this.xs[i + 1]) - this.halfWidth;
                this.segMaxX[i] = Math.max(this.xs[i], this.xs[i + 1]) + this.halfWidth;
                this.segMinZ[i] = Math.min(this.zs[i], this.zs[i + 1]) - this.halfWidth;
                this.segMaxZ[i] = Math.max(this.zs[i], this.zs[i + 1]) + this.halfWidth;
                minX = Math.min(minX, this.segMinX[i]);
                maxX = Math.max(maxX, this.segMaxX[i]);
                minZ = Math.min(minZ, this.segMinZ[i]);
                maxZ = Math.max(maxZ, this.segMaxZ[i]);
            }
            this.bounds = new BoundingBox(
                    (int) Math.floor(minX), floorY, (int) Math.floor(minZ),
                    (int) Math.ceil(maxX), floorY + height - 1, (int) Math.ceil(maxZ));
        }

        /**
         * The same corridor grown by {@code cells} in every direction: its shell, plus itself.
         *
         * <p>"Every direction" includes along the axis, which is what puts an end wall on a tunnel. A
         * growth that only widened and heightened would leave both portals open, and a tunnel that stops
         * inside an aquifer would fill from its own face.</p>
         */
        /**
         * Push one end vertex out along its own segment, far enough to cover the cell its end is in.
         *
         * <p>The distance is whatever it takes to reach the outward-rounded coordinate in whichever
         * axis the route actually runs along, which is nothing at all when the end is already on the
         * grid. Moving along the segment rather than towards the rounded point is the whole trick: the
         * line stays exactly as straight as it was surveyed.</p>
         */
        private static void extendEnd(double[] xs, double[] zs, int at, int towards, double snap) {
            double dx = xs[at] - xs[towards];
            double dz = zs[at] - zs[towards];
            double length = Math.hypot(dx, dz);
            if (length <= 1.0E-9) {
                return;
            }
            double ux = dx / length;
            double uz = dz / length;
            double need = Math.max(along(xs[at], toCell(xs[at], snap, ux), ux),
                    along(zs[at], toCell(zs[at], snap, uz), uz));
            // Capped, because a route running very nearly along one axis needs an enormous distance
            // along itself to shift the other coordinate by half a block, and it does not want to: a
            // corridor that barely moves in an axis is not going to miss a cell in it.
            double reach = Math.max(0.0, Math.min(need, MAX_END_REACH));
            xs[at] += ux * reach;
            zs[at] += uz * reach;
        }

        /** How far along a direction it is to a coordinate, or nothing when the route barely moves in it. */
        private static double along(double from, double to, double unit) {
            return Math.abs(unit) < 0.1 ? 0.0 : (to - from) / unit;
        }

        /** The cell boundary on the outward side of a coordinate, or the coordinate when it is on one. */
        private static double toCell(double value, double snap, double outward) {
            double local = value - snap;
            if (outward > 0.0) {
                return Math.ceil(local) + snap;
            }
            if (outward < 0.0) {
                return Math.floor(local) + snap;
            }
            return Math.floor(local + 0.5) + snap;
        }

        public Corridor grown(int cells) {
            double[] gx = this.xs.clone();
            double[] gz = this.zs.clone();
            extend(gx, gz, 0, 1, cells);
            extend(gx, gz, gx.length - 1, gx.length - 2, cells);
            return new Corridor(gx, gz, this.width + cells * 2, this.floorY - cells,
                    this.height + cells * 2);
        }

        /** Push the end vertex {@code at} away from its neighbour {@code towards} by {@code cells}. */
        private static void extend(double[] xs, double[] zs, int at, int towards, int cells) {
            double dx = xs[at] - xs[towards];
            double dz = zs[at] - zs[towards];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len <= 1.0E-9) {
                return;
            }
            xs[at] += dx / len * cells;
            zs[at] += dz / len * cells;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }

        public int floorY() {
            return this.floorY;
        }

        /** Where a point sits in the corridor's own frame. */
        public record Local(double chainage, double offset) {
        }

        /** @return the snapped polyline's x at a vertex, which is what the profile is swept along. */
        public double[] xs() {
            return this.xs.clone();
        }

        public double[] zs() {
            return this.zs.clone();
        }

        public double length() {
            return this.cumulative[this.cumulative.length - 1];
        }

        /**
         * Where a point sits along and across the corridor.
         *
         * <p>The offset is signed, positive to the left of the direction of travel, so a profile always
         * sweeps the same way round however the route bends.</p>
         *
         * @param endAllowance how far past each end of the corridor still counts, in blocks. One block
         *                     is what puts an end wall on a tunnel.
         * @return the local position, or null when the point is not on the corridor at all
         */
        public Local localAt(double px, double pz, double endAllowance) {
            int last = this.segMinX.length - 1;
            double bestDistance = Double.MAX_VALUE;
            Local best = null;
            for (int i = 0; i <= last; i++) {
                double ax = this.xs[i];
                double az = this.zs[i];
                double bx = this.xs[i + 1] - ax;
                double bz = this.zs[i + 1] - az;
                double segLen = Math.sqrt(bx * bx + bz * bz);
                if (segLen <= 1.0E-9) {
                    continue;
                }
                double raw = ((px - ax) * bx + (pz - az) * bz) / (segLen * segLen);
                // Clamping at an interior joint is what fills the wedge on the outside of a bend, so it
                // has to happen there. At the two ends of the corridor it must not: a clamped end
                // measures only the sideways distance and ignores the overshoot, so the tunnel would
                // run on past the last point the surveyor put down for as far as the section is wide.
                double low = i == 0 ? -endAllowance / segLen : 0.0;
                double high = i == last ? 1.0 + endAllowance / segLen : 1.0;
                // A hair of slack at the ends. A point sitting exactly on the final cross-section
                // computes raw as 1.0000000000000002 as often as 1.0, and dropping the segment for that
                // sends the point to a worse one, where it is measured against the wrong part of the
                // route and comes back as wall.
                if ((i == 0 && raw < low - 1.0E-9) || (i == last && raw > high + 1.0E-9)) {
                    continue;
                }
                double t = Math.max(0.0, Math.min(raw, 1.0));
                if (i == 0 && raw < 0.0) {
                    t = raw;
                } else if (i == last && raw > 1.0) {
                    t = raw;
                }
                double dx = px - (ax + bx * t);
                double dz = pz - (az + bz * t);
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    // Signed by which side of the direction of travel the point is on, so a section
                    // always sweeps the same way round however the route bends.
                    double side = bx * (pz - az) - bz * (px - ax);
                    best = new Local(this.cumulative[i] + t * segLen,
                            side < 0.0 ? -distance : distance);
                }
            }
            return best;
        }

        /** The snapped path's position and heading at a distance along it. */
        public double[] pointAt(double chainage) {
            int last = this.segMinX.length - 1;
            double s = Math.max(0.0, Math.min(chainage, length()));
            for (int i = 0; i <= last; i++) {
                double segLen = this.cumulative[i + 1] - this.cumulative[i];
                if (segLen <= 0.0) {
                    continue;
                }
                if (s <= this.cumulative[i + 1] || i == last) {
                    double t = (s - this.cumulative[i]) / segLen;
                    double bx = (this.xs[i + 1] - this.xs[i]) / segLen;
                    double bz = (this.zs[i + 1] - this.zs[i]) / segLen;
                    return new double[]{this.xs[i] + bx * (t * segLen), this.zs[i] + bz * (t * segLen),
                            bx, bz};
                }
            }
            return new double[]{this.xs[0], this.zs[0], 1.0, 0.0};
        }

        @Override
        public BoundingBox bounds() {
            return this.bounds;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (y < this.floorY || y >= this.floorY + this.height) {
                return false;
            }
            double px = x + 0.5;
            double pz = z + 0.5;
            int last = this.segMinX.length - 1;
            for (int i = 0; i <= last; i++) {
                if (px < this.segMinX[i] || px > this.segMaxX[i]
                        || pz < this.segMinZ[i] || pz > this.segMaxZ[i]) {
                    continue;
                }
                if (distanceSqToSegment(px, pz, i, last) <= this.halfWidthSq) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Distance to one segment, with the ends of the whole corridor cut square.
         *
         * <p>Clamping the projection onto a segment is what fills the wedge on the outside of a bend, so
         * it has to happen at every interior joint. At the two ends of the corridor it must not: a
         * clamped end is a half-round cap, which bores half the tunnel width past the point the surveyor
         * actually put down and leaves a domed portal. Squaring them off makes a route of a given length
         * excavate exactly that length.</p>
         */
        private double distanceSqToSegment(double px, double pz, int i, int last) {
            double ax = this.xs[i];
            double az = this.zs[i];
            double bx = this.xs[i + 1] - ax;
            double bz = this.zs[i + 1] - az;
            double lenSq = bx * bx + bz * bz;
            double t = lenSq <= 1.0E-9 ? 0.0 : ((px - ax) * bx + (pz - az) * bz) / lenSq;
            if (t < 0.0) {
                if (i == 0) {
                    return Double.MAX_VALUE;
                }
                t = 0.0;
            } else if (t > 1.0) {
                if (i == last) {
                    return Double.MAX_VALUE;
                }
                t = 1.0;
            }
            double dx = px - (ax + bx * t);
            double dz = pz - (az + bz * t);
            return dx * dx + dz * dz;
        }
    }

    /** @see #positions */
    final class Positions implements CarveVolume {

        private final java.util.Set<Long> packed = new java.util.HashSet<>();
        private final BoundingBox bounds;

        Positions(java.util.Collection<net.minecraft.core.BlockPos> positions) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (net.minecraft.core.BlockPos pos : positions) {
                this.packed.add(pos.asLong());
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            this.bounds = positions.isEmpty()
                    ? new BoundingBox(0, 0, 0, 0, 0, 0)
                    : new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public boolean isEmpty() {
            return this.packed.isEmpty();
        }

        @Override
        public BoundingBox bounds() {
            return this.bounds;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return this.packed.contains(net.minecraft.core.BlockPos.asLong(x, y, z));
        }
    }

    /** @see #difference */
    final class Difference implements CarveVolume {

        private final CarveVolume outer;
        private final CarveVolume inner;

        Difference(CarveVolume outer, CarveVolume inner) {
            this.outer = outer;
            this.inner = inner;
        }

        @Override
        public BoundingBox bounds() {
            return this.outer.bounds();
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return this.outer.contains(x, y, z) && !this.inner.contains(x, y, z);
        }
    }

    /** @see #union */
    final class Union implements CarveVolume {

        private final java.util.List<CarveVolume> parts;
        private final BoundingBox bounds;

        Union(java.util.List<CarveVolume> parts) {
            this.parts = parts;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (CarveVolume part : parts) {
                BoundingBox box = part.bounds();
                minX = Math.min(minX, box.minX());
                minY = Math.min(minY, box.minY());
                minZ = Math.min(minZ, box.minZ());
                maxX = Math.max(maxX, box.maxX());
                maxY = Math.max(maxY, box.maxY());
                maxZ = Math.max(maxZ, box.maxZ());
            }
            this.bounds = parts.isEmpty() ? new BoundingBox(0, 0, 0, 0, 0, 0)
                    : new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public BoundingBox bounds() {
            return this.bounds;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            for (CarveVolume part : this.parts) {
                if (part.contains(x, y, z)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** @see #atLevel */
    final class Level implements CarveVolume {

        private final CarveVolume volume;
        private final int y;

        Level(CarveVolume volume, int y) {
            this.volume = volume;
            this.y = y;
        }

        @Override
        public BoundingBox bounds() {
            BoundingBox box = this.volume.bounds();
            return new BoundingBox(box.minX(), this.y, box.minZ(), box.maxX(), this.y, box.maxZ());
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y == this.y && this.volume.contains(x, y, z);
        }
    }

    /** @see #capsule */
    final class Capsule implements CarveVolume {

        private final Vec3 from;
        private final Vec3 axis;
        private final double lengthSq;
        private final double radius;
        private final double radiusSq;
        private final BoundingBox bounds;

        Capsule(Vec3 from, Vec3 to, double radius) {
            this.from = from;
            this.axis = to.subtract(from);
            this.lengthSq = this.axis.lengthSqr();
            this.radius = radius;
            this.radiusSq = radius * radius;
            this.bounds = new BoundingBox(
                    (int) Math.floor(Math.min(from.x, to.x) - radius),
                    (int) Math.floor(Math.min(from.y, to.y) - radius),
                    (int) Math.floor(Math.min(from.z, to.z) - radius),
                    (int) Math.ceil(Math.max(from.x, to.x) + radius),
                    (int) Math.ceil(Math.max(from.y, to.y) + radius),
                    (int) Math.ceil(Math.max(from.z, to.z) + radius));
        }

        @Override
        public BoundingBox bounds() {
            return this.bounds;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            // Block centres, so the tunnel radius means what it says rather than being half a block tight.
            double px = x + 0.5 - this.from.x;
            double py = y + 0.5 - this.from.y;
            double pz = z + 0.5 - this.from.z;
            double t = this.lengthSq <= 1.0E-9 ? 0.0
                    : (px * this.axis.x + py * this.axis.y + pz * this.axis.z) / this.lengthSq;
            t = t < 0.0 ? 0.0 : Math.min(t, 1.0);
            double dx = px - this.axis.x * t;
            double dy = py - this.axis.y * t;
            double dz = pz - this.axis.z * t;
            return dx * dx + dy * dy + dz * dz <= this.radiusSq;
        }

        /** @return the capsule radius, in blocks. */
        public double radius() {
            return this.radius;
        }
    }

    /** @see #box */
    final class Box implements CarveVolume {

        private final BoundingBox box;

        Box(BoundingBox box) {
            this.box = box;
        }

        @Override
        public BoundingBox bounds() {
            return this.box;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return this.box.isInside(x, y, z);
        }
    }
}
