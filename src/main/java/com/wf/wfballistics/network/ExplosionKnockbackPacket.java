package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Carries an explosion's knockback impulse to the affected player's client. The server cannot shove a
 * player around directly (the client owns its position), so the entity processor records the impulse and
 * this packet asks the client to apply it locally and report the new velocity back.
 *
 * @see PlayerProcessorStandard
 */
public record ExplosionKnockbackPacket(double x, double y, double z) implements CustomPacketPayload {

    public ExplosionKnockbackPacket(Vec3 motion) {
        this(motion.x, motion.y, motion.z);
    }

    public static final Type<ExplosionKnockbackPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "explosion_knockback"));

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
