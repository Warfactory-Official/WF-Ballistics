package com.wf.wflib.round.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Preset or missile model id -> {@link RoundRenderer}. Precedes the glTF model. */
public final class RoundRenderers {

    private static final Map<ResourceLocation, RoundRenderer> BY_ID = new ConcurrentHashMap<>();

    private RoundRenderers() {
    }

    public static void register(ResourceLocation id, RoundRenderer renderer) {
        BY_ID.put(id, renderer);
    }

    @Nullable
    public static RoundRenderer get(ResourceLocation id) {
        return BY_ID.get(id);
    }
}
