package com.wf.wfballistics.drone;

import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneAiScheduler;
import com.wf.wfballistics.drone.ai.DroneBrain;
import com.wf.wfballistics.drone.ai.DroneCarrier;
import com.wf.wfballistics.drone.ai.DroneNav;
import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import com.wf.wfballistics.drone.ai.SquadPlan;
import com.wf.wfballistics.drone.ai.coord.Consensus;
import com.wf.wfballistics.drone.ai.coord.CoordinationModel;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.ai.coord.Flocking;
import com.wf.wfballistics.drone.ai.coord.LeaderFollower;
import com.wf.wfballistics.drone.ai.coord.Slots;
import com.wf.wfballistics.drone.ai.coord.SquadAnchor;
import com.wf.wfballistics.drone.ai.coord.SquadCommand;
import com.wf.wfballistics.drone.ai.coord.VirtualStructure;
import com.wf.wfballistics.drone.ai.state.MusterHandler;
import com.wf.wfballistics.drone.ai.state.TakeoffHandler;
import com.wf.wfballistics.drone.ai.state.Cruising;
import com.wf.wfballistics.drone.ai.state.PayloadRunHandler;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.Contacts;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.drone.flight.Multirotor;
import com.wf.wfballistics.drone.nav.DroneNavigation;
import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.nav.PathPlanner;
import com.wf.wfballistics.drone.nav.TerrainCache;
import com.wf.wfballistics.drone.nav.TerrainField;
import com.wf.wfballistics.drone.nav.TerrainGuard;
import com.wf.wfballistics.exchange.ExchangeMode;
import com.wf.wfballistics.exchange.Obfuscation;
import com.wf.wfballistics.exchange.StationCode;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.fire.FireType;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-game checks for the parts of the drone system that are pure functions: the battery policy, the
 * ballistics, the formations, the state machine, and NBT round-trips.
 *
 * <p>All of it is exercised without a world, which is the point: the AI's decision half deliberately holds no
 * world references, so it can be driven from synthetic snapshots and asserted on directly. Run it with
 * {@code /wfballistics drone selftest} after changing tuning values or handlers.
 */
public final class DroneSelfTest {

    /**
     * The spacing these checks are written against. Named rather than defaulted so the geometry
     * assertions keep meaning what they say if the shipped default is retuned.
     */
    private static final double SPACING = Formation.DEFAULT_SPACING;

    private DroneSelfTest() {
    }

    public static List<Result> runAll() {
        List<Result> results = new ArrayList<>();
        battery(results);
        ballistics(results);
        kinematics(results);
        navigation(results);
        threading(results);
        exchange(results);
        formations(results);
        formationFlight(results);
        coordination(results);
        muster(results);
        slotTracking(results);
        squadSafety(results);
        contacts(results);
        breakOff(results);
        stateMachine(results);
        persistence(results);
        programs(results);
        succession(results);
        warheads(results);
        fire(results);
        results.addAll(com.wf.wfballistics.build.BuildSelfTest.runAll());
        return results;
    }

    /**
     * The multirotor physics. These assert the <em>behaviour</em> the model is supposed to produce (that it
     * leans to accelerate, cannot snap its attitude, tops out, and costs more to fly loaded) rather than
     * pinning down numbers that would have to be edited every time the airframe is tuned.
     */
    private static void kinematics(List<Result> out) {
        Airframe frame = Airframe.QUADCOPTER;

        Multirotor.Step hover = Multirotor.step(Vec3.ZERO, FlightAttitude.LEVEL, Vec3.ZERO, frame, 1.0);
        out.add(check("flight/hover-is-level", hover.attitude().tilt() < 1.0E-6,
                "a drone asked to hold station should not lean"));
        out.add(check("flight/hover-throttle-is-one", Math.abs(hover.attitude().throttle() - 1.0) < 1.0E-6,
                "hovering should take exactly hover throttle, got " + hover.attitude().throttle()));
        out.add(check("flight/hover-holds-altitude", Math.abs(hover.velocity().y) < 1.0E-6,
                "a hovering drone should be neither climbing nor sinking"));

        Multirotor.Step push = Multirotor.step(Vec3.ZERO, FlightAttitude.LEVEL, new Vec3(0.75, 0.0, 0.0),
                frame, 1.0);
        out.add(check("flight/leans-into-acceleration", push.attitude().tiltX() > 0.0,
                "a drone asked to fly +X should lean +X, got tiltX " + push.attitude().tiltX()));
        out.add(check("flight/lean-is-rate-limited", push.attitude().tilt() <= frame.tiltRate() + 1.0E-9,
                "the attitude moved " + push.attitude().tilt() + " rad in one tick, limit " + frame.tiltRate()));
        out.add(check("flight/lean-within-limit", push.attitude().tilt() <= frame.maxTilt() + 1.0E-9,
                "the airframe leaned past its own limit"));

        Flight cruise = fly(frame, new Vec3(0.75, 0.0, 0.0), 1.0, 300);
        out.add(check("flight/reaches-commanded-speed",
                Math.abs(cruise.velocity.horizontalDistance() - 0.75) < 0.05,
                "asked for 0.75 b/t, settled at " + cruise.velocity.horizontalDistance()));
        out.add(check("flight/cruise-tilt-matches-prediction",
                Math.abs(cruise.attitude.tilt() - Multirotor.cruiseTilt(frame, 0.75)) < 0.02,
                "simulated lean " + cruise.attitude.tilt() + " vs predicted "
                        + Multirotor.cruiseTilt(frame, 0.75)));
        out.add(check("flight/cruise-costs-more-than-hover", cruise.attitude.throttle() > 1.0,
                "leaning over spends lift, so cruising must take more than hover throttle"));

        Flight flatOut = fly(frame, new Vec3(99.0, 0.0, 0.0), 1.0, 600);
        double top = frame.topSpeed(1.0);
        out.add(check("flight/top-speed-is-finite", flatOut.velocity.horizontalDistance() < top + 0.1,
                "flat out reached " + flatOut.velocity.horizontalDistance() + ", predicted ceiling " + top));
        out.add(check("flight/top-speed-covers-attack-run", top > DroneEntity.DEFAULT_RELEASE_SPEED,
                "the airframe tops out at " + top + " but attack runs are flown at "
                        + DroneEntity.DEFAULT_RELEASE_SPEED));

        Multirotor.Step climb = Multirotor.step(Vec3.ZERO, FlightAttitude.LEVEL, new Vec3(0.0, 0.35, 0.0),
                frame, 1.0);
        out.add(check("flight/climb-needs-throttle", climb.attitude().throttle() > 1.0,
                "climbing should take more than hover throttle, got " + climb.attitude().throttle()));
        Multirotor.Step sink = Multirotor.step(Vec3.ZERO, FlightAttitude.LEVEL, new Vec3(0.0, -0.35, 0.0),
                frame, 1.0);
        out.add(check("flight/descent-eases-off", sink.attitude().throttle() < 1.0,
                "descending should take less than hover throttle, got " + sink.attitude().throttle()));

        Flight emptyClimb = fly(frame, new Vec3(0.0, 9.0, 0.0), 1.0, 6);
        Flight ladenClimb = fly(frame, new Vec3(0.0, 9.0, 0.0), PowerProfile.DEFAULT.massFactor(true), 6);
        out.add(check("flight/cargo-climbs-slower", ladenClimb.velocity.y < emptyClimb.velocity.y,
                "a laden drone should climb slower: laden " + ladenClimb.velocity.y
                        + " vs empty " + emptyClimb.velocity.y));

        Vec3 spot = new Vec3(100.0, 100.0, 100.0);
        out.add(check("flight/hold-commands-a-stop",
                Steering.hold(spot, spot, 100.0, 0.3, 0.35, frame).horizontalDistance() < 1.0E-9,
                "a drone already over the point should be told to stop, not to keep moving"));
        double far = Steering.hold(new Vec3(96.0, 100.0, 100.0), spot, 100.0, 0.3, 0.35, frame)
                .horizontalDistance();
        double near = Steering.hold(new Vec3(99.0, 100.0, 100.0), spot, 100.0, 0.3, 0.35, frame)
                .horizontalDistance();
        out.add(check("flight/hold-eases-in", far > near && near > 0.0,
                "the approach should slow with the offset: 4 blocks out " + far + ", 1 block out " + near));
        out.add(check("flight/hold-respects-limit",
                Steering.hold(new Vec3(0.0, 100.0, 100.0), spot, 100.0, 0.3, 0.35, frame).horizontalDistance()
                        <= 0.3 + 1.0E-9,
                "the approach exceeded the speed it was capped at"));
        boolean stoppable = true;
        for (double d = 0.25; d <= 30.0; d += 0.25) {
            double commanded = Steering.hold(new Vec3(100.0 - d, 100.0, 100.0), spot, 100.0, 5.0, 0.35, frame)
                    .horizontalDistance();
            if (frame.stoppingDistance(commanded) > d + 1.0E-6) {
                stoppable = false;
            }
        }
        out.add(check("flight/hold-never-outruns-its-brakes", stoppable,
                "the approach profile commands a speed the airframe could not stop from in time"));

        Vec3 position = new Vec3(100.0 - Tuning.DELIVER_ENTRY_RADIUS, 100.0, 100.0);
        Vec3 velocity = new Vec3(DroneEntity.DEFAULT_CRUISE_SPEED, 0.0, 0.0);
        FlightAttitude held = FlightAttitude.LEVEL;
        for (int i = 0; i < 400; i++) {
            Vec3 want = Steering.hold(position, spot, 100.0, DroneEntity.DEFAULT_CRUISE_SPEED * 0.4, 0.35,
                    frame);
            Multirotor.Step step = Multirotor.step(velocity, held, want, frame, 1.0);
            velocity = step.velocity();
            held = step.attitude();
            position = position.add(velocity);
        }
        double offset = Math.hypot(position.x - spot.x, position.z - spot.z);
        out.add(check("flight/settles-over-the-point", offset < 0.5 && velocity.horizontalDistance() < 0.05,
                String.format("began the approach at the handover radius doing cruise speed and ended %.2f "
                        + "blocks off, still drifting %.3f b/t", offset, velocity.horizontalDistance())));

        out.add(check("flight/approach-starts-far-enough-out",
                Tuning.DELIVER_ENTRY_RADIUS >= frame.stoppingDistance(DroneEntity.DEFAULT_CRUISE_SPEED),
                String.format("delivery begins %.0f blocks out but stopping from cruise takes %.0f",
                        Tuning.DELIVER_ENTRY_RADIUS,
                        frame.stoppingDistance(DroneEntity.DEFAULT_CRUISE_SPEED))));
        out.add(check("flight/settled-drone-may-release", velocity.horizontalDistance() <= Tuning.RELEASE_DRIFT,
                "a settled drone still drifts faster than RELEASE_DRIFT allows, so it would never let go"));

        out.add(check("flight/rotors-follow-throttle",
                frame.rotorScale(1.6, 1.0) > frame.rotorScale(1.0, 1.0)
                        && frame.rotorScale(0.0, 1.0) == 0.0,
                "rotor speed should rise with throttle and stop at zero"));
        out.add(check("flight/rotor-speed-does-not-strobe",
                Math.abs(frame.hoverRotorSpeed() % 90.0) > 5.0
                        && Math.abs(frame.hoverRotorSpeed() % 90.0) < 85.0,
                "hoverRotorSpeed " + frame.hoverRotorSpeed()
                        + " deg/tick is too close to the prop's 90 degree symmetry and will appear frozen"));
    }

    /**
     * Fly the model for a while and report where it ended up.
     */
    private static Flight fly(Airframe frame, Vec3 desired, double massFactor, int ticks) {
        Vec3 velocity = Vec3.ZERO;
        FlightAttitude attitude = FlightAttitude.LEVEL;
        for (int i = 0; i < ticks; i++) {
            Multirotor.Step step = Multirotor.step(velocity, attitude, desired, frame, massFactor);
            velocity = step.velocity();
            attitude = step.attitude();
        }
        return new Flight(velocity, attitude);
    }

    private record Flight(Vec3 velocity, FlightAttitude attitude) {
    }

    private static void navigation(List<Result> out) {
        Vec3 level = new Vec3(0.75, 0.0, 0.0);
        Vec3 clear = TerrainGuard.enforce(level, 100.0, 60.0, 0.35);
        out.add(check("nav/guard-leaves-clear-flight-alone", clear.equals(level),
                "a drone already above the floor should not be touched"));

        Vec3 low = TerrainGuard.enforce(level, 100.0, 108.0, 0.35);
        out.add(check("nav/guard-climbs-when-low", low.y > 0.35,
                "a drone below the floor should climb harder than its nominal rate, got " + low.y));
        out.add(check("nav/guard-trades-speed-for-climb", low.x < level.x,
                "climbing to clear terrain should cost ground speed, got " + low.x));
        out.add(check("nav/guard-climb-is-bounded",
                low.y <= 0.35 * (1.0 + TerrainGuard.CLIMB_BOOST) + 1.0E-9,
                "the emergency climb exceeded its own boost limit"));

        TerrainField flat = field(64, 64, 64, -1, -1, 0);
        DronePath straight = PathPlanner.plan(flat, new Vec3(10, 110, 10), new Vec3(240, 110, 240),
                40.0, 220.0, 0L);
        out.add(check("nav/flat-route-is-direct", straight.waypoints().size() <= 3,
                "flat terrain should not need corners, got " + straight.waypoints().size() + " waypoints"));
        out.add(check("nav/route-holds-cruise-height",
                straight.waypoints().stream().allMatch(w -> Math.abs(w.y - (64 + 40)) < 1.0E-6),
                "a route over flat ground should sit exactly one cruise altitude above it"));

        TerrainField walled = field(64, 64, 64, 32, 200, 5);
        DronePath around = PathPlanner.plan(walled, new Vec3(66, 110, 34), new Vec3(66, 110, 226),
                40.0, 200.0, 0L);
        double highest = around.waypoints().stream().mapToDouble(w -> w.y).max().orElse(0.0);
        out.add(check("nav/routes-around-impassable-terrain", highest < 200.0,
                "the route climbed to " + highest + ", which means it went over a wall it cannot clear"));
        double gapX = walled.worldX(42);
        boolean usedGap = around.waypoints().stream().anyMatch(w -> Math.abs(w.x - gapX) < 40.0);
        out.add(check("nav/finds-the-gap", usedGap,
                "the route never went near the only opening in the wall"));
        out.add(check("nav/route-reaches-goal",
                Math.abs(around.end().z - 226.0) < 16.0,
                "the route stopped at z " + around.end().z + " instead of reaching the goal"));

        DronePath leg = PathPlanner.plan(flat, new Vec3(10, 110, 10), new Vec3(9000, 110, 9000),
                40.0, 220.0, 0L);
        out.add(check("nav/distant-goal-is-partial", leg.partial(),
                "a goal beyond the planning horizon should come back as a partial route"));
        out.add(check("nav/partial-route-still-moves", !leg.isEmpty(),
                "a partial route must still give the drone somewhere to go"));

        DronePath line = new DronePath(List.of(new Vec3(0, 100, 0), new Vec3(200, 100, 0)),
                new Vec3(200, 100, 0), 0L, false);
        Vec3 carrot = line.carrot(new Vec3(50, 100, 0));
        out.add(check("nav/carrot-leads-the-drone",
                Math.abs(carrot.x - (50.0 + DronePath.LOOKAHEAD)) < 1.0E-6,
                "the aim point should sit one lookahead ahead, got x " + carrot.x));
        out.add(check("nav/carrot-stops-at-the-end",
                Math.abs(line.carrot(new Vec3(195, 100, 0)).x - 200.0) < 1.0E-6,
                "near the end the aim point should be the end, not past it"));
        out.add(check("nav/deviation-measured-off-route",
                Math.abs(line.deviation(new Vec3(50, 100, 30)) - 30.0) < 1.0E-6,
                "a drone 30 blocks to the side should read as 30 off route"));

        out.add(check("nav/stale-when-goal-moves",
                line.stale(new Vec3(50, 100, 0), new Vec3(-500, 100, 0), 10L),
                "a route should go stale when the destination changes"));
        out.add(check("nav/fresh-while-on-route",
                !line.stale(new Vec3(50, 100, 0), new Vec3(200, 100, 0), 10L),
                "a route still being flown to the same place should not be replanned"));
        out.add(check("nav/stale-when-old",
                line.stale(new Vec3(50, 100, 0), new Vec3(200, 100, 0), DronePath.MAX_AGE + 10L),
                "a route should eventually be replanned even if nothing changed"));

        out.add(check("nav/goal-follows-the-leg",
                DroneNavigation.goal(DroneState.TRANSIT, null, at(100, 0), at(0, 0)).x == 100.0
                        && DroneNavigation.goal(DroneState.EXFIL, null, at(100, 0), at(0, 0)).x == 0.0
                        && DroneNavigation.goal(DroneState.IDLE, null, at(100, 0), at(0, 0)) == null,
                "outbound should route to the destination, homebound to the exfil, parked to nowhere"));

        DroneSnapshot flying = snapshot(DroneState.TRANSIT, DroneBattery.DEFAULT_CAPACITY,
                at(0, 0), at(400, 0), false).withVelocity(new Vec3(0.75, 0.0, 0.0));
        DronePlan clearPlan = DroneBrain.think(flying, SquadView.solo(flying));
        DroneSnapshot blocked = flying.withNav(new DroneNav(null, null, flying.pos().y + 20.0));
        DronePlan climbPlan = DroneBrain.think(blocked, SquadView.solo(blocked));
        out.add(check("nav/climbs-over-rising-ground", climbPlan.velocity().y > clearPlan.velocity().y,
                "a drone with terrain ahead should be climbing harder than one without"));
        out.add(check("nav/climb-opens-the-throttle",
                climbPlan.attitude().throttle() > clearPlan.attitude().throttle(),
                "clearing terrain should cost throttle, and therefore battery"));
    }

    /**
     * A synthetic height field: flat at {@code groundTop}, optionally with a wall running across it at one
     * cell row, broken by a gap.
     *
     * @param wallZ    cell row the wall sits on, or -1 for none
     * @param gapCells how many cells wide the opening is
     */
    private static TerrainField field(int width, int depth, int groundTop, int wallZ, int wallTop,
                                      int gapCells) {
        short[] tops = new short[width * depth];
        int gapStart = width * 2 / 3;
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                boolean inWall = cz == wallZ && !(cx >= gapStart && cx < gapStart + gapCells);
                tops[cz * width + cx] = (short) (inWall ? wallTop : groundTop);
            }
        }
        return new TerrainField(0, 0, TerrainCache.CELL, width, depth, tops, groundTop);
    }

    private static void battery(List<Result> out) {
        double capacity = DroneBattery.DEFAULT_CAPACITY;
        out.add(check("battery/full-mission",
                PowerPolicy.override(snapshot(DroneState.TRANSIT, capacity, at(0, 0), at(200, 0), true)) == null,
                "a charged drone near its destination should not be overruled"));

        DroneSnapshot oneWay = snapshot(DroneState.TRANSIT, 600.0, at(0, 0), at(200, 0), true);
        out.add(check("battery/one-way-still-delivers", PowerPolicy.override(oneWay) == null,
                "a drone that can reach the destination should keep going even if it can't get home"));
        out.add(check("battery/one-way-detected", !PowerPolicy.canReturnAfterDelivery(oneWay),
                "the same drone should know the return leg is unaffordable"));

        DroneSnapshot abort = snapshot(DroneState.TRANSIT, 260.0, at(0, 0), at(600, 0), true)
                .withExfil(at(30, 0));
        out.add(check("battery/abort-returns", PowerPolicy.override(abort) == DroneState.EXFIL,
                "a drone that can't deliver but can get home should abort to EXFIL"));

        DroneSnapshot land = snapshot(DroneState.TRANSIT, 130.0, at(0, 0), at(4000, 0), true)
                .withExfil(at(-4000, 0));
        out.add(check("battery/strand-lands", PowerPolicy.override(land) == DroneState.LANDING,
                "a drone that can reach neither should land where it is"));

        out.add(check("battery/flat-goes-depleted",
                PowerPolicy.override(snapshot(DroneState.TRANSIT, 0.0, at(0, 0), at(50, 0), true))
                        == DroneState.DEPLETED,
                "a flat battery should drop the drone into DEPLETED"));
        out.add(check("battery/flat-on-ground-stays",
                PowerPolicy.override(snapshot(DroneState.IDLE, 0.0, at(0, 0), at(50, 0), true)) == null,
                "a parked drone with a flat battery should stay parked, not enter DEPLETED"));

        out.add(check("battery/downed-not-overruled",
                PowerPolicy.override(snapshot(DroneState.DOWNED, 0.0, at(0, 0), at(50, 0), true)) == null,
                "a shot-down drone should never be overruled by the battery policy"));

        Airframe frame = Airframe.QUADCOPTER;
        double loaded = PowerProfile.DEFAULT.costToTravel(frame, 100.0, 0.75, true);
        double empty = PowerProfile.DEFAULT.costToTravel(frame, 100.0, 0.75, false);
        out.add(check("battery/cargo-costs-more", loaded > empty,
                "carrying a crate should cost more charge per block"));

        double crawl = PowerProfile.DEFAULT.rangeFor(frame, 1000.0, 0.2, false);
        double cruise = PowerProfile.DEFAULT.rangeFor(frame, 1000.0, 0.75, false);
        double sprint = PowerProfile.DEFAULT.rangeFor(frame, 1000.0, 1.3, false);
        out.add(check("battery/cruise-is-most-efficient", cruise > crawl && cruise > sprint,
                String.format("range should peak near cruise, got crawl %.0f, cruise %.0f, sprint %.0f",
                        crawl, cruise, sprint)));

        double hover = PowerProfile.DEFAULT.drain(DroneState.DELIVER, 1.0, 1.0, 0.0);
        out.add(check("battery/hover-is-reference", Math.abs(hover - PowerProfile.DEFAULT.hoverDrain()) < 1.0E-9,
                "an unladen hover at throttle 1.0 should cost exactly hoverDrain, got " + hover));

        out.add(check("battery/throttle-costs",
                PowerProfile.DEFAULT.drain(DroneState.TAKEOFF, 1.6, 1.0, 0.0)
                        > PowerProfile.DEFAULT.drain(DroneState.TAKEOFF, 1.0, 1.0, 0.0),
                "climbing at more than hover throttle should drain faster than hovering"));

        double ladenHover = PowerProfile.DEFAULT.drain(DroneState.DELIVER, 1.0,
                PowerProfile.DEFAULT.massFactor(true), 0.0);
        out.add(check("battery/cargo-mass-matches-multiplier",
                Math.abs(ladenHover - hover * PowerProfile.DEFAULT.cargoMultiplier()) < 1.0E-6,
                "hovering laden should cost cargoMultiplier times hovering empty, got "
                        + ladenHover + " vs " + hover * PowerProfile.DEFAULT.cargoMultiplier()));
    }

    private static void ballistics(List<Result> out) {
        double g = Tuning.PAYLOAD_GRAVITY;
        out.add(check("ballistics/zero-height-no-lead", Steering.ballisticLead(0.0, 0.0, 1.2, g) == 0.0,
                "a payload released at ground level should have no lead"));
        out.add(check("ballistics/higher-is-further",
                Steering.ballisticLead(80.0, 0.0, 1.2, g) > Steering.ballisticLead(40.0, 0.0, 1.2, g),
                "dropping from higher should throw the payload further"));
        out.add(check("ballistics/faster-is-further",
                Steering.ballisticLead(40.0, 0.0, 2.0, g) > Steering.ballisticLead(40.0, 0.0, 1.0, g),
                "dropping faster should throw the payload further"));
        out.add(check("ballistics/climbing-throws-further",
                Steering.ballisticLead(40.0, 0.3, 1.2, g) > Steering.ballisticLead(40.0, -0.3, 1.2, g),
                "a climbing drone's payload should stay up longer than a diving one's"));

        double lead = Steering.ballisticLead(40.0, 0.0, 1.2, g);
        out.add(check("ballistics/plausible-magnitude", lead > 20.0 && lead < 100.0,
                "lead from 40 blocks at 1.2 b/t was " + (int) lead + "m, expected 20-100m"));

        out.add(check("ballistics/run-in-covers-lead", Tuning.PAYLOAD_RUN_IN > lead,
                "PAYLOAD_RUN_IN must exceed the release lead or the run starts too late"));
    }

    private static void formations(List<Result> out) {
        Vec3 leader = new Vec3(0.0, 100.0, 0.0);
        Vec3 forward = new Vec3(0.0, 0.0, 1.0);
        for (ResourceLocation id : Formations.ids()) {
            Formation formation = Formations.get(id);
            out.add(check("formation/" + id.getPath() + "/leader-at-origin",
                    formation.slot(0, leader, forward, SPACING).distanceTo(leader) < 1.0E-6,
                    "slot 0 must be the leader's own position"));

            boolean distinct = true;
            List<Vec3> slots = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Vec3 slot = formation.slot(i, leader, forward, SPACING);
                for (Vec3 other : slots) {
                    if (other.distanceTo(slot) < 1.0E-3) {
                        distinct = false;
                    }
                }
                slots.add(slot);
            }
            out.add(check("formation/" + id.getPath() + "/slots-distinct", distinct,
                    "two drones would be assigned the same slot"));
        }

        Vec3 left = Formations.VEE.slot(1, leader, forward, SPACING);
        Vec3 right = Formations.VEE.slot(2, leader, forward, SPACING);
        out.add(check("formation/vee/alternates-sides", left.x * right.x < 0.0,
                "slots 1 and 2 of the wedge should sit on opposite sides of the leader"));
        out.add(check("formation/vee/trails-behind", left.z < leader.z && right.z < leader.z,
                "wedge followers should trail the leader, not lead it"));

        List<Vec3> block = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            block.add(Formations.GRID.slot(i, leader, forward, SPACING));
        }
        Vec3 sum = Vec3.ZERO;
        for (Vec3 slot : block) {
            sum = sum.add(slot);
        }
        out.add(check("formation/grid/leader-centred", sum.scale(1.0 / block.size()).distanceTo(leader) < 1.0E-6,
                "the leader should sit at the centre of mass of a closed grid"));

        boolean filled = true;
        for (int lateral = -1; lateral <= 1; lateral++) {
            for (int along = -1; along <= 1; along++) {
                Vec3 cell = leader.add(Formation.right(forward).scale(SPACING * lateral))
                        .add(forward.scale(SPACING * along));
                boolean held = false;
                for (Vec3 slot : block) {
                    if (slot.distanceTo(cell) < 1.0E-3) {
                        held = true;
                    }
                }
                if (!held) {
                    filled = false;
                }
            }
        }
        out.add(check("formation/grid/closes-a-square", filled,
                "nine drones should hold every cell of the 3x3 around the leader"));
    }

    /**
     * That the expensive half really does run off the server thread.
     *
     * <p>This test is worth more than it looks. The off-thread split is the property the whole design rests
     * on, and it is the kind that quietly stops being true: someone moves a call, and everything still works
     * until the server is under load. Because this runs from a command, it runs <em>on the world thread</em>,
     * so it can prove the guards are armed by tripping them deliberately.
     */
    private static void threading(List<Result> out) {
        out.add(check("threading/guards-are-armed", WorldThread.armed(),
                "the world thread was never recorded, so nothing is being enforced"));
        out.add(check("threading/selftest-runs-on-world-thread", WorldThread.isWorldThread(),
                "this test only proves anything if it is itself on the world thread, and it is not"));

        DroneSnapshot any = snapshot(DroneState.TRANSIT, DroneBattery.DEFAULT_CAPACITY, at(0, 0), at(300, 0),
                false);
        out.add(check("threading/path-search-refuses-world-thread", refuses(() -> DroneBrain.planRoute(any)),
                "the A* ran on the server thread instead of refusing - the off-thread guarantee is gone"));
        out.add(check("threading/squad-planning-refuses-world-thread",
                refuses(() -> DroneBrain.planSquad(SquadView.solo(any))),
                "squad planning ran on the server thread instead of refusing"));

        out.add(check("threading/terrain-read-is-world-thread-only", !offThreadPasses(),
                "the world-thread assertion does not fire off-thread, so chunk reads are unguarded"));
    }

    private static boolean refuses(Runnable work) {
        try {
            work.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    /**
     * @return true if {@code WorldThread.assertOn} would wrongly allow a non-world thread through.
     */
    private static boolean offThreadPasses() {
        boolean[] allowed = {false};
        Thread probe = new Thread(() -> {
            try {
                WorldThread.assertOn("self-test probe");
                allowed[0] = true;
            } catch (IllegalStateException expected) {
                allowed[0] = false;
            }
        }, "wfballistics-selftest-probe");
        probe.start();
        try {
            probe.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return allowed[0];
    }

    /**
     * The obfuscation and the secrecy.
     *
     * <p>Most of what this system promises is <em>statistical</em>, "rarely approaches from the direction of
     * home" cannot be confirmed by watching one flight, or <em>negative</em>: "the client is never told
     * where the package is going" is a claim about what is absent, which is exactly the kind of thing that
     * silently stops being true. Both are checked here rather than asserted in a comment.
     */
    private static void exchange(List<Result> out) {
        java.util.Random rng = new java.util.Random(20260820L);

        String code = StationCode.generate(rng);
        out.add(check("exchange/code-is-well-formed",
                StationCode.valid(code) && code.length() == StationCode.LENGTH,
                "generated code '" + code + "' is not valid"));
        out.add(check("exchange/code-alphabet-has-no-confusables",
                StationCode.ALPHABET.indexOf('I') < 0 && StationCode.ALPHABET.indexOf('O') < 0
                        && StationCode.ALPHABET.indexOf('L') < 0,
                "the alphabet contains glyphs that cannot be told apart when typed back in"));
        out.add(check("exchange/code-normalises-confusables",
                StationCode.normalise("o1i-l0O ").equals("011100"),
                "typed confusables should fold onto the real alphabet, got "
                        + StationCode.normalise("o1i-l0O ")));
        out.add(check("exchange/code-rejects-nonsense", !StationCode.valid(StationCode.normalise("hello!")),
                "a typo should fail validation rather than resolve to some other station"));
        java.util.Set<String> drawn = new java.util.HashSet<>();
        for (int i = 0; i < 20000; i++) {
            drawn.add(StationCode.generate(rng));
        }
        out.add(check("exchange/codes-do-not-collide", drawn.size() == 20000,
                "20000 codes produced " + drawn.size() + " distinct values"));

        double home = 0.0;
        int samples = 40000;
        int nearHome = 0;
        int nearOpposite = 0;
        for (int i = 0; i < samples; i++) {
            double b = Obfuscation.approachBearing(rng, home);
            double off = Obfuscation.angleBetween(b, home);
            if (off < Math.toRadians(30.0)) {
                nearHome++;
            }
            if (off > Math.toRadians(150.0)) {
                nearOpposite++;
            }
        }
        double uniformShare = 30.0 / 180.0;
        double homeShare = nearHome / (double) samples;
        out.add(check("exchange/approach-avoids-home", homeShare < uniformShare * 0.5,
                String.format("%.1f%% of approaches came from within 30 degrees of home; uniform would be "
                        + "%.1f%% and this should be well under it", homeShare * 100.0, uniformShare * 100.0)));
        out.add(check("exchange/approach-never-rules-home-out", nearHome > 0,
                "no approach ever came from the home bearing - a direction that never happens is a "
                        + "direction an observer can rule out, which is information"));
        out.add(check("exchange/approach-prefers-the-far-side", nearOpposite > nearHome * 3,
                "approaches should cluster opposite home: " + nearOpposite + " far vs " + nearHome + " near"));

        int leavingTowardHome = 0;
        for (int i = 0; i < samples; i++) {
            double b = Obfuscation.approachBearing(rng, home);
            if (Obfuscation.angleBetween(b, home) < Math.toRadians(30.0)) {
                leavingTowardHome++;
            }
        }
        out.add(check("exchange/egress-is-obfuscated-too",
                leavingTowardHome / (double) samples < uniformShare * 0.5,
                "the departure bearing is no better hidden than a straight line home"));

        Vec3 origin = new Vec3(0.0, 80.0, 0.0);
        Vec3 target = new Vec3(600.0, 80.0, 0.0);
        List<Vec3> legs = Obfuscation.approachLegs(rng, origin, target);
        out.add(check("exchange/approach-has-a-staging-point", legs.size() == 1,
                "an indirect approach should turn in from exactly one staging point"));
        double stagingRange = Obfuscation.horizontal(target, legs.get(0));
        out.add(check("exchange/staging-is-far-enough-out",
                stagingRange >= Obfuscation.STAGING_MIN - 1.0 && stagingRange <= Obfuscation.STAGING_MAX + 1.0,
                "staging point sat " + (int) stagingRange + " blocks out, expected "
                        + (int) Obfuscation.STAGING_MIN + "-" + (int) Obfuscation.STAGING_MAX));

        DroneMission indirect = new DroneMission();
        indirect.mode = ExchangeMode.INDIRECT;
        indirect.destination = target;
        indirect.exfil = origin;
        indirect.planObfuscation(rng, origin);
        DroneMission direct = new DroneMission();
        direct.destination = target;
        direct.exfil = origin;
        out.add(check("exchange/indirect-costs-more",
                indirect.outboundDistance(origin) > direct.outboundDistance(origin)
                        && indirect.returnDistance(origin) > direct.returnDistance(origin),
                String.format("indirect flew %.0f out and %.0f back against a direct %.0f/%.0f",
                        indirect.outboundDistance(origin), indirect.returnDistance(origin),
                        direct.outboundDistance(origin), direct.returnDistance(origin))));
        out.add(check("exchange/direct-has-no-legs",
                direct.approachLegs.isEmpty() && direct.egressLegs.isEmpty(),
                "a direct delivery should fly straight there and straight back"));

        Vec3 stationA = new Vec3(0.0, 70.0, 0.0);
        Vec3 stationB = new Vec3(900.0, 70.0, 400.0);
        boolean standsOff = true;
        boolean offTheLine = true;
        java.util.Set<String> distinctPoints = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            Vec3 point = Obfuscation.rendezvous(rng, stationA, stationB);
            if (Obfuscation.horizontal(point, stationA) < Obfuscation.RENDEZVOUS_STANDOFF
                    || Obfuscation.horizontal(point, stationB) < Obfuscation.RENDEZVOUS_STANDOFF) {
                standsOff = false;
            }
            if (perpendicularDistance(stationA, stationB, point) < 1.0E-6) {
                offTheLine = false;
            }
            distinctPoints.add((int) point.x + ":" + (int) point.z);
        }
        out.add(check("exchange/rendezvous-stands-off-both-stations", standsOff,
                "a rendezvous was placed within " + (int) Obfuscation.RENDEZVOUS_STANDOFF
                        + " blocks of a station, which would identify it"));
        out.add(check("exchange/rendezvous-is-off-the-line", offTheLine,
                "a rendezvous sat exactly on the line between the two stations, giving that line away"));
        out.add(check("exchange/rendezvous-does-not-repeat", distinctPoints.size() > 1900,
                "2000 draws produced only " + distinctPoints.size() + " distinct meeting points"));

        leakage(out);
    }

    /**
     * The negative claims: that a handshake's destination never reaches a client.
     *
     * <p>Checked by serialising a mission and searching the actual bytes for the coordinate. A comment
     * saying the field is not written is worth nothing; the bytes are the contract.
     */
    private static void leakage(List<Result> out) {
        Vec3 secret = new Vec3(1234567.5, 89.25, -7654321.5);

        DroneMission handshake = new DroneMission();
        handshake.mode = ExchangeMode.HANDSHAKE;
        handshake.recipientCode = "ABCDEFGHJKMN";
        handshake.destination = secret;
        byte[] handshakeBytes = serialise(handshake);
        out.add(check("exchange/handshake-packet-carries-no-coordinates",
                !containsDouble(handshakeBytes, secret.x) && !containsDouble(handshakeBytes, secret.z),
                "a handshake packet contained the destination coordinates"));

        DroneMission plain = new DroneMission();
        plain.mode = ExchangeMode.DIRECT;
        plain.destination = secret;
        byte[] plainBytes = serialise(plain);
        out.add(check("exchange/control-direct-packet-does-carry-them",
                containsDouble(plainBytes, secret.x) && containsDouble(plainBytes, secret.z),
                "a direct packet did not contain its destination, so the leak test above proves nothing"));

        DroneMission decoded = DroneMission.read(new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(handshakeBytes)));
        out.add(check("exchange/handshake-destination-is-server-resolved",
                decoded.destination.equals(Vec3.ZERO) && decoded.mode == ExchangeMode.HANDSHAKE,
                "a decoded handshake arrived with a destination already set, got " + decoded.destination));
        out.add(check("exchange/handshake-carries-the-code",
                "ABCDEFGHJKMN".equals(decoded.recipientCode),
                "the recipient code did not survive the round trip"));

        out.add(check("exchange/hidden-modes-are-classified",
                ExchangeMode.INDIRECT.classified() && ExchangeMode.HANDSHAKE.classified()
                        && !ExchangeMode.DIRECT.classified(),
                "the wrong set of modes is treated as classified"));
    }

    private static byte[] serialise(DroneMission mission) {
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        mission.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(0, bytes);
        return bytes;
    }

    /**
     * @return true if this double's exact bit pattern appears anywhere in the buffer.
     */
    private static boolean containsDouble(byte[] haystack, double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] needle = new byte[8];
        for (int i = 0; i < 8; i++) {
            needle[i] = (byte) (bits >>> (56 - i * 8));
        }
        outer:
        for (int i = 0; i + 8 <= haystack.length; i++) {
            for (int j = 0; j < 8; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * @return how far a point sits off the line through two others, horizontally.
     */
    private static double perpendicularDistance(Vec3 a, Vec3 b, Vec3 point) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-9) {
            return Obfuscation.horizontal(a, point);
        }
        return Math.abs((point.x - a.x) * dz - (point.z - a.z) * dx) / length;
    }

    /**
     * Station-keeping, flown rather than measured. The geometry checks above say where a slot is; these say
     * a follower can actually reach one and stay in it while the leader moves, which is a different question
     * and the one that decides whether a squad arrives as a squad.
     *
     * <p>Flown through the real flight model, so what is being asserted is the guidance and the airframe
     * together: a command the drone cannot produce fails here exactly as it would in the air.
     */
    private static void formationFlight(List<Result> out) {
        double cruise = DroneEntity.DEFAULT_CRUISE_SPEED;
        double climb = DroneEntity.DEFAULT_CLIMB_RATE;
        out.add(stationKeeping("level", new Vec3(cruise, 0.0, 0.0)));
        out.add(stationKeeping("climbing", new Vec3(0.0, climb, 0.0)));
        out.add(stationKeeping("climbing-cruise", new Vec3(cruise, climb, 0.0)));
        out.add(stationKeeping("descending-cruise", new Vec3(cruise, -climb, 0.0)));
        out.add(stationKeeping("crawling", new Vec3(cruise * 0.33, 0.0, 0.0)));
        out.add(stationKeeping("hovering", Vec3.ZERO));

        DroneSnapshot wing = escort(1, at(0, 0), Vec3.ZERO);
        Vec3 driftA = new Vec3(0.03, climb, 0.0);
        Vec3 driftB = new Vec3(-0.03, climb, 0.0);
        Vec3 commandA = slotCommand(wing, pair(escort(0, at(0, 0), driftA), wing), driftA, 0.0f);
        Vec3 commandB = slotCommand(wing, pair(escort(0, at(0, 0), driftB), wing), driftB, 0.0f);
        out.add(check("formation/frame-ignores-climb-drift", commandA.distanceTo(commandB) < 0.1,
                "reversing a near-zero horizontal drift swung the whole formation round: " + commandA
                        + " vs " + commandB));
        float held = Steering.faceTravel(driftA, 0.0f,
                DroneEntity.DEFAULT_CRUISE_SPEED * Steering.HEADING_MIN_SPEED);
        out.add(check("formation/heading-holds-through-drift", held == 0.0f,
                "a drone climbing with a trickle of sideways drift must hold its heading, not chase it"));
        float turned = Steering.faceTravel(new Vec3(cruise, 0.0, 0.0), 0.0f,
                DroneEntity.DEFAULT_CRUISE_SPEED * Steering.HEADING_MIN_SPEED);
        out.add(check("formation/heading-still-follows-real-travel", turned != 0.0f,
                "a drone actually travelling must still turn to face where it is going"));

        Vec3 origin = at(0, 0);
        Vec3 axis = new Vec3(1.0, 0.0, 0.0);
        for (ResourceLocation id : Formations.ids()) {
            Formation shape = Formations.get(id);
            boolean scales = true;
            for (int i = 1; i < 6; i++) {
                Vec3 tight = shape.slot(i, origin, axis, SPACING).subtract(origin);
                Vec3 loose = shape.slot(i, origin, axis, SPACING * 2.0).subtract(origin);
                scales &= loose.distanceTo(tight.scale(2.0)) < 1.0E-9;
            }
            out.add(check("formation/" + id.getPath() + "/spacing-scales-the-shape", scales,
                    "doubling the spacing should put every slot exactly twice as far out"));
        }
        out.add(check("formation/spacing-is-clamped",
                Formation.clampSpacing(0.0) == Formation.MIN_SPACING
                        && Formation.clampSpacing(1.0E9) == Formation.MAX_SPACING
                        && Formation.clampSpacing(Double.NaN) == Formation.DEFAULT_SPACING,
                "a spacing typed into a command or a config screen has to be brought into range once, here"));

        Vec3 before = new Vec3(cruise, 0.0, 0.0);
        double turn = 0.02;
        Vec3 after = new Vec3(cruise * Math.cos(turn), 0.0, -cruise * Math.sin(turn));
        float afterYaw = (float) Mth.atan2(after.x, after.z);
        DroneSnapshot turning = escort(0, at(0, 0), before);
        Formation vee = Formations.get(Formations.DEFAULT);
        Vec3 leftSlot = vee.slot(1, turning.pos(), axis, SPACING);
        Vec3 rightSlot = vee.slot(2, turning.pos(), axis, SPACING);
        DroneSnapshot onLeft = escort(1, leftSlot, before);
        DroneSnapshot onRight = escort(2, rightSlot, before);
        SquadView flight = new SquadView(9L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, turning,
                List.of(turning, onLeft, onRight));
        double leftSpeed = slotCommand(onLeft, flight, after, afterYaw).horizontalDistance();
        double rightSpeed = slotCommand(onRight, flight, after, afterYaw).horizontalDistance();
        out.add(check("formation/turn-speeds-the-outside-drone-up", leftSpeed > cruise + 1.0E-6,
                "the drone on the outside of a turn has a longer arc to fly and must be told to, got "
                        + String.format("%.3f", leftSpeed) + " against a cruise of " + cruise));
        out.add(check("formation/turn-slows-the-inside-drone-down", rightSpeed < cruise - 1.0E-6,
                "the drone on the inside of a turn has a shorter arc and must be told to ease off, got "
                        + String.format("%.3f", rightSpeed)));

        Vec3 travel = new Vec3(cruise, 0.0, 0.0);
        DroneSnapshot lead = escort(0, at(0, 0), travel);
        Vec3 onSlot = Formations.get(Formations.DEFAULT).slot(1, lead.pos(), headingOf(travel), SPACING);
        DroneSnapshot seated = escort(1, onSlot, travel);
        out.add(check("formation/matches-leader-when-seated",
                slotCommand(seated, pair(lead, seated), travel, settledYaw(travel)).distanceTo(travel) < 1.0E-6,
                "a follower already in its slot should ask for exactly the leader's velocity"));
    }

    /**
     * Fly a follower from the leader's own position out to its slot and hold it there, and report the worst
     * it did over the second half of the run.
     *
     * <p>The <em>worst</em>, deliberately, rather than where it happened to be at the end. The failure this
     * is here to catch is a limit cycle (a follower that overruns its slot, turns round, overruns it the
     * other way and repeats for the whole flight) and a single sample at the end of the run passes or
     * fails that on nothing but which phase of the swing it landed in.
     */
    private static Result stationKeeping(String name, Vec3 leaderVelocity) {
        int ticks = 600;
        Vec3 leaderPos = new Vec3(0.0, 140.0, 0.0);
        Vec3 followerPos = leaderPos;
        Vec3 followerVelocity = Vec3.ZERO;
        FlightAttitude attitude = FlightAttitude.LEVEL;
        Formation formation = Formations.get(Formations.DEFAULT);
        Vec3 heading = headingOf(leaderVelocity);
        double worst = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            DroneSnapshot leader = escort(0, leaderPos, leaderVelocity);
            DroneSnapshot follower = escort(1, followerPos, followerVelocity);
            Vec3 desired = slotCommand(follower, pair(leader, follower), leaderVelocity, settledYaw(leaderVelocity));
            Multirotor.Step step = Multirotor.step(followerVelocity, attitude, desired, Airframe.QUADCOPTER,
                    1.0);
            followerVelocity = step.velocity();
            attitude = step.attitude();
            followerPos = followerPos.add(followerVelocity);
            leaderPos = leaderPos.add(leaderVelocity);
            if (tick >= ticks / 2) {
                worst = Math.max(worst, followerPos.distanceTo(formation.slot(1, leaderPos, heading, SPACING)));
            }
        }
        return check("formation/holds-station-" + name, worst < 1.0,
                "a follower should settle into its slot and stay there; flying " + name
                        + " it was still up to " + String.format("%.2f", worst) + " blocks out of it");
    }

    /**
     * Ask {@link LeaderFollower} where a drone should be flying, through the same two-step the brain uses:
     * seed the frame from the leader's snapshot, then rebuild it around the plan the leader just made.
     *
     * <p>Goes through the real model rather than reimplementing the geometry, so a check here cannot quietly
     * keep passing against a copy of the arithmetic after the shipped path has changed underneath it.
     */
    private static Vec3 slotCommand(DroneSnapshot self, SquadView squad, Vec3 leaderVelocity, float leaderYaw) {
        DroneSnapshot leader = squad.leader();
        DronePlan plan = new DronePlan(leader.id(), leader.gameTime(), leaderVelocity, leader.attitude(),
                leaderYaw, null, List.of());
        SquadAnchor seeded = LeaderFollower.INSTANCE.advance(squad, null);
        return LeaderFollower.INSTANCE.guide(self, squad,
                LeaderFollower.INSTANCE.refresh(squad, seeded, plan)).velocity();
    }

    private static SquadView pair(DroneSnapshot leader, DroneSnapshot follower) {
        return new SquadView(9L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, leader, List.of(leader, follower));
    }

    /**
     * @return the formation frame a drone flying {@code velocity} would be holding: its heading, since that
     * is what the frame is built on. Mirrors {@link #settledYaw}, so a check can place a slot by hand and
     * get the same answer the code will.
     */
    private static Vec3 headingOf(Vec3 velocity) {
        float yaw = settledYaw(velocity);
        return new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /**
     * @return the heading a drone flying {@code velocity} would have settled on.
     *
     * <p>Held at zero below {@link Steering#HEADING_MIN_SPEED} of cruise, exactly as
     * {@link Steering#faceTravel} holds it: a drone that is climbing, hovering or drifting is not turning to
     * face a direction it is not really going in, and a check that pretended otherwise would be testing a
     * drone that cannot exist.
     */
    private static float settledYaw(Vec3 velocity) {
        double horiz = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return horiz < DroneEntity.DEFAULT_CRUISE_SPEED * Steering.HEADING_MIN_SPEED
                ? 0.0f : (float) Mth.atan2(velocity.x, velocity.z);
    }

    /**
     * A squad member in transit with a mission, so it counts as flying formation.
     */
    private static DroneSnapshot escort(int index, Vec3 pos, Vec3 velocity) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) (0x40 + index)}), false, pos,
                velocity, settledYaw(velocity), FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE,
                DroneState.TRANSIT, 20, at(4000, 0), null, DroneProgram.EMPTY, at(0, 0),
                true, false, false, true, List.of(),
                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                pos.y - DroneEntity.DEFAULT_CRUISE_ALTITUDE, 100.0, 9L, index == 0, 1, null, 0L, index);
    }


    /**
     * The four architectures a squad can hold its shape with.
     *
     * <p>Written as assertions about what makes each one <em>different</em> rather than as numeric tolerances
     * on how well each flies. The flying is measured off-line against the real flight model, where a long run
     * can be flown and the answer is a distribution; what belongs here is the structural claim each model
     * makes, because that is what a later change is liable to break silently.
     */
    private static void coordination(List<Result> out) {
        for (ResourceLocation id : CoordinationModels.ids()) {
            out.add(check("coord/resolves-" + id.getPath(),
                    CoordinationModels.get(id) != null && CoordinationModels.rl(
                            CoordinationModels.get(id).id()).equals(id),
                    "every registered model must resolve back to itself by id"));
        }
        out.add(check("coord/unknown-falls-back",
                CoordinationModels.get(ResourceLocation.fromNamespaceAndPath("wfballistics", "nope"))
                        == CoordinationModels.get(CoordinationModels.DEFAULT),
                "an id that names nothing must fall back to the default, not fly nothing at all"));
        out.add(check("coord/legacy-is-leader-follower",
                CoordinationModels.get(CoordinationModels.LEGACY) == LeaderFollower.INSTANCE,
                "a squad saved before models existed was flying leader-follower and must load as it"));

        Vec3 travel = new Vec3(DroneEntity.DEFAULT_CRUISE_SPEED, 0.0, 0.0);
        DroneSnapshot lead = escort(0, at(0, 0), travel);
        Formation vee = Formations.get(Formations.DEFAULT);
        Vec3 frame = headingOf(travel);
        DroneSnapshot wing1 = escort(1, vee.slot(1, lead.pos(), frame, SPACING), travel);
        DroneSnapshot wing2 = escort(2, vee.slot(2, lead.pos(), frame, SPACING), travel);
        List<DroneSnapshot> members = List.of(lead, wing1, wing2);

        for (ResourceLocation id : CoordinationModels.ids()) {
            CoordinationModel model = CoordinationModels.get(id);
            SquadView squad = new SquadView(9L, Formations.DEFAULT, SPACING, id, null, lead, members);
            SquadAnchor anchor = model.advance(squad, null);
            boolean sane = anchor != null && finite(anchor.pos()) && finite(anchor.velocity())
                    && finite(anchor.acceleration()) && Float.isFinite(anchor.yaw());
            for (DroneSnapshot member : members) {
                SquadCommand command = model.guide(member, squad, anchor);
                sane &= command != null && finite(command.velocity()) && finite(command.acceleration())
                        && command.velocity().length() < 100.0;
            }
            out.add(check("coord/usable-" + id.getPath(), sane,
                    "every model must return a finite, bounded command for every member of a settled squad"));
        }

        SquadView lf = new SquadView(9L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null,
                lead, members);
        out.add(check("coord/leader-follower-anchors-on-the-leader",
                LeaderFollower.INSTANCE.advance(lf, null).pos().distanceTo(lead.pos()) < 1.0E-9,
                "leader-follower measures the formation off the leader, so its frame is the leader"));
        out.add(check("coord/only-computed-frames-station-the-leader",
                VirtualStructure.INSTANCE.stationsLeader() && !LeaderFollower.INSTANCE.stationsLeader()
                        && !Consensus.INSTANCE.stationsLeader(),
                "a drone cannot station-keep on itself, so only a model whose frame is elsewhere may hold "
                        + "the leader to a slot"));

        SquadView vs = new SquadView(9L, Formations.DEFAULT, SPACING,
                CoordinationModels.rl(VirtualStructure.INSTANCE.id()), null, lead, members);
        SquadAnchor seeded = VirtualStructure.INSTANCE.advance(vs, null);
        DroneSnapshot shoved = escort(0, lead.pos().add(6.0, 0.0, 0.0), travel);
        SquadView jolted = new SquadView(9L, Formations.DEFAULT, SPACING,
                CoordinationModels.rl(VirtualStructure.INSTANCE.id()), seeded, shoved,
                List.of(shoved, wing1, wing2));
        SquadAnchor after = VirtualStructure.INSTANCE.advance(jolted, seeded);
        double followed = after.pos().subtract(seeded.pos()).subtract(seeded.velocity()).length();
        out.add(check("coord/virtual-frame-ignores-a-shoved-leader", followed < 1.0,
                "six blocks of leader displacement moved the virtual frame by " + String.format("%.2f", followed)
                        + "; the point of computing the reference is that it does not chase one aircraft"));

        DroneSnapshot adrift1 = escort(1, wing1.pos().subtract(60.0, 0.0, 0.0), travel);
        DroneSnapshot adrift2 = escort(2, wing2.pos().subtract(60.0, 0.0, 0.0), travel);
        SquadView lagging = new SquadView(9L, Formations.DEFAULT, SPACING,
                CoordinationModels.rl(VirtualStructure.INSTANCE.id()), seeded, lead,
                List.of(lead, adrift1, adrift2));
        double heldBack = VirtualStructure.INSTANCE.advance(lagging, seeded).velocity().horizontalDistance();
        double freeRun = VirtualStructure.INSTANCE.advance(vs, seeded).velocity().horizontalDistance();
        out.add(check("coord/virtual-frame-waits-for-stragglers", heldBack < freeRun,
                "a frame that outruns the drones flying it is not a reference; got " + String.format("%.3f", heldBack)
                        + " against " + String.format("%.3f", freeRun) + " with the squad in place"));

        SquadView con = new SquadView(9L, Formations.DEFAULT, SPACING,
                CoordinationModels.rl(Consensus.INSTANCE.id()), null, lead, members);
        SquadAnchor conAnchor = Consensus.INSTANCE.advance(con, null);
        double worst = 0.0;
        for (DroneSnapshot member : members) {
            worst = Math.max(worst,
                    Consensus.INSTANCE.guide(member, con, conAnchor).velocity().distanceTo(travel));
        }
        out.add(check("coord/consensus-settles-with-no-correction", worst < 0.05,
                "a squad already in shape must be told to fly the leader's velocity and nothing else, "
                        + "worst member was " + String.format("%.3f", worst) + " off it"));

        DroneSnapshot near1 = escort(1, lead.pos().add(2.0, 0.0, 0.0), travel);
        DroneSnapshot near2 = escort(2, lead.pos().add(-2.0, 0.0, 0.0), travel);
        SquadView flock = new SquadView(9L, Formations.DEFAULT, SPACING,
                CoordinationModels.rl(Flocking.INSTANCE.id()), null, lead, List.of(lead, near1, near2));
        SquadAnchor flockAnchor = Flocking.INSTANCE.advance(flock, null);
        double apart = Flocking.INSTANCE.guide(near1, flock, flockAnchor).velocity().x
                - Flocking.INSTANCE.guide(near2, flock, flockAnchor).velocity().x;
        out.add(check("coord/flock-has-a-size", apart > 0.1,
                "cohesion has no preferred distance in it, so without a separation term a flock collapses to "
                        + "a point and grinds against itself; the two drones were closing at "
                        + String.format("%.3f", apart)));
    }

    /**
     * The slot arithmetic every shape-holding model shares.
     */
    private static void slotTracking(List<Result> out) {
        Formation vee = Formations.get(Formations.DEFAULT);
        Vec3 travel = new Vec3(DroneEntity.DEFAULT_CRUISE_SPEED, 0.0, 0.0);
        float east = (float) Mth.atan2(travel.x, travel.z);

        SquadAnchor straight = new SquadAnchor(at(0, 0), travel, Vec3.ZERO, east, 0.0f);
        Slots.Track level = Slots.of(vee, 3, straight, SPACING);
        out.add(check("slots/straight-slot-matches-the-frame",
                level.velocity().distanceTo(travel) < 1.0E-9 && level.acceleration().length() < 1.0E-9,
                "with the frame not turning, a slot should fly the frame's velocity and accelerate at "
                        + "nothing; got " + level.velocity() + " and " + level.acceleration()));

        SquadAnchor turning = new SquadAnchor(at(0, 0), travel, Vec3.ZERO, east, 0.01f);
        double left = Slots.of(vee, 1, turning, SPACING).velocity().horizontalDistance();
        double right = Slots.of(vee, 2, turning, SPACING).velocity().horizontalDistance();
        out.add(check("slots/turn-sweeps-the-outside-slot-faster",
                Math.max(left, right) > travel.horizontalDistance() + 1.0E-6
                        && Math.min(left, right) < travel.horizontalDistance() - 1.0E-6,
                "a rotating frame must speed the outside slot up and slow the inside one down, got "
                        + String.format("%.3f and %.3f", left, right)));
        out.add(check("slots/turn-accelerates-the-slots",
                Slots.of(vee, 1, turning, SPACING).acceleration().length() > 1.0E-6,
                "holding a slot round a turn is an acceleration, and a model that reports none has nothing "
                        + "to feed forward"));

        Vec3 held = new Vec3(0.5, 0.0, 0.0);
        Multirotor.Step blind = Multirotor.step(held, FlightAttitude.LEVEL, held,
                Airframe.QUADCOPTER, 1.0);
        Multirotor.Step told = Multirotor.step(held, FlightAttitude.LEVEL, held,
                new Vec3(0.0, 0.0, 0.01), Airframe.QUADCOPTER, 1.0);
        out.add(check("slots/feed-forward-leans-with-no-error",
                Math.abs(told.attitude().tiltZ()) > Math.abs(blind.attitude().tiltZ()) + 1.0E-6,
                "a drone perfectly on a station that is beginning to turn has no error to lean on, so "
                        + "without the reference's acceleration it cannot start the turn at all; tilt was "
                        + String.format("%.5f against %.5f", told.attitude().tiltZ(),
                                blind.attitude().tiltZ())));
        out.add(check("slots/feed-forward-turns-the-drone",
                told.velocity().z > blind.velocity().z + 1.0E-6,
                "the lean has to produce motion in the direction the reference is accelerating"));
        out.add(check("slots/no-feed-forward-changes-nothing",
                Multirotor.step(held, FlightAttitude.LEVEL, held, Vec3.ZERO, Airframe.QUADCOPTER, 1.0)
                        .velocity().distanceTo(blind.velocity()) < 1.0E-12,
                "the two-argument form must be exactly the zero-feed-forward case, or every drone flying its "
                        + "own route has quietly changed behaviour"));
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }


    /**
     * Form-up. A flight goes up one drone at a time, so the question that decides whether it is a formation
     * or a straggle is when the first one is allowed to leave.
     */
    private static void muster(List<Result> out) {
        Vec3 up = at(0, 0);
        double alt = DroneEntity.DEFAULT_CRUISE_ALTITUDE;

        DroneSnapshot solo = musterer(0, up, Vec3.ZERO, alt, 1);
        out.add(check("muster/solo-never-waits",
                !MusterHandler.required(solo, SquadView.solo(solo)),
                "a drone flying alone has nobody to form up on and must carry straight on"));

        DroneSnapshot lead = musterer(0, up, Vec3.ZERO, alt, 4);
        DroneSnapshot wing = musterer(1, museSlot(1), Vec3.ZERO, alt, 4);
        SquadView half = squadOf(lead, wing);
        out.add(check("muster/waits-for-drones-still-on-the-pad",
                MusterHandler.required(lead, half),
                "the squad it can see is not the squad it was ordered; two of four up must still wait"));
        out.add(check("muster/holds-state-while-waiting",
                MusterHandler.INSTANCE.next(lead, half) == null,
                "a flight still forming up has nowhere to be yet"));

        DroneSnapshot a = musterer(0, up, Vec3.ZERO, alt, 4);
        DroneSnapshot b = musterer(1, museSlot(1), Vec3.ZERO, alt, 4);
        DroneSnapshot c = musterer(2, museSlot(2), Vec3.ZERO, alt, 4);
        DroneSnapshot d = musterer(3, museSlot(3), Vec3.ZERO, alt, 4);
        SquadView full = squadOf(a, b, c, d);
        out.add(check("muster/releases-a-complete-flight",
                MusterHandler.INSTANCE.next(a, full) == DroneState.TRANSIT,
                "everyone up, at height and steady is the whole condition; the flight should depart"));

        DroneSnapshot low = musterer(3, museSlot(3), Vec3.ZERO, alt * 0.5, 4);
        out.add(check("muster/waits-for-the-climb-out",
                MusterHandler.INSTANCE.next(a, squadOf(a, b, c, low)) == null,
                "a drone halfway up its climb has spawned but is not ready, and a slot is at the flight's "
                        + "altitude"));
        DroneSnapshot outOfPlace = musterer(3, museSlot(3).add(SPACING, 0.0, 0.0), Vec3.ZERO, alt, 4);
        out.add(check("muster/waits-for-the-squad-to-get-into-shape",
                MusterHandler.INSTANCE.next(a, squadOf(a, b, c, outOfPlace)) == null,
                "a drone a whole slot away from its station is nearer somebody else's place than its own, "
                        + "and the flight is not yet in shape"));
        DroneSnapshot jittering = musterer(3, museSlot(3), new Vec3(0.4, 0.0, 0.0), alt, 4);
        out.add(check("muster/does-not-wait-for-jitter-to-stop",
                MusterHandler.INSTANCE.next(a, squadOf(a, b, c, jittering)) == DroneState.TRANSIT,
                "a drone on its station but still being jostled is formed up; waiting for it to be perfectly "
                        + "still is waiting for something a close formation cannot do"));

        DroneSnapshot dead = downed(3, museSlot(3), 4);
        out.add(check("muster/does-not-wait-for-a-lost-drone",
                MusterHandler.INSTANCE.next(a, squadOf(a, b, c, dead)) == DroneState.TRANSIT,
                "a member falling out of the sky has still turned up; waiting for it to reach altitude is "
                        + "waiting for ever"));

        DroneSnapshot patient = musterer(0, up, Vec3.ZERO, alt, 4, Tuning.MUSTER_TIMEOUT);
        out.add(check("muster/gives-up-eventually",
                MusterHandler.INSTANCE.next(patient, half) == DroneState.TRANSIT,
                "one drone that never arrives must not hold the whole flight until its battery is flat"));

        DroneSnapshot climbed = new DroneSnapshot(lead.id(), false, up, Vec3.ZERO, 0.0f,
                FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE, DroneState.TAKEOFF, 40,
                at(4000, 0), null, DroneProgram.EMPTY, at(0, 0), false, false, false, true, List.of(),
                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, alt, DroneEntity.DEFAULT_CLIMB_RATE,
                DroneEntity.DEFAULT_RELEASE_SPEED, up.y - alt, 100.0, 9L, true, 4, null, 0L, 0);
        out.add(check("muster/climb-out-hands-over-to-it",
                TakeoffHandler.INSTANCE.next(climbed, squadOf(climbed, wing)) == DroneState.MUSTER,
                "a drone that reaches cruise altitude with its flight still launching must form up, not set "
                        + "off"));
        DroneSnapshot aloneUp = musterer(0, up, Vec3.ZERO, alt, 1);
        out.add(check("muster/climb-out-still-goes-straight-through",
                TakeoffHandler.INSTANCE.next(
                        new DroneSnapshot(aloneUp.id(), false, up, Vec3.ZERO, 0.0f, FlightAttitude.LEVEL,
                                Airframe.QUADCOPTER, DroneNav.NONE, DroneState.TAKEOFF, 40, at(4000, 0), null,
                                DroneProgram.EMPTY, at(0, 0), false, false, false, true, List.of(),
                                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY,
                                PowerProfile.DEFAULT, DroneEntity.DEFAULT_CRUISE_SPEED, alt,
                                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                                up.y - alt, 100.0, 0L, true, 1, null, 0L, 0),
                        SquadView.solo(aloneUp)) == DroneState.TRANSIT,
                "a solo drone's climb-out must reach transit exactly as it always did"));

        Vec3 hold = MusterHandler.INSTANCE.guide(a, full);
        out.add(check("muster/holds-its-position", hold.horizontalDistance() < 1.0E-6,
                "a drone waiting over the pad should be told to stay there, got " + hold));

        out.add(check("muster/climbing-drones-do-not-take-slots",
                DroneBrain.think(climbed.withVelocity(Vec3.ZERO), squadOf(lead, climbed)) != null,
                "a follower still climbing must be planned by its own handler"));

        out.add(check("muster/interval-is-clamped",
                DroneMission.clampInterval(-5) == 0
                        && DroneMission.clampInterval(999999) == DroneMission.MAX_LAUNCH_INTERVAL
                        && DroneMission.clampInterval(20) == 20,
                "a launch gap typed into a screen has to be brought into range where it enters the system"));

        DroneMission mission = new DroneMission();
        mission.count = 4;
        mission.launchInterval = 37;
        mission.approachLegs = List.of(new Vec3(1.0, 2.0, 3.0), new Vec3(4.0, 5.0, 6.0));
        mission.egressLegs = List.of(new Vec3(7.0, 8.0, 9.0));
        mission.collecting = true;
        mission.stationCode = "ABCD";
        DroneMission reloaded = DroneMission.load(mission.save());
        out.add(check("muster/queued-mission-survives-a-reload",
                reloaded.launchInterval == 37 && reloaded.collecting
                        && reloaded.approachLegs.equals(mission.approachLegs)
                        && reloaded.egressLegs.equals(mission.egressLegs)
                        && "ABCD".equals(reloaded.stationCode),
                "the drones still queued must fly the same drawn-once dogleg as the one already airborne, "
                        + "so the dispatch-time fields have to persist with the launch"));
        out.add(check("muster/old-pads-still-launch-together",
                DroneMission.load(new net.minecraft.nbt.CompoundTag()).launchInterval == 0,
                "a pad configured before staggered launch existed put its whole flight up at once, and "
                        + "reloading a world is not the moment to change that"));
    }

    /**
     * @return where slot {@code index} sits for a flight formed up over the pad facing north: the frame a
     * {@link #musterer} is built in, since they are all given a yaw of zero. Taken from the real
     * {@link Formation} rather than written out, so these checks cannot drift away from the shape the code
     * actually flies.
     */
    private static Vec3 museSlot(int index) {
        return Formations.get(Formations.DEFAULT)
                .slot(index, at(0, 0), Formation.forward(0.0f), SPACING);
    }

    private static SquadView squadOf(DroneSnapshot... members) {
        return new SquadView(9L, Formations.DEFAULT, SPACING, CoordinationModels.DEFAULT, null,
                members[0], List.of(members));
    }

    /**
     * A drone holding over the pad: airborne, at {@code altitude} above the ground, part of a flight of
     * {@code ordered}.
     */
    private static DroneSnapshot musterer(int index, Vec3 pos, Vec3 velocity, double altitude, int ordered) {
        return musterer(index, pos, velocity, altitude, ordered, 20);
    }

    private static DroneSnapshot musterer(int index, Vec3 pos, Vec3 velocity, double altitude, int ordered,
                                          int stateTicks) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) (0x70 + index)}), false, pos,
                velocity, 0.0f, FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE,
                DroneState.MUSTER, stateTicks, at(4000, 0), null, DroneProgram.EMPTY, at(0, 0),
                false, false, false, true, List.of(),
                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                pos.y - altitude, 100.0, 9L, index == 0, ordered, null, 0L, index);
    }

    private static DroneSnapshot downed(int index, Vec3 pos, int ordered) {
        DroneSnapshot alive = musterer(index, pos, Vec3.ZERO, 0.0, ordered);
        return new DroneSnapshot(alive.id(), false, alive.pos(), alive.velocity(), alive.yaw(),
                alive.attitude(), alive.airframe(), alive.nav(), DroneState.DOWNED, alive.stateTicks(),
                alive.destination(), null, alive.program(), alive.exfil(), false, false, false, true,
                List.of(), 0.0, alive.capacity(), alive.power(), alive.cruiseSpeed(), alive.cruiseAltitude(),
                alive.climbRate(), alive.releaseSpeed(), alive.groundY(), alive.destinationGroundY(),
                alive.squadId(), false, ordered, null, alive.gameTime(), alive.seed());
    }

    /**
     * The two ways a flight can destroy itself: piling into one another, and dropping ordnance on one
     * another. Both come down to geometry, so both can be asserted directly.
     */
    private static void squadSafety(List<Result> out) {
        List<DroneSnapshot> members = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            members.add(member(i, new Vec3(i * 0.5, 100.0, 0.0), at(300, 0)));
        }
        SquadView squad = new SquadView(7L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, members.get(0), List.copyOf(members));

        List<Vec3> aims = new ArrayList<>();
        for (DroneSnapshot member : members) {
            aims.add(PayloadRunHandler.aimPoint(member, squad));
        }
        out.add(check("squad/leader-aims-at-the-target",
                aims.get(0).distanceTo(members.get(0).destination()) < 1.0E-6,
                "the leader should bomb the point it was actually given"));

        double closest = Double.MAX_VALUE;
        for (int i = 0; i < aims.size(); i++) {
            for (int j = i + 1; j < aims.size(); j++) {
                closest = Math.min(closest, aims.get(i).distanceTo(aims.get(j)));
            }
        }
        out.add(check("squad/strike-aim-points-are-spread", closest >= SPACING,
                "closest pair of aim points was " + (int) closest + " blocks apart, expected at least "
                        + (int) SPACING));
        out.add(check("squad/strike-pattern-scales-with-spread",
                closest >= SPACING * Tuning.PAYLOAD_AIM_SPREAD * 0.9,
                "the aim spread multiplier is not being applied: " + (int) closest + " blocks"));

        DroneSnapshot solo = member(0, new Vec3(0.0, 100.0, 0.0), at(300, 0));
        out.add(check("squad/solo-strike-is-unshifted",
                PayloadRunHandler.aimPoint(solo, SquadView.solo(solo)).distanceTo(solo.destination()) < 1.0E-6,
                "a single drone's aim point should not be offset"));

        DroneSnapshot near = member(1, new Vec3(1.0, 100.0, 0.0), at(300, 0));
        SquadView pair = new SquadView(7L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, members.get(0),
                List.of(members.get(0), near));
        Vec3 pushA = Cruising.separation(members.get(0), pair);
        Vec3 pushB = Cruising.separation(near, pair);
        out.add(check("squad/separation-pushes-apart", pushA.length() > 0.0 && pushA.dot(pushB) < 0.0,
                "two drones a block apart should be pushed in opposite directions"));
        out.add(check("squad/separation-is-bounded",
                pushA.length() <= Tuning.SEPARATION_MAX + 1.0E-9,
                "the separation push exceeded its own cap: " + pushA.length()));

        DroneSnapshot spaced = member(1, new Vec3(SPACING, 100.0, 0.0), at(300, 0));
        SquadView inFormation = new SquadView(7L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, members.get(0),
                List.of(members.get(0), spaced));
        out.add(check("squad/separation-leaves-formation-alone",
                Cruising.separation(members.get(0), inFormation).length() < 1.0E-9,
                "drones sitting correctly in formation should not be shoving each other out of it"));

        DroneSnapshot stacked = member(1, new Vec3(0.0, 100.0, 0.0), at(300, 0));
        SquadView onTop = new SquadView(7L, Formations.DEFAULT, SPACING, CoordinationModels.LEGACY, null, members.get(0),
                List.of(members.get(0), stacked));
        Vec3 scatterA = Cruising.separation(members.get(0), onTop);
        Vec3 scatterB = Cruising.separation(stacked, onTop);
        out.add(check("squad/coincident-drones-scatter",
                scatterA.length() > 0.0 && scatterB.length() > 0.0 && scatterA.distanceTo(scatterB) > 1.0E-6,
                "drones in exactly the same place must still be given different ways out"));
    }

    private static DroneSnapshot member(int index, Vec3 pos, Vec3 destination) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) index}), false, pos, Vec3.ZERO, 0.0f,
                FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE,
                DroneState.PAYLOAD_RUN, 5, destination, null, DroneProgram.EMPTY, at(0, 0),
                false, true, false, true, List.of(),
                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                pos.y - DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                destination.y - DroneEntity.DEFAULT_CRUISE_ALTITUDE, 7L, index == 0, 1, null, 0L, index);
    }

    /**
     * Hull-to-hull collision. Pure box arithmetic, so what it does can be stated exactly rather than flown
     * for and eyeballed.
     */
    private static void contacts(List<Result> out) {
        AABB self = hull(0.0, 100.0, 0.0);

        out.add(check("contact/clear-hulls-are-left-alone",
                Contacts.escape(self, hull(SPACING, 100.0, 0.0), true).equals(Vec3.ZERO),
                "drones a formation slot apart are not touching and must not be pushed"));

        AABB beside = hull(2.0, 100.0, 0.0);
        Vec3 sideways = Contacts.escape(self, beside, true);
        out.add(check("contact/side-by-side-pushes-sideways",
                sideways.x < 0.0 && sideways.y == 0.0 && sideways.z == 0.0,
                "a drone overlapped from +X should be pushed out along -X, got " + sideways));
        out.add(check("contact/escape-clears-the-overlap",
                Contacts.escape(self.move(sideways), beside, true).equals(Vec3.ZERO),
                "applying the escape in full should leave the two hulls clear of each other"));

        Vec3 stacked = Contacts.escape(self, hull(0.0, 99.4, 0.0), true);
        out.add(check("contact/stacked-pushes-vertically",
                stacked.y > 0.0 && stacked.x == 0.0 && stacked.z == 0.0,
                "a drone sitting just above another should be lifted, not shoved sideways, got " + stacked));

        AABB same = hull(0.0, 100.0, 0.0);
        Vec3 mine = Contacts.escape(self, same, true);
        Vec3 theirs = Contacts.escape(same, self, false);
        out.add(check("contact/coincident-hulls-part",
                mine.lengthSqr() > 0.0 && mine.dot(theirs) < 0.0,
                "two drones in exactly the same place must be sent opposite ways, got " + mine + " and "
                        + theirs));

        Vec3 step = Contacts.step(new Vec3(0.0, 40.0, 0.0));
        out.add(check("contact/one-tick-is-bounded", step.length() <= Contacts.MAX_ESCAPE + 1.0E-9,
                "a deeply overlapped drone should be pushed out over several ticks, not teleported: "
                        + step.length()));
        out.add(check("contact/shares-the-correction",
                Contacts.step(new Vec3(0.0, 0.2, 0.0)).y == 0.2 * Contacts.SHARE,
                "each drone should clear its own half of a shallow overlap"));

        out.add(check("contact/push-outruns-a-held-pair",
                Contacts.MAX_ESCAPE > DroneEntity.DEFAULT_CRUISE_SPEED * Contacts.SHARE,
                "a held pair pulls together at cruise speed and each drone clears only its own half, so the "
                        + "push must beat half of cruise: " + Contacts.MAX_ESCAPE));
        out.add(check("contact/push-stays-under-a-tick-of-flight",
                Contacts.MAX_ESCAPE < DroneEntity.DEFAULT_RELEASE_SPEED,
                "a shove that moved a drone further than flying does would read as a teleport"));
    }

    /**
     * @return a hull the size of the stock airframe, centred on the given point.
     */
    private static AABB hull(double x, double y, double z) {
        return new AABB(x - 1.5, y - 0.6, z - 1.5, x + 1.5, y + 0.6, z + 1.5);
    }

    /**
     * That a drone climbs away from the weapon it has just released rather than flying on through it.
     */
    private static void breakOff(List<Result> out) {
        double cruise = DroneEntity.DEFAULT_CRUISE_ALTITUDE;
        Vec3 target = at(600, 0);
        DroneSnapshot dropped = striker(new Vec3(0.0, 100.0, 0.0), 100.0 - cruise, false,
                new Vec3(DroneEntity.DEFAULT_RELEASE_SPEED, 0.0, 0.0), target);
        SquadView alone = SquadView.solo(dropped);

        out.add(check("payload/run-holds-until-clear",
                PayloadRunHandler.INSTANCE.next(dropped, alone) == null,
                "the run is not over the instant the bomb leaves the rack - the drone is still on top of it"));

        Vec3 egress = PayloadRunHandler.INSTANCE.guide(dropped, alone);
        out.add(check("payload/break-off-climbs", egress.y > 0.0,
                "a drone that has just released should be climbing away from its payload, got " + egress));
        out.add(check("payload/break-off-runs-out", egress.x > 0.0,
                "the break-off must carry on past the target, not turn back over the falling bomb: " + egress));
        out.add(check("payload/break-off-keeps-its-speed",
                egress.horizontalDistance() >= DroneEntity.DEFAULT_RELEASE_SPEED - 1.0E-6,
                "easing off while the weapon is still underneath defeats the manoeuvre: " + egress));

        DroneSnapshot clear = striker(new Vec3(0.0, 100.0 + Tuning.BREAK_OFF_CLIMB, 0.0), 100.0 - cruise,
                false, new Vec3(DroneEntity.DEFAULT_RELEASE_SPEED, 0.0, 0.0), target);
        out.add(check("payload/break-off-ends",
                PayloadRunHandler.INSTANCE.next(clear, SquadView.solo(clear)) != null,
                "once it has the height the drone should rejoin the mission instead of climbing forever"));

        DroneProgram queue = DroneProgram.of(List.of(new DroneTask.Strike(target), new DroneTask.Exfil()));
        List<DroneAction> early = new ArrayList<>();
        PayloadRunHandler.INSTANCE.act(dropped.withProgram(queue), alone, early);
        out.add(check("payload/queue-waits-for-the-break-off",
                early.stream().noneMatch(a -> a instanceof DroneAction.AdvanceTask),
                "the program stepped on while the drone was still climbing away from its payload"));

        List<DroneAction> late = new ArrayList<>();
        DroneSnapshot clearQueued = clear.withProgram(queue);
        PayloadRunHandler.INSTANCE.act(clearQueued, SquadView.solo(clearQueued), late);
        out.add(check("payload/queue-steps-on-after-it",
                late.stream().anyMatch(a -> a instanceof DroneAction.AdvanceTask),
                "the program must step on once the break-off is finished, or the drone repeats the strike"));

        DroneSnapshot armed = striker(new Vec3(0.0, 100.0, 0.0), 100.0 - cruise, true,
                new Vec3(DroneEntity.DEFAULT_RELEASE_SPEED, 0.0, 0.0), target);
        out.add(check("payload/armed-run-is-unchanged",
                PayloadRunHandler.INSTANCE.next(armed, SquadView.solo(armed)) == null
                        && PayloadRunHandler.INSTANCE.guide(armed, SquadView.solo(armed)).x > 0.0,
                "a drone still carrying its payload should be flying the run in as before"));
    }

    /**
     * A strike drone mid-run, with the ground height stated outright so the break-off's altitude test can be
     * driven either side of its threshold.
     */
    private static DroneSnapshot striker(Vec3 pos, double groundY, boolean armed, Vec3 velocity,
                                         Vec3 destination) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{(byte) 0x7f}), false, pos, velocity, 0.0f,
                FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE,
                DroneState.PAYLOAD_RUN, 40, destination, null, DroneProgram.EMPTY, at(0, 0),
                false, armed, false, true, List.of(),
                DroneBattery.DEFAULT_CAPACITY, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                groundY, groundY, 0L, true, 1, null, 0L, 0L);
    }

    private static void stateMachine(List<Result> out) {
        DroneSnapshot idle = snapshot(DroneState.IDLE, DroneBattery.DEFAULT_CAPACITY, at(0, 0), at(100, 0), false);
        DronePlan plan = DroneBrain.think(idle, SquadView.solo(idle));
        out.add(check("state/idle-launches", plan.nextState() == DroneState.TAKEOFF,
                "a charged drone with a destination should take off"));

        DroneSnapshot parked = idle.withDestination(null);
        out.add(check("state/idle-without-mission-stays",
                DroneBrain.think(parked, SquadView.solo(parked)).nextState() == null,
                "a drone with nowhere to go should stay parked"));

        DroneSnapshot armed = snapshot(DroneState.TRANSIT, DroneBattery.DEFAULT_CAPACITY,
                at(0, 0), at(100, 0), false).withPayload(true);
        out.add(check("state/armed-breaks-to-run",
                DroneBrain.think(armed, SquadView.solo(armed)).nextState() == DroneState.PAYLOAD_RUN,
                "an armed drone inside the run-in distance should start its attack run"));

        DroneSnapshot wreck = snapshot(DroneState.DOWNED, 1000.0, at(0, 0), at(100, 0), false);
        DronePlan wreckPlan = DroneBrain.think(wreck, SquadView.solo(wreck));
        out.add(check("state/downed-is-terminal", wreckPlan.nextState() == null,
                "a downed drone must never transition back out of DOWNED"));
        out.add(check("state/downed-falls", wreckPlan.velocity().y < 0.0,
                "a downed drone should be falling"));

        for (DroneState state : DroneState.values()) {
            DroneSnapshot s = snapshot(state, 1000.0, at(0, 0), at(100, 0), false);
            out.add(check("state/" + state.name().toLowerCase() + "/plans",
                    DroneBrain.think(s, SquadView.solo(s)) != null,
                    "no plan produced for " + state));
        }
    }

    private static void persistence(List<Result> out) {
        DroneMission m = new DroneMission();
        m.destination = new Vec3(123.5, 70.0, -456.5);
        m.exfil = new Vec3(1.0, 2.0, 3.0);
        m.count = 4;
        m.formationId = Formations.rl("column");
        m.cruiseSpeed = 0.9;
        m.cruiseAltitude = 55.0;
        m.batteryCapacity = 1234.0;
        m.payloadId = WarheadRegistry.defaultId();
        m.releaseSpeed = 1.7;

        CompoundTag tag = m.save();
        DroneMission back = DroneMission.load(tag);
        boolean same = back.destination.equals(m.destination)
                && back.exfil != null && back.exfil.equals(m.exfil)
                && back.count == m.count
                && back.formationId.equals(m.formationId)
                && back.cruiseSpeed == m.cruiseSpeed
                && back.cruiseAltitude == m.cruiseAltitude
                && back.batteryCapacity == m.batteryCapacity
                && m.payloadId.equals(back.payloadId)
                && back.releaseSpeed == m.releaseSpeed;
        out.add(check("persistence/mission-round-trip", same,
                "a mission did not survive save/load intact"));

        DroneBattery battery = new DroneBattery(900.0, 2000.0);
        CompoundTag batteryTag = new CompoundTag();
        battery.save(batteryTag);
        DroneBattery loaded = DroneBattery.load(batteryTag);
        out.add(check("persistence/battery-round-trip",
                loaded.charge() == 900.0 && loaded.capacity() == 2000.0,
                "a battery did not survive save/load intact"));
        DroneBattery restored = new DroneBattery(DroneBattery.DEFAULT_CAPACITY);
        restored.setCharge(100.0);
        out.add(check("persistence/set-charge-not-recharge", restored.charge() == 100.0,
                "setCharge must replace the charge, not add to it"));
    }

    private static void warheads(List<Result> out) {
        out.add(check("warhead/default-exists", WarheadRegistry.exists(WarheadRegistry.defaultId()),
                "the default warhead id is not registered"));
        boolean allResolve = true;
        for (ResourceLocation id : WarheadRegistry.ids()) {
            if (WarheadRegistry.get(id) == null) {
                allResolve = false;
            }
        }
        out.add(check("warhead/all-resolve", allResolve,
                "a registered warhead id did not resolve to a detonation"));
        out.add(check("warhead/unknown-rejected", !WarheadRegistry.exists(
                        ResourceLocation.fromNamespaceAndPath("wfballistics", "definitely_not_a_warhead")),
                "an unregistered warhead id was reported as existing"));
    }

    /**
     * Fire kinds. {@link com.wf.wfballistics.fire.FireType} ordinals are written to disk by
     * {@code WFFireData} and synched as the variant of {@code FireLingeringEntity}, so reordering the
     * constants silently turns saved phosphorus into diesel. These pin the wire values down.
     */
    private static void fire(List<Result> out) {
        out.add(check("fire/ordinals-are-stable",
                FireType.NORMAL.id() == 0 && FireType.PHOSPHORUS.id() == 1,
                "NORMAL=" + FireType.NORMAL.id() + " PHOSPHORUS=" + FireType.PHOSPHORUS.id()));
        out.add(check("fire/round-trips-through-its-id",
                FireType.byId(FireType.PHOSPHORUS.id()) == FireType.PHOSPHORUS,
                "PHOSPHORUS -> " + FireType.PHOSPHORUS.id() + " -> "
                        + FireType.byId(FireType.PHOSPHORUS.id())));
        int past = FireType.values().length;
        out.add(check("fire/unknown-id-falls-back",
                FireType.byId(-1) == FireType.NORMAL && FireType.byId(past) == FireType.NORMAL,
                "id -1 -> " + FireType.byId(-1) + ", id " + past + " -> " + FireType.byId(past)));
        out.add(check("fire/phosphorus-is-the-nastier-one",
                FireType.PHOSPHORUS.damage > FireType.NORMAL.damage
                        && FireType.PHOSPHORUS.burnTicks > FireType.NORMAL.burnTicks
                        && FireType.PHOSPHORUS.survivesWater && !FireType.NORMAL.survivesWater,
                "dmg " + FireType.NORMAL.damage + " -> " + FireType.PHOSPHORUS.damage
                        + ", burn " + FireType.NORMAL.burnTicks + " -> " + FireType.PHOSPHORUS.burnTicks
                        + ", waterproof " + FireType.PHOSPHORUS.survivesWater));
    }

    private static Vec3 at(double x, double z) {
        return new Vec3(x, 100.0, z);
    }

    private static DroneSnapshot snapshot(DroneState state, double charge, Vec3 pos, Vec3 destination,
                                          boolean cargo) {
        return new DroneSnapshot(UUID.nameUUIDFromBytes(new byte[]{1}), false, pos, Vec3.ZERO, 0.0f,
                FlightAttitude.LEVEL, Airframe.QUADCOPTER, DroneNav.NONE,
                state, 5, destination, null, DroneProgram.EMPTY, pos, cargo, false, false, true, List.of(),
                charge, DroneBattery.DEFAULT_CAPACITY, PowerProfile.DEFAULT,
                DroneEntity.DEFAULT_CRUISE_SPEED, DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                DroneEntity.DEFAULT_CLIMB_RATE, DroneEntity.DEFAULT_RELEASE_SPEED,
                pos.y - DroneEntity.DEFAULT_CRUISE_ALTITUDE, destination.y - DroneEntity.DEFAULT_CRUISE_ALTITUDE,
                0L, true, 1, null, 0L, 0L);
    }

    /**
     * The queue: that it advances, that it hands the state machine the right arrival state, that it survives
     * being written down, and that a drone with no program still behaves exactly as it did before there were
     * any. That last one is the load-bearing check: every existing mission depends on it.
     */
    private static void programs(List<Result> out) {
        Vec3 drop = at(200, 0);
        Vec3 watch = at(400, 0);
        DroneProgram program = DroneProgram.of(List.of(
                new DroneTask.MoveTo(at(100, 0)),
                new DroneTask.Deliver(drop),
                new DroneTask.Loiter(watch, 50.0, 0),
                new DroneTask.Exfil()));

        out.add(check("program/starts-at-first-step",
                program.current() instanceof DroneTask.MoveTo, "a fresh program flies its first step"));
        out.add(check("program/advances-in-order",
                program.advanced().current() instanceof DroneTask.Deliver
                        && program.advanced().advanced().current() instanceof DroneTask.Loiter,
                "advancing should walk the steps in the order they were written"));
        out.add(check("program/runs-out",
                program.advanced().advanced().advanced().advanced().current() == null,
                "a program past its last step should report nothing left to do"));
        out.add(check("program/has-next-tracks-the-cursor",
                program.hasNext() && !program.advanced().advanced().advanced().hasNext(),
                "hasNext is what tells an arrival handler whether to carry on or go home"));

        DroneSnapshot moving = snapshot(DroneState.TRANSIT, DroneBattery.DEFAULT_CAPACITY, at(0, 0), drop, true)
                .withProgram(program);
        out.add(check("program/waypoint-has-no-arrival-state", moving.arrivalState() == null,
                "a plain waypoint should not hand over to anything, it should just be passed"));
        out.add(check("program/deliver-step-hands-over-to-deliver",
                moving.withProgram(program.advanced()).arrivalState() == DroneState.DELIVER,
                "a deliver step should put the drone into DELIVER on arrival"));
        out.add(check("program/loiter-step-hands-over-to-surveil",
                moving.withProgram(program.advanced().advanced()).arrivalState() == DroneState.SURVEIL,
                "a loiter step should put the drone on station"));

        DroneSnapshot bare = snapshot(DroneState.TRANSIT, DroneBattery.DEFAULT_CAPACITY, at(0, 0), drop, true);
        out.add(check("program/absent-falls-back-to-deliver", bare.arrivalState() == DroneState.DELIVER,
                "a drone with no program should deliver exactly as it did before programs existed"));
        out.add(check("program/absent-armed-falls-back-to-run-in",
                bare.withPayload(true).arrivalState() == DroneState.PAYLOAD_RUN,
                "an armed drone with no program should still break into an attack run"));

        DroneProgram edited = program.removing(0).moved(0, 1);
        out.add(check("program/edits-are-immutable", program.tasks().size() == 4 && edited.tasks().size() == 3,
                "editing must return a new program rather than mutating the one being flown"));
        out.add(check("program/reorders", edited.tasks().get(0) instanceof DroneTask.Loiter,
                "moving a step down should swap it with the one below"));
        DroneProgram full = program;
        for (int i = 0; i < DroneTask.MAX_STEPS + 8; i++) {
            full = full.plus(new DroneTask.MoveTo(at(i, i)));
        }
        out.add(check("program/is-capped", full.tasks().size() == DroneTask.MAX_STEPS,
                "a program arrives from a client, so its length has to be bounded; got " + full.tasks().size()));

        DroneProgram loaded = DroneProgram.load(program.save());
        out.add(check("program/survives-nbt",
                loaded.tasks().size() == 4 && loaded.current() instanceof DroneTask.MoveTo
                        && loaded.tasks().get(2) instanceof DroneTask.Loiter loiter
                        && Math.abs(loiter.radius() - 50.0) < 1.0E-9
                        && loaded.tasks().get(2).at().equals(watch),
                "a saved program should load back identical"));

        Vec3 origin = at(0, 0);
        double route = program.routeLength(origin, origin);
        double firstHop = origin.distanceTo(at(100, 0));
        out.add(check("program/prices-every-stop", route > firstHop * 2.0,
                "a four-step program priced at " + (int) route + "m should cost far more than its first "
                        + (int) firstHop + "m hop, or the dispatcher will launch drones that strand"));
        out.add(check("program/ends-at-the-exfil-when-it-says-so",
                program.endsAt(drop, origin).equals(origin),
                "a program ending in an exfil step ends at the exfil point"));
        out.add(check("program/ends-at-its-last-stop",
                DroneProgram.of(List.of(new DroneTask.Deliver(drop))).endsAt(origin, origin).equals(drop),
                "a program with no exfil step ends wherever its last step was"));

        DroneSnapshot onStation = snapshot(DroneState.SURVEIL, DroneBattery.DEFAULT_CAPACITY,
                at(300, 0), watch, false).withExfil(origin).withProgram(program.advanced().advanced());
        double justEnough = onStation.power().costToTravel(onStation.airframe(),
                onStation.horizontalDistanceTo(origin), onStation.cruiseSpeed(), false)
                + PowerPolicy.LANDING_RESERVE;
        DroneSnapshot nearlyOut = snapshot(DroneState.SURVEIL, justEnough + 1.0, at(300, 0), watch, false)
                .withExfil(origin).withProgram(program.advanced().advanced());
        out.add(check("program/loiter-breaks-station-before-it-is-too-late",
                !PowerPolicy.canHoldStation(nearlyOut),
                "a drone with only just enough to get home must already have left station"));
        out.add(check("program/loiter-stays-while-it-can-afford-to",
                PowerPolicy.canHoldStation(onStation),
                "a full battery over a nearby target should be allowed to keep watching"));
        out.add(check("program/loiter-leaving-goes-to-exfil",
                PowerPolicy.override(nearlyOut) == DroneState.EXFIL,
                "breaking station should send the drone home, not land it where it stands"));
    }

    /**
     * Losing the leader. The property under test is not "somebody takes over", the old ordering did that
     * implicitly, but that <em>which</em> drone takes over is not predictable, and that having taken over it
     * stays taken over.
     */
    private static void succession(List<Result> out) {
        RandomSource random = RandomSource.create(20250820L);

        List<DroneCarrier> withLeader = stubSquad(4, 0);
        DroneAiScheduler.promoteIfLeaderless(random, withLeader);
        out.add(check("squad/live-leader-is-left-alone",
                withLeader.stream().filter(DroneCarrier::leader).count() == 1 && withLeader.get(0).leader(),
                "a squad that still has its leader must not promote anyone else"));

        List<DroneCarrier> orphaned = stubSquad(4, -1);
        DroneAiScheduler.promoteIfLeaderless(random, orphaned);
        out.add(check("squad/leaderless-gets-exactly-one-leader",
                orphaned.stream().filter(DroneCarrier::leader).count() == 1,
                "a decapitated squad should end up with exactly one leader"));

        DroneCarrier promoted = orphaned.stream().filter(DroneCarrier::leader).findFirst().orElseThrow();
        for (int i = 0; i < 20; i++) {
            DroneAiScheduler.promoteIfLeaderless(random, orphaned);
        }
        out.add(check("squad/promotion-sticks",
                orphaned.stream().filter(DroneCarrier::leader).count() == 1 && promoted.leader(),
                "re-running succession must not move the leadership around"));

        boolean[] everPromoted = new boolean[4];
        for (int trial = 0; trial < 200; trial++) {
            List<DroneCarrier> squad = stubSquad(4, -1);
            DroneAiScheduler.promoteIfLeaderless(random, squad);
            for (int i = 0; i < squad.size(); i++) {
                if (squad.get(i).leader()) {
                    everPromoted[i] = true;
                }
            }
        }
        boolean spread = true;
        for (boolean seen : everPromoted) {
            spread &= seen;
        }
        out.add(check("squad/successor-is-not-predictable", spread,
                "over 200 decapitations every member should have led at least once; a fixed order makes the "
                        + "next drone worth shooting knowable in advance"));

        List<DroneCarrier> alone = stubSquad(1, -1);
        DroneAiScheduler.promoteIfLeaderless(random, alone);
        out.add(check("squad/last-survivor-leads-itself", alone.get(0).leader(),
                "one drone left is still a squad, and it leads itself"));
        out.add(check("squad/empty-squad-is-harmless",
                runsWithoutThrowing(() -> DroneAiScheduler.promoteIfLeaderless(random, List.of())),
                "promoting nobody must not throw"));
    }

    private static boolean runsWithoutThrowing(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @param leaderIndex which member is the leader, or -1 for a squad whose leader has been shot down
     */
    private static List<DroneCarrier> stubSquad(int size, int leaderIndex) {
        List<DroneCarrier> members = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(new StubCarrier(UUID.nameUUIDFromBytes(new byte[]{(byte) i}), i == leaderIndex));
        }
        return members;
    }

    /**
     * The least a {@link DroneCarrier} can be and still have succession run against it. Only the squad
     * bookkeeping is real; anything that would need a world throws, which is the point, if succession ever
     * starts reaching for one, this stops compiling being enough and the test says so.
     */
    private static final class StubCarrier implements DroneCarrier {
        private final UUID id;
        private boolean leader;

        StubCarrier(UUID id, boolean leader) {
            this.id = id;
            this.leader = leader;
        }

        @Override
        public UUID droneId() {
            return this.id;
        }

        @Override
        public long squadId() {
            return 7L;
        }

        @Override
        public ResourceLocation coordinationId() {
            return CoordinationModels.DEFAULT;
        }

        @Override
        public boolean leader() {
            return this.leader;
        }

        @Override
        public void promote() {
            this.leader = true;
        }

        @Override
        public ResourceLocation formationId() {
            return Formations.DEFAULT;
        }

        @Override
        public double formationSpacing() {
            return SPACING;
        }

        @Override
        public boolean carrierAlive() {
            return true;
        }

        @Override
        public DroneSnapshot snapshot(net.minecraft.server.level.ServerLevel level, long gameTime) {
            throw new UnsupportedOperationException("succession must not need a snapshot");
        }

        @Override
        public void apply(net.minecraft.server.level.ServerLevel level, DronePlan plan) {
            throw new UnsupportedOperationException("succession must not apply plans");
        }

        @Override
        public void coast(net.minecraft.server.level.ServerLevel level) {
            throw new UnsupportedOperationException("succession must not fly anything");
        }
    }

    private static Result check(String name, boolean passed, String detail) {
        return new Result(name, passed, detail);
    }

    public record Result(String name, boolean passed, String detail) {
    }
}
