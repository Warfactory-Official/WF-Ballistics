package com.wf.wflib.client.map;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** What a layer publishes; each host draws it in its own map's terms. World-space unless a size says pixels. */
public sealed interface MapShape permits MapShape.Area, MapShape.Line, MapShape.Icon {

    Look look();

    /**
     * @param group host-side toggle group (JourneyMap overlay group)
     * @param title tooltip, newline-separated
     * @param order higher draws over lower
     * @param fullscreen false = minimap too
     * @param minBlocksPerPixel / maxBlocksPerPixel zoom window shown in; 0 / +inf = always
     */
    record Look(String group, String title, String label, int order, int textColour, boolean fullscreen,
                double minBlocksPerPixel, double maxBlocksPerPixel, @Nullable MapHover hover) {

        public static Look of(String group, int order) {
            return new Look(group, "", "", order, 0xFFFFFF, false, 0.0, Double.POSITIVE_INFINITY, null);
        }

        public Look title(String text) {
            return new Look(group, text, label, order, textColour, fullscreen, minBlocksPerPixel,
                    maxBlocksPerPixel, hover);
        }

        public Look label(String text, int colour) {
            return new Look(group, title, text, order, colour, fullscreen, minBlocksPerPixel,
                    maxBlocksPerPixel, hover);
        }

        public Look fullscreenOnly() {
            return new Look(group, title, label, order, textColour, true, minBlocksPerPixel, maxBlocksPerPixel,
                    hover);
        }

        public Look hover(MapHover listener) {
            return new Look(group, title, label, order, textColour, fullscreen, minBlocksPerPixel,
                    maxBlocksPerPixel, listener);
        }
    }

    /** Closed polygon, clockwise in x/z (JourneyMap: other winding = hole). */
    record Area(List<MapPoint> outline, int y, MapStyle style, Look look) implements MapShape {
    }

    /** Polyline of constant on-screen width; style fill = core, stroke = outline. */
    record Line(List<MapPoint> path, int y, float widthPixels, MapStyle style, Look look) implements MapShape {
    }

    /**
     * @param texture whole-texture sprite, {@code textureSize} square
     * @param rotation degrees clockwise from north (map up)
     * @param tint 0xRRGGBB multiplied in
     */
    record Icon(MapPoint at, int y, ResourceLocation texture, int textureSize, float sizePixels, float rotation,
                int tint, float opacity, Look look) implements MapShape {
    }
}
