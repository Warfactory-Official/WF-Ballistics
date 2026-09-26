package com.wf.wflib.rail.align;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A line being edited: the PIs, what is selected, and the compiled geometry.
 *
 * <p>Deliberately free of Minecraft and JourneyMap types. The map editor is one front end onto this;
 * an in-world surveying instrument and a gametest are others, and none of them should need a client to
 * exist. It follows that everything here is in world block coordinates and nothing here knows about
 * pixels.</p>
 *
 * <p>The compiled result is cached and invalidated on every mutation, because the editor recompiles on
 * every frame of a drag and the compile is the only thing in the loop that is not free.</p>
 */
public final class AlignEditor {

    /** How close a click must be to count as grabbing a PI rather than placing one, in blocks. */
    public static final double DEFAULT_PICK_TOLERANCE = 6.0;

    private final List<AlignPoint> points = new ArrayList<>();
    private DesignClass designClass = DesignClass.BRANCH;
    private int selected = -1;
    private AlignResult cached;
    private String message = "";
    private long messageUntil;
    /** Identity of the line being edited, so publishing twice replaces rather than duplicates. */
    private UUID id = UUID.randomUUID();
    private String name = "";
    private int coreColour = LineColour.AMBER.rgb();
    private RouteStatus status = RouteStatus.DRAFT;
    private BuildProgress built = BuildProgress.NONE;
    /** The revision this copy started from. 0 means the server has never seen it. */
    private int baseRevision;
    /** Whether the server said this player may change it. A borrowed route is read only. */
    private boolean mayEdit = true;
    /**
     * How many edits have been made here, and how far the server has got with them.
     *
     * <p>A counter rather than a flag because a player keeps dragging while a save is in flight. With a
     * flag, the acknowledgement of the first save clears the edits made after it and they are never
     * sent; with a counter the acknowledgement only clears the version it was actually for.</p>
     */
    private int localVersion;
    private int ackedVersion;
    private int sentVersion = -1;
    private long sentAt;

    public List<AlignPoint> points() {
        return List.copyOf(this.points);
    }

    public int size() {
        return this.points.size();
    }

    public boolean isEmpty() {
        return this.points.isEmpty();
    }

    public AlignPoint point(int index) {
        return index < 0 || index >= this.points.size() ? null : this.points.get(index);
    }

    public UUID id() {
        return this.id;
    }

    public String name() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
        this.localVersion++;
    }

    /** The surveyor's own colour for this line's core. The outline is the faction's and is not set here. */
    public int coreColour() {
        return this.coreColour;
    }

    public void setCoreColour(int rgb) {
        this.coreColour = rgb & 0xFFFFFF;
        this.localVersion++;
    }

    /**
     * Start editing a published line.
     *
     * <p>Keeps its id, so publishing again replaces it. A line loaded this way and then published by
     * someone without the standing to edit it is refused by the server, not here: the client does not
     * know who owns what.</p>
     */
    public void loadPublished(UUID id, String name, int coreColour, List<AlignPoint> points,
                              DesignClass designClass, RouteStatus status, BuildProgress built,
                              int revision, boolean mayEdit) {
        load(points, designClass);
        this.id = id;
        this.name = name == null ? "" : name;
        this.coreColour = coreColour & 0xFFFFFF;
        this.status = status == null ? RouteStatus.DRAFT : status;
        this.built = built == null ? BuildProgress.NONE : built;
        this.baseRevision = revision;
        this.mayEdit = mayEdit;
        this.ackedVersion = this.localVersion;
        this.sentVersion = this.localVersion;
    }

    /**
     * Take on a newer version of the route this copy is of, as broadcast by the server.
     *
     * <p>Geometry is only taken when there is nothing unsaved here: a teammate's change must not
     * overwrite a drag in progress, and refusing to move the base revision is what makes the next save
     * fail loudly instead of quietly winning. Build progress and edit rights are taken either way,
     * because a machine reporting track laid is not in competition with anyone moving a point.</p>
     *
     * @return whether the geometry was replaced
     */
    public boolean refreshFrom(int revision, RouteStatus status, BuildProgress built, boolean mayEdit,
                               List<AlignPoint> points, DesignClass designClass, int coreColour,
                               String name) {
        this.mayEdit = mayEdit;
        // Track laid and a status somebody set are never in competition with a point being dragged, so
        // they are taken even when the geometry here is ahead of the server's.
        if (built != null) {
            this.built = built;
        }
        if (status != null) {
            this.status = status;
        }
        if (dirty() || revision <= this.baseRevision) {
            return false;
        }
        loadPublished(this.id, name, coreColour, points, designClass, status, this.built, revision, mayEdit);
        return true;
    }

    /**
     * The server accepted a save and this is the version it produced.
     *
     * <p>Separate from {@link #refreshFrom}, and the only thing that moves the base revision while there
     * is unsaved work. A player who keeps dragging while a save is in flight would otherwise be told
     * they had conflicted with themselves.</p>
     */
    public void acknowledge(int revision) {
        if (revision > this.baseRevision) {
            this.baseRevision = revision;
        }
        this.ackedVersion = this.sentVersion;
    }

    /** The server refused because this is not ours to change. Stop trying. */
    public void refuseEdits() {
        this.mayEdit = false;
        this.ackedVersion = this.localVersion;
    }

    /** Forget which route this was, so the next save makes a new one. */
    public void detach() {
        this.id = UUID.randomUUID();
        this.baseRevision = 0;
        this.mayEdit = true;
        this.localVersion++;
    }

    public DesignClass designClass() {
        return this.designClass;
    }

    public void setDesignClass(DesignClass designClass) {
        if (designClass != null && designClass != this.designClass) {
            this.designClass = designClass;
            touch();
        }
    }

    public int selected() {
        return this.selected;
    }

    public void select(int index) {
        this.selected = index >= 0 && index < this.points.size() ? index : -1;
    }

    /** The compiled line. Recompiled only when something has changed since it was last asked for. */
    public AlignResult result() {
        if (this.cached == null) {
            this.cached = AlignCompiler.compile(this.points, this.designClass);
        }
        return this.cached;
    }

    private void invalidate() {
        this.cached = null;
    }

    /**
     * A change the server needs to hear about.
     *
     * <p>Every edit a player makes goes through here, and loading a route from the server deliberately
     * does not: otherwise receiving a route would immediately queue a save of what was just received.</p>
     */
    private void touch() {
        this.cached = null;
        this.localVersion++;
    }

    /** Whether there are changes the server has not accepted. */
    public boolean dirty() {
        return this.localVersion != this.ackedVersion;
    }

    /** Whether a save is out and still worth waiting for. */
    public boolean saveInFlight(long timeoutMillis) {
        return this.sentVersion != this.ackedVersion
                && System.currentTimeMillis() - this.sentAt < timeoutMillis;
    }

    /** A save has gone out for everything up to now. */
    public void markSent() {
        this.sentVersion = this.localVersion;
        this.sentAt = System.currentTimeMillis();
    }

    public int baseRevision() {
        return this.baseRevision;
    }

    public boolean mayEdit() {
        return this.mayEdit;
    }

    public RouteStatus status() {
        return this.status;
    }

    public BuildProgress built() {
        return this.built;
    }

    /**
     * Take this copy off the route it came from and keep it as a new one.
     *
     * <p>What happens when a save is refused: someone else changed the route first, or it was deleted
     * underneath. The work itself is still good, so it becomes a fresh draft rather than being lost.</p>
     */
    public void fork() {
        this.id = java.util.UUID.randomUUID();
        this.baseRevision = 0;
        this.status = RouteStatus.DRAFT;
        this.built = BuildProgress.NONE;
        this.mayEdit = true;
        this.localVersion++;
    }

    // -- editing ----------------------------------------------------------------------------------

    /** Append a PI at the end of the line and select it. @return its index */
    public int addPoint(double x, double z) {
        this.points.add(new AlignPoint(x, z, this.designClass.defaultRadius()));
        touch();
        this.selected = this.points.size() - 1;
        return this.selected;
    }

    /** Put a PI between two existing ones, so a line can be bent after it has been drawn. */
    public int insertPoint(int index, double x, double z) {
        int at = Math.max(0, Math.min(index, this.points.size()));
        this.points.add(at, new AlignPoint(x, z, this.designClass.defaultRadius()));
        touch();
        this.selected = at;
        return at;
    }

    public void movePoint(int index, double x, double z) {
        AlignPoint existing = point(index);
        if (existing == null) {
            return;
        }
        this.points.set(index, existing.withPos(x, z));
        touch();
    }

    public void setRadius(int index, double radius) {
        AlignPoint existing = point(index);
        if (existing == null) {
            return;
        }
        this.points.set(index, existing.withRadius(Math.max(0.0, radius)));
        touch();
    }

    public void removePoint(int index) {
        if (index < 0 || index >= this.points.size()) {
            return;
        }
        this.points.remove(index);
        touch();
        // Keep the selection on a neighbour rather than dropping it: deleting a run of points one at a
        // time is the common case, and re-selecting each time is tedious.
        this.selected = this.points.isEmpty() ? -1 : Math.min(index, this.points.size() - 1);
    }

    public void clear() {
        this.points.clear();
        this.selected = -1;
        // A cleared line is a new line: keeping the id would make the next publish silently overwrite
        // whatever was last published under it.
        this.id = UUID.randomUUID();
        this.name = "";
        this.status = RouteStatus.DRAFT;
        this.built = BuildProgress.NONE;
        this.baseRevision = 0;
        this.mayEdit = true;
        this.ackedVersion = this.localVersion;
        this.sentVersion = this.localVersion;
        invalidate();
    }

    public void load(List<AlignPoint> points, DesignClass designClass) {
        this.points.clear();
        if (points != null) {
            this.points.addAll(points);
        }
        if (designClass != null) {
            this.designClass = designClass;
        }
        this.selected = -1;
        invalidate();
    }

    // -- status -----------------------------------------------------------------------------------

    /**
     * Say something to the surveyor, briefly.
     *
     * <p>A menu action that finds nothing to act on has to say so. Silence reads as a broken tool, and
     * "delete the point here" quietly doing nothing when the cursor was a little off is exactly the
     * case that happens most.</p>
     *
     * @param millis how long to keep showing it
     */
    public void say(String text, long millis) {
        this.message = text == null ? "" : text;
        this.messageUntil = System.currentTimeMillis() + millis;
    }

    /** @return the current message, or empty once it has expired. */
    public String message() {
        return System.currentTimeMillis() > this.messageUntil ? "" : this.message;
    }

    // -- picking ----------------------------------------------------------------------------------

    /**
     * The PI nearest this position, within {@code tolerance} blocks, or -1.
     *
     * <p>Ties go to the later point, so the one drawn on top is the one grabbed. Stacked PIs are
     * otherwise impossible to separate: you would grab the buried one every time.</p>
     */
    public int pickPoint(double x, double z, double tolerance) {
        int best = -1;
        double bestDist = tolerance * tolerance;
        for (int i = 0; i < this.points.size(); i++) {
            AlignPoint p = this.points.get(i);
            double dx = p.x() - x;
            double dz = p.z() - z;
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Which leg a position is nearest, as the index a PI inserted there would take.
     *
     * <p>Measured against the straight PI-to-PI legs rather than against the compiled centreline,
     * because that is the thing the player is pointing at when they mean "bend it here", and because
     * the compiled line has been pulled off the legs by exactly the curves they want to change.</p>
     *
     * @return an insertion index in 1..size-1, or -1 when there is no leg or none within tolerance
     */
    public int pickLeg(double x, double z, double tolerance) {
        int best = -1;
        double bestDist = tolerance * tolerance;
        for (int i = 0; i < this.points.size() - 1; i++) {
            double d = distanceSqToSegment(x, z, this.points.get(i), this.points.get(i + 1));
            if (d <= bestDist) {
                bestDist = d;
                best = i + 1;
            }
        }
        return best;
    }

    private static double distanceSqToSegment(double x, double z, AlignPoint a, AlignPoint b) {
        double abx = b.x() - a.x();
        double abz = b.z() - a.z();
        double lenSq = abx * abx + abz * abz;
        double t = lenSq <= 0.0 ? 0.0 : ((x - a.x()) * abx + (z - a.z()) * abz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double dx = a.x() + abx * t - x;
        double dz = a.z() + abz * t - z;
        return dx * dx + dz * dz;
    }
}
