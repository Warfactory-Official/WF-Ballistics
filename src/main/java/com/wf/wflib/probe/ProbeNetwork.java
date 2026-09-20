package com.wf.wflib.probe;

import com.wf.wflib.WFLib;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The probe's own two payloads, registered by the probe rather than by the mod's shared registrar so the
 * whole feature stays in one package.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class ProbeNetwork {

    private ProbeNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("probe-1").optional();

        registrar.playToServer(ProbeRequestPacket.TYPE, ProbeRequestPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        ProbeSync.onRequest(pkt, player);
                    }
                }));
        registrar.playToServer(ProbeActionPacket.TYPE, ProbeActionPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        ProbeActions.perform(pkt, player);
                    }
                }));
        registrar.playToClient(ProbeDataPacket.TYPE, ProbeDataPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.probe.client.ProbeClientData.accept(pkt)));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProbeSync.forget(player);
            ProbeActions.forget(player);
        }
    }
}
