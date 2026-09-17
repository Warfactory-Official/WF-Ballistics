package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.drone.cam.CameraChunkStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** What this client can do with a camera feed, told to the server once at login. */
public record CameraCapabilityPacket(boolean streamedTerrain) implements CustomPacketPayload {

    public static final Type<CameraCapabilityPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "camera_capability"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraCapabilityPacket> STREAM_CODEC =
            StreamCodec.of(CameraCapabilityPacket::write, CameraCapabilityPacket::read);

    @Override
    public Type<CameraCapabilityPacket> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, CameraCapabilityPacket pkt) {
        buf.writeBoolean(pkt.streamedTerrain);
    }

    private static CameraCapabilityPacket read(RegistryFriendlyByteBuf buf) {
        return new CameraCapabilityPacket(buf.readBoolean());
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                CameraChunkStream.setCapable(player.getUUID(), this.streamedTerrain);
            }
        });
    }
}
