package com.wf.wfballistics.flight;

import com.wf.wfballistics.MissileEntity.Phase;

import java.util.*;

/**
 * Registry of {@link FlightStage}s, grouped by the {@link Phase} they fly. A missile stores one stage id per
 * phase and composes them into its {@link FlightProfile}, so its ascent curve, cruise/loiter behaviour and
 * attack run can each be swapped independently (and persisted / picked in the emitter GUI). Add-ons register
 * their own stages via {@link #register}; the first stage registered for a phase is that phase's default.
 */
public final class FlightStageRegistry {

    private static final Map<Phase, Map<String, FlightStage>> BY_PHASE = new EnumMap<>(Phase.class);
    private static final Map<Phase, String> DEFAULT_ID = new EnumMap<>(Phase.class);

    static {
        register(Phase.ASCEND, AscentStage.INSTANCE);
        register(Phase.CRUISE, CruiseStage.INSTANCE);
        register(Phase.CRUISE, LoiterStage.INSTANCE);
        register(Phase.ATTACK, AttackStage.INSTANCE);
        register(Phase.ATTACK, VerticalDiveStage.INSTANCE);
        // Interceptor homing: registered for every phase so an all-"intercept" interceptor keeps homing in
        // any phase and survives the id-based FlightProfile rebuild on reload (see InterceptStage).
        register(Phase.ASCEND, InterceptStage.INSTANCE);
        register(Phase.CRUISE, InterceptStage.INSTANCE);
        register(Phase.ATTACK, InterceptStage.INSTANCE);
    }

    private FlightStageRegistry() {
    }

    /**
     * Register a stage for a phase; the first one registered for a phase becomes that phase's default.
     */
    public static void register(Phase phase, FlightStage stage) {
        BY_PHASE.computeIfAbsent(phase, p -> new LinkedHashMap<>()).put(stage.id(), stage);
        DEFAULT_ID.putIfAbsent(phase, stage.id());
    }

    /**
     * @return the stage for {@code (phase, id)}, falling back to the phase's default when unknown.
     */
    public static FlightStage get(Phase phase, String id) {
        Map<String, FlightStage> byId = BY_PHASE.get(phase);
        if (byId == null) {
            return null;
        }
        FlightStage stage = byId.get(id);
        return stage != null ? stage : byId.get(DEFAULT_ID.get(phase));
    }

    public static boolean exists(Phase phase, String id) {
        Map<String, FlightStage> byId = BY_PHASE.get(phase);
        return byId != null && byId.containsKey(id);
    }

    public static String defaultId(Phase phase) {
        return DEFAULT_ID.get(phase);
    }

    /**
     * @return all stage ids registered for a phase, in registration order.
     */
    public static Set<String> ids(Phase phase) {
        Map<String, FlightStage> byId = BY_PHASE.get(phase);
        return byId != null ? Collections.unmodifiableSet(byId.keySet()) : Set.of();
    }
}
