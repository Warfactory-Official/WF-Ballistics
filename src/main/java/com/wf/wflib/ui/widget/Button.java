package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.BoolProp;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.Interactive;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.input.UiInput;

/**
 * {@link Box} that runs {@code action} on click while {@code enabled}; background swaps to {@code hover}
 * under the pointer and {@code pressed} while held over it.
 */
public record Button(UiAction action, String enabled, String background, String hover, String pressed, String border,
                     float borderWidth, float radius, float padX, float padY, float minWidth, float minHeight,
                     Point align, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        BoolProp on = binder.bool(enabled);
        ColorProp back = binder.color(background);
        ColorProp over = binder.color(hover);
        ColorProp down = binder.color(pressed);
        ColorProp edge = binder.color(border);
        int itemSlot = binder.itemSlot();
        Widget node = child == null ? null : binder.child(child);
        return new Node(on, back, over, down, edge, itemSlot, node);
    }

    private final class Node extends Widget implements Interactive {
        private final BoolProp on;
        private final ColorProp back;
        private final ColorProp over;
        private final ColorProp down;
        private final ColorProp edge;
        private final int itemSlot;
        private final Widget node;

        Node(BoolProp on, ColorProp back, ColorProp over, ColorProp down, ColorProp edge, int itemSlot, Widget node) {
            this.on = on;
            this.back = back;
            this.over = over;
            this.down = down;
            this.edge = edge;
            this.itemSlot = itemSlot;
            this.node = node;
        }

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
            UiInput input = ev.input;
            Object item = ev.item(itemSlot);
            int b = back.get(ev);
            if (input != null && on.get(ev)) {
                input.hit(x, y, x + width, y + height, this, item);
                if (input.hovered(this, item)) {
                    b = input.captured(this, item) ? down.get(ev) : over.get(ev);
                }
            }
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

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (inside) {
                action.run(item);
            }
        }
    }
}
