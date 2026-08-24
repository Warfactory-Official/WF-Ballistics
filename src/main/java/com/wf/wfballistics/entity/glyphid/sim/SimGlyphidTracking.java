package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.network.SimGlyphidSyncPacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Ships the record tier to the clients that can see it.
 *
 * <p>Nothing about the sim tier is meant to be visible, so records are drawn out to the same distance
 * vanilla would have tracked the entities they replaced. See {@code SimGlyphidVisual} for the drawing and
 * {@link SimGlyphidSyncPacket} for the wire format.
 *
 * <p>Per player rather than per chunk: a record has no chunk to be tracked from, which is the point of it.
 */
public final class SimGlyphidTracking {

    /** Furthest a record is sent. The audience is the smaller of this and the client's view distance. */
    private static final double MAX_RANGE = 256.0;
    /** Most records in one packet. A cap that is hit hides glyphids, so it only bounds the packet. */
    private static final int MAX_PER_PACKET = 2048;

    private SimGlyphidTracking() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SimGlyphidSyncPacket.INTERVAL != 0L) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        List<SimGlyphid> all = SimGlyphidRegistry.get(level).view();
        double range = Math.min(MAX_RANGE, level.getServer().getPlayerList().getViewDistance() * 16.0);
        double limit = range * range;

        for (ServerPlayer player : players) {
            // An empty packet still goes out: it is what tells a client the swarm it drew has gone.
            List<SimGlyphidSyncPacket.Entry> visible = new ArrayList<>();
            int originX = Mth.floor(player.getX());
            int originY = Mth.floor(player.getY());
            int originZ = Mth.floor(player.getZ());

            for (int i = 0; i < all.size() && visible.size() < MAX_PER_PACKET; i++) {
                SimGlyphid sim = all.get(i);
                double dx = sim.x - player.getX();
                double dz = sim.z - player.getZ();
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                visible.add(new SimGlyphidSyncPacket.Entry(
                        sim.id,
                        quantise(sim.x - originX),
                        quantise(sim.y - originY),
                        quantise(sim.z - originZ),
                        // Wrapped before it is quantised. The heading comes out of an atan2 minus ninety, so
                        // it ranges past -180; unwrapped, everything between -270 and -180 overflows the byte
                        // and comes back out of the wire pointing the other way.
                        (byte) Mth.floor(Mth.wrapDegrees(sim.yRot) * 256.0F / 360.0F),
                        (byte) sim.caste.ordinal()));
            }
            if (!visible.isEmpty() || SimGlyphidClientState.wasSending(player)) {
                WFNetwork.sendToPlayer(player,
                        new SimGlyphidSyncPacket(originX, originY, originZ, visible));
            }
            SimGlyphidClientState.setSending(player, !visible.isEmpty());
        }
    }

    private static short quantise(double blocks) {
        return (short) Mth.clamp(Math.round(blocks / SimGlyphidSyncPacket.QUANTUM),
                Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Which players are currently being sent glyphids, so the one packet that empties a client's swarm is
     * sent and the empty ones after it are not.
     */
    private static final class SimGlyphidClientState {

        private static final java.util.Set<java.util.UUID> SENDING =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

        static boolean wasSending(ServerPlayer player) {
            return SENDING.contains(player.getUUID());
        }

        static void setSending(ServerPlayer player, boolean sending) {
            if (sending) {
                SENDING.add(player.getUUID());
            } else {
                SENDING.remove(player.getUUID());
            }
        }
    }

    /**
     * @return how many castes the wire format can name, so the client can reject an out-of-range index rather
     * than index off the end of the enum.
     */
    public static int casteCount() {
        return GlyphidCaste.VALUES.length;
    }
}
