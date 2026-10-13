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
import com.wf.wflib.ui.input.UiSetter;

/**
 * Horizontal {@code w x h} track filled to {@code value} (0..1); press/drag calls {@code set} with the
 * pointer's fraction, wheel steps by {@code step} (0 = no wheel).
 */
public record Slider(String w, String h, String value, UiSetter set, float step, String track, String fill,
                     String knob) implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        NumProp pw = binder.num(w);
        NumProp ph = binder.num(h);
        NumProp pv = binder.num(value);
        ColorProp back = binder.color(track);
        ColorProp front = binder.color(fill);
        ColorProp handle = binder.color(knob);
        int itemSlot = binder.itemSlot();
        return new Node(pw, ph, pv, back, front, handle, itemSlot);
    }

    private final class Node extends Widget implements Interactive {
        private final NumProp pw;
        private final NumProp ph;
        private final NumProp pv;
        private final ColorProp back;
        private final ColorProp front;
        private final ColorProp handle;
        private final int itemSlot;
        private double current;

        Node(NumProp pw, NumProp ph, NumProp pv, ColorProp back, ColorProp front, ColorProp handle, int itemSlot) {
            this.pw = pw;
            this.ph = ph;
            this.pv = pv;
            this.back = back;
            this.front = front;
            this.handle = handle;
            this.itemSlot = itemSlot;
        }

        @Override
        public void measure(UiEval ev) {
            width = Math.max(0, pw.getFloat(ev));
            height = Math.max(0, ph.getFloat(ev));
        }

        @Override
        public void draw(UiEval ev, float x, float y, float w, float h) {
            double v = pv.get(ev);
            current = Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + width, y + height, this, ev.item(itemSlot));
            }
            Canvas canvas = ev.canvas;
            float at = x + (float) (current * width);
            canvas.fill(x, y, x + width, y + height, back.get(ev));
            canvas.fill(x, y, at, y + height, front.get(ev));
            float half = Math.max(1, height * 0.25f);
            canvas.fill(at - half, y - 1, at + half, y + height + 1, handle.get(ev));
        }

        @Override
        public void press(Object item, float x, float y) {
            drag(item, x, y);
        }

        @Override
        public void drag(Object item, float x, float y) {
            if (width > 0 && !Float.isNaN(x)) {
                set.set(item, Math.max(0, Math.min(1, x / width)));
            }
        }

        @Override
        public boolean wheel(Object item, float amount) {
            if (step == 0) {
                return false;
            }
            set.set(item, Math.max(0, Math.min(1, current + amount * step)));
            return true;
        }
    }
}
