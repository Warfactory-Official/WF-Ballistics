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
        r.playToClient(com.wf.wflib.grenade.FlashPacket.TYPE, com.wf.wflib.grenade.FlashPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.grenade.client.Flashbang.receive(pkt)));
        r.playToClient(SoundAtPacket.TYPE, SoundAtPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSoundAt(pkt)));
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

        r.playToClient(TelemetryPacket.TYPE, TelemetryPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleTelemetry(pkt)));
        r.playToClient(com.wf.wflib.telemetry.TelemetryLogPacket.TYPE,
                com.wf.wflib.telemetry.TelemetryLogPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.TelemetryApp.accept(pkt)));
        r.playToServer(com.wf.wflib.telemetry.TelemetryLogRequestPacket.TYPE,
                com.wf.wflib.telemetry.TelemetryLogRequestPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));

        r.playToClient(SurveyTilePacket.TYPE, SurveyTilePacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSurveyTile(pkt)));
        r.playToClient(SurveyOpenPacket.TYPE, SurveyOpenPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> ClientPacketHandler.handleSurveyOpen(pkt)));

        r.playToServer(com.wf.wflib.nvg.NvgActionPacket.TYPE, com.wf.wflib.nvg.NvgActionPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(SurveyRequestPacket.TYPE, SurveyRequestPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(SpawnMissilePacket.TYPE, SpawnMissilePacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(DronePadConfigPacket.TYPE, DronePadConfigPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.PdaRadarPacket.TYPE, com.wf.wflib.pda.PdaRadarPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.RadarApp.accept(pkt)));
        r.playToServer(com.wf.wflib.pda.PdaRadarSubscribePacket.TYPE,
                com.wf.wflib.pda.PdaRadarSubscribePacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.markers.MarkerSyncPacket.TYPE,
                com.wf.wflib.pda.markers.MarkerSyncPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.MarkerClient.accept(pkt)));
        r.playToServer(com.wf.wflib.pda.markers.MarkerEditPacket.TYPE,
                com.wf.wflib.pda.markers.MarkerEditPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(com.wf.wflib.pda.PdaCamerasRequestPacket.TYPE, com.wf.wflib.pda.PdaCamerasRequestPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.sat.SatStatusPacket.TYPE, com.wf.wflib.pda.sat.SatStatusPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.SatellitesApp.accept(pkt)));
        r.playToClient(com.wf.wflib.pda.sat.SatReplyPacket.TYPE, com.wf.wflib.pda.sat.SatReplyPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.SatellitesApp.accept(pkt)));
        r.playToServer(com.wf.wflib.pda.sat.SpotRequestPacket.TYPE,
                com.wf.wflib.pda.sat.SpotRequestPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.sat.SpotOpenPacket.TYPE, com.wf.wflib.pda.sat.SpotOpenPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.pda.client.SatellitesApp.spot(pkt.feedId())));
        r.playToServer(com.wf.wflib.pda.sat.SatCommandPacket.TYPE,
                com.wf.wflib.pda.sat.SatCommandPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(com.wf.wflib.pda.drone.DroneStatusRequestPacket.TYPE,
                com.wf.wflib.pda.drone.DroneStatusRequestPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.drone.DroneStatusPacket.TYPE,
                com.wf.wflib.pda.drone.DroneStatusPacket.STREAM_CODEC, (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.pda.client.DronesApp.accept(pkt)));
        r.playToClient(com.wf.wflib.laser.LaserBeamPacket.TYPE, com.wf.wflib.laser.LaserBeamPacket.STREAM_CODEC,
                (pkt, ctx) -> ctx.enqueueWork(() -> com.wf.wflib.client.fx.LaserBeams.accept(pkt)));
        r.playToServer(com.wf.wflib.pda.drone.DroneActionPacket.TYPE,
                com.wf.wflib.pda.drone.DroneActionPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(com.wf.wflib.pda.drone.DroneDispatchPacket.TYPE,
                com.wf.wflib.pda.drone.DroneDispatchPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.drone.DroneDispatchReplyPacket.TYPE,
                com.wf.wflib.pda.drone.DroneDispatchReplyPacket.STREAM_CODEC, (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.pda.client.DronesApp.reply(pkt.line())));
        r.playToServer(com.wf.wflib.pda.terminal.TerminalCommandPacket.TYPE,
                com.wf.wflib.pda.terminal.TerminalCommandPacket.STREAM_CODEC, (pkt, ctx) -> pkt.handle(ctx));
        r.playToClient(com.wf.wflib.pda.terminal.TerminalReplyPacket.TYPE,
                com.wf.wflib.pda.terminal.TerminalReplyPacket.STREAM_CODEC, (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.pda.client.TerminalApp.accept(pkt.lines())));
        r.playToClient(com.wf.wflib.pda.alerts.PdaAlertPacket.TYPE,
                com.wf.wflib.pda.alerts.PdaAlertPacket.STREAM_CODEC, (pkt, ctx) -> ctx.enqueueWork(
                        () -> com.wf.wflib.pda.client.PdaAlerts.post(pkt.message(), pkt.tier())));
        r.playToServer(CameraControlPacket.TYPE, CameraControlPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
        r.playToServer(CameraCapabilityPacket.TYPE, CameraCapabilityPacket.STREAM_CODEC,
                (pkt, ctx) -> pkt.handle(ctx));
    }
}
