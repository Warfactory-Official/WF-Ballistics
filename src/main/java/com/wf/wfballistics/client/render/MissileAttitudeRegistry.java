package com.wf.wfballistics.client.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of {@link MissileAttitude}s keyed by a short stable id. A missile model declares its attitude id in
 * {@code MissileModels}; the visual resolves it here. Mirrors the flight-stage/warhead registries: add a new
 * way to fly by implementing {@link MissileAttitude} and calling {@link #register}.
 */
public final class MissileAttitudeRegistry {

    public static final String DEFAULT_ID = "missile";

    private static final Map<String, MissileAttitude> BY_ID = new HashMap<>();

    static {
        register(DEFAULT_ID, NoseToVelocityAttitude.INSTANCE);
        register("drone", LevelDroneAttitude.INSTANCE);
    }

    private MissileAttitudeRegistry() {
    }

    public static void register(String id, MissileAttitude attitude) {
        BY_ID.put(id, attitude);
    }

    /**
     * @return the attitude for {@code id}, falling back to nose-to-velocity when unknown.
     */
    public static MissileAttitude get(String id) {
        return BY_ID.getOrDefault(id, NoseToVelocityAttitude.INSTANCE);
    }
}
