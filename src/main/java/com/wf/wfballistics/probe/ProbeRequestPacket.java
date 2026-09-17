package com.wf.wfballistics.probe;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * "What else do you know about this?": sent only while the player is actually looking at something a
 * {@link ProbeDataProvider} has been registered for.
 *
 * @param entityId -1 for a block target, in which case {@code pos} is the block
 */
public record ProbeRequestPacket(BlockPos pos, int entityId) implements CustomPacketPayload {

    public static final Type<ProbeRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "probe_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeRequestPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos);
                        buf.writeVarInt(pkt.entityId);
                    },
                    buf -> new ProbeRequestPacket(buf.readBlockPos(), buf.readVarInt()));

    @Override
    public Type<ProbeRequestPacket> type() {
        return TYPE;
    }
}
