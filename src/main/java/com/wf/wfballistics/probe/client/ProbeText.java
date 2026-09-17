package com.wf.wfballistics.probe.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
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

    public static void draw(GuiGraphics graphics, Font font, Component text, int x, int y, int argb) {
        draw(graphics, font, text.getVisualOrderText(), x, y, argb);
    }

    public static void draw(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y,
                            int argb) {
        if (!ProbeConfig.TEXT_OUTLINE.get()) {
            graphics.drawString(font, text, x, y, argb, true);
            return;
        }
        FormattedCharSequence border = uncoloured(text);
        int outline = outline(argb);
        for (int[] offset : AROUND) {
            graphics.drawString(font, border, x + offset[0], y + offset[1], outline, false);
        }
        graphics.drawString(font, text, x, y, argb, false);
    }

    /**
     * The same glyphs with every style colour stripped out, so the colour handed to the font is the one that
     * actually lands.
     */
    private static FormattedCharSequence uncoloured(FormattedCharSequence text) {
        return sink -> text.accept((index, style, codePoint) ->
                sink.accept(index, style.withColor((TextColor) null), codePoint));
    }

    /** Black at the text's own opacity; anything lighter reads as a blur rather than an edge. */
    private static int outline(int argb) {
        int alpha = FastColor.ARGB32.alpha(argb);
        return (alpha == 0 ? 255 : alpha) << 24;
    }
}
