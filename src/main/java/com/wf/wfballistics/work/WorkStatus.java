package com.wf.wfballistics.work;

/** Where one {@link WorkOrder} has got to. */
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
    /** Tried {@link WorkQueue#MAX_ATTEMPTS} times and failed every time. */
    BLOCKED;

    private static final WorkStatus[] VALUES = values();

    public static WorkStatus byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : PENDING;
    }

    public boolean terminal() {
        return this == DONE || this == BLOCKED;
    }
}
