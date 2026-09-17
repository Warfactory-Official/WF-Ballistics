package com.wf.wfballistics.client;

import com.wf.wfballistics.aef.standard.ExplosionEffectStandard;
import com.wf.wfballistics.client.fx.MissileAudioClient;
import com.wf.wfballistics.client.fx.WFEffects;
import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.ExplosionBlockFXPacket;
import com.wf.wfballistics.network.ExplosionKnockbackPacket;
import com.wf.wfballistics.client.render.SimGlyphids;
import com.wf.wfballistics.client.cam.CameraFeedCache;
import com.wf.wfballistics.client.scope.ScopeCache;
import com.wf.wfballistics.network.CameraFeedPacket;
import com.wf.wfballistics.network.ScopeFramePacket;
import com.wf.wfballistics.network.MissileFlightAudioPacket;
import com.wf.wfballistics.network.SimGlyphidSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Client-only sink for the mod's effect packets. */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void handleKnockback(ExplosionKnockbackPacket pkt) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setDeltaMovement(player.getDeltaMovement().add(pkt.x(), pkt.y(), pkt.z()));
            player.hurtMarked = true; // makes the client push the new velocity back to the server
        }
    }

    public static void handleSimGlyphids(SimGlyphidSyncPacket pkt) {
        SimGlyphids.accept(pkt);
    }

    public static void handleScopeFrame(ScopeFramePacket pkt) {
        ScopeCache.accept(pkt.frame());
    }

    public static void handleSurveyTile(com.wf.wfballistics.network.SurveyTilePacket pkt) {
        com.wf.wfballistics.client.survey.SurveyCache.accept(pkt);
    }

    public static void handleSurveyOpen(com.wf.wfballistics.network.SurveyOpenPacket pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.wf.wfballistics.client.survey.SurveyScreen(pkt.netId(), pkt.centreX(), pkt.centreZ()));
    }

    public static void handleReconMap(com.wf.wfballistics.network.ReconMapPacket pkt) {
        com.wf.wfballistics.client.recon.ReconMapClient.accept(pkt.dimension(), pkt.view());
    }

    /** The one place a feed payload is turned back into state. */
    public static void handleCameraFeed(CameraFeedPacket pkt) {
        CameraFeedCache.accept(pkt.feed());
    }

    public static void handleCameraPanel(com.wf.wfballistics.network.CameraPanelPacket pkt) {
        com.wf.wfballistics.client.gui.CameraScreenOpener.panel(pkt.feedIds(), pkt.selected());
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

    public static void handleMissileAudio(MissileFlightAudioPacket pkt) {
        if (Minecraft.getInstance().level != null) {
            MissileAudioClient.upsert(pkt);
        }
    }
}
