package com.wf.wflib.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** What happens when a probe action is chosen: the server half, keyed by action id. */
public final class ProbeActions {

    /** Beyond this an action request is dropped: further than anyone can be interacting from. */
    private static final double MAX_RANGE_SQR = 8.0 * 8.0;
    private static final long MIN_INTERVAL_MS = 100L;

    private static final Map<ResourceLocation, BlockHandler> BLOCKS = new HashMap<>();
    private static final Map<ResourceLocation, EntityHandler> ENTITIES = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACTED = new ConcurrentHashMap<>();

    private ProbeActions() {
    }

    @FunctionalInterface
    public interface BlockHandler {
        /** @return true if the action was performed; false leaves the player's list as it was */
        boolean perform(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, int arg);
    }

    @FunctionalInterface
    public interface EntityHandler {
        boolean perform(ServerPlayer player, Entity entity, int arg);
    }

    public static void registerBlock(ResourceLocation id, BlockHandler handler) {
        BLOCKS.put(id, handler);
    }

    public static void registerEntity(ResourceLocation id, EntityHandler handler) {
        ENTITIES.put(id, handler);
    }

    static void perform(ProbeActionPacket packet, ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_ACTED.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        LAST_ACTED.put(player.getUUID(), now);

        ServerLevel level = player.serverLevel();
        if (packet.entityId() >= 0) {
            Entity entity = level.getEntity(packet.entityId());
            EntityHandler handler = ENTITIES.get(packet.action());
            if (entity != null && handler != null && entity.distanceToSqr(player) <= MAX_RANGE_SQR) {
                handler.perform(player, entity, packet.arg());
            }
            return;
        }

        BlockPos pos = packet.pos();
        BlockHandler handler = BLOCKS.get(packet.action());
        if (handler == null || !level.isLoaded(pos)
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_RANGE_SQR) {
            return;
        }
        handler.perform(player, level, pos, level.getBlockState(pos), packet.arg());
    }

    public static void forget(ServerPlayer player) {
        LAST_ACTED.remove(player.getUUID());
    }
}
