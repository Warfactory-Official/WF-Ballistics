package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.aef.standard.PlayerProcessorStandard;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Carries an explosion's knockback impulse to the affected player's client.
 *
 * @see PlayerProcessorStandard
 */
public record ExplosionKnockbackPacket(double x, double y, double z) implements CustomPacketPayload {

    public ExplosionKnockbackPacket(Vec3 motion) {
        this(motion.x, motion.y, motion.z);
    }

    public static final Type<ExplosionKnockbackPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "explosion_knockback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExplosionKnockbackPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeDouble(pkt.x);
                        buf.writeDouble(pkt.y);
                        buf.writeDouble(pkt.z);
                    },
                    buf -> new ExplosionKnockbackPacket(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<ExplosionKnockbackPacket> type() {
        return TYPE;
    }
}
