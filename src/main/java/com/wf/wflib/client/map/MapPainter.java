package com.wf.wflib.client.map;

import com.wf.gemrender.ui.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MapShape}s drawn on a {@link Canvas}, for hosts whose map mod has no overlay API. One per host;
 * client thread. Shapes of all layers draw in {@link MapShape.Look#order()} order.
 */
public final class MapPainter {

    /** Block (x, z) -> screen px: {@code sx = xx*x + xz*z + x0}, {@code sy = zx*x + zz*z + z0}. */
    public record Affine(double xx, double xz, double x0, double zx, double zz, double z0) {

        public double screenX(double x, double z) {
            return xx * x + xz * z + x0;
        }

        public double screenY(double x, double z) {
            return zx * x + zz * z + z0;
        }

        public double pixelsPerBlock() {
            return Math.hypot(xx, zx);
        }

        /** Screen angle of block +x, degrees clockwise. */
        public float rotationDegrees() {
            return (float) Math.toDegrees(Math.atan2(zx, xx));
        }

        public double blockX(double sx, double sy) {
            return (zz * (sx - x0) - xz * (sy - z0)) / (xx * zz - xz * zx);
        }

        public double blockZ(double sx, double sy) {
            return (xx * (sy - z0) - zx * (sx - x0)) / (xx * zz - xz * zx);
        }
    }

    /** {@code xz}: interleaved world points; {@code triangles}: Area only. */
    private record Prepared(MapShape shape, double[] xz, int[] triangles, double minX, double minZ, double maxX,
                            double maxZ) {
    }

    private record Layer(ResourceKey<Level> dimension, List<Prepared> shapes) {
    }

    private static final int LABEL_BACKGROUND = 0x66000000;
    /** Line pick slack beyond half its width, px. */
    private static final double LINE_PICK_SLACK = 2.0;

    private final Map<String, Layer> layers = new HashMap<>();
    @Nullable
    private List<Prepared> ordered;
    @Nullable
    private ResourceKey<Level> orderedDimension;
    @Nullable
    private MapHover hovering;

    public void show(String layer, ResourceKey<Level> dimension, List<MapShape> shapes) {
        this.ordered = null;
        if (shapes.isEmpty()) {
            this.layers.remove(layer);
            return;
        }
        List<Prepared> prepared = new ArrayList<>(shapes.size());
        for (MapShape shape : shapes) {
            prepared.add(prepare(shape));
        }
        this.layers.put(layer, new Layer(dimension, prepared));
    }

    /**
     * Viewport {@code [minX, maxX] x [minY, maxY]} in the same px as {@code affine}. {@code mouseX} NaN = no
     * cursor (no hover). Labels: fullscreen only.
     *
     * @return the topmost shape under the cursor, for its title
     */
    @Nullable
    public MapShape paint(Canvas canvas, ResourceKey<Level> dimension, Affine affine, boolean fullscreen,
                          double minX, double minY, double maxX, double maxY, double mouseX, double mouseY) {
        List<Prepared> shapes = ordered(dimension);
        double ppb = affine.pixelsPerBlock();
        double bpp = 1.0 / ppb;
        double[] bounds = worldBounds(affine, minX, minY, maxX, maxY);
        List<Prepared> drawn = new ArrayList<>();
        for (Prepared p : shapes) {
            MapShape.Look look = p.shape().look();
            if (look.fullscreen() && !fullscreen || bpp < look.minBlocksPerPixel() || bpp > look.maxBlocksPerPixel()
                    || !visible(p, bounds, ppb)) {
                continue;
            }
            drawn.add(p);
            switch (p.shape()) {
                case MapShape.Area area -> area(canvas, affine, p, area.style());
                case MapShape.Line line -> line(canvas, affine, p.xz(), line);
                case MapShape.Icon icon -> icon(canvas, affine, icon);
            }
        }
        if (fullscreen) {
            labels(canvas, affine, drawn);
        }
        MapShape hit = Double.isNaN(mouseX) ? null : pick(drawn, affine, mouseX, mouseY);
        hover(hit == null ? null : hit.look().hover());
        return hit;
    }

    /** Cursor left the map. */
    public void leave() {
        hover(null);
    }

    private void hover(@Nullable MapHover next) {
        if (next == this.hovering) {
            return;
        }
        if (this.hovering != null) {
            this.hovering.exit();
        }
        this.hovering = next;
        if (next != null) {
            next.enter();
        }
    }

    private List<Prepared> ordered(ResourceKey<Level> dimension) {
        if (this.ordered == null || !dimension.equals(this.orderedDimension)) {
            List<Prepared> all = new ArrayList<>();
            for (Layer layer : this.layers.values()) {
                if (layer.dimension().equals(dimension)) {
                    all.addAll(layer.shapes());
                }
            }
            all.sort(Comparator.comparingInt(p -> p.shape().look().order()));
            this.ordered = all;
            this.orderedDimension = dimension;
        }
        return this.ordered;
    }

    private static Prepared prepare(MapShape shape) {
        List<MapPoint> points = switch (shape) {
            case MapShape.Area area -> area.outline();
            case MapShape.Line line -> line.path();
            case MapShape.Icon icon -> List.of(icon.at());
        };
        double[] xz = new double[points.size() * 2];
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            MapPoint p = points.get(i);
            xz[i * 2] = p.x();
            xz[i * 2 + 1] = p.z();
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        int[] triangles = shape instanceof MapShape.Area ? triangulate(xz) : null;
        return new Prepared(shape, xz, triangles, minX, minZ, maxX, maxZ);
    }

    /** {minX, minZ, maxX, maxZ} of the viewport in blocks: the four corners, any rotation. */
    private static double[] worldBounds(Affine a, double minX, double minY, double maxX, double maxY) {
        double[] sx = {minX, maxX, maxX, minX};
        double[] sy = {minY, minY, maxY, maxY};
        double[] out = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        for (int i = 0; i < 4; i++) {
            double x = a.blockX(sx[i], sy[i]);
            double z = a.blockZ(sx[i], sy[i]);
            out[0] = Math.min(out[0], x);
            out[1] = Math.min(out[1], z);
            out[2] = Math.max(out[2], x);
            out[3] = Math.max(out[3], z);
        }
        return out;
    }

    private static boolean visible(Prepared p, double[] bounds, double ppb) {
        double pad = switch (p.shape()) {
            case MapShape.Area area -> area.style().strokeWidth() / ppb;
            case MapShape.Line line -> (line.widthPixels() + 2.0 * line.style().strokeWidth()) / ppb;
            case MapShape.Icon icon -> icon.sizePixels() / ppb;
        };
        return p.maxX() + pad >= bounds[0] && p.minX() - pad <= bounds[2]
                && p.maxZ() + pad >= bounds[1] && p.minZ() - pad <= bounds[3];
    }

    private static void area(Canvas canvas, Affine a, Prepared p, MapStyle style) {
        double[] xz = p.xz();
        int n = xz.length / 2;
        float[] s = new float[n * 2];
        for (int i = 0; i < n; i++) {
            s[i * 2] = (float) a.screenX(xz[i * 2], xz[i * 2 + 1]);
            s[i * 2 + 1] = (float) a.screenY(xz[i * 2], xz[i * 2 + 1]);
        }
        if (style.fillOpacity() > 0.0f && p.triangles().length > 0) {
            int argb = argb(style.fillColour(), style.fillOpacity());
            int[] t = p.triangles();
            for (int i = 0; i < t.length; i += 3) {
                canvas.triangle(s[t[i] * 2], s[t[i] * 2 + 1], s[t[i + 1] * 2], s[t[i + 1] * 2 + 1],
                        s[t[i + 2] * 2], s[t[i + 2] * 2 + 1], argb);
            }
        }
        if (style.strokeOpacity() > 0.0f && style.strokeWidth() > 0.0f) {
            int argb = argb(style.strokeColour(), style.strokeOpacity());
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                MapDraw.line(canvas, s[i * 2], s[i * 2 + 1], s[j * 2], s[j * 2 + 1], style.strokeWidth(), argb);
            }
        }
    }

    private static void line(Canvas canvas, Affine a, double[] xz, MapShape.Line line) {
        int n = xz.length / 2;
        double[] s = new double[n * 2];
        for (int i = 0; i < n; i++) {
            s[i * 2] = a.screenX(xz[i * 2], xz[i * 2 + 1]);
            s[i * 2 + 1] = a.screenY(xz[i * 2], xz[i * 2 + 1]);
        }
        MapStyle style = line.style();
        if (style.strokeOpacity() > 0.0f && style.strokeWidth() > 0.0f) {
            MapDraw.polyline(canvas, s, n, line.widthPixels() + 2.0f * style.strokeWidth(),
                    argb(style.strokeColour(), style.strokeOpacity()));
        }
        if (style.fillOpacity() > 0.0f) {
            MapDraw.polyline(canvas, s, n, line.widthPixels(), argb(style.fillColour(), style.fillOpacity()));
        }
    }

    private static void icon(Canvas canvas, Affine a, MapShape.Icon icon) {
        float half = icon.sizePixels() / 2.0f;
        int argb = Math.round(Math.max(0, Math.min(1, icon.opacity())) * 255) << 24 | icon.tint() & 0xFFFFFF;
        canvas.push()
                .translate((float) a.screenX(icon.at().x(), icon.at().z()), (float) a.screenY(icon.at().x(), icon.at().z()))
                .rotate((float) Math.toRadians(icon.rotation() + a.rotationDegrees()));
        canvas.sprite(icon.texture(), -half, -half, half, half, argb);
        canvas.pop();
    }

    private static void labels(Canvas canvas, Affine a, List<Prepared> drawn) {
        Font font = Minecraft.getInstance().font;
        for (Prepared p : drawn) {
            String label = p.shape().look().label();
            if (label.isEmpty()) {
                continue;
            }
            double x;
            double y;
            if (p.shape() instanceof MapShape.Icon icon) {
                x = a.screenX(icon.at().x(), icon.at().z());
                y = a.screenY(icon.at().x(), icon.at().z()) + icon.sizePixels() / 2.0 + 2.0;
            } else {
                double cx = (p.minX() + p.maxX()) / 2.0;
                double cz = (p.minZ() + p.maxZ()) / 2.0;
                x = a.screenX(cx, cz);
                y = a.screenY(cx, cz) - font.lineHeight / 2.0;
            }
            int width = font.width(label);
            int left = (int) Math.round(x - width / 2.0);
            int top = (int) Math.round(y);
            canvas.fill(left - 2, top - 1, left + width + 2, top + font.lineHeight, LABEL_BACKGROUND);
            canvas.text(label, left, top, 0xFF000000 | p.shape().look().textColour(), true);
        }
    }

    @Nullable
    private static MapShape pick(List<Prepared> drawn, Affine a, double mouseX, double mouseY) {
        double x = a.blockX(mouseX, mouseY);
        double z = a.blockZ(mouseX, mouseY);
        double bpp = 1.0 / a.pixelsPerBlock();
        for (int i = drawn.size() - 1; i >= 0; i--) {
            Prepared p = drawn.get(i);
            boolean hit = switch (p.shape()) {
                case MapShape.Area ignored -> contains(p.xz(), x, z);
                case MapShape.Line line -> near(p.xz(), x, z, (line.widthPixels() / 2.0 + LINE_PICK_SLACK) * bpp);
                case MapShape.Icon icon -> Math.hypot(icon.at().x() - x, icon.at().z() - z)
                        <= icon.sizePixels() / 2.0 * bpp;
            };
            if (hit) {
                return p.shape();
            }
        }
        return null;
    }

    static boolean contains(double[] xz, double x, double z) {
        int n = xz.length / 2;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xz[i * 2];
            double zi = xz[i * 2 + 1];
            double xj = xz[j * 2];
            double zj = xz[j * 2 + 1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean near(double[] xz, double x, double z, double reach) {
        for (int i = 2; i < xz.length; i += 2) {
            double ax = xz[i - 2];
            double az = xz[i - 1];
            double dx = xz[i] - ax;
            double dz = xz[i + 1] - az;
            double length2 = dx * dx + dz * dz;
            double t = length2 < 1.0e-12 ? 0.0 : Math.clamp(((x - ax) * dx + (z - az) * dz) / length2, 0.0, 1.0);
            if (Math.hypot(ax + t * dx - x, az + t * dz - z) <= reach) {
                return true;
            }
        }
        return false;
    }

    /** Ear clipping, either winding; degenerate remainder => fan. */
    static int[] triangulate(double[] xz) {
        int n = xz.length / 2;
        if (n < 3) {
            return new int[0];
        }
        double area2 = 0.0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            area2 += xz[j * 2] * xz[i * 2 + 1] - xz[i * 2] * xz[j * 2 + 1];
        }
        double sign = Math.signum(area2);
        List<Integer> left = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            left.add(i);
        }
        int[] out = new int[(n - 2) * 3];
        int written = 0;
        int guard = 0;
        int at = 0;
        while (left.size() > 3 && guard < left.size()) {
            int m = left.size();
            int a = left.get((at + m - 1) % m);
            int b = left.get(at % m);
            int c = left.get((at + 1) % m);
            if (ear(xz, left, a, b, c, sign)) {
                out[written++] = a;
                out[written++] = b;
                out[written++] = c;
                left.remove(at % m);
                guard = 0;
            } else {
                at++;
                guard++;
            }
            at %= left.size();
        }
        for (int i = 1; i + 1 < left.size(); i++) {
            out[written++] = left.get(0);
            out[written++] = left.get(i);
            out[written++] = left.get(i + 1);
        }
        return out;
    }

    private static boolean ear(double[] xz, List<Integer> left, int a, int b, int c, double sign) {
        double ax = xz[a * 2];
        double az = xz[a * 2 + 1];
        double bx = xz[b * 2];
        double bz = xz[b * 2 + 1];
        double cx = xz[c * 2];
        double cz = xz[c * 2 + 1];
        if (((bx - ax) * (cz - az) - (bz - az) * (cx - ax)) * sign <= 0.0) {
            return false;
        }
        for (int k : left) {
            if (k == a || k == b || k == c) {
                continue;
            }
            double px = xz[k * 2];
            double pz = xz[k * 2 + 1];
            if (side(ax, az, bx, bz, px, pz) * sign >= 0.0 && side(bx, bz, cx, cz, px, pz) * sign >= 0.0
                    && side(cx, cz, ax, az, px, pz) * sign >= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static double side(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    private static int argb(int rgb, float opacity) {
        return Math.round(Math.clamp(opacity, 0.0f, 1.0f) * 255.0f) << 24 | rgb & 0xFFFFFF;
    }
}
