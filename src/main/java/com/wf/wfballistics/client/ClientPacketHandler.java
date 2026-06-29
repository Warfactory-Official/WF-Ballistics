package com.wf.wfballistics.client;

import com.wf.wfballistics.aef.standard.ExplosionEffectStandard;
import com.wf.wfballistics.client.fx.WFEffects;
import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.ExplosionBlockFXPacket;
import com.wf.wfballistics.network.ExplosionKnockbackPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * Client-only sink for the mod's effect packets. Kept in its own class and only ever reached through
 * {@code DistExecutor} so the dedicated server never classloads {@link Minecraft} and friends.
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() { }

    public static void handleKnockback(ExplosionKnockbackPacket pkt) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setDeltaMovement(player.getDeltaMovement().add(pkt.x, pkt.y, pkt.z));
            player.hurtMarked = true; // makes the client push the new velocity back to the server
        }
    }

    public static void handleBlockFX(ExplosionBlockFXPacket pkt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            ExplosionEffectStandard.performClient(level, pkt.x, pkt.y, pkt.z, pkt.size, pkt.blocks);
        }
    }

    public static void handleAuxParticle(AuxParticlePacket pkt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            WFEffects.dispatch(pkt.effect, level, pkt.x, pkt.y, pkt.z, pkt.data);
        }
    }
}
