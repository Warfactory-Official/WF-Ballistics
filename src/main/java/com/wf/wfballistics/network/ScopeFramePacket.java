package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.recon.scope.ScopeFrame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** One network's scope picture, sent to the players who can see a display for it. */
public record ScopeFramePacket(ScopeFrame frame) implements CustomPacketPayload {

    public static final Type<ScopeFramePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "scope_frame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeFramePacket> STREAM_CODEC =
            StreamCodec.of(ScopeFramePacket::encode, ScopeFramePacket::decode);

    @Override
    public Type<ScopeFramePacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ScopeFramePacket pkt) {
        ScopeFrame frame = pkt.frame;
        buf.writeLong(frame.netId());
        buf.writeDouble(frame.originX());
        buf.writeDouble(frame.originY());
        buf.writeDouble(frame.originZ());
        buf.writeDouble(frame.range());
        buf.writeVarLong(frame.gameTime());
        List<ScopeFrame.Blip> blips = frame.blips();
        buf.writeVarInt(blips.size());
        for (int i = 0; i < blips.size(); i++) {
            ScopeFrame.Blip blip = blips.get(i);
            buf.writeFloat(blip.x());
            buf.writeFloat(blip.y());
            buf.writeFloat(blip.z());
            buf.writeFloat(blip.error());
            buf.writeFloat(blip.confidence());
            buf.writeByte(blip.quality());
            buf.writeByte(blip.kind());
            buf.writeByte(blip.iff());
            buf.writeShort(blip.count());
            buf.writeShort(blip.age());
            buf.writeByte(blip.bands());
            buf.writeByte(blip.hops());
        }
        List<ScopeFrame.Event> events = frame.events();
        buf.writeVarInt(events.size());
        for (int i = 0; i < events.size(); i++) {
            ScopeFrame.Event event = events.get(i);
            buf.writeFloat(event.x());
            buf.writeFloat(event.y());
            buf.writeFloat(event.z());
            buf.writeFloat(event.error());
            buf.writeFloat(event.power());
            buf.writeVarInt(event.ageSeconds());
            buf.writeByte(event.stations());
            buf.writeBoolean(event.subsurface());
        }
    }

    private static ScopeFramePacket decode(RegistryFriendlyByteBuf buf) {
        long netId = buf.readLong();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double range = buf.readDouble();
        long gameTime = buf.readVarLong();
        int count = Math.min(buf.readVarInt(), ScopeFrame.MAX_BLIPS);
        List<ScopeFrame.Blip> blips = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blips.add(new ScopeFrame.Blip(buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readByte(), buf.readByte(), buf.readByte(),
                    buf.readShort(), buf.readShort(),
                    buf.readByte(), buf.readByte()));
        }
        int eventCount = Math.min(buf.readVarInt(), ScopeFrame.MAX_EVENTS);
        List<ScopeFrame.Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new ScopeFrame.Event(buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readByte(), buf.readBoolean()));
        }
        return new ScopeFramePacket(new ScopeFrame(netId, x, y, z, range, gameTime,
                List.copyOf(blips), List.copyOf(events)));
    }
}
