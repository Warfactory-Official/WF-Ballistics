package com.wf.wflib.pda.sat;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Satellites app -> server: live spot of {@code (x, z)} from bird {@code satId}. */
public record SpotRequestPacket(long satId, double x, double z) implements CustomPacketPayload {
    public static final Type<SpotRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_spot_request"));
    public static final StreamCodec<FriendlyByteBuf, SpotRequestPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SpotRequestPacket::satId, ByteBufCodecs.DOUBLE, SpotRequestPacket::x,
            ByteBufCodecs.DOUBLE, SpotRequestPacket::z, SpotRequestPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> PdaSatellites.spot(player, satId, x, z));
        }
    }

    @Override
    public Type<SpotRequestPacket> type() {
        return TYPE;
    }
}
