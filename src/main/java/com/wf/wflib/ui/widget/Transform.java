package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Translates, then rotates ({@code rotate} degrees, clockwise) and scales about the child's
 * {@code pivot}. Reports the unscaled child size; layout ignores the transform.
 */
public record Transform(String dx, String dy, String rotate, String scale, Point pivot, WidgetDef child)
        implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp tx = binder.num(dx);
        NumProp ty = binder.num(dy);
        NumProp rot = binder.num(rotate);
        NumProp sc = binder.num(scale);
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
                Canvas canvas = ev.canvas;
                float px = x + pivot.x.pivot(width);
                float py = y + pivot.y.pivot(height);
                canvas.push()
                        .translate(px + tx.getFloat(ev), py + ty.getFloat(ev))
                        .rotate((float) Math.toRadians(rot.get(ev)))
                        .scale(sc.getFloat(ev))
                        .translate(-px, -py);
                try {
                    node.draw(ev, x, y, w, h);
                } finally {
                    canvas.pop();
                }
            }
        };
    }
}
