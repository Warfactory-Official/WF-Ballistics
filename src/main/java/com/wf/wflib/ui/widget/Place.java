package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/** Puts the child's {@code pivot} at ({@code x}, {@code y}) relative to the box origin. Size 0. */
public record Place(String x, String y, Point pivot, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp px = binder.num(x);
        NumProp py = binder.num(y);
        Widget node = binder.child(child);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                node.measure(ev);
            }

            @Override
            public void draw(UiEval ev, float bx, float by, float w, float h) {
                float cx = bx + px.getFloat(ev) - pivot.x.pivot(node.width());
                float cy = by + py.getFloat(ev) - pivot.y.pivot(node.height());
                node.draw(ev, cx, cy, node.width(), node.height());
            }
        };
    }
}
