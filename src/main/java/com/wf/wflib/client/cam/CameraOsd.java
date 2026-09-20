package com.wf.wflib.client.cam;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.Locale;

/** The overlay burned into a feed: battery, mode, attitude, link. */
final class CameraOsd {

    /** The virtual space the OSD is laid out in. 16:9, and small enough that vanilla's font reads as a HUD. */
    private static final int OSD_W = 320;
    private static final int OSD_H = 180;

    private static final int GREEN = 0xFF6BFF7D;
    private static final int AMBER = 0xFFFFC24A;
    private static final int RED = 0xFFFF5A3C;
    private static final int DIM = 0xFF3A6B44;

    private CameraOsd() {
    }

    static void draw(CameraTarget target, CameraFeed feed) {
        Minecraft mc = Minecraft.getInstance();
        target.target().bindWrite(true);

        RenderSystem.clear(256, Minecraft.ON_OSX);

        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        float far = ClientHooks.getGuiFarPlane();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0f, OSD_W, OSD_H, 0.0f, 1000.0f, far),
                VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translation(0.0f, 0.0f, 10000.0f - far);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        try {
            paint(g, mc, feed);
            g.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }

    private static void paint(GuiGraphics g, Minecraft mc, CameraFeed feed) {
        CameraMode mode = feed.modeValue();
        int tint = mode == CameraMode.THERMAL ? AMBER : GREEN;

        bracket(g, 6, 6, 1, 1, tint);
        bracket(g, OSD_W - 7, 6, -1, 1, tint);
        bracket(g, 6, OSD_H - 7, 1, -1, tint);
        bracket(g, OSD_W - 7, OSD_H - 7, -1, -1, tint);

        g.drawString(mc.font, String.format(Locale.ROOT, "CAM-%04X  %s",
                feed.feedId() & 0xFFFF, mode.name()), 10, 10, tint, false);

        long tod = Math.floorMod(mc.level == null ? 0L : mc.level.getDayTime(), 24000L);
        int hh = (int) ((tod / 1000L + 6L) % 24L);
        int mm = (int) (tod % 1000L * 60L / 1000L);
        String clock = String.format(Locale.ROOT, "%02d:%02d", hh, mm);
        g.drawString(mc.font, clock, OSD_W - 10 - mc.font.width(clock), 10, tint, false);

        boolean fixed = feed.feedId() < 0;
        if (fixed) {
            g.drawString(mc.font, "MAINS", 10, OSD_H - 24, tint, false);
        } else {
            g.drawString(mc.font, String.format(Locale.ROOT, "AGL %5.0f", feed.altitude()),
                    10, OSD_H - 34, tint, false);
            g.drawString(mc.font, String.format(Locale.ROOT, "SPD %5.1f", feed.speed()),
                    10, OSD_H - 24, tint, false);
        }

        String gimbal = String.format(Locale.ROOT, "%03.0f / %+03.0f",
                (FeedGimbal.yaw(feed) + 360.0f) % 360.0f, FeedGimbal.pitch(feed));
        g.drawString(mc.font, gimbal, OSD_W - 10 - mc.font.width(gimbal), OSD_H - 24, tint, false);

        if (!fixed) {
            battery(g, mc, feed, tint);
        }
        link(g, mc, feed);
        reticle(g, tint);
    }

    /** Battery as a bar and a number. */
    private static void battery(GuiGraphics g, Minecraft mc, CameraFeed feed, int tint) {
        float charge = Math.max(0.0f, Math.min(1.0f, feed.battery()));
        int colour = charge < 0.10f ? RED : charge < 0.25f ? AMBER : tint;
        int x = 10;
        int y = OSD_H - 14;
        int w = 46;
        g.fill(x, y, x + w, y + 6, 0x80000000);
        g.renderOutline(x, y, w, 6, colour);
        g.fill(x + 1, y + 1, x + 1 + (int) ((w - 2) * charge), y + 5, colour);
        g.drawString(mc.font, String.format(Locale.ROOT, "%3.0f%%", charge * 100.0f),
                x + w + 4, y - 1, colour, false);
        if (charge < 0.10f) {
            g.drawString(mc.font, "RTB", x + w + 26, y - 1, RED, false);
        }
    }

    /** Link strength as five bars. */
    private static void link(GuiGraphics g, Minecraft mc, CameraFeed feed) {
        int bars = Math.round(Math.max(0.0f, Math.min(1.0f, feed.link())) * 5.0f);
        int colour = bars <= 1 ? RED : bars <= 2 ? AMBER : GREEN;
        int x = OSD_W - 10 - 5 * 4;
        int y = OSD_H - 40;
        for (int i = 0; i < 5; i++) {
            int h = 2 + i * 2;
            int bx = x + i * 4;
            g.fill(bx, y + 10 - h, bx + 3, y + 10, i < bars ? colour : DIM);
        }
        if (bars <= 1) {
            String warn = "LOW SIGNAL";
            g.drawString(mc.font, warn, (OSD_W - mc.font.width(warn)) / 2, OSD_H / 2 - 30, RED, false);
        }
    }

    /** The centre cross. */
    private static void reticle(GuiGraphics g, int tint) {
        int cx = OSD_W / 2;
        int cy = OSD_H / 2;
        g.fill(cx - 8, cy, cx - 2, cy + 1, tint);
        g.fill(cx + 3, cy, cx + 9, cy + 1, tint);
        g.fill(cx, cy - 8, cx + 1, cy - 2, tint);
        g.fill(cx, cy + 3, cx + 1, cy + 9, tint);
    }

    /** One corner mark, twelve pixels along each axis from {@code (x, y)} in the direction given. */
    private static void bracket(GuiGraphics g, int x, int y, int dx, int dy, int colour) {
        int left = dx > 0 ? x : x - 11;
        int top = dy > 0 ? y : y - 11;
        g.fill(left, y, left + 12, y + 1, colour);
        g.fill(x, top, x + 1, top + 12, colour);
    }
}
