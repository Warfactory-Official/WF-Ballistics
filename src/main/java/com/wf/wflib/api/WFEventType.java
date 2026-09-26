package com.wf.wflib.api;

public enum WFEventType {
    SPAWN,
    ASCEND,
    CRUISE,
    ATTACK,
    OFFLOAD,
    ONLOAD,
    DAMAGED,
    INTERCEPTED,
    DESTROYED,
    FUEL_OUT,
    IMPACTED,
    DETONATED,
    TAKEOFF,
    LANDED,
    IDLE,
    CARGO_PICKUP,
    CARGO_DROP,
    EXFIL,
    MISSION_COMPLETE,
    POWER_LOW,
    POWER_OUT,
    SPOTTED,
    /**
     * A squad member took over after its leader was lost.
     */
    PROMOTED,
    /** TV round: operator took over ("on") or let go (detail = what the seeker locked). */
    OPERATOR
}
