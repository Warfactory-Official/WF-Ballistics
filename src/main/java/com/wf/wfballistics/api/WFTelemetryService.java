package com.wf.wfballistics.api;

import com.wf.wfballistics.debug.MissileDebug;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class WFTelemetryService {

    public static final int MAX_TRACKED = 128;

    private static final LinkedHashMap<UUID, WFTelemetry> TRACKED = new LinkedHashMap<>();

    private static volatile boolean autoOpen = false;

    private WFTelemetryService() {
    }

    public static boolean autoOpen() {
        return autoOpen;
    }

    public static void setAutoOpen(boolean value) {
        autoOpen = value;
    }

    public static WFTelemetry open(UUID id, long gameTime) {
        WFTelemetry existing = TRACKED.get(id);
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
        WFTelemetry created = new WFTelemetry(id, gameTime);
        TRACKED.put(id, created);
        return created;
    }

    public static WFTelemetry get(UUID id) {
        return TRACKED.get(id);
    }

    public static boolean isTracked(UUID id) {
        return TRACKED.containsKey(id);
    }

    public static Collection<WFTelemetry> all() {
        return new ArrayList<>(TRACKED.values());
    }

    public static void close(UUID id) {
        TRACKED.remove(id);
    }

    public static void clear() {
        TRACKED.clear();
    }

    public static void record(WFTelemetry cached, UUID id, WFEventType type, long gameTime, Vec3 pos,
                              boolean simulated, String detail) {
        if (cached != null) {
            cached.record(type, gameTime, pos, simulated, detail);
        }
        MissileDebug.onEvent(id, type, gameTime, pos, simulated, detail);
    }

    public static void record(UUID id, WFEventType type, long gameTime, Vec3 pos, boolean simulated,
                              String detail) {
        record(TRACKED.get(id), id, type, gameTime, pos, simulated, detail);
    }
}
