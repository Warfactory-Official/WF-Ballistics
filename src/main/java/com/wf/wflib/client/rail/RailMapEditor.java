package com.wf.wflib.client.rail;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.MapButton;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapPointer;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.MapView;
import net.minecraft.resources.ResourceKey;
import com.wf.wflib.rail.align.AlignEditor;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.network.RailBuildPacket;
import com.wf.wflib.network.RailDeletePacket;
import com.wf.wflib.network.RailSavePacket;
import com.wf.wflib.network.RailSaveResultPacket;
import com.wf.wflib.network.RailStatusPacket;
import com.wf.wflib.network.RailRightOfWayRequestPacket;
import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.rail.align.AlignmentView;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.align.RouteStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Rail alignment editor as a {@link MapTool}; geometry lives in {@link AlignEditor}. Working copy autosaved
 * from its 2nd point => faction data, not a private drawing; concurrent edits: server refuses the stale write.
 */
public final class RailMapEditor implements MapTool, PublishedAlignments.Listener,
        PublishedAlignments.SaveListener {

    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/map/rail_survey.png");

    /** Pixels. */
    private static final double PICK_PIXELS = 7.0;

    /** Pixels; > click: the cursor moved to reach the menu. */
    private static final double MENU_PICK_PIXELS = 14.0;

    /** Line still this long => right of way re-asked; no request per drag frame. */
    private static final long ROW_DEBOUNCE_MS = 250L;

    /** > right-of-way debounce: a save writes world save data. */
    private static final long SAVE_DEBOUNCE_MS = 1200L;

    /** No acknowledgement this long => resend. */
    private static final long SAVE_TIMEOUT_MS = 4000L;

    /** Backoff after {@code FULL}. */
    private static final long SAVE_BACKOFF_MS = 30_000L;

    private static final int MENU_ROUTE_LIMIT = 12;

    private static final int COLOUR_LINE = 0xFFFFC24B;
    private static final int COLOUR_LINE_BAD = 0xFFFF4A3D;
    private static final int COLOUR_LEG = 0x66FFFFFF;
    private static final int COLOUR_HANDLE = 0xFF1B1B1B;
    private static final int COLOUR_HANDLE_SELECTED = 0xFFFFC24B;
    private static final int COLOUR_HANDLE_BORDER = 0xFFFFFFFF;

    private final AlignEditor editor = new AlignEditor();
    private final RailMapRenderer renderer = new RailMapRenderer();

    /** For {@link #resetSession}: else the working line survives into the next world. */
    private static RailMapEditor instance;

    /** Null: no line. */
    private ResourceKey<Level> lineDimension;

    private boolean armed;
    private long saveDirtySince;
    private long saveBlockedUntil;
    private long rowDirtySince;
    private int rowSentFor = -1;
    private int dragIndex = -1;
    private int hoverIndex = -1;

    /** Client tick, not map render: closing the map would strand the last edit. */
    public static void tickClient() {
        RailMapEditor editor = instance;
        if (editor != null) {
            editor.pollAutosave();
        }
    }

    public static void resetSession() {
        RailMapEditor editor = instance;
        if (editor != null) {
            editor.editor.clear();
            editor.lineDimension = null;
            editor.armed = false;
            editor.dragIndex = -1;
            editor.hoverIndex = -1;
            editor.rowSentFor = -1;
            editor.saveDirtySince = 0L;
            editor.saveBlockedUntil = 0L;
        }
    }

    public RailMapEditor() {
        instance = this;
        PublishedAlignments.addListener(this);
        PublishedAlignments.addSaveListener(this);
    }

    @Override
    public MapButton button() {
        return new MapButton("Survey on", "Survey off", ICON, List.of("Rail alignment",
                "Left click to add a point, or drag one to move it.",
                "Right click for radius, insert and delete."));
    }

    @Override
    public boolean armed() {
        return this.armed;
    }

    @Override
    public void setArmed(boolean armed) {
        this.armed = armed;
        if (!armed) {
            this.dragIndex = -1;
        }
    }

    // -- input ------------------------------------------------------------------------------------

    @Override
    public boolean click(MapPointer pointer) {
        if (!this.armed || pointer.button() != 0) {
            return false;
        }
        double tolerance = PICK_PIXELS * pointer.view().blocksPerPixel();
        int hit = this.editor.pickPoint(pointer.blockX(), pointer.blockZ(), tolerance);
        if (hit >= 0) {
            this.editor.select(hit);
            this.dragIndex = hit;
        } else {
            if (this.editor.isEmpty()) {
                this.lineDimension = pointer.view().dimension();
            }
            this.editor.addPoint(pointer.blockX(), pointer.blockZ());
        }
        return true;
    }

    /** Consumed, else the map pans out from under the point being dragged. */
    @Override
    public boolean drag(MapPointer pointer) {
        if (!this.armed || this.dragIndex < 0) {
            return false;
        }
        this.editor.movePoint(this.dragIndex, pointer.blockX(), pointer.blockZ());
        return true;
    }

    @Override
    public void move(MapPointer pointer) {
        this.hoverIndex = this.armed ? this.editor.pickPoint(pointer.blockX(), pointer.blockZ(),
                PICK_PIXELS * pointer.view().blocksPerPixel()) : -1;
    }

    /** Point items act on the point under the cursor and say so when there is none (silent = reads broken). */
    @Override
    public void menu(MapMenu menu, MapView view) {
        menu.item(this.armed ? "Rail: stop surveying" : "Rail: survey a line", pos -> setArmed(!this.armed));
        if (!this.armed) {
            return;
        }

        double tolerance = MENU_PICK_PIXELS * view.blocksPerPixel();

        menu.item("Rail: add a point here", pos -> this.editor.addPoint(pos.getX(), pos.getZ()));
        menu.item("Rail: bend the line here", pos -> {
            int index = this.editor.pickLeg(pos.getX(), pos.getZ(), tolerance);
            if (index > 0) {
                this.editor.insertPoint(index, pos.getX(), pos.getZ());
            } else {
                this.editor.say("no leg of the line within " + Math.round(tolerance) + " blocks", 4000);
            }
        });
        menu.item("Rail: delete the point here", pos -> withPointAt(pos.getX(), pos.getZ(), tolerance,
                this.editor::removePoint));

        var radii = menu.sub("Rail: radius of the point here");
        for (double radius : new double[]{30, 60, 100, 200, 400, 800}) {
            radii.item(String.format(Locale.ROOT, "%.0f blocks", radius),
                    pos -> withPointAt(pos.getX(), pos.getZ(), tolerance,
                            index -> this.editor.setRadius(index, radius)));
        }
        var classes = menu.sub("Rail: design class");
        for (DesignClass designClass : DesignClass.values()) {
            classes.item(designClass.name().toLowerCase(Locale.ROOT), pos -> {
                this.editor.setDesignClass(designClass);
                this.editor.say("design class is now " + designClass.name().toLowerCase(Locale.ROOT)
                        + ", minimum radius " + Math.round(designClass.minRadius()), 4000);
            });
        }

        var colours = menu.sub("Rail: colour of this line");
        for (LineColour colour : LineColour.values()) {
            colours.item(colour.lowerName(), pos -> {
                this.editor.setCoreColour(colour.rgb());
                this.editor.say("line core is " + colour.lowerName()
                        + "; the outline stays the faction's colour", 4000);
            });
        }

        var statuses = menu.sub("Rail: status of this route");
        for (RouteStatus status : RouteStatus.values()) {
            statuses.item(status.lowerName(), pos -> setStatus(status));
        }

        var construction = menu.sub("Rail: construction");
        construction.item("built from the start to here",
                pos -> mark(0.0, chainageNear(pos.getX(), pos.getZ()), true));
        construction.item("built from here to the end",
                pos -> mark(chainageNear(pos.getX(), pos.getZ()),
                        this.editor.result().centreline().length(), true));
        construction.item("the whole route is built",
                pos -> mark(0.0, this.editor.result().centreline().length(), true));
        construction.item("track here is gone", pos -> {
            double at = chainageNear(pos.getX(), pos.getZ());
            mark(at - 32.0, at + 32.0, false);
        });
        construction.item("nothing on it is built",
                pos -> mark(0.0, this.editor.result().centreline().length(), false));

        var routes = menu.sub("Rail: open a route");
        int listed = 0;
        for (AlignmentView route : PublishedAlignments.current()) {
            if (!route.mayEdit() || listed >= MENU_ROUTE_LIMIT) {
                continue;
            }
            listed++;
            String label = (route.name().isEmpty() ? "unnamed" : route.name())
                    + " (" + route.status().lowerName() + ")";
            routes.item(label, pos -> load(route));
        }
        if (listed == 0) {
            routes.item("nothing here is yours to edit", pos -> this.editor.say(
                    "your faction has no routes in this world yet", 4000));
        }

        menu.item("Rail: open the route here", pos -> loadPublishedAt(pos.getX(), pos.getZ(),
                MENU_PICK_PIXELS * 4 * view.blocksPerPixel()));
        menu.item("Rail: start a new route", pos -> {
            this.editor.clear();
            this.editor.say("started a new route; the last one is still on the server", 4000);
        });
        menu.item("Rail: delete this route", pos -> {
            WFNetwork.sendToServer(new RailDeletePacket(this.editor.id()));
            this.editor.clear();
            this.editor.say("asked the server to delete it", 4000);
        });
    }

    // -- talking to the server ---------------------------------------------------------------------

    /** Debounced autosave; never mid-drag. */
    private void pollAutosave() {
        if (this.editor.size() < 2 || !this.editor.mayEdit()) {
            return;
        }
        if (System.currentTimeMillis() < this.saveBlockedUntil) {
            return;
        }
        // Server files under the player's current dimension.
        var level = Minecraft.getInstance().level;
        if (level == null || (this.lineDimension != null && !this.lineDimension.equals(level.dimension()))) {
            return;
        }
        if (!this.editor.dirty()) {
            this.saveDirtySince = 0L;
            return;
        }
        if (this.editor.saveInFlight(SAVE_TIMEOUT_MS) || this.dragIndex >= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.saveDirtySince == 0L) {
            this.saveDirtySince = now;
            return;
        }
        if (now - this.saveDirtySince < SAVE_DEBOUNCE_MS) {
            return;
        }
        this.saveDirtySince = 0L;
        nameIfUnnamed();
        this.editor.markSent();
        WFNetwork.sendToServer(new RailSavePacket(this.editor.id(), this.editor.name(),
                this.editor.coreColour(), this.editor.designClass(), this.editor.points(),
                this.editor.baseRevision()));
    }

    /** No text entry in a popup menu => surveyor + class. */
    private void nameIfUnnamed() {
        if (!this.editor.name().isEmpty()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        this.editor.setName((player == null ? "a" : player.getGameProfile().getName()) + "'s "
                + this.editor.designClass().name().toLowerCase(Locale.ROOT) + " line");
    }

    private void setStatus(RouteStatus status) {
        if (this.editor.size() < 2) {
            this.editor.say("there is no route here to mark " + status.lowerName(), 4000);
            return;
        }
        WFNetwork.sendToServer(new RailStatusPacket(this.editor.id(), status));
        this.editor.say("asked the server to mark it " + status.lowerName(), 4000);
    }

    private void mark(double from, double to, boolean laid) {
        double length = this.editor.result().centreline().length();
        if (this.editor.size() < 2 || length <= 0.0) {
            this.editor.say("there is no route here to mark", 4000);
            return;
        }
        double lo = Math.max(0.0, Math.min(from, to));
        double hi = Math.min(length, Math.max(from, to));
        if (hi - lo <= 0.0) {
            this.editor.say("that is not a stretch of the route", 4000);
            return;
        }
        WFNetwork.sendToServer(new RailBuildPacket(this.editor.id(), lo, hi, laid));
        this.editor.say(String.format(Locale.ROOT, "%s %.0f blocks of route",
                laid ? "marked built:" : "marked not built:", hi - lo), 4000);
    }

    private double chainageNear(double x, double z) {
        var centreline = this.editor.result().centreline();
        double length = centreline.length();
        if (length <= 0.0) {
            return 0.0;
        }

        double step = Math.max(1.0, length / 512.0);
        double best = 0.0;
        double bestDistance = Double.MAX_VALUE;
        for (double at = 0.0; at <= length; at += step) {
            var sample = centreline.at(at);
            double distance = Math.hypot(sample.x() - x, sample.z() - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = at;
            }
        }
        return best;
    }

    // -- what the server says ----------------------------------------------------------------------

    /** Geometry taken only with nothing unsaved here; build progress and status always. */
    @Override
    public void onAlignments(ResourceKey<Level> dimension, List<AlignmentView> alignments) {
        AlignmentView mine = PublishedAlignments.byId(this.editor.id());
        if (mine == null) {
            return;
        }
        if (this.editor.refreshFrom(mine.revision(), mine.status(), mine.built(), mine.mayEdit(),
                mine.points(), mine.designClass(), mine.coreColour(), mine.name())
                && !mine.lastEditorName().isEmpty()) {
            this.editor.say(mine.lastEditorName() + " changed this route", 4000);
        }
    }

    @Override
    public void onSaveResult(RailSaveResultPacket result) {
        if (!result.id().equals(this.editor.id())) {
            return;
        }
        switch (result.outcome()) {
            case SAVED -> this.editor.acknowledge(result.revision());
            case CONFLICT, GONE -> {

                this.editor.fork();
                this.editor.say("someone else changed that route first, so yours is now a new draft", 6000);
            }
            case DENIED -> {
                this.editor.refuseEdits();
                this.editor.say("that route is not yours to change", 5000);
            }
            case FULL -> {
                this.saveBlockedUntil = System.currentTimeMillis() + SAVE_BACKOFF_MS;
                this.editor.say("your faction has no room for another route; delete one first", 6000);
            }
            case INVALID -> {
                // < 2 points; never sent.
            }
        }
    }

    /** Identity kept: a save replaces it. */
    private void load(AlignmentView view) {
        this.editor.loadPublished(view.id(), view.name(), view.coreColour(), view.points(),
                view.designClass(), view.status(), view.built(), view.revision(), view.mayEdit());
        var level = Minecraft.getInstance().level;
        this.lineDimension = level == null ? null : level.dimension();
        this.editor.say("editing \"" + view.name() + "\" (" + view.status().lowerName() + ")"
                + (view.mayEdit() ? "" : ", which is not yours to change"), 5000);
    }

    private void loadPublishedAt(double x, double z, double tolerance) {
        AlignmentView nearest = null;
        double best = Double.MAX_VALUE;
        for (AlignmentView view : PublishedAlignments.current()) {
            for (var point : view.points()) {
                double d = Math.hypot(point.x() - x, point.z() - z);
                if (d < best) {
                    best = d;
                    nearest = view;
                }
            }
        }
        if (nearest == null || best > tolerance) {
            this.editor.say("no route within " + Math.round(tolerance) + " blocks of there", 4000);
            return;
        }
        load(nearest);
    }

    private void withPointAt(double x, double z, double tolerance, java.util.function.IntConsumer action) {
        int index = this.editor.pickPoint(x, z, tolerance);
        if (index < 0) {
            this.editor.say("no point within " + Math.round(tolerance) + " blocks of there", 4000);
            return;
        }
        this.editor.select(index);
        action.accept(index);
    }

    // -- render -----------------------------------------------------------------------------------

    @Override
    public void render(Canvas canvas, MapView view) {
        pollRightOfWay();
        // No mouse-release event in the API => the button is the authority on a drag still happening.
        if (this.dragIndex >= 0 && !mouseHeld()) {
            this.dragIndex = -1;
        }
        if (!this.armed && this.editor.isEmpty()) {
            return;
        }
        // Coordinates mean another place in another dimension.
        if (!this.editor.isEmpty() && this.lineDimension != null && !this.lineDimension.equals(view.dimension())) {
            return;
        }
        this.renderer.draw(canvas, view, this.editor, this.armed, this.hoverIndex, RouteOwnership.current(),
                new RailMapRenderer.Palette(COLOUR_LINE, COLOUR_LINE_BAD, COLOUR_LEG,
                        COLOUR_HANDLE, COLOUR_HANDLE_SELECTED, COLOUR_HANDLE_BORDER));
    }

    /** Answer dropped the moment the line changes: a stale one still looks authoritative. */
    private void pollRightOfWay() {
        if (!this.armed) {
            return;
        }
        int fingerprint = this.editor.points().hashCode() * 31 + this.editor.designClass().ordinal();
        if (fingerprint != this.rowSentFor) {
            if (this.rowDirtySince == 0L) {
                this.rowDirtySince = System.currentTimeMillis();
                RouteOwnership.clear();
            }
            if (System.currentTimeMillis() - this.rowDirtySince < ROW_DEBOUNCE_MS) {
                return;
            }
            this.rowDirtySince = 0L;
            this.rowSentFor = fingerprint;
            if (this.editor.size() >= 2 && this.dragIndex < 0) {
                WFNetwork.sendToServer(new RailRightOfWayRequestPacket(this.editor.designClass(),
                        this.editor.points()));
            }
        }
    }

    private static boolean mouseHeld() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    public AlignEditor editor() {
        return this.editor;
    }

    public java.util.List<AlignPoint> points() {
        return this.editor.points();
    }
}
