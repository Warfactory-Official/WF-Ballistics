package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Fixed-size box (expressions); the child gets this box, placed by {@code align}. {@code clip}
 * cuts drawing to the box. {@code child} may be null (spacer).
 */
public record Sized(String w, String h, Point align, boolean clip, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        Widget node = child == null ? null : binder.child(child);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = Math.max(0, ph.getFloat(ev));
                if (node != null) {
                    node.measure(ev);
                }
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                if (node == null) {
                    return;
                }
                if (clip) {
                    ev.canvas.pushClip(x, y, x + width, y + height);
                }
                try {
                    node.draw(ev, x + align.x.offset(width, node.width()), y + align.y.offset(height, node.height()),
                            width, height);
                } finally {
                    if (clip) {
                        ev.canvas.popClip();
                    }
                }
            }
        };
    }
}
