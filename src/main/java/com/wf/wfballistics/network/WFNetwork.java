package com.wf.wfballistics.network;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

/** Send-helpers for the mod's server→client effect payloads. */
public final class WFNetwork {

    private WFNetwork() {
    }

    /**
     * Sends a payload from the client to the server.
     */
    public static void sendToServer(CustomPacketPayload msg) {
        PacketDistributor.sendToServer(msg);
    }

    /**
     * Sends to every player who has the chunk containing {@code (x, z)} loaded: the correct, cheap audience for a
     * localized effect.
     */
    public static void sendToTracking(Level level, double x, double z, CustomPacketPayload msg) {
        if (!(level instanceof ServerLevel sl)) return;
        LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord((int) Math.floor(x)),
                SectionPos.blockToSectionCoord((int) Math.floor(z)));
        PacketDistributor.sendToPlayersTrackingChunk(sl, chunk.getPos(), msg);
    }

    /**
     * Sends to a specific {@link ServerPlayer}.
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload msg) {
        PacketDistributor.sendToPlayer(player, msg);
    }

    /**
     * Radius-based fallback when an explicit cutoff (rather than view distance) is wanted.
     */
    public static void sendToAllAround(Level level, double x, double y, double z, double radius, CustomPacketPayload msg) {
        if (!(level instanceof ServerLevel sl)) return;
        PacketDistributor.sendToPlayersNear(sl, null, x, y, z, radius, msg);
    }
}
