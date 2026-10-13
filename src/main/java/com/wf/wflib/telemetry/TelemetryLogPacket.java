package com.wf.wflib.telemetry;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.WFEventType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Datalink events this player hears; pushed as they happen, history on request. */
public record TelemetryLogPacket(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<TelemetryLogPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "telemetry_log"));
    public static final StreamCodec<FriendlyByteBuf, TelemetryLogPacket> STREAM_CODEC =
            StreamCodec.of(TelemetryLogPacket::encode, TelemetryLogPacket::decode);

    static final int MAX_ENTRIES = 256;
    private static final int MAX_LABEL = 64;
    private static final int MAX_DETAIL = 96;

    /**
     * @param seq server-wide, increasing
     * @param id {@link TelemetryLink#trackId}
     * @param own this player is the operator
     */
    public record Entry(long seq, long dayTime, long id, TelemetryKind kind, String label, WFEventType type,
                        String detail, int x, int y, int z, boolean own) {
        Entry owned(boolean own) {
            return own == this.own ? this : new Entry(seq, dayTime, id, kind, label, type, detail, x, y, z, own);
        }
    }

    @Override
    public Type<TelemetryLogPacket> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, TelemetryLogPacket pkt) {
        int count = Math.min(pkt.entries.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int i = pkt.entries.size() - count; i < pkt.entries.size(); i++) {
            Entry e = pkt.entries.get(i);
            buf.writeVarLong(e.seq());
            buf.writeVarLong(e.dayTime());
            buf.writeLong(e.id());
            buf.writeEnum(e.kind());
            buf.writeUtf(e.label(), MAX_LABEL);
            buf.writeEnum(e.type());
            buf.writeUtf(e.detail().length() > MAX_DETAIL ? e.detail().substring(0, MAX_DETAIL) : e.detail(),
                    MAX_DETAIL);
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.y());
            buf.writeVarInt(e.z());
            buf.writeBoolean(e.own());
        }
    }

    private static TelemetryLogPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readVarLong(), buf.readVarLong(), buf.readLong(),
                    buf.readEnum(TelemetryKind.class), buf.readUtf(MAX_LABEL), buf.readEnum(WFEventType.class),
                    buf.readUtf(MAX_DETAIL), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean()));
        }
        return new TelemetryLogPacket(entries);
    }
}
