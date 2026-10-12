package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Panel: background and inner border around a padded child; at least {@code minWidth} x
 * {@code minHeight}. {@code child} may be null (plain panel). The child is placed by {@code align}
 * inside the padded area.
 */
public record Box(String background, String border, float borderWidth, float radius, float padX, float padY,
                  float minWidth, float minHeight, Point align, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        ColorProp back = binder.color(background);
        ColorProp edge = binder.color(border);
        Widget node = child == null ? null : binder.child(child);
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                float cw = 0;
                float ch = 0;
                if (node != null) {
                    node.measure(ev);
                    cw = node.width();
                    ch = node.height();
                }
                width = Math.max(minWidth, cw + padX * 2);
                height = Math.max(minHeight, ch + padY * 2);
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                Canvas canvas = ev.canvas;
                int b = back.get(ev);
                int e = edge.get(ev);
                float x1 = x + width;
                float y1 = y + height;
                if (radius > 0) {
                    if ((b >>> 24) != 0) {
                        canvas.roundRect(x, y, x1, y1, radius, b);
                    }
                    if ((e >>> 24) != 0 && borderWidth > 0) {
                        canvas.roundFrame(x, y, x1, y1, radius, borderWidth, e);
                    }
                } else {
                    if ((b >>> 24) != 0) {
                        canvas.fill(x, y, x1, y1, b);
                    }
                    if ((e >>> 24) != 0 && borderWidth > 0) {
                        canvas.frame(x, y, x1, y1, borderWidth, e);
                    }
                }
                if (node != null) {
                    float iw = width - padX * 2;
                    float ih = height - padY * 2;
                    node.draw(ev, x + padX + align.x.offset(iw, node.width()), y + padY + align.y.offset(ih, node.height()),
                            node.width(), node.height());
                }
            }
        };
    }
}
