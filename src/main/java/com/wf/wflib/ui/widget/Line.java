package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/** Antialiased segment in box-local coordinates; size 0 (place it inside a {@link Sized} or {@link Stack}). */
public record Line(String x0, String y0, String x1, String y1, String width, String color) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp ax = binder.num(x0);
        NumProp ay = binder.num(y0);
        NumProp bx = binder.num(x1);
        NumProp by = binder.num(y1);
        NumProp lw = binder.num(width);
        ColorProp colour = binder.color(color);
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
                ev.canvas.line(x + ax.getFloat(ev), y + ay.getFloat(ev), x + bx.getFloat(ev), y + by.getFloat(ev),
                        lw.getFloat(ev), argb);
            }
        };
    }
}
