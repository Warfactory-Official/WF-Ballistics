package com.wf.wflib.pda.drone;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Drones app SELECT / CONNECT / REMOVE on the held PDA's channel {@code index}; fresh status back. */
public record DroneActionPacket(PdaDrones.Action action, int index) implements CustomPacketPayload {
    public static final Type<DroneActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_drone_action"));
    public static final StreamCodec<FriendlyByteBuf, DroneActionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> PdaDrones.Action.values()[i], PdaDrones.Action::ordinal),
            DroneActionPacket::action, ByteBufCodecs.VAR_INT, DroneActionPacket::index, DroneActionPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> {
                PdaDrones.act(player, action, index);
                PacketDistributor.sendToPlayer(player, PdaDrones.status(player));
            });
        }
    }

    @Override
    public Type<DroneActionPacket> type() {
        return TYPE;
    }
}
