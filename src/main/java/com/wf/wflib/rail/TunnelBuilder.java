package com.wf.wflib.rail;

import com.mojang.logging.LogUtils;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentEdits;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.build.RailWorks;
import com.wf.wflib.rail.build.TrackJob;
import com.wf.wflib.rail.excavate.CarvePlan;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ExcavationService;
import com.wf.wflib.rail.excavate.LightingPolicy;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TorchPlan;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a surveyed route and a drawn section into a tunnel, and tells the route what got built.
 *
 * <p>This is the whole point of the survey: the line was drawn on a map weeks ago, and building it is
 * one instruction rather than a player dragging a machine along it. The route already carries the
 * geometry and the record of what is built, and the {@link TunnelProfile} carries the shape, so there
 * is nothing to specify here except how deep.</p>
 *
 * <p>Four passes, each over the whole tunnel before the next begins, and the order is the fluid story:
 * see {@link CarvePlan.Stage}. Only the boring pass is split into stretches, because it is the only one
 * whose progress is worth watching.</p>
 */
public final class TunnelBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks of route per carve. Short enough to see progress, long enough not to be all overhead. */
    public static final double STRETCH = 48.0;

    /**
     * How many times to sweep the water out before giving up and cutting anyway.
     *
     * <p>Each pass leaves strictly less than it found, so this is a bound on a loop that terminates
     * rather than a guess. Two is the usual number.</p>
     */
    public static final int MAX_DEWATER_ROUNDS = 6;

    /**
     * How finely the centreline is followed.
     *
     * <p>Chords this long depart from a curve by {@code step² / 8R}, which on the tightest curve any
     * design class allows is under a hundredth of a block.</p>
     */
    private static final double FOLLOW_STEP = 4.0;

    private TunnelBuilder() {
    }

    /**
     * What the slab past one end of a route is: a face, or the next railway.
     *
     * <p>A route ends where the survey stops, and that is a wall everywhere except the one place it
     * matters most. Where a route joins another end to end, or a branch ends on the main line it leaves,
     * its "face" is the middle of somebody else's finished tunnel, and capping it bricks up a working
     * railway a block short of the junction. Neither route can see it: each of them is built correctly
     * right up to the point they share, and the plug belongs to whichever one happened to be dug
     * second.</p>
     *
     * <p>Asked of the survey rather than of the ground, so it does not depend on which route was built
     * first. A crossing is not a reason to open an end: the routes run on through each other there and
     * neither of them stops.</p>
     */
    public static ProfileVolume.End portalAt(List<RouteMeeting> meetings, UUID routeId,
                                             RouteMeeting.End which) {
        if (meetings == null) {
            return ProfileVolume.End.CAP;
        }
        for (RouteMeeting meeting : meetings) {
            RouteMeeting.Side mine = meeting.sideOf(routeId);
            if (mine == null || mine.end() != which
                    || meeting.kind() == RouteMeeting.Kind.CROSSING) {
                continue;
            }
            return ProfileVolume.End.OPEN;
        }
        return ProfileVolume.End.CAP;
    }

    /** The lining of a whole route, walled at its faces and open where it meets another railway. */
    public static ProfileVolume sweep(CarveVolume.Corridor path, TunnelProfile profile,
                                      TunnelProfile.Kind want, int floorY,
                                      List<RouteMeeting> meetings, UUID routeId) {
        return new ProfileVolume(path, profile, want, floorY,
                portalAt(meetings, routeId, RouteMeeting.End.START),
                portalAt(meetings, routeId, RouteMeeting.End.FINISH));
    }


    /**
     * @param columns chunk columns the whole tunnel touches
     * @param territory who owns the ground it crosses, and whether the digger may have it
     */
    public record Submitted(int stretches, int columns, double length, int torches, String profile,
                            TunnelTerritory.Survey territory) {

        public static final Submitted NOTHING = new Submitted(0, 0, 0.0, 0, "", null);

        /** @return whether nothing was queued because the route crosses somebody else's ground. */
        public boolean refused() {
            return this.territory != null && !this.territory.clear();
        }
    }

    /**
     * Excavate, line and light a whole route.
     *
     * @param floorY y of the tunnel floor: the lowest block you can stand on inside it
     * @param lit whether to run the lighting pass at all; the section decides where the torches go
     */
    public static Submitted build(ServerLevel level, Alignment route, TunnelProfile profile,
                                  String lining, int floorY, boolean lit, LightingPolicy lighting,
                                  UUID faction) {
        Centreline centreline = route.compile().centreline();
        double length = centreline.length();
        if (length <= 0.0) {
            return Submitted.NOTHING;
        }
        // The section's full width decides the parity the centreline snaps to, so "six across" is six
        // cells wherever the surveyor happened to click. See CarveVolume.Corridor.
        CarveVolume.Corridor path = corridor(centreline, 0.0, length, profile, floorY);
        // Where this route runs into railways that are already built, the lining stops at their tunnel
        // and the bore leaves the course their rails stand on. See TunnelCrossings: without it the
        // second tunnel of any crossing walls off the first and sweeps its track away.
        List<RouteMeeting> meetings =
                com.wf.wflib.rail.align.RouteMeetings.on(AlignmentStore.of(level).all(), route.id());
        CarveVolume shared = TunnelCrossings.shared(level, route.id(), meetings, profile, floorY);
        CarveVolume bore = TunnelCrossings.boring(
                sweep(path, profile, TunnelProfile.Kind.BORE, floorY, meetings, route.id()), shared,
                floorY);
        CarveVolume shell = TunnelCrossings.lining(
                sweep(path, profile, TunnelProfile.Kind.LINING, floorY, meetings, route.id()), shared);
        List<TorchPlan.Torch> torches = lit ? TorchPlan.along(path, profile, floorY, bore) : List.of();
        CarveVolume lights = torches.isEmpty() ? null
                : CarveVolume.positions(TorchPlan.positions(torches));

        // Asked before anything is queued, because a refusal after the first stretch has been cut is a
        // tunnel that already exists under somebody else's citadel.
        TunnelTerritory.Survey territory = TunnelTerritory.survey(level, faction, path, profile, floorY);
        if (!territory.clear()) {
            return new Submitted(0, 0, length, 0, profile.name(), territory);
        }

        CarvePlan plan = CarvePlan.tunnel(bore, shell, lights, lining, lighting);
        int stretches = Math.max(1, (int) Math.ceil(length / STRETCH));

        int columns = ExcavationService.of(level).submit(level, plan.at(CarvePlan.Stage.LINE),
                territory.allowed(),
                fluid -> dewater(level, route.id(), centreline, profile, floorY, plan, territory,
                        faction, 1));
        return new Submitted(stretches, columns, length, torches.size(), profile.name(), territory);
    }

    /**
     * Pass two: take the water out of the sealed tube, and keep doing it until a pass finds none.
     *
     * <p>One pass is not enough and cannot be. Placing the lining tells every fluid block it touches to
     * flow, and those scheduled flows are still arriving for some ticks afterwards, so a sweep that
     * started before they landed finishes behind them. Removing fluid schedules nothing new, so each
     * pass strictly reduces what is left and a clean pass means clean.</p>
     */
    private static void dewater(ServerLevel level, UUID routeId, Centreline centreline,
                                TunnelProfile profile, int floorY, CarvePlan plan,
                                TunnelTerritory.Survey territory, UUID faction, int round) {
        ExcavationService.of(level).submit(level, plan.at(CarvePlan.Stage.DEWATER), territory.allowed(),
                fluid -> {
            if (fluid > 0 && round < MAX_DEWATER_ROUNDS) {
                dewater(level, routeId, centreline, profile, floorY, plan, territory, faction,
                        round + 1);
                return;
            }
            if (fluid > 0) {
                LOGGER.warn("[wflib] gave up dewatering the tunnel on route {} with {} cell(s) still wet"
                        + " after {} passes; it will be cut anyway", routeId, fluid, round);
            }
            bore(level, routeId, centreline, profile, floorY, plan, territory, faction);
        });
    }

    /** Pass three: the rock. Nothing can flood it, so this one can be reported stretch by stretch. */
    private static void bore(ServerLevel level, UUID routeId, Centreline centreline,
                             TunnelProfile profile, int floorY, CarvePlan plan,
                             TunnelTerritory.Survey territory, UUID faction) {
        double length = centreline.length();
        int stretches = Math.max(1, (int) Math.ceil(length / STRETCH));
        ExcavationService service = ExcavationService.of(level);
        List<RouteMeeting> meetings =
                com.wf.wflib.rail.align.RouteMeetings.on(AlignmentStore.of(level).all(), routeId);
        // Only the two outermost stretch boundaries are the route's own ends; the rest are seams in the
        // middle of one tunnel, and a seam claims nothing past itself.
        ProfileVolume.End first = portalAt(meetings, routeId, RouteMeeting.End.START);
        ProfileVolume.End last = portalAt(meetings, routeId, RouteMeeting.End.FINISH);
        int[] outstanding = {stretches};
        for (int i = 0; i < stretches; i++) {
            double from = length * i / stretches;
            double to = length * (i + 1) / stretches;
            CarveVolume.Corridor part = corridor(centreline, from, to, profile, floorY);
            CarveVolume stretchBore = new ProfileVolume(part, profile, TunnelProfile.Kind.BORE, floorY,
                    i == 0 ? first : ProfileVolume.End.FLUSH,
                    i == stretches - 1 ? last : ProfileVolume.End.FLUSH);
            service.submit(level, plan.at(CarvePlan.Stage.BORE).withBore(stretchBore),
                    territory.allowed(), fluid -> {
                        report(level, routeId, from, to);
                        if (--outstanding[0] == 0) {
                            light(level, routeId, profile, floorY, plan, territory, faction);
                        }
                    });
        }
    }

    /** Pass four: the torches, once there is a tunnel for them to be in. */
    private static void light(ServerLevel level, UUID routeId, TunnelProfile profile, int floorY,
                              CarvePlan plan, TunnelTerritory.Survey territory, UUID faction) {
        CarvePlan lighting = plan.at(CarvePlan.Stage.LIGHT);
        if (!lighting.hasWork()) {
            track(level, routeId, profile, floorY, plan, faction);
            return;
        }
        ExcavationService.of(level).submit(level, lighting, territory.allowed(),
                fluid -> track(level, routeId, profile, floorY, plan, faction));
    }

    /**
     * Pass five: the rails, once the tunnel is finished.
     *
     * <p>Last of all and never sooner. A boring pass over a cell that already has a rail in it takes the
     * rail out again, so track laid before the bore had finished would be track laid twice, the first
     * time for nothing.</p>
     */
    private static void track(ServerLevel level, UUID routeId, TunnelProfile profile, int floorY,
                              CarvePlan plan, UUID faction) {
        if (!RailConfig.TUNNEL_TRACK.get()) {
            return;
        }
        Alignment route = AlignmentStore.of(level).get(routeId);
        if (route == null) {
            return;
        }
        TrackJob job = TrackJob.on(level, route, profile, plan.lining(), floorY,
                RailConfig.TRACK_BOOST_SPACING.get(), faction);
        if (job != null) {
            RailWorks.lay(level, job);
        }
    }

    /**
     * The tunnel exists between these chainages, so the route says so.
     *
     * <p>Runs when every column of that stretch has landed, not when it was queued. A route that claims
     * to be built where the carve was cancelled or failed would be worse than one that claims
     * nothing.</p>
     */
    private static void report(ServerLevel level, UUID routeId, double from, double to) {
        AlignmentStore store = AlignmentStore.of(level);
        Alignment current = store.get(routeId);
        if (current == null) {
            return;
        }
        store.put(AlignmentEdits.applyBuild(current, from, to, true));
    }

    /** One stretch of the centreline as a path for the section to be swept along. */
    public static CarveVolume.Corridor corridor(Centreline centreline, double from, double to,
                                                 TunnelProfile profile, int floorY) {
        List<AlignElement.Sample> samples = between(centreline, from, to);
        double[] xs = new double[samples.size()];
        double[] zs = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            xs[i] = samples.get(i).x();
            zs[i] = samples.get(i).z();
        }
        return CarveVolume.corridor(xs, zs, profile.width(), floorY, profile.boreHeight());
    }

    /**
     * Sample one stretch of a centreline by chainage.
     *
     * <p>Whole-numbered divisions of the stretch, so neighbouring stretches share their end sample
     * exactly and the two corridors meet with no seam between them.</p>
     */
    private static List<AlignElement.Sample> between(Centreline centreline, double from, double to) {
        int parts = Math.max(1, (int) Math.ceil((to - from) / FOLLOW_STEP));
        List<AlignElement.Sample> out = new ArrayList<>(parts + 1);
        for (int i = 0; i <= parts; i++) {
            out.add(centreline.at(from + (to - from) * i / parts));
        }
        return out;
    }
}
