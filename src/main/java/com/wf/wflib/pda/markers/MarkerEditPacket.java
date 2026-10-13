package com.wf.wflib.pda.markers;

import com.wf.wflib.WFLib;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** {@code removeId} != 0 => remove that marker; else add {@code label} at {@code pos}. */
public record MarkerEditPacket(long removeId, ResourceLocation dimension, BlockPos pos, String label)
        implements CustomPacketPayload {
    public static final Type<MarkerEditPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_marker_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarkerEditPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarLong(pkt.removeId);
                buf.writeResourceLocation(pkt.dimension);
                buf.writeBlockPos(pkt.pos);
                buf.writeUtf(pkt.label, Marker.MAX_LABEL);
            },
            buf -> new MarkerEditPacket(buf.readVarLong(), buf.readResourceLocation(), buf.readBlockPos(),
                    buf.readUtf(Marker.MAX_LABEL)));

    public static MarkerEditPacket add(ResourceLocation dimension, BlockPos pos, String label) {
        return new MarkerEditPacket(0, dimension, pos, label);
    }

    public static MarkerEditPacket remove(long id) {
        return new MarkerEditPacket(id, ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO, "");
    }

    @Override
    public Type<MarkerEditPacket> type() {
        return TYPE;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                if (removeId != 0) {
                    Markers.remove(player, removeId);
                } else {
                    Markers.add(player, dimension, pos, label);
                }
            }
        });
    }
}
