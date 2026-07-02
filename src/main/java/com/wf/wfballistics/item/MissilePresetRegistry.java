package com.wf.wfballistics.item;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of launch-ready {@link MissilePreset}s. Each registered preset is turned into a carryable
 * {@link MissileItem} by {@link ModItems}. Register your own during mod construction (before items are
 * frozen) with {@link #register}; the built-ins are added by {@link #bootstrap()}.
 *
 * <p>Registration order is preserved so the creative tab and item list stay stable.
 */
public final class MissilePresetRegistry {

    private static final Map<String, MissilePreset> PRESETS = new LinkedHashMap<>();
    private static boolean bootstrapped = false;

    private MissilePresetRegistry() {
    }

    public static void register(MissilePreset preset) {
        PRESETS.put(preset.id(), preset);
    }

    public static MissilePreset get(String id) {
        return PRESETS.get(id);
    }

    public static Collection<MissilePreset> all() {
        return Collections.unmodifiableCollection(PRESETS.values());
    }

    /** Registers the built-in presets. Idempotent; call before {@link ModItems} enumerates them. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        // A spread of archetypes across the model + warhead catalogue.
        register(MissilePreset.builder("cruise", "v2", "standard")
                .terrainFollow(24.0).cruiseSpeed(1.0).build());

        register(MissilePreset.builder("ballistic", "strong", "standard")
                .highAltitude(220.0).cruiseSpeed(1.2).health(60.0f).build());

        register(MissilePreset.builder("nuclear", "huge", "mininuke")
                .highAltitude(240.0).cruiseSpeed(1.0).health(90.0f).build());

        register(MissilePreset.builder("fragmentation", "atlas", "fragmentation")
                .highAltitude(200.0).explosionOffset(30.0f).fragmentCount(32).build());

        register(MissilePreset.builder("cluster", "micro", "recursive_frag")
                .terrainFollow(16.0).cruiseSpeed(1.5).splitDepth(2).fragmentCount(4)
                .explosionOffset(16.0f).build());

        register(MissilePreset.builder("chemical", "taint", "gas")
                .terrainFollow(24.0).explosionOffset(12.0f).build());
    }
}
