package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Pitch ladder centred on the box origin: a dashed rung every {@code step} degrees, {@code ppd}
 * canvas units per degree, higher pitch upward, rungs within ±{@code range} degrees of
 * {@code pitch}. Each half-rung = {@code dashes} dashes of {@code dash} with {@code dashGap} between,
 * starting {@code centreGap} from the middle. Labels (pitch in whole degrees) are centred at
 * {@code labelOffset}. Wrap in a {@link Transform} for roll and a clipping {@link Sized} for the window.
 */
public record Ladder(String pitch, float ppd, float step, float range, int dashes, float dash, float dashGap,
                     float centreGap, float thickness, float labelOffset, String color) implements WidgetDef {
    private static final int LABEL_CAPACITY = 32;

    @Override
    public Widget create(Binder binder) {
        NumProp p = binder.num(pitch);
        ColorProp colour = binder.color(color);
        return new Widget() {
            private final TextBuffer text = new TextBuffer(LABEL_CAPACITY);

            @Override
            public void measure(UiEval ev) {
                width = 0;
                height = 0;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                double value = p.get(ev);
                int argb = colour.get(ev);
                if (Double.isNaN(value) || (argb >>> 24) == 0 || step <= 0) {
                    return;
                }
                Canvas canvas = ev.canvas;
                long first = (long) Math.ceil((value - range) / step);
                long last = (long) Math.floor((value + range) / step);
                for (long i = first; i <= last; i++) {
                    double mark = i * (double) step;
                    float ry = y - (float) ((mark - value) * ppd);
                    for (int d = 0; d < dashes; d++) {
                        float a = centreGap + d * (dash + dashGap);
                        canvas.fill(x + a, ry, x + a + dash, ry + thickness, argb);
                        canvas.fill(x - a - dash, ry, x - a, ry + thickness, argb);
                    }
                    if (labelOffset != 0) {
                        text.clear().append(Math.round(mark));
                        canvas.text(text, x + labelOffset - canvas.width(text) * 0.5f, ry - 3, argb, true);
                    }
                }
            }
        };
    }
}
