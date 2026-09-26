package com.wf.wflib.stream;

import com.wf.wflib.mixin.AccessorChunkMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunks sent to a player outside their vanilla view, shared by every streamer.
 * <p>
 * Invariants: forget sent iff no owner and not in vanilla view; vanilla forget suppressed while owned
 * (see {@code MixinPlayerChunkSender}). Client keeps whatever was not forgotten (see
 * {@code MixinClientChunkCache}), so this map is exact.
 */
public final class ChunkStreams {

    private static final Map<ServerPlayer, Viewer> VIEWERS = new IdentityHashMap<>();

    private ChunkStreams() {
    }

    /** One pass's chunk packets, built once however many players receive them. */
    public static final class Encoder {
        private final ServerLevel level;
        private final Long2ObjectMap<Packet<?>> built = new Long2ObjectOpenHashMap<>();
        private int budget;
        private long encodes;

        /** @param budget packets this pass may build; cached ones are free */
        public Encoder(ServerLevel level, int budget) {
            this.level = level;
            this.budget = budget;
        }

        @Nullable
        Packet<?> packet(long pos) {
            Packet<?> packet = this.built.get(pos);
            if (packet != null || this.budget <= 0) {
                return packet;
            }
            LevelChunk chunk = this.level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
            if (chunk == null) {
                return null;
            }
            packet = chunk.getAuxLightManager(chunk.getPos()).sendLightDataTo(
                    new ClientboundLevelChunkWithLightPacket(chunk, this.level.getLightEngine(), null, null));
            this.built.put(pos, packet);
            this.budget--;
            this.encodes++;
            return packet;
        }

        public long encodes() {
            return this.encodes;
        }
    }

    /** @return {@code (2r+1)^2} positions around {@code centre}, nearest ring first */
    public static long[] square(ChunkPos centre, int radius) {
        long[] out = new long[(2 * radius + 1) * (2 * radius + 1)];
        int n = 0;
        out[n++] = centre.toLong();
        for (int ring = 1; ring <= radius; ring++) {
            for (int d = -ring; d < ring; d++) {
                out[n++] = ChunkPos.asLong(centre.x + d, centre.z - ring);
                out[n++] = ChunkPos.asLong(centre.x + ring, centre.z + d);
                out[n++] = ChunkPos.asLong(centre.x - d, centre.z + ring);
                out[n++] = ChunkPos.asLong(centre.x - ring, centre.z - d);
            }
        }
        return out;
    }

    /**
     * Make {@code owner}'s share of this player's streamed terrain exactly {@code window}.
     *
     * @param window positions in send order
     * @param maxSends deliveries this call may make
     * @return deliveries made
     */
    public static int sync(ServerPlayer player, Object owner, long[] window, int maxSends, Encoder encoder) {
        Viewer viewer = viewer(player);
        LongOpenHashSet owned = viewer.byOwner.computeIfAbsent(owner, key -> new LongOpenHashSet());
        LongOpenHashSet wanted = new LongOpenHashSet(window);
        for (LongIterator it = owned.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            if (!wanted.contains(pos)) {
                it.remove();
                viewer.release(player, owner, pos);
            }
        }
        int sends = 0;
        for (long pos : window) {
            Claim claim = viewer.claims.get(pos);
            if (claim == null) {
                claim = new Claim(vanillaHolds(player, pos));
                viewer.claims.put(pos, claim);
                viewer.changed = true;
            }
            if (owned.add(pos)) {
                claim.owners.add(owner);
            }
            if (claim.delivered || sends >= maxSends) {
                continue;
            }
            Packet<?> packet = encoder.packet(pos);
            if (packet != null) {
                player.connection.send(packet);
                claim.delivered = true;
                viewer.changed = true;
                sends++;
            }
        }
        if (owned.isEmpty()) {
            viewer.byOwner.remove(owner);
        }
        viewer.rescanIfChanged(player);
        return sends;
    }

    /** Drop {@code owner}'s share for this player. */
    public static void release(ServerPlayer player, Object owner) {
        Viewer viewer = VIEWERS.get(player);
        if (viewer == null || viewer.level != player.level()) {
            return;
        }
        LongOpenHashSet owned = viewer.byOwner.remove(owner);
        if (owned == null) {
            return;
        }
        for (LongIterator it = owned.iterator(); it.hasNext(); ) {
            viewer.release(player, owner, it.nextLong());
        }
        viewer.rescanIfChanged(player);
        if (viewer.claims.isEmpty()) {
            VIEWERS.remove(player);
        }
    }

    /** Whether this player's client holds the chunk because a streamer sent it. */
    public static boolean streamed(ServerPlayer player, long pos) {
        if (VIEWERS.isEmpty()) {
            return false;
        }
        Viewer viewer = VIEWERS.get(player);
        if (viewer == null || viewer.level != player.level()) {
            return false;
        }
        Claim claim = viewer.claims.get(pos);
        return claim != null && claim.delivered;
    }

    /** Whether any streamer owns this chunk for this player, delivered or not. */
    public static boolean owned(ServerPlayer player, long pos) {
        if (VIEWERS.isEmpty()) {
            return false;
        }
        Viewer viewer = VIEWERS.get(player);
        return viewer != null && viewer.level == player.level() && viewer.claims.containsKey(pos);
    }

    /**
     * Vanilla dropped an owned chunk from its view.
     *
     * @param wasPending vanilla never sent it
     */
    public static void vanillaDropped(ServerPlayer player, long pos, boolean wasPending) {
        Claim claim = VIEWERS.get(player).claims.get(pos);
        if (!wasPending) {
            claim.delivered = true;
        }
    }

    /** @return players that must hear about changes to this chunk though vanilla does not track it for them */
    public static List<ServerPlayer> extraWatchers(ServerLevel level, long pos, List<ServerPlayer> vanilla) {
        List<ServerPlayer> out = null;
        for (Map.Entry<ServerPlayer, Viewer> entry : VIEWERS.entrySet()) {
            Viewer viewer = entry.getValue();
            if (viewer.level != level) {
                continue;
            }
            Claim claim = viewer.claims.get(pos);
            if (claim == null || !claim.delivered || vanilla.contains(entry.getKey())) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(vanilla);
            }
            out.add(entry.getKey());
        }
        return out == null ? vanilla : out;
    }

    public static boolean idle() {
        return VIEWERS.isEmpty();
    }

    /** Client state died with the player's level: forget without packets. */
    public static void drop(ServerPlayer player) {
        VIEWERS.remove(player);
    }

    /** Player object retired, client level kept (same-dimension respawn): forget every delivered chunk. */
    public static void forgetAll(ServerPlayer player) {
        Viewer viewer = VIEWERS.remove(player);
        if (viewer == null || viewer.level != player.level()) {
            return;
        }
        for (Long2ObjectMap.Entry<Claim> entry : viewer.claims.long2ObjectEntrySet()) {
            if (entry.getValue().delivered) {
                player.connection.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(entry.getLongKey())));
            }
        }
    }

    public static void shutdown() {
        VIEWERS.clear();
    }

    /** @return positions delivered to this player, for audits */
    public static LongOpenHashSet delivered(ServerPlayer player) {
        LongOpenHashSet out = new LongOpenHashSet();
        Viewer viewer = VIEWERS.get(player);
        if (viewer != null && viewer.level == player.level()) {
            for (Long2ObjectMap.Entry<Claim> entry : viewer.claims.long2ObjectEntrySet()) {
                if (entry.getValue().delivered) {
                    out.add(entry.getLongKey());
                }
            }
        }
        return out;
    }

    public static int claimCount() {
        int total = 0;
        for (Viewer viewer : VIEWERS.values()) {
            total += viewer.claims.size();
        }
        return total;
    }

    private static boolean vanillaHolds(ServerPlayer player, long pos) {
        return player.getChunkTrackingView().contains(ChunkPos.getX(pos), ChunkPos.getZ(pos))
                && !player.connection.chunkSender.isPending(pos);
    }

    private static Viewer viewer(ServerPlayer player) {
        Viewer viewer = VIEWERS.get(player);
        if (viewer == null || viewer.level != player.level()) {
            viewer = new Viewer((ServerLevel) player.level());
            VIEWERS.put(player, viewer);
        }
        return viewer;
    }

    private static final class Claim {
        private final Set<Object> owners = new ReferenceArraySet<>(2);
        private boolean delivered;

        private Claim(boolean delivered) {
            this.delivered = delivered;
        }
    }

    private static final class Viewer {
        private final ServerLevel level;
        private final Long2ObjectMap<Claim> claims = new Long2ObjectOpenHashMap<>();
        private final Map<Object, LongOpenHashSet> byOwner = new IdentityHashMap<>();
        private boolean changed;

        private Viewer(ServerLevel level) {
            this.level = level;
        }

        private void release(ServerPlayer player, Object owner, long pos) {
            Claim claim = this.claims.get(pos);
            claim.owners.remove(owner);
            if (!claim.owners.isEmpty()) {
                return;
            }
            this.claims.remove(pos);
            this.changed = true;
            if (claim.delivered && !player.getChunkTrackingView().contains(ChunkPos.getX(pos), ChunkPos.getZ(pos))) {
                player.connection.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(pos)));
            }
        }

        /** Entity tracking re-evaluates on movement only; a parked observer's chunk set changing is neither. */
        private void rescanIfChanged(ServerPlayer player) {
            if (!this.changed) {
                return;
            }
            this.changed = false;
            for (Object tracked : ((AccessorChunkMap) this.level.getChunkSource().chunkMap).wfEntityMap().values()) {
                ((StreamTracked) tracked).wfUpdatePlayer(player);
            }
        }
    }
}
