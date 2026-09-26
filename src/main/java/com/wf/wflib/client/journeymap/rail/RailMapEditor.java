package com.wf.wflib.client.journeymap.rail;

import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.AlignEditor;
import com.wf.wflib.client.rail.PublishedAlignments;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.network.RailBuildPacket;
import com.wf.wflib.network.RailDeletePacket;
import com.wf.wflib.client.rail.RouteOwnership;
import com.wf.wflib.network.RailSavePacket;
import com.wf.wflib.network.RailSaveResultPacket;
import com.wf.wflib.network.RailStatusPacket;
import com.wf.wflib.network.RailRightOfWayRequestPacket;
import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.rail.align.AlignmentView;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.align.RouteStatus;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.event.PopupMenuEvent;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * The rail alignment editor, hosted on JourneyMap's fullscreen map.
 *
 * <p>The map is the only plan view a player has, and a railway is decided in plan view. Rather than
 * building a map of our own, this borrows JourneyMap's: a toolbar button arms the tool, clicks and
 * drags are taken before JourneyMap sees them, and the line is drawn in the render event, in screen
 * space, so it keeps its width at any zoom.</p>
 *
 * <p>Nothing here holds geometry. {@link AlignEditor} is the model and knows nothing about maps, which
 * is what lets the same alignment be edited by an in-world instrument or driven by a test.</p>
 *
 * <p>Nor does anything here own a route. The working copy is saved to the server on a debounce from its
 * second point onwards, so what is on screen is a view of faction data rather than a private drawing:
 * a teammate can pick it up, a crash does not lose it, and two people editing at once is resolved by the
 * server refusing the stale write rather than by whoever lets go of a point last.</p>
 */
public final class RailMapEditor implements com.wf.wflib.client.rail.PublishedAlignments.Listener,
        com.wf.wflib.client.rail.PublishedAlignments.SaveListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/rail-map");

    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/journeymap/rail_survey.png");

    /** Grab radius for a PI handle, in pixels. Constant on screen, so it works at every zoom. */
    private static final double PICK_PIXELS = 7.0;

    /** How many clicks to check the transform on. Enough to catch an error that scales with position. */
    private static final int TRANSFORM_CHECKS = 4;

    /** Grab radius for a menu action, in pixels. Looser than a click: the cursor moved to reach the menu. */
    private static final double MENU_PICK_PIXELS = 14.0;

    /**
     * How long the line has to stop changing before the right of way is asked for again.
     *
     * <p>Long enough that a drag does not send a request per frame, short enough that letting go of a
     * point answers before you have looked away.</p>
     */
    private static final long ROW_DEBOUNCE_MS = 250L;

    /**
     * How long the route has to stop changing before it is saved.
     *
     * <p>Longer than the right-of-way debounce because a save writes the world's save data, and shorter
     * than the time it takes to look away from what you just drew.</p>
     */
    private static final long SAVE_DEBOUNCE_MS = 1200L;

    /** How long to wait for an acknowledgement before assuming the save was lost and sending again. */
    private static final long SAVE_TIMEOUT_MS = 4000L;

    /** How long to stop saving for after the server says the faction is out of room. */
    private static final long SAVE_BACKOFF_MS = 30_000L;

    /** Most routes listed by name in the popup menu. Beyond this the list is longer than the map. */
    private static final int MENU_ROUTE_LIMIT = 12;

    private static final int COLOUR_LINE = 0xFFFFC24B;
    private static final int COLOUR_LINE_BAD = 0xFFFF4A3D;
    private static final int COLOUR_LEG = 0x66FFFFFF;
    private static final int COLOUR_HANDLE = 0xFF1B1B1B;
    private static final int COLOUR_HANDLE_SELECTED = 0xFFFFC24B;
    private static final int COLOUR_HANDLE_BORDER = 0xFFFFFFFF;

    private final AlignEditor editor = new AlignEditor();
    private final RailMapRenderer renderer = new RailMapRenderer();

    /**
     * The one editor this client has, so a session ending can reset it.
     *
     * <p>The working line is client state with no server behind it, which means nothing clears it on
     * its own: without this it survives leaving a world and is drawn over the next one's terrain, at
     * coordinates that mean nothing there.</p>
     */
    private static RailMapEditor instance;

    /** Which world and dimension the working line was drawn in. Null when there is no line. */
    private ResourceKey<Level> lineDimension;

    private boolean armed;
    private long saveDirtySince;
    private long saveBlockedUntil;
    private long rowDirtySince;
    private int rowSentFor = -1;
    private int dragIndex = -1;
    private int hoverIndex = -1;
    private IThemeButton button;

    private String getModId() {
        return WFLib.MODID;
    }

    /**
     * Save the working route if it is due, whether or not the map is open.
     *
     * <p>Driven from the client tick rather than from the map's render event, because closing the map
     * is exactly when the last edit would otherwise be left unsaved.</p>
     */
    public static void tickClient() {
        RailMapEditor editor = instance;
        if (editor != null) {
            editor.pollAutosave();
        }
    }

    /** Drop the working line when a session ends. Called from the client's logout handler. */
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
            if (editor.button != null) {
                editor.button.setToggled(false);
            }
        }
    }

    public void attach(IClientAPI jmClientApi) {
        instance = this;
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(getModId(), this::onToolbar);
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(getModId(), this::onClick);
        FullscreenEventRegistry.FULLSCREEN_MAP_DRAG_EVENT.subscribe(getModId(), this::onDrag);
        FullscreenEventRegistry.FULLSCREEN_MAP_MOVE_EVENT.subscribe(getModId(), this::onMove);
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(getModId(), this::onPopupMenu);
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(getModId(), this::onRender);
        com.wf.wflib.client.rail.PublishedAlignments.addListener(this);
        com.wf.wflib.client.rail.PublishedAlignments.addSaveListener(this);
        LOGGER.info("[wflib] rail alignment editor attached to JourneyMap");
    }

    // -- toolbar ----------------------------------------------------------------------------------

    private void onToolbar(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
        this.button = event.getThemeButtonDisplay().addThemeToggleButton(
                "Survey on", "Survey off", ICON, this.armed, this::onButtonPressed);
        this.button.setTooltip("Rail alignment",
                "Left click to add a point, or drag one to move it.",
                "Right click for radius, insert and delete.");
    }

    private void onButtonPressed(IThemeButton pressed) {
        this.armed = !this.armed;
        pressed.setToggled(this.armed);
        if (!this.armed) {
            this.dragIndex = -1;
        }
    }

    // -- input ------------------------------------------------------------------------------------

    /**
     * Take a click when the tool is armed.
     *
     * <p>Cancelled at {@code PRE}, which is the only stage that can be cancelled: unhandled, the same
     * click pans the map or drops a waypoint. When the tool is disarmed nothing is cancelled and
     * JourneyMap behaves exactly as it did.</p>
     */
    private void onClick(FullscreenMapEvent.ClickEvent event) {
        if (!this.armed || event.getStage() != FullscreenMapEvent.Stage.PRE || event.getButton() != 0) {
            return;
        }
        MapTransform transform = this.renderer.transform();
        if (transform == null) {
            return;
        }
        double blockX = transform.toBlockX(event.getMouseX());
        double blockZ = transform.toBlockZ(event.getMouseY());
        checkAgainstJourneyMap(transform, event, blockX, blockZ);

        double tolerance = PICK_PIXELS * transform.blocksPerPixel();
        int hit = this.editor.pickPoint(blockX, blockZ, tolerance);
        if (hit >= 0) {
            this.editor.select(hit);
            this.dragIndex = hit;
        } else {
            if (this.editor.isEmpty()) {
                this.lineDimension = event.getLevel();
            }
            this.editor.addPoint(blockX, blockZ);
        }
        event.cancel();
    }

    private void onDrag(FullscreenMapEvent.MouseDraggedEvent event) {
        if (!this.armed || this.dragIndex < 0 || event.getStage() != FullscreenMapEvent.Stage.PRE) {
            return;
        }
        MapTransform transform = this.renderer.transform();
        if (transform == null) {
            return;
        }
        this.editor.movePoint(this.dragIndex, transform.toBlockX(event.getMouseX()),
                transform.toBlockZ(event.getMouseY()));
        // Without this the map pans out from under the point being dragged.
        event.cancel();
    }

    private void onMove(FullscreenMapEvent.MouseMoveEvent event) {
        if (!this.armed) {
            this.hoverIndex = -1;
            return;
        }
        MapTransform transform = this.renderer.transform();
        if (transform == null) {
            return;
        }
        this.hoverIndex = this.editor.pickPoint(transform.toBlockX(event.getMouseX()),
                transform.toBlockZ(event.getMouseY()), PICK_PIXELS * transform.blocksPerPixel());
    }

    /**
     * The right-click menu.
     *
     * <p>Every item that acts on a point acts on the point under the cursor, with a generous tolerance,
     * and says so when there is none. The first version said "the nearest point" and silently did
     * nothing when the cursor was out of range, which is indistinguishable from a broken menu.</p>
     */
    private void onPopupMenu(PopupMenuEvent.FullscreenPopupMenuEvent event) {
        MapTransform transform = this.renderer.transform();
        if (transform == null) {
            return;
        }
        var menu = event.getPopupMenu();
        menu.addMenuItem(this.armed ? "Rail: stop surveying" : "Rail: survey a line", pos -> {
            this.armed = !this.armed;
            if (this.button != null) {
                this.button.setToggled(this.armed);
            }
        });
        if (!this.armed) {
            return;
        }

        double tolerance = MENU_PICK_PIXELS * transform.blocksPerPixel();

        menu.addMenuItem("Rail: add a point here", pos -> this.editor.addPoint(pos.getX(), pos.getZ()));
        menu.addMenuItem("Rail: bend the line here", pos -> {
            int index = this.editor.pickLeg(pos.getX(), pos.getZ(), tolerance);
            if (index > 0) {
                this.editor.insertPoint(index, pos.getX(), pos.getZ());
            } else {
                this.editor.say("no leg of the line within " + Math.round(tolerance) + " blocks", 4000);
            }
        });
        menu.addMenuItem("Rail: delete the point here", pos -> withPointAt(pos.getX(), pos.getZ(), tolerance,
                this.editor::removePoint));

        var radii = menu.createSubItemList("Rail: radius of the point here");
        for (double radius : new double[]{30, 60, 100, 200, 400, 800}) {
            radii.addMenuItem(String.format(Locale.ROOT, "%.0f blocks", radius),
                    pos -> withPointAt(pos.getX(), pos.getZ(), tolerance,
                            index -> this.editor.setRadius(index, radius)));
        }
        var classes = menu.createSubItemList("Rail: design class");
        for (DesignClass designClass : DesignClass.values()) {
            classes.addMenuItem(designClass.name().toLowerCase(Locale.ROOT), pos -> {
                this.editor.setDesignClass(designClass);
                this.editor.say("design class is now " + designClass.name().toLowerCase(Locale.ROOT)
                        + ", minimum radius " + Math.round(designClass.minRadius()), 4000);
            });
        }

        var colours = menu.createSubItemList("Rail: colour of this line");
        for (LineColour colour : LineColour.values()) {
            colours.addMenuItem(colour.lowerName(), pos -> {
                this.editor.setCoreColour(colour.rgb());
                this.editor.say("line core is " + colour.lowerName()
                        + "; the outline stays the faction's colour", 4000);
            });
        }

        var statuses = menu.createSubItemList("Rail: status of this route");
        for (RouteStatus status : RouteStatus.values()) {
            statuses.addMenuItem(status.lowerName(), pos -> setStatus(status));
        }

        var construction = menu.createSubItemList("Rail: construction");
        construction.addMenuItem("built from the start to here",
                pos -> mark(0.0, chainageNear(pos.getX(), pos.getZ()), true));
        construction.addMenuItem("built from here to the end",
                pos -> mark(chainageNear(pos.getX(), pos.getZ()),
                        this.editor.result().centreline().length(), true));
        construction.addMenuItem("the whole route is built",
                pos -> mark(0.0, this.editor.result().centreline().length(), true));
        construction.addMenuItem("track here is gone", pos -> {
            double at = chainageNear(pos.getX(), pos.getZ());
            mark(at - 32.0, at + 32.0, false);
        });
        construction.addMenuItem("nothing on it is built",
                pos -> mark(0.0, this.editor.result().centreline().length(), false));

        var routes = menu.createSubItemList("Rail: open a route");
        int listed = 0;
        for (AlignmentView view : PublishedAlignments.current()) {
            if (!view.mayEdit() || listed >= MENU_ROUTE_LIMIT) {
                continue;
            }
            listed++;
            String label = (view.name().isEmpty() ? "unnamed" : view.name())
                    + " (" + view.status().lowerName() + ")";
            routes.addMenuItem(label, pos -> load(view));
        }
        if (listed == 0) {
            routes.addMenuItem("nothing here is yours to edit", pos -> this.editor.say(
                    "your faction has no routes in this world yet", 4000));
        }

        menu.addMenuItem("Rail: open the route here", pos -> loadPublishedAt(pos.getX(), pos.getZ()));
        menu.addMenuItem("Rail: start a new route", pos -> {
            this.editor.clear();
            this.editor.say("started a new route; the last one is still on the server", 4000);
        });
        menu.addMenuItem("Rail: delete this route", pos -> {
            WFNetwork.sendToServer(new RailDeletePacket(this.editor.id()));
            this.editor.clear();
            this.editor.say("asked the server to delete it", 4000);
        });
    }

    // -- talking to the server ---------------------------------------------------------------------

    /**
     * Save the working copy, once it has stopped changing.
     *
     * <p>Not a deliberate act: a route belongs to the faction from its second point onwards, and asking
     * someone to remember to publish is asking them to lose an afternoon's survey to a crash. Held back
     * during a drag, because a save per frame of a drag is the one thing this must not do.</p>
     */
    private void pollAutosave() {
        if (this.editor.size() < 2 || !this.editor.mayEdit()) {
            return;
        }
        if (System.currentTimeMillis() < this.saveBlockedUntil) {
            return;
        }
        // The server saves into whichever world the player is standing in, so a route drawn elsewhere
        // must not be sent from here: it would be filed under the wrong dimension's routes.
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

    /**
     * Name a route after the surveyor and the class, because there is nowhere to type a name from a
     * popup menu. The colour is what actually tells one of your own routes from another, and that is
     * chosen.
     */
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

    /** Record a stretch of the route as built, or as no longer built. */
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

    /** How far along the working route a map position is, in blocks. */
    private double chainageNear(double x, double z) {
        var centreline = this.editor.result().centreline();
        double length = centreline.length();
        if (length <= 0.0) {
            return 0.0;
        }
        // Coarse enough to be free, fine enough that a menu click lands on the right side of a curve.
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

    /**
     * A newer version of the route being edited has arrived.
     *
     * <p>Taken only when there is nothing unsaved here, so a teammate saving cannot pull the map out
     * from under a drag. Track laid and the status are taken either way: neither is in competition with
     * anyone moving a point.</p>
     */
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
                // The work is still good; it just is not that route any more.
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
                // Fewer than two points. The editor already refuses to send one of those.
            }
        }
    }

    /** Pull a route into the editor, keeping its identity so a save replaces it. */
    private void load(AlignmentView view) {
        this.editor.loadPublished(view.id(), view.name(), view.coreColour(), view.points(),
                view.designClass(), view.status(), view.built(), view.revision(), view.mayEdit());
        var level = Minecraft.getInstance().level;
        this.lineDimension = level == null ? null : level.dimension();
        this.editor.say("editing \"" + view.name() + "\" (" + view.status().lowerName() + ")"
                + (view.mayEdit() ? "" : ", which is not yours to change"), 5000);
    }

    /** Pull the route nearest a map position into the editor. */
    private void loadPublishedAt(double x, double z) {
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
        MapTransform transform = this.renderer.transform();
        double tolerance = transform == null ? 64.0 : MENU_PICK_PIXELS * 4 * transform.blocksPerPixel();
        if (nearest == null || best > tolerance) {
            this.editor.say("no route within " + Math.round(tolerance) + " blocks of there", 4000);
            return;
        }
        load(nearest);
    }

    /** Run something on the point under the cursor, or explain why nothing happened. */
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

    private void onRender(FullscreenRenderEvent event) {
        IFullscreen fullscreen = event.getFullscreen();
        this.renderer.update(fullscreen);
        pollRightOfWay();
        // There is no mouse-release event in the API, so the button itself is the authority on whether
        // a drag is still happening. Same reason a held keybind has to read GLFW rather than isDown.
        if (this.dragIndex >= 0 && !mouseHeld()) {
            this.dragIndex = -1;
        }
        if (!this.armed && this.editor.isEmpty()) {
            return;
        }
        // A line belongs to the dimension it was surveyed in. Its coordinates mean something else in
        // the Nether, so drawing it there would be drawing a different line.
        if (!this.editor.isEmpty() && this.lineDimension != null
                && !this.lineDimension.equals(fullscreen.getUiState().dimension)) {
            return;
        }
        this.renderer.draw(event.getGraphics(), this.editor, this.armed, this.hoverIndex,
                RouteOwnership.current(),
                new RailMapRenderer.Palette(COLOUR_LINE, COLOUR_LINE_BAD, COLOUR_LEG,
                        COLOUR_HANDLE, COLOUR_HANDLE_SELECTED, COLOUR_HANDLE_BORDER));
    }

    /**
     * Ask the server whose land the line crosses, once it has stopped moving.
     *
     * <p>The answer is thrown away the moment the line changes rather than kept until a new one
     * arrives. A right of way drawn against a line that has since moved is worse than none, because it
     * still looks authoritative.</p>
     */
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

    /**
     * Cross-check our own transform against the block position JourneyMap reports for the same click.
     *
     * <p>JourneyMap hands us both the mouse position and the block it decided that was, so the one
     * number the entire map layer rests on can check itself against the authority rather than be taken
     * on trust. This is not paranoia: the first version of the transform was out by 32 blocks at one
     * zoom because {@code displayBounds} and {@code blockSize} are in different coordinate spaces, and
     * the only visible symptom was a line drawn in the wrong part of the map.</p>
     *
     * <p>A few clicks rather than one, because an error proportional to the position reads as zero at
     * the origin.</p>
     */
    private int transformChecks;

    private void checkAgainstJourneyMap(MapTransform transform, FullscreenMapEvent.ClickEvent event,
                                        double blockX, double blockZ) {
        if (this.transformChecks >= TRANSFORM_CHECKS) {
            return;
        }
        this.transformChecks++;
        double slack = Math.max(1.0, transform.blocksPerPixel());
        double dx = Math.abs(blockX - event.getLocation().getX());
        double dz = Math.abs(blockZ - event.getLocation().getZ());
        if (dx > slack || dz > slack) {
            LOGGER.warn("[wflib] map transform disagrees with JourneyMap by ({}, {}) blocks at {}"
                            + " blocks per pixel; clicks will land in the wrong place."
                            + " mouse=({}, {}) jm=({}, {}) mine=({}, {}) centreBlock=({}, {})"
                            + " drag=({}, {}) centreFb=({}, {}) blockSize={} guiScale={}",
                    String.format(Locale.ROOT, "%.1f", dx), String.format(Locale.ROOT, "%.1f", dz),
                    String.format(Locale.ROOT, "%.2f", transform.blocksPerPixel()),
                    event.getMouseX(), event.getMouseY(),
                    event.getLocation().getX(), event.getLocation().getZ(),
                    String.format(Locale.ROOT, "%.1f", blockX), String.format(Locale.ROOT, "%.1f", blockZ),
                    transform.centreBlockX(), transform.centreBlockZ(),
                    transform.dragX(), transform.dragZ(),
                    transform.centreFbX(), transform.centreFbY(),
                    transform.blockSize(), transform.guiScale());
            var fs = this.renderer.fullscreen();
            if (fs != null && fs.getUiState() != null && fs.getUiState().blockBounds != null) {
                var st = fs.getUiState();
                LOGGER.warn("[wflib]   raw: blockBounds=[{}..{}]x[{}..{}] display=({}, {}, {}x{})"
                                + " zoom={} mapCenter={} centreNoDrag=({}, {}) centreDrag=({}, {})",
                        st.blockBounds.minX, st.blockBounds.maxX, st.blockBounds.minZ, st.blockBounds.maxZ,
                        st.displayBounds.x, st.displayBounds.y,
                        st.displayBounds.width, st.displayBounds.height,
                        st.zoom, st.mapCenter,
                        fs.getCenterBlockX(false), fs.getCenterBlockZ(false),
                        fs.getCenterBlockX(true), fs.getCenterBlockZ(true));
            }
        } else if (this.transformChecks == 1) {
            LOGGER.info("[wflib] map transform agrees with JourneyMap to within {} block(s)",
                    String.format(Locale.ROOT, "%.2f", Math.max(dx, dz)));
        }
    }

    /** The line being edited, for whatever else wants to read or publish it. */
    public AlignEditor editor() {
        return this.editor;
    }

    /** @return the PIs as they stand, in world coordinates. */
    public java.util.List<AlignPoint> points() {
        return this.editor.points();
    }
}
