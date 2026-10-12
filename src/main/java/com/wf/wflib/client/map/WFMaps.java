package com.wf.wflib.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Map-mod-neutral entry: layers publish shapes, tools register once; every attached {@link MapHost} draws
 * and dispatches them. Late host => current layers replayed. Client only.
 */
public final class WFMaps {

    private record Published(ResourceKey<Level> dimension, List<MapShape> shapes) {
    }

    private static final List<MapHost> HOSTS = new CopyOnWriteArrayList<>();
    private static final List<MapTool> TOOLS = new CopyOnWriteArrayList<>();
    /** Client thread only. */
    private static final Map<String, Published> LAYERS = new LinkedHashMap<>();

    private WFMaps() {
    }

    public static void addHost(MapHost host) {
        Minecraft.getInstance().execute(() -> {
            HOSTS.add(host);
            LAYERS.forEach((layer, published) -> host.show(layer, published.dimension(), published.shapes()));
        });
    }

    public static List<MapHost> hosts() {
        return HOSTS;
    }

    /** Any thread; applied on the client thread. */
    public static void publish(String layer, ResourceKey<Level> dimension, List<MapShape> shapes) {
        List<MapShape> copy = List.copyOf(shapes);
        Minecraft.getInstance().execute(() -> {
            if (copy.isEmpty()) {
                LAYERS.remove(layer);
            } else {
                LAYERS.put(layer, new Published(dimension, copy));
            }
            for (MapHost host : HOSTS) {
                host.show(layer, dimension, copy);
            }
        });
    }

    public static void clear(String layer) {
        Minecraft.getInstance().execute(() -> {
            Published gone = LAYERS.remove(layer);
            if (gone != null) {
                for (MapHost host : HOSTS) {
                    host.show(layer, gone.dimension(), List.of());
                }
            }
        });
    }

    public static void addTool(MapTool tool) {
        TOOLS.add(tool);
    }

    public static List<MapTool> tools() {
        return TOOLS;
    }

    /** Layer ids currently drawn, for diagnostics. */
    public static List<String> layers() {
        return new ArrayList<>(LAYERS.keySet());
    }
}
