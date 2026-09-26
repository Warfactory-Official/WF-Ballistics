package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Marking a stretch of a route built, or no longer built, by hand.
 *
 * <p>The machine path does not come through here: a track layer reports its own work server-side. This
 * is for the route that was built before any of this existed, and for a surveyor correcting the record
 * after a bridge goes down.</p>
 *
 * @param from chainage in blocks
 * @param laid true for track down, false for track gone
 */
public record RailBuildPacket(UUID id, double from, double to, boolean laid) implements CustomPacketPayload {

    public static final Type<RailBuildPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "rail_build"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RailBuildPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.id);
                        buf.writeDouble(pkt.from);
                        buf.writeDouble(pkt.to);
                        buf.writeBoolean(pkt.laid);
                    },
                    buf -> new RailBuildPacket(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                            buf.readBoolean()));

    @Override
    public Type<RailBuildPacket> type() {
        return TYPE;
    }
}
