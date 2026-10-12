package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Progress bar. {@code value} in [0,1] filled from the start edge of {@code direction}; bipolar =>
 * [-1,1] filled from the middle. {@code marker} (NaN = none) draws a 1-unit tick at that value.
 * {@code divisions > 1} draws {@code tickColor} scale marks between equal divisions (bipolar: per
 * half). {@code inset} shrinks the fill across the bar; the marker sticks out {@code markerOverhang}
 * either side.
 */
public record Bar(float w, float h, String value, String fill, String back, String border, Direction direction,
                  boolean bipolar, String marker, String markerColor, int divisions, String tickColor, float inset,
                  float markerOverhang) implements WidgetDef {
    public Bar(float w, float h, String value, String fill, String back, String border, Direction direction,
               boolean bipolar, String marker, String markerColor) {
        this(w, h, value, fill, back, border, direction, bipolar, marker, markerColor, 0, "0", 0, 0);
    }

    public enum Direction {
        RIGHT,
        LEFT,
        UP,
        DOWN
    }

    @Override
    public Widget create(Binder binder) {
        NumProp v = binder.num(value);
        ColorProp f = binder.color(fill);
        ColorProp b = binder.color(back);
        ColorProp e = binder.color(border);
        NumProp m = binder.num(marker);
        ColorProp mc = binder.color(markerColor);
        ColorProp tc = binder.color(tickColor);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = w;
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas canvas = ev.canvas;
                int backArgb = b.get(ev);
                if ((backArgb >>> 24) != 0) {
                    canvas.fill(x, y, x + w, y + h, backArgb);
                }
                int tickArgb = tc.get(ev);
                if (divisions > 1 && (tickArgb >>> 24) != 0) {
                    boolean horizontal = direction == Direction.RIGHT || direction == Direction.LEFT;
                    float half = 0.5f / (horizontal ? w : h);
                    for (int d = 1; d < divisions; d++) {
                        float t = (float) d / divisions;
                        if (bipolar) {
                            span(canvas, x, y, 0.5f + t * 0.5f - half, 0.5f + t * 0.5f + half, tickArgb, 0);
                            span(canvas, x, y, 0.5f - t * 0.5f - half, 0.5f - t * 0.5f + half, tickArgb, 0);
                        } else {
                            span(canvas, x, y, t - half, t + half, tickArgb, 0);
                        }
                    }
                }
                double raw = v.get(ev);
                float t = Double.isNaN(raw) ? 0 : (float) Math.max(bipolar ? -1 : 0, Math.min(1, raw));
                float from = bipolar ? 0.5f : 0;
                float to = bipolar ? 0.5f + t * 0.5f : t;
                span(canvas, x, y, Math.min(from, to), Math.max(from, to), f.get(ev), inset);
                double mark = m.get(ev);
                if (!Double.isNaN(mark)) {
                    float mt = (float) Math.max(bipolar ? -1 : 0, Math.min(1, mark));
                    float at = bipolar ? 0.5f + mt * 0.5f : mt;
                    boolean horizontal = direction == Direction.RIGHT || direction == Direction.LEFT;
                    float len = horizontal ? w : h;
                    float half = 0.5f / len;
                    span(canvas, x, y, at - half, at + half, mc.get(ev), -markerOverhang);
                }
                int edge = e.get(ev);
                if ((edge >>> 24) != 0) {
                    canvas.frame(x, y, x + w, y + h, 1, edge);
                }
            }

            /** {@code cross} > 0 shrinks across the bar, < 0 grows. */
            private void span(Canvas canvas, float x, float y, float a, float c, int argb, float cross) {
                if ((argb >>> 24) == 0 || c <= a) {
                    return;
                }
                switch (direction) {
                    case RIGHT -> canvas.fill(x + a * w, y + cross, x + c * w, y + h - cross, argb);
                    case LEFT -> canvas.fill(x + (1 - c) * w, y + cross, x + (1 - a) * w, y + h - cross, argb);
                    case UP -> canvas.fill(x + cross, y + (1 - c) * h, x + w - cross, y + (1 - a) * h, argb);
                    case DOWN -> canvas.fill(x + cross, y + a * h, x + w - cross, y + c * h, argb);
                }
            }
        };
    }
}
