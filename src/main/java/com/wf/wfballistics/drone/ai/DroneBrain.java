package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.drone.ai.coord.CoordinationModel;
import com.wf.wfballistics.drone.ai.coord.SquadAnchor;
import com.wf.wfballistics.drone.ai.coord.SquadCommand;
import com.wf.wfballistics.drone.ai.state.Cruising;
import com.wf.wfballistics.drone.ai.state.Tuning;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.drone.flight.Multirotor;
import com.wf.wfballistics.drone.nav.DroneNavigation;
import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.nav.PathPlanner;
import com.wf.wfballistics.drone.nav.PlannerStats;
import com.wf.wfballistics.drone.nav.TerrainGuard;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what a squad does next. This is the whole off-thread half of the drone AI: it reads
 * {@link DroneSnapshot}s and returns {@link DronePlan}s, and it is the only place allowed to make those
 * decisions.
 *
 * <p>Each drone is decided in four layers, outermost first: battery, then formation, then its state's own
 * handler, then physics. The first three answer "where do I want to be going"; the last answers "what can
 * this airframe actually do about that", and its answer is the one that gets flown.
 *
 * <p><b>Runs on a worker thread.</b> Everything it can see is a value type, so there is nothing here that
 * could reach into the level: including the terrain, which arrives pre-condensed as a
 * {@code TerrainField}. Keep it that way: see {@link DroneSnapshot}.
 */
public final class DroneBrain {

    private DroneBrain() {
    }

    /**
     * Plan a whole squad in one go. One job holds every member's snapshot, so members can be placed against
     * one another with no cross-thread reads.
     *
     * <p>The squad's {@link CoordinationModel} settles the reference frame first, and the shape of what
     * happens next is its decision rather than this method's. The leader is still planned before anybody
     * else, because a model that references the leader wants the decision it has just made rather than the
     * state it was in a tick ago, and a model that does not reference it simply ignores the chance.
     */
    public static SquadPlan planSquad(SquadView squad) {
        WorldThread.assertOff("squad planning");
        CoordinationModel model = squad.coordination();
        SquadAnchor anchor = model.advance(squad, squad.anchor());

        List<DronePlan> plans = new ArrayList<>(squad.size());
        DronePlan leaderPlan = think(squad.leader(), squad, null, anchor);
        plans.add(leaderPlan);
        anchor = model.refresh(squad, anchor, leaderPlan);
        for (DroneSnapshot member : squad.slots()) {
            if (!squad.isLeader(member)) {
                plans.add(think(member, squad, leaderPlan, anchor));
            }
        }
        return new SquadPlan(squad.squadId(), plans, squad.size() > 1 ? anchor : null);
    }

    public static DronePlan think(DroneSnapshot self, SquadView squad) {
        return think(self, squad, null, null);
    }

    private static DronePlan think(DroneSnapshot self, SquadView squad, @Nullable DronePlan leaderPlan,
                                   @Nullable SquadAnchor anchor) {
        List<DroneAction> actions = new ArrayList<>(3);
        DroneStateHandler current = DroneStateRegistry.get(self.state());
        CoordinationModel model = squad.coordination();
        DroneSnapshot routed = self;

        boolean escorting = leaderPlan != null && escorts(routed, squad);
        DroneState next = PowerPolicy.override(routed);
        if (next != null && next != routed.state()) {
            actions.add(powerLog(routed, next));
            if (routed.currentTask() != null) {
                actions.add(new DroneAction.AbortProgram("battery"));
            }
            escorting = false;
        } else if (escorting && leaderFinished(squad, leaderPlan)) {
            actions.add(new DroneAction.CompleteMission("escort complete"));
            next = routed.state() == DroneState.LANDING ? null : DroneState.LANDING;
            escorting = false;
        } else if (escorting) {
            DroneState leaderState = leaderPlan.nextState() != null
                    ? leaderPlan.nextState() : squad.leader().state();
            next = inherited(leaderState, routed.state());
            if (routed.currentTask() != null
                    && leaderPlan.actions().stream().anyMatch(a -> a instanceof DroneAction.AdvanceTask)) {
                actions.add(new DroneAction.AdvanceTask());
            }
        } else {
            next = current.next(routed, squad);
            current.act(routed, squad, actions);
        }

        DroneStateHandler active = next != null ? DroneStateRegistry.get(next) : current;
        DroneState flying = next != null ? next : routed.state();
        Vec3 desired;
        Vec3 feedForward = Vec3.ZERO;
        if (stationed(routed, squad, model, anchor, escorting, flying)) {
            SquadCommand command = model.guide(routed, squad, anchor);
            desired = command.velocity();
            feedForward = command.acceleration();
        } else {
            desired = active.guide(routed, squad);
        }
        boolean shielded = squad.isLeader(routed) && !model.stationsLeader() && anchors(squad);
        if (squad.size() > 1 && flying.powered() && !shielded) {
            desired = desired.add(Cruising.separation(routed, squad));
        }
        if (flying.followsTerrain()) {
            desired = TerrainGuard.enforce(desired, routed.pos().y, routed.nav().requiredAltitude(),
                    routed.climbRate());
        }

        Vec3 velocity;
        FlightAttitude attitude;
        if (flying.powered()) {
            Multirotor.Step step = Multirotor.step(routed.velocity(), routed.attitude(), desired, feedForward,
                    routed.airframe(), routed.massFactor());
            velocity = step.velocity();
            attitude = step.attitude();
        } else {
            velocity = desired;
            attitude = flying == DroneState.DOWNED
                    ? routed.attitude().tumble(Tuning.DOWNED_TUMBLE, Tuning.DOWNED_TUMBLE_LIMIT)
                    : routed.attitude().relax(Tuning.UNPOWERED_LEVELLING);
        }

        float yaw = heading(routed, flying, velocity);
        return DronePlan.of(routed, velocity, attitude, yaw, next, actions);
    }

    /**
     * @return which way the drone should be facing at the end of this tick.
     *
     * <p>Normally that is wherever it is travelling. The two exceptions are the parts of a flight that are
     * going somewhere without moving yet, the climb-out and the form-up, where the horizontal velocity is
     * made almost entirely of squadmates shoving past, and a heading taken from it points wherever the last
     * shove came from. Since the formation frame is built on the leader's heading, that is not a cosmetic
     * problem: the flight assembles facing a direction nobody chose and then swings the whole formation
     * round the moment it departs. Pointing at the destination costs nothing, because that is where it is
     * going.
     */
    private static float heading(DroneSnapshot self, DroneState flying, Vec3 velocity) {
        if (flying == DroneState.DOWNED) {
            return Steering.wrap(self.yaw() + Tuning.DOWNED_SPIN);
        }
        Vec3 outbound = self.outbound();
        if ((flying == DroneState.TAKEOFF || flying == DroneState.MUSTER) && outbound != null) {
            return Steering.faceToward(self.pos(), outbound, self.yaw());
        }
        return Steering.faceTravel(velocity, self.yaw(), self.cruiseSpeed() * Steering.HEADING_MIN_SPEED);
    }

    /**
     * Search for a route over the terrain.
     *
     * <p><b>Deliberately not part of {@link #think}.</b> An A* over a few thousand cells takes orders of
     * magnitude longer than deciding a tick of flight does, and the scheduler will not re-dispatch a squad
     * whose job is still running, so folding the search into the steering job means every drone in that
     * squad misses its plan for as long as the search takes and coasts instead. The visible result is a
     * flight that stutters every time it replans. Run as its own job, the search takes as long as it likes
     * while the steering keeps landing every tick, and the route is adopted whenever it turns up.
     *
     * @return the route, or null if there was nothing to plan
     */
    @Nullable
    public static DronePath planRoute(DroneSnapshot self) {
        WorldThread.assertOff("terrain path search");
        Vec3 goal = self.goal();
        if (!self.nav().canPlan() || goal == null) {
            return null;
        }
        double clearance = Math.max(PathPlanner.CLEARANCE, self.cruiseAltitude());
        double ceiling = Math.max(self.pos().y, self.cruiseY()) + DroneNavigation.CEILING_MARGIN;
        long startedAt = System.nanoTime();
        try {
            return PathPlanner.plan(self.nav().field(), self.pos(), goal, clearance, ceiling, self.gameTime());
        } finally {
            PlannerStats.record((System.nanoTime() - startedAt) / 1000L);
        }
    }

    private static boolean leaderFinished(SquadView squad, DronePlan leaderPlan) {
        return !squad.leader().state().powered()
                || leaderPlan.actions().stream().anyMatch(a -> a instanceof DroneAction.CompleteMission);
    }

    /**
     * @return the state an escorting follower should adopt from its leader, or null to stay as it is.
     *
     * <p>A follower pinned to its slot never reaches its own waypoints, so while escorting it takes the
     * leader's state rather than waiting on arrivals that will never happen. That is the rule, and it has
     * exactly one exception.
     *
     * <p><b>A climb-out is not inherited.</b> A follower already at altitude that is handed its leader's
     * {@link DroneState#TAKEOFF} drops out of {@link DroneState#MUSTER}; {@code TakeoffHandler} then sees a
     * drone that has finished climbing and a flight that is not ready, and sends it straight back to MUSTER;
     * and the two hand it to each other for as long as the leader is still climbing. Every hop is a
     * {@code DroneEntity#setState}, which puts {@code stateTicks} back to zero — and {@code MUSTER_TIMEOUT} is
     * counted in {@code stateTicks}, so the one bound on the whole form-up cannot accumulate while that is
     * going on. A flight in this cycle waits not for sixty seconds but for ever.
     *
     * <p>It needs the leader to be the last one up, which sounds unreachable for a squad that launches its
     * leader first and is not: the leader is the drone carrying the crate, so it is the heaviest and slowest
     * climber in the flight, and the launch gap the pad offers can be set to 0, which puts the whole squad in
     * the air at once. Nothing is lost by declining it — the follower is already where a climb-out would have
     * taken it.
     */
    @Nullable
    public static DroneState inherited(DroneState leaderState, DroneState own) {
        if (leaderState == DroneState.TAKEOFF) {
            return null;
        }
        return leaderState != own ? leaderState : null;
    }

    /**
     * @return true if this drone's flying is the formation's to decide rather than its own handler's.
     *
     * <p>Public because it is a real part of the contract and not an implementation detail: several
     * behaviours are defined by <em>not</em> being flown in formation (an attack run, a climb-out, a
     * construction sortie) and each of those is a decision worth being able to assert on.
     */
    public static boolean escorts(DroneSnapshot self, SquadView squad) {
        return !squad.isLeader(self) && self.state().powered() && self.hasMission()
                && self.state() != DroneState.PAYLOAD_RUN
                && self.state() != DroneState.TAKEOFF
                && self.assignment() == null;
    }

    /**
     * @return true if this drone's flying is the {@link CoordinationModel}'s to decide this tick, rather
     * than its own handler's.
     *
     * <p>A follower on escort always is. The leader is too, but only under a model that keeps its reference
     * somewhere other than the leader, {@link CoordinationModel#stationsLeader()}, because a drone cannot
     * station-keep on itself, and asking it to would be a controller whose reference moves whenever its
     * output does.
     *
     * <p>An attack run is nobody's: every drone needs its own release solution, since each is offset from
     * the others and reaches its own lead point at its own moment.
     */
    private static boolean stationed(DroneSnapshot self, SquadView squad, CoordinationModel model,
                                     @Nullable SquadAnchor anchor, boolean escorting, DroneState flying) {
        if (anchor == null || squad.size() <= 1) {
            return false;
        }
        if (escorting) {
            return true;
        }
        return squad.isLeader(self) && model.stationsLeader() && flying.powered() && self.hasMission()
                && flying != DroneState.PAYLOAD_RUN;
    }

    /**
     * @return true if anyone in this squad is flying formation on its leader.
     *
     * <p>Only consulted for a model whose reference <em>is</em> the leader. For those, letting the drones
     * stationed on it shove it about is a loop with gain: the push moves the leader, every slot in the squad
     * moves with it, the followers chase the slots and push again. The squad still keeps itself apart: the
     * followers do all of the avoiding, which is the right way round, since the leader is the one flying the
     * mission and they are the ones with somewhere else to be.
     *
     * <p>A model that references a computed point has nothing here to protect: the frame is virtual, so
     * nothing can shove it, and the leader is free to avoid its squadmates like anybody else. Neither has a
     * squad with nobody in formation: an attack run being the case that matters.
     */
    private static boolean anchors(SquadView squad) {
        for (DroneSnapshot member : squad.slots()) {
            if (!squad.isLeader(member) && escorts(member, squad)) {
                return true;
            }
        }
        return false;
    }

    private static DroneAction.Log powerLog(DroneSnapshot self, DroneState forced) {
        float percent = (float) (100.0 * self.charge() / self.capacity());
        if (forced == DroneState.DEPLETED) {
            return new DroneAction.Log(WFEventType.POWER_OUT, "battery flat, sinking");
        }
        String reason = forced == DroneState.EXFIL ? "aborting delivery, returning" : "cannot reach exfil, landing";
        return new DroneAction.Log(WFEventType.POWER_LOW, String.format("%s (%.0f%%)", reason, percent));
    }

    /**
     * @return what a drone should do when its plan didn't arrive in time: hold the last velocity, bleeding
     * it off so a stalled planner degrades into a hover rather than a runaway.
     */
    public static Vec3 coast(@Nullable Vec3 lastVelocity) {
        return lastVelocity == null ? Vec3.ZERO : lastVelocity.scale(0.85);
    }
}
