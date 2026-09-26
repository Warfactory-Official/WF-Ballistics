package com.wf.wflib.rail.build;

import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Centreline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve fitting that decides whether built track is the line that was surveyed.
 *
 * <p>This is the half of the IR track layer that can be tested without IR installed, and it is the half
 * worth testing: a cubic whose handles are a little wrong still builds, still looks like track from any
 * distance a person stands at, and puts the rails a foot off the surveyed centreline all the way round
 * the curve. Nothing in the game reports that. The arithmetic here does.</p>
 */
class TrackPiecesTest {

    private static final double MAX_PIECE = 24.0;

    /** A quarter circle of radius 100, turning from heading +X towards +Z. */
    private static Centreline arc() {
        AlignElement.Curve curve = new AlignElement.Curve(0.0, 100.0, 100.0, -Math.PI / 2.0,
                Math.PI / 2.0);
        return new Centreline(List.of(curve), curve.length());
    }

    private static Centreline straight() {
        AlignElement.Tangent tangent = new AlignElement.Tangent(10.0, 20.0, 110.0, 20.0);
        return new Centreline(List.of(tangent), tangent.length());
    }

    /** A cubic Bezier at its midpoint, which is where it departs furthest from the arc it approximates. */
    private static double[] midpoint(TrackPieces.Piece piece) {
        return new double[]{
                (piece.x1() + 3.0 * piece.c1x() + 3.0 * piece.c2x() + piece.x2()) / 8.0,
                (piece.z1() + 3.0 * piece.c1z() + 3.0 * piece.c2z() + piece.z2()) / 8.0};
    }

    @Test
    @DisplayName("every piece of a curve follows the arc to within a hundredth of a block")
    void curveIsAccurate() {
        Centreline line = arc();
        TrackPieces pieces = TrackPieces.along(line, MAX_PIECE);
        assertTrue(pieces.pieces().size() >= 7, "157 blocks at 24 a piece: " + pieces.pieces().size());
        for (TrackPieces.Piece piece : pieces.pieces()) {
            double[] mid = midpoint(piece);
            AlignElement.Sample truth = line.at((piece.from() + piece.to()) / 2.0);
            double error = Math.hypot(mid[0] - truth.x(), mid[1] - truth.z());
            assertTrue(error < 0.01, "piece at " + piece.from() + " is " + error
                    + " blocks off the surveyed line at its midpoint");
        }
    }

    @Test
    @DisplayName("pieces join end to end, in position and in heading")
    void piecesJoin() {
        for (Centreline line : List.of(arc(), straight())) {
            List<TrackPieces.Piece> pieces = TrackPieces.along(line, MAX_PIECE).pieces();
            for (int i = 0; i < pieces.size() - 1; i++) {
                TrackPieces.Piece here = pieces.get(i);
                TrackPieces.Piece next = pieces.get(i + 1);
                assertEquals(here.to(), next.from(), 1.0E-9, "a gap in the chainage at piece " + i);
                assertEquals(here.x2(), next.x1(), 1.0E-9, "piece " + i + " does not end where the"
                        + " next one starts");
                assertEquals(here.z2(), next.z1(), 1.0E-9, "piece " + i + " does not end where the"
                        + " next one starts");
                assertEquals(here.heading2(), next.heading1(), 1.0E-9,
                        "piece " + i + " hands over a kink rather than a heading");
            }
        }
    }

    @Test
    @DisplayName("a straight's handles are collinear with it, so the cubic is the straight")
    void straightIsStraight() {
        for (TrackPieces.Piece piece : TrackPieces.along(straight(), MAX_PIECE).pieces()) {
            assertTrue(piece.straight(), "a tangent should produce straight pieces");
            assertEquals(piece.z1(), piece.c1z(), 1.0E-9);
            assertEquals(piece.z2(), piece.c2z(), 1.0E-9);
            double[] mid = midpoint(piece);
            assertEquals(20.0, mid[1], 1.0E-9, "the cubic wanders off a straight line");
        }
    }

    @Test
    @DisplayName("no piece is longer than it was asked to be, and none spans two elements")
    void pieceLength() {
        AlignElement.Tangent lead = new AlignElement.Tangent(0.0, 0.0, 50.0, 0.0);
        AlignElement.Curve turn = new AlignElement.Curve(50.0, 100.0, 100.0, -Math.PI / 2.0,
                Math.PI / 2.0);
        Centreline line = new Centreline(List.of(lead, turn), lead.length() + turn.length());
        List<TrackPieces.Piece> pieces = TrackPieces.along(line, MAX_PIECE).pieces();
        for (TrackPieces.Piece piece : pieces) {
            assertTrue(piece.length() <= MAX_PIECE + 1.0E-9,
                    "a piece of " + piece.length() + " blocks");
        }
        // The straight ends at 50 blocks, so some piece has to end exactly there.
        assertTrue(pieces.stream().anyMatch(p -> Math.abs(p.to() - 50.0) < 1.0E-9),
                "no piece ends at the element boundary, so one of them spans both");
        long straights = pieces.stream().filter(TrackPieces.Piece::straight).count();
        assertTrue(straights >= 2 && straights < pieces.size(),
                "a straight into a curve should produce both kinds: " + straights + " straight of "
                        + pieces.size());
    }

    @Test
    @DisplayName("headings convert to IR's yaw, which turns the other way from Minecraft's")
    void yawConvention() {
        // Alignment headings are radians from +X toward +Z. IR yaw is degrees from +Z toward +X.
        assertEquals(90.0f, TrackPieces.Piece.yawOf(0.0), 1.0E-4, "+X is IR yaw 90");
        assertEquals(0.0f, TrackPieces.Piece.yawOf(Math.PI / 2.0), 1.0E-4, "+Z is IR yaw 0");
        assertEquals(270.0f, TrackPieces.Piece.yawOf(Math.PI), 1.0E-4, "-X is IR yaw 270");
        assertEquals(180.0f, TrackPieces.Piece.yawOf(-Math.PI / 2.0), 1.0E-4, "-Z is IR yaw 180");
    }

    @Test
    @DisplayName("the far end's yaw points back down the piece, which is where IR measures its handle from")
    void farYawPointsBack() {
        TrackPieces.Piece piece = TrackPieces.along(straight(), MAX_PIECE).pieces().get(0);
        assertEquals(90.0f, piece.nearYaw(), 1.0E-4);
        assertEquals(270.0f, piece.farYaw(), 1.0E-4);
    }

    @Test
    @DisplayName("a break puts a join exactly on it, so a meeting falls between pieces and not across one")
    void breaksBecomeJoins() {
        Centreline line = straight();
        TrackPieces pieces = TrackPieces.along(line, MAX_PIECE, 0.0, List.of(37.0));
        boolean joined = false;
        for (TrackPieces.Piece piece : pieces.pieces()) {
            if (Math.abs(piece.from() - 37.0) < 1.0E-6) {
                joined = true;
            }
            assertTrue(piece.from() >= 37.0 - 1.0E-6 || piece.to() <= 37.0 + 1.0E-6,
                    "no piece may straddle the break: " + piece.from() + " to " + piece.to());
        }
        assertTrue(joined, "and one of them starts on it");
    }

    @Test
    @DisplayName("several breaks are honoured at once, and none of them lengthens a piece")
    void severalBreaks() {
        Centreline line = straight();
        TrackPieces pieces = TrackPieces.along(line, MAX_PIECE, 0.0, List.of(15.0, 37.0, 80.0));
        List<Double> joins = pieces.pieces().stream().map(TrackPieces.Piece::from).toList();
        for (double each : List.of(15.0, 37.0, 80.0)) {
            assertTrue(joins.stream().anyMatch(at -> Math.abs(at - each) < 1.0E-6),
                    "no join at " + each + "; joins are " + joins);
        }
        for (TrackPieces.Piece piece : pieces.pieces()) {
            assertTrue(piece.length() <= MAX_PIECE + 1.0E-6,
                    "a break must never make a piece longer: " + piece.length());
        }
    }

    @Test
    @DisplayName("a break on top of a piece join, or off the end of the line, changes nothing")
    void uselessBreaksAreIgnored() {
        Centreline line = straight();
        int plain = TrackPieces.along(line, MAX_PIECE).pieces().size();
        assertEquals(plain, TrackPieces.along(line, MAX_PIECE, 0.0, List.of(-5.0, 1000.0)).pieces().size(),
                "a break nowhere near the line is not a join");
        assertEquals(plain, TrackPieces.along(line, MAX_PIECE, 0.0, List.of(0.0, line.length()))
                        .pieces().size(),
                "and a break on an end is the end, which is already a join");
    }

    @Test
    @DisplayName("a span re-cut short is the same curve, only shorter")
    void cutTrimsAPiece() {
        Centreline line = arc();
        TrackPieces.Piece full = TrackPieces.along(line, MAX_PIECE).pieces().get(0);
        TrackPieces.Piece shorter = TrackPieces.cut(line, full.from() + 0.5, full.to());
        assertEquals(full.to(), shorter.to(), 1.0E-9, "the far end does not move");
        assertEquals(full.length() - 0.5, shorter.length(), 1.0E-9);

        AlignElement.Sample truth = line.at(shorter.from());
        assertEquals(truth.x(), shorter.x1(), 1.0E-9, "and its near end is still on the surveyed line");
        assertEquals(truth.z(), shorter.z1(), 1.0E-9);

        double[] mid = midpoint(shorter);
        AlignElement.Sample middle = line.at((shorter.from() + shorter.to()) / 2.0);
        assertTrue(Math.hypot(mid[0] - middle.x(), mid[1] - middle.z()) < 0.01,
                "a trimmed piece is still the arc it was cut from");
    }

    @Test
    @DisplayName("trimming a piece away to nothing gives nothing, rather than a piece of no length")
    void cutRefusesASliver() {
        Centreline line = straight();
        assertEquals(null, TrackPieces.cut(line, 40.0, 40.2),
                "IR needs a piece long enough to have a middle to anchor on");
    }
}
