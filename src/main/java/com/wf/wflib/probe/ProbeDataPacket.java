package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The server's answer. Keyed by the same target, so a late reply for something else is dropped. */
public record ProbeDataPacket(BlockPos pos, int entityId, CompoundTag data) implements CustomPacketPayload {

    public static final Type<ProbeDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeDataPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos);
                        buf.writeVarInt(pkt.entityId);
                        buf.writeNbt(pkt.data);
                    },
                    buf -> new ProbeDataPacket(buf.readBlockPos(), buf.readVarInt(),
                            buf.readNbt() instanceof CompoundTag tag ? tag : new CompoundTag()));

    @Override
    public Type<ProbeDataPacket> type() {
        return TYPE;
    }
}
