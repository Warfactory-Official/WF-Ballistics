package com.wf.wflib.pda.alerts;

import com.wf.wflib.WFLib;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.alert.Alert;
import com.wf.wflib.recon.alert.AlertTier;
import com.wf.wflib.recon.track.TrackPicture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PDA notifications, server end: {@link #send} for any system; recon alerts (track turned ALARM / WEAPONS_RELEASE) of
 * the carrier's own net within {@link #RECON_RANGE}, while uplinked. Client: toast + buzz.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class PdaAlert {
    public enum Tier { INFO, WARNING, CRITICAL }

    static final double RECON_RANGE = 4096;
    private static final int CARRIER_REFRESH = 20;
    /** Last published picture forwarded, per (dimension, net). */
    private static final Map<Key, Long> FORWARDED = new HashMap<>();
    private static final List<ServerPlayer> CARRIERS = new ArrayList<>();
    private static final Set<Key> SEEN = new HashSet<>();

    private record Key(ResourceKey<Level> dimension, long net) {
    }

    private PdaAlert() {
    }

    /** Only to PDA carriers; no-op otherwise. */
    public static void send(ServerPlayer player, Component message, Tier tier) {
        if (Pda.carries(player)) {
            PacketDistributor.sendToPlayer(player, new PdaAlertPacket(message, tier));
        }
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CARRIER_REFRESH == 0) {
            CARRIERS.clear();
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                if (Pda.carries(player)) {
                    CARRIERS.add(player);
                }
            }
        }
        SEEN.clear();
        for (int i = 0; i < CARRIERS.size(); i++) {
            ServerPlayer player = CARRIERS.get(i);
            if (player.isRemoved()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            long net = ReconOwners.netOf(player);
            Key key = new Key(level.dimension(), net);
            if (!SEEN.add(key)) {
                continue;
            }
            TrackPicture picture = ReconNet.picture(level, net);
            Long last = FORWARDED.put(key, picture.gameTime());
            // First sight (login, new net): its alerts are old news.
            if (last != null && last != picture.gameTime()) {
                forward(level, net, picture);
            }
        }
    }

    private static void forward(ServerLevel level, long net, TrackPicture picture) {
        List<Alert> alerts = picture.alerts();
        if (alerts.isEmpty()) {
            return;
        }
        for (int c = 0; c < CARRIERS.size(); c++) {
            ServerPlayer player = CARRIERS.get(c);
            if (player.isRemoved() || player.serverLevel() != level || ReconOwners.netOf(player) != net
                    || !Uplink.allows(player, net)) {
                continue;
            }
            for (int a = 0; a < alerts.size(); a++) {
                Alert alert = alerts.get(a);
                double dx = alert.x() - player.getX();
                double dz = alert.z() - player.getZ();
                double range = Math.sqrt(dx * dx + dz * dz);
                if (range > RECON_RANGE) {
                    continue;
                }
                long bearing = Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dx, -dz))), 360);
                PacketDistributor.sendToPlayer(player, new PdaAlertPacket(Component.translatable(
                        "wflib.pda.alert.track." + alert.tier().name().toLowerCase(Locale.ROOT),
                        Component.translatable("wflib.pda.contact." + alert.kind().name().toLowerCase(
                                Locale.ROOT)), Math.round(range), String.format(Locale.ROOT,
                                "%03d", bearing)), alert.tier() == AlertTier.CRITICAL ? Tier.CRITICAL : Tier.WARNING));
            }
        }
    }
}
