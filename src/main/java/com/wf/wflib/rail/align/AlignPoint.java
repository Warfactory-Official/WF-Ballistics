package com.wf.wflib.rail.align;

/**
 * One point of intersection: where two straights would meet if the line did not turn, and the radius of
 * the curve that cuts the corner.
 *
 * <p>This, and not a control point, is what a player edits. A PI has a position they chose and a radius
 * with a consequence they understand, and moving one only disturbs the two curves either side of it.
 * A chain of Bezier handles has neither property.</p>
 *
 * <p>Horizontal only. The vertical profile is a separate list against chainage, because the two are
 * edited in different views and a PI in plan has no business carrying a grade.</p>
 *
 * @param x world X of the intersection
 * @param z world Z of the intersection
 * @param radius curve radius in blocks; ignored at the first and last PI, which cannot turn
 */
public record AlignPoint(double x, double z, double radius) {

    public AlignPoint withPos(double x, double z) {
        return new AlignPoint(x, z, this.radius);
    }

    public AlignPoint withRadius(double radius) {
        return new AlignPoint(this.x, this.z, radius);
    }

    public double distanceTo(AlignPoint other) {
        double dx = other.x - this.x;
        double dz = other.z - this.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
