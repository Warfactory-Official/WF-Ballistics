package com.wf.wflib.client.journeymap;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.recon.ReconMapClient;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.map.ReconMapView;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Draws a sensor network on JourneyMap: what it can see, and what it is hanging by. */
@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class ReconJourneyMapPlugin implements IClientPlugin, ReconMapClient.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/journeymap");

    /** Points around a coverage circle. Enough that a 384-block seismic ring does not read as a polygon. */
    private static final int CIRCLE_POINTS = 48;
    /** Half-width of a link line, in blocks. */
    private static final double LINK_HALF_WIDTH = 2.5;
    /** Half-side of a node marker, in blocks. Same reasoning: it is the more reliable of the two targets. */
    private static final int NODE_HALF = 5;

    private static final String GROUP_COVERAGE = "WF Recon: coverage";
    private static final String GROUP_LINKS = "WF Recon: links";
    private static final String GROUP_NODES = "WF Recon: nodes";

    private static final int COLOUR_HUB = 0xFFC24B;
    private static final int COLOUR_NODE = 0x8FD8FF;
    private static final int COLOUR_LINK = 0x66E0FF;
    /** A node with no route home. Red against amber and blue markers, so it reads as the fault it is. */
    private static final int COLOUR_ORPHAN = 0xFF4A3D;
    /** A footprint contributing nothing, drawn grey rather than in the same red as an orphaned node. */
    private static final int COLOUR_INERT = 0x9AA0A6;
    private static final int COLOUR_MET = 0x8CE99A;
    /** The hover ring. White so it reads as a measurement rather than as another thing on the network. */
    private static final int COLOUR_REACH = 0xFFFFFF;

    @Nullable
    private IClientAPI api;
    private final List<PolygonOverlay> shown = new ArrayList<>();
    /** The reach ring currently drawn, and whose it is. At most one: it follows a single cursor. */
    @Nullable
    private PolygonOverlay ring;
    @Nullable
    private ReachRing ringOwner;

    @Override
    public String getModId() {
        return WFLib.MODID;
    }

    @Override
    public void initialize(IClientAPI clientApi) {
        this.api = clientApi;
        // Last, because setListener replays whatever has already arrived and that replay draws immediately.
        ReconMapClient.setListener(this);
    }

    @Override
    public void onReconMap(ResourceKey<Level> dimension, ReconMapView view) {
        Minecraft.getInstance().execute(() -> rebuild(dimension, view));
    }

    /** Throw the previous drawing away and make a new one. */
    private void rebuild(ResourceKey<Level> dimension, ReconMapView view) {
        IClientAPI jm = this.api;
        if (jm == null) {
            return;
        }
        clear(jm);
        if (view.isEmpty() || !jm.playerAccepts(getModId(), DisplayType.Polygon)) {
            return;
        }

        String net = Long.toHexString(view.netId());
        for (ReconMapView.Footprint shape : view.coverage()) {
            show(jm, coverage(dimension, shape));
        }

        Map<Long, ReconMapView.Node> byPos = new HashMap<>();
        for (ReconMapView.Node node : view.nodes()) {
            byPos.put(node.pos().asLong(), node);
        }
        for (ReconMapView.Node node : view.nodes()) {
            ReconMapView.Node parent = node.uplink() == 0L ? null : byPos.get(node.uplink());
            if (parent != null) {
                show(jm, link(dimension, net, node, parent));
            }
            show(jm, marker(dimension, net, node));
        }
    }

    private void show(IClientAPI jm, PolygonOverlay overlay) {
        try {
            jm.show(overlay);
            shown.add(overlay);
        } catch (Exception e) {
            // One overlay failing must not lose the rest of the grid, and this is cosmetic either way.
            LOGGER.warn("could not show recon overlay {}", overlay.getId(), e);
        }
    }

    private void clear(IClientAPI jm) {
        hideRing(null);
        for (PolygonOverlay overlay : shown) {
            jm.remove(overlay);
        }
        shown.clear();
    }

    /** One sensor's nominal footprint. */
    private PolygonOverlay coverage(ResourceKey<Level> dimension, ReconMapView.Footprint shape) {
        Band band = shape.band();
        int colour = shape.online() ? bandColour(band) : COLOUR_INERT;
        ShapeProperties props = new ShapeProperties()
                .setStrokeWidth(shape.online() ? 1.5f : 1.0f)
                .setStrokeColor(colour)
                .setStrokeOpacity(shape.online() ? 0.55f : 0.35f)
                .setFillColor(colour)
                .setFillOpacity(shape.online() ? 0.10f : 0.0f);

        String label = band == null ? "met" : band.name().toLowerCase(Locale.ROOT);
        PolygonOverlay overlay = new PolygonOverlay(getModId(), dimension, props,
                circle(shape.pos(), shape.range()));
        overlay.setOverlayGroupName(GROUP_COVERAGE)
                .setTitle(String.format(Locale.ROOT, "%s %s: %.0f blocks nominal%s",
                        band == null ? "met station" : label + " sensor",
                        shape.pos().toShortString(), shape.range(),
                        shape.online() ? "" : "  (no route to a hub - contributing nothing)"))
                .setDisplayOrder(0)
                .setTextProperties(text(colour));
        return overlay;
    }

    /** The line from a node to whatever it reaches the hub through. */
    private PolygonOverlay link(ResourceKey<Level> dimension, String net, ReconMapView.Node node,
                                ReconMapView.Node parent) {
        ShapeProperties props = new ShapeProperties()
                .setStrokeWidth(0.0f)
                .setStrokeColor(COLOUR_LINK)
                .setStrokeOpacity(0.0f)
                .setFillColor(COLOUR_LINK)
                .setFillOpacity(0.7f);
        double reach = Math.min(node.linkRange(), parent.linkRange());
        double span = Math.sqrt(node.pos().distSqr(parent.pos()));
        PolygonOverlay overlay = new PolygonOverlay(getModId(), dimension, props,
                segment(node.pos(), parent.pos()));
        overlay.setOverlayGroupName(GROUP_LINKS)
                .setTitle(String.format(Locale.ROOT, "%s -> %s%nspans %.0f of %.0f blocks (%.0f%% used)%n"
                                + "hop %d of net %s",
                        node.pos().toShortString(), parent.pos().toShortString(), span, reach,
                        reach <= 0.0 ? 0.0 : 100.0 * span / reach, node.hops(), net))
                .setDisplayOrder(5)
                .setOverlayListener(new ReachRing(dimension, node.pos(), reach,
                        String.format(Locale.ROOT, "link limit %.0f blocks", reach)));
        return overlay;
    }

    /** One grid member, labelled with the number that decides where the next one goes. */
    private PolygonOverlay marker(ResourceKey<Level> dimension, String net, ReconMapView.Node node) {
        int colour = !node.online() ? COLOUR_ORPHAN : node.root() ? COLOUR_HUB : COLOUR_NODE;
        ShapeProperties props = new ShapeProperties()
                .setStrokeWidth(1.5f)
                .setStrokeColor(colour)
                .setStrokeOpacity(0.95f)
                .setFillColor(colour)
                .setFillOpacity(0.65f);

        String label = node.root() ? "HUB" : node.label();
        String detail = !node.online()
                ? "ORPHANED - no route to a hub"
                : String.format(Locale.ROOT, "%d hop(s), carrying %d", node.hops(), node.downstream());

        PolygonOverlay overlay = new PolygonOverlay(getModId(), dimension, props,
                square(node.pos(), NODE_HALF));
        overlay.setOverlayGroupName(GROUP_NODES)
                .setLabel(node.downstream() > 0 ? label + " x" + node.downstream() : label)
                .setTitle(String.format(Locale.ROOT, "%s %s%n%s%nradio reaches %.0f blocks%nnet %s",
                        label, node.pos().toShortString(), detail, node.linkRange(), net))
                .setDisplayOrder(10)
                .setTextProperties(text(colour))
                .setOverlayListener(new ReachRing(dimension, node.pos(), node.linkRange(),
                        String.format(Locale.ROOT, "%s radio: %.0f blocks", label, node.linkRange())));
        return overlay;
    }

    /** Draws a node's radio reach while the cursor is on it, and takes it away again. */
    private final class ReachRing implements IOverlayListener {

        private final ResourceKey<Level> dimension;
        private final BlockPos centre;
        private final double reach;
        private final String title;

        private ReachRing(ResourceKey<Level> dimension, BlockPos centre, double reach, String title) {
            this.dimension = dimension;
            this.centre = centre;
            this.reach = reach;
            this.title = title;
        }

        @Override
        public void onMouseMove(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
            showRing(this);
        }

        @Override
        public void onMouseOut(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
            hideRing(this);
        }

        @Override
        public void onDeactivate(UIState mapState) {
            hideRing(this);
        }

        private PolygonOverlay build() {
            ShapeProperties props = new ShapeProperties()
                    .setStrokeWidth(2.0f)
                    .setStrokeColor(COLOUR_REACH)
                    .setStrokeOpacity(0.9f)
                    .setFillColor(COLOUR_REACH)
                    .setFillOpacity(0.0f);
            PolygonOverlay ring = new PolygonOverlay(getModId(), dimension, props, circle(centre, reach));
            ring.setOverlayGroupName(GROUP_NODES)
                    .setTitle(title)
                    .setActiveUIs(Context.UI.Fullscreen)
                    .setDisplayOrder(20);
            return ring;
        }
    }

    /** Replace whatever ring is showing with this one. */
    private void showRing(ReachRing owner) {
        IClientAPI jm = this.api;
        if (jm == null || this.ringOwner == owner) {
            return;
        }
        hideRing(this.ringOwner);
        PolygonOverlay ring = owner.build();
        try {
            jm.show(ring);
            this.ringOwner = owner;
            this.ring = ring;
        } catch (Exception e) {
            LOGGER.warn("could not show reach ring", e);
        }
    }

    /**
     * @param owner whose ring to take down, or null to take down whatever is up. Ignored if somebody else's
     *      ring is showing: two overlays under one cursor both get the mouse events, and a stale
     *      mouse-out must not remove the ring the other one just put up.
     */
    private void hideRing(@Nullable ReachRing owner) {
        IClientAPI jm = this.api;
        if (jm == null || this.ring == null || (owner != null && this.ringOwner != owner)) {
            return;
        }
        jm.remove(this.ring);
        this.ring = null;
        this.ringOwner = null;
    }

    private static TextProperties text(int colour) {
        return new TextProperties()
                .setColor(colour)
                .setOpacity(1.0f)
                .setBackgroundOpacity(0.4f)
                .setFontShadow(true);
    }

    private static int bandColour(@Nullable Band band) {
        if (band == null) {
            return COLOUR_MET;
        }
        return switch (band) {
            case RADAR -> 0x2FD2FF;
            case SEISMIC -> 0xFFA640;
            case THERMAL -> 0xFF6B57;
            case ACOUSTIC -> 0xC98BFF;
            case EM -> 0x9CFF6B;
            case SONAR -> 0x3FFFC8;
        };
    }

    /** A regular polygon standing in for a circle. */
    private static MapPolygon circle(BlockPos centre, double radius) {
        List<BlockPos> points = new ArrayList<>(CIRCLE_POINTS);
        double cx = centre.getX() + 0.5;
        double cz = centre.getZ() + 0.5;
        for (int i = 0; i < CIRCLE_POINTS; i++) {
            double angle = -2.0 * Math.PI * i / CIRCLE_POINTS;
            points.add(new BlockPos((int) Math.round(cx + radius * Math.cos(angle)), centre.getY(),
                    (int) Math.round(cz + radius * Math.sin(angle))));
        }
        return new MapPolygon(points);
    }

    private static MapPolygon square(BlockPos centre, int half) {
        int x = centre.getX();
        int y = centre.getY();
        int z = centre.getZ();
        return new MapPolygon(
                new BlockPos(x - half, y, z + half),
                new BlockPos(x + half, y, z + half),
                new BlockPos(x + half, y, z - half),
                new BlockPos(x - half, y, z - half));
    }

    /** A line as a quad: the two ends, offset either side by the perpendicular. */
    private static MapPolygon segment(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-3) {
            return square(from, 1);
        }
        double ux = dx / length;
        double uz = dz / length;
        double trim = Math.min(NODE_HALF + 1.0, length * 0.5 - 0.5);
        double px = -uz * LINK_HALF_WIDTH;
        double pz = ux * LINK_HALF_WIDTH;
        int y = Math.max(from.getY(), to.getY());
        double startX = from.getX() + ux * trim;
        double startZ = from.getZ() + uz * trim;
        double endX = to.getX() - ux * trim;
        double endZ = to.getZ() - uz * trim;
        return new MapPolygon(
                offset(startX + px, y, startZ + pz),
                offset(endX + px, y, endZ + pz),
                offset(endX - px, y, endZ - pz),
                offset(startX - px, y, startZ - pz));
    }

    private static BlockPos offset(double x, int y, double z) {
        return new BlockPos((int) Math.round(x), y, (int) Math.round(z));
    }
}
