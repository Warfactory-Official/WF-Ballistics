package com.wf.wflib.client.map;

import com.wf.gemrender.ui.Canvas;

/**
 * Pixel-space, not {@code PolygonOverlay} world-space: at the fullscreen zoom floor a 5-block width is a
 * fiftieth of a pixel, vanishes. Widths floor at 1 px.
 */
public final class MapDraw {

    private MapDraw() {
    }

    public static void line(Canvas canvas, double x1, double y1, double x2, double y2, float width, int argb) {
        canvas.line((float) x1, (float) y1, (float) x2, (float) y2, Math.max(1.0f, width), argb);
    }

    public static void polyline(Canvas canvas, double[] xy, int count, float width, int argb) {
        for (int i = 1; i < count; i++) {
            line(canvas, xy[(i - 1) * 2], xy[(i - 1) * 2 + 1], xy[i * 2], xy[i * 2 + 1], width, argb);
        }
    }

    /** Dashed = construction marker, not track. */
    public static void dashed(Canvas canvas, double x1, double y1, double x2, double y2, float width, int argb,
                              double dash) {
        float step = (float) Math.max(2.0, dash);
        canvas.dashed((float) x1, (float) y1, (float) x2, (float) y2, Math.max(1.0f, width), step, step, argb);
    }

    /** Border for contrast against any terrain. */
    public static void handle(Canvas canvas, double x, double y, int half, int fill, int border) {
        int minX = (int) Math.round(x) - half;
        int minY = (int) Math.round(y) - half;
        int maxX = minX + half * 2;
        int maxY = minY + half * 2;
        canvas.fill(minX - 1, minY - 1, maxX + 1, maxY + 1, border);
        canvas.fill(minX, minY, maxX, maxY, fill);
    }
}
