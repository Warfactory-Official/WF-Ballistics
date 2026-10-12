package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.List;

/**
 * Dotted dial centred on the box origin; degrees counter-clockwise from +x (+90 = up). Dots
 * ({@code dot} square, rounded to whole units) every {@code minorStep} on {@code radius} over
 * [{@code from}, {@code to}]; inward ticks {@code majorLength} long every {@code majorStep},
 * multiples of {@code accentStep} in {@code accentColor}; band [{@code bandFrom}, {@code bandTo}]
 * (clipped to the range, hidden when empty) as {@code bandSize} dots at {@code radius + bandOffset};
 * needles as dotted rays at their clamped value. Size 0.
 */
public record Dial(float radius, float from, float to, float dot, float minorStep, String minorColor, float majorStep,
                   float majorLength, String majorColor, float accentStep, String accentColor, String bandFrom,
                   String bandTo, float bandOffset, float bandSize, String bandColor, List<Needle> needles)
        implements WidgetDef {
    /** Ray from {@code inner} to {@code outer} radius at {@code value} degrees. */
    public record Needle(String value, float inner, float outer, float size, String color) {
    }

    private static final float DOT_SPACING = 0.7f;

    @Override
    public Widget create(Binder binder) {
        ColorProp minor = binder.color(minorColor);
        ColorProp major = binder.color(majorColor);
        ColorProp accent = binder.color(accentColor);
        NumProp low = binder.num(bandFrom);
        NumProp high = binder.num(bandTo);
        ColorProp band = binder.color(bandColor);
        NumProp[] values = new NumProp[needles.size()];
        ColorProp[] colours = new ColorProp[needles.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = binder.num(needles.get(i).value());
            colours[i] = binder.color(needles.get(i).color());
        }
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                Canvas canvas = ev.canvas;
                int minorArgb = minor.get(ev);
                if (minorStep > 0 && (minorArgb >>> 24) != 0) {
                    for (double mark = Math.ceil(from / minorStep) * minorStep; mark <= to; mark += minorStep) {
                        dot(canvas, x, y, mark, radius, dot, minorArgb);
                    }
                }
                if (majorStep > 0) {
                    int majorArgb = major.get(ev);
                    int accentArgb = accent.get(ev);
                    for (double mark = Math.ceil(from / majorStep) * majorStep; mark <= to; mark += majorStep) {
                        boolean accented = accentStep > 0 && Math.abs(Math.IEEEremainder(mark, accentStep)) < 1e-6;
                        ray(canvas, x, y, mark, radius - majorLength, radius, dot, accented ? accentArgb : majorArgb);
                    }
                }
                double bandLow = Math.max(low.get(ev), from);
                double bandHigh = Math.min(high.get(ev), to);
                int bandArgb = band.get(ev);
                if (bandHigh > bandLow && (bandArgb >>> 24) != 0) {
                    double r = radius + bandOffset;
                    int steps = Math.max(1, (int) Math.ceil(Math.toRadians(bandHigh - bandLow) * r / (bandSize * DOT_SPACING)));
                    for (int i = 0; i <= steps; i++) {
                        dot(canvas, x, y, bandLow + (bandHigh - bandLow) * i / steps, r, bandSize, bandArgb);
                    }
                }
                for (int i = 0; i < values.length; i++) {
                    double value = values[i].get(ev);
                    if (Double.isNaN(value)) {
                        continue;
                    }
                    Needle needle = needles.get(i);
                    ray(canvas, x, y, Math.max(from, Math.min(to, value)), needle.inner(), needle.outer(), needle.size(),
                            colours[i].get(ev));
                }
            }
        };
    }

    private static void ray(Canvas canvas, float cx, float cy, double degrees, double inner, double outer, float size,
                            int argb) {
        if ((argb >>> 24) == 0) {
            return;
        }
        int steps = Math.max(1, (int) Math.ceil((outer - inner) / (size * DOT_SPACING)));
        for (int i = 0; i <= steps; i++) {
            dot(canvas, cx, cy, degrees, inner + (outer - inner) * i / steps, size, argb);
        }
    }

    private static void dot(Canvas canvas, float cx, float cy, double degrees, double radius, float size, int argb) {
        double radians = Math.toRadians(degrees);
        float px = Math.round(cx + Math.cos(radians) * radius);
        float py = Math.round(cy - Math.sin(radians) * radius);
        canvas.fill(px, py, px + size, py + size, argb);
    }
}
