package com.wf.wfballistics.api;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class WFTelemetry {

    public static final int MAX_EVENTS = 256;

    private final UUID id;
    private final long openedAt;
    private final ArrayDeque<WFTelemetryEvent> events = new ArrayDeque<>();
    private WFTelemetryEvent latest;
    private int total;

    WFTelemetry(UUID id, long openedAt) {
        this.id = id;
        this.openedAt = openedAt;
    }

    public UUID id() {
        return id;
    }

    public long openedAt() {
        return openedAt;
    }

    public int total() {
        return total;
    }

    void record(WFEventType type, long gameTime, Vec3 pos, boolean simulated, String detail) {
        WFTelemetryEvent event = new WFTelemetryEvent(type, gameTime, pos.x, pos.y, pos.z, simulated,
                detail == null ? "" : detail);
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(event);
        latest = event;
        total++;
    }

    public WFTelemetryEvent latest() {
        return latest;
    }

    public List<WFTelemetryEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
