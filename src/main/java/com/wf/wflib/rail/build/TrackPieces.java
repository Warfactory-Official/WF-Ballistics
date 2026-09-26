package com.wf.wflib.rail.build;

import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Centreline;

import java.util.ArrayList;
import java.util.List;

/**
 * A surveyed centreline cut into the pieces a railway is actually built from.
 *
 * <p>Immersive Railroading builds track as cubic curves between two points with a tangent handle at
 * each end, which is the same thing a surveyed alignment is made of read the other way round. This
 * turns one into the other, and it is deliberately free of any IR type so the geometry - the part that
 * is wrong in ways nobody sees until a locomotive rides it - can be tested without the mod installed.</p>
 *
 * <p>Two rules decide where a piece ends. <b>A piece never spans two elements:</b> a cubic that tried to
 * be half straight and half curve is neither, and the join it hides is exactly where a train derails.
 * And <b>a piece is never longer than {@code maxPiece}</b>, because the machine laying them wants to put
 * one down every few seconds rather than one at the end of a kilometre.</p>
 *
 * <p>The handle length is the classic circular approximation, {@code R * 4/3 * tan(theta/4)}, which is
 * the same formula IR uses for its own curves and is accurate to under a hundredth of a block over the
 * angles a piece this short can turn through. A straight is the degenerate case of it and needs no
 * special handling: its handles are simply collinear.</p>
 */
public record TrackPieces(List<Piece> pieces, double length) {

    /** Below this turn, in radians over a whole piece, the piece is a straight. */
    private static final double STRAIGHT = 1.0E-9;

    /**
     * Shortest piece worth laying, in blocks.
     *
     * <p>IR anchors a piece on one parent block in the middle of it and fills the rest with gags, so a
     * piece has to be long enough to have a middle. Anything shorter is dropped rather than built: the
     * track either side of it covers the same ground.</p>
     */
    private static final double MIN_PIECE = 1.0;

    /**
     * Closest two forced joins may be, in blocks.
     *
     * <p>Not tidiness, and not the same number as {@link #MIN_PIECE}. A piece's anchor block sits at its
     * midpoint, so a piece shorter than this has its anchor inside its neighbour's sleepers, and two
     * anchors that end up in one block is the one case IR cannot resolve: it refuses to place the
     * second, and the piece is simply not built. Two junctions four blocks apart is not a railway anyone
     * can build anyway; this is where that stops being our problem to draw.</p>
     */
    private static final double MIN_BREAK_SPAN = 8.0;

    public TrackPieces {
        pieces = List.copyOf(pieces);
    }

    /**
     * One buildable piece of track.
     *
     * @param from where this piece starts along the route, in blocks
     * @param heading1 heading at the near end, radians from +X toward +Z, as the alignment measures it
     * @param c1x the near Bezier handle, in world coordinates
     */
    public record Piece(double from, double to, double x1, double z1, double x2, double z2,
                        double heading1, double heading2,
                        double c1x, double c1z, double c2x, double c2z) {

        public double length() {
            return this.to - this.from;
        }

        /**
         * IR's yaw for a heading: degrees, zero along +Z, increasing toward +X.
         *
         * <p>Not Minecraft's, which turns the other way, and not the alignment's, which is radians from
         * +X. Three conventions in one call chain is two too many, so the conversion lives here and
         * nowhere else.</p>
         */
        public static float yawOf(double heading) {
            double degrees = 90.0 - Math.toDegrees(heading);
            return (float) ((degrees % 360.0 + 360.0) % 360.0);
        }

        public float nearYaw() {
            return yawOf(this.heading1);
        }

        /**
         * @return the far end's yaw, pointing back down the piece.
         *
         * <p>Which is what IR wants there: its far handle is measured from the far point back towards
         * the curve, so the yaw that describes it is the reverse of the direction of travel.</p>
         */
        public float farYaw() {
            return (yawOf(this.heading2) + 180.0f) % 360.0f;
        }

        /** @return whether the two ends are parallel, which is the only thing "straight" means here. */
        public boolean straight() {
            return Math.abs(turn(this.heading1, this.heading2)) < 1.0E-6;
        }
    }

    public boolean isEmpty() {
        return this.pieces.isEmpty();
    }

    /**
     * Cut a centreline into pieces of at most {@code maxPiece} blocks.
     *
     * <p>{@code margin} blocks are left bare at each end. That is not tidiness: a bored tunnel is
     * sealed with a wall of lining at each portal, and the block the route's first point sits in is
     * as likely as not to be part of it. Track laid into that block asks to replace the end wall,
     * which a track layer that respects the world will refuse, and the line then never starts.</p>
     */
    public static TrackPieces along(Centreline centreline, double maxPiece, double margin) {
        return along(centreline, maxPiece, margin, List.of());
    }

    /**
     * Cut a centreline into pieces, with a join forced at each of {@code breaks}.
     *
     * <p>A break is a chainage where something else meets this route: another line crossing it, or a
     * branch leaving it. Putting a piece join exactly there is what makes the meeting buildable at all.
     * Immersive Railroading anchors each piece on one parent rail block in the middle of it and fills
     * the rest with sleepers, and a block can hold any number of sleepers: IR keeps every path it finds
     * there and offers a train all of them. Splitting at the meeting is what makes the join land where
     * the two routes actually touch, so each piece runs tangent through it instead of across it.</p>
     */
    public static TrackPieces along(Centreline centreline, double maxPiece, double margin,
                                    List<Double> breaks) {
        return along(centreline, maxPiece, margin, margin, breaks);
    }

    /**
     * As above, with a different amount left bare at each end.
     *
     * <p>The two ends of a route are not alike. One may run into a sealed portal and the other into a
     * turnout, and a turnout is the case that needs a real margin: the switch <em>is</em> the first
     * dozen blocks of the branch, carrying both the straight and the diverging road, and a branch that
     * laid its own track over that stretch would put its anchor block inside the switch and IR would
     * delete one of the two without saying which.</p>
     */
    public static TrackPieces along(Centreline centreline, double maxPiece, double startMargin,
                                    double endMargin, List<Double> breaks) {
        List<Piece> out = new ArrayList<>();
        double limit = Math.max(1.0, maxPiece);
        double total = centreline.length();
        double first = Math.max(0.0, startMargin);
        double last = Math.max(first, total - Math.max(0.0, endMargin));
        double start = 0.0;
        for (AlignElement element : centreline.elements()) {
            double length = element.length();
            if (length <= 0.0) {
                continue;
            }
            // This element's own share of the part of the route that gets track.
            double from = Math.max(first, start) - start;
            double to = Math.min(last, start + length) - start;
            for (double[] span : split(from, to, breaks, start)) {
                double a0 = span[0];
                double b0 = span[1];
                int parts = Math.max(1, (int) Math.ceil((b0 - a0) / limit));
                for (int i = 0; i < parts; i++) {
                    double a = a0 + (b0 - a0) * i / parts;
                    double b = a0 + (b0 - a0) * (i + 1) / parts;
                    out.add(piece(element, start + a, a, b));
                }
            }
            start += length;
        }
        return new TrackPieces(out, start);
    }

    /** As above, with track run right to the ends. */
    public static TrackPieces along(Centreline centreline, double maxPiece) {
        return along(centreline, maxPiece, 0.0);
    }

    /**
     * One span of an element, cut at whichever breaks fall inside it.
     *
     * <p>A break within {@link #MIN_BREAK_SPAN} of an end of the span is dropped rather than honoured.
     * A join that close leaves a piece whose anchor block is inside its neighbour's sleepers, and two
     * anchors in one block is the one stacking case IR will not do.</p>
     */
    private static List<double[]> split(double from, double to, List<Double> breaks, double offset) {
        List<double[]> out = new ArrayList<>();
        if (to - from <= MIN_PIECE) {
            return out;
        }
        double at = from;
        for (double global : breaks) {
            double local = global - offset;
            if (local - at > MIN_BREAK_SPAN && to - local > MIN_BREAK_SPAN) {
                out.add(new double[]{at, local});
                at = local;
            }
        }
        out.add(new double[]{at, to});
        return out;
    }

    /**
     * Re-cut one span of a centreline as a single piece.
     *
     * <p>What a layer uses when a piece was refused and a shorter one might not be: a portal wall, or
     * another railway, standing in the last block of a route costs that block rather than the whole
     * line. The span is clamped into the element its start falls in, because a piece that spanned two
     * elements would be half straight and half curve and neither.</p>
     *
     * @return the piece, or null when there is nothing left of the span to build
     */
    public static Piece cut(Centreline centreline, double from, double to) {
        List<AlignElement> elements = centreline.elements();
        double start = 0.0;
        AlignElement holding = null;
        double holdingStart = 0.0;
        for (AlignElement element : elements) {
            double length = element.length();
            if (length <= 0.0) {
                continue;
            }
            holding = element;
            holdingStart = start;
            if (from < start + length - 1.0E-9) {
                break;
            }
            start += length;
        }
        if (holding == null) {
            return null;
        }
        double a = Math.max(0.0, from - holdingStart);
        double b = Math.min(holding.length(), to - holdingStart);
        return b - a <= MIN_PIECE ? null : piece(holding, holdingStart + a, a, b);
    }

    private static Piece piece(AlignElement element, double chainage, double from, double to) {
        AlignElement.Sample a = element.at(from);
        AlignElement.Sample b = element.at(to);
        double run = to - from;
        double turn = turn(a.heading(), b.heading());
        double handle;
        if (Math.abs(turn) < STRAIGHT) {
            // A straight's handles only have to be collinear; a third of the way along is what a cubic
            // with evenly spaced handles looks like, and it keeps the arc-length parameter honest.
            handle = run / 3.0;
        } else {
            double radius = run / Math.abs(turn);
            handle = radius * 4.0 / 3.0 * Math.tan(Math.abs(turn) / 4.0);
        }
        return new Piece(chainage, chainage + run, a.x(), a.z(), b.x(), b.z(), a.heading(), b.heading(),
                a.x() + Math.cos(a.heading()) * handle, a.z() + Math.sin(a.heading()) * handle,
                b.x() - Math.cos(b.heading()) * handle, b.z() - Math.sin(b.heading()) * handle);
    }

    /** The signed turn from one heading to another, in radians, taking the short way round. */
    public static double turn(double from, double to) {
        double delta = (to - from) % (Math.PI * 2.0);
        if (delta > Math.PI) {
            delta -= Math.PI * 2.0;
        } else if (delta < -Math.PI) {
            delta += Math.PI * 2.0;
        }
        return delta;
    }
}
