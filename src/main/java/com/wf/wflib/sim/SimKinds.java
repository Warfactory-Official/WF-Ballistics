package com.wf.wflib.sim;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registered sim kinds; iteration = registration order (= prepare/resolve order within a slot). */
public final class SimKinds {

    private static final Map<ResourceLocation, SimKind<?>> KINDS = new LinkedHashMap<>();

    private SimKinds() {
    }

    /** Before the first level loads: a level's store is built from the kinds known then. */
    public static synchronized <R, K extends SimKind<R>> K register(K kind) {
        if (KINDS.putIfAbsent(kind.id(), kind) != null) {
            throw new IllegalStateException("sim kind registered twice: " + kind.id());
        }
        return kind;
    }

    public static Collection<SimKind<?>> all() {
        return Collections.unmodifiableCollection(KINDS.values());
    }
}
