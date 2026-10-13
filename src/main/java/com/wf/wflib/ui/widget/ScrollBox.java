package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.Interactive;
import com.wf.wflib.ui.input.UiInput;

/** {@code w x h} clip over a vertically scrolled {@code child}; wheel scrolls {@code step} units per notch. */
public record ScrollBox(String w, String h, float step, String bar, WidgetDef child) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        ColorProp thumb = binder.color(bar);
        int itemSlot = binder.itemSlot();
        Widget node = binder.child(child);
        return new Node(pw, ph, thumb, itemSlot, node);
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final ColorProp thumb;
        private final int itemSlot;
        private final Widget node;
        private float scroll;

        Node(NumProp pw, NumProp ph, ColorProp thumb, int itemSlot, Widget node) {
            this.pw = pw;
            this.ph = ph;
            this.thumb = thumb;
            this.itemSlot = itemSlot;
            this.node = node;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
            node.measure(ev);
        }

        @Override
        public void draw(UiEval ev, float x, float y, float w, float h) {
            float content = node.height();
            scroll = ListView.clamp(scroll, content - height);
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, ev.item(itemSlot));
            }
            Canvas canvas = ev.canvas;
            canvas.pushClip(x, y, x + width, y + height);
            if (input != null) {
                input.pushClip(x, y, x + width, y + height);
            }
            try {
                int argb = thumb.get(ev);
                boolean bar = (argb >>> 24) != 0 && content > height;
                node.draw(ev, x, y - scroll, bar ? width - ListView.BAR_WIDTH - 1 : width, content);
                if (bar) {
                    float size = Math.max(4, height * height / content);
                    float at = y + (height - size) * scroll / (content - height);
                    canvas.fill(x + width - ListView.BAR_WIDTH, at, x + width, at + size, argb);
                }
            } finally {
                canvas.popClip();
                if (input != null) {
                    input.popClip();
                }
            }
        }

        @Override
        public boolean wheel(Object item, float amount) {
            float before = scroll;
            scroll = ListView.clamp(scroll - amount * step, node.height() - height);
            return scroll != before;
        }
    }
}
