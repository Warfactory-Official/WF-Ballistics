package com.wf.wfballistics.drone;

/**
 * What a drone is currently doing. The state decides which {@code DroneStateHandler} steers it and how fast
 * its battery drains; transitions are chosen off-thread by the brain and applied on the world thread.
 */
public enum DroneState {
    /**
     * Parked on the ground. Costs almost nothing and is where a drone ends up when its mission finishes or
     * its battery runs out.
     */
    IDLE,
    TAKEOFF,
    /**
     * Holding over the launch point, waiting for the rest of the flight.
     *
     * <p>A squad is dispatched one drone at a time rather than all at once (see {@code DroneLaunchQueue}), so
     * the first one up would otherwise set off alone and spend the whole outbound leg being caught up with,
     * or, worse, arrive on its own. This is the pause that makes a staggered launch into a formation: each
     * drone climbs out, takes its slot, and nothing departs until everyone is up, in place and settled.
     *
     * <p>Powered, so the battery is still being spent and a drone that cannot afford to wait any longer is
     * overruled and recovers on its own like any other.
     */
    MUSTER,
    /**
     * Cruising to the delivery destination.
     */
    TRANSIT,
    /**
     * Over the destination, descending to release the crate.
     */
    DELIVER,
    /**
     * Over a rendezvous, descending to pick a crate up rather than put one down. The other half of a
     * handshake: what one station's drone left, another station's drone comes for.
     */
    COLLECT,
    /**
     * Holding over one block of a work job, placing it or taking it down.
     *
     * <p>Hovers a little above the block rather than beside it, which is only safe because of the order the
     * plan imposes: a construction builds a layer at a time from the bottom, so the space above the target is
     * always still empty, and a salvage strips from the top, so it has just been emptied. A job worked in any
     * other order would fly drones into their own structure.
     */
    WORK,
    /**
     * At a station, loading building materials or unloading what a demolition recovered.
     */
    SUPPLY,
    /**
     * Attack run: holds a set release speed straight down the line to the target and pickles its payload
     * early enough that the bomb's own fall carries it onto the aim point.
     */
    PAYLOAD_RUN,
    /**
     * On station over a place it was sent to watch: orbiting it, or holding still above it, and reporting
     * whoever turns up. Costs the same as any other powered flight, which is what makes loiter time a real
     * trade against how far away the place is.
     */
    SURVEIL,
    /**
     * Cargo released, heading for the exfil point (by default where it launched from).
     */
    EXFIL,
    LANDING,
    /**
     * Battery flat while airborne: sinking under gravity with rotor drag, no guidance. Becomes {@link #IDLE}
     * on touchdown: a dead drone is recoverable, not destroyed.
     */
    DEPLETED,
    /**
     * Shot down: spinning, falling, and finished. Comes to rest as a wreck on the ground that can still be
     * looted: it never flies again.
     */
    DOWNED;

    public boolean airborne() {
        return this != IDLE;
    }

    /**
     * @return true while the drone is under power and steering itself (so formation and the battery
     * reachability checks apply).
     */
    public boolean powered() {
        return this != IDLE && this != DEPLETED && this != DOWNED;
    }

    /**
     * @return true while the drone is going somewhere and must therefore be kept clear of the ground.
     *
     * <p>The states left out are the ones that are deliberately descending onto a spot they have already
     * arrived over, or that have no thrust to climb with. Both the terrain avoidance in the brain and the
     * hard floor on the entity ask this, because a drone that refused to approach the ground it is trying to
     * land on would never finish a mission.
     */
    public boolean followsTerrain() {
        return this == TAKEOFF || this == MUSTER || this == TRANSIT || this == EXFIL || this == PAYLOAD_RUN;
    }
}
