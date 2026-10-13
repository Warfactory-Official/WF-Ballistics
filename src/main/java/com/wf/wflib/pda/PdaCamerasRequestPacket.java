package com.wf.wflib.pda;

import com.wf.wflib.WFLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Cameras app opened: resend the held PDA's channels now. */
public record PdaCamerasRequestPacket() implements CustomPacketPayload {
    public static final Type<PdaCamerasRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_cameras"));
    public static final StreamCodec<ByteBuf, PdaCamerasRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new PdaCamerasRequestPacket());

    @Override
    public Type<PdaCamerasRequestPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                PdaCameras.refresh(player, true);
            }
        });
    }
}
