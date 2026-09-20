package com.wf.wflib.exchange;

import java.util.Locale;

/** One thing a station is willing to do. */
public enum StationRole {
    /** Dispatches drones of its own. */
    LAUNCH("launch", "dispatches drones"),
    /**
     * Accepts cargo somebody else sent: the receiving half of a handshake, and where a salvage job brings what it
     * recovered.
     */
    DEPOT("depot", "accepts inbound cargo"),
    /** Supplies building materials to a construction job. */
    MATERIALS("materials", "supplies building materials"),
    /**
     * Recharges drones that land on it.
     */
    RECHARGE("recharge", "recharges landed drones");

    private final String id;
    private final String description;

    StationRole(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return this.id;
    }

    public String description() {
        return this.description;
    }

    public static StationRole byId(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (StationRole role : values()) {
                if (role.id.equals(wanted)) {
                    return role;
                }
            }
        }
        return null;
    }
}
