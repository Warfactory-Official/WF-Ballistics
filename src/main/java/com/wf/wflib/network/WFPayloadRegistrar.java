package com.wf.wflib.network;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.ClientPacketHandler;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers the mod's custom payloads (replaces the old Forge SimpleChannel). */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD)
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

        r.playToClient(SimGlyphidSyncPacket.TYPE, SimGlyphidSyncPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSimGlyphids(pkt)));
        r.playToClient(ScopeFramePacket.TYPE, ScopeFramePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleScopeFrame(pkt)));
        r.playToClient(CameraFeedPacket.TYPE, CameraFeedPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleCameraFeed(pkt)));
        r.playToClient(CameraPanelPacket.TYPE, CameraPanelPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleCameraPanel(pkt)));
        r.playToClient(ReconMapPacket.TYPE, ReconMapPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleReconMap(pkt)));

        r.playToClient(SurveyTilePacket.TYPE, SurveyTilePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSurveyTile(pkt)));
        r.playToClient(SurveyOpenPacket.TYPE, SurveyOpenPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSurveyOpen(pkt)));

        r.playToServer(SurveyRequestPacket.TYPE, SurveyRequestPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(SpawnMissilePacket.TYPE, SpawnMissilePacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(DronePadConfigPacket.TYPE, DronePadConfigPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(CameraControlPacket.TYPE, CameraControlPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(CameraCapabilityPacket.TYPE, CameraCapabilityPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
    }
}
