package com.wf.wflib.rail.build;

import com.mojang.logging.LogUtils;
import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.rail.TunnelBuilder;
import com.wf.wflib.rail.TunnelCrossings;
import com.wf.wflib.rail.TunnelTerritory;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentEdits;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.AlignmentService;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.excavate.CarvePlan;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ProfileVolume;
import com.wf.wflib.rail.excavate.TorchPlan;
import com.wf.wflib.rail.excavate.TunnelProfile;
import com.wf.wflib.rail.plan.Leg;
import com.wf.wflib.rail.supply.Bill;
import com.wf.wflib.rail.supply.Depot;
import com.wf.wflib.rail.supply.Stores;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A machine that drives a tunnel and lays the track in it, a few blocks at a time, while you watch.
 *
 * <p>The same four passes {@link TunnelBuilder} runs over a whole route, run instead over the couple of
 * blocks in front of the cutter head, in one tick, in the order that keeps water out: line the slice,
 * empty it, cut it. Doing all three inside a single tick is what makes a moving face safe. Fluid only
 * flows on a tick boundary, so a slice that is walled, drained and opened before the tick ends can never
 * be reached by the lake it just cut into; the ground still ahead of the face is the plug, and it is
 * removed only once the walls around it already exist.</p>
 *
 * <p>Where it differs from the whole-route builder is what it can promise. The builder owns the entire
 * tunnel before it starts and can order its passes globally; this owns three metres and the next three
 * metres may be someone else's by then. So territory is asked again for every chunk the face enters
 * rather than trusted from the survey, and a refusal stops the machine where it stands instead of
 * cancelling work that has already happened.</p>
 *
 * <p>Position is ours and the consist is the display: carts are put where the machine says the train is,
 * rather than the machine reading where the carts have rolled to. That is the same split the dormant
 * train sim uses, and it is what stops a stall in chunk loading turning into a consist that has run off
 * the end of its own railhead.</p>
 *
 * <p>A machine is given a <b>journey</b> rather than a route: an ordered list of {@link Leg}s, each one
 * a stretch of one surveyed line in the direction the train runs over it. One leg is the ordinary case
 * and everything below here is written for it, because a leg presents itself as a route - its own
 * centreline from zero, its own meetings, its own portals. What several legs add is that the machine
 * carries on to the next one instead of finishing, and that the train's way home runs back over the
 * ones it has already built rather than stopping at the end of the one it is on.</p>
 *
 * <p>It also starts wherever the route says it has already been built. A machine that ran out of
 * bricks a kilometre down and was sent out again would otherwise drive the same kilometre a second
 * time and be charged for it twice, which is a railway you cannot finish by paying for it.</p>
 *
 * <p>A machine given a {@link Depot} is a <b>work train</b> and pays for what it builds out of its own
 * wagons: it runs out to the railhead over the line already there, cuts until it is out of something,
 * runs back down its own tunnel to the depot, tips the spoil, reloads and goes out again. One without a
 * depot is an engineer's drive, builds for nothing and says so. Both cut the same tunnel; the only
 * difference is whether there is a bill.</p>
 */
public final class BoreTrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Notify clients, and tell neighbours, exactly as the attended carve does. */
    private static final int LINING_FLAGS = Block.UPDATE_ALL;

    /**
     * Tell the clients, tell nobody else. Only ever for taking fluid out of a tube that is already
     * sealed: waking the neighbours there invites the rest of the pool in behind the machine's back.
     */
    private static final int FLUID_FLAGS = Block.UPDATE_CLIENTS;

    /** How far behind the face track is laid. One block, so the cell being laid is fully cut. */
    private static final double TRACK_LAG = 1.0;

    /** How much faster a train runs over finished railway than it cuts new tunnel. */
    private static final double TRAVEL_FACTOR = 6.0;

    /** How far behind the face the cutter car sits, and the gap between cars behind it. */
    private static final double HEAD_LAG = 2.0;
    private static final double CAR_GAP = 2.0;

    /** A minecart's own ride height above the rail it sits on. */
    private static final double RIDE_HEIGHT = 0.1;

    /** Blocks of progress between writes to the route's build record. */
    private static final double REPORT_STEP = 8.0;

    /**
     * Blocks of progress between asking again which other railways are in the way.
     *
     * <p>Short against {@link TunnelCrossings}' own reach, so a crossing is seen long before the face
     * arrives at it, and long against a slice, so the question is asked a few times per crossing rather
     * than a few times per second.</p>
     */
    private static final double CROSSING_REFRESH = 4.0;

    /** How far ahead of the working face chunks are asked for, in blocks. */
    private static final int LOOKAHEAD = 48;

    /** How long a machine waits for ground that never arrives before giving up, in ticks. */
    private static final int MAX_STALL = 600;

    private final UUID faction;
    private final TunnelProfile profile;
    private final String liningName;
    private final BlockState lining;
    private final int floorY;
    private final boolean lit;
    private final double speed;
    private final int boostSpacing;

    /** The whole job, in the order it is built. One leg for an ordinary single route drive. */
    private final List<Leg> legs;
    private int legIndex;
    /** The leg being built, which is what every per-route field below is a view of. */
    private Leg leg;

    private UUID routeId;
    private String routeName;
    private Centreline centreline;
    private CarveVolume.Corridor path;
    /**
     * The leg's own length, which is not the corridor's.
     *
     * <p>The corridor is the centreline sampled every few blocks and snapped to the grid the section
     * sweeps along, so it is a little shorter or longer than the curve it came from. Progress is
     * measured along the corridor because that is what the machine drives on, and reported along the
     * route because that is what the survey is in; the two are kept apart rather than quietly mixed,
     * which would have a finished tunnel reporting ninety-nine per cent built.</p>
     */
    private double routeLength;
    /** The whole surveyed route this leg is part of, which is the length its build record is clamped to. */
    private double surveyLength;
    /** What the slab past each end of this leg is, which at a junction is the next railway. */
    private ProfileVolume.End atStart;
    private ProfileVolume.End atFinish;
    /**
     * Where other railways meet this one, in this leg's own chainage.
     *
     * <p>Worked out again as the face advances rather than kept from launch. See
     * {@link TunnelCrossings}: a machine that did not know would wall off the line it crossed and
     * sweep its rails away, and say nothing about either - and with more than one machine on the map
     * what it has to know changes while it is driving.</p>
     */
    private List<RouteMeeting> meetings;
    private TrackLine line;
    private List<TorchPlan.Torch> torches;
    /** How many spoil cars this machine may grow to before it starts leaving rock behind. */
    private final int maxCars;

    /**
     * The corridors of the legs already built, which is the railway the train goes home over.
     *
     * <p>Travel is measured from the depot along the whole journey rather than along the leg being
     * cut, because on a resupply trip those are different things: the face may be on the third line
     * out and the chest is still at the first one's portal.</p>
     */
    private final List<CarveVolume.Corridor> done = new ArrayList<>();
    private double behind;
    /** Blocks still to build on the legs after this one, which is what a load is sized against. */
    private double ahead;

    /** Chunks already asked about, so WarForge is asked once per chunk rather than once per block. */
    private final Set<Long> cleared = new HashSet<>();

    private final List<AbstractMinecart> consist = new ArrayList<>();
    private final List<Container> holds = new ArrayList<>();

    /** Where this train loads and tips, or null for a drive that builds for nothing. */
    private final Depot depot;
    private final Bill bill;
    private final Stores stores;
    /** Told what the train is doing, so a player who dispatched one is not watching a silent tunnel. */
    private final java.util.function.Consumer<String> told;

    /** What a work train is doing. A machine with no depot is only ever {@link Stage#WORKING}. */
    public enum Stage { WORKING, INBOUND, LOADING, OUTBOUND }

    private Stage stage;
    /**
     * Where the consist is, along the corridor.
     *
     * <p>Its own number rather than the face minus a lag, because on a resupply trip the train and the
     * face are a tunnel apart: the face stays exactly where it was left and the train goes home.</p>
     */
    private double train;
    /** Blocks of track built but not yet paid for in whole items. */
    private double owedTrack;
    private int trips;
    private int torchesShort;
    /**
     * What the train ran out of, which is what it went back for.
     *
     * <p>The item and not its name, because coming home is only half of a resupply: a train that went
     * back for bricks and returned with a wagon of torches has not been resupplied, and telling those
     * two apart is what stops it shuttling for ever. See {@link #restock}.</p>
     */
    private Item waiting;

    private double face;
    private double reported;
    private int torchCursor;
    private boolean started;
    private boolean finished;
    private String halted;
    private int stalled;

    /**
     * The crossings near the face, and the band of the drive they were worked out for.
     *
     * <p>Held for a few blocks at a time. Recomputing it per tick would compile every alignment this
     * route meets forty times a second; not recomputing it at all is the bug this exists to fix.</p>
     */
    private CarveVolume shared;
    private double sharedBand = Double.NaN;

    private int broken;
    private int linedCells;
    private int lightsPlaced;
    private int spoilHauled;
    private int spoilLost;

    private BoreTrain(List<Leg> legs, TunnelProfile profile, String lining, int floorY, boolean lit,
                      double blocksPerSecond, int boostSpacing, UUID faction, int maxCars, Depot depot,
                      java.util.function.Consumer<String> told) {
        this.legs = List.copyOf(legs);
        this.maxCars = Math.max(1, maxCars);
        this.faction = faction;
        this.profile = profile;
        this.liningName = lining;
        this.lining = CarvePlan.stateOf(lining);
        this.floorY = floorY;
        this.lit = lit;
        this.speed = Math.max(0.05, blocksPerSecond / 20.0);
        this.boostSpacing = boostSpacing;
        this.depot = depot;
        this.bill = Bill.of(profile, lining, com.wf.wflib.rail.RailConfig.TRACK_MATERIAL.get());
        this.stores = new Stores(this.holds);
        this.told = told;
        // A work train starts at the depot, because it starts empty. An engineer's drive starts at the
        // face, because there is nothing for it to load.
        this.stage = depot == null ? Stage.WORKING : Stage.LOADING;
    }

    /**
     * Take up the next leg: its centreline, its tunnel, its track and whatever of it is already built.
     *
     * <p>Everything the machine knows about the ground it is cutting is rebuilt here and nowhere else,
     * which is what lets the rest of the class go on being written for one route. The consist, the
     * cargo, the trips and the spoil are not touched: it is the same train, further down the line.</p>
     *
     * @return false when the route has gone from under the journey
     */
    private boolean onto(ServerLevel level, Leg next) {
        AlignmentStore store = AlignmentStore.of(level);
        Alignment route = store.get(next.route());
        if (route == null) {
            return false;
        }
        this.leg = next;
        this.routeId = next.route();
        this.routeName = next.name() == null || next.name().isEmpty() ? "unnamed route" : next.name();
        Centreline survey = route.compile().centreline();
        this.surveyLength = survey.length();
        this.centreline = next.centreline(survey);
        this.routeLength = this.centreline.length();
        this.meetings = next.meetings(RouteMeetings.on(store.all(), this.routeId));
        this.path = TunnelBuilder.corridor(this.centreline, 0.0, this.routeLength, this.profile,
                this.floorY);
        // An end that meets another railway is not a face and gets no plug: see TunnelBuilder.portalAt.
        // The plug is also subtracted against anything already built there, which covers the case where
        // the other route was drawn after this one was surveyed.
        this.atStart = TunnelBuilder.portalAt(this.meetings, this.routeId, RouteMeeting.End.START);
        this.atFinish = TunnelBuilder.portalAt(this.meetings, this.routeId, RouteMeeting.End.FINISH);
        CarveVolume bore = TunnelBuilder.sweep(this.path, this.profile, TunnelProfile.Kind.BORE,
                this.floorY, this.meetings, this.routeId);
        this.line = TrackLines.on(level, this.routeId, this.centreline, this.path, this.floorY,
                this.lining, this.boostSpacing, bore, this.meetings);
        this.torches = this.lit ? TorchPlan.along(this.path, this.profile, this.floorY, bore) : List.of();
        this.torchCursor = 0;
        this.shared = null;
        this.sharedBand = Double.NaN;
        // Where this leg has got to already. The face is put there rather than at zero, and nothing
        // behind it is cut, laid, lit or charged for a second time.
        double built = this.routeLength <= 0.0 ? 0.0
                : next.builtAlong(route.built()) / this.routeLength * this.path.length();
        this.face = Math.max(0.0, Math.min(this.path.length(), built));
        this.reported = this.face;
        this.line.skipTo(this.face);
        while (this.torchCursor < this.torches.size()
                && this.torches.get(this.torchCursor).chainage() <= this.face) {
            this.torchCursor++;
        }
        this.ahead = 0.0;
        for (int i = this.legIndex + 1; i < this.legs.size(); i++) {
            Leg later = this.legs.get(i);
            Alignment on = store.get(later.route());
            this.ahead += Math.max(0.0,
                    later.length() - (on == null ? 0.0 : later.builtAlong(on.built())));
        }
        return true;
    }

    /**
     * What happened when a drive was ordered.
     *
     * @param train the machine, now running, or null when it never started
     * @param territory the pre-flight answer about whose ground the route crosses
     * @param problem why it did not start, when the ground was not the reason
     */
    public record Launch(BoreTrain train, TunnelTerritory.Survey territory, String problem) {

        public boolean refused() {
            return this.train == null && this.territory != null && !this.territory.clear();
        }
    }

    /**
     * Put a machine on a route.
     *
     * <p>The whole route is surveyed for territory before a block is cut, which is the same refusal the
     * one-shot builder makes and for the same reason: finding out halfway that the far end is somebody
     * else's leaves a tunnel already dug under them. It is not the enforcement, though, only the
     * warning. The enforcement is per chunk, as the face reaches it.</p>
     */
    public static Launch launch(ServerLevel level, Alignment route, TunnelProfile profile, String lining,
                                int floorY, boolean lit, double blocksPerSecond, int boostSpacing,
                                UUID faction) {
        return launch(level, route, profile, lining, floorY, lit, blocksPerSecond, boostSpacing,
                faction, null, null);
    }

    /**
     * Put a work train on a route, paid for out of a depot.
     *
     * <p>The same machine and the same tunnel. What the depot adds is a bill and a way of settling it:
     * the train is charged for every block it puts in the ground, and when it cannot pay it goes home
     * for more instead of carrying on.</p>
     *
     * @param depot where it loads and tips, or null to build for nothing
     * @param told  where to send a line when it sets off, runs dry or comes back loaded
     */
    public static Launch launch(ServerLevel level, Alignment route, TunnelProfile profile, String lining,
                                int floorY, boolean lit, double blocksPerSecond, int boostSpacing,
                                UUID faction, Depot depot,
                                java.util.function.Consumer<String> told) {
        return launch(level, List.of(Leg.of(route)), profile, lining, floorY, lit, blocksPerSecond,
                boostSpacing, faction, depot, told);
    }

    /**
     * Put a work train on a journey: several routes, in order, built as one job.
     *
     * <p>The territory survey is run over every leg before a block is cut anywhere, for the same reason
     * it is run over a whole route rather than the first hundred metres: a train that finds out at the
     * third junction that the fourth line crosses somebody's claim has already dug three lines to get
     * there. A refusal on any leg refuses the whole dispatch.</p>
     */
    public static Launch launch(ServerLevel level, List<Leg> legs, TunnelProfile profile, String lining,
                                int floorY, boolean lit, double blocksPerSecond, int boostSpacing,
                                UUID faction, Depot depot,
                                java.util.function.Consumer<String> told) {
        if (legs == null || legs.isEmpty()) {
            return new Launch(null, null, "there is no route to build");
        }
        AlignmentStore store = AlignmentStore.of(level);
        TunnelTerritory.Survey first = null;
        for (Leg leg : legs) {
            Alignment route = store.get(leg.route());
            if (route == null) {
                return new Launch(null, null, "one of the routes on that journey has been deleted");
            }
            Centreline part = leg.centreline(route.compile().centreline());
            if (part.length() <= 0.0) {
                return new Launch(null, null, "that route has no length to build");
            }
            CarveVolume.Corridor path = TunnelBuilder.corridor(part, 0.0, part.length(), profile, floorY);
            TunnelTerritory.Survey survey = TunnelTerritory.survey(level, faction, path, profile, floorY);
            if (!survey.clear()) {
                return new Launch(null, survey, null);
            }
            first = first == null ? survey : first;
        }
        BoreTrain train = new BoreTrain(legs, profile, lining, floorY, lit, blocksPerSecond,
                boostSpacing, faction, com.wf.wflib.rail.RailConfig.SPOIL_CARS.get(), depot, told);
        if (!train.onto(level, legs.get(0))) {
            return new Launch(null, first, "that route has been deleted");
        }
        if (train.line.total() == 0) {
            return new Launch(null, first, "that route is too short to lay track on");
        }
        return new Launch(train, first, null);
    }

    // ---------------------------------------------------------------- the drive

    /** @return whether the machine is still working; false once it has finished or been stopped. */
    public boolean tick(ServerLevel level) {
        if (this.finished || this.halted != null) {
            return false;
        }
        return switch (this.stage) {
            case WORKING -> work(level);
            case INBOUND -> run(level, 0.0, Stage.LOADING);
            case LOADING -> restock(level);
            case OUTBOUND -> run(level, railhead(), Stage.WORKING);
        };
    }

    /** Where the consist stands when the machine is cutting, measured from the depot. */
    private double railhead() {
        return this.behind + Math.max(0.0, this.face - HEAD_LAG);
    }

    /** The whole journey's length so far: everything built, plus the leg being cut. */
    private double journey() {
        return this.behind + this.path.length();
    }

    /**
     * Where the train is, for a distance measured from the depot along the whole journey.
     *
     * <p>Not along the leg being cut. The two are the same thing on a single route job and nothing
     * like it on the third line out, where going home means running back over two railways this
     * machine built earlier in the shift.</p>
     */
    private double[] pointAt(double along) {
        double at = Math.max(0.0, along);
        for (CarveVolume.Corridor part : this.done) {
            if (at <= part.length()) {
                return part.pointAt(at);
            }
            at -= part.length();
        }
        return this.path.pointAt(Math.max(0.0, Math.min(at, this.path.length())));
    }

    /**
     * Run over railway that is already there, without cutting anything.
     *
     * <p>Over the whole journey, which is the tunnel this machine dug on the way out and is therefore
     * the one stretch of railway it knows for certain is built. Where the job starts on a line that
     * was already finished - a route being resumed, or a station two junctions back - the legs it has
     * not cut are travelled exactly the same way, because a railway somebody else built is railway
     * just the same.</p>
     */
    private boolean run(ServerLevel level, double target, Stage next) {
        if (!ChunkHold.hold(level, around(this.train), LOOKAHEAD)) {
            if (++this.stalled > MAX_STALL) {
                halt(level, "the ground under the train never loaded");
            }
            return this.halted == null;
        }
        this.stalled = 0;
        double step = this.speed * TRAVEL_FACTOR;
        this.train = this.train < target ? Math.min(target, this.train + step)
                : Math.max(target, this.train - step);
        ride(level);
        if (Math.abs(this.train - target) <= 1.0E-6) {
            this.stage = next;
        }
        return true;
    }

    /**
     * At the depot: tip the spoil, load up, and go back out.
     *
     * <p>Tipping first is not tidiness. The wagons a train comes home with are full of the rock it cut,
     * and there is nowhere to put the lining until that rock is out of them.</p>
     */
    private boolean restock(ServerLevel level) {
        if (this.depot == null) {
            this.stage = Stage.OUTBOUND;
            return true;
        }
        if (!ChunkHold.hold(level, around(this.train), LOOKAHEAD)) {
            if (++this.stalled > MAX_STALL) {
                halt(level, "the ground at the depot never loaded");
            }
            return this.halted == null;
        }
        this.stalled = 0;
        if (!this.started) {
            begin(level);
        }
        if (!this.depot.exists(level)) {
            halt(level, "the depot is gone, so there is nothing to load from");
            return false;
        }
        int[] tipped = this.depot.unload(level, this.stores, materials());
        // What the depot could not take goes on the heap. A tunnel makes about as much rock as it
        // takes brick, so a depot that is also the spoil dump fills long before the line is finished,
        // and a train holding a wagon of rock it cannot put down is a train that cannot load bricks.
        this.spoilLost += this.stores.clear(materials());
        Map<Item, Integer> loaded = this.depot.load(level, this.stores, needed());
        // Loading something is not the same as loading what it came back for. A depot with torches in
        // it and no bricks left will otherwise send the train straight out to the same slice it could
        // not pay for, and it will do that for ever: the run back is short, the load is not empty, and
        // nothing in the cycle ever changes. Progress is what is being checked for here, not cargo.
        boolean resupplied = this.waiting == null ? !loaded.isEmpty()
                : loaded.getOrDefault(this.waiting, 0) > 0;
        if (!resupplied) {
            halt(level, "the depot is out of "
                    + (this.waiting == null ? "materials" : name(this.waiting))
                    + ", and the line is " + String.format(Locale.ROOT, "%.0f", left())
                    + " blocks short; stock it and dispatch again to carry on from here");
            return false;
        }
        this.trips++;
        StringBuilder what = new StringBuilder();
        for (Map.Entry<Item, Integer> line : loaded.entrySet()) {
            what.append(what.isEmpty() ? "" : ", ").append(line.getValue()).append(' ')
                    .append(line.getKey().getDescription().getString());
        }
        say(String.format(Locale.ROOT, "%s: trip %d, loaded %s%s", jobName(), this.trips,
                what, tipped[0] > 0 ? ", tipped " + tipped[0] + " spoil" : ""));
        this.waiting = null;
        this.stage = Stage.OUTBOUND;
        return true;
    }

    /** The materials, as opposed to the spoil, which is everything else in the wagons. */
    private List<Item> materials() {
        return List.of(this.bill.lining(), this.bill.track(), this.bill.torch());
    }

    /** What the rest of this route will take, which is what a trip to the depot loads against. */
    private Map<Item, Integer> needed() {
        return Bill.forLength(this.profile, left()).against(this.bill);
    }

    /** Blocks of railway still owed, over this leg and every one after it. */
    private double left() {
        return Math.max(0.0, this.routeLength - onLeg(this.face)) + this.ahead;
    }

    /**
     * The ground under the consist, which is what has to stay loaded while it runs.
     *
     * <p>Taken from where the train is rather than from a stretch of the leg being cut, because on the
     * way home it is not on that leg at all.</p>
     */
    private BoundingBox around(double along) {
        double[] at = pointAt(along);
        int reach = this.profile.width() / 2 + 2;
        BlockPos centre = BlockPos.containing(at[0], this.floorY, at[1]);
        return new BoundingBox(centre.getX() - reach, this.floorY + this.profile.lowestUp(),
                centre.getZ() - reach, centre.getX() + reach,
                this.floorY + this.profile.highestUp(), centre.getZ() + reach);
    }

    private void say(String message) {
        if (this.told != null) {
            this.told.accept(message);
        }
        LOGGER.info("[wflib] {}", message);
    }

    /** One tick at the face. */
    private boolean work(ServerLevel level) {
        double length = this.path.length();
        if (this.face >= length - 1.0E-9) {
            // Either this leg is cut, or it was already built before the train got here. The same
            // thing either way: finish it off and take up the next one.
            return advance(level);
        }
        double to = Math.min(length, this.face + this.speed);
        BoundingBox slice = workBox(this.face, to);
        if (!ChunkHold.hold(level, slice, LOOKAHEAD)) {
            // The ground is on its way. Everything else waits, including the train, so the consist can
            // never end up ahead of the track that is supposed to be under it.
            if (++this.stalled > MAX_STALL) {
                halt(level, "the ground ahead never loaded");
            }
            return this.halted == null;
        }
        this.stalled = 0;
        if (!mayDig(level, slice)) {
            return false;
        }
        if (!this.started) {
            begin(level);
        }
        if (to > this.face) {
            if (!cut(level, this.face, to)) {
                // Out of something. The face stays exactly where it is, walled and dry, and the train
                // goes home; nothing half cut is left behind, because a slice is paid for before it is
                // started rather than as it goes.
                say(jobName() + ": out of " + name(this.waiting) + " at "
                        + String.format(Locale.ROOT, "%.0f", onLeg(this.face))
                        + " blocks, running back to the depot");
                this.stage = Stage.INBOUND;
                return true;
            }
            this.face = to;
        }
        this.train = railhead();
        this.line.layTo(level, this.face - TRACK_LAG);
        lightUp(level, this.face - TRACK_LAG);
        ride(level);
        reportBuilt(level, false);
        if (this.face >= length - 1.0E-9) {
            return advance(level);
        }
        return true;
    }

    /**
     * This leg is done: lay the last of it, then take up the next one or stop.
     *
     * <p>Only the last leg's far end is walled. A portal in the middle of a journey would be a plug
     * across the junction the train is about to run through, which is the same mistake as walling off
     * a line you cross, made against your own railway.</p>
     */
    private boolean advance(ServerLevel level) {
        // Everything left, with no lag: the last piece of track sits at the very end of the leg and
        // would otherwise be the one block of railway the machine never laid.
        this.line.layAll(level);
        lightUp(level, Double.MAX_VALUE);
        reportBuilt(level, true);
        if (this.legIndex + 1 >= this.legs.size()) {
            finish(level);
            return false;
        }
        this.done.add(this.path);
        this.behind += this.path.length();
        this.legIndex++;
        if (!onto(level, this.legs.get(this.legIndex))) {
            halt(level, "the next route on the journey was deleted");
            return false;
        }
        say(String.format(Locale.ROOT, "now on %s, leg %d of %d, %.0f blocks", this.routeName,
                this.legIndex + 1, this.legs.size(), this.routeLength));
        return true;
    }

    /** What to call this job in a line of chat: the route, or which leg of the journey it is on. */
    private String jobName() {
        return this.legs.size() <= 1 ? this.routeName
                : this.routeName + " (leg " + (this.legIndex + 1) + " of " + this.legs.size() + ")";
    }

    /** Seal the portal behind the machine and put the consist on the ground, once it is loaded. */
    private void begin(ServerLevel level) {
        this.started = true;
        cap(level, 0.0);
        double[] at = pointAt(0.0);
        MinecartFurnace head = new MinecartFurnace(level, at[0], this.floorY + RIDE_HEIGHT, at[1]);
        head.setCustomName(net.minecraft.network.chat.Component.literal(this.routeName + " bore"));
        add(level, head);
        couple(level);
        if (this.depot == null) {
            return;
        }
        // A work train is made up for the job before it leaves: enough wagons to carry the whole line
        // if the depot can supply it, capped at what a train is allowed to be. One wagon and eleven
        // trips is not a railway, it is a queue. Sized against what is left to build rather than the
        // length of the line, so a route being resumed with ten blocks to go does not couple up nine
        // empty wagons to carry them.
        int want = Bill.forLength(this.profile, left()).total();
        while (this.holds.size() < this.maxCars && capacity() < want) {
            couple(level);
        }
    }

    /** How many items the wagons could hold if they were empty. */
    private int capacity() {
        int slots = 0;
        for (Container hold : this.holds) {
            slots += hold.getContainerSize();
        }
        return slots * 64;
    }

    private void add(ServerLevel level, AbstractMinecart cart) {
        // The consist is a display of where the machine is, not a thing being pushed along by physics.
        // Letting the world move it would put it wherever the last powered rail sent it.
        cart.setNoGravity(true);
        cart.setInvulnerable(true);
        level.addFreshEntity(cart);
        this.consist.add(cart);
    }

    /**
     * Cut one slice: wall it, empty it, then open it, all before the tick ends.
     *
     * <p>The three passes are over the same few hundred cells and could be one loop. They are not,
     * because the order between them is the only thing keeping the tunnel dry, and a single loop would
     * bore the first column before it had walled the last.</p>
     */
    private boolean cut(ServerLevel level, double from, double to) {
        CarveVolume.Corridor part = TunnelBuilder.corridor(this.centreline, from, to, this.profile,
                this.floorY);
        // The lining stops at the edge of anything this route crosses, and the bore leaves the one
        // course the crossed line's rails stand on. Dewatering still sweeps the whole section: fluid
        // inside a crossing chamber is fluid in this tunnel too.
        // A slice boundary in the middle of the drive claims nothing past itself; the first and last
        // slices carry the route's own ends, which at a junction run on into the next railway.
        ProfileVolume.End near = from <= 0.0 ? this.atStart : ProfileVolume.End.FLUSH;
        ProfileVolume.End far = to >= this.path.length() ? this.atFinish : ProfileVolume.End.FLUSH;
        CarveVolume crossing = crossings(level, from);
        CarveVolume shell = TunnelCrossings.lining(new ProfileVolume(part, this.profile,
                TunnelProfile.Kind.LINING, this.floorY, near, far), crossing);
        CarveVolume bore = new ProfileVolume(part, this.profile, TunnelProfile.Kind.BORE,
                this.floorY, near, far);
        CarveVolume cutting = TunnelCrossings.boring(bore, crossing, this.floorY);
        BoundingBox box = new ProfileVolume(part, this.profile, TunnelProfile.Kind.LINING,
                this.floorY, near, far).bounds();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        // Counted before any of it is placed, because the bill for a slice is settled in one go: a
        // machine that ran out of lining halfway through walling one would have to leave the hole it
        // was walling against, and the whole reason the three passes are in this order is that no such
        // hole ever exists at the end of a tick.
        List<BlockPos> wall = new ArrayList<>();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!shell.contains(x, y, z)) {
                        continue;
                    }
                    at.set(x, y, z);
                    BlockState was = level.getBlockState(at);
                    if (was != this.lining && !isRailway(was)) {
                        wall.add(at.immutable());
                    }
                }
            }
        }
        if (!afford(wall.size(), to - from)) {
            return false;
        }
        for (BlockPos cell : wall) {
            level.setBlock(cell, this.lining, LINING_FLAGS);
            this.linedCells++;
        }
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!bore.contains(x, y, z)) {
                        continue;
                    }
                    at.set(x, y, z);
                    if (!level.getBlockState(at).getFluidState().isEmpty()) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
                    }
                }
            }
        }
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (cutting.contains(x, y, z)) {
                        at.set(x, y, z);
                        open(level, at);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Pay for a slice, or refuse it having paid nothing.
     *
     * <p>Track is charged by the block of route rather than by the piece, and the fraction of a block
     * a slice leaves over is carried rather than rounded: a machine cutting eight tenths of a block
     * forty times a second would otherwise be charged one rail per tick and a hundred and sixty blocks
     * of line would cost eight hundred rails.</p>
     *
     * @param blocks how far this slice advances the face
     */
    private boolean afford(int lining, double blocks) {
        if (this.depot == null) {
            this.owedTrack = 0.0;
            return true;
        }
        int rails = (int) (this.owedTrack + blocks);
        if (!this.stores.take(this.bill.lining(), lining)) {
            this.waiting = this.bill.lining();
            return false;
        }
        if (!this.stores.take(this.bill.track(), rails)) {
            // Handed straight back. The wagons had room for it a moment ago, so it goes where it came
            // from rather than being a slice's worth of lining quietly destroyed by a failed purchase.
            this.stores.put(new ItemStack(this.bill.lining(), lining));
            this.waiting = this.bill.track();
            return false;
        }
        this.owedTrack = this.owedTrack + blocks - rails;
        return true;
    }

    private static String name(Item item) {
        return item.getDescription().getString();
    }

    /**
     * The other railways near the face, as they are right now.
     *
     * <p>Held for a band of the drive rather than recomputed per slice, and asked of the machines still
     * running as well as of the build records. A route being dug this second has only reported itself
     * every eight blocks, and eight blocks of somebody's tunnel is exactly the length of wall that
     * makes their line impassable.</p>
     */
    private CarveVolume crossings(ServerLevel level, double from) {
        double band = Math.floor(from / CROSSING_REFRESH);
        if (band != this.sharedBand) {
            this.sharedBand = band;
            this.shared = TunnelCrossings.shared(level, this.routeId, this.meetings, this.profile,
                    this.floorY, onLeg(band * CROSSING_REFRESH),
                    onLeg((band + 1.0) * CROSSING_REFRESH), route -> digging(level, route));
        }
        return this.shared;
    }

    /** What another machine has dug this second, on its route's own survey, or null for a quiet route. */
    private static BuildProgress.Span digging(ServerLevel level, UUID route) {
        BoreTrain other = RailWorks.on(level, route);
        return other == null ? null : other.dugNow();
    }

    /** Take one block out of the face, into the spoil cars. */
    private void open(ServerLevel level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.isAir() || isRailway(state)) {
            // Somebody's track, standing in ground this machine has been told to empty. The geometry
            // already spares the course a crossed line runs on; this is the belt to that brace, and it
            // is what stops a re-drive of a route quietly deleting the railway already in it.
            return;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
            this.broken++;
            return;
        }
        haul(level, at, state);
        level.destroyBlock(at, false);
        // destroyBlock hands back a block's own fluid state, so breaking anything waterlogged leaves
        // water where the tunnel should be and every counter still says it worked.
        if (!level.getBlockState(at).isAir()) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
        }
        this.broken++;
    }

    /**
     * Spoil goes in the hoppers, not on the floor.
     *
     * <p>Six thousand cobbled deepslate as item entities is a tick cost and a lag spike; in the consist
     * it is freight, which is the thing the railway existed for.</p>
     *
     * <p>A bore train is not two wagons long. When the last car fills, another is coupled on behind it,
     * up to the limit in the config, because a fixed pair of cars means a two hundred block tunnel
     * throws half its own spoil away and says so in a footnote. Past the limit it is counted rather
     * than dropped: a machine that stops dead halfway down a tunnel because its bins are full is a
     * worse answer than one that tells you how much it left behind.</p>
     */
    private void haul(ServerLevel level, BlockPos at, BlockState state) {
        for (ItemStack drop : Block.getDrops(state, level, at, level.getBlockEntity(at))) {
            ItemStack stack = drop.copy();
            load(level, stack);
            this.spoilHauled += drop.getCount() - stack.getCount();
            this.spoilLost += stack.getCount();
        }
    }

    /** Fill the train from the front, coupling another car on when the last of them is full. */
    private void load(ServerLevel level, ItemStack stack) {
        while (!stack.isEmpty()) {
            for (Container hold : this.holds) {
                stow(hold, stack);
                if (stack.isEmpty()) {
                    return;
                }
            }
            if (this.holds.size() >= this.maxCars || !couple(level)) {
                return;
            }
        }
    }

    /** Another spoil car on the back of the train, at whatever the tail is standing on now. */
    private boolean couple(ServerLevel level) {
        double[] at = pointAt(Math.max(0.0, this.train - this.consist.size() * CAR_GAP));
        MinecartChest car = new MinecartChest(level, at[0], this.floorY + RIDE_HEIGHT, at[1]);
        add(level, car);
        this.holds.add(car);
        return true;
    }

    private static void stow(Container hold, ItemStack stack) {
        for (int slot = 0; slot < hold.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack in = hold.getItem(slot);
            if (in.isEmpty()) {
                hold.setItem(slot, stack.copyAndClear());
                return;
            }
            if (!ItemStack.isSameItemSameComponents(in, stack)) {
                continue;
            }
            int room = Math.min(in.getMaxStackSize(), hold.getMaxStackSize()) - in.getCount();
            int moved = Math.min(room, stack.getCount());
            if (moved > 0) {
                in.grow(moved);
                stack.shrink(moved);
                hold.setChanged();
            }
        }
    }

    /**
     * Whether a block is somebody's railway.
     *
     * <p>By namespace rather than by class, because Immersive Railroading is a soft dependency and
     * naming one of its blocks here would stop this class loading without it.</p>
     *
     * <p>Asked of the lining pass as well as the bore, and that is not belt and braces. A sleeper
     * replaced by a brick is not a hole in someone's railway, it is the <em>whole piece</em> gone: IR
     * anchors each piece on one block and removes every sleeper of a piece it can no longer keep, so
     * a portal plugged across a main line takes out the two pieces either side of the junction and
     * reports nothing. That cost a live run to find.</p>
     */
    private static boolean isRailway(BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.RAILS)) {
            return true;
        }
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && com.wf.wflib.rail.RailCompat.MODID.equals(id.getNamespace());
    }

    private void lightUp(ServerLevel level, double reach) {
        while (this.torchCursor < this.torches.size()
                && this.torches.get(this.torchCursor).chainage() <= reach) {
            BlockPos at = this.torches.get(this.torchCursor).at();
            if (level.isLoaded(at) && level.getBlockState(at).isAir()) {
                // A torch is charged for like everything else, but running out of them does not send
                // the train home. An unlit tunnel is a tunnel; an unwalled one is a flood, and a cycle
                // that turned back for a torch could do it forever against a depot that has none.
                if (this.depot == null || this.stores.take(this.bill.torch(), 1)) {
                    level.setBlock(at, Blocks.TORCH.defaultBlockState(), LINING_FLAGS);
                    this.lightsPlaced++;
                } else {
                    this.torchesShort++;
                }
            }
            this.torchCursor++;
        }
    }

    /** Put the consist where the machine says it is. */
    private void ride(ServerLevel level) {
        double head = Math.max(0.0, this.train);
        for (int i = 0; i < this.consist.size(); i++) {
            AbstractMinecart cart = this.consist.get(i);
            if (!cart.isAlive()) {
                continue;
            }
            double[] at = pointAt(Math.max(0.0, head - i * CAR_GAP));
            cart.setDeltaMovement(Vec3.ZERO);
            cart.moveTo(at[0], this.floorY + RIDE_HEIGHT, at[1], yawOf(at), 0.0f);
        }
    }

    /** Minecraft's yaw for a heading: zero looking along +z, turning towards -x. */
    private static float yawOf(double[] at) {
        return (float) (-Math.atan2(at[2], at[3]) * 180.0 / Math.PI);
    }

    // ---------------------------------------------------------------- territory

    /**
     * Whether the machine may cut this slice.
     *
     * <p>Asked of WarForge once per chunk, as the face reaches it, rather than taken from the survey at
     * launch. A drive is minutes long and a claim can be planted in the middle of one; the survey is the
     * warning and this is the rule.</p>
     */
    private boolean mayDig(ServerLevel level, BoundingBox box) {
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                if (!this.cleared.add(chunk.toLong())) {
                    continue;
                }
                TerritoryVerdict verdict =
                        TunnelTerritory.verdict(level, this.faction, chunk, this.floorY);
                if (!verdict.allowed()) {
                    halt(level, "chunk " + cx + ", " + cz + ": " + verdict.reason());
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- finishing

    /** The last leg is dug to the end: seal the far portal and hand the train over. */
    private void finish(ServerLevel level) {
        this.finished = true;
        cap(level, this.path.length());
        release();
        AlignmentService.pushAll(level);
        LOGGER.info("[wflib] bore train finished {}: {}", this.routeName, summary());
    }

    /**
     * Stop where it stands. The tunnel behind it is real and stays reported as built.
     *
     * <p>Said out loud, because a work train is dispatched by somebody who then goes and does
     * something else. One that stopped quietly at the depot is indistinguishable from one still
     * working a mile down the tunnel, and the difference is whether the railway is ever finished.</p>
     */
    public void halt(ServerLevel level, String why) {
        this.halted = why;
        say(jobName() + ": stopped - " + why);
        release();
        reportBuilt(level, true);
        AlignmentService.pushAll(level);
    }

    /** Hand the consist back to the world as an ordinary train sitting on its new line. */
    private void release() {
        for (AbstractMinecart cart : this.consist) {
            cart.setNoGravity(false);
            cart.setInvulnerable(false);
        }
    }

    /**
     * Wall off one end of the route.
     *
     * <p>The section swept past the end of a route is all lining, tunnel cells included, which is what
     * stops a bore that ends inside an aquifer filling from its own face. Both ends get one: the portal
     * behind the machine as it sets off, and the one in front of it when it stops.</p>
     */
    private void cap(ServerLevel level, double chainage) {
        double[] at = this.path.pointAt(chainage);
        // Worked out here rather than at launch, because the far portal is walled minutes later and by
        // then another line may be running through the ground it would otherwise wall off.
        CarveVolume portal = TunnelCrossings.lining(
                TunnelBuilder.sweep(this.path, this.profile, TunnelProfile.Kind.LINING, this.floorY,
                        this.meetings, this.routeId),
                TunnelCrossings.shared(level, this.routeId, this.meetings, this.profile, this.floorY,
                        onLeg(chainage), onLeg(chainage), route -> digging(level, route)));
        int reach = this.profile.width() / 2 + 3;
        BlockPos centre = BlockPos.containing(at[0], this.floorY, at[1]);
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int y = this.floorY + this.profile.lowestUp(); y <= this.floorY + this.profile.highestUp(); y++) {
            for (int z = centre.getZ() - reach; z <= centre.getZ() + reach; z++) {
                for (int x = centre.getX() - reach; x <= centre.getX() + reach; x++) {
                    if (!portal.contains(x, y, z)) {
                        continue;
                    }
                    cell.set(x, y, z);
                    if (!level.isLoaded(cell)) {
                        continue;
                    }
                    BlockState was = level.getBlockState(cell);
                    if (was != this.lining && !isRailway(was)) {
                        level.setBlock(cell, this.lining, LINING_FLAGS);
                        this.linedCells++;
                    }
                }
            }
        }
    }

    /** Tell the route how much of it is now on the ground. */
    private void reportBuilt(ServerLevel level, boolean force) {
        if (!force && this.face - this.reported < REPORT_STEP) {
            return;
        }
        if (this.face <= this.reported) {
            return;
        }
        AlignmentStore store = AlignmentStore.of(level);
        Alignment current = store.get(this.routeId);
        if (current == null) {
            this.halted = "the route was deleted while it was being built";
            return;
        }
        store.put(AlignmentEdits.applyBuild(current, onSurvey(this.reported), onSurvey(this.face), true,
                this.surveyLength));
        this.reported = this.face;
    }

    /** A distance along the corridor, as a distance along the leg it was cut from. */
    private double onLeg(double chainage) {
        double length = this.path.length();
        return length <= 0.0 ? 0.0 : chainage / length * this.routeLength;
    }

    /**
     * A distance along the corridor, as a chainage on the route's own survey.
     *
     * <p>The one place a leg admits to being part of something bigger. A build record is written
     * against the route as it was drawn, so a leg that runs backwards from halfway along reports the
     * chainages the surveyor would recognise and not the ones the machine has been counting.</p>
     */
    private double onSurvey(double chainage) {
        return this.leg.onRoute(onLeg(chainage));
    }

    // ---------------------------------------------------------------- reading it back

    public UUID routeId() {
        return this.routeId;
    }

    public String routeName() {
        return this.routeName;
    }

    public double face() {
        return this.face;
    }

    public double length() {
        return this.path.length();
    }


    /**
     * What this machine has dug of the route it is on, as a span on that route's own survey.
     *
     * <p>Not the same number as {@link #face()}, which is along the corridor, and not a distance from
     * the route's start either: a leg may run backwards, or begin in the middle. This is the one
     * another machine needs, because a crossing is a chainage on a surveyed line.</p>
     */
    public BuildProgress.Span dugNow() {
        return this.leg == null ? null : this.leg.dug(onLeg(this.face));
    }

    /** Every route this journey will touch, which is what stops two machines being given one line. */
    public List<UUID> routes() {
        List<UUID> out = new ArrayList<>();
        for (Leg leg : this.legs) {
            if (!out.contains(leg.route())) {
                out.add(leg.route());
            }
        }
        return out;
    }


    public boolean running() {
        return !this.finished && this.halted == null;
    }

    /** @return why the machine stopped early, or null when it did not. */
    public String halted() {
        return this.halted;
    }

    public String profileName() {
        return this.profile.name();
    }

    public String lining() {
        return this.liningName;
    }

    /** @return what kind of railway this machine is laying, which depends on what is installed. */
    public String trackKind() {
        return this.line.kind();
    }

    public int floorY() {
        return this.floorY;
    }

    /** One line of what this machine has done so far, meant to be read in chat. */
    public String summary() {
        double cut = this.behind + this.face;
        double whole = journey();
        return String.format(Locale.ROOT,
                "%.0f of %.0f blocks (%.0f%%)%s, %s, %d blocks cut, %d lining, %d torches,"
                        + " %d spoil in %d car(s)%s%s%s",
                cut, whole, whole <= 0 ? 0.0 : cut / whole * 100.0,
                this.legs.size() > 1
                        ? String.format(Locale.ROOT, " over %d legs, on %s", this.legs.size(),
                                this.routeName)
                        : "",
                this.line.describe(), this.broken, this.linedCells, this.lightsPlaced, this.spoilHauled,
                this.holds.size(),
                this.spoilLost > 0 ? ", " + this.spoilLost + " left behind (all " + this.maxCars
                        + " cars full)" : "",
                this.trips > 0 ? ", " + this.trips + " trip(s) to the depot" : "",
                this.torchesShort > 0 ? ", " + this.torchesShort + " left dark" : "");
    }

    /** What the train is doing: at the face, or somewhere on the way to or from the depot. */
    public Stage stage() {
        return this.stage;
    }

    /** @return whether this machine is paying for what it builds. */
    public boolean paying() {
        return this.depot != null;
    }

    public int trips() {
        return this.trips;
    }

    /** One line of where the train is and what it is doing, for a player who dispatched it. */
    public String doing() {
        return switch (this.stage) {
            case WORKING -> String.format(Locale.ROOT, "cutting %s at %.0f of %.0f", this.routeName,
                    onLeg(this.face), this.routeLength);
            case INBOUND -> String.format(Locale.ROOT, "running back for %s, %.0f from the depot",
                    this.waiting == null ? "materials" : name(this.waiting), this.train);
            case LOADING -> "loading at the depot";
            case OUTBOUND -> String.format(Locale.ROOT, "running out to the railhead, %.0f of %.0f",
                    this.train, railhead());
        };
    }

    /** The ground one slice of the drive will change, which is what has to be loaded and permitted. */
    private BoundingBox workBox(double from, double to) {
        CarveVolume.Corridor part = TunnelBuilder.corridor(this.centreline, from,
                Math.max(to, from + 0.01), this.profile, this.floorY);
        return new ProfileVolume(part, this.profile, TunnelProfile.Kind.LINING, this.floorY, false)
                .bounds();
    }
}
