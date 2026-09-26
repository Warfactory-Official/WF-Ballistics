package com.wf.wflib.rail;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;


/**
 * The parts of other railways a tunnel is about to be driven straight through.
 *
 * <p>Two tunnels that cross share one chamber, and a machine that did not know that would wreck the
 * line it crossed twice over. Its <b>lining pass</b> would build the second tunnel's side walls right
 * across the first one's bore, sealing it off at both sides of the crossing; and its <b>boring pass</b>
 * would take out the first line's rails, which are simply blocks sitting in ground the second tunnel
 * has been told to empty. Neither shows up as an error. The tunnel finishes, the other railway is cut
 * in half, and the only sign is a coverage check on a route nobody was building.</p>
 *
 * <p>Both are avoided by subtracting: the lining skips what is already another line's tunnel, and the
 * bore skips the one course that other line's rails stand on. What is left is a crossing chamber open
 * to both, which is what a flat crossing is.</p>
 *
 * <p>Only stretches the other route <em>reports track on</em> are exempted, and that restriction is the
 * safety. A crossing that is surveyed but not yet dug is still solid ground, and leaving a six block
 * gap in a tunnel wall on the strength of somebody's plan is how an aquifer gets in.</p>
 *
 * <p>Which is why this is asked again as a machine advances rather than once when it sets off. With one
 * train on the map the two are the same thing: every other route is either finished or untouched before
 * the drive begins. With several out at once they are not, and a snapshot taken at launch says every
 * crossing is solid rock - so whichever machine reaches a crossing second walls the first one's tunnel
 * off across the middle and reports a clean drive. A route being dug right now is also asked of the
 * machine digging it, because the build record is only written every few blocks and the face is exact.</p>
 */
public final class TunnelCrossings {

    /**
     * How far either side of the meeting the other tunnel is taken to run, in blocks.
     *
     * <p>Only the crossing itself matters, and keeping the exemption short keeps it honest: two routes
     * that run alongside each other for a kilometre meet once, and this is not a licence to stop lining
     * for the rest of it.</p>
     */
    private static final double REACH = 24.0;

    private TunnelCrossings() {
    }

    /**
     * One stretch of another route's tunnel, as chainages on that route's own centreline.
     *
     * <p>Chainages rather than a volume, so the geometry can be built and tested without a world: the
     * rule that a branch must not bore its main line's rails away is exactly the sort of thing that is
     * wrong in a way nobody sees until a coverage check on a route nobody was building.</p>
     */
    public record Stretch(Centreline line, double from, double to) {
    }

    /**
     * The tunnels of other routes that this one runs into.
     *
     * @param profile this machine's own section, which is also what the other tunnel is assumed to be.
     *                A flat crossing is two tunnels at one level, so a route dug to a different section
     *                at a different depth does not cross this one at all and the exemption then covers
     *                ground nothing ever opened. It is bounded to stretches the other route reports
     *                track on, so that ground is at worst left as the rock it already was.
     * @return the volume, or null when this route meets nothing that is built
     */
    public static CarveVolume shared(ServerLevel level, UUID routeId, List<RouteMeeting> meetings,
                                     TunnelProfile profile, int floorY) {
        return shared(level, routeId, meetings, profile, floorY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, route -> null);
    }

    /**
     * The same, for one stretch of this route and with the machines currently digging taken into
     * account.
     *
     * <p>A machine cutting one slice only cares about the crossings that slice can reach, and working
     * it out costs compiling somebody else's alignment: bounding it to the window is what makes asking
     * every few blocks affordable rather than a whole-network computation per tick.</p>
     *
     * @param from the near end of the stretch being cut, as a chainage on this route
     * @param digging which stretch of another route has been dug <em>this second</em>, which a build
     *                record written every eight blocks does not yet know. Null for a route nobody is
     *                on. A stretch rather than a distance, because a machine part way through a
     *                journey may be building the middle of a route, or its far end backwards.
     */
    public static CarveVolume shared(ServerLevel level, UUID routeId, List<RouteMeeting> meetings,
                                     TunnelProfile profile, int floorY, double from, double to,
                                     Function<UUID, BuildProgress.Span> digging) {
        AlignmentStore store = AlignmentStore.of(level);
        return volume(stretches(routeId, meetings, store::get, from, to, digging), profile, floorY);
    }

    /**
     * Which stretches of which other routes this one has to leave alone, as chainages.
     *
     * <p>The whole of the rule, with no world in it, because the rule is exactly the sort of thing that
     * is wrong in a way nobody sees until a coverage check on a route nobody was building.</p>
     *
     * @param routes how to look a route up by id
     * @param from the window on this route, in its own chainage; a meeting outside it cannot be reached
     *             by what is being cut and costs an alignment compile to consider
     */
    public static List<Stretch> stretches(UUID routeId, List<RouteMeeting> meetings,
                                          Function<UUID, Alignment> routes, double from, double to,
                                          Function<UUID, BuildProgress.Span> digging) {
        List<Stretch> stretches = new ArrayList<>();
        if (meetings == null || meetings.isEmpty()) {
            return stretches;
        }
        for (RouteMeeting meeting : meetings) {
            RouteMeeting.Side mine = meeting.sideOf(routeId);
            if (mine == null || mine.chainage() < from - REACH || mine.chainage() > to + REACH) {
                continue;
            }
            RouteMeeting.Side theirs = meeting.otherThan(routeId);
            Alignment other = routes.apply(theirs.route());
            if (other == null) {
                continue;
            }
            Centreline line = other.compile().centreline();
            for (BuildProgress.Span span : dug(other, digging)) {
                double a = Math.max(span.from(), theirs.chainage() - REACH);
                double b = Math.min(span.to(), theirs.chainage() + REACH);
                if (b - a >= 1.0) {
                    stretches.add(new Stretch(line, a, b));
                }
            }
        }
        return stretches;
    }

    /**
     * What of another route is in the ground: what it has reported, plus what is being cut right now.
     *
     * <p>The live face is an extra span rather than a correction to the last one. The spans are unioned
     * into one volume, so an overlap costs nothing and the two do not have to agree.</p>
     */
    private static List<BuildProgress.Span> dug(Alignment other,
                                                Function<UUID, BuildProgress.Span> digging) {
        List<BuildProgress.Span> spans = new ArrayList<>(other.built().spans());
        BuildProgress.Span face = digging.apply(other.id());
        if (face != null && face.length() > 0.0) {
            spans.add(face);
        }
        return spans;
    }

    /** The same volume, from stretches already worked out: the whole of the geometry, and no world. */
    public static CarveVolume volume(List<Stretch> stretches, TunnelProfile profile, int floorY) {
        List<CarveVolume> parts = new ArrayList<>();
        for (Stretch stretch : stretches) {
            CarveVolume.Corridor path = TunnelBuilder.corridor(stretch.line(), stretch.from(),
                    stretch.to(), profile, floorY);
            parts.add(new ProfileVolume(path, profile, TunnelProfile.Kind.BORE, floorY, false));
        }
        return parts.isEmpty() ? null : CarveVolume.union(parts);
    }

    /** A lining that stops at the edge of somebody else's tunnel instead of walling it off. */
    public static CarveVolume lining(CarveVolume shell, CarveVolume shared) {
        return shell == null || shared == null ? shell : CarveVolume.difference(shell, shared);
    }

    /**
     * A bore that opens the crossing without sweeping the other line's rails out of it.
     *
     * <p>Only the one course the rails stand on is spared, because that is all track is: everything
     * above it is still cut, so the chamber really is open and a train really can pass through.</p>
     */
    public static CarveVolume boring(CarveVolume bore, CarveVolume shared, int floorY) {
        return shared == null ? bore
                : CarveVolume.difference(bore, CarveVolume.atLevel(shared, floorY));
    }
}
