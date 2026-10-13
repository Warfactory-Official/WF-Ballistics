package com.wf.wflib.pda.uplink;

import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.SatPayloads;
import com.wf.wflib.orbital.SatView;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A PDA's link into its owner's recon net: ground = within {@link GridNode#linkRange()} of an online node of that
 * net; relay = under the swath of an in-contact relay satellite on it. Own net only, no operator override. Cached
 * {@link #CACHE_TICKS} per player.
 */
public final class Uplink {
    public enum Link {
        NONE, GROUND, RELAY
    }

    static final int CACHE_TICKS = 10;
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private record Cached(long netId, long gameTime, Link link) {
    }

    private Uplink() {
    }

    /** Link to {@link ReconOwners#netOf} the player; {@link Link#NONE} without a net. */
    public static Link of(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long netId = ReconOwners.netOf(player);
        long now = level.getGameTime();
        Cached cached = CACHE.get(player.getUUID());
        if (cached != null && cached.netId == netId && now - cached.gameTime < CACHE_TICKS && now >= cached.gameTime) {
            return cached.link;
        }
        Link link = netId == ReconNet.UNAFFILIATED ? Link.NONE : resolve(level, netId, player);
        CACHE.put(player.getUUID(), new Cached(netId, now, link));
        return link;
    }

    public static boolean allows(ServerPlayer player, long netId) {
        return netId != ReconNet.UNAFFILIATED && netId == ReconOwners.netOf(player) && of(player) != Link.NONE;
    }

    public static void forget(UUID player) {
        CACHE.remove(player);
    }

    static Link resolve(ServerLevel level, long netId, ServerPlayer player) {
        ReconGrid grid = ReconNet.grid(level, netId);
        if (grid != null) {
            double x = player.getX();
            double y = player.getEyeY();
            double z = player.getZ();
            for (GridNode node : grid.nodes()) {
                if (!node.online()) {
                    continue;
                }
                double dx = node.pos().getX() + 0.5 - x;
                double dy = node.eyeY() - y;
                double dz = node.pos().getZ() + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz <= node.linkRange() * node.linkRange()) {
                    return Link.GROUND;
                }
            }
        }
        for (SatView sat : OrbitalNet.overhead(level, netId, player.getX(), player.getZ())) {
            if (sat.inContact() && sat.payload().equals(SatPayloads.RELAY)) {
                return Link.RELAY;
            }
        }
        return Link.NONE;
    }
}
