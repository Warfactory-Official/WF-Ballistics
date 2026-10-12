package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/** Filled (stroke 0) or outlined rectangle, optional corner radius. */
public record Rect(String w, String h, String color, float radius, float stroke) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        ColorProp colour = binder.color(color);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = Math.max(0, ph.getFloat(ev));
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                int argb = colour.get(ev);
                if ((argb >>> 24) == 0 || width == 0 || height == 0) {
                    return;
                }
                Canvas canvas = ev.canvas;
                if (radius > 0) {
                    if (stroke > 0) {
                        canvas.roundFrame(x, y, x + width, y + height, radius, stroke, argb);
                    } else {
                        canvas.roundRect(x, y, x + width, y + height, radius, argb);
                    }
                } else if (stroke > 0) {
                    canvas.frame(x, y, x + width, y + height, stroke, argb);
                } else {
                    canvas.fill(x, y, x + width, y + height, argb);
                }
            }
        };
    }
}
