package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.ItemsRef;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.RepeatScope;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.Interactive;
import com.wf.wflib.ui.input.UiInput;

import java.util.List;

/**
 * Scrolling column of {@code child} per element of list signal {@code items}, rows {@code rowHeight}
 * apart (+ {@code gap}) in a {@code w x h} clip; only visible rows are evaluated and drawn. Wheel
 * scrolls; {@code bar} (0 = none) colours the scroll thumb at the right edge.
 */
public record ListView(String items, float rowHeight, float gap, String w, String h, String bar, WidgetDef child)
        implements WidgetDef {
    static final float BAR_WIDTH = 2;

    @Override
    public Widget create(Binder binder) {
        if (!(rowHeight > 0)) {
            throw new IllegalArgumentException("list row_height " + rowHeight);
        }
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        ColorProp thumb = binder.color(bar);
        int outerSlot = binder.itemSlot();
        ItemsRef<?> ref = binder.items(items);
        RepeatScope scope = binder.enterScope(ref);
        Widget node = binder.child(child);
        binder.exitScope();
        return new Node(pw, ph, thumb, outerSlot, ref, scope, node);
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final ColorProp thumb;
        private final int outerSlot;
        private final ItemsRef<?> ref;
        private final RepeatScope scope;
        private final Widget node;
        private float scroll;
        private float content;

        Node(NumProp pw, NumProp ph, ColorProp thumb, int outerSlot, ItemsRef<?> ref, RepeatScope scope, Widget node) {
            this.pw = pw;
            this.ph = ph;
            this.thumb = thumb;
            this.outerSlot = outerSlot;
            this.ref = ref;
            this.scope = scope;
            this.node = node;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
        }

        @Override
        public void draw(UiEval ev, float x, float y, float w, float h) {
            List<?> list = ref.get(ev);
            int count = list.size();
            float stride = rowHeight + gap;
            content = count == 0 ? 0 : count * stride - gap;
            scroll = clamp(scroll, content - height);
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, ev.item(outerSlot));
            }
            Canvas canvas = ev.canvas;
            canvas.pushClip(x, y, x + width, y + height);
            if (input != null) {
                input.pushClip(x, y, x + width, y + height);
            }
            Object saved = ev.saveItem(ref);
            try {
                int argb = thumb.get(ev);
                boolean bar = (argb >>> 24) != 0 && content > height;
                float rowWidth = bar ? width - BAR_WIDTH - 1 : width;
                int first = (int) (scroll / stride);
                for (int i = first; i < count; i++) {
                    float top = y + i * stride - scroll;
                    if (top >= y + height) {
                        break;
                    }
                    ev.enterItem(ref, scope, list.get(i), i, count);
                    node.measure(ev);
                    node.draw(ev, x, top, rowWidth, rowHeight);
                }
                if (bar) {
                    float size = Math.max(4, height * height / content);
                    float at = y + (height - size) * scroll / (content - height);
                    canvas.fill(x + width - BAR_WIDTH, at, x + width, at + size, argb);
                }
            } finally {
                ev.restoreItem(ref, saved);
                canvas.popClip();
                if (input != null) {
                    input.popClip();
                }
            }
        }

        @Override
        public boolean wheel(Object item, float amount) {
            float before = scroll;
            scroll = clamp(scroll - amount * (rowHeight + gap), content - height);
            return scroll != before;
        }
    }

    static float clamp(float scroll, float max) {
        return Math.max(0, Math.min(scroll, Math.max(0, max)));
    }
}
