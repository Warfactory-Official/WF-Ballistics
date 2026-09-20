package com.wf.wflib.rail.excavate;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which chunks of one level are being rewritten off-thread, and who gets to say stop. */
public final class ClaimRegistry {

    private final Map<Long, ChunkClaim> claims = new ConcurrentHashMap<>();

    /**
     * Claim a chunk for off-thread rewriting.
     *
     * @return the claim, or null when the chunk is already claimed or is loaded and therefore out of bounds
     */
    public ChunkClaim claim(ServerLevel level, ChunkPos pos) {
        WorldThread.assertOn("taking an excavation claim");
        if (isLoaded(level, pos)) {
            return null;
        }
        long key = pos.toLong();
        if (this.claims.containsKey(key)) {
            return null;
        }
        ChunkClaim claim = new ChunkClaim(pos, level.getGameTime());
        this.claims.put(key, claim);
        return claim;
    }

    /** Give a claim back once its worker has finished or aborted. */
    public void release(ChunkClaim claim) {
        this.claims.remove(claim.pos().toLong(), claim);
    }

    /** Revoke the claim on this chunk, if any. */
    public void revoke(ChunkPos pos, ChunkClaim.Revocation why) {
        ChunkClaim claim = this.claims.get(pos.toLong());
        if (claim != null) {
            claim.revoke(why);
        }
    }

    public void revokeAll(ChunkClaim.Revocation why) {
        for (ChunkClaim claim : this.claims.values()) {
            claim.revoke(why);
        }
    }

    /** Drop every claim a player has come close to, before the chunk itself loads. */
    public void revokeNearPlayers(ServerLevel level, int keepoutChunks) {
        WorldThread.assertOn("sweeping excavation claims against players");
        if (this.claims.isEmpty()) {
            return;
        }
        for (ChunkClaim claim : this.claims.values()) {
            ChunkPos pos = claim.pos();
            for (ServerPlayer player : level.players()) {
                ChunkPos at = player.chunkPosition();
                if (Math.abs(at.x - pos.x) <= keepoutChunks && Math.abs(at.z - pos.z) <= keepoutChunks) {
                    claim.revoke(ChunkClaim.Revocation.PLAYER_NEAR);
                    break;
                }
            }
        }
    }

    public int inFlight() {
        return this.claims.size();
    }

    public boolean isClaimed(ChunkPos pos) {
        return this.claims.containsKey(pos.toLong());
    }

    public void clear() {
        revokeAll(ChunkClaim.Revocation.SHUTDOWN);
        this.claims.clear();
    }

    /** @return whether this chunk is resident right now; a ticket query would also count one on its way. */
    public static boolean isLoaded(ServerLevel level, ChunkPos pos) {
        return level.getChunkSource().getChunkNow(pos.x, pos.z) != null;
    }
}
