package com.wf.wflib.client.scope;

import com.mojang.blaze3d.platform.NativeImage;
import com.wf.wflib.WFLib;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.scope.ScopeFrame;
import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.recon.track.TrackQuality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** A plan-position scope, drawn on the CPU into a {@link NativeImage}. */
public final class ScopeRaster implements AutoCloseable {

    /** Texture edge. A scope is round; the corners are background. */
    public static final int SIZE = 256;
    private static final int CENTRE = SIZE / 2;
    private static final int RADIUS = CENTRE - 6;
    /** Range rings, as fractions of full scale. */
    private static final double[] RINGS = {0.25, 0.5, 0.75, 1.0};

    // Phosphor. ARGB, and NativeImage is little-endian ABGR in memory, so everything goes through argb().
    private static final int BACKGROUND = 0xFF04140A;
    private static final int GRID = 0xFF0E5A28;
    private static final int GRID_BRIGHT = 0xFF14883C;
    /** The graticule behind everything. Dark enough that a faint blip drawn over it still reads as a blip. */
    private static final int GRID_FAINT = 0xFF0A3A19;
    private static final int BLIP_HOSTILE = 0xFFFF5A3C;
    private static final int BLIP_FRIENDLY = 0xFF46B4FF;
    private static final int BLIP_UNKNOWN = 0xFF6BFF7D;
    /** Logged seismic events. */
    private static final int EVENT = 0xFFFFA640;
    /** Seconds over which an event fades to its floor. */
    private static final float EVENT_FADE_SECONDS = 300.0f;

    private final long netId;
    private final NativeImage image;
    private final DynamicTexture texture;
    private final ResourceLocation location;
    private long drawnAt = Long.MIN_VALUE;
    private boolean drawnEmpty;

    ScopeRaster(long netId) {
        this.netId = netId;
        this.image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        this.texture = new DynamicTexture(this.image);
        this.location = Minecraft.getInstance().getTextureManager()
                .register("wflib_scope_" + Long.toHexString(netId), this.texture);
    }

    public ResourceLocation texture() {
        return location;
    }

    /** Redraw only if this frame is newer than what is on the texture. */
    void ensureDrawn(ScopeFrame frame) {
        boolean empty = frame.blips().isEmpty() && frame.events().isEmpty();
        if (frame.gameTime() == drawnAt && !(drawnEmpty && !empty)) {
            return;
        }
        drawnAt = frame.gameTime();
        drawnEmpty = empty;
        draw(frame);
        texture.upload();
    }

    private void draw(ScopeFrame frame) {
        background();
        rings();
        double scale = RADIUS / Math.max(1.0, frame.range());
        for (ScopeFrame.Event event : frame.events()) {
            drawEvent(frame, event, scale);
        }
        for (ScopeFrame.Blip blip : frame.blips()) {
            double dx = (blip.x() - frame.originX()) * scale;
            double dz = (blip.z() - frame.originZ()) * scale;
            int px = CENTRE + (int) Math.round(dx);
            int py = CENTRE + (int) Math.round(dz);
            if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) {
                continue;
            }
            drawBlip(px, py, blip, scale);
        }
        crosshair();
    }

    private void background() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setPixelRGBA(x, y, argb(BACKGROUND));
            }
        }
    }

    private void rings() {
        for (int i = -RADIUS; i <= RADIUS; i += 2) {
            plot(CENTRE + i, CENTRE, GRID_FAINT);
            plot(CENTRE, CENTRE + i, GRID_FAINT);
        }
        for (double fraction : RINGS) {
            int r = (int) Math.round(RADIUS * fraction);
            circleAt(CENTRE, CENTRE, r, fraction == 1.0 ? GRID_BRIGHT : GRID);
            for (int t = -2; t <= 2; t++) {
                plot(CENTRE + r, CENTRE + t, GRID_BRIGHT);
                plot(CENTRE - r, CENTRE + t, GRID_BRIGHT);
                plot(CENTRE + t, CENTRE + r, GRID_BRIGHT);
                plot(CENTRE + t, CENTRE - r, GRID_BRIGHT);
            }
        }
    }

    /**
     * Midpoint circle, eight-way symmetric, blended so a translucent colour draws as one.
     */
    private void circleAt(int cx, int cy, int r, int colour) {
        circleAt(cx, cy, r, colour, 1);
    }

    /**
     * @param step draw one point in {@code step}. Two gives a dashed circle, which is how an event's error
     *      boundary is drawn: it is a statement about where something might have been rather than a
     *      thing that is there, and a solid ring the width of the scope reads as the second.
     */
    private void circleAt(int cx, int cy, int r, int colour, int step) {
        int x = r;
        int y = 0;
        int err = 1 - r;
        int n = 0;
        while (x >= y) {
            if (n++ % step == 0) {
                blend(cx + x, cy + y, colour);
                blend(cx + y, cy + x, colour);
                blend(cx - y, cy + x, colour);
                blend(cx - x, cy + y, colour);
                blend(cx - x, cy - y, colour);
                blend(cx - y, cy - x, colour);
                blend(cx + y, cy - x, colour);
                blend(cx + x, cy - y, colour);
            }
            y++;
            if (err < 0) {
                err += 2 * y + 1;
            } else {
                x--;
                err += 2 * (y - x) + 1;
            }
        }
    }

    /**
     * A contact, drawn as what it actually is: a disc the size of its own error radius, brightness from quality and
     * staleness, colour from IFF.
     */
    private void drawBlip(int px, int py, ScopeFrame.Blip blip, double scale) {
        int colour = switch (blip.iffValue()) {
            case FRIENDLY -> BLIP_FRIENDLY;
            case HOSTILE -> BLIP_HOSTILE;
            case UNKNOWN -> blip.kindValue() == ContactClass.UNKNOWN ? BLIP_UNKNOWN : BLIP_HOSTILE;
        };
        float freshness = 1.0f - Math.min(1.0f, blip.age() / 100.0f);
        float weight = blip.qualityValue() == TrackQuality.FIRM ? 1.0f
                : blip.qualityValue() == TrackQuality.CONFIRMED ? 0.75f : 0.45f;
        int alpha = (int) (255 * Math.max(0.15f, freshness * weight));
        int shaded = (alpha << 24) | (colour & 0x00FFFFFF);

        int r = (int) Math.max(1.0, Math.round(blip.error() * scale));
        r = Math.min(r, 24);
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                if (x * x + y * y > r * r) {
                    continue;
                }
                blend(px + x, py + y, shaded);
            }
        }
        if (r > 2) {
            circleAt(px, py, r, (Math.min(255, alpha + 110) << 24) | (colour & 0x00FFFFFF));
        }
        // A merged cell says so: a bright core scaled by how many the estimator thinks are in there.
        if (blip.count() > 1) {
            int core = Math.min(r, 1 + (int) Math.round(Math.log(blip.count()) / Math.log(2.0)));
            for (int y = -core; y <= core; y++) {
                for (int x = -core; x <= core; x++) {
                    if (x * x + y * y <= core * core) {
                        plot(px + x, py + y, 0xFFFFFFFF);
                    }
                }
            }
        }
    }

    /** A logged explosion: a hollow ring at the fix's own error, and a cross where the network thinks it was. */
    private void drawEvent(ScopeFrame frame, ScopeFrame.Event event, double scale) {
        double dx = (event.x() - frame.originX()) * scale;
        double dz = (event.z() - frame.originZ()) * scale;
        if (Math.abs(dx) > SIZE * 4 || Math.abs(dz) > SIZE * 4) {
            return;
        }
        int px = CENTRE + (int) Math.round(dx);
        int py = CENTRE + (int) Math.round(dz);

        float fresh = 1.0f - Math.min(1.0f, event.ageSeconds() / EVENT_FADE_SECONDS);
        int alpha = (int) (255 * (0.25f + 0.75f * fresh));
        int shaded = (alpha << 24) | (EVENT & 0x00FFFFFF);

        int r = (int) Math.max(2.0, Math.round(event.error() * scale));
        circleAt(px, py, Math.min(r, SIZE * 2), shaded, 2);
        for (int i = -3; i <= 3; i++) {
            blend(px + i, py + i, shaded);
            blend(px + i, py - i, shaded);
        }
    }

    private void crosshair() {
        for (int i = -3; i <= 3; i++) {
            plot(CENTRE + i, CENTRE, GRID_BRIGHT);
            plot(CENTRE, CENTRE + i, GRID_BRIGHT);
        }
    }

    private void plot(int x, int y, int colour) {
        if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
            image.setPixelRGBA(x, y, argb(colour));
        }
    }

    /**
     * Source-over, so overlapping error discs accumulate into a brighter smear instead of the last one drawn
     * winning.
     */
    private void blend(int x, int y, int colour) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
            return;
        }
        int dst = image.getPixelRGBA(x, y);
        int sa = (colour >>> 24) & 0xFF;
        int sr = (colour >>> 16) & 0xFF;
        int sg = (colour >>> 8) & 0xFF;
        int sb = colour & 0xFF;
        // NativeImage stores ABGR; unpack accordingly.
        int dr = dst & 0xFF;
        int dg = (dst >>> 8) & 0xFF;
        int db = (dst >>> 16) & 0xFF;
        int r = (sr * sa + dr * (255 - sa)) / 255;
        int g = (sg * sa + dg * (255 - sa)) / 255;
        int b = (sb * sa + db * (255 - sa)) / 255;
        image.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
    }

    /** ARGB to the ABGR word {@link NativeImage#setPixelRGBA} actually stores. */
    private static int argb(int colour) {
        int a = (colour >>> 24) & 0xFF;
        int r = (colour >>> 16) & 0xFF;
        int g = (colour >>> 8) & 0xFF;
        int b = colour & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(location);
        texture.close();
    }

    /**
     * @return which network this raster belongs to, for diagnostics.
     */
    public long netId() {
        return netId;
    }

    /**
     * @return how a blip is named on the readout beside the scope.
     */
    public static String label(ScopeFrame.Blip blip) {
        String name = blip.kindValue().name();
        return blip.iffValue() == IffState.FRIENDLY ? name + " (F)" : name;
    }
}
