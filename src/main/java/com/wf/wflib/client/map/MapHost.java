package com.wf.wflib.client.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * One map mod. Implementations live in their own package, the only place that mod's types appear, and
 * register via {@link WFMaps#addHost}. Map mod without an overlay API => draw with {@link MapPainter}.
 */
public interface MapHost {

    String name();

    /** Replace everything {@code layer} drew. Client thread. Empty list = clear. */
    void show(String layer, ResourceKey<Level> dimension, List<MapShape> shapes);

    default boolean hasFullscreen() {
        return false;
    }

    /** Only when {@link #hasFullscreen()}. Client thread, in world. */
    default void openFullscreen() {
        throw new UnsupportedOperationException(name() + " has no fullscreen map");
    }
}
