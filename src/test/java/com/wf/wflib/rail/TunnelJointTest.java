package com.wf.wflib.rail;

import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where one route ends and the next one begins.
 *
 * <p>A stop on a network is two routes that meet end to end, and every route end is a sealed face,
 * because a bore that stops inside an aquifer has to be. Those two rules are in direct contradiction at
 * a junction and the contradiction is <b>invisible</b>: each route is dug correctly right up to the
 * point they share, and one of them lays a wall a block into the other's finished tunnel. Nothing
 * refuses, both coverage checks pass to within one sample, and trains stop at every stop.</p>
 *
 * <p>So it is asserted here, on the geometry alone, with no world: a route end that another route's end
 * meets is not a face.</p>
 */
class TunnelJointTest {

    private static final TunnelProfile BOX = TunnelProfile.parse("box", List.of(
            "########",
            "#......#",
            "#......#",
            "#......#",
            "#......#",
            "########"));

    private static final UUID FIRST = UUID.nameUUIDFromBytes("first".getBytes());
    private static final UUID SECOND = UUID.nameUUIDFromBytes("second".getBytes());

    /** Where the two routes meet: the end of the first and the start of the second. */
    private static final double JOIN_X = 60.0;

    /** A straight run in along +X, finishing at the junction. */
    private static CarveVolume.Corridor arriving() {
        double[] xs = {0.0, 20.0, 40.0, JOIN_X};
        double[] zs = {0.0, 0.0, 0.0, 0.0};
        return CarveVolume.corridor(xs, zs, BOX.width(), 0, BOX.boreHeight());
    }

    /** A straight run out of the junction, turned a little, so the two share a point and not a line. */
    private static CarveVolume.Corridor leaving(double degrees) {
        double radians = Math.toRadians(degrees);
        double[] xs = new double[4];
        double[] zs = new double[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = JOIN_X + Math.cos(radians) * i * 20.0;
            zs[i] = Math.sin(radians) * i * 20.0;
        }
        return CarveVolume.corridor(xs, zs, BOX.width(), 0, BOX.boreHeight());
    }

    private static CarveVolume wall(ProfileVolume.End atFinish) {
        return new ProfileVolume(arriving(), BOX, TunnelProfile.Kind.LINING, 0,
                ProfileVolume.End.CAP, atFinish);
    }

    @Test
    @DisplayName("a capped end stands a wall in the block the two routes share")
    void cappedEndPlugsTheJunction() {
        // This is the defect, asserted rather than described. The wall is one block at the height a
        // train runs at, in the one block both routes have to have, and neither route can see it: each
        // of them is dug correctly right up to the point they share.
        assertTrue(wall(ProfileVolume.End.CAP).contains((int) JOIN_X, 0, 0),
                "a capped face should wall the shared block, or this test is not testing what it says");
        assertFalse(wall(ProfileVolume.End.OPEN).contains((int) JOIN_X, 0, 0),
                "an open end walled the block the next route starts in");
    }

    @Test
    @DisplayName("an open end moves its wall out of the junction rather than dropping it")
    void openEndKeepsItsWall() {
        // Not the same thing as having no face. Until the railway it joins is dug there is nothing on
        // the other side but rock, and the reason a route's face is walled at all is that the rock is
        // sometimes water. The wall stands one block further on, where the other route's bore takes it
        // out when it arrives.
        assertTrue(wall(ProfileVolume.End.OPEN).contains((int) JOIN_X + 1, 0, 0),
                "an open end left its face unwalled");
    }

    @Test
    @DisplayName("an open end carries the tunnel into the junction rather than stopping short of it")
    void openEndReachesTheSharedBlock() {
        CarveVolume flush = new ProfileVolume(arriving(), BOX, TunnelProfile.Kind.BORE, 0,
                ProfileVolume.End.CAP, ProfileVolume.End.FLUSH);
        CarveVolume open = new ProfileVolume(arriving(), BOX, TunnelProfile.Kind.BORE, 0,
                ProfileVolume.End.CAP, ProfileVolume.End.OPEN);
        // This is what actually leaves rock in a junction. The block a shared endpoint falls in is
        // claimed by whichever centreline reaches into it, and two routes that both stop flush reach
        // into it from neither side: the two tunnels then abut on a block that nobody ever cut.
        int shared = (int) JOIN_X;
        assertFalse(flush.contains(shared, 0, 0),
                "a flush end stops before the block its own endpoint falls in");
        assertTrue(open.contains(shared, 0, 0),
                "an open end should cut the block it shares with the railway it joins");
    }

    @Test
    @DisplayName("an open end is still a sealed tube")
    void openEndStillSeals() {
        CarveVolume bore = new ProfileVolume(arriving(), BOX, TunnelProfile.Kind.BORE, 0,
                ProfileVolume.End.CAP, ProfileVolume.End.OPEN);
        CarveVolume shell = new ProfileVolume(arriving(), BOX, TunnelProfile.Kind.LINING, 0,
                ProfileVolume.End.CAP, ProfileVolume.End.OPEN);
        var box = shell.bounds();
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!bore.contains(x, y, z)) {
                        continue;
                    }
                    for (int[] face : faces) {
                        int nx = x + face[0];
                        int ny = y + face[1];
                        int nz = z + face[2];
                        assertTrue(bore.contains(nx, ny, nz) || shell.contains(nx, ny, nz),
                                "the tunnel leaks at " + nx + ", " + ny + ", " + nz);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a junction opens an end and a crossing does not")
    void onlyTerminalMeetingsOpenAnEnd() {
        List<RouteMeeting> joint = List.of(meeting(RouteMeeting.Kind.JOINT,
                RouteMeeting.End.FINISH, RouteMeeting.End.START));
        assertEquals(ProfileVolume.End.OPEN,
                TunnelBuilder.portalAt(joint, FIRST, RouteMeeting.End.FINISH));
        assertEquals(ProfileVolume.End.CAP,
                TunnelBuilder.portalAt(joint, FIRST, RouteMeeting.End.START),
                "the far end of the route is still a face");
        assertEquals(ProfileVolume.End.OPEN,
                TunnelBuilder.portalAt(joint, SECOND, RouteMeeting.End.START));

        List<RouteMeeting> turnout = List.of(meeting(RouteMeeting.Kind.TURNOUT,
                RouteMeeting.End.NONE, RouteMeeting.End.START));
        assertEquals(ProfileVolume.End.CAP,
                TunnelBuilder.portalAt(turnout, FIRST, RouteMeeting.End.FINISH),
                "the route a branch leaves does not end at the junction");
        assertEquals(ProfileVolume.End.OPEN,
                TunnelBuilder.portalAt(turnout, SECOND, RouteMeeting.End.START),
                "a branch begins inside the tunnel it leaves from, so its own face is that tunnel");

        // A crossing at chainage zero is still a route running on through, and its face is a face.
        List<RouteMeeting> crossing = List.of(meeting(RouteMeeting.Kind.CROSSING,
                RouteMeeting.End.FINISH, RouteMeeting.End.START));
        assertEquals(ProfileVolume.End.CAP,
                TunnelBuilder.portalAt(crossing, FIRST, RouteMeeting.End.FINISH));

        assertEquals(ProfileVolume.End.CAP,
                TunnelBuilder.portalAt(List.of(), FIRST, RouteMeeting.End.FINISH));
        assertEquals(ProfileVolume.End.CAP,
                TunnelBuilder.portalAt(null, FIRST, RouteMeeting.End.FINISH));
    }

    private static RouteMeeting meeting(RouteMeeting.Kind kind, RouteMeeting.End mine,
                                        RouteMeeting.End theirs) {
        return new RouteMeeting(
                new RouteMeeting.Side(FIRST, "first", null, 60.0, 0.0, mine),
                new RouteMeeting.Side(SECOND, "second", null, 0.0, 0.5, theirs),
                kind, JOIN_X, 0.0, 30.0);
    }
}
