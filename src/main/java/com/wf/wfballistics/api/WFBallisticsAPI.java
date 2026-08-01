package com.wf.wfballistics.api;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.sim.SimMissile;
import com.wf.wfballistics.sim.SimMissileRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WFBallisticsAPI {

    private WFBallisticsAPI() {
    }

    public static MissileTelemetry openTelemetry(MissileEntity missile) {
        if (missile.level().isClientSide) {
            return null;
        }
        MissileTelemetry telemetry = MissileTelemetryService.open(missile.getUUID(), missile.level().getGameTime());
        missile.attachTelemetry(telemetry);
        return telemetry;
    }

    public static MissileTelemetry openTelemetry(UUID id, long gameTime) {
        return MissileTelemetryService.open(id, gameTime);
    }

    public static MissileTelemetry getTelemetry(UUID id) {
        return MissileTelemetryService.get(id);
    }

    /**
     * Open a telemetry queue for every launched missile automatically, independent of the debug toggle. Lets an
     * addon flip one switch and then read {@link #getTelemetry} for any launch. Queues are bounded (oldest
     * evicted past {@link MissileTelemetryService#MAX_TRACKED}), so consume them while the missile is live.
     */
    public static void setAutoTelemetry(boolean enabled) {
        MissileTelemetryService.setAutoOpen(enabled);
    }

    public static boolean isAutoTelemetry() {
        return MissileTelemetryService.autoOpen();
    }

    public static MissileData getMissileData(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof MissileEntity missile) {
            return fromEntity(missile);
        }
        SimMissile sm = SimMissileRegistry.get(level).getById(id);
        return sm == null ? null : fromSim(sm);
    }

    public static List<MissileData> listSimMissiles(ServerLevel level) {
        List<MissileData> out = new ArrayList<>();
        for (SimMissile sm : SimMissileRegistry.get(level).view()) {
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
        String phase = m.isInterceptor() ? "INTERCEPTOR" : m.getPhase().name();
        return new MissileData(m.getUUID(), false, m.getModelId().getPath(), phase,
                m.position(), m.getTarget(), m.getCruiseSpeed(), m.getFuel(), m.getFuelCapacity());
    }

    public static MissileData fromSim(SimMissile sm) {
        String phase = sm.role == SimMissile.Role.INTERCEPTOR ? "SIM/INT" : "SIM/CRUISE";
        return new MissileData(sm.id, true, sm.modelId.getPath(), phase,
                sm.pos, sm.target, sm.speed, sm.fuel, sm.fuelCapacity);
    }
}
