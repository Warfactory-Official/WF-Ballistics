package com.wf.wfballistics.exchange;

import java.util.Locale;

/**
 * One thing a station is willing to do. A station holds a <em>set</em> of these, not one of them.
 *
 * <p>A set rather than a type because the capabilities genuinely compose and a single-valued kind would have
 * to enumerate the combinations: a pad that supplies materials and accepts returns but never launches
 * anything of its own is a perfectly sensible thing to want, and under a one-of-many enum it would need its
 * own constant, as would every other combination anyone thought of. Under a set it needs no code at all.
 * {@link StationKind} then exists for the two or three combinations worth having a name for, which is the
 * part players actually interact with.
 *
 * <p>Persisted by {@link #name()}, like every other enum in this mod that reaches a save file, so the
 * constants can be reordered and new ones added in the middle without rewriting anyone's stations.
 */
public enum StationRole {
    /**
     * Dispatches drones of its own. Every drone pad did this before roles existed and it is what the pad
     * screen's launch button means.
     */
    LAUNCH("launch", "dispatches drones"),
    /**
     * Accepts cargo somebody else sent: the receiving half of a handshake, and where a salvage job brings
     * what it recovered.
     */
    DEPOT("depot", "accepts inbound cargo"),
    /**
     * Supplies building materials to a construction job. What makes a station a supplier rather than a
     * destination.
     */
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
