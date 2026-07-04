package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileEntity.Phase;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class LaunchConfig {
    public ResourceLocation modelId = MissileModels.defaultId();
    public ResourceLocation warheadId = WarheadRegistry.defaultId();
    public boolean highAltitude;
    public double targetX;
    public double targetY;
    public double targetZ;
    public float explosionOffset;
    public double altitudeParam = 24.0;
    public int fragmentCount = MissileEntity.DEFAULT_FRAGMENT_COUNT;
    public double cruiseSpeed = MissileEntity.CRUISE_SPEED;
    public double turnRate;
    public float health = MissileEntity.DEFAULT_HEALTH;
    public boolean startInCruise;
    public boolean startArmed;
    public ResourceLocation ascentStageId;
    public ResourceLocation cruiseStageId;
    public ResourceLocation attackStageId;

    public void write(FriendlyByteBuf b) {
        b.writeResourceLocation(modelId);
        b.writeResourceLocation(warheadId);
        b.writeBoolean(highAltitude);
        b.writeDouble(targetX);
        b.writeDouble(targetY);
        b.writeDouble(targetZ);
        b.writeFloat(explosionOffset);
        b.writeDouble(altitudeParam);
        b.writeVarInt(fragmentCount);
        b.writeDouble(cruiseSpeed);
        b.writeDouble(turnRate);
        b.writeFloat(health);
        b.writeBoolean(startInCruise);
        b.writeBoolean(startArmed);
        b.writeResourceLocation(ascentStageId);
        b.writeResourceLocation(cruiseStageId);
        b.writeResourceLocation(attackStageId);
    }

    public static LaunchConfig read(FriendlyByteBuf b) {
        LaunchConfig c = new LaunchConfig();
        c.modelId = b.readResourceLocation();
        c.warheadId = b.readResourceLocation();
        c.highAltitude = b.readBoolean();
        c.targetX = b.readDouble();
        c.targetY = b.readDouble();
        c.targetZ = b.readDouble();
        c.explosionOffset = b.readFloat();
        c.altitudeParam = b.readDouble();
        c.fragmentCount = b.readVarInt();
        c.cruiseSpeed = b.readDouble();
        c.turnRate = b.readDouble();
        c.health = b.readFloat();
        c.startInCruise = b.readBoolean();
        c.startArmed = b.readBoolean();
        c.ascentStageId = b.readResourceLocation();
        c.cruiseStageId = b.readResourceLocation();
        c.attackStageId = b.readResourceLocation();
        return c;
    }

    public void save(CompoundTag tag) {
        tag.putString("Model", modelId.toString());
        tag.putString("Warhead", warheadId.toString());
        tag.putBoolean("HighAltitude", highAltitude);
        tag.putDouble("TargetX", targetX);
        tag.putDouble("TargetY", targetY);
        tag.putDouble("TargetZ", targetZ);
        tag.putFloat("ExplosionOffset", explosionOffset);
        tag.putDouble("AltitudeParam", altitudeParam);
        tag.putInt("FragmentCount", fragmentCount);
        tag.putDouble("CruiseSpeed", cruiseSpeed);
        tag.putDouble("TurnRate", turnRate);
        tag.putFloat("Health", health);
        tag.putBoolean("StartInCruise", startInCruise);
        tag.putBoolean("StartArmed", startArmed);
        if (ascentStageId != null) {
            tag.putString("AscentStage", ascentStageId.toString());
        }
        if (cruiseStageId != null) {
            tag.putString("CruiseStage", cruiseStageId.toString());
        }
        if (attackStageId != null) {
            tag.putString("AttackStage", attackStageId.toString());
        }
    }

    public static LaunchConfig load(CompoundTag tag) {
        LaunchConfig c = new LaunchConfig();
        c.modelId = MissileModels.parse(tag.getString("Model"));
        c.warheadId = WarheadRegistry.parse(tag.getString("Warhead"));
        c.highAltitude = tag.getBoolean("HighAltitude");
        c.targetX = tag.getDouble("TargetX");
        c.targetY = tag.getDouble("TargetY");
        c.targetZ = tag.getDouble("TargetZ");
        c.explosionOffset = tag.getFloat("ExplosionOffset");
        c.altitudeParam = tag.getDouble("AltitudeParam");
        c.fragmentCount = tag.getInt("FragmentCount");
        c.cruiseSpeed = tag.getDouble("CruiseSpeed");
        c.turnRate = tag.getDouble("TurnRate");
        c.health = tag.getFloat("Health");
        c.startInCruise = tag.getBoolean("StartInCruise");
        c.startArmed = tag.getBoolean("StartArmed");
        if (tag.contains("AscentStage")) {
            c.ascentStageId = FlightStageRegistry.parse(Phase.ASCEND, tag.getString("AscentStage"));
        }
        if (tag.contains("CruiseStage")) {
            c.cruiseStageId = FlightStageRegistry.parse(Phase.CRUISE, tag.getString("CruiseStage"));
        }
        if (tag.contains("AttackStage")) {
            c.attackStageId = FlightStageRegistry.parse(Phase.ATTACK, tag.getString("AttackStage"));
        }
        return c;
    }

    public void spawn(ServerLevel level, BlockPos pos, MissileDispenserBlockEntity dispenser) {
        ResourceLocation model = MissileModels.exists(modelId) ? modelId : MissileModels.defaultId();
        ResourceLocation warhead = WarheadRegistry.exists(warheadId) ? warheadId : WarheadRegistry.defaultId();
        double speed = cruiseSpeed > 1.0E-3 ? cruiseSpeed : MissileEntity.CRUISE_SPEED;
        int frags = Math.max(0, fragmentCount);
        float hp = health > 0.0f ? health : MissileEntity.DEFAULT_HEALTH;

        MissileEntity.Builder builder = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                .target(new Vec3(targetX, targetY, targetZ))
                .model(model)
                .detonation(warhead)
                .explosionOffset(explosionOffset)
                .fragmentCount(frags)
                .cruiseSpeed(speed)
                .health(hp)
                .controlId(dispenser.getControlId())
                .teamId(WarforgeCompat.factionClaiming(level, pos))
                .ascentStage(FlightStageRegistry.exists(Phase.ASCEND, ascentStageId)
                        ? ascentStageId : FlightStageRegistry.defaultId(Phase.ASCEND))
                .cruiseStage(FlightStageRegistry.exists(Phase.CRUISE, cruiseStageId)
                        ? cruiseStageId : FlightStageRegistry.defaultId(Phase.CRUISE))
                .attackStage(FlightStageRegistry.exists(Phase.ATTACK, attackStageId)
                        ? attackStageId : FlightStageRegistry.defaultId(Phase.ATTACK));
        if (highAltitude) {
            builder.highAltitude(altitudeParam);
        } else {
            builder.terrainFollow(altitudeParam);
        }
        if (turnRate > 0.0) {
            builder.turnRate(turnRate);
        }
        if (startInCruise) {
            builder.startInCruise();
        }
        if (startArmed) {
            builder.startArmed();
        }

        MissileEntity missile = builder.build();
        Vec3 spawn = Vec3.atCenterOf(pos).add(0.0, 2.0, 0.0);
        missile.moveTo(spawn.x, spawn.y, spawn.z, 0.0f, 0.0f);
        level.addFreshEntity(missile);
    }
}
