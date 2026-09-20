package com.wf.wflib.drone;

/** What a drone is currently doing. */
public enum DroneState {
    /** Parked on the ground. */
    IDLE,
    TAKEOFF,
    /** Holding over the launch point, waiting for the rest of the flight. */
    MUSTER,
    /**
     * Cruising to the delivery destination.
     */
    TRANSIT,
    /**
     * Over the destination, descending to release the crate.
     */
    DELIVER,
    /** Over a rendezvous, descending to pick a crate up rather than put one down. */
    COLLECT,
    /** Holding over one block of a work job, placing it or taking it down. */
    WORK,
    /**
     * At a station, loading building materials or unloading what a demolition recovered.
     */
    SUPPLY,
    /**
     * Attack run: holds a set release speed straight down the line to the target and pickles its payload early
     * enough that the bomb's own fall carries it onto the aim point.
     */
    PAYLOAD_RUN,
    /**
     * Mine-laying run: flies a lane through the ordered point at release speed and dispenses its rack at a steady
     * interval along it.
     */
    MINELAY,
    /**
     * On station over a place it was sent to watch: orbiting it, or holding still above it, and reporting whoever
     * turns up.
     */
    SURVEIL,
    /**
     * Cargo released, heading for the exfil point (by default where it launched from).
     */
    EXFIL,
    LANDING,
    /** Battery flat while airborne: sinking under gravity with rotor drag, no guidance. */
    DEPLETED,
    /** Shot down: spinning, falling, and finished. */
    DOWNED;

    public boolean airborne() {
        return this != IDLE;
    }

    /**
     * @return true while the drone is under power and steering itself (so formation and the battery
     *      reachability checks apply).
     */
    public boolean powered() {
        return this != IDLE && this != DEPLETED && this != DOWNED;
    }

    /**
     * @return true while the drone is going somewhere and must therefore be kept clear of the ground.
     */
    public boolean followsTerrain() {
        return this == TAKEOFF || this == MUSTER || this == TRANSIT || this == EXFIL || this == PAYLOAD_RUN
                || this == MINELAY;
    }

    /**
     * @return true for the states every drone has to fly for itself, rather than holding a formation slot
     *      and taking its state from the leader.
     */
    public boolean flownIndividually() {
        return this == PAYLOAD_RUN || this == MINELAY;
    }
}
