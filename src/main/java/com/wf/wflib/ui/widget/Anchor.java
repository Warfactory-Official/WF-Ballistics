package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Pins the child's {@code at} point to the same point of the parent box, {@code margin} inward from
 * the edges, then shifts by ({@code dx}, {@code dy}) (screen axes, +y down).
 */
public record Anchor(Point at, float margin, String dx, String dy, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp ox = binder.num(dx);
        NumProp oy = binder.num(dy);
        Widget node = binder.child(child);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                node.measure(ev);
                width = node.width();
                height = node.height();
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                float cx = x + at.x.offset(w, width) + Point.inward(at.x) * margin + ox.getFloat(ev);
                float cy = y + at.y.offset(h, height) + Point.inward(at.y) * margin + oy.getFloat(ev);
                node.draw(ev, cx, cy, width, height);
            }
        };
    }
}
