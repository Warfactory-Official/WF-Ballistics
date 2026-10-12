package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.List;

/**
 * Row ({@code vertical=false}) or column. Zero-size children (hidden {@link When}) take no gap.
 * {@code align}: cross-axis placement of each child.
 */
public record Flow(boolean vertical, float gap, Align align, List<WidgetDef> children) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        Widget[] nodes = new Widget[children.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = binder.child(children.get(i));
        }
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                float main = 0;
                float cross = 0;
                boolean any = false;
                for (Widget node : nodes) {
                    node.measure(ev);
                    float m = vertical ? node.height() : node.width();
                    float c = vertical ? node.width() : node.height();
                    if (m == 0 && c == 0) {
                        continue;
                    }
                    main += (any ? gap : 0) + m;
                    cross = Math.max(cross, c);
                    any = true;
                }
                width = vertical ? cross : main;
                height = vertical ? main : cross;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                float at = 0;
                boolean any = false;
                float cross = vertical ? width : height;
                for (Widget node : nodes) {
                    float m = vertical ? node.height() : node.width();
                    float c = vertical ? node.width() : node.height();
                    if (m == 0 && c == 0) {
                        continue;
                    }
                    if (any) {
                        at += gap;
                    }
                    any = true;
                    float off = align.offset(cross, c);
                    if (vertical) {
                        node.draw(ev, x + off, y + at, c, m);
                    } else {
                        node.draw(ev, x + at, y + off, m, c);
                    }
                    at += m;
                }
            }
        };
    }
}
