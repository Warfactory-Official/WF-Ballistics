package com.wf.wflib.ui;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named layouts (presets). Two sources per id: {@link #register} (Java, re-registering replaces) and JSON
 * resources ({@code assets/<ns>/wf_ui/layouts/<path>.json}, see {@link com.wf.wflib.ui.json.UiResources});
 * a resource layout overrides a Java one of the same id while its pack is loaded.
 */
public final class UiLayouts {
    private static final Map<ResourceLocation, UiLayout> JAVA = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, UiLayout> RESOURCES = new ConcurrentHashMap<>();
    private static volatile int generation;

    private UiLayouts() {
    }

    public static UiLayout register(UiLayout layout) {
        JAVA.put(layout.id(), layout);
        generation++;
        return layout;
    }

    /** Throws for an unknown id. */
    public static UiLayout get(ResourceLocation id) {
        UiLayout layout = find(id);
        if (layout == null) {
            throw new IllegalArgumentException("Unknown UI layout " + id);
        }
        return layout;
    }

    public static UiLayout find(ResourceLocation id) {
        UiLayout layout = RESOURCES.get(id);
        return layout != null ? layout : JAVA.get(id);
    }

    /** Changes whenever any id may resolve differently. */
    public static int generation() {
        return generation;
    }

    /** Replaces every resource layout. */
    public static void setResources(Map<ResourceLocation, UiLayout> layouts) {
        RESOURCES.clear();
        RESOURCES.putAll(layouts);
        generation++;
    }

    public static UiLayout findResource(ResourceLocation id) {
        return RESOURCES.get(id);
    }
}
