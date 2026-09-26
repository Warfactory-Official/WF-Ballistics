package com.wf.wflib.rail.align;

import java.util.Locale;

/**
 * Where a route stands between "someone had an idea" and "trains run on it".
 *
 * <p>A planning tool that cannot tell a proposal from track on the ground is a drawing, not a plan. The
 * whole point of surveying a line weeks before building it is that everyone can see what is agreed, what
 * is being cut, and what already carries traffic, so this is carried on the route itself and drawn.</p>
 *
 * <p>{@link #DRAFT} is the one that is <b>not</b> shared with allies. A half-formed idea is not something
 * a faction has decided to tell anyone, and an ally seeing a line that is later abandoned is worse than
 * an ally seeing nothing. Everything from {@link #PLANNED} on is a commitment, and an ally needs those to
 * plan around.</p>
 *
 * <p>{@link #BUILDING} and {@link #BUILT} are ordinarily reached by building rather than by declaring:
 * reporting track laid promotes a route, and covering it end to end promotes it again. They can still be
 * set by hand, because a route built before this existed has no report to make.</p>
 */
public enum RouteStatus {

    DRAFT(false, 0.30f, 0.55f),
    PLANNED(true, 0.45f, 0.90f),
    BUILDING(true, 0.55f, 0.95f),
    BUILT(true, 0.85f, 0.95f),
    ABANDONED(true, 0.20f, 0.45f);

    private final boolean sharedWithAllies;
    private final float fillOpacity;
    private final float strokeOpacity;

    RouteStatus(boolean sharedWithAllies, float fillOpacity, float strokeOpacity) {
        this.sharedWithAllies = sharedWithAllies;
        this.fillOpacity = fillOpacity;
        this.strokeOpacity = strokeOpacity;
    }

    /** Whether an allied faction may be shown a route in this state. */
    public boolean sharedWithAllies() {
        return this.sharedWithAllies;
    }

    /**
     * How solidly the <em>unbuilt</em> part of a route in this state is drawn.
     *
     * <p>Built track is always drawn at {@link #BUILT}'s weight whatever the route's own status, because
     * the question the map is answering there is "is there track here", and the answer does not get
     * fainter because the rest of the line is still a plan.</p>
     */
    public float fillOpacity() {
        return this.fillOpacity;
    }

    public float strokeOpacity() {
        return this.strokeOpacity;
    }

    /** Whether a route in this state claims to exist on the ground at all. */
    public boolean onTheGround() {
        return this == BUILDING || this == BUILT;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the named status, or {@code fallback} for anything unrecognised. */
    public static RouteStatus byName(String name, RouteStatus fallback) {
        if (name == null) {
            return fallback;
        }
        for (RouteStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return fallback;
    }

    /** @return the status for an ordinal off the wire, never throwing on the render thread. */
    public static RouteStatus byOrdinal(int ordinal) {
        RouteStatus[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DRAFT;
    }
}
