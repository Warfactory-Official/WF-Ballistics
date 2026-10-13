package com.wf.wflib.ui.input;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Handlers JSON layouts name by id ({@code "action"}, {@code "set"}, {@code "drag"}); register at client setup. */
public final class UiActions {
    private static final Map<ResourceLocation, Object> HANDLERS = new ConcurrentHashMap<>();

    private UiActions() {
    }

    public static void register(ResourceLocation id, UiAction action) {
        put(id, action);
    }

    public static void register(ResourceLocation id, UiSetter setter) {
        put(id, setter);
    }

    public static void register(ResourceLocation id, UiDrag drag) {
        put(id, drag);
    }

    private static void put(ResourceLocation id, Object handler) {
        if (HANDLERS.putIfAbsent(id, handler) != null) {
            throw new IllegalStateException("UI handler already registered: " + id);
        }
    }

    public static <T> T get(ResourceLocation id, Class<T> type) {
        Object handler = HANDLERS.get(id);
        if (!type.isInstance(handler)) {
            throw new IllegalArgumentException(handler == null ? "no UI handler " + id
                    : "UI handler " + id + " is not a " + type.getSimpleName());
        }
        return type.cast(handler);
    }
}
