package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** "Do this to that." The only thing the probe ever asks the server to change. */
public record ProbeActionPacket(BlockPos pos, int entityId, ResourceLocation action, int arg)
        implements CustomPacketPayload {

    public static final Type<ProbeActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos);
                        buf.writeVarInt(pkt.entityId);
                        buf.writeResourceLocation(pkt.action);
                        buf.writeVarInt(pkt.arg);
                    },
                    buf -> new ProbeActionPacket(buf.readBlockPos(), buf.readVarInt(),
                            buf.readResourceLocation(), buf.readVarInt()));

    @Override
    public Type<ProbeActionPacket> type() {
        return TYPE;
    }
}
