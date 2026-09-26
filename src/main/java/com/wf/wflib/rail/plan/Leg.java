package com.wf.wflib.rail.plan;

import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.BuildProgress;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.align.RouteMeetings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One stretch of one route, in the direction a train runs over it.
 *
 * <p>A journey across a network is a handful of these. A route is surveyed once, in one direction, from
 * one end to the other; a train reaching the far side of the map runs over part of one route backwards,
 * all of the next one forwards, and stops halfway along a third. Every one of those is this.</p>
 *
 * <p><b>The whole point is that a leg presents itself as a route.</b> Its centreline starts at zero
 * where the train enters and runs to {@link #length()} where the train leaves, and the meetings on it
 * are renumbered to match. Everything downstream - the corridor, the lining, the track pieces, the
 * crossing exemption - then works exactly as it always did on a whole route, and the one place that
 * knows a leg is part of something larger is {@link #onRoute}, which is what the build record is
 * written through.</p>
 *
 * <p>No world in it, on purpose. Which way round a leg runs decides where a tunnel's portal is walled
 * and which end of a turnout a train arrives at, and that is the sort of thing that is wrong in a way
 * nobody sees until a train is standing at the wrong end of its own railway.</p>
 *
 * @param from where the train joins this route, as a chainage on the route's own survey
 * @param to where it leaves. Less than {@code from} when the leg runs against the survey.
 */
public record Leg(UUID route, String name, double from, double to) {

    /** A whole route, in the direction it was surveyed. */
    public static Leg of(Alignment route) {
        return new Leg(route.id(), route.name(), 0.0, route.compile().centreline().length());
    }

    public boolean reversed() {
        return this.to < this.from;
    }

    public double length() {
        return Math.abs(this.to - this.from);
    }

    /** A distance along this leg, as a chainage on the route it is part of. */
    public double onRoute(double along) {
        return reversed() ? this.from - along : this.from + along;
    }

    /** A chainage on the route, as a distance along this leg. Negative for ground before it. */
    public double onLeg(double chainage) {
        return reversed() ? this.from - chainage : chainage - this.from;
    }

    /** @return this leg's own centreline, clipped out of the route's and turned the right way. */
    public Centreline centreline(Centreline route) {
        Centreline part = route.part(Math.min(this.from, this.to), Math.max(this.from, this.to));
        return reversed() ? part.reversed() : part;
    }

    /**
     * The meetings on this route, renumbered onto this leg.
     *
     * <p>Two things move and one deliberately does not. Chainages and headings are the leg's, because
     * that is the frame everything that reads them works in. <b>Which end of the <em>route</em> a
     * meeting is at does not become which end of the leg it is at</b>: a leg that stops halfway along a
     * route has stopped in the middle of a railway, not at the end of one, and saying otherwise would
     * leave the tunnel open at a face and hand a turnout's diverging road to the line running straight
     * through it.</p>
     *
     * <p>The other side of each meeting is left exactly as it was. It is a chainage on somebody else's
     * survey and this leg has no opinion about it.</p>
     */
    public List<RouteMeeting> meetings(List<RouteMeeting> all) {
        List<RouteMeeting> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        for (RouteMeeting meeting : all) {
            boolean isA = meeting.a().route().equals(this.route);
            RouteMeeting.Side mine = isA ? meeting.a() : meeting.b();
            if (!isA && !meeting.b().route().equals(this.route)) {
                out.add(meeting);
                continue;
            }
            RouteMeeting.Side moved = new RouteMeeting.Side(mine.route(), mine.name(), mine.owner(),
                    onLeg(mine.chainage()),
                    reversed() ? mine.heading() + Math.PI : mine.heading(), endOf(mine));
            out.add(new RouteMeeting(isA ? moved : meeting.a(), isA ? meeting.b() : moved,
                    meeting.kind(), meeting.x(), meeting.z(), meeting.angle()));
        }
        return out;
    }

    /** Which end of this leg a meeting sits at, which it only can if it was an end of the route too. */
    private RouteMeeting.End endOf(RouteMeeting.Side mine) {
        if (!mine.end().terminal()) {
            return RouteMeeting.End.NONE;
        }
        double along = onLeg(mine.chainage());
        if (Math.abs(along) <= RouteMeetings.END_REACH) {
            return RouteMeeting.End.START;
        }
        return Math.abs(along - length()) <= RouteMeetings.END_REACH ? RouteMeeting.End.FINISH
                : RouteMeeting.End.NONE;
    }

    /**
     * How far into this leg the track already runs.
     *
     * <p>From the end the train arrives at, and only the unbroken run from there. A route with track on
     * its middle third and nothing either side is not a route a work train can reach the far end of, and
     * counting the total built would have it set off to a railhead that is not connected to anything.</p>
     */
    public double builtAlong(BuildProgress built) {
        if (built == null || built.isEmpty()) {
            return 0.0;
        }
        double near = this.from;
        double reach = 0.0;
        for (BuildProgress.Span span : built.spans()) {
            if (reversed()) {
                if (span.to() >= near - BuildProgress.EPSILON && span.from() < near) {
                    reach = Math.max(reach, near - Math.max(span.from(), this.to));
                }
            } else if (span.from() <= near + BuildProgress.EPSILON && span.to() > near) {
                reach = Math.max(reach, Math.min(span.to(), this.to) - near);
            }
        }
        return Math.max(0.0, Math.min(reach, length()));
    }

    /** @return whether track runs the whole way over this leg already. */
    public boolean done(BuildProgress built) {
        return builtAlong(built) >= length() - BuildProgress.EPSILON;
    }

    /**
     * What a machine {@code along} blocks into this leg has dug, as a span on the route's own survey.
     *
     * <p>Which is not "the first {@code along} blocks of the route" whenever the leg runs backwards or
     * starts in the middle, and that difference is the difference between a crossing exemption that
     * covers the tunnel that is there and one that covers a stretch of solid rock a kilometre away.</p>
     */
    public BuildProgress.Span dug(double along) {
        double end = onRoute(Math.max(0.0, Math.min(along, length())));
        return new BuildProgress.Span(Math.min(this.from, end), Math.max(this.from, end));
    }

    /** One line of where this leg goes, for a player reading a dispatch. */
    public String describe() {
        String label = this.name == null || this.name.isEmpty() ? "unnamed route" : this.name;
        return String.format(Locale.ROOT, "%s %.0f to %.0f", label, this.from, this.to);
    }
}
