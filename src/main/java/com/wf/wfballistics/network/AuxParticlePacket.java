package com.wf.wfballistics.network;

import com.wf.wfballistics.client.ClientPacketHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A generic "play this named particle effect here" packet.
 *
 * <p>Rather than one packet class per effect, the effect is identified by a string key registered in
 * {@link com.wf.wfballistics.client.fx.WFEffects}; arbitrary parameters (scale, colour, counts, a target
 * entity id, ...) ride along in the {@link CompoundTag}. This keeps adding a new effect to a single
 * registration call instead of a new packet + wiring.
 */
public class AuxParticlePacket {

    public final String effect;
    public final double x, y, z;
    public final CompoundTag data;

    public AuxParticlePacket(String effect, double x, double y, double z, CompoundTag data) {
        this.effect = effect;
        this.x = x;
        this.y = y;
        this.z = z;
        this.data = data;
    }

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
