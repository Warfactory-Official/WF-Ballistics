package com.wf.wfballistics.api;

import net.minecraft.world.phys.Vec3;

public record MissileTelemetryEvent(MissileEventType type, long gameTime, double x, double y, double z,
                                    boolean simulated, String detail) {

    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    @Override
    public String toString() {
        String where = simulated ? "sim" : "flight";
        String suffix = (detail == null || detail.isEmpty()) ? "" : " (" + detail + ")";
        return String.format("t=%d %s %s @ (%.1f, %.1f, %.1f)%s", gameTime, type, where, x, y, z, suffix);
    }
}
