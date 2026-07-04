package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * A composed flight: one {@link FlightStage} per {@link MissileEntity.Phase}. The missile runs the stage for
 * its current phase each tick (see {@link #stage}). Build new missile styles — a drone with a different ascent
 * curve, a new attack run — by composing a profile from different stages and registering it in
 * {@link FlightProfileRegistry}.
 */
public final class FlightProfile {

    private final EnumMap<MissileEntity.Phase, FlightStage> stages;

    public FlightProfile(Map<MissileEntity.Phase, FlightStage> stages) {
        this.stages = new EnumMap<>(stages);
    }

    /**
     * Compose a profile from the three canonical stages.
     */
    public static FlightProfile of(FlightStage ascent, FlightStage cruise, FlightStage attack) {
        EnumMap<MissileEntity.Phase, FlightStage> map = new EnumMap<>(MissileEntity.Phase.class);
        map.put(MissileEntity.Phase.ASCEND, ascent);
        map.put(MissileEntity.Phase.CRUISE, cruise);
        map.put(MissileEntity.Phase.ATTACK, attack);
        return new FlightProfile(map);
    }

    /**
     * The default guided cruise missile: vertical boost -> terrain/high-altitude cruise -> terminal dive.
     */
    public static FlightProfile standard() {
        return of(AscentStage.INSTANCE, CruiseStage.INSTANCE, AttackStage.INSTANCE);
    }

    /**
     * Compose a profile by resolving one stage id per phase through the {@link FlightStageRegistry}.
     */
    public static FlightProfile fromIds(ResourceLocation ascentId, ResourceLocation cruiseId, ResourceLocation attackId) {
        return of(FlightStageRegistry.get(MissileEntity.Phase.ASCEND, ascentId),
                FlightStageRegistry.get(MissileEntity.Phase.CRUISE, cruiseId),
                FlightStageRegistry.get(MissileEntity.Phase.ATTACK, attackId));
    }

    /**
     * @return the stage that flies the given phase.
     */
    public FlightStage stage(MissileEntity.Phase phase) {
        return this.stages.get(phase);
    }
}
