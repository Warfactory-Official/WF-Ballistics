package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Drones app open -> server, polled. */
public record DroneStatusRequestPacket() implements CustomPacketPayload {
    public static final DroneStatusRequestPacket INSTANCE = new DroneStatusRequestPacket();
    public static final Type<DroneStatusRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_request"));
    public static final StreamCodec<FriendlyByteBuf, DroneStatusRequestPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> PacketDistributor.sendToPlayer(player, PdaDrones.status(player)));
        }
    }

    @Override
    public Type<DroneStatusRequestPacket> type() {
        return TYPE;
    }
}
