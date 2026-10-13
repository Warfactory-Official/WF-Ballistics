package com.wf.wflib.client;

import com.wf.wflib.aef.standard.ExplosionEffectStandard;
import com.wf.wflib.client.fx.MissileAudioClient;
import com.wf.wflib.client.fx.WFEffects;
import com.wf.wflib.network.AuxParticlePacket;
import com.wf.wflib.network.ExplosionBlockFXPacket;
import com.wf.wflib.network.ExplosionKnockbackPacket;
import com.wf.wflib.client.render.SimGlyphids;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.scope.ScopeCache;
import com.wf.wflib.network.CameraFeedPacket;
import com.wf.wflib.network.ScopeFramePacket;
import com.wf.wflib.network.MissileFlightAudioPacket;
import com.wf.wflib.network.SoundAtPacket;
import com.wf.wflib.audio.client.Sounds;
import com.wf.wflib.network.SimGlyphidSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void handleKnockback(ExplosionKnockbackPacket pkt) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setDeltaMovement(player.getDeltaMovement().add(pkt.x(), pkt.y(), pkt.z()));
            player.hurtMarked = true; // hurtMarked => client resyncs velocity to server
        }
    }

    public static void handleSimGlyphids(SimGlyphidSyncPacket pkt) {
        SimGlyphids.accept(pkt);
    }

    public static void handleScopeFrame(ScopeFramePacket pkt) {
        ScopeCache.accept(pkt.frame());
    }

    public static void handleSurveyTile(com.wf.wflib.network.SurveyTilePacket pkt) {
        com.wf.wflib.client.survey.SurveyCache.accept(pkt);
    }

    public static void handleSurveyOpen(com.wf.wflib.network.SurveyOpenPacket pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.wf.wflib.client.survey.SurveyScreen(pkt.netId(), pkt.centreX(), pkt.centreZ()));
    }

    public static void handleTelemetry(com.wf.wflib.network.TelemetryPacket pkt) {
        com.wf.wflib.telemetry.client.TelemetryClient.accept(pkt);
    }

    public static void handleReconMap(com.wf.wflib.network.ReconMapPacket pkt) {
        com.wf.wflib.client.recon.ReconMapClient.accept(pkt.dimension(), pkt.view());
    }

    /** Sole call to {@code CameraFeedCache.accept}. */
    public static void handleCameraFeed(CameraFeedPacket pkt) {
        CameraFeedCache.accept(pkt.feed());
    }

    public static void handleCameraPanel(com.wf.wflib.network.CameraPanelPacket pkt) {
        com.wf.wflib.pda.client.CamerasApp.accept(pkt.feedIds(), pkt.selected());
    }

    public static void handleBlockFX(ExplosionBlockFXPacket pkt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            ExplosionEffectStandard.performClient(level, pkt.x(), pkt.y(), pkt.z(), pkt.size(), pkt.blocks());
        }
    }

    public static void handleAuxParticle(AuxParticlePacket pkt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            WFEffects.dispatch(pkt.effect(), level, pkt.x(), pkt.y(), pkt.z(), pkt.data());
        }
    }

    public static void handleSoundAt(SoundAtPacket pkt) {
        Sounds.handle(pkt);
    }

    public static void handleMissileAudio(MissileFlightAudioPacket pkt) {
        if (Minecraft.getInstance().level != null) {
            MissileAudioClient.upsert(pkt);
        }
    }
}
