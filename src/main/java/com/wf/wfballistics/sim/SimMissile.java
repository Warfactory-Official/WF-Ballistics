package com.wf.wfballistics.sim;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;


public final class SimMissile {
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
    public ResourceLocation detonationId = WarheadRegistry.defaultId();
    public ResourceLocation ascentStageId = null;
    public ResourceLocation cruiseStageId = null;
    public ResourceLocation attackStageId = null;
    public int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
    public int splitDepth = 0;
    public long swarmId = 0L;
    public UUID controlId = null;
    public UUID teamId = null;
    // Interception.
    public Role role = Role.NORMAL;
    public UUID interceptTarget = null;
    public float interceptChance = MissileSimConfig.DEFAULT_INTERCEPT_CHANCE;
    // Propulsion & fuel (carried so an offloaded missile keeps burning down and reconstructs correctly).
    public MissileEntity.FuelType fuelType = MissileEntity.FuelType.SOLID;
    public int fuel = MissileEntity.DEFAULT_FUEL_TICKS;
    public int fuelCapacity = MissileEntity.DEFAULT_FUEL_TICKS;
    public double acceleration = MissileEntity.DEFAULT_ACCELERATION;
    public double deceleration = MissileEntity.DEFAULT_DECELERATION;
    public boolean stealth = false;
    public float evasion = 0.0f;

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
        sm.teamId = m.getTeamId();
        sm.interceptChance = m.getInterceptChance();
        sm.fuelType = m.getFuelType();
        sm.fuel = m.getFuel();
        sm.fuelCapacity = m.getFuelCapacity();
        sm.acceleration = m.getAcceleration();
        sm.deceleration = m.getDeceleration();
        sm.stealth = m.isStealth();
        sm.evasion = m.getEvasion();
        sm.role = Role.NORMAL;
        return sm;
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
            sm.detonationId = WarheadRegistry.parse(tag.getString("DetonationId"));
        }
        if (tag.contains("AscentStage")) {
            sm.ascentStageId = FlightStageRegistry.parse(MissileEntity.Phase.ASCEND, tag.getString("AscentStage"));
        }
        if (tag.contains("CruiseStage")) {
            sm.cruiseStageId = FlightStageRegistry.parse(MissileEntity.Phase.CRUISE, tag.getString("CruiseStage"));
        }
        if (tag.contains("AttackStage")) {
            sm.attackStageId = FlightStageRegistry.parse(MissileEntity.Phase.ATTACK, tag.getString("AttackStage"));
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
        if (tag.hasUUID("TeamId")) {
            sm.teamId = tag.getUUID("TeamId");
        }
        try {
            sm.role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
        }
        if (tag.hasUUID("InterceptTarget")) {
            sm.interceptTarget = tag.getUUID("InterceptTarget");
        }
        if (tag.contains("InterceptChance")) {
            sm.interceptChance = tag.getFloat("InterceptChance");
        }
        if (tag.contains("FuelType")) {
            try {
                sm.fuelType = MissileEntity.FuelType.valueOf(tag.getString("FuelType"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (tag.contains("Fuel")) {
            sm.fuel = tag.getInt("Fuel");
        }
        if (tag.contains("FuelCapacity")) {
            sm.fuelCapacity = tag.getInt("FuelCapacity");
        }
        if (tag.contains("Acceleration")) {
            sm.acceleration = tag.getDouble("Acceleration");
        }
        if (tag.contains("Deceleration")) {
            sm.deceleration = tag.getDouble("Deceleration");
        }
        sm.stealth = tag.getBoolean("Stealth");
        if (tag.contains("Evasion")) {
            sm.evasion = tag.getFloat("Evasion");
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
                .teamId(this.teamId)
                .fuel(this.fuelType, this.fuel) // preserve remaining fuel across the offload/respawn
                .acceleration(this.acceleration)
                .deceleration(this.deceleration)
                .stealth(this.stealth)
                .evasion(this.evasion)
                .startInCruise()
                .startArmed(); // a simulated missile has already flown clear of its launcher

        // A simulated interceptor rematerializes as a real interceptor entity, resuming its LOCK on the
        // target so the in-world closest-approach roll (MissileEntity.tryIntercept) finishes the kill.
        if (this.role == Role.INTERCEPTOR && this.interceptTarget != null) {
            b.interceptor(true).lockTarget(this.interceptTarget).interceptChance(this.interceptChance);
        }

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
        tag.putString("DetonationId", detonationId.toString());
        if (ascentStageId != null) {
            tag.putString("AscentStage", ascentStageId.toString());
        }
        if (cruiseStageId != null) {
            tag.putString("CruiseStage", cruiseStageId.toString());
        }
        if (attackStageId != null) {
            tag.putString("AttackStage", attackStageId.toString());
        }
        tag.putInt("FragmentCount", fragmentCount);
        tag.putInt("SplitDepth", splitDepth);
        tag.putLong("SwarmId", swarmId);
        if (controlId != null) {
            tag.putUUID("ControlId", controlId);
        }
        if (teamId != null) {
            tag.putUUID("TeamId", teamId);
        }
        tag.putString("Role", role.name());
        if (interceptTarget != null) {
            tag.putUUID("InterceptTarget", interceptTarget);
        }
        tag.putFloat("InterceptChance", interceptChance);
        tag.putString("FuelType", fuelType.name());
        tag.putInt("Fuel", fuel);
        tag.putInt("FuelCapacity", fuelCapacity);
        tag.putDouble("Acceleration", acceleration);
        tag.putDouble("Deceleration", deceleration);
        tag.putBoolean("Stealth", stealth);
        tag.putFloat("Evasion", evasion);
        return tag;
    }

    public enum Role {
        NORMAL,
        INTERCEPTOR
    }
}
