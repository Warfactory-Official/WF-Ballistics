package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.telemetry.TelemetryKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Every reporter this player's terminal hears; empty = stop. */
public record TelemetryPacket(ResourceKey<Level> dimension, List<Entry> entries) implements CustomPacketPayload {

    public static final Type<TelemetryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "telemetry"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TelemetryPacket> STREAM_CODEC =
            StreamCodec.of(TelemetryPacket::encode, TelemetryPacket::decode);

    private static final int MAX_ENTRIES = 512;
    private static final int MAX_LABEL = 64;

    /** @param heading degrees clockwise from north; own = this player is the operator */
    public record Entry(long id, TelemetryKind kind, double x, double y, double z, float heading, String label,
                        boolean own) {
    }

    @Override
    public Type<TelemetryPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, TelemetryPacket pkt) {
        buf.writeResourceKey(pkt.dimension);
        int count = Math.min(pkt.entries.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Entry e = pkt.entries.get(i);
            buf.writeLong(e.id());
            buf.writeByte(e.kind().ordinal());
            buf.writeDouble(e.x());
            buf.writeFloat((float) e.y());
            buf.writeDouble(e.z());
            buf.writeFloat(e.heading());
            buf.writeUtf(e.label(), MAX_LABEL);
            buf.writeBoolean(e.own());
        }
    }

    private static TelemetryPacket decode(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readLong(), TelemetryKind.VALUES[buf.readByte()], buf.readDouble(),
                    buf.readFloat(), buf.readDouble(), buf.readFloat(), buf.readUtf(MAX_LABEL), buf.readBoolean()));
        }
        return new TelemetryPacket(dimension, entries);
    }
}
