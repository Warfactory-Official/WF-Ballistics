package com.wf.wflib.ui.json;

import com.wf.wflib.WFLib;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON {@code "type"} => reader. A bare name means {@code wflib:<name>}; any other id not registered here
 * is looked up as a component ({@code assets/<ns>/wf_ui/components/<path>.json}). Register Java widgets
 * during client setup.
 */
public final class UiWidgetTypes {
    private static final Map<ResourceLocation, WidgetReader> TYPES = new ConcurrentHashMap<>();

    static {
        BuiltinWidgets.register();
    }

    private UiWidgetTypes() {
    }

    public static void register(ResourceLocation id, WidgetReader reader) {
        if (TYPES.putIfAbsent(id, reader) != null) {
            throw new IllegalStateException("UI widget type already registered: " + id);
        }
    }

    static WidgetReader get(ResourceLocation id) {
        return TYPES.get(id);
    }

    static ResourceLocation id(String type) {
        return type.indexOf(':') < 0 ? ResourceLocation.fromNamespaceAndPath(WFLib.MODID, type)
                : ResourceLocation.parse(type);
    }
}
