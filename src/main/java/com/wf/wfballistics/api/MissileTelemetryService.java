package com.wf.wfballistics.api;

import com.wf.wfballistics.debug.MissileDebug;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class MissileTelemetryService {

    public static final int MAX_TRACKED = 128;

    private static final LinkedHashMap<UUID, MissileTelemetry> TRACKED = new LinkedHashMap<>();

    private static volatile boolean autoOpen = false;

    private MissileTelemetryService() {
    }

    public static boolean autoOpen() {
        return autoOpen;
    }

    public static void setAutoOpen(boolean value) {
        autoOpen = value;
    }

    public static MissileTelemetry open(UUID id, long gameTime) {
        MissileTelemetry existing = TRACKED.get(id);
        if (existing != null) {
            return existing;
        }
        if (TRACKED.size() >= MAX_TRACKED) {
            Iterator<UUID> it = TRACKED.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        MissileTelemetry created = new MissileTelemetry(id, gameTime);
        TRACKED.put(id, created);
        return created;
    }

    public static MissileTelemetry get(UUID id) {
        return TRACKED.get(id);
    }

    public static boolean isTracked(UUID id) {
        return TRACKED.containsKey(id);
    }

    public static Collection<MissileTelemetry> all() {
        return new ArrayList<>(TRACKED.values());
    }

    public static void close(UUID id) {
        TRACKED.remove(id);
    }

    public static void clear() {
        TRACKED.clear();
    }

    public static void record(MissileTelemetry cached, UUID id, MissileEventType type, long gameTime, Vec3 pos,
                              boolean simulated, String detail) {
        if (cached != null) {
            cached.record(type, gameTime, pos, simulated, detail);
        }
        MissileDebug.onEvent(id, type, gameTime, pos, simulated, detail);
    }

    public static void record(UUID id, MissileEventType type, long gameTime, Vec3 pos, boolean simulated,
                              String detail) {
        record(TRACKED.get(id), id, type, gameTime, pos, simulated, detail);
    }
}
