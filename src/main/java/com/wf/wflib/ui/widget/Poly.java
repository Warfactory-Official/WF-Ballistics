package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/** Filled convex polygon, box-local constant points (x0, y0, x1, y1, ...). Size 0. */
public record Poly(float[] points, String color) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        ColorProp colour = binder.color(color);
        float[] local = points.clone();
        float[] moved = new float[local.length];
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                int argb = colour.get(ev);
                if ((argb >>> 24) == 0) {
                    return;
                }
                for (int i = 0; i < local.length; i += 2) {
                    moved[i] = local[i] + x;
                    moved[i + 1] = local[i + 1] + y;
                }
                ev.canvas.fan(moved, local.length / 2, argb);
            }
        };
    }
}
