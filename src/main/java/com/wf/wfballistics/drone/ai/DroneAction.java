package com.wf.wfballistics.drone.ai;

import com.wf.wfballistics.api.WFEventType;
import net.minecraft.world.phys.Vec3;

/**
 * A world change the brain decided on but is not allowed to perform itself. Actions are inert data; the
 * carrier applies them on the world thread (see {@code DroneCarrier#apply}).
 */
public sealed interface DroneAction {

    /**
     * Release the slung crate so it falls to the ground at {@code at}.
     */
    record DropCargo(Vec3 at) implements DroneAction {
    }

    /**
     * Pickle the explosive payload. It inherits the drone's velocity, so the release point already accounts
     * for how far it will travel while falling.
     *
     * @param aim the point the release was solved for, for telemetry
     */
    record DropPayload(Vec3 aim) implements DroneAction {
    }

    /**
     * The mission is over: clear the destination so the drone stays parked.
     */
    record CompleteMission(String reason) implements DroneAction {
    }

    /**
     * Fly somewhere else instead (battery abort, retasking).
     */
    record Retarget(Vec3 destination) implements DroneAction {
    }

    /**
     * Pick up a crate lying at {@code at}. The collecting half of a handshake.
     */
    record PickUpCargo(Vec3 at) implements DroneAction {
    }

    /**
     * This staging waypoint has been reached; move on to the next one, or to the real destination if that
     * was the last. How a dogleg route is walked.
     */
    record AdvanceLeg() implements DroneAction {
    }

    /**
     * This step of the program is done; move on to the next one and take its destination. The queue's
     * counterpart to {@link AdvanceLeg}, one level up: a leg is a corner of the route to one place, a task is
     * a whole place with something to do when you get there.
     */
    record AdvanceTask() implements DroneAction {
    }

    /**
     * Abandon everything still queued. What a battery abort leaves behind, so a drone sent home early does
     * not pick the rest of its program back up on the way past.
     */
    record AbortProgram(String reason) implements DroneAction {
    }

    /**
     * The drone is on station over the block it claimed and has stopped moving: do the work.
     *
     * <p>Carries the position it believes it is over, which the world thread checks against the claim it
     * actually holds. Those can disagree, a claim can lapse while the drone is settling, and placing a
     * block at a position nobody asked for is much worse than doing nothing.
     */
    record FinishWork(net.minecraft.core.BlockPos at) implements DroneAction {
    }

    /**
     * The drone is on station over its supply station: move items, whichever way this job moves them.
     *
     * <p>Deliberately without a direction. The world thread knows whether this is a construction loading up
     * or a demolition handing over, and encoding that here would mean a worker thread deciding it from a
     * snapshot that could be a tick out of date.
     */
    record ExchangeSupplies() implements DroneAction {
    }

    /**
     * Append an entry to this drone's telemetry timeline. Ignored on a classified mission, which keeps no
     * timeline at all.
     */
    record Log(WFEventType type, String detail) implements DroneAction {
    }
}
