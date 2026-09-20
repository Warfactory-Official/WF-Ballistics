package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.atomic.AtomicReference;

/** One worker's exclusive hold on an unloaded chunk while it rewrites that chunk's stored NBT. */
public final class ChunkClaim {

    /** Why a claim stopped being valid. */
    public enum Revocation {
        /** The chunk was loaded, so its in-memory copy is now authoritative and ours is stale. */
        CHUNK_LOADED,
        /** A player came within the keep-out radius; the chunk is about to load. */
        PLAYER_NEAR,
        /** The level is shutting down. */
        SHUTDOWN,
        /** The job that wanted this carve was cancelled. */
        CANCELLED;

        /** @return whether the column should go back in the queue rather than be dropped. */
        public boolean retry() {
            return this == CHUNK_LOADED || this == PLAYER_NEAR;
        }
    }

    private final ChunkPos pos;
    private final long claimedAt;
    private final AtomicReference<Revocation> revoked = new AtomicReference<>();

    ChunkClaim(ChunkPos pos, long claimedAt) {
        this.pos = pos;
        this.claimedAt = claimedAt;
    }

    public ChunkPos pos() {
        return this.pos;
    }

    /** @return the game tick this claim was taken, for abort-cost accounting. */
    public long claimedAt() {
        return this.claimedAt;
    }

    /** @return why this claim is no longer valid, or null while it still is. */
    public Revocation revocation() {
        return this.revoked.get();
    }

    /** @return whether the worker should abandon what it is doing. */
    public boolean revoked() {
        return this.revoked.get() != null;
    }

    /** Take this claim away from whatever worker holds it. */
    void revoke(Revocation why) {
        this.revoked.compareAndSet(null, why);
    }
}
