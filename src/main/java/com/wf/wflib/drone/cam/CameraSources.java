package com.wf.wflib.drone.cam;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Entity feeds beyond drones and TV rounds, registered by other mods. Server side; first non-null wins. */
public final class CameraSources {

    private static final List<Function<Entity, CameraSource>> PROVIDERS = new CopyOnWriteArrayList<>();

    private CameraSources() {
    }

    /** {@code provider}: the entity's camera, or null if it has none (now). */
    public static void register(Function<Entity, CameraSource> provider) {
        PROVIDERS.add(provider);
    }

    @Nullable
    static CameraSource of(Entity entity) {
        for (Function<Entity, CameraSource> provider : PROVIDERS) {
            CameraSource source = provider.apply(entity);
            if (source != null) {
                return source;
            }
        }
        return null;
    }
}
