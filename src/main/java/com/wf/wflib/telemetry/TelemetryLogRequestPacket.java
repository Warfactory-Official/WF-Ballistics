package com.wf.wflib.telemetry;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Telemetry app open -> history after {@code seq}. */
public record TelemetryLogRequestPacket(long after) implements CustomPacketPayload {
    public static final Type<TelemetryLogRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "telemetry_log_request"));
    public static final StreamCodec<FriendlyByteBuf, TelemetryLogRequestPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TelemetryLogRequestPacket::after, TelemetryLogRequestPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> PacketDistributor.sendToPlayer(player, TelemetryLog.since(player, after)));
        }
    }

    @Override
    public Type<TelemetryLogRequestPacket> type() {
        return TYPE;
    }
}
