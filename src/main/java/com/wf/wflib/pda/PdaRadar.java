package com.wf.wflib.pda;

import com.wf.wflib.WFLib;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.scope.ScopeFrame;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Radar app, server end: the subscriber's own net picture around them, while carried and uplinked. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class PdaRadar {
    static final int PERIOD_TICKS = 20;
    static final double MIN_RANGE = 256;
    static final double MAX_RANGE = 8192;
    private static final Map<UUID, Double> SUBSCRIBED = new HashMap<>();

    private PdaRadar() {
    }

    /** {@code range} <= 0 => stop. */
    static void subscribe(ServerPlayer player, double range) {
        if (range <= 0) {
            SUBSCRIBED.remove(player.getUUID());
        } else {
            SUBSCRIBED.put(player.getUUID(), Math.max(MIN_RANGE, Math.min(MAX_RANGE, range)));
            send(player, SUBSCRIBED.get(player.getUUID()));
        }
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (SUBSCRIBED.isEmpty() || event.getServer().getTickCount() % PERIOD_TICKS != 0) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Double>> it = SUBSCRIBED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Double> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            send(player, entry.getValue());
        }
    }

    private static void send(ServerPlayer player, double range) {
        Uplink.Link link = Pda.carries(player) ? Uplink.of(player) : Uplink.Link.NONE;
        long netId = ReconOwners.netOf(player);
        ScopeFrame frame = link == Uplink.Link.NONE ? null : ScopeFrame.of(netId, player.getX(), player.getY(),
                player.getZ(), range, ReconNet.picture(player.serverLevel(), netId));
        PacketDistributor.sendToPlayer(player, new PdaRadarPacket(netId != ReconNet.UNAFFILIATED, link, frame));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIBED.remove(event.getEntity().getUUID());
        Uplink.forget(event.getEntity().getUUID());
    }
}
