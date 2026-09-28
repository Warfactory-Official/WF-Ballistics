package com.wf.wflib.kinetic;

import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.round.pen.PenMaterial;
import com.wf.wflib.warhead.FireWarhead;
import com.wf.wflib.warhead.GasWarhead;
import com.wf.wflib.warhead.RecursiveFrag;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Registry of loadable {@link KineticPreset}s, keyed by {@link ResourceLocation}. Copy-on-write: any thread. */
public final class KineticPresetRegistry {

    private static final ResourceLocation DEFAULT_ID = rl("he");
    /** Pre-1.6.4 blast-resistance 100/60 outcome: every class below ARMOR drilled. */
    private static final double DRILLS_METAL = PenMaterial.METAL.resistance;
    private static volatile Map<ResourceLocation, KineticPreset> presets = Map.of();
    private static volatile boolean bootstrapped = false;

    private KineticPresetRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    public static synchronized void register(KineticPreset preset) {
        Map<ResourceLocation, KineticPreset> next = new LinkedHashMap<>(presets);
        next.put(preset.id(), preset);
        presets = Collections.unmodifiableMap(next);
    }

    public static KineticPreset get(ResourceLocation id) {
        bootstrap();
        return presets.get(id);
    }

    public static boolean exists(ResourceLocation id) {
        bootstrap();
        return presets.containsKey(id);
    }

    public static Collection<KineticPreset> all() {
        bootstrap();
        return presets.values();
    }

    public static Set<ResourceLocation> ids() {
        bootstrap();
        return presets.keySet();
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }

    /** The round anything unrecognised flies as, so a shell is never a null preset. */
    public static KineticPreset fallback() {
        bootstrap();
        return presets.get(DEFAULT_ID);
    }

    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        synchronized (KineticPresetRegistry.class) {
            if (bootstrapped) {
                return;
            }
            registerDefaults();
            bootstrapped = true;
        }
    }

    private static void registerDefaults() {
        // A field gun's shell: the reference round every other one is a variation on.
        register(KineticPreset.builder(DEFAULT_ID, MissileModels.rl("micro"), WarheadRegistry.rl("standard"))
                .speed(6.0).mass(20.0).build());

        register(KineticPreset.builder(rl("ap"), MissileModels.rl("micro"), WarheadRegistry.rl("inert"))
                .speed(12.0).drag(0.005).mass(30.0).penetration(8, DRILLS_METAL).armorPenetration(550.0)
                .passesThroughEntities().build());

        // Shaped charge: a narrow jet along the impact heading, so it drills rather than craters.
        register(KineticPreset.builder(rl("heat"), MissileModels.rl("micro"), WarheadRegistry.rl("shaped_charge"))
                .speed(8.0).mass(22.0).penetration(2, DRILLS_METAL).blastHalfAngle(15.0).armorPenetration(450.0).build());

        // Airburst fragmentation: bursts short of the ground so the cone covers infantry in the open.
        register(KineticPreset.builder(rl("frag"), MissileModels.rl("micro"), WarheadRegistry.rl("fragmentation"))
                .speed(6.0).mass(18.0).fragments(24).airburst(8.0).build());

        register(KineticPreset.builder(rl("incendiary"), MissileModels.rl("micro"), FireWarhead.ID)
                .speed(6.0).mass(18.0).build());

        register(KineticPreset.builder(rl("chemical"), MissileModels.rl("micro_taint"), GasWarhead.ID)
                .speed(5.0).mass(18.0).airburst(6.0).build());

        register(KineticPreset.builder(rl("cluster"), MissileModels.rl("cluster_part"), RecursiveFrag.ID)
                .speed(6.0).mass(22.0).fragments(6).airburst(24.0).build());

        register(KineticPreset.builder(rl("emp"), MissileModels.rl("micro_emp"), WarheadRegistry.rl("emp"))
                .speed(6.0).mass(16.0).airburst(12.0).build());

        // Rocket assisted: less drag and more speed buy range, which is the whole point of one.
        register(KineticPreset.builder(rl("rap"), MissileModels.rl("micro"), WarheadRegistry.rl("standard"))
                .speed(9.0).drag(0.004).mass(24.0).life(4000).build());

        register(KineticPreset.builder(rl("nuclear"), MissileModels.rl("micro_schrab"), WarheadRegistry.rl("mininuke"))
                .speed(4.0).mass(60.0).build());

        register(KineticPreset.builder(rl("practice"), MissileModels.rl("micro"), WarheadRegistry.rl("inert"))
                .speed(6.0).mass(20.0).build());
    }
}
