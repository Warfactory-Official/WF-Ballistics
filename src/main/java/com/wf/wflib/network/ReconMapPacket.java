package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.map.ReconMapView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** One network's grid and coverage, sent to a player who asked to see it. */
public record ReconMapPacket(ResourceKey<Level> dimension, ReconMapView view) implements CustomPacketPayload {

    public static final Type<ReconMapPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "recon_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReconMapPacket> STREAM_CODEC =
            StreamCodec.of(ReconMapPacket::encode, ReconMapPacket::decode);

    /** Longest node label accepted off the wire. Labels are {@code hub} and the probe kinds. */
    private static final int MAX_LABEL = 32;
    /** Marks a footprint with no band: a met station. */
    private static final int NO_BAND = -1;

    @Override
    public Type<ReconMapPacket> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, ReconMapPacket pkt) {
        buf.writeResourceKey(pkt.dimension);
        ReconMapView view = pkt.view;
        buf.writeLong(view.netId());

        List<ReconMapView.Node> nodes = view.nodes();
        buf.writeVarInt(nodes.size());
        for (ReconMapView.Node node : nodes) {
            buf.writeBlockPos(node.pos());
            buf.writeUtf(node.label(), MAX_LABEL);
            buf.writeBoolean(node.root());
            // Biased by one so the orphan sentinel is not a negative varint, which costs five bytes.
            buf.writeVarInt(node.hops() + 1);
            buf.writeVarInt(node.downstream());
            buf.writeLong(node.uplink());
            buf.writeFloat((float) node.linkRange());
        }

        List<ReconMapView.Footprint> coverage = view.coverage();
        buf.writeVarInt(coverage.size());
        for (ReconMapView.Footprint shape : coverage) {
            buf.writeBlockPos(shape.pos());
            buf.writeByte(shape.band() == null ? NO_BAND : shape.band().ordinal());
            buf.writeFloat((float) shape.range());
            buf.writeVarInt(shape.hops() + 1);
        }
    }

    private static ReconMapPacket decode(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        long netId = buf.readLong();

        int nodeCount = Math.min(buf.readVarInt(), ReconMapView.MAX_NODES);
        List<ReconMapView.Node> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            BlockPos pos = buf.readBlockPos();
            nodes.add(new ReconMapView.Node(pos, buf.readUtf(MAX_LABEL), buf.readBoolean(),
                    buf.readVarInt() - 1, buf.readVarInt(), buf.readLong(), buf.readFloat()));
        }

        int shapeCount = Math.min(buf.readVarInt(), ReconMapView.MAX_FOOTPRINTS);
        List<ReconMapView.Footprint> coverage = new ArrayList<>(shapeCount);
        for (int i = 0; i < shapeCount; i++) {
            BlockPos pos = buf.readBlockPos();
            coverage.add(new ReconMapView.Footprint(pos, band(buf.readByte()), buf.readFloat(),
                    buf.readVarInt() - 1));
        }

        return new ReconMapPacket(dimension, new ReconMapView(netId, List.copyOf(nodes), List.copyOf(coverage)));
    }

    /**
     * @return the band for an ordinal, or null for {@link #NO_BAND} <em>and</em> for anything out of range.
     *      A band this build does not have is a band it cannot draw, and treating it as "no band" leaves the
     *      footprint on the map instead of dropping the packet: the ordinal is the only thing here a newer server
     *      can send that an older client cannot read.
     */
    private static Band band(byte ordinal) {
        Band[] all = Band.values();
        return ordinal < 0 || ordinal >= all.length ? null : all[ordinal];
    }
}
