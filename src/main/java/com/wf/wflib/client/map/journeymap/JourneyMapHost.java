package com.wf.wflib.client.map.journeymap;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.MapHost;
import com.wf.wflib.client.map.MapHover;
import com.wf.wflib.client.map.MapPoint;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapStyle;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.display.Overlay;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MapShape} -> JourneyMap overlays. Zoom: {@code blockSize = zoom / 512} framebuffer px/block, so a
 * pixel-sized {@link MapShape.Line} is published once per zoom band (JM has no polyline, no screen-space width).
 */
public final class JourneyMapHost implements MapHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/journeymap");

    /** JM zoom range 2..16384 in x4 bands; ribbon half-width taken at each band's geometric mean. */
    private static final int[] BAND_EDGES = {2, 8, 32, 128, 512, 2048, 8192, 16384};

    private final IClientAPI api;
    private final Map<String, List<Overlay>> shown = new HashMap<>();

    public JourneyMapHost(IClientAPI api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "journeymap";
    }

    @Override
    public boolean hasFullscreen() {
        return true;
    }

    @Override
    public void openFullscreen() {
        journeymap.client.ui.UIManager.INSTANCE.getOrOpenFullscreenMap();
    }

    @Override
    public void show(String layer, ResourceKey<Level> dimension, List<MapShape> shapes) {
        List<Overlay> previous = this.shown.remove(layer);
        if (previous != null) {
            previous.forEach(this.api::remove);
        }
        if (shapes.isEmpty()) {
            return;
        }
        List<Overlay> out = new ArrayList<>();
        for (MapShape shape : shapes) {
            switch (shape) {
                case MapShape.Area area -> {
                    if (this.api.playerAccepts(WFLib.MODID, DisplayType.Polygon)) {
                        add(out, look(new PolygonOverlay(WFLib.MODID, dimension, props(area.style()),
                                polygon(area.outline(), area.y())), area.look(), 0, Integer.MAX_VALUE));
                    }
                }
                case MapShape.Line line -> {
                    if (this.api.playerAccepts(WFLib.MODID, DisplayType.Polygon)) {
                        ribbons(out, dimension, line);
                    }
                }
                case MapShape.Icon icon -> {
                    if (this.api.playerAccepts(WFLib.MODID, DisplayType.Marker)) {
                        MapImage image = new MapImage(icon.texture(), icon.textureSize(), icon.textureSize())
                                .setDisplayWidth(icon.sizePixels())
                                .setDisplayHeight(icon.sizePixels())
                                .centerAnchors()
                                .setRotation(Math.round(icon.rotation()))
                                .setColor(icon.tint())
                                .setOpacity(icon.opacity());
                        // MarkerOverlay has no dimension in its constructor; null => never drawn.
                        add(out, look(new MarkerOverlay(WFLib.MODID, pos(icon.at(), icon.y()), image)
                                .setDimension(dimension), icon.look(), 0, Integer.MAX_VALUE));
                    }
                }
            }
        }
        this.shown.put(layer, out);
    }

    private void add(List<Overlay> out, Overlay overlay) {
        try {
            this.api.show(overlay);
            out.add(overlay);
        } catch (Exception e) {
            LOGGER.warn("could not show overlay {}", overlay.getId(), e);
        }
    }

    private void ribbons(List<Overlay> out, ResourceKey<Level> dimension, MapShape.Line line) {
        if (line.path().size() < 2) {
            return;
        }
        for (int i = 0; i + 1 < BAND_EDGES.length; i++) {
            int lo = BAND_EDGES[i];
            int hi = BAND_EDGES[i + 1] - 1;
            double zoom = Math.sqrt((double) lo * hi);
            double halfWidth = line.widthPixels() / 2.0 * 512.0 / zoom;
            add(out, look(new PolygonOverlay(WFLib.MODID, dimension, props(line.style()),
                    ribbon(line.path(), line.y(), halfWidth)), line.look(), lo, hi));
        }
    }

    /** Out one side, back the other: same winding as an {@link MapShape.Area}. */
    private static MapPolygon ribbon(List<MapPoint> path, int y, double halfWidth) {
        int n = path.size();
        List<BlockPos> left = new ArrayList<>(n);
        List<BlockPos> right = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            MapPoint a = path.get(Math.max(0, i - 1));
            MapPoint b = path.get(Math.min(n - 1, i + 1));
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            double nx = length < 1.0e-9 ? 0.0 : -dz / length * halfWidth;
            double nz = length < 1.0e-9 ? 0.0 : dx / length * halfWidth;
            MapPoint p = path.get(i);
            left.add(BlockPos.containing(p.x() + nx, y, p.z() + nz));
            right.add(BlockPos.containing(p.x() - nx, y, p.z() - nz));
        }
        List<BlockPos> points = new ArrayList<>(2 * n);
        points.addAll(left);
        for (int i = n - 1; i >= 0; i--) {
            points.add(right.get(i));
        }
        return new MapPolygon(points);
    }

    private static Overlay look(Overlay overlay, MapShape.Look look, int bandMin, int bandMax) {
        int minZoom = Math.max(bandMin, look.maxBlocksPerPixel() == Double.POSITIVE_INFINITY ? 0
                : (int) Math.floor(512.0 / look.maxBlocksPerPixel()));
        int maxZoom = Math.min(bandMax, look.minBlocksPerPixel() <= 0.0 ? Integer.MAX_VALUE
                : (int) Math.ceil(512.0 / look.minBlocksPerPixel()));
        overlay.setOverlayGroupName(look.group())
                .setTitle(look.title())
                .setLabel(look.label())
                .setDisplayOrder(look.order())
                .setMinZoom(minZoom)
                .setMaxZoom(maxZoom)
                .setTextProperties(new TextProperties()
                        .setColor(look.textColour())
                        .setOpacity(1.0f)
                        .setBackgroundOpacity(0.4f)
                        .setFontShadow(true));
        if (look.fullscreen()) {
            overlay.setActiveUIs(Context.UI.Fullscreen);
        }
        MapHover hover = look.hover();
        if (hover != null) {
            overlay.setOverlayListener(new IOverlayListener() {
                @Override
                public void onMouseMove(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
                    hover.enter();
                }

                @Override
                public void onMouseOut(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
                    hover.exit();
                }

                @Override
                public void onDeactivate(UIState mapState) {
                    hover.exit();
                }
            });
        }
        return overlay;
    }

    private static ShapeProperties props(MapStyle style) {
        return new ShapeProperties()
                .setStrokeWidth(style.strokeWidth())
                .setStrokeColor(style.strokeColour())
                .setStrokeOpacity(style.strokeOpacity())
                .setFillColor(style.fillColour())
                .setFillOpacity(style.fillOpacity());
    }

    private static MapPolygon polygon(List<MapPoint> outline, int y) {
        List<BlockPos> points = new ArrayList<>(outline.size());
        for (MapPoint p : outline) {
            points.add(pos(p, y));
        }
        return new MapPolygon(points);
    }

    private static BlockPos pos(MapPoint p, int y) {
        return new BlockPos((int) Math.round(p.x()), y, (int) Math.round(p.z()));
    }
}
