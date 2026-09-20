package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraFeed;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One camera's state, pre-encoded. */
public record CameraFeedPacket(byte[] body) implements CustomPacketPayload {

    public static final Type<CameraFeedPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "camera_feed"));

    /** Sanity bound on an inbound payload, so a hostile server cannot make a client allocate. */
    private static final int MAX_BODY = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraFeedPacket> STREAM_CODEC =
            StreamCodec.of(CameraFeedPacket::write, CameraFeedPacket::read);

    @Override
    public Type<CameraFeedPacket> type() {
        return TYPE;
    }

    /** Serialise a feed. */
    public static CameraFeedPacket of(CameraFeed feed) {
        ByteBuf raw = Unpooled.buffer(64);
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(raw);
            buf.writeVarInt(feed.feedId());
            buf.writeDouble(feed.x());
            buf.writeDouble(feed.y());
            buf.writeDouble(feed.z());
            buf.writeFloat(feed.yaw());
            buf.writeFloat(feed.pitch());
            buf.writeFloat(feed.fov());
            buf.writeFloat(feed.fovDeg());
            buf.writeFloat(feed.maxZoom());
            buf.writeFloat(feed.slewRate());
            buf.writeByte(feed.mode());
            buf.writeByte(feed.modeMask());
            buf.writeFloat(feed.battery());
            buf.writeFloat(feed.link());
            buf.writeFloat(feed.speed());
            buf.writeFloat(feed.altitude());
            buf.writeVarLong(feed.gameTime());
            byte[] body = new byte[buf.readableBytes()];
            buf.readBytes(body);
            return new CameraFeedPacket(body);
        } finally {
            raw.release();
        }
    }

    /**
     * @return the state this payload carries. Client side; parses the array the server built.
     */
    public CameraFeed feed() {
        ByteBuf raw = Unpooled.wrappedBuffer(this.body);
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        return new CameraFeed(buf.readVarInt(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readByte(), buf.readByte(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarLong());
    }

    private static void write(RegistryFriendlyByteBuf buf, CameraFeedPacket pkt) {
        buf.writeVarInt(pkt.body.length);
        buf.writeBytes(pkt.body);
    }

    private static CameraFeedPacket read(RegistryFriendlyByteBuf buf) {
        int length = buf.readVarInt();
        if (length < 0 || length > MAX_BODY) {
            throw new IllegalArgumentException("camera feed body of " + length + " bytes");
        }
        byte[] body = new byte[length];
        buf.readBytes(body);
        return new CameraFeedPacket(body);
    }
}
