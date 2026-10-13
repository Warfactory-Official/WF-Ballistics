package com.wf.wflib.pda;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Radar app open at {@code range} blocks; 0 = closed. */
public record PdaRadarSubscribePacket(float range) implements CustomPacketPayload {
    public static final Type<PdaRadarSubscribePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_radar_subscribe"));
    public static final StreamCodec<ByteBuf, PdaRadarSubscribePacket> STREAM_CODEC =
            ByteBufCodecs.FLOAT.map(PdaRadarSubscribePacket::new, PdaRadarSubscribePacket::range);

    @Override
    public Type<PdaRadarSubscribePacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                PdaRadar.subscribe(player, range);
            }
        });
    }
}
