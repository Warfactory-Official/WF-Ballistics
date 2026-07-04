package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.sim.MissileSimConfig;

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

    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;


        register(MissilePreset.builder("cruise", "v2", "standard")
                .terrainFollow(24.0).cruiseSpeed(1.0).fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder("ballistic", "strong", "standard")
                .highAltitude(220.0).cruiseSpeed(1.2).health(60.0f)
                .fuel(MissileEntity.FuelType.SOLID, 2000).build());

        register(MissilePreset.builder("nuclear", "huge", "mininuke")
                .highAltitude(240.0).cruiseSpeed(1.0).health(90.0f)
                .fuel(MissileEntity.FuelType.SOLID, 2500).build());

        register(MissilePreset.builder("fragmentation", "atlas", "fragmentation")
                .highAltitude(200.0).explosionOffset(30.0f).fragmentCount(32)
                .fuel(MissileEntity.FuelType.SOLID, 1500).build());

        register(MissilePreset.builder("cluster", "micro", "recursive_frag")
                .terrainFollow(16.0).cruiseSpeed(1.5).splitDepth(2).fragmentCount(4)
                .explosionOffset(16.0f).fuel(MissileEntity.FuelType.SOLID, 1200).build());

        register(MissilePreset.builder("chemical", "taint", "gas")
                .highAltitude(250).explosionOffset(5.0f)
                .fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder("incendiary", "v2_incendiary", "fire")
                .terrainFollow(24.0).cruiseSpeed(1.0)
                .fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder("loiter", "shahed", "fragmentation")
                .terrainFollow(30.0).cruiseSpeed(0.8)
                .cruiseStage("loiter").attackStage("dive")
                .accel(0.08, 0.15).fuel(MissileEntity.FuelType.LIQUID, 2400)
                .fragmentCount(16).health(15.0f).build());

        register(MissilePreset.builder("supersonic", "thermo", "standard")
                .highAltitude(260.0).cruiseSpeed(6.0).health(60.0f)
                .accel(0.4, 0.5).fuel(MissileEntity.FuelType.SOLID, 1600).evasion(0.2f).build());

        register(MissilePreset.builder("supersonic_cruise", "neon", "standard")
                .terrainFollow(30.0).cruiseSpeed(5.0)
                .accel(0.4, 0.5).fuel(MissileEntity.FuelType.LIQUID, 1600).evasion(0.15f).build());


        register(MissilePreset.builder("hypersonic", "strong", "standard")
                .highAltitude(300.0).cruiseSpeed(12.0).health(60.0f)
                .accel(0.8, 0.9).fuel(MissileEntity.FuelType.SOLID, 2000).evasion(0.3f)
                .evasiveManeuver().build());

        register(MissilePreset.builder("marv", "atlas_thermo", "standard")
                .highAltitude(280.0).cruiseSpeed(7.0).health(70.0f)
                .accel(0.5, 0.6).fuel(MissileEntity.FuelType.SOLID, 1800).evasion(0.6f)
                .evasiveManeuver().build());


        register(MissilePreset.builder("stealth", "stealth", "standard")
                .terrainFollow(24.0).cruiseSpeed(1.2).stealth().evasion(0.3f)
                .fuel(MissileEntity.FuelType.LIQUID, 1600).build());

        register(MissilePreset.builder("emp", "stealth", "emp")
                .terrainFollow(24.0).cruiseSpeed(1.2).stealth().evasion(0.3f)
                .fuel(MissileEntity.FuelType.LIQUID, 1600).build());


        register(MissilePreset.builder("interceptor", "abm", "interceptor")
                .highAltitude(200.0)
                .cruiseSpeed(MissileSimConfig.INTERCEPTOR_ENTITY_SPEED)
                .turnRate(MissileSimConfig.INTERCEPTOR_TURN_RATE)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(0.6, 0.6).fuel(MissileEntity.FuelType.SOLID, 600).build());

        register(MissilePreset.builder("interceptor_supersonic", "abm", "interceptor")
                .highAltitude(220.0)
                .cruiseSpeed(MissileSimConfig.INTERCEPTOR_SUPERSONIC_ENTITY_SPEED)
                .turnRate(MissileSimConfig.INTERCEPTOR_SUPERSONIC_TURN_RATE)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(0.9, 0.9).fuel(MissileEntity.FuelType.SOLID, 700).build());

        register(MissilePreset.builder("interceptor_hypersonic", "abm", "interceptor")
                .highAltitude(260.0)
                .cruiseSpeed(18.0)
                .turnRate(0.45)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(1.3, 1.3).fuel(MissileEntity.FuelType.SOLID, 800).build());
    }
}
