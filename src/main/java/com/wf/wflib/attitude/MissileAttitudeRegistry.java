package com.wf.wflib.attitude;

import java.util.HashMap;
import java.util.Map;

public final class MissileAttitudeRegistry {

    public static final String DEFAULT_ID = "missile";

    private static final Map<String, MissileAttitude> BY_ID = new HashMap<>();

    static {
        register(DEFAULT_ID, NoseToVelocityAttitude.INSTANCE);
        register("drone", LevelDroneAttitude.INSTANCE);
        register("copter", CopterAttitude.INSTANCE);
    }

    private MissileAttitudeRegistry() {
    }

    public static void register(String id, MissileAttitude attitude) {
        BY_ID.put(id, attitude);
    }

    public static MissileAttitude get(String id) {
        return BY_ID.getOrDefault(id, NoseToVelocityAttitude.INSTANCE);
    }
}
