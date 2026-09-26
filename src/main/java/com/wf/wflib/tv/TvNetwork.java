package com.wf.wflib.tv;

import com.wf.wflib.WFLib;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = WFLib.MODID)
public final class TvNetwork {

    private TvNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("tv-1");
        registrar.playToServer(TvConnectPacket.TYPE, TvConnectPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        PacketDistributor.sendToPlayer(player, new TvLinkPacket(TvGuidance.connect(player), false));
                    }
                }));
        registrar.playToClient(TvLinkPacket.TYPE, TvLinkPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.tv.client.TvClient.accept(pkt)));
    }
}
