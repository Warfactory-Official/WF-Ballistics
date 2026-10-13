package com.wf.wflib.pda;

import com.wf.wflib.WFLib;
import com.wf.wflib.network.ScopeFramePacket;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.scope.ScopeFrame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Radar app picture: {@code frame} null <=> no uplink (or no net). */
public record PdaRadarPacket(boolean hasNet, Uplink.Link link, @Nullable ScopeFrame frame) implements CustomPacketPayload {
    public static final Type<PdaRadarPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_radar"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PdaRadarPacket> STREAM_CODEC =
            StreamCodec.of(PdaRadarPacket::write, PdaRadarPacket::read);

    @Override
    public Type<PdaRadarPacket> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buf, PdaRadarPacket pkt) {
        buf.writeBoolean(pkt.hasNet);
        buf.writeEnum(pkt.link);
        buf.writeBoolean(pkt.frame != null);
        if (pkt.frame != null) {
            ScopeFramePacket.write(buf, pkt.frame);
        }
    }

    private static PdaRadarPacket read(RegistryFriendlyByteBuf buf) {
        boolean hasNet = buf.readBoolean();
        Uplink.Link link = buf.readEnum(Uplink.Link.class);
        return new PdaRadarPacket(hasNet, link, buf.readBoolean() ? ScopeFramePacket.read(buf) : null);
    }
}
