package com.wf.wflib.rail.build;

import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;

/**
 * The connecting curve between two routes that join end to end.
 *
 * <p>Two surveyed routes ending at the same point do not make one railway. Where they meet at an angle
 * the rails <b>kink</b>, and a kink is not something a train can be run through: Immersive Railroading
 * asks each piece of track under a train for the point on it nearest to where the train is going, so it
 * always prefers to carry straight on, and at a kink carrying straight on leaves both railways.</p>
 *
 * <p>So the junction is a curve, and it is specified the way a real turnout is: <b>the lead is the
 * number you choose and the radius is what it buys you.</b> The curve runs from {@code lead} blocks
 * back along one route to {@code lead} blocks along the other, tangent to both, and takes the whole of
 * the deflection between them at {@code lead / tan(deflection / 2)}. Two routes that are already in
 * line give an infinite radius, which is a straight, which is right.</p>
 *
 * <p>Geometry only, with no world and no IR in it, because this is the half that is wrong in ways
 * nobody sees until a locomotive rides it.</p>
 */
public record JunctionCurve(double x1, double z1, double heading1, double x2, double z2,
                            double heading2, double deflection, double radius, double handle) {

    /** Below this turn, in radians, the two routes are in line and the connection is a straight. */
    private static final double STRAIGHT = 1.0E-6;

    /**
     * Work out the curve that joins two routes at a meeting.
     *
     * @param a the centreline of the meeting's first route, b the second's
     * @param lead how far back along each route the curve starts, in blocks
     * @return the curve, or null when either route is too short to give the junction its lead
     */
    public static JunctionCurve between(Centreline a, Centreline b, RouteMeeting meeting, double lead) {
        double reach = Math.max(1.0, lead);
        if (a.length() <= reach * 2.0 || b.length() <= reach * 2.0) {
            return null;
        }
        // A route that finishes at the junction is walked the way it was surveyed and one that starts
        // there is walked backwards, so one of the two headings has to be turned round for both of them
        // to mean "the way a train travels through this junction".
        double[] in = approach(a, meeting.a(), reach);
        double[] out = approach(b, meeting.b(), reach);
        double toward = in[2] + Math.PI;
        double away = out[2];
        double turn = TrackPieces.turn(toward, away);
        boolean straight = Math.abs(turn) < STRAIGHT;
        double radius = straight ? Double.POSITIVE_INFINITY : reach / Math.tan(Math.abs(turn) / 2.0);
        double handle = straight
                ? Math.hypot(out[0] - in[0], out[1] - in[1]) / 3.0
                : radius * 4.0 / 3.0 * Math.tan(Math.abs(turn) / 4.0);
        return new JunctionCurve(in[0], in[1], toward, out[0], out[1], away,
                Math.toDegrees(turn), radius, handle);
    }

    /** @return x, z, and the direction of travel <em>away</em> from the junction along this route. */
    private static double[] approach(Centreline line, RouteMeeting.Side side, double reach) {
        boolean atFinish = side.end() == RouteMeeting.End.FINISH;
        AlignElement.Sample at = line.at(atFinish ? line.length() - reach : reach);
        return new double[]{at.x(), at.z(), atFinish ? at.heading() + Math.PI : at.heading()};
    }

    public boolean straight() {
        return Double.isInfinite(this.radius);
    }

    /** The near tangent handle, in world coordinates: IR measures it from the near point forwards. */
    public double c1x() {
        return this.x1 + Math.cos(this.heading1) * this.handle;
    }

    public double c1z() {
        return this.z1 + Math.sin(this.heading1) * this.handle;
    }

    /** The far tangent handle: IR measures it from the far point back down the curve. */
    public double c2x() {
        return this.x2 - Math.cos(this.heading2) * this.handle;
    }

    public double c2z() {
        return this.z2 - Math.sin(this.heading2) * this.handle;
    }

    public double length() {
        return Math.hypot(this.x2 - this.x1, this.z2 - this.z1);
    }
}
