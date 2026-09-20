package com.wf.wflib.drone.ai;

import com.wf.wflib.api.WFEventType;
import net.minecraft.world.phys.Vec3;

/** A world change the brain decided on but is not allowed to perform itself. */
public sealed interface DroneAction {

    /**
     * Release the slung crate so it falls to the ground at {@code at}.
     */
    record DropCargo(Vec3 at) implements DroneAction {
    }

    /**
     * Pickle the explosive payload.
     *
     * @param aim the point the release was solved for, for telemetry
     */
    record DropPayload(Vec3 aim) implements DroneAction {
    }

    /**
     * Put one mine out of the rack down.
     *
     * @param aim where this one was meant to land, for telemetry
     */
    record LayMine(Vec3 aim) implements DroneAction {
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
     * This staging waypoint has been reached; move on to the next one, or to the real destination if that was the
     * last.
     */
    record AdvanceLeg() implements DroneAction {
    }

    /** This step of the program is done; move on to the next one and take its destination. */
    record AdvanceTask() implements DroneAction {
    }

    /** Abandon everything still queued. */
    record AbortProgram(String reason) implements DroneAction {
    }

    /** The drone is on station over the block it claimed and has stopped moving: do the work. */
    record FinishWork(net.minecraft.core.BlockPos at) implements DroneAction {
    }

    /** The drone is on station over its supply station: move items, whichever way this job moves them. */
    record ExchangeSupplies() implements DroneAction {
    }

    /** Append an entry to this drone's telemetry timeline. */
    record Log(WFEventType type, String detail) implements DroneAction {
    }
}
