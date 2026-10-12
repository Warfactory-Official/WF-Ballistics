package com.wf.wflib.client.recon;

import com.wf.wflib.client.map.MapHover;
import com.wf.wflib.client.map.MapPoint;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapStyle;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.map.ReconMapView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A sensor network on the map: coverage, links, nodes; hovered node/link => its radio reach. */
public final class ReconMapLayer implements ReconMapClient.Listener {

    private static final String LAYER = "wflib:recon";
    private static final String LAYER_REACH = "wflib:recon/reach";

    /** Enough that a 384-block seismic ring does not read as a polygon. */
    private static final int CIRCLE_POINTS = 48;
    private static final double LINK_HALF_WIDTH = 2.5;
    private static final int NODE_HALF = 5;

    private static final String GROUP_COVERAGE = "WF Recon: coverage";
    private static final String GROUP_LINKS = "WF Recon: links";
    private static final String GROUP_NODES = "WF Recon: nodes";

    private static final int COLOUR_HUB = 0xFFC24B;
    private static final int COLOUR_NODE = 0x8FD8FF;
    private static final int COLOUR_LINK = 0x66E0FF;
    private static final int COLOUR_ORPHAN = 0xFF4A3D;
    /** Grey, not orphan red: a footprint contributing nothing != a fault. */
    private static final int COLOUR_INERT = 0x9AA0A6;
    private static final int COLOUR_MET = 0x8CE99A;
    private static final int COLOUR_REACH = 0xFFFFFF;

    @Nullable
    private ReachRing ring;

    public static void register() {
        ReconMapClient.setListener(new ReconMapLayer());
    }

    @Override
    public void onReconMap(ResourceKey<Level> dimension, ReconMapView view) {
        this.ring = null;
        WFMaps.clear(LAYER_REACH);
        if (view.isEmpty()) {
            WFMaps.publish(LAYER, dimension, List.of());
            return;
        }
        List<MapShape> shapes = new ArrayList<>();
        String net = Long.toHexString(view.netId());
        for (ReconMapView.Footprint shape : view.coverage()) {
            shapes.add(coverage(shape));
        }
        Map<Long, ReconMapView.Node> byPos = new HashMap<>();
        for (ReconMapView.Node node : view.nodes()) {
            byPos.put(node.pos().asLong(), node);
        }
        for (ReconMapView.Node node : view.nodes()) {
            ReconMapView.Node parent = node.uplink() == 0L ? null : byPos.get(node.uplink());
            if (parent != null) {
                shapes.add(link(dimension, net, node, parent));
            }
            shapes.add(marker(dimension, net, node));
        }
        WFMaps.publish(LAYER, dimension, shapes);
    }

    private static MapShape coverage(ReconMapView.Footprint shape) {
        Band band = shape.band();
        int colour = shape.online() ? bandColour(band) : COLOUR_INERT;
        String label = band == null ? "met" : band.name().toLowerCase(Locale.ROOT);
        return new MapShape.Area(circle(shape.pos(), shape.range()), shape.pos().getY(),
                new MapStyle(colour, shape.online() ? 0.55f : 0.35f, shape.online() ? 1.5f : 1.0f, colour,
                        shape.online() ? 0.10f : 0.0f),
                MapShape.Look.of(GROUP_COVERAGE, 0).label("", colour).title(String.format(Locale.ROOT,
                        "%s %s: %.0f blocks nominal%s", band == null ? "met station" : label + " sensor",
                        shape.pos().toShortString(), shape.range(),
                        shape.online() ? "" : "  (no route to a hub - contributing nothing)")));
    }

    private MapShape link(ResourceKey<Level> dimension, String net, ReconMapView.Node node, ReconMapView.Node parent) {
        double reach = Math.min(node.linkRange(), parent.linkRange());
        double span = Math.sqrt(node.pos().distSqr(parent.pos()));
        return new MapShape.Area(segment(node.pos(), parent.pos()), Math.max(node.pos().getY(), parent.pos().getY()),
                MapStyle.fill(COLOUR_LINK, 0.7f),
                MapShape.Look.of(GROUP_LINKS, 5).title(String.format(Locale.ROOT,
                                "%s -> %s%nspans %.0f of %.0f blocks (%.0f%% used)%nhop %d of net %s",
                                node.pos().toShortString(), parent.pos().toShortString(), span, reach,
                                reach <= 0.0 ? 0.0 : 100.0 * span / reach, node.hops(), net))
                        .hover(new ReachRing(dimension, node.pos(), reach,
                                String.format(Locale.ROOT, "link limit %.0f blocks", reach))));
    }

    private MapShape marker(ResourceKey<Level> dimension, String net, ReconMapView.Node node) {
        int colour = !node.online() ? COLOUR_ORPHAN : node.root() ? COLOUR_HUB : COLOUR_NODE;
        String label = node.root() ? "HUB" : node.label();
        String detail = !node.online() ? "ORPHANED - no route to a hub"
                : String.format(Locale.ROOT, "%d hop(s), carrying %d", node.hops(), node.downstream());
        return new MapShape.Area(square(node.pos(), NODE_HALF), node.pos().getY(),
                new MapStyle(colour, 0.95f, 1.5f, colour, 0.65f),
                MapShape.Look.of(GROUP_NODES, 10)
                        .label(node.downstream() > 0 ? label + " x" + node.downstream() : label, colour)
                        .title(String.format(Locale.ROOT, "%s %s%n%s%nradio reaches %.0f blocks%nnet %s",
                                label, node.pos().toShortString(), detail, node.linkRange(), net))
                        .hover(new ReachRing(dimension, node.pos(), node.linkRange(),
                                String.format(Locale.ROOT, "%s radio: %.0f blocks", label, node.linkRange()))));
    }

    /** At most one ring: it follows a single cursor. */
    private final class ReachRing implements MapHover {

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
        public void enter() {
            if (ring == this) {
                return;
            }
            ring = this;
            WFMaps.publish(LAYER_REACH, dimension, List.of(new MapShape.Area(circle(centre, reach), centre.getY(),
                    MapStyle.outline(COLOUR_REACH, 0.9f, 2.0f),
                    MapShape.Look.of(GROUP_NODES, 20).title(title).fullscreenOnly())));
        }

        /** Another's ring up => stale exit, ignored. */
        @Override
        public void exit() {
            if (ring == this) {
                ring = null;
                WFMaps.clear(LAYER_REACH);
            }
        }
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

    private static List<MapPoint> circle(BlockPos centre, double radius) {
        List<MapPoint> points = new ArrayList<>(CIRCLE_POINTS);
        double cx = centre.getX() + 0.5;
        double cz = centre.getZ() + 0.5;
        for (int i = 0; i < CIRCLE_POINTS; i++) {
            double angle = -2.0 * Math.PI * i / CIRCLE_POINTS;
            points.add(new MapPoint(cx + radius * Math.cos(angle), cz + radius * Math.sin(angle)));
        }
        return points;
    }

    private static List<MapPoint> square(BlockPos centre, int half) {
        int x = centre.getX();
        int z = centre.getZ();
        return List.of(new MapPoint(x - half, z + half), new MapPoint(x + half, z + half),
                new MapPoint(x + half, z - half), new MapPoint(x - half, z - half));
    }

    /** Trimmed clear of both node markers. */
    private static List<MapPoint> segment(BlockPos from, BlockPos to) {
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
        double sx = from.getX() + ux * trim;
        double sz = from.getZ() + uz * trim;
        double ex = to.getX() - ux * trim;
        double ez = to.getZ() - uz * trim;
        return List.of(new MapPoint(sx + px, sz + pz), new MapPoint(ex + px, ez + pz),
                new MapPoint(ex - px, ez - pz), new MapPoint(sx - px, sz - pz));
    }
}
