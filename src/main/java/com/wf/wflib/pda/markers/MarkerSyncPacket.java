package com.wf.wflib.pda.markers;

import com.wf.wflib.WFLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Every marker of the receiver's side. */
public record MarkerSyncPacket(List<Marker> markers) implements CustomPacketPayload {
    public static final Type<MarkerSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_markers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarkerSyncPacket> STREAM_CODEC =
            StreamCodec.of((buf, pkt) -> {
                buf.writeVarInt(pkt.markers.size());
                for (Marker m : pkt.markers) {
                    m.write(buf);
                }
            }, buf -> {
                int n = Math.min(buf.readVarInt(), MarkerStore.CAP);
                List<Marker> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(Marker.read(buf));
                }
                return new MarkerSyncPacket(list);
            });

    @Override
    public Type<MarkerSyncPacket> type() {
        return TYPE;
    }
}
