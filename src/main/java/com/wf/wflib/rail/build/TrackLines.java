package com.wf.wflib.rail.build;

import com.mojang.logging.LogUtils;
import com.wf.wflib.rail.RailCompat;
import com.wf.wflib.rail.RailConfig;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.excavate.CarveVolume;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Picks the railway a world can actually have.
 *
 * <p>Immersive Railroading if it is installed, vanilla rail if it is not, decided once when a machine
 * starts rather than per piece. This is the only class that names {@link com.wf.wflib.rail.build.ir.IrTrackLine}, which is what
 * keeps every IR type out of the rest of the package: a class is linked when it is first used, so an
 * install with no IR never reaches the branch that would need it.</p>
 */
public final class TrackLines {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Longest piece of IR track laid in one go, in blocks.
     *
     * <p>Short enough that a machine puts one down every few seconds rather than one at the end of the
     * tunnel, and short enough that the cubic approximating an arc is indistinguishable from it. IR
     * will happily build a far longer piece; watching it appear is the reason not to.</p>
     */
    public static final double MAX_PIECE = 24.0;

    private TrackLines() {
    }

    /**
     * @param routeId which route this is, so a meeting can say which half of it is this one's
     * @param path the corridor the tunnel follows, which is what the caller measures progress along
     * @param centreline the surveyed curve itself, which is what the track is built on
     * @param inside the tunnel the track runs in, which is the only ground a layer may clear, or null
     * @param meetings where other routes cross or join this one, which decides where the joins go
     */
    public static TrackLine on(ServerLevel level, UUID routeId, Centreline centreline,
                               CarveVolume.Corridor path, int floorY, BlockState bed, int boostSpacing,
                               CarveVolume inside, List<RouteMeeting> meetings) {
        if (RailCompat.trackGraphAvailable()) {
            try {
                // Track runs to both ends of the route. Where a portal wall or somebody else's railway
                // will not let it, the end piece is laid a little shorter instead, which costs half a
                // block rather than a fixed margin left off every route whether it needs one or not.
                List<Double> breaks = RouteMeetings.breaksOn(meetings, routeId);
                double lead = RailConfig.TURNOUT_LEAD.get();
                return new com.wf.wflib.rail.build.ir.IrTrackLine(centreline,
                        TrackPieces.along(centreline, MAX_PIECE,
                                leftToTheJunction(meetings, routeId, RouteMeeting.End.START, lead),
                                leftToTheJunction(meetings, routeId, RouteMeeting.End.FINISH, lead),
                                breaks), floorY,
                        path.length(), RailConfig.IR_TRACK.get(), RailConfig.IR_GAUGE.get(), inside,
                        meetings, routeId);
            } catch (Throwable error) {
                // A half-present IR, or one whose API has moved. Saying so once and laying something is
                // better than a machine that stops with a linkage error nobody reads.
                LOGGER.error("[wflib] could not build Immersive Railroading track; falling back to"
                        + " vanilla rail", error);
            }
        }
        return new VanillaTrackLine(RailPath.along(path), floorY, bed, boostSpacing);
    }

    /**
     * How much of one end of this route belongs to a junction rather than to the route.
     *
     * <p>Zero unless another railway meets this one <em>at that end</em>. Where one does, the first or
     * last stretch of this route is the junction itself: one piece of track, built by
     * {@code /wfrail junctions build}, which carries the whole of the connection.</p>
     *
     * <p>Two meetings need it and for the same reason. A <b>turnout</b> is a switch carrying both the
     * straight and the diverging road, and a branch that laid its own track over that stretch would
     * anchor a second piece inside the switch, which IR resolves by deleting one of them without saying
     * which. A <b>joint</b> is a connecting curve taking the deflection between two routes that would
     * otherwise meet in a kink, and a kink is not a railway: IR's pathing asks each piece under a train
     * for the point nearest where the train is going, so it always prefers to carry straight on, and at
     * a kink carrying straight on leaves both railways. See {@link com.wf.wflib.rail.build.ir.IrJunction}.</p>
     *
     * <p>Which means a pair of routes that join is not finished until the junction is built, exactly as
     * a branch is not. The tunnel runs through either way: only the rails stop short.</p>
     */
    private static double leftToTheJunction(List<RouteMeeting> meetings, UUID routeId,
                                            RouteMeeting.End end, double lead) {
        for (RouteMeeting meeting : meetings) {
            RouteMeeting.Side mine = meeting.sideOf(routeId);
            if (mine == null || mine.end() != end) {
                continue;
            }
            if (meeting.kind() == RouteMeeting.Kind.JOINT
                    || (meeting.kind() == RouteMeeting.Kind.TURNOUT && meeting.branch() == mine)) {
                return lead;
            }
        }
        return 0.0;
    }
}
