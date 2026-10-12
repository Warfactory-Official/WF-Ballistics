package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Ring section in box-local coordinates, degrees clockwise from +x; {@code thickness <= 0} => filled
 * sector (disc at sweep >= 360). Size 0.
 */
public record Arc(String cx, String cy, String radius, String thickness, String start, String sweep, String color)
        implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp px = binder.num(cx);
        NumProp py = binder.num(cy);
        NumProp r = binder.num(radius);
        NumProp t = binder.num(thickness);
        NumProp from = binder.num(start);
        NumProp span = binder.num(sweep);
        ColorProp colour = binder.color(color);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                int argb = colour.get(ev);
                float sweepRad = (float) Math.toRadians(span.get(ev));
                if ((argb >>> 24) == 0 || !(sweepRad > 0)) {
                    return;
                }
                Canvas canvas = ev.canvas;
                float ox = x + px.getFloat(ev);
                float oy = y + py.getFloat(ev);
                float thick = t.getFloat(ev);
                float startRad = (float) Math.toRadians(from.get(ev));
                if (thick > 0) {
                    canvas.arc(ox, oy, r.getFloat(ev), thick, startRad, sweepRad, argb);
                } else {
                    canvas.sector(ox, oy, r.getFloat(ev), startRad, sweepRad, argb);
                }
            }
        };
    }
}
