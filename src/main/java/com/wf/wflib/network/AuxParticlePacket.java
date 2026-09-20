package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A generic "play this named particle effect here" packet.
 */
public record AuxParticlePacket(String effect, double x, double y, double z, CompoundTag data)
        implements CustomPacketPayload {

    public static final Type<AuxParticlePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "aux_particle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuxParticlePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUtf(pkt.effect);
                        buf.writeDouble(pkt.x);
                        buf.writeDouble(pkt.y);
                        buf.writeDouble(pkt.z);
                        buf.writeNbt(pkt.data);
                    },
                    buf -> new AuxParticlePacket(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readNbt()));

    @Override
    public Type<AuxParticlePacket> type() {
        return TYPE;
    }
}
