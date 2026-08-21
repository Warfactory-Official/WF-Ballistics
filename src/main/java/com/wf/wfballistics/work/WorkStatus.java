package com.wf.wfballistics.work;

/**
 * Where one {@link WorkOrder} has got to.
 *
 * <p>Stored as an ordinal rather than a name, unlike most enums in this mod: a queue writes one of these per
 * order into a {@code byte[]}, and there can be tens of thousands of them. The ordinals are therefore part of
 * the save format: <b>append new constants at the end</b>.
 */
public enum WorkStatus {
    /**
     * Nobody has it. Available to be claimed, subject to the sequence gate.
     */
    PENDING,
    /**
     * A worker has it and has until its deadline to finish. See {@link WorkQueue#lapse}.
     */
    CLAIMED,
    /**
     * Finished. Terminal.
     */
    DONE,
    /**
     * Tried {@link WorkQueue#MAX_ATTEMPTS} times and failed every time. Terminal, and terminal on purpose:
     * a block that cannot be placed (the space is occupied by bedrock, the material never arrives, the
     * position is outside the world) must stop being handed out, or the queue spends the rest of the job
     * flying drones to it. Counted separately from {@link #DONE} so a job that "finished" with half of it
     * blocked does not read as a success.
     */
    BLOCKED;

    private static final WorkStatus[] VALUES = values();

    public static WorkStatus byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : PENDING;
    }

    public boolean terminal() {
        return this == DONE || this == BLOCKED;
    }
}
