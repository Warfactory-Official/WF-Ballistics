package com.wf.wflib.pda.sat;

import com.wf.wflib.WFLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Satellites app -> server: one command bus line for an own-net bird. */
public record SatCommandPacket(long satId, String verb, List<String> args) implements CustomPacketPayload {
    public static final Type<SatCommandPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_sat_command"));
    public static final StreamCodec<FriendlyByteBuf, SatCommandPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SatCommandPacket::satId, ByteBufCodecs.stringUtf8(32), SatCommandPacket::verb,
            ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(8)), SatCommandPacket::args, SatCommandPacket::new);

    public void handle(IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            ctx.enqueueWork(() -> PdaSatellites.command(player, satId, verb, args));
        }
    }

    @Override
    public Type<SatCommandPacket> type() {
        return TYPE;
    }
}
