package com.wf.wflib.probe.client;

import com.wf.gemrender.ui.Canvas;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;

/** Text with a border rather than a drop shadow. */
public final class ProbeText {

    private static final int[][] AROUND = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1},
    };

    private ProbeText() {
    }

    public static void draw(Canvas canvas, Component text, float x, float y, int argb) {
        draw(canvas, text.getVisualOrderText(), x, y, argb);
    }

    public static void draw(Canvas canvas, FormattedCharSequence text, float x, float y, int argb) {
        argb = opaque(argb);
        if (!ProbeConfig.TEXT_OUTLINE.get()) {
            canvas.text(text, x, y, argb, true);
            return;
        }
        int outline = outline(argb);
        for (int[] offset : AROUND) {
            canvas.text(text, x + offset[0], y + offset[1], outline, false, false);
        }
        canvas.text(text, x, y, argb, false);
    }

    /** Vanilla {@code Font.adjustColor}: alpha below 4 means "no alpha given" => opaque. */
    static int opaque(int argb) {
        return (argb & 0xFC000000) == 0 ? argb | 0xFF000000 : argb;
    }

    /** Black at the text's own opacity; anything lighter reads as a blur rather than an edge. */
    private static int outline(int argb) {
        int alpha = FastColor.ARGB32.alpha(argb);
        return (alpha == 0 ? 255 : alpha) << 24;
    }
}
