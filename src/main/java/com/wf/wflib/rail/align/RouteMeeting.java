package com.wf.wflib.rail.align;

import java.util.Locale;
import java.util.UUID;

/**
 * Where two surveyed routes meet, and what kind of railway that has to be.
 *
 * <p>A route on its own is a line. A railway is routes that meet, and the three ways they can is the
 * whole of it: one crosses another, one branches off another, or two join end to end. Which of those a
 * meeting is decides what gets built there, so it is worked out once, on the survey graph, rather than
 * guessed at by whatever is laying track at the time.</p>
 *
 * <p>Deliberately expressed as chainages on two routes and not as a world position. A position stops
 * being true the moment somebody drags a point of either route; a chainage is what the survey means,
 * and the position falls out of it again on demand. That is the same rule the design doc sets for a
 * junction, applied to every kind of meeting.</p>
 *
 * @param angle how far the two lines are off parallel at the meeting, in degrees
 */
public record RouteMeeting(Side a, Side b, Kind kind, double x, double z, double angle) {

    /** Which end of a route a meeting falls on, if either. */
    public enum End {
        NONE, START, FINISH;

        public boolean terminal() {
            return this != NONE;
        }
    }

    /**
     * What has to be built where two routes meet.
     *
     * <p>Not a preference. A train can only take a diverging route through a switch, can only cross
     * another line where both tracks occupy the same ground, and needs neither where two lines simply
     * become one.</p>
     */
    public enum Kind {
        /** Both run on through: a diamond, where the two tracks share their crossing blocks. */
        CROSSING,
        /** One ends on the other: a turnout, so a train can be sent either way. */
        TURNOUT,
        /** Both end here: one railway in two surveys, and nothing to build but continuous rail. */
        JOINT;

        public String lowerName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One route's half of a meeting.
     *
     * @param heading the route's heading there, in radians from +X toward +Z, as the alignment measures
     * @param end whether the route finishes here, which is what makes a meeting a turnout
     */
    public record Side(UUID route, String name, UUID owner, double chainage, double heading, End end) {

        public String label() {
            return this.name == null || this.name.isEmpty() ? "unnamed route" : this.name;
        }
    }

    /** @return the side that ends here, for a turnout; null for anything else. */
    public Side branch() {
        if (this.kind != Kind.TURNOUT) {
            return null;
        }
        return this.a.end().terminal() ? this.a : this.b;
    }

    /** @return the side that runs on through, for a turnout; null for anything else. */
    public Side through() {
        if (this.kind != Kind.TURNOUT) {
            return null;
        }
        return this.a.end().terminal() ? this.b : this.a;
    }

    /** @return this meeting's half of a given route, or null when it is not one of the two. */
    public Side sideOf(UUID route) {
        if (this.a.route().equals(route)) {
            return this.a;
        }
        return this.b.route().equals(route) ? this.b : null;
    }

    public Side otherThan(UUID route) {
        return this.a.route().equals(route) ? this.b : this.a;
    }

    /**
     * Whether both routes are the same faction's.
     *
     * <p>The rule that matters: a turnout cut into somebody else's main line is the same trespass as a
     * tunnel driven under their citadel, and it is worse in one way, because a switch left set the wrong
     * way puts their train on your railway. Two unowned routes count as one owner, which is what a
     * server with no factions on it is.</p>
     */
    public boolean sameOwner() {
        UUID mine = this.a.owner();
        UUID theirs = this.b.owner();
        return mine == null ? theirs == null : mine.equals(theirs);
    }

    /** One line of what this is, for a player reading a list. */
    public String describe() {
        String what = switch (this.kind) {
            case CROSSING -> String.format(Locale.ROOT, "%s crosses %s at %.0f degrees",
                    this.a.label(), this.b.label(), this.angle);
            case TURNOUT -> String.format(Locale.ROOT, "%s branches off %s at %.0f degrees",
                    branch().label(), through().label(), this.angle);
            case JOINT -> String.format(Locale.ROOT, "%s joins %s end to end",
                    this.a.label(), this.b.label());
        };
        return String.format(Locale.ROOT, "%s, at %.0f, %.0f (%.0f blocks along %s)",
                what, this.x, this.z, this.a.chainage(), this.a.label());
    }
}
