package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.Interactive;
import com.wf.wflib.ui.input.UiDrag;
import com.wf.wflib.ui.input.UiInput;

/**
 * {@code w x h} pan/zoom surface: drag deltas and wheel go to {@code handler}. {@code child} (nullable)
 * draws in the box; its own interactive widgets sit above the area.
 */
public record DragArea(String w, String h, UiDrag handler, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        int itemSlot = binder.itemSlot();
        Widget node = child == null ? null : binder.child(child);
        return new Node(pw, ph, itemSlot, node);
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final int itemSlot;
        private final Widget node;
        private float lastX;
        private float lastY;

        Node(NumProp pw, NumProp ph, int itemSlot, Widget node) {
            this.pw = pw;
            this.ph = ph;
            this.itemSlot = itemSlot;
            this.node = node;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
            if (node != null) {
                node.measure(ev);
            }
        }

        @Override
        public void draw(UiEval ev, float x, float y, float w, float h) {
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, ev.item(itemSlot));
            }
            if (node != null) {
                node.draw(ev, x, y, width, height);
            }
        }

        @Override
        public void press(Object item, float x, float y) {
            lastX = x;
            lastY = y;
        }

        @Override
        public void drag(Object item, float x, float y) {
            if (Float.isNaN(x) || Float.isNaN(y)) {
                return;
            }
            if (Float.isNaN(lastX)) {
                lastX = x;
                lastY = y;
            } else if (x != lastX || y != lastY) {
                handler.drag(item, x - lastX, y - lastY);
                lastX = x;
                lastY = y;
            }
        }

        @Override
        public boolean wheel(Object item, float amount) {
            return handler.wheel(item, amount);
        }
    }
}
