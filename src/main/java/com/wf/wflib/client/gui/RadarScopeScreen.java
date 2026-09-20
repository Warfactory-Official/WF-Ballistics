package com.wf.wflib.client.gui;

import com.wf.wflib.client.scope.ScopeCache;
import com.wf.wflib.client.scope.ScopeRaster;
import com.wf.wflib.recon.scope.ScopeFrame;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The scope, as a screen. */
public class RadarScopeScreen extends Screen {

    /** Wide enough for the widest row: a friendly contact's label and a count, plus five columns after it. */
    private static final int PANEL = 246;
    private static final int PAD = 8;
    private static final int ROW = 11;
    /** Smallest the scope is allowed to get before the rings stop being readable at all. */
    private static final int SCOPE_MIN = 96;

    private static final int COL_TYPE = 0;
    private static final int COL_TYPE_MAX = 106;
    private static final int COL_RNG = 114;
    private static final int COL_ALT = 154;
    private static final int COL_TRK = 162;
    private static final int COL_SRC = 186;
    private static final int COL_PWR = 244;

    /** Column headings and the rule under them: dim phosphor, so the table's frame never outshines its rows. */
    private static final int HEADING = 0xFF3E9E5C;
    private static final int RULE = 0xFF1A5B30;
    private static final int BODY = 0xFFAAAAAA;
    private static final int FAINT = 0xFF555555;
    /** Provenance, when only one band holds the contact. Deliberately quiet; corroboration is the loud case. */
    private static final int SOURCE_ALONE = 0xFF4A6A55;
    private static final int SOURCE_CORROBORATED = 0xFFFFD24A;
    private static final int CARDINAL = 0xFF6BFF7D;
    private static final int RING_LABEL = 0xFF2E7D32;
    /** Seismic amber, matching the raster and the map overlay. */
    private static final int EVENT = 0xFFFFA640;
    /** Seconds over which an event's row fades, the same curve the raster fades its ring on. */
    private static final float EVENT_FADE_SECONDS = 300.0f;

    private final long netId;
    private final double originX;
    private final double originZ;

    private int left;
    private int top;
    /** Edge of the scope on screen. */
    private int scope;
    private int boxW;
    private int boxH;

    public RadarScopeScreen(long netId, double originX, double originZ) {
        super(Component.literal("Sensor scope"));
        this.netId = netId;
        this.originX = originX;
        this.originZ = originZ;
    }

    @Override
    protected void init() {
        int room = Math.min(this.height - PAD * 2, this.width - PANEL - PAD * 3);
        this.scope = Mth.clamp(Math.min(ScopeRaster.SIZE, room), SCOPE_MIN, ScopeRaster.SIZE);
        this.boxW = this.scope + PANEL + PAD * 3;
        this.boxH = Math.max(this.scope + PAD * 2, ROW * 20);
        this.left = (this.width - this.boxW) / 2;
        this.top = (this.height - this.boxH) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.fill(left, top, left + boxW, top + boxH, 0xE0060A08);
        g.renderOutline(left, top, boxW, boxH, 0xFF14883C);

        ScopeFrame frame = ScopeCache.frame(netId);
        ResourceLocation texture = ScopeCache.texture(netId);
        int sx = left + PAD;
        int sy = top + PAD;
        if (texture != null) {
            g.blit(texture, sx, sy, scope, scope, 0.0f, 0.0f,
                    ScopeRaster.SIZE, ScopeRaster.SIZE, ScopeRaster.SIZE, ScopeRaster.SIZE);
            this.graticule(g, sx, sy, frame);
        } else {
            g.fill(sx, sy, sx + scope, sy + scope, 0xFF04140A);
            g.drawCenteredString(this.font, Component.literal("no signal").withStyle(ChatFormatting.DARK_GREEN),
                    sx + scope / 2, sy + scope / 2 - 4, 0xFF2E7D32);
        }

        this.panel(g, left + PAD * 2 + scope, top + PAD, frame);
    }

    /** The labels the raster cannot draw. */
    private void graticule(GuiGraphics g, int sx, int sy, @Nullable ScopeFrame frame) {
        int centre = scope / 2;
        g.drawString(this.font, "N", sx + centre - 2, sy + 2, CARDINAL, false);
        g.drawString(this.font, "S", sx + centre - 2, sy + scope - 10, CARDINAL, false);
        g.drawString(this.font, "E", sx + scope - 8, sy + centre - 4, CARDINAL, false);
        g.drawString(this.font, "W", sx + 2, sy + centre - 4, CARDINAL, false);
        if (frame == null) {
            return;
        }
        double radius = centre * (double) (ScopeRaster.SIZE / 2 - 6) / (ScopeRaster.SIZE / 2.0);
        for (double fraction : new double[]{0.25, 0.5, 0.75}) {
            String text = String.format(Locale.ROOT, "%.0f", frame.range() * fraction);
            int rx = sx + centre + (int) Math.round(radius * fraction);
            g.drawString(this.font, text, rx - this.font.width(text) - 1, sy + centre + 2, RING_LABEL, false);
        }
    }

    private void panel(GuiGraphics g, int x, int y, @Nullable ScopeFrame frame) {
        int row = y;
        g.drawString(this.font, "NET " + Long.toHexString(netId), x, row, 0xFFFFAA00, false);
        row += ROW;
        if (frame == null) {
            g.drawString(this.font, "awaiting frame", x, row, FAINT, false);
            return;
        }
        g.drawString(this.font, String.format(Locale.ROOT, "%.0f blk   %d contact(s)   %d event(s)",
                frame.range(), frame.blips().size(), frame.events().size()), x, row, BODY, false);
        row += ROW + 3;

        int bottom = top + boxH - PAD;
        int eventRows = frame.events().isEmpty() ? 0 : frame.events().size() + 2;
        int room = Math.max(1, (bottom - row) / ROW - eventRows - 3);

        row = this.contacts(g, x, row, frame, room);
        if (!frame.events().isEmpty()) {
            this.events(g, x, row + 4, frame);
        }
    }

    /**
     * @return the row after the last one drawn.
     */
    private int contacts(GuiGraphics g, int x, int y, ScopeFrame frame, int room) {
        this.heading(g, x, y, "TYPE", "RNG", "ALT", "TRK", "SRC", null);
        int row = y + ROW;

        // Nearest first: on a warning set the thing that matters is what is closest, not what was found first.
        List<ScopeFrame.Blip> sorted = new ArrayList<>(frame.blips());
        sorted.sort(Comparator.comparingDouble(b -> range(b, frame)));
        if (sorted.isEmpty()) {
            g.drawString(this.font, "clear", x, row, SOURCE_ALONE, false);
            return row + ROW;
        }

        for (int i = 0; i < sorted.size(); i++) {
            if (i >= room) {
                g.drawString(this.font, "+" + (sorted.size() - room) + " more", x, row, FAINT, false);
                row += ROW;
                break;
            }
            ScopeFrame.Blip blip = sorted.get(i);
            int colour = switch (blip.iffValue()) {
                case FRIENDLY -> 0xFF46B4FF;
                case HOSTILE -> 0xFFFF5A3C;
                case UNKNOWN -> 0xFF6BFF7D;
            };
            String label = ScopeRaster.label(blip) + (blip.count() > 1 ? " x" + blip.count() : "");
            g.drawString(this.font, this.font.plainSubstrByWidth(label, COL_TYPE_MAX),
                    x + COL_TYPE, row, colour, false);
            this.right(g, String.format(Locale.ROOT, "%.0fm", range(blip, frame)), x + COL_RNG, row, colour);
            this.right(g, String.format(Locale.ROOT, "%+.0fm", blip.y() - frame.originY()),
                    x + COL_ALT, row, colour);
            g.drawString(this.font, mark(blip), x + COL_TRK, row, colour, false);
            g.drawString(this.font, blip.bandTag() + (blip.hops() > 0 ? "+" + blip.hops() : ""),
                    x + COL_SRC, row,
                    blip.corroborated() ? SOURCE_CORROBORATED : SOURCE_ALONE, false);
            row += ROW;
        }
        return row;
    }

    /** The seismic log: what the network has heard go off, newest first. */
    private void events(GuiGraphics g, int x, int y, ScopeFrame frame) {
        g.drawString(this.font, "SEISMIC LOG", x, y, EVENT, false);
        this.heading(g, x, y + ROW, "AGE", "RNG", "ERR", "STN", "BRG", "PWR");
        int row = y + ROW * 2;
        for (ScopeFrame.Event event : frame.events()) {
            double dx = event.x() - frame.originX();
            double dy = event.y() - frame.originY();
            double dz = event.z() - frame.originZ();
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float fresh = 1.0f - Math.min(1.0f, event.ageSeconds() / EVENT_FADE_SECONDS);
            int colour = tint(EVENT, 0.4f + 0.6f * fresh);

            g.drawString(this.font, age(event.ageSeconds()), x + COL_TYPE, row, colour, false);
            this.right(g, String.format(Locale.ROOT, "%.0fm", range), x + COL_RNG, row, colour);
            this.right(g, String.format(Locale.ROOT, "%.0f", event.error()), x + COL_ALT, row, colour);
            g.drawString(this.font, bars(event.stations()), x + COL_TRK, row, colour, false);
            g.drawString(this.font, event.hasBearing(frame.originX(), frame.originY(), frame.originZ())
                            ? String.format(Locale.ROOT, "%03.0f°", bearing(dx, dz))
                            : "---",
                    x + COL_SRC, row, colour, false);
            this.right(g, String.format(Locale.ROOT, "%.0f", event.power()), x + COL_PWR - 8, row, colour);
            if (event.subsurface()) {
                g.drawString(this.font, "u", x + COL_PWR - 5, row, colour, false);
            }
            row += ROW;
        }
    }

    private void heading(GuiGraphics g, int x, int y, String type, String rng, String alt,
                         String trk, String src, @Nullable String pwr) {
        g.drawString(this.font, type, x + COL_TYPE, y, HEADING, false);
        this.right(g, rng, x + COL_RNG, y, HEADING);
        this.right(g, alt, x + COL_ALT, y, HEADING);
        g.drawString(this.font, trk, x + COL_TRK, y, HEADING, false);
        g.drawString(this.font, src, x + COL_SRC, y, HEADING, false);
        if (pwr != null) {
            this.right(g, pwr, x + COL_PWR - 8, y, HEADING);
        }
        g.fill(x, y + ROW - 3, x + PANEL, y + ROW - 2, RULE);
    }

    /**
     * Draw right-aligned to {@code rightX}. The whole reason the columns line up.
     */
    private void right(GuiGraphics g, String text, int rightX, int y, int colour) {
        g.drawString(this.font, text, rightX - this.font.width(text), y, colour, false);
    }

    /** The one-character quality mark. */
    private static String mark(ScopeFrame.Blip blip) {
        return switch (blip.qualityValue()) {
            case FIRM -> "###";
            case CONFIRMED -> "##";
            case TENTATIVE -> "#";
        };
    }

    /**
     * @return the same three-glyph scale for how many stations contributed to a fix.
     */
    private static String bars(int stations) {
        return stations >= 3 ? "###" : stations == 2 ? "##" : "#";
    }

    /**
     * @return compass bearing in degrees, zero north and rising clockwise: the convention the game's own
     *      readouts use, so it can be walked without conversion.
     */
    private static double bearing(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        return degrees < 0.0 ? degrees + 360.0 : degrees;
    }

    /**
     * @return an age a person reads at a glance. Seconds up to a minute, then minutes, then hours: the log
     *      spans however long the hub has been running, and "4380s" is a number nobody converts in their head.
     */
    private static String age(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        return (seconds / 3600) + "h";
    }

    /**
     * @return the colour scaled toward black, so an old event dims without shifting hue into a colour that
     *      means something else.
     */
    private static int tint(int colour, float factor) {
        int r = (int) (((colour >>> 16) & 0xFF) * factor);
        int g = (int) (((colour >>> 8) & 0xFF) * factor);
        int b = (int) ((colour & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double range(ScopeFrame.Blip blip, ScopeFrame frame) {
        double dx = blip.x() - frame.originX();
        double dz = blip.z() - frame.originZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * @return where this screen's owner sits, so a caller can centre something on it later. Kept because the
     *      origin is what makes two screens on one net agree about which way is north.
     */
    public double originX() {
        return Mth.floor(originX);
    }

    public double originZ() {
        return Mth.floor(originZ);
    }
}
