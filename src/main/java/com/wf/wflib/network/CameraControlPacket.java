package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraNet;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** An operator's hands on the camera: which feed they are watching, and where they would like it pointed. */
public record CameraControlPacket(int feedId, float yaw, float pitch, float zoom, byte mode,
                                  boolean watching) implements CustomPacketPayload {

    /** Ticks between keep-alives from an open screen. Comfortably inside the server's expiry. */
    public static final int RENEW_INTERVAL = 20;

    public static final Type<CameraControlPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "camera_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraControlPacket> STREAM_CODEC =
            StreamCodec.of(CameraControlPacket::write, CameraControlPacket::read);

    @Override
    public Type<CameraControlPacket> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, CameraControlPacket pkt) {
        buf.writeVarInt(pkt.feedId);
        buf.writeFloat(pkt.yaw);
        buf.writeFloat(pkt.pitch);
        buf.writeFloat(pkt.zoom);
        buf.writeByte(pkt.mode);
        buf.writeBoolean(pkt.watching);
    }

    private static CameraControlPacket read(RegistryFriendlyByteBuf buf) {
        return new CameraControlPacket(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readByte(), buf.readBoolean());
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!this.watching) {
                CameraNet.unsubscribe(player);
                return;
            }
            CameraNet.subscribe(player, this.feedId);
            CameraNet.aim(player, this.feedId, this.yaw, this.pitch, this.zoom, this.mode);
            com.wf.wflib.item.CameraTabletItem.refresh(player);
        });
    }
}
