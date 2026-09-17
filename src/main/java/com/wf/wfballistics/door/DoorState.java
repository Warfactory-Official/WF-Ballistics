package com.wf.wfballistics.door;

/** Where a door is between shut and open. Persisted and synced as its ordinal. */
public enum DoorState {
    CLOSED,
    OPEN,
    CLOSING,
    OPENING;

    private static final DoorState[] VALUES = values();

    public static DoorState of(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : CLOSED;
    }

    public boolean moving() {
        return this == CLOSING || this == OPENING;
    }
}
