package com.wf.wflib.rail.align;

/**
 * One piece of compiled centreline: a straight, or a circular arc.
 *
 * <p>These two are all a horizontal alignment is. They map onto IR's {@code STRAIGHT} and
 * {@code TURN_V2} one for one, which is the point: the compiler emits geometry IR can already build
 * rather than geometry we would then have to approximate.</p>
 *
 * <p>Angles are headings in radians, measured from +X toward +Z, which is how {@link Math#atan2} with
 * {@code (dz, dx)} reports them. Keeping one convention everywhere is worth more than picking the
 * prettiest one.</p>
 */
public sealed interface AlignElement {

    /** Length along this element, in blocks. */
    double length();

    /** Position and heading at {@code s} blocks from this element's start. */
    Sample at(double s);

    /**
     * The stretch between two distances along this element, as an element in its own right.
     *
     * <p>Exact rather than a re-fit: a piece of a straight is a straight between the two points, and a
     * piece of an arc is the same arc over a shorter sweep. A route built in legs is cut here, so an
     * approximation would put a kink at every junction.</p>
     */
    AlignElement part(double from, double to);

    /** The same ground, measured from the other end and headed the other way. */
    AlignElement reversed();

    /** Position and heading somewhere on the centreline. */
    record Sample(double x, double z, double heading) {
    }

    /** A straight run. */
    record Tangent(double x1, double z1, double x2, double z2) implements AlignElement {

        @Override
        public double length() {
            double dx = this.x2 - this.x1;
            double dz = this.z2 - this.z1;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public double heading() {
            return Math.atan2(this.z2 - this.z1, this.x2 - this.x1);
        }

        @Override
        public Sample at(double s) {
            double len = length();
            double t = len <= 0.0 ? 0.0 : s / len;
            return new Sample(this.x1 + (this.x2 - this.x1) * t, this.z1 + (this.z2 - this.z1) * t, heading());
        }

        @Override
        public AlignElement part(double from, double to) {
            Sample a = at(from);
            Sample b = at(to);
            return new Tangent(a.x(), a.z(), b.x(), b.z());
        }

        @Override
        public AlignElement reversed() {
            return new Tangent(this.x2, this.z2, this.x1, this.z1);
        }
    }

    /**
     * A circular arc.
     *
     * @param cx centre X
     * @param cz centre Z
     * @param radius arc radius in blocks, always positive
     * @param startAngle angle of the start point as seen from the centre
     * @param sweep signed angular extent; positive turns toward +Z, negative toward -Z
     */
    record Curve(double cx, double cz, double radius, double startAngle, double sweep) implements AlignElement {

        @Override
        public double length() {
            return Math.abs(this.sweep) * this.radius;
        }

        @Override
        public Sample at(double s) {
            double travelled = this.radius <= 0.0 ? 0.0 : s / this.radius;
            double angle = this.startAngle + Math.signum(this.sweep) * travelled;
            // The tangent runs a quarter turn ahead of the radius, on whichever side the arc turns.
            double heading = angle + Math.signum(this.sweep) * (Math.PI / 2.0);
            return new Sample(this.cx + Math.cos(angle) * this.radius,
                    this.cz + Math.sin(angle) * this.radius, heading);
        }

        @Override
        public AlignElement part(double from, double to) {
            if (this.radius <= 0.0) {
                return this;
            }
            double sign = Math.signum(this.sweep);
            return new Curve(this.cx, this.cz, this.radius,
                    this.startAngle + sign * from / this.radius,
                    sign * (to - from) / this.radius);
        }

        /**
         * @return the same arc, swept the other way.
         *
         * <p>Starting where it ended and sweeping back, which is a different thing from negating the
         * start angle: the centre and the radius are the curve, and only the direction of travel
         * round it changes.</p>
         */
        @Override
        public AlignElement reversed() {
            return new Curve(this.cx, this.cz, this.radius, this.startAngle + this.sweep, -this.sweep);
        }

        /** Extent of the turn, unsigned, in radians. This is the deflection the curve absorbs. */
        public double deflection() {
            return Math.abs(this.sweep);
        }
    }
}
