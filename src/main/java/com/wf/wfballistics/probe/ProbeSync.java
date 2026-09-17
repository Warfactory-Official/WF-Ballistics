package com.wf.wfballistics.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The server half of the data channel. */
public final class ProbeSync {

    /** Beyond this a request is ignored outright: nobody is reading a probe from sixty-four blocks. */
    private static final double MAX_RANGE_SQR = 64.0 * 64.0;
    private static final long MIN_INTERVAL_MS = 100L;

    private static final Map<UUID, Long> LAST_ANSWERED = new ConcurrentHashMap<>();

    private ProbeSync() {
    }

    public static void onRequest(ProbeRequestPacket packet, ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_ANSWERED.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        LAST_ANSWERED.put(player.getUUID(), now);

        ServerLevel level = player.serverLevel();
        CompoundTag data = new CompoundTag();

        if (packet.entityId() >= 0) {
            Entity entity = level.getEntity(packet.entityId());
            if (entity == null || entity.distanceToSqr(player) > MAX_RANGE_SQR) {
                return;
            }
            ProbeRegistry.collectData(data, player, entity);
        } else {
            BlockPos pos = packet.pos();
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_RANGE_SQR
                    || !level.isLoaded(pos)) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            ProbeRegistry.collectData(data, player, level, pos, state, blockEntity);
        }

        if (data.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new ProbeDataPacket(packet.pos(), packet.entityId(), data));
    }

    /** Called when a player leaves, so the throttle map does not outlive the session. */
    public static void forget(ServerPlayer player) {
        LAST_ANSWERED.remove(player.getUUID());
    }
}
