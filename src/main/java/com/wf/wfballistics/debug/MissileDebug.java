package com.wf.wfballistics.debug;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.api.MissileData;
import com.wf.wfballistics.api.MissileEventType;
import com.wf.wfballistics.api.WFBallisticsAPI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.UUID;

public final class MissileDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int POSITION_LOG_INTERVAL = 20;

    private static volatile boolean enabled = false;
    private static volatile UUID latest;
    private static int positionCounter;

    private MissileDebug() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        LOGGER.info("[wf-debug] missile debug logging {}", value ? "ENABLED" : "disabled");
    }

    public static void configureDefault(boolean value) {
        enabled = value;
    }

    public static UUID latest() {
        return latest;
    }

    public static void track(UUID id) {
        latest = id;
        positionCounter = 0;
        LOGGER.info("[wf-debug] now tracking missile {}", id);
    }

    public static void markLatest(UUID id) {
        latest = id;
        positionCounter = 0;
    }

    public static void onEvent(UUID id, MissileEventType type, long gameTime, Vec3 pos, boolean simulated, String detail) {
        if (!enabled) {
            return;
        }
        String marker = id.equals(latest) ? "*" : " ";
        String where = simulated ? "sim" : "flight";
        String suffix = (detail == null || detail.isEmpty()) ? "" : " " + detail;
        LOGGER.info("[wf-debug]{} {} {} {} @ ({}, {}, {}){}", marker, shortId(id), type, where,
                f1(pos.x), f1(pos.y), f1(pos.z), suffix);
    }

    public static void tickLatest(ServerLevel level, long now) {
        if (!enabled || latest == null || now % POSITION_LOG_INTERVAL != 0) {
            return;
        }
        MissileData data = WFBallisticsAPI.getMissileData(level, latest);
        if (data == null) {
            return;
        }
        positionCounter++;
        LOGGER.info("[wf-debug] #{} {} {} pos=({}, {}, {}) dist={} fuel={}/{} range={} reach={}",
                positionCounter, shortId(latest), data.phase(),
                f1(data.pos().x), f1(data.pos().y), f1(data.pos().z),
                f1(data.horizontalDistance()), data.fuel(), data.fuelCapacity(),
                f1(data.poweredRange()), data.canReach() ? "YES" : "NO");
    }

    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String f1(double v) {
        return String.format("%.1f", v);
    }
}
