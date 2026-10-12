package com.wf.wflib.ui.widget;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.NumProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.Map;

/**
 * Moving scale centred on {@code value}: compass, heading, altitude, speed tapes. Measured box =
 * 2 x {@code extent} (expression) along the axis; {@code value} sits at its middle on the base line (top edge
 * horizontal, left edge vertical, or the opposite edge with {@code flip}); clipped to the extent. Marks every {@code step} units, {@code ppu} canvas units apart per unit; every
 * {@code majorEvery}-th mark is major and gets a label when {@code labels} is on. Ticks grow
 * along +y (horizontal) or +x (vertical), or the negative axis with {@code flip}.
 *
 * <p>{@code wrapLo}/{@code wrapHi} (both non-NaN) wrap label values into [lo, hi); {@code names}
 * replaces the label (and forces a major tick) at those wrapped values, e.g. {@code 180 -> "N"}.
 * Vertical tapes put larger values towards -y unless {@code descending}. Numeric labels print
 * {@code shown * labelScale}, zero-padded to {@code labelPad} digits; named marks tick and label in
 * {@code nameColor}, other major ticks in {@code majorColor}.
 */
public record Tape(String value, boolean vertical, boolean flip, boolean descending, float ppu, float step,
                   int majorEvery, String extent, float minorLength, float majorLength, float thickness,
                   boolean labels, float labelOffset, int labelDecimals, float wrapLo, float wrapHi,
                   Map<Float, String> names, String color, String labelColor, float labelScale, int labelPad,
                   String nameColor, String majorColor) implements WidgetDef {
    /** Plain numeric labels, names in the label colour. */
    public Tape(String value, boolean vertical, boolean flip, boolean descending, float ppu, float step,
                int majorEvery, String extent, float minorLength, float majorLength, float thickness,
                boolean labels, float labelOffset, int labelDecimals, float wrapLo, float wrapHi,
                Map<Float, String> names, String color, String labelColor) {
        this(value, vertical, flip, descending, ppu, step, majorEvery, extent, minorLength, majorLength, thickness,
                labels, labelOffset, labelDecimals, wrapLo, wrapHi, names, color, labelColor, 1, 0, labelColor, color);
    }

    private static final int LABEL_CAPACITY = 32;

    private static final float UNBOUNDED = 1e4f;

    @Override
    public Widget create(Binder binder) {
        NumProp current = binder.num(value);
        NumProp half = binder.num(extent);
        ColorProp tick = binder.color(color);
        ColorProp label = binder.color(labelColor);
        ColorProp named = binder.color(nameColor);
        ColorProp majorTick = binder.color(majorColor);
        float[] nameKeys = new float[names.size()];
        String[] nameValues = new String[names.size()];
        int n = 0;
        for (Map.Entry<Float, String> entry : names.entrySet()) {
            nameKeys[n] = entry.getKey();
            nameValues[n++] = entry.getValue();
        }
        return new Widget() {
            private final TextBuffer text = new TextBuffer(LABEL_CAPACITY);
            private float extent;

            @Override
            public void measure(UiEval ev) {
                extent = Math.max(0, (float) half.get(ev));
                float cross = Math.max(minorLength, majorLength) + (labels ? Math.abs(labelOffset) + 9 : 0);
                width = vertical ? cross : extent * 2;
                height = vertical ? extent * 2 : cross;
            }

            @Override
            public void draw(UiEval ev, float bx, float by, float w, float h) {
                float x = vertical ? (flip ? bx + width : bx) : bx + width * 0.5f;
                float y = vertical ? by + height * 0.5f : (flip ? by + height : by);
                double v = current.get(ev);
                if (Double.isNaN(v) || step <= 0 || ppu <= 0) {
                    return;
                }
                Canvas canvas = ev.canvas;
                int tickArgb = tick.get(ev);
                int labelArgb = label.get(ev);
                int nameArgb = named.get(ev);
                int majorArgb = majorTick.get(ev);
                float span = extent / ppu;
                // One past each end: a tick or label straddling the clip edge still shows its inner half.
                long first = (long) Math.ceil((v - span) / step) - 1;
                long last = (long) Math.floor((v + span) / step) + 1;
                float side = flip ? -1 : 1;
                float sign = vertical && !descending ? -1 : 1;
                if (vertical) {
                    canvas.pushClip(x - UNBOUNDED, y - extent, x + UNBOUNDED, y + extent);
                } else {
                    canvas.pushClip(x - extent, y - UNBOUNDED, x + extent, y + UNBOUNDED);
                }
                try {
                    for (long i = first; i <= last; i++) {
                        double mark = i * (double) step;
                        float along = (float) ((mark - v) * ppu) * sign;
                        double shown = mark;
                        if (!Float.isNaN(wrapLo) && !Float.isNaN(wrapHi)) {
                            double range = wrapHi - wrapLo;
                            shown = wrapLo + ((mark - wrapLo) % range + range) % range;
                        }
                        int named = -1;
                        for (int k = 0; k < nameKeys.length; k++) {
                            if (Math.abs(nameKeys[k] - shown) < 1e-4) {
                                named = k;
                                break;
                            }
                        }
                        boolean major = named >= 0 || majorEvery > 0 && Math.floorMod(i, majorEvery) == 0;
                        float len = major ? majorLength : minorLength;
                        int markArgb = named >= 0 ? nameArgb : major ? majorArgb : tickArgb;
                        if ((markArgb >>> 24) != 0 && len > 0) {
                            if (vertical) {
                                float x0 = x;
                                float x1 = x + side * len;
                                canvas.fill(Math.min(x0, x1), y + along - thickness * 0.5f, Math.max(x0, x1),
                                        y + along + thickness * 0.5f, markArgb);
                            } else {
                                float y0 = y;
                                float y1 = y + side * len;
                                canvas.fill(x + along - thickness * 0.5f, Math.min(y0, y1), x + along + thickness * 0.5f,
                                        Math.max(y0, y1), markArgb);
                            }
                        }
                        int textArgb = named >= 0 ? nameArgb : labelArgb;
                        if (!labels || !major || (textArgb >>> 24) == 0) {
                            continue;
                        }
                        text.clear();
                        if (named >= 0) {
                            text.append(nameValues[named]);
                        } else {
                            text.append(shown * labelScale, labelDecimals, labelPad, '0', false);
                        }
                        float tw = canvas.width(text);
                        if (vertical) {
                            float lx = flip ? x - labelOffset - tw : x + labelOffset;
                            canvas.text(text, lx, y + along - 4, textArgb, false);
                        } else {
                            float ly = flip ? y - labelOffset - 8 : y + labelOffset;
                            canvas.text(text, x + along - tw * 0.5f, ly, textArgb, false);
                        }
                    }
                } finally {
                    canvas.popClip();
                }
            }
        };
    }
}
