package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.List;

/** Children share the parent's box, drawn in order; size = largest child. */
public record Stack(List<WidgetDef> children) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        Widget[] nodes = new Widget[children.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = binder.child(children.get(i));
        }
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                float w = 0;
                float h = 0;
                for (Widget node : nodes) {
                    node.measure(ev);
                    w = Math.max(w, node.width());
                    h = Math.max(h, node.height());
                }
                width = w;
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                for (Widget node : nodes) {
                    node.draw(ev, x, y, w, h);
                }
            }
        };
    }
}
