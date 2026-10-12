package com.wf.wflib.ui.widget;

import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ItemsRef;
import com.wf.wflib.ui.RepeatScope;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.List;

/**
 * One {@code child} per element of list signal {@code items} (at most {@code max}, 0 = all), laid
 * out as a row or column; {@code columns > 0} => grid of that many columns ({@code gap} both ways,
 * cells sized by the largest element). Inside, the list's item subject reads the current element and
 * {@code index}/{@code count} the iteration. Each element is measured again right before it draws.
 */
public record Repeat(String items, boolean vertical, float gap, Align align, int max, int columns, WidgetDef child)
        implements WidgetDef {
    @Override
    public Widget create(Binder binder) {
        ItemsRef<?> ref = binder.items(items);
        RepeatScope scope = binder.enterScope(ref);
        Widget node = binder.child(child);
        binder.exitScope();
        return new Widget() {
            private float cellW;
            private float cellH;

            @Override
            public void measure(UiEval ev) {
                if (columns > 0) {
                    measureGrid(ev);
                    return;
                }
                List<?> list = ref.get(ev);
                int count = max > 0 ? Math.min(max, list.size()) : list.size();
                Object saved = ev.saveItem(ref);
                float main = 0;
                float cross = 0;
                boolean any = false;
                for (int i = 0; i < count; i++) {
                    ev.enterItem(ref, scope, list.get(i), i, count);
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
                ev.restoreItem(ref, saved);
                width = vertical ? cross : main;
                height = vertical ? main : cross;
            }

            private void measureGrid(UiEval ev) {
                List<?> list = ref.get(ev);
                int count = max > 0 ? Math.min(max, list.size()) : list.size();
                Object saved = ev.saveItem(ref);
                cellW = 0;
                cellH = 0;
                for (int i = 0; i < count; i++) {
                    ev.enterItem(ref, scope, list.get(i), i, count);
                    node.measure(ev);
                    cellW = Math.max(cellW, node.width());
                    cellH = Math.max(cellH, node.height());
                }
                ev.restoreItem(ref, saved);
                int rows = (count + columns - 1) / columns;
                int cols = Math.min(columns, count);
                width = cols == 0 ? 0 : cols * cellW + (cols - 1) * gap;
                height = rows == 0 ? 0 : rows * cellH + (rows - 1) * gap;
            }

            private void drawGrid(UiEval ev, float x, float y) {
                List<?> list = ref.get(ev);
                int count = max > 0 ? Math.min(max, list.size()) : list.size();
                Object saved = ev.saveItem(ref);
                for (int i = 0; i < count; i++) {
                    ev.enterItem(ref, scope, list.get(i), i, count);
                    node.measure(ev);
                    float cx = x + (i % columns) * (cellW + gap);
                    float cy = y + (i / columns) * (cellH + gap);
                    node.draw(ev, cx + align.offset(cellW, node.width()), cy, cellW, cellH);
                }
                ev.restoreItem(ref, saved);
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                if (columns > 0) {
                    drawGrid(ev, x, y);
                    return;
                }
                List<?> list = ref.get(ev);
                int count = max > 0 ? Math.min(max, list.size()) : list.size();
                Object saved = ev.saveItem(ref);
                float cross = vertical ? width : height;
                float at = 0;
                boolean any = false;
                for (int i = 0; i < count; i++) {
                    ev.enterItem(ref, scope, list.get(i), i, count);
                    node.measure(ev);
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
                ev.restoreItem(ref, saved);
            }
        };
    }
}
