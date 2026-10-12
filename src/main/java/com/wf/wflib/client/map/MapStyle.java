package com.wf.wflib.client.map;

/** Colours 0xRRGGBB; opacity 0..1; stroke width in pixels. */
public record MapStyle(int strokeColour, float strokeOpacity, float strokeWidth, int fillColour, float fillOpacity) {

    public static MapStyle fill(int colour, float opacity) {
        return new MapStyle(colour, 0.0f, 0.0f, colour, opacity);
    }

    public static MapStyle outline(int colour, float opacity, float width) {
        return new MapStyle(colour, opacity, width, colour, 0.0f);
    }
}
