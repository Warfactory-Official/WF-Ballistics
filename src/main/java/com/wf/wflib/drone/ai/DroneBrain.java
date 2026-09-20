package com.wf.wflib.drone.ai;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.RotorDamage;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.drone.ai.coord.CoordinationModel;
import com.wf.wflib.drone.ai.coord.SquadAnchor;
import com.wf.wflib.drone.ai.coord.SquadCommand;
import com.wf.wflib.drone.ai.state.Cruising;
import com.wf.wflib.drone.ai.state.Tuning;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.flight.Multirotor;
import com.wf.wflib.drone.nav.DroneNavigation;
import com.wf.wflib.drone.nav.DronePath;
import com.wf.wflib.drone.nav.PathPlanner;
import com.wf.wflib.drone.nav.PlannerStats;
import com.wf.wflib.drone.nav.TerrainGuard;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Decides what a squad does next. */
public final class DroneBrain {

    private DroneBrain() {
    }

    /** Plan a whole squad in one go. */
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
                    ? tumble(routed)
                    : routed.attitude().relax(Tuning.UNPOWERED_LEVELLING);
        }

        float yaw = heading(routed, flying, velocity);
        return DronePlan.of(routed, velocity, attitude, yaw, next, actions);
    }

    /** How a wreck rolls on the way down. */
    private static FlightAttitude tumble(DroneSnapshot self) {
        if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
            return self.attitude();
        }
        RotorDamage rotors = self.rotors();
        double asymmetry = rotors.asymmetry();
        if (!rotors.damaged() || asymmetry < 1.0E-3) {
            return self.attitude().tumble(Tuning.DOWNED_TUMBLE, Tuning.DOWNED_TUMBLE_LIMIT);
        }
        double sin = Math.sin(self.yaw());
        double cos = Math.cos(self.yaw());
        double x = rotors.leanX() * cos + rotors.leanZ() * sin;
        double z = rotors.leanZ() * cos - rotors.leanX() * sin;
        double limit = Tuning.DOWNED_TUMBLE_LIMIT * asymmetry;
        return self.attitude().tumbleToward(x, z, Tuning.DOWNED_TUMBLE, limit);
    }

    /**
     * @return which way the drone should be facing at the end of this tick.
     */
    private static float heading(DroneSnapshot self, DroneState flying, Vec3 velocity) {
        if (flying == DroneState.DOWNED) {
            if (self.altitudeAboveGround() <= Tuning.LAND_CONTACT) {
                return self.yaw();
            }
            float spin = self.rotors().damaged() ? self.rotors().spin() : Tuning.DOWNED_SPIN;
            return Steering.wrap(self.yaw() + spin);
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
     */
    public static boolean escorts(DroneSnapshot self, SquadView squad) {
        return !squad.isLeader(self) && self.state().powered() && self.hasMission()
                && !self.state().flownIndividually()
                && self.state() != DroneState.TAKEOFF
                && self.assignment() == null;
    }

    /**
     * @return true if this drone's flying is the {@link CoordinationModel}'s to decide this tick, rather
     *      than its own handler's.
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
                && !flying.flownIndividually();
    }

    /**
     * @return true if anyone in this squad is flying formation on its leader.
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
     *      it off so a stalled planner degrades into a hover rather than a runaway.
     */
    public static Vec3 coast(@Nullable Vec3 lastVelocity) {
        return lastVelocity == null ? Vec3.ZERO : lastVelocity.scale(0.85);
    }
}
