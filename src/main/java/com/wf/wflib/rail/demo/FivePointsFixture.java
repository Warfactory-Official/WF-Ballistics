package com.wf.wflib.rail.demo;

import com.wf.wflib.rail.RailCompat;
import com.wf.wflib.rail.RailConfig;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentEdits;
import com.wf.wflib.rail.align.AlignmentService;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.build.BoreTrain;
import com.wf.wflib.rail.build.RailWorks;
import com.wf.wflib.rail.build.TrackJob;
import com.wf.wflib.rail.build.ir.IrStock;
import com.wf.wflib.rail.excavate.TunnelProfile;
import com.wf.wflib.rail.excavate.TunnelProfiles;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deploys the Five Points network: surveys it, then builds it one route at a time.
 *
 * <p>How many machines are out at once is the argument, and it is the whole of the test. <b>One</b> is
 * the easy case and the one everything was written against: a tunnel only makes room for another railway
 * where that railway <em>reports itself built</em>, and with one machine on the map every other route is
 * either finished or untouched, so that report is never out of date. <b>A few</b> is the railway as
 * anyone would actually build it, and it is a different question: two machines can reach the same
 * crossing within a second of each other, each having been told by the survey that the other's ground is
 * solid rock.</p>
 *
 * <p>Either way, every route already laid is checked again each time one finishes, because both of the
 * destructive bugs found so far showed up only as a fall in the coverage of a route nobody was building.
 * With a gang the drop is reported against whatever was in the ground at the time rather than against
 * one route, since that is honestly all that is known.</p>
 *
 * <p>The order the routes are built in decides which line arrives second at each of the six crossings,
 * which is the half that has to make room. Reversing it swaps all six, which is a second run over the
 * same ground rather than a second network.</p>
 */
public final class FivePointsFixture {

    /** How finely a finished route is asked whether it carries a railway. */
    private static final double CHECK_STEP = 2.0;

    /** Ticks a stage may sit still before the run is called off rather than left hanging. */
    private static final int MAX_STALL = 2400;

    private static final Map<ResourceKey<Level>, FivePointsFixture> RUNNING = new HashMap<>();

    private enum Phase { DRIVE, BORING, LAY, LAYING, DONE }

    /** One route, and how far through building it the fixture has got. */
    private static final class Work {

        private final FivePoints.Route route;
        private Phase phase = Phase.DRIVE;
        private int stalled;
        private BoreTrain driving;

        private Work(FivePoints.Route route) {
            this.route = route;
        }
    }

    private final ServerLevel level;
    private final ServerPlayer watcher;
    private final List<Work> work;
    private final TunnelProfile profile;
    private final int floorY;
    private final double speed;
    private final UUID faction;
    /** How many routes may be under a machine at once. One is the old sequential run. */
    private final int gang;

    /** The best coverage each route has ever reported, so a later drop is a regression and not news. */
    private final Map<String, Integer> best = new LinkedHashMap<>();
    private final List<String> regressions = new ArrayList<>();

    private boolean finished;

    private FivePointsFixture(ServerLevel level, ServerPlayer watcher, List<FivePoints.Route> order,
                              TunnelProfile profile, int floorY, double speed, int gang, UUID faction) {
        this.level = level;
        this.watcher = watcher;
        this.work = new ArrayList<>();
        for (FivePoints.Route route : order) {
            this.work.add(new Work(route));
        }
        this.profile = profile;
        this.floorY = floorY;
        this.speed = speed;
        this.gang = Math.max(1, gang);
        this.faction = faction;
    }

    // ------------------------------------------------------------------ surveying

    /**
     * Draw the network.
     *
     * <p>Every route is replaced rather than added to, because the ids are derived from the names: this
     * is a fixture, and running it twice has to give the same railway rather than a second one on top
     * of the first.</p>
     *
     * @return how many routes were drawn
     */
    public static int survey(ServerPlayer player, ServerLevel level, double originX, double originZ) {
        clear(player, level);
        int drawn = 0;
        for (FivePoints.Route route : FivePoints.routes()) {
            AlignmentEdits.SaveOutcome outcome = AlignmentEdits.save(player, level, route.id(),
                    route.name(), route.colour().rgb(), FivePoints.CLASS,
                    FivePoints.points(route, originX, originZ), 0);
            if (outcome == AlignmentEdits.SaveOutcome.SAVED) {
                drawn++;
            } else {
                player.sendSystemMessage(Component.literal(route.name() + " was not drawn: "
                        + outcome.name().toLowerCase(Locale.ROOT)));
            }
        }
        AlignmentService.pushAll(level);
        return drawn;
    }

    /** Take the network back off the map. The tunnels and the track stay where they are. */
    public static int clear(ServerPlayer player, ServerLevel level) {
        AlignmentStore store = AlignmentStore.of(level);
        int gone = 0;
        for (FivePoints.Route route : FivePoints.routes()) {
            if (store.get(route.id()) != null && AlignmentEdits.delete(player, level, route.id())) {
                gone++;
            }
        }
        AlignmentService.pushAll(level);
        return gone;
    }

    /** The network's routes as they stand on the server, in build order, skipping any not drawn. */
    public static List<Alignment> drawn(ServerLevel level) {
        AlignmentStore store = AlignmentStore.of(level);
        List<Alignment> out = new ArrayList<>();
        for (FivePoints.Route route : FivePoints.routes()) {
            Alignment alignment = store.get(route.id());
            if (alignment != null) {
                out.add(alignment);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ building

    public static FivePointsFixture on(ServerLevel level) {
        return RUNNING.get(level.dimension());
    }

    /**
     * Set the whole network building.
     *
     * @param gang how many routes may have a machine on them at once. One is a sequential run, where
     *             every crossing is met by a line that is either finished or has not started.
     * @return why it could not start, or null when it is away
     */
    public static String start(ServerLevel level, ServerPlayer watcher, String profileName,
                               int floorY, double speed, boolean reverse, int gang) {
        if (RUNNING.containsKey(level.dimension())) {
            return "the network is already being built; /wfrail drive stop calls it off";
        }
        List<Alignment> routes = drawn(level);
        if (routes.size() < FivePoints.routes().size()) {
            return "the network is not drawn: /wfrail demo survey first";
        }
        TunnelProfile profile = TunnelProfiles.get(profileName);
        if (profile == null) {
            return "no tunnel section called '" + profileName + "'";
        }
        List<FivePoints.Route> order = new ArrayList<>(FivePoints.routes());
        if (reverse) {
            java.util.Collections.reverse(order);
        }
        UUID faction = routes.get(0).ownerFaction();
        RUNNING.put(level.dimension(),
                new FivePointsFixture(level, watcher, order, profile, floorY, speed, gang, faction));
        return null;
    }

    public static void tick(ServerLevel level) {
        FivePointsFixture fixture = RUNNING.get(level.dimension());
        if (fixture == null) {
            return;
        }
        fixture.step();
        if (fixture.finished) {
            RUNNING.remove(level.dimension());
        }
    }

    /** Called off, along with everything else a {@code /wfrail drive stop} stops. */
    public static boolean stop(ServerLevel level) {
        return RUNNING.remove(level.dimension()) != null;
    }

    public String where() {
        List<String> out = new ArrayList<>();
        int done = 0;
        for (Work item : this.work) {
            if (item.phase == Phase.DONE) {
                done++;
            } else if (item.driving != null || item.phase != Phase.DRIVE) {
                out.add(item.route.name() + " " + item.phase.name().toLowerCase(Locale.ROOT));
            }
        }
        return String.format(Locale.ROOT, "%d of %d done, %s", done, this.work.size(),
                out.isEmpty() ? "nothing under a machine" : String.join(", ", out));
    }

    /**
     * One tick of the whole gang.
     *
     * <p>The first {@code gang} routes that are not finished are the ones being worked, which is what
     * makes a machine coming off one route put a machine straight on to the next without any handover
     * of its own.</p>
     */
    private void step() {
        int working = 0;
        boolean any = false;
        for (Work item : this.work) {
            if (item.phase == Phase.DONE) {
                continue;
            }
            any = true;
            if (working == this.gang) {
                break;
            }
            working++;
            step(item);
        }
        if (!any) {
            done();
        }
    }

    private void step(Work item) {
        Alignment alignment = AlignmentStore.of(this.level).get(item.route.id());
        if (alignment == null) {
            say(item.route.name() + " is not on the map any more, so it is left unbuilt");
            item.phase = Phase.DONE;
            return;
        }
        switch (item.phase) {
            case DRIVE -> drive(item, alignment);
            case BORING -> waitForBore(item);
            case LAY -> lay(item, alignment);
            case LAYING -> waitForTrack(item);
            default -> { }
        }
    }

    private void drive(Work item, Alignment alignment) {
        BoreTrain.Launch launch = BoreTrain.launch(this.level, alignment, this.profile,
                this.profile.liningOr(RailConfig.TUNNEL_LINING.get()), this.floorY,
                RailConfig.TUNNEL_LIGHTING.get(), this.speed,
                RailConfig.TRACK_BOOST_SPACING.get(), this.faction);
        if (launch.refused()) {
            say(item.route.name() + ": refused, that route crosses ground the digger may not build on");
            item.phase = Phase.DONE;
            return;
        }
        if (launch.train() == null) {
            say(item.route.name() + ": " + launch.problem());
            item.phase = Phase.DONE;
            return;
        }
        RailWorks.start(this.level, launch.train());
        item.driving = launch.train();
        item.phase = Phase.BORING;
        item.stalled = 0;
        say(String.format(Locale.ROOT, "[%d/%d] %s: driving %.0f blocks", finished() + 1,
                this.work.size(), item.route.name(), launch.train().length()));
    }

    private void waitForBore(Work item) {
        if (RailWorks.on(this.level, item.route.id()) == null) {
            if (item.driving != null) {
                say("    " + item.route.name() + ": " + item.driving.summary());
                item.driving = null;
            }
            item.phase = Phase.LAY;
            item.stalled = 0;
            return;
        }
        stall(item, "the bore train");
    }

    private void lay(Work item, Alignment alignment) {
        TrackJob job = TrackJob.on(this.level, alignment, this.profile,
                this.profile.liningOr(RailConfig.TUNNEL_LINING.get()), this.floorY,
                RailConfig.TRACK_BOOST_SPACING.get(), this.faction);
        if (job == null) {
            say("    " + item.route.name() + " is too short to lay track on");
            finish(item);
            return;
        }
        RailWorks.lay(this.level, job);
        item.phase = Phase.LAYING;
        item.stalled = 0;
    }

    private void waitForTrack(Work item) {
        for (TrackJob job : RailWorks.jobs(this.level)) {
            if (job.routeId().equals(item.route.id())) {
                stall(item, "the track gang");
                return;
            }
        }
        finish(item);
    }

    /**
     * This route is built: check it, and check every route already built.
     *
     * <p>The second half is the one that matters. A tunnel driven through a finished railway can take
     * that railway out without anything at all going wrong on the route being built, so the only place
     * it shows up is in a number nobody would otherwise have looked at again.</p>
     */
    private void finish(Work item) {
        item.phase = Phase.DONE;
        item.stalled = 0;
        if (!RailCompat.trackGraphAvailable()) {
            return;
        }
        AlignmentStore store = AlignmentStore.of(this.level);
        for (Work built : this.work) {
            if (built.phase != Phase.DONE) {
                continue;
            }
            Alignment alignment = store.get(built.route.id());
            if (alignment == null) {
                continue;
            }
            // Over the stretch the line lays itself. Where a route meets another one it leaves its
            // last stretch to the junction and lays no track there, and counting that against it reads
            // as a hole in a railway built exactly as it was meant to be.
            var centre = alignment.compile().centreline();
            double lead = RailConfig.TURNOUT_LEAD.get();
            IrStock.Coverage coverage = IrStock.check(this.level, centre, this.floorY, CHECK_STEP,
                    junction(alignment, RouteMeeting.End.START) ? lead : 0.0,
                    centre.length() - (junction(alignment, RouteMeeting.End.FINISH) ? lead : 0.0));
            Integer was = this.best.get(built.route.name());
            if (was == null) {
                this.best.put(built.route.name(), coverage.onTrack());
                say("    " + built.route.name() + " " + coverage.describe());
            } else if (coverage.onTrack() < was) {
                String line = String.format(Locale.ROOT,
                        "%s fell from %d to %d point(s) on track while %s %s being built",
                        built.route.name(), was, coverage.onTrack(), working(item),
                        this.gang > 1 ? "were" : "was");
                this.regressions.add(line);
                this.best.put(built.route.name(), coverage.onTrack());
                say("    LOST: " + line);
            }
        }
    }

    /** What was in the ground when a drop appeared, which with a gang is all that can honestly be said. */
    private String working(Work just) {
        List<String> names = new ArrayList<>();
        names.add(just.route.name());
        for (Work item : this.work) {
            if (item.phase != Phase.DONE && item.driving != null) {
                names.add(item.route.name());
            }
        }
        return String.join(" and ", names);
    }

    private int finished() {
        int done = 0;
        for (Work item : this.work) {
            if (item.phase == Phase.DONE) {
                done++;
            }
        }
        return done;
    }

    /** Whether one end of a route is a junction's rather than the line's. */
    private boolean junction(Alignment route, RouteMeeting.End end) {
        for (RouteMeeting meeting : RouteMeetings.on(AlignmentStore.of(this.level).all(), route.id())) {
            RouteMeeting.Side mine = meeting.sideOf(route.id());
            if (mine == null || mine.end() != end) {
                continue;
            }
            if (meeting.kind() == RouteMeeting.Kind.JOINT
                    || (meeting.kind() == RouteMeeting.Kind.TURNOUT && meeting.branch() == mine)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stage that is not moving.
     *
     * <p>Counted per route rather than for the run, because with a gang one machine waiting on ground
     * that never loads must not take the other machines' work down with it.</p>
     */
    private void stall(Work item, String what) {
        if (++item.stalled > MAX_STALL) {
            say(item.route.name() + ": " + what + " has not moved for two minutes, so it is left here");
            item.phase = Phase.DONE;
        }
    }

    private void done() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        // The junctions last, and on purpose. A joint is a connecting curve and a branch is a switch,
        // and both of them sit in the last stretch of a route that the route deliberately did not lay:
        // until every route that meets here is on the ground there is nothing to connect.
        if (RailCompat.trackGraphAvailable()) {
            int[] junctions = com.wf.wflib.rail.RailCommands.junctions(this.level, this.floorY,
                    line -> say("  " + line), line -> say("  " + line));
            say(String.format(Locale.ROOT, "junctions: %d built%s", junctions[0],
                    junctions[1] > 0 ? ", " + junctions[1] + " not" : ""));
        }
        if (this.regressions.isEmpty()) {
            say(this.gang > 1
                    ? "network built by " + this.gang + " machines at once, and no route lost track"
                            + " to one being built beside it"
                    : "network built, and no route lost track to a later one");
        } else {
            say(this.regressions.size() + " route(s) lost track while another was being built:");
            for (String line : this.regressions) {
                say("  " + line);
            }
        }
        say("/wfrail demo check for the verdict");
    }

    private void say(String message) {
        if (this.watcher != null && !this.watcher.hasDisconnected()) {
            this.watcher.sendSystemMessage(Component.literal(message));
        }
    }

    /** Server shutting down. */
    public static void clearAll() {
        RUNNING.clear();
    }
}
