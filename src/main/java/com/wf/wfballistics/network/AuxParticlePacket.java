package com.wf.wfballistics.network;

import com.wf.wfballistics.client.ClientPacketHandler;
import com.wf.wfballistics.client.fx.WFEffects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A generic "play this named particle effect here" packet.
 */
public record AuxParticlePacket(String effect, double x, double y, double z, CompoundTag data) {

    public static void encode(AuxParticlePacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.effect);
        buf.writeDouble(pkt.x);
        buf.writeDouble(pkt.y);
        buf.writeDouble(pkt.z);
        buf.writeNbt(pkt.data);
    }

    public static AuxParticlePacket decode(FriendlyByteBuf buf) {
        return new AuxParticlePacket(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readNbt());
    }

    public static void handle(AuxParticlePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleAuxParticle(pkt)));
        ctx.get().setPacketHandled(true);
    }
}
