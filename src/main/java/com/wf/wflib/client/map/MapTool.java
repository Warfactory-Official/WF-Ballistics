package com.wf.wflib.client.map;

import com.wf.gemrender.ui.Canvas;
import org.jetbrains.annotations.Nullable;

/**
 * Interactive fullscreen-map tool. Input returns true = consumed (map neither pans nor drops a waypoint).
 * Host polls {@link #available()} when it builds its toolbar and {@link #armed()} every frame.
 */
public interface MapTool {

    @Nullable
    default MapButton button() {
        return null;
    }

    default boolean available() {
        return true;
    }

    default boolean armed() {
        return false;
    }

    default void setArmed(boolean armed) {
    }

    default boolean click(MapPointer pointer) {
        return false;
    }

    default boolean drag(MapPointer pointer) {
        return false;
    }

    default void move(MapPointer pointer) {
    }

    /** Called while the menu is built; skip entirely when not {@link #available()}. */
    default void menu(MapMenu menu, MapView view) {
    }

    /** Canvas units = GUI px from the map's top left, the space {@link MapView} uses. */
    default void render(Canvas canvas, MapView view) {
    }
}
