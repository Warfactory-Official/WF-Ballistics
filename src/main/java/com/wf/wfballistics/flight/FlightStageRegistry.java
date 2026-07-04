package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity.Phase;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public final class FlightStageRegistry {

    private static final Map<Phase, Map<ResourceLocation, FlightStage>> BY_PHASE = new EnumMap<>(Phase.class);
    private static final Map<Phase, ResourceLocation> DEFAULT_ID = new EnumMap<>(Phase.class);

    static {
        register(Phase.ASCEND, AscentStage.INSTANCE);
        register(Phase.CRUISE, CruiseStage.INSTANCE);
        register(Phase.CRUISE, LoiterStage.INSTANCE);
        register(Phase.ATTACK, AttackStage.INSTANCE);
        register(Phase.ATTACK, VerticalDiveStage.INSTANCE);
        register(Phase.ASCEND, InterceptStage.INSTANCE);
        register(Phase.CRUISE, InterceptStage.INSTANCE);
        register(Phase.ATTACK, InterceptStage.INSTANCE);
    }

    private FlightStageRegistry() {
    }

    public static ResourceLocation rl(String path) {
        return new ResourceLocation(WFBallistics.MODID, path);
    }

    public static ResourceLocation keyOf(FlightStage stage) {
        return rl(stage.id());
    }

    public static ResourceLocation parse(Phase phase, String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT_ID.get(phase);
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null ? parsed : DEFAULT_ID.get(phase);
    }

    public static void register(Phase phase, FlightStage stage) {
        ResourceLocation id = keyOf(stage);
        BY_PHASE.computeIfAbsent(phase, p -> new LinkedHashMap<>()).put(id, stage);
        DEFAULT_ID.putIfAbsent(phase, id);
    }

    public static FlightStage get(Phase phase, ResourceLocation id) {
        Map<ResourceLocation, FlightStage> byId = BY_PHASE.get(phase);
        if (byId == null) {
            return null;
        }
        FlightStage stage = byId.get(id);
        return stage != null ? stage : byId.get(DEFAULT_ID.get(phase));
    }

    public static boolean exists(Phase phase, ResourceLocation id) {
        Map<ResourceLocation, FlightStage> byId = BY_PHASE.get(phase);
        return byId != null && byId.containsKey(id);
    }

    public static ResourceLocation defaultId(Phase phase) {
        return DEFAULT_ID.get(phase);
    }

    public static Set<ResourceLocation> ids(Phase phase) {
        Map<ResourceLocation, FlightStage> byId = BY_PHASE.get(phase);
        return byId != null ? Collections.unmodifiableSet(byId.keySet()) : Set.of();
    }
}
