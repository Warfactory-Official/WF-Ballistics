package com.wf.wflib.client.journeymap.rail;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Screen-space drawing for the map editor.
 *
 * <p>Everything here is in pixels, which is the whole point. A {@code PolygonOverlay} is in world
 * coordinates, so its width shrinks with zoom until it vanishes: 5 blocks is a fiftieth of a pixel at
 * the fullscreen zoom floor. Drawing in JourneyMap's render event instead means a line is as many
 * pixels wide as we say it is, at any zoom.</p>
 *
 * <p>{@link GuiGraphics#fill} is axis-aligned, so a sloped line is a rectangle drawn under a rotation
 * of the pose. That costs a push and a pop per segment, which at a few hundred segments is nothing
 * next to the tile rendering underneath it.</p>
 */
public final class MapDraw {

    private MapDraw() {
    }

    /** A line of constant pixel width between two screen points. */
    public static void line(GuiGraphics graphics, double x1, double y1, double x2, double y2,
                            float width, int argb) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0e-6) {
            return;
        }
        float half = Math.max(0.5f, width / 2.0f);
        graphics.pose().pushPose();
        graphics.pose().translate((float) x1, (float) y1, 0.0f);
        graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        // Drawn from 0 to length along local X, centred on it, so joints between segments overlap
        // rather than leaving a notch on the outside of a turn.
        graphics.fill(0, (int) -Math.ceil(half), (int) Math.ceil(length), (int) Math.ceil(half), argb);
        graphics.pose().popPose();
    }

    /** A polyline through screen points held as flat x,y pairs. */
    public static void polyline(GuiGraphics graphics, double[] xy, int count, float width, int argb) {
        for (int i = 1; i < count; i++) {
            line(graphics, xy[(i - 1) * 2], xy[(i - 1) * 2 + 1], xy[i * 2], xy[i * 2 + 1], width, argb);
        }
    }

    /** A dashed line, for things that are construction rather than track. */
    public static void dashed(GuiGraphics graphics, double x1, double y1, double x2, double y2,
                             float width, int argb, double dash) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0e-6) {
            return;
        }
        double step = Math.max(2.0, dash);
        for (double at = 0.0; at < length; at += step * 2.0) {
            double end = Math.min(at + step, length);
            line(graphics, x1 + dx * (at / length), y1 + dy * (at / length),
                    x1 + dx * (end / length), y1 + dy * (end / length), width, argb);
        }
    }

    /** A square handle centred on a screen point, with a border so it reads against any terrain. */
    public static void handle(GuiGraphics graphics, double x, double y, int half, int fill, int border) {
        int minX = (int) Math.round(x) - half;
        int minY = (int) Math.round(y) - half;
        int maxX = minX + half * 2;
        int maxY = minY + half * 2;
        graphics.fill(minX - 1, minY - 1, maxX + 1, maxY + 1, border);
        graphics.fill(minX, minY, maxX, maxY, fill);
    }
}
