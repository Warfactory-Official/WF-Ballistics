package com.wf.wfballistics.network;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileEntity.Phase;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import com.wf.wfballistics.flight.FlightStageRegistry;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;


public class SpawnMissilePacket {
    // A generous cap so the debug launcher stays a launcher, not a remote artillery exploit.
    private static final double MAX_USE_DISTANCE_SQR = 64.0 * 64.0;

    private final BlockPos pos;
    private final String modelId;
    private final String warheadId;
    private final boolean highAltitude;
    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final float explosionOffset;
    private final double altitudeParam; // cruiseAltitude (high) or terrainClearance (terrain follow)
    private final int fragmentCount;
    private final double cruiseSpeed;
    private final double turnRate;      // <= 0 means "use the model-size default"
    private final float health;         // <= 0 means "use the default health pool"
    private final boolean startInCruise;
    private final boolean startArmed;
    private final String ascentStageId;
    private final String cruiseStageId;
    private final String attackStageId;

    public SpawnMissilePacket(BlockPos pos, String modelId, String warheadId, boolean highAltitude,
                              double targetX, double targetY, double targetZ, float explosionOffset,
                              double altitudeParam, int fragmentCount, double cruiseSpeed, double turnRate,
                              float health, boolean startInCruise, boolean startArmed,
                              String ascentStageId, String cruiseStageId, String attackStageId) {
        this.pos = pos;
        this.modelId = modelId;
        this.warheadId = warheadId;
        this.highAltitude = highAltitude;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.explosionOffset = explosionOffset;
        this.altitudeParam = altitudeParam;
        this.fragmentCount = fragmentCount;
        this.cruiseSpeed = cruiseSpeed;
        this.turnRate = turnRate;
        this.health = health;
        this.startInCruise = startInCruise;
        this.startArmed = startArmed;
        this.ascentStageId = ascentStageId;
        this.cruiseStageId = cruiseStageId;
        this.attackStageId = attackStageId;
    }

    public static void encode(SpawnMissilePacket m, FriendlyByteBuf b) {
        b.writeBlockPos(m.pos);
        b.writeUtf(m.modelId);
        b.writeUtf(m.warheadId);
        b.writeBoolean(m.highAltitude);
        b.writeDouble(m.targetX);
        b.writeDouble(m.targetY);
        b.writeDouble(m.targetZ);
        b.writeFloat(m.explosionOffset);
        b.writeDouble(m.altitudeParam);
        b.writeVarInt(m.fragmentCount);
        b.writeDouble(m.cruiseSpeed);
        b.writeDouble(m.turnRate);
        b.writeFloat(m.health);
        b.writeBoolean(m.startInCruise);
        b.writeBoolean(m.startArmed);
        b.writeUtf(m.ascentStageId);
        b.writeUtf(m.cruiseStageId);
        b.writeUtf(m.attackStageId);
    }

    public static SpawnMissilePacket decode(FriendlyByteBuf b) {
        return new SpawnMissilePacket(
                b.readBlockPos(),
                b.readUtf(),
                b.readUtf(),
                b.readBoolean(),
                b.readDouble(),
                b.readDouble(),
                b.readDouble(),
                b.readFloat(),
                b.readDouble(),
                b.readVarInt(),
                b.readDouble(),
                b.readDouble(),
                b.readFloat(),
                b.readBoolean(),
                b.readBoolean(),
                b.readUtf(),
                b.readUtf(),
                b.readUtf());
    }

    public static void handle(SpawnMissilePacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();

            // Re-validate: the block must still exist and the player must be next to it.
            if (!level.isLoaded(m.pos)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(m.pos);
            if (!(be instanceof MissileDispenserBlockEntity dispenser)) {
                return;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(m.pos)) > MAX_USE_DISTANCE_SQR) {
                return;
            }

            String model = MissileModels.exists(m.modelId) ? m.modelId : MissileModels.DEFAULT;
            String warhead = WarheadRegistry.exists(m.warheadId) ? m.warheadId : WarheadRegistry.defaultId();
            double speed = m.cruiseSpeed > 1.0E-3 ? m.cruiseSpeed : MissileEntity.CRUISE_SPEED;
            int frags = Math.max(0, m.fragmentCount);
            float health = m.health > 0.0f ? m.health : MissileEntity.DEFAULT_HEALTH;

            MissileEntity.Builder builder = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
                    .target(new Vec3(m.targetX, m.targetY, m.targetZ))
                    .model(model)
                    .detonation(warhead)
                    .explosionOffset(m.explosionOffset)
                    .fragmentCount(frags)
                    .cruiseSpeed(speed)
                    .health(health)
                    .controlId(dispenser.getControlId())
                    .teamId(com.wf.wfballistics.compat.WarforgeCompat.factionClaiming(level, m.pos))
                    .ascentStage(FlightStageRegistry.exists(Phase.ASCEND, m.ascentStageId)
                            ? m.ascentStageId : FlightStageRegistry.defaultId(Phase.ASCEND))
                    .cruiseStage(FlightStageRegistry.exists(Phase.CRUISE, m.cruiseStageId)
                            ? m.cruiseStageId : FlightStageRegistry.defaultId(Phase.CRUISE))
                    .attackStage(FlightStageRegistry.exists(Phase.ATTACK, m.attackStageId)
                            ? m.attackStageId : FlightStageRegistry.defaultId(Phase.ATTACK));
            if (m.highAltitude) {
                builder.highAltitude(m.altitudeParam);
            } else {
                builder.terrainFollow(m.altitudeParam);
            }
            if (m.turnRate > 0.0) {
                builder.turnRate(m.turnRate); // else keep the model-size default
            }
            if (m.startInCruise) {
                builder.startInCruise();
            }
            if (m.startArmed) {
                builder.startArmed();
            }

            MissileEntity missile = builder.build();
            // Spawn clear above the block; the arming distance is what actually stops a launch-site detonation.
            Vec3 spawn = Vec3.atCenterOf(m.pos).add(0.0, 2.0, 0.0);
            missile.moveTo(spawn.x, spawn.y, spawn.z, 0.0f, 0.0f);
            level.addFreshEntity(missile);
        });
        context.setPacketHandled(true);
    }
}
