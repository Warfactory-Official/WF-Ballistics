package com.wf.wfballistics.item;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.warhead.FireWarhead;
import com.wf.wfballistics.warhead.GasWarhead;
import com.wf.wfballistics.warhead.RecursiveFrag;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of launch-ready {@link MissilePreset}s, keyed by {@link ResourceLocation}. Each registered preset
 * is turned into a carryable {@link MissileItem} by {@link ModItems}. Register your own during mod construction
 * (before items are frozen) with {@link #register}; the built-ins are added by {@link #bootstrap()}.
 *
 * <p>Registration order is preserved so the creative tab and item list stay stable.
 */
public final class MissilePresetRegistry {

    private static final ResourceLocation DEFAULT_ID = rl("cruise");
    private static final Map<ResourceLocation, MissilePreset> PRESETS = new LinkedHashMap<>();
    private static boolean bootstrapped = false;

    private MissilePresetRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return new ResourceLocation(WFBallistics.MODID, path);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID;
    }

    public static void register(MissilePreset preset) {
        PRESETS.put(preset.id(), preset);
    }

    public static MissilePreset get(ResourceLocation id) {
        return PRESETS.get(id);
    }

    public static boolean exists(ResourceLocation id) {
        return PRESETS.containsKey(id);
    }

    public static Collection<MissilePreset> all() {
        return Collections.unmodifiableCollection(PRESETS.values());
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(PRESETS.keySet());
    }

    public static ResourceLocation defaultId() {
        return DEFAULT_ID;
    }

    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;


        register(MissilePreset.builder(rl("cruise"), MissileModels.rl("v2"), WarheadRegistry.rl("standard"))
                .terrainFollow(24.0).cruiseSpeed(1.0).fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder(rl("ballistic"), MissileModels.rl("strong"), WarheadRegistry.rl("standard"))
                .highAltitude(220.0).cruiseSpeed(1.2).health(60.0f)
                .fuel(MissileEntity.FuelType.SOLID, 2000).build());

        register(MissilePreset.builder(rl("nuclear"), MissileModels.rl("huge"), WarheadRegistry.rl("mininuke"))
                .highAltitude(240.0).cruiseSpeed(1.0).health(90.0f)
                .fuel(MissileEntity.FuelType.SOLID, 2500).build());

        register(MissilePreset.builder(rl("fragmentation"), MissileModels.rl("atlas"), WarheadRegistry.rl("fragmentation"))
                .highAltitude(200.0).explosionOffset(30.0f).fragmentCount(32)
                .fuel(MissileEntity.FuelType.SOLID, 1500).build());

        register(MissilePreset.builder(rl("cluster"), MissileModels.rl("micro"), RecursiveFrag.ID)
                .terrainFollow(16.0).cruiseSpeed(1.5).splitDepth(2).fragmentCount(4)
                .explosionOffset(16.0f).fuel(MissileEntity.FuelType.SOLID, 1200).build());

        register(MissilePreset.builder(rl("chemical"), MissileModels.rl("taint"), GasWarhead.ID)
                .highAltitude(250).explosionOffset(5.0f)
                .fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder(rl("incendiary"), MissileModels.rl("v2_incendiary"), FireWarhead.ID)
                .terrainFollow(24.0).cruiseSpeed(1.0)
                .fuel(MissileEntity.FuelType.LIQUID, 1500).build());

        register(MissilePreset.builder(rl("loiter"), MissileModels.rl("shahed"), WarheadRegistry.rl("fragmentation"))
                .terrainFollow(30.0).cruiseSpeed(0.8)
                .cruiseStage(FlightStageRegistry.rl("loiter")).attackStage(FlightStageRegistry.rl("dive"))
                .accel(0.08, 0.15).fuel(MissileEntity.FuelType.LIQUID, 2400)
                .fragmentCount(16).health(15.0f).build());

        register(MissilePreset.builder(rl("supersonic"), MissileModels.rl("thermo"), WarheadRegistry.rl("standard"))
                .highAltitude(260.0).cruiseSpeed(6.0).health(60.0f)
                .accel(0.4, 0.5).fuel(MissileEntity.FuelType.SOLID, 1600).evasion(0.2f).build());

        register(MissilePreset.builder(rl("supersonic_cruise"), MissileModels.rl("neon"), WarheadRegistry.rl("standard"))
                .terrainFollow(30.0).cruiseSpeed(5.0)
                .accel(0.4, 0.5).fuel(MissileEntity.FuelType.LIQUID, 1600).evasion(0.15f).build());


        register(MissilePreset.builder(rl("hypersonic"), MissileModels.rl("neon"), WarheadRegistry.rl("standard"))
                .highAltitude(300.0).cruiseSpeed(12.0).health(60.0f)
                .accel(0.8, 0.9).fuel(MissileEntity.FuelType.SOLID, 2000).evasion(0.3f)
                .evasiveManeuver().build());

        register(MissilePreset.builder(rl("marv"), MissileModels.rl("atlas_thermo"), WarheadRegistry.rl("standard"))
                .highAltitude(280.0).cruiseSpeed(7.0).health(70.0f)
                .accel(0.5, 0.6).fuel(MissileEntity.FuelType.SOLID, 1800).evasion(0.6f)
                .evasiveManeuver().build());


        register(MissilePreset.builder(rl("stealth"), MissileModels.rl("stealth"), WarheadRegistry.rl("standard"))
                .terrainFollow(24.0).cruiseSpeed(1.2).stealth().evasion(0.3f)
                .fuel(MissileEntity.FuelType.LIQUID, 1600).build());

        register(MissilePreset.builder(rl("emp"), MissileModels.rl("stealth"), WarheadRegistry.rl("emp"))
                .terrainFollow(24.0).cruiseSpeed(1.2).stealth().evasion(0.3f)
                .fuel(MissileEntity.FuelType.LIQUID, 1600).build());


        register(MissilePreset.builder(rl("interceptor"), MissileModels.rl("abm"), WarheadRegistry.rl("interceptor"))
                .highAltitude(200.0)
                .cruiseSpeed(MissileSimConfig.INTERCEPTOR_ENTITY_SPEED)
                .turnRate(MissileSimConfig.INTERCEPTOR_TURN_RATE)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(0.6, 0.6).fuel(MissileEntity.FuelType.SOLID, 600).build());

        register(MissilePreset.builder(rl("interceptor_supersonic"), MissileModels.rl("abm"), WarheadRegistry.rl("interceptor"))
                .highAltitude(220.0)
                .cruiseSpeed(MissileSimConfig.INTERCEPTOR_SUPERSONIC_ENTITY_SPEED)
                .turnRate(MissileSimConfig.INTERCEPTOR_SUPERSONIC_TURN_RATE)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(0.9, 0.9).fuel(MissileEntity.FuelType.SOLID, 700).build());

        register(MissilePreset.builder(rl("interceptor_hypersonic"), MissileModels.rl("abm"), WarheadRegistry.rl("interceptor"))
                .highAltitude(260.0)
                .cruiseSpeed(18.0)
                .turnRate(0.45)
                .health(20.0f)
                .interceptor(MissileSimConfig.DEFAULT_INTERCEPT_CHANCE)
                .accel(1.3, 1.3).fuel(MissileEntity.FuelType.SOLID, 800).build());
    }
}
