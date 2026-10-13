package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.Arrays;
import java.util.List;

/** Log over a live {@code lines} list, newest at the bottom; lines wrapped at spaces (mid-word when a word overflows). */
record PdaLog(List<PdaLog.Line> lines, float w, float h) implements WidgetDef {

    record Line(String text, int colour) {
    }

    @Override
    public Widget create(Binder binder) {
        return new Widget() {
            /** Segment starts of the line being drawn, then its length. */
            private int[] breaks = new int[8];

            @Override
            public void measure(UiEval ev) {
                width = w;
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas canvas = ev.canvas;
                canvas.fill(x, y, x + w, y + h, 0xFF101210);
                float lineH = canvas.lineHeight() + 1;
                float ly = y + h - lineH - 1;
                for (int i = lines.size() - 1; i >= 0 && ly >= y; i--) {
                    Line line = lines.get(i);
                    String text = line.text();
                    int n = wrap(canvas, text, w - 6);
                    for (int k = n - 1; k >= 0 && ly >= y; k--, ly -= lineH) {
                        canvas.text(text, breaks[k], breaks[k + 1], x + 3, ly + 1, line.colour(), false);
                    }
                }
            }

            /** @return segment count; {@code breaks[0..n]} filled. */
            private int wrap(Canvas canvas, String text, float room) {
                int n = 0;
                int start = 0;
                int len = text.length();
                while (true) {
                    if (n + 2 > breaks.length) {
                        breaks = Arrays.copyOf(breaks, breaks.length * 2);
                    }
                    breaks[n++] = start;
                    if (canvas.width(text, start, len) <= room) {
                        breaks[n] = len;
                        return n;
                    }
                    int end = start + 1;
                    while (end < len && canvas.width(text, start, end + 1) <= room) {
                        end++;
                    }
                    int space = text.lastIndexOf(' ', end);
                    start = space > start ? space + 1 : end;
                }
            }
        };
    }
}
