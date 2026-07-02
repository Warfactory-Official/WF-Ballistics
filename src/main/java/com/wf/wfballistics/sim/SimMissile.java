package com.wf.wfballistics.sim;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;


public final class SimMissile {
    public enum Role {
        NORMAL,
        INTERCEPTOR
    }

    public UUID id;
    public Vec3 pos;
    public Vec3 target;
    public double simY;
    public double speed;
    public long lastGameTime;

    // Reconstruction parameters (mirror MissileEntity flight state).
    public MissileEntity.CruiseMode cruiseMode = MissileEntity.CruiseMode.TERRAIN_FOLLOW;
    public double cruiseAltitude = 200.0;
    public double terrainClearance = 24.0;
    public float explosionOffset = 0.0f;
    public double maxTurnRate = 0.0;
    public String modelId = "";
    public String detonationId = "standard";
    public String ascentStageId = null;
    public String cruiseStageId = null;
    public String attackStageId = null;
    public int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
    public int splitDepth = 0;
    public long swarmId = 0L;
    public UUID controlId = null;

    // Interception.
    public Role role = Role.NORMAL;
    public UUID interceptTarget = null;

    public static SimMissile fromEntity(MissileEntity m) {
        SimMissile sm = new SimMissile();
        sm.id = m.getUUID();
        sm.pos = m.position();
        sm.target = m.getTarget();
        sm.simY = Math.max(m.getY(), MissileSimConfig.SIM_ALTITUDE_FLOOR);
        sm.speed = m.getCruiseSpeed();
        sm.lastGameTime = m.level().getGameTime();
        sm.cruiseMode = m.getCruiseMode();
        sm.cruiseAltitude = m.getCruiseAltitude();
        sm.terrainClearance = m.getTerrainClearance();
        sm.explosionOffset = m.getExplosionOffset();
        sm.maxTurnRate = m.getMaxTurnRate();
        sm.modelId = m.getModelId();
        sm.detonationId = m.getDetonationId();
        sm.ascentStageId = m.getAscentStageId();
        sm.cruiseStageId = m.getCruiseStageId();
        sm.attackStageId = m.getAttackStageId();
        sm.fragmentCount = m.getFragmentCount();
        sm.splitDepth = m.getSplitDepth();
        sm.swarmId = m.getSwarmId();
        sm.controlId = m.getControlId();
        sm.role = Role.NORMAL;
        return sm;
    }

    public MissileEntity toEntity(ServerLevel level, Vec3 spawnPos) {
        MissileEntity.Builder b = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(this.target);
        if (this.cruiseMode == MissileEntity.CruiseMode.HIGH_ALTITUDE) {
            b.highAltitude(this.cruiseAltitude);
        } else {
            b.terrainFollow(this.terrainClearance);
        }
        b.explosionOffset(this.explosionOffset)
                .turnRate(this.maxTurnRate)
                .cruiseSpeed(this.speed)
                .model(this.modelId)
                .detonation(this.detonationId)
                .ascentStage(this.ascentStageId)
                .cruiseStage(this.cruiseStageId)
                .attackStage(this.attackStageId)
                .fragmentCount(this.fragmentCount)
                .splitDepth(this.splitDepth)
                .swarmId(this.swarmId)
                .controlId(this.controlId)
                .startInCruise()
                .startArmed(); // a simulated missile has already flown clear of its launcher

        MissileEntity m = b.build();
        m.setUUID(this.id);
        m.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, m.getYRot(), m.getXRot());
        return m;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        putVec(tag, "Pos", pos);
        putVec(tag, "Target", target);
        tag.putDouble("SimY", simY);
        tag.putDouble("Speed", speed);
        tag.putLong("LastGameTime", lastGameTime);
        tag.putString("CruiseMode", cruiseMode.name());
        tag.putDouble("CruiseAltitude", cruiseAltitude);
        tag.putDouble("TerrainClearance", terrainClearance);
        tag.putFloat("ExplosionOffset", explosionOffset);
        tag.putDouble("MaxTurnRate", maxTurnRate);
        tag.putString("ModelId", modelId);
        tag.putString("DetonationId", detonationId);
        if (ascentStageId != null) {
            tag.putString("AscentStage", ascentStageId);
        }
        if (cruiseStageId != null) {
            tag.putString("CruiseStage", cruiseStageId);
        }
        if (attackStageId != null) {
            tag.putString("AttackStage", attackStageId);
        }
        tag.putInt("FragmentCount", fragmentCount);
        tag.putInt("SplitDepth", splitDepth);
        tag.putLong("SwarmId", swarmId);
        if (controlId != null) {
            tag.putUUID("ControlId", controlId);
        }
        tag.putString("Role", role.name());
        if (interceptTarget != null) {
            tag.putUUID("InterceptTarget", interceptTarget);
        }
        return tag;
    }

    public static SimMissile load(CompoundTag tag) {
        SimMissile sm = new SimMissile();
        sm.id = tag.getUUID("Id");
        sm.pos = getVec(tag, "Pos");
        sm.target = getVec(tag, "Target");
        sm.simY = tag.getDouble("SimY");
        sm.speed = tag.getDouble("Speed");
        sm.lastGameTime = tag.getLong("LastGameTime");
        try {
            sm.cruiseMode = MissileEntity.CruiseMode.valueOf(tag.getString("CruiseMode"));
        } catch (IllegalArgumentException ignored) {
        }
        sm.cruiseAltitude = tag.getDouble("CruiseAltitude");
        sm.terrainClearance = tag.getDouble("TerrainClearance");
        sm.explosionOffset = tag.getFloat("ExplosionOffset");
        sm.maxTurnRate = tag.getDouble("MaxTurnRate");
        sm.modelId = tag.getString("ModelId");
        if (tag.contains("DetonationId")) {
            sm.detonationId = tag.getString("DetonationId");
        }
        if (tag.contains("AscentStage")) {
            sm.ascentStageId = tag.getString("AscentStage");
        }
        if (tag.contains("CruiseStage")) {
            sm.cruiseStageId = tag.getString("CruiseStage");
        }
        if (tag.contains("AttackStage")) {
            sm.attackStageId = tag.getString("AttackStage");
        }
        if (tag.contains("FragmentCount")) {
            sm.fragmentCount = tag.getInt("FragmentCount");
        }
        if (tag.contains("SplitDepth")) {
            sm.splitDepth = tag.getInt("SplitDepth");
        }
        sm.swarmId = tag.getLong("SwarmId");
        if (tag.hasUUID("ControlId")) {
            sm.controlId = tag.getUUID("ControlId");
        }
        try {
            sm.role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
        }
        if (tag.hasUUID("InterceptTarget")) {
            sm.interceptTarget = tag.getUUID("InterceptTarget");
        }
        return sm;
    }

    private static void putVec(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "X", v.x);
        tag.putDouble(key + "Y", v.y);
        tag.putDouble(key + "Z", v.z);
    }

    private static Vec3 getVec(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }
}
