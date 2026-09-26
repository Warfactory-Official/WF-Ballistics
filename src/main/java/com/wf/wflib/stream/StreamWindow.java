package com.wf.wflib.stream;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** One streamed square and its audience; its own owner identity in {@link ChunkStreams}. */
public final class StreamWindow {

    private long centre = Long.MIN_VALUE;
    private long stampedAt = Long.MIN_VALUE;
    private Set<ServerPlayer> players = Collections.emptySet();

    /** @return true when tickets must be (re)stamped: centre moved or {@code refresh} ticks passed */
    public boolean restamp(ChunkPos next, long now, int refresh) {
        if (this.centre == next.toLong() && now - this.stampedAt < refresh) {
            return false;
        }
        this.centre = next.toLong();
        this.stampedAt = now;
        return true;
    }

    /**
     * Stream the square to {@code watching}, release anyone who left; one encoder for all.
     *
     * @return deliveries
     */
    public int publish(ServerLevel level, ChunkPos centre, int radius, Collection<ServerPlayer> watching,
                       int maxSendsPerPlayer, ChunkStreams.Encoder encoder) {
        long[] square = ChunkStreams.square(centre, radius);
        Set<ServerPlayer> now = Collections.newSetFromMap(new IdentityHashMap<>());
        int sends = 0;
        for (ServerPlayer player : watching) {
            if (player.level() == level) {
                now.add(player);
                sends += ChunkStreams.sync(player, this, square, maxSendsPerPlayer, encoder);
            }
        }
        for (ServerPlayer player : this.players) {
            if (!now.contains(player)) {
                ChunkStreams.release(player, this);
            }
        }
        this.players = now;
        return sends;
    }

    public void close() {
        for (ServerPlayer player : this.players) {
            ChunkStreams.release(player, this);
        }
        this.players = Collections.emptySet();
    }

    public Set<ServerPlayer> players() {
        return this.players;
    }

    public long centre() {
        return this.centre;
    }
}
