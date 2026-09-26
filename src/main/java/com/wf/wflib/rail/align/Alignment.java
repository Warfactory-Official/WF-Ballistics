package com.wf.wflib.rail.align;

import java.util.List;
import java.util.UUID;

/**
 * A surveyed route, as the server holds it.
 *
 * <p><b>This is faction data, not one player's drawing.</b> A route is planned by a team over days: one
 * person sketches it, another moves a corner off a claim, a third reports the first three kilometres cut.
 * All of that lives here, on the server, from the moment a line has two points, which is why there is no
 * separate "draft" type. {@link AlignEditor} is a working copy on whichever client currently has it open,
 * and it is a view onto one of these rather than the place the route lives.</p>
 *
 * @param id stable identity, so an edit replaces rather than duplicates
 * @param name what the surveyor called it
 * @param author who drew it first; they keep sight of it even after leaving the faction that owns it
 * @param ownerFaction the faction it belongs to, or null when the author had none
 * @param designClass the class it was surveyed at, which fixes its limits and its clearance
 * @param coreColour the surveyor's own colour for the line, 0xRRGGBB. The outline is the faction's.
 * @param points the PIs, in order
 * @param status plan or track, which decides who is shown it and how it is drawn
 * @param built which parts of it have track on them
 * @param revision what number this version is, who made it and when
 */
public record Alignment(UUID id, String name, UUID author, UUID ownerFaction, DesignClass designClass,
                        int coreColour, List<AlignPoint> points, RouteStatus status,
                        BuildProgress built, Revision revision) {

    /** Longest name accepted, here and off the wire. */
    public static final int MAX_NAME = 48;

    /** Most PIs one line may have. A 20 km main line needs a few dozen; this is a wire-safety bound. */
    public static final int MAX_POINTS = 512;

    /**
     * Which version of a route this is, and who made it.
     *
     * <p>The number is what stops two planners overwriting each other. A client saves the number it
     * started from; if the server has moved on, someone else saved in between and the write is refused
     * rather than silently winning. Without it the last person to let go of a point takes the route.</p>
     *
     * @param number 0 for a route that has never been saved, then 1 upwards
     * @param editor who last changed it, or null
     * @param at when, in server epoch milliseconds
     */
    public record Revision(int number, UUID editor, long at) {

        public static final Revision NEW = new Revision(0, null, 0L);

        public Revision next(UUID editor) {
            return new Revision(this.number + 1, editor, System.currentTimeMillis());
        }
    }

    public Alignment {
        name = name == null ? "" : name.substring(0, Math.min(name.length(), MAX_NAME));
        points = List.copyOf(points);
        coreColour &= 0xFFFFFF;
        status = status == null ? RouteStatus.DRAFT : status;
        built = built == null ? BuildProgress.NONE : built;
        revision = revision == null ? Revision.NEW : revision;
    }

    /** A brand new route: a draft, with no track on it and no history. */
    public Alignment(UUID id, String name, UUID author, UUID ownerFaction, DesignClass designClass,
                     int coreColour, List<AlignPoint> points) {
        this(id, name, author, ownerFaction, designClass, coreColour, points, RouteStatus.DRAFT,
                BuildProgress.NONE, Revision.NEW);
    }

    public AlignResult compile() {
        return AlignCompiler.compile(this.points, this.designClass);
    }

    public Alignment withPoints(List<AlignPoint> points) {
        return new Alignment(this.id, this.name, this.author, this.ownerFaction, this.designClass,
                this.coreColour, points, this.status, this.built, this.revision);
    }

    public Alignment withColour(int coreColour) {
        return new Alignment(this.id, this.name, this.author, this.ownerFaction, this.designClass,
                coreColour, this.points, this.status, this.built, this.revision);
    }

    public Alignment withStatus(RouteStatus status) {
        return new Alignment(this.id, this.name, this.author, this.ownerFaction, this.designClass,
                this.coreColour, this.points, status, this.built, this.revision);
    }

    public Alignment withBuilt(BuildProgress built) {
        return new Alignment(this.id, this.name, this.author, this.ownerFaction, this.designClass,
                this.coreColour, this.points, this.status, built, this.revision);
    }

    /** Stamp a new revision on this route. Every accepted write goes through here. */
    public Alignment editedBy(UUID editor) {
        return new Alignment(this.id, this.name, this.author, this.ownerFaction, this.designClass,
                this.coreColour, this.points, this.status, this.built, this.revision.next(editor));
    }
}
