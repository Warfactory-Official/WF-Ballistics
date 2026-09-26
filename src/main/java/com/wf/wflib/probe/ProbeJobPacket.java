package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: a channelled action started ({@code ticks > 0}), finished ({@code ticks == 0}) or was
 * cancelled ({@code ticks < 0}, {@code message} = why).
 */
public record ProbeJobPacket(ResourceLocation action, int arg, int ticks, Component message)
        implements CustomPacketPayload {

    public static final Type<ProbeJobPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "probe_job"));

    private static final ResourceLocation NONE = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "none");

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbeJobPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeResourceLocation(pkt.action);
                buf.writeVarInt(pkt.arg);
                buf.writeVarInt(pkt.ticks);
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, pkt.message);
            },
            buf -> new ProbeJobPacket(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(),
                    ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));

    static ProbeJobPacket started(ResourceLocation action, int arg, int ticks) {
        return new ProbeJobPacket(action, arg, ticks, Component.empty());
    }

    static ProbeJobPacket finished() {
        return new ProbeJobPacket(NONE, 0, 0, Component.empty());
    }

    static ProbeJobPacket cancelled(ProbeJobs.Reason reason) {
        return new ProbeJobPacket(NONE, 0, -1, reason.message());
    }

    @Override
    public Type<ProbeJobPacket> type() {
        return TYPE;
    }
}
