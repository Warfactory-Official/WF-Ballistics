package com.wf.wfballistics.network;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.client.ClientPacketHandler;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the mod's custom payloads (replaces the old Forge SimpleChannel). Runs on the mod event bus.
 *
 * <p>The client-bound handlers defer to {@link ClientPacketHandler} inside {@code ctx.enqueueWork(...)}; that
 * client-only class is only classloaded when a client actually receives the payload, so this registrar stays
 * safe to run on a dedicated server.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WFPayloadRegistrar {

    private WFPayloadRegistrar() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        r.playToClient(ExplosionKnockbackPacket.TYPE, ExplosionKnockbackPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleKnockback(pkt)));
        r.playToClient(ExplosionBlockFXPacket.TYPE, ExplosionBlockFXPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleBlockFX(pkt)));
        r.playToClient(AuxParticlePacket.TYPE, AuxParticlePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleAuxParticle(pkt)));
        r.playToClient(MissileFlightAudioPacket.TYPE, MissileFlightAudioPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleMissileAudio(pkt)));

        r.playToServer(SpawnMissilePacket.TYPE, SpawnMissilePacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
    }
}
