package com.wf.wflib.api;

import com.wf.wflib.sim.SimMissileManager;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.sim.SimMissile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WFLibAPI {

    private WFLibAPI() {
    }

    public static WFTelemetry openTelemetry(MissileEntity missile) {
        if (missile.level().isClientSide) {
            return null;
        }
        WFTelemetry telemetry = WFTelemetryService.open(missile.getUUID(), missile.level().getGameTime());
        missile.attachTelemetry(telemetry);
        return telemetry;
    }

    public static WFTelemetry openTelemetry(UUID id, long gameTime) {
        return WFTelemetryService.open(id, gameTime);
    }

    public static WFTelemetry getTelemetry(UUID id) {
        return WFTelemetryService.get(id);
    }

    /** Open a telemetry queue for every launched missile automatically, independent of the debug toggle. */
    public static void setAutoTelemetry(boolean enabled) {
        WFTelemetryService.setAutoOpen(enabled);
    }

    public static boolean isAutoTelemetry() {
        return WFTelemetryService.autoOpen();
    }

    public static MissileData getMissileData(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof MissileEntity missile) {
            return fromEntity(missile);
        }
        SimMissile sm = SimMissileManager.find(level, id);
        return sm == null ? null : fromSim(sm);
    }

    public static List<MissileData> listSimMissiles(ServerLevel level) {
        List<MissileData> out = new ArrayList<>();
        for (SimMissile sm : SimMissileManager.tier(level).view()) {
            out.add(fromSim(sm));
        }
        return out;
    }

    public static List<MissileData> listRealMissiles(ServerLevel level) {
        List<MissileData> out = new ArrayList<>();
        for (MissileEntity missile : level.getEntities(EntityTypeTest.forClass(MissileEntity.class), MissileEntity::isAlive)) {
            out.add(fromEntity(missile));
        }
        return out;
    }

    public static List<MissileData> listActiveMissiles(ServerLevel level) {
        List<MissileData> out = listRealMissiles(level);
        out.addAll(listSimMissiles(level));
        return out;
    }

    public static MissileData fromEntity(MissileEntity m) {
        String phase = m.interceptor().isActive() ? "INTERCEPTOR" : m.flight().getPhase().name();
        return new MissileData(m.getUUID(), false, m.getModelId().getPath(), phase,
                m.position(), m.flight().getTarget(), m.flight().getCruiseSpeed(), m.motor().getFuel(), m.motor().getFuelCapacity());
    }

    public static MissileData fromSim(SimMissile sm) {
        String phase = sm.role == SimMissile.Role.INTERCEPTOR ? "SIM/INT" : "SIM/CRUISE";
        return new MissileData(sm.id, true, sm.modelId.getPath(), phase,
                sm.pos, sm.target, sm.speed, sm.fuel, sm.fuelCapacity);
    }
}
