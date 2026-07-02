package com.wf.wfballistics.network;

import com.wf.wfballistics.aef.standard.PlayerProcessorStandard;
import com.wf.wfballistics.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Carries an explosion's knockback impulse to the affected player's client. The server cannot shove a
 * player around directly (the client owns its position), so the entity processor records the impulse and
 * this packet asks the client to apply it locally and report the new velocity back.
 *
 * @see PlayerProcessorStandard
 */
public record ExplosionKnockbackPacket(double x, double y, double z) {

    public ExplosionKnockbackPacket(Vec3 motion) {
        this(motion.x, motion.y, motion.z);
    }

    public static void encode(ExplosionKnockbackPacket pkt, FriendlyByteBuf buf) {
        buf.writeDouble(pkt.x);
        buf.writeDouble(pkt.y);
        buf.writeDouble(pkt.z);
    }

    public static ExplosionKnockbackPacket decode(FriendlyByteBuf buf) {
        return new ExplosionKnockbackPacket(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(ExplosionKnockbackPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleKnockback(pkt)));
        ctx.get().setPacketHandled(true);
    }
}
