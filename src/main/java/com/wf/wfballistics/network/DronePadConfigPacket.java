package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.block.entity.DronePadBlockEntity;
import com.wf.wfballistics.drone.DroneMission;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The drone pad's config screen talking to its block entity: store the mission, and optionally act on it.
 * Mirrors {@code SpawnMissilePacket}, including the re-validation on arrival: the client is never trusted
 * about which block it is operating or how far away it is.
 */
public record DronePadConfigPacket(BlockPos pos, DroneMission mission, Action action) implements CustomPacketPayload {

    /**
     * A generous cap so the pad stays a pad, not a remote artillery exploit.
     */
    private static final double MAX_USE_DISTANCE_SQR = 64.0 * 64.0;

    public static final Type<DronePadConfigPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "drone_pad_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DronePadConfigPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeEnum(p.action);
                        p.mission.write(buf);
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        Action action = buf.readEnum(Action.class);
                        return new DronePadConfigPacket(pos, DroneMission.read(buf), action);
                    });

    @Override
    public Type<DronePadConfigPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.getAbilities().instabuild && !player.hasPermissions(2)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof DronePadBlockEntity pad)
                    || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_USE_DISTANCE_SQR) {
                return;
            }

            pad.setMission(mission);
            switch (action) {
                case SAVE -> player.displayClientMessage(Component.literal("Pad mission saved"), true);
                case DISPATCH -> {
                    String error = pad.dispatch();
                    player.displayClientMessage(Component.literal(
                            error == null ? "Dispatched" : "Refused: " + error), true);
                }
                case RECALL -> {
                    int recalled = pad.recall(level);
                    player.displayClientMessage(Component.literal("Recalled " + recalled + " drone(s)"), true);
                }
                case CLEAR -> {
                    int removed = pad.clearDrones(level);
                    player.displayClientMessage(Component.literal("Removed " + removed + " drone(s)"), true);
                }
            }
        });
    }

    /**
     * What the screen asked for, beyond storing the mission.
     */
    public enum Action {
        SAVE,
        DISPATCH,
        /**
         * Send every drone this pad launched straight back to it: the testing escape hatch for a flight
         * that is halfway across the world.
         */
        RECALL,
        /**
         * Delete this dimension's drones and crates. For iterating without littering the world.
         */
        CLEAR
    }
}
