package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.drone.DroneProgram;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.DroneTask;
import com.wf.wfballistics.drone.PowerProfile;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.work.WorkAssignment;
import com.wf.wfballistics.drone.nav.DroneNavigation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Everything the AI is allowed to know about one drone, captured on the world thread and then handed to a
 * worker. Deliberately holds no {@code Level}, {@code Entity} or {@code SavedData} reference: that
 * absence is what makes the brain safe to run off-thread, so keep it that way when adding fields: sample
 * the world here, never reach for it later.
 *
 * <p>Built from either a live {@code DroneEntity} or an off-world {@code SimDrone}, so both run the exact
 * same AI.
 *
 * @param attitude           how the airframe is leaning and how hard the rotors are working: real state the
 *                           flight model integrates, not a render-time flourish
 * @param airframe           the physical limits this drone flies within
 * @param nav                its route and what the ground ahead demands of it
 * @param groundY            terrain height under the drone, sampled on the world thread
 * @param destinationGroundY terrain height at the destination, sampled on the world thread
 * @param leg                the staging waypoint currently being flown to, or null to head straight for the
 *                           destination. How an obfuscated route is expressed to the guidance layer
 * @param program            the queued mission steps and how far through them this drone is. Immutable, so
 *                           the whole queue rides along rather than being looked up
 * @param collecting         true if this drone is going to a rendezvous to pick a crate <em>up</em> rather
 *                           than to put one down
 * @param zoneClear          false if a player is standing near the drop point. Sampled on the world thread,
 *                           because a worker cannot see players; it is what lets the drop be called off
 * @param contacts           players spotted on station since the last snapshot, for the same reason: a
 *                           worker cannot see them, so the world thread hands them over already named.
 *                           Only ever filled while watching somewhere: see {@code SurveilHandler}
 * @param hasPayload         true while an explosive payload is still slung and armed
 * @param releaseSpeed       the speed an attack run is flown at
 * @param squadSize          how many drones the mission ordered, which is not the same as how many have
 *                           turned up. A flight launches one at a time, so a squad of six with two still on
 *                           the pad has a {@link SquadView} of four and would otherwise look complete to
 *                           itself: see {@code MusterHandler}
 * @param assignment         what this drone is doing for a work job, or null if it is not on one. Sampled
 *                           rather than looked up, for the usual reason: a handler runs off-thread and must
 *                           not be able to reach a {@code WorkQueue}, let alone claim from one
 * @param seed               per-drone deterministic seed, so a decision doesn't depend on thread timing
 */
public record DroneSnapshot(UUID id, boolean simulated, Vec3 pos, Vec3 velocity, float yaw,
                            FlightAttitude attitude, Airframe airframe, DroneNav nav,
                            DroneState state, int stateTicks,
                            @Nullable Vec3 destination, @Nullable Vec3 leg, DroneProgram program, Vec3 exfil,
                            boolean hasCargo, boolean hasPayload, boolean collecting, boolean zoneClear,
                            List<String> contacts,
                            double charge, double capacity, PowerProfile power,
                            double cruiseSpeed, double cruiseAltitude, double climbRate, double releaseSpeed,
                            double groundY, double destinationGroundY,
                            long squadId, boolean leader, int squadSize,
                            @Nullable WorkAssignment assignment,
                            long gameTime, long seed) {

    /**
     * @return true if this drone has somewhere to be. A drone without a destination stays parked.
     */
    public boolean hasMission() {
        return destination != null;
    }

    public double horizontalDistanceTo(Vec3 target) {
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * @return the altitude this drone should hold in level flight: its cruise height above the terrain
     * beneath it.
     */
    public double cruiseY() {
        return groundY + cruiseAltitude;
    }

    /**
     * @return height above the terrain directly below.
     */
    public double altitudeAboveGround() {
        return pos.y - groundY;
    }

    /**
     * @return the point this drone is routing to right now, or null if it is going nowhere. A staging
     * waypoint takes precedence over the real destination: that is what makes a dogleg a dogleg rather than
     * a suggestion the guidance immediately ignores.
     */
    @Nullable
    public Vec3 goal() {
        return DroneNavigation.goal(state, leg, destination, exfil);
    }

    /**
     * @return where this drone will be heading once it is done climbing out or forming up, or null if it is
     * going nowhere.
     *
     * <p>Not the same question as {@link #goal()}, which answers "where am I routing to <em>now</em>" and is
     * deliberately null while a drone is still on the ground or holding over the pad. This is what it should
     * be <em>pointing</em> at meanwhile: see {@code Steering#faceToward}.
     */
    @Nullable
    public Vec3 outbound() {
        return DroneNavigation.goal(DroneState.TRANSIT, leg, destination, exfil);
    }

    /**
     * @return true if the drone is on a staging leg rather than heading for its actual destination.
     */
    public boolean onLeg() {
        return leg != null && state.followsTerrain();
    }

    /**
     * @return the step being flown, or null when the drone has no queue (or has run out of it).
     */
    @Nullable
    public DroneTask currentTask() {
        return program.current();
    }

    /**
     * @return the state to hand over to on arriving at the destination.
     *
     * <p>Falls back to the pre-program behaviour when there is no queue: armed drones run in, collecting
     * drones collect, everyone else delivers. That fallback is what keeps a mission dispatched the old way,
     * or loaded from a world saved before programs existed, flying exactly as it did.
     */
    @Nullable
    public DroneState arrivalState() {
        if (assignment != null) {
            if (assignment.hasOrder() && assignment.siteOpen()) {
                return DroneState.WORK;
            }
            return assignment.hasStation() ? DroneState.SUPPLY : DroneState.EXFIL;
        }
        DroneTask task = program.current();
        if (task != null) {
            return task.arrivalState();
        }
        if (hasPayload) {
            return DroneState.PAYLOAD_RUN;
        }
        return collecting ? DroneState.COLLECT : DroneState.DELIVER;
    }

    /**
     * @return true if finishing the current step leaves another queued behind it.
     */
    public boolean hasNextTask() {
        return program.hasNext();
    }

    /**
     * @return the total mass as a multiple of the unladen airframe. A slung crate makes the drone slower to
     * climb and heavier on the battery, both through this one number.
     */
    public double massFactor() {
        return power.massFactor(hasCargo || hasPayload);
    }

    public DroneSnapshot withVelocity(Vec3 velocity) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withNav(DroneNav nav) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withDestination(@Nullable Vec3 destination) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withExfil(Vec3 exfil) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withPayload(boolean hasPayload) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withProgram(DroneProgram program) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }

    public DroneSnapshot withContacts(List<String> contacts) {
        return new DroneSnapshot(id, simulated, pos, velocity, yaw, attitude, airframe, nav, state, stateTicks,
                destination, leg, program, exfil, hasCargo, hasPayload, collecting, zoneClear, contacts, charge,
                capacity, power, cruiseSpeed, cruiseAltitude, climbRate, releaseSpeed, groundY,
                destinationGroundY, squadId, leader, squadSize, assignment, gameTime, seed);
    }
}
