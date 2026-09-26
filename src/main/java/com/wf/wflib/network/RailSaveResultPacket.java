package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.AlignmentEdits;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * What the server did with a save.
 *
 * <p>An explicit answer rather than letting the client infer one from the broadcast that follows. The
 * two cases that matter cannot be inferred: a refused write produces no broadcast at all, and a client
 * that kept editing while its save was in flight cannot tell its own acknowledgement from a teammate's
 * change by revision number alone.</p>
 *
 * @param revision the version the route is now at, so the client can keep saving against it
 */
public record RailSaveResultPacket(UUID id, AlignmentEdits.SaveOutcome outcome, int revision)
        implements CustomPacketPayload {

    public static final Type<RailSaveResultPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_save_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailSaveResultPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.id);
                        buf.writeVarInt(pkt.outcome.ordinal());
                        buf.writeVarInt(Math.max(0, pkt.revision));
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        int ordinal = buf.readVarInt();
                        AlignmentEdits.SaveOutcome[] values = AlignmentEdits.SaveOutcome.values();
                        return new RailSaveResultPacket(id,
                                ordinal >= 0 && ordinal < values.length
                                        ? values[ordinal] : AlignmentEdits.SaveOutcome.DENIED,
                                buf.readVarInt());
                    });

    @Override
    public Type<RailSaveResultPacket> type() {
        return TYPE;
    }
}
