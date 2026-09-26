package com.wf.wflib.rail.align;

import java.util.Locale;

/**
 * The colours a surveyor may give a line's core.
 *
 * <p>A named set rather than a free integer, for two reasons: it is pickable from a menu with no colour
 * widget to build, and every one of them has been chosen to stay legible against map terrain and
 * distinct from the others. A free picker would let someone choose the exact green of a forest.</p>
 *
 * <p>The core is the surveyor's choice. The line's outline is the owning faction's colour and is not
 * chosen here, so that "whose line is this" never depends on the taste of whoever drew it.</p>
 */
public enum LineColour {

    WHITE(0xF2F2F2),
    AMBER(0xFFC24B),
    RED(0xFF4A3D),
    MAGENTA(0xFF6FD5),
    VIOLET(0xB38CFF),
    BLUE(0x6FB3FF),
    CYAN(0x66E0FF),
    GREEN(0x8CE99A),
    BLACK(0x1B1B1B);

    private final int rgb;

    LineColour(int rgb) {
        this.rgb = rgb;
    }

    /** 0xRRGGBB. */
    public int rgb() {
        return this.rgb;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The nearest named colour to an arbitrary one, so a stored value always has a name to show. */
    public static LineColour nearest(int rgb) {
        LineColour best = WHITE;
        int bestDistance = Integer.MAX_VALUE;
        for (LineColour colour : values()) {
            int dr = ((rgb >> 16) & 0xFF) - ((colour.rgb >> 16) & 0xFF);
            int dg = ((rgb >> 8) & 0xFF) - ((colour.rgb >> 8) & 0xFF);
            int db = (rgb & 0xFF) - (colour.rgb & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = colour;
            }
        }
        return best;
    }
}
