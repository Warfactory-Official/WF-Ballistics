package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.Interactive;
import com.wf.wflib.ui.input.TextLine;
import com.wf.wflib.ui.input.UiInput;
import net.minecraft.Util;

/**
 * Single-line input over a {@link TextLine}; press => keyboard focus. Text scrolls to keep the caret visible.
 * Code-only (model is a Java object), no JSON name.
 */
public record TextField(TextLine line, String prompt, float w, float h, int back, int edge, int focusEdge,
                        int textColour, int promptColour) implements WidgetDef {
    private static final float PAD = 3;
    private static final long BLINK_MS = 500;

    @Override
    public Widget create(Binder binder) {
        return new Node();
    }

    private final class Node extends Widget implements Interactive {
        @Override
        public void measure(UiEval ev) {
            width = w;
            height = h;
        }

        @Override
        public void draw(UiEval ev, float x, float y, float bw, float bh) {
            Canvas canvas = ev.canvas;
            boolean focused = line.focused();
            canvas.fill(x, y, x + w, y + h, focused ? focusEdge : edge);
            canvas.fill(x + 1, y + 1, x + w - 1, y + h - 1, back);
            float ty = y + (h - canvas.lineHeight()) / 2 + 1;
            float tx = canvas.text(prompt, x + PAD, ty, promptColour, false);
            CharSequence text = line.text();
            int cursor = line.cursor();
            float room = x + w - PAD - tx - 2;
            int start = 0;
            while (start < cursor && canvas.width(text, start, cursor) > room) {
                start++;
            }
            int end = text.length();
            while (end > cursor && canvas.width(text, start, end) > room) {
                end--;
            }
            canvas.text(text, start, end, tx, ty, textColour, false);
            if (focused && Util.getMillis() / BLINK_MS % 2 == 0) {
                float cx = tx + canvas.width(text, start, cursor);
                canvas.fill(cx, ty - 1, cx + 1, ty + canvas.lineHeight() - 1, textColour);
            }
            UiInput input = ev.input;
            if (input != null) {
                input.hit(x, y, x + w, y + h, this, null);
            }
        }

        @Override
        public void press(Object item, float x, float y) {
            line.focus();
        }
    }
}
