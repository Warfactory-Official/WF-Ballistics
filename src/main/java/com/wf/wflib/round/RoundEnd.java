package com.wf.wflib.round;

/** Why a round ended; on the wire with the end position. */
public enum RoundEnd {
    EXPIRED,
    /** Resting round's chunk unloaded; its fuse waits for the chunk ({@link DeferredImpact}). */
    DEFERRED,
    /** {@link Rounds#destroy}. */
    DESTROYED,
    /** Strike listener said {@code DUD}. */
    DUD,
    ENTITY,
    BLOCK,
    /** Proximity, airburst or landed fuse. */
    FUSE,
    /** Client only: level gone or logout. */
    CLEARED;

    static final RoundEnd[] VALUES = values();
}
