package com.wf.wflib.rail.excavate;

import com.mojang.logging.LogUtils;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.rail.RailConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carves terrain for one level, taking the cheap path when nobody is looking and the correct one when they are. */
public final class ExcavationService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, ExcavationService> BY_LEVEL = new HashMap<>();

    private static ExecutorService pool;

    /** One chunk column of one carve request: the atomic unit of work and of abort. */
    private record Column(CarveVolume volume, LightingPolicy lighting, ChunkPos pos) {
    }

    /** A chunk's stored tag and the moment it arrived, so the disk can be told apart from the backlog. */
    private record Read(Optional<CompoundTag> tag, long at) {
    }

    /** A finished worker handing its outcome back to the server thread. */
    private record Finished(Column column, ChunkClaim claim, StoredChunkCarver.Outcome outcome, long nanos,
                            Throwable error) {
    }

    private final ClaimRegistry claims = new ClaimRegistry();
    private final CarveStats stats = new CarveStats();
    private final Deque<Column> pending = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Finished> finished = new ConcurrentLinkedQueue<>();
    private final Map<Long, LoadedChunkCarver> attended = new LinkedHashMap<>();
    private final Map<Long, Column> attendedColumns = new LinkedHashMap<>();

    private ExcavationService() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Start the shared carve pool. Server thread, at level load. */
    public static void startup() {
        shutdown();
        WorldThread.mark();
        int want = RailConfig.EXCAVATION_WORKERS.get();
        int workers = Math.max(1, Math.min(want, Runtime.getRuntime().availableProcessors() - 2));
        pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "wflib-excavate");
            t.setDaemon(true);
            return t;
        });
        LOGGER.debug("[wflib] excavation pool started with {} workers", workers);
    }

    public static void shutdown() {
        for (ExcavationService service : BY_LEVEL.values()) {
            service.claims.clear();
        }
        BY_LEVEL.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** @return the excavator for this level, creating it on first use. */
    public static ExcavationService of(ServerLevel level) {
        WorldThread.assertOn("getting a level's excavator");
        return BY_LEVEL.computeIfAbsent(level.dimension(), key -> new ExcavationService());
    }

    /** Tick the excavator for this level, but only if one was ever asked for. */
    public static void tickIfPresent(ServerLevel level) {
        ExcavationService service = BY_LEVEL.get(level.dimension());
        if (service != null) {
            service.tick(level);
        }
    }

    /** Revoke any claim on a chunk that has just loaded. */
    public static void onChunkLoad(ServerLevel level, ChunkPos pos) {
        ExcavationService service = BY_LEVEL.get(level.dimension());
        if (service != null) {
            service.claims.revoke(pos, ChunkClaim.Revocation.CHUNK_LOADED);
        }
    }

    // ---------------------------------------------------------------- submission

    /**
     * Queue a volume for removal.
     *
     * @return how many chunk columns the volume touches
     */
    public int submit(ServerLevel level, CarveVolume volume, LightingPolicy lighting) {
        WorldThread.assertOn("submitting a carve");
        BoundingBox box = volume.bounds();
        int minX = box.minX() >> 4;
        int maxX = box.maxX() >> 4;
        int minZ = box.minZ() >> 4;
        int maxZ = box.maxZ() >> 4;
        int columns = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                this.pending.add(new Column(volume, lighting, new ChunkPos(cx, cz)));
                columns++;
            }
        }
        return columns;
    }

    public CarveStats stats() {
        return this.stats;
    }

    public int pending() {
        return this.pending.size();
    }

    public int inFlight() {
        return this.claims.inFlight();
    }

    /** @return columns being carved the attended way, which are neither queued nor in flight. */
    public int attended() {
        return this.attended.size();
    }

    /** Drop everything queued and revoke everything running. */
    public void cancel() {
        this.pending.clear();
        this.attended.clear();
        this.attendedColumns.clear();
        this.claims.revokeAll(ChunkClaim.Revocation.CANCELLED);
    }

    // ---------------------------------------------------------------- the tick

    /** Server thread. Sweeps claims, lands finished work, then dispatches more. */
    public void tick(ServerLevel level) {
        WorldThread.assertOn("ticking the excavator");
        this.claims.revokeNearPlayers(level, RailConfig.CLAIM_KEEPOUT.get());
        drainFinished();
        advanceAttended(level);
        dispatch(level);
    }

    private void drainFinished() {
        Finished done;
        while ((done = this.finished.poll()) != null) {
            this.claims.release(done.claim());
            if (done.error() != null) {
                LOGGER.warn("[wflib] carve of {} failed", done.column().pos(), done.error());
                continue;
            }
            if (done.outcome() == null) {
                // Aborted: the claim went away before the store.
                this.stats.recordAborted(done.nanos());
                ChunkClaim.Revocation why = done.claim().revocation();
                if (why == null || why.retry()) {
                    // The chunk still wants carving; it just has to be done the attended way now.
                    this.pending.addLast(done.column());
                }
                continue;
            }
            this.stats.recordStored(done.outcome().cellsCleared(), done.outcome().blockEntitiesRemoved(),
                    done.nanos());
            if (!done.outcome().refusals().isEmpty()) {
                this.stats.recordRefusals(done.outcome().refusals().size());
                LOGGER.warn("[wflib] carve of {} left {} section(s) alone: {}", done.column().pos(),
                        done.outcome().refusals().size(), done.outcome().refusals());
            }
        }
    }

    private void advanceAttended(ServerLevel level) {
        if (this.attended.isEmpty()) {
            return;
        }
        int budget = RailConfig.ATTENDED_BLOCK_BUDGET.get();
        boolean drop = RailConfig.DROP_ATTENDED_BLOCKS.get();
        var it = this.attended.entrySet().iterator();
        while (it.hasNext() && budget > 0) {
            var entry = it.next();
            LoadedChunkCarver carver = entry.getValue();
            int before = carver.broken();
            boolean complete = carver.advance(level, budget, drop);
            int spent = carver.broken() - before;
            budget -= spent;
            this.stats.recordAttended(spent);
            if (complete) {
                it.remove();
                this.attendedColumns.remove(entry.getKey());
            }
        }
    }

    private void dispatch(ServerLevel level) {
        boolean offThread = RailConfig.EXCAVATION_ENABLED.get() && pool != null;
        int max = RailConfig.EXCAVATION_IN_FLIGHT.get();
        while (!this.pending.isEmpty() && this.claims.inFlight() < max) {
            Column column = this.pending.peekFirst();
            long key = column.pos().toLong();
            if (this.attended.containsKey(key)) {
                // Already being carved the attended way; leave it queued behind that.
                break;
            }
            if (!offThread || ClaimRegistry.isLoaded(level, column.pos())) {
                this.pending.pollFirst();
                this.attended.put(key, new LoadedChunkCarver(column.volume(), column.pos(), level));
                this.attendedColumns.put(key, column);
                continue;
            }
            ChunkClaim claim = this.claims.claim(level, column.pos());
            if (claim == null) {
                break;
            }
            this.pending.pollFirst();
            launch(level, column, claim);
        }
    }

    // ---------------------------------------------------------------- the worker

    /** Read, carve, store off the server thread, re-checking the claim before the store. */
    private void launch(ServerLevel level, Column column, ChunkClaim claim) {
        long started = System.nanoTime();
        var chunkMap = level.getChunkSource().chunkMap;
        chunkMap.read(column.pos())
                .thenApply(stored -> new Read(stored, System.nanoTime()))
                .thenApplyAsync(read -> carve(chunkMap, column, claim, read.tag(), started, read.at()), pool)
                .whenComplete((outcome, error) ->
                        this.finished.add(new Finished(column, claim, outcome, System.nanoTime() - started,
                                unwrap(error))));
    }

    private StoredChunkCarver.Outcome carve(net.minecraft.server.level.ChunkMap chunkMap, Column column,
                                            ChunkClaim claim, Optional<CompoundTag> stored,
                                            long started, long readDone) {
        WorldThread.assertOff("carving a stored chunk");
        if (claim.revoked()) {
            return null;
        }
        if (stored.isEmpty()) {
            // Never generated. Skipped deliberately: generating it here would be far dearer than the carve.
            return new StoredChunkCarver.Outcome(0, 0, 0, java.util.List.of());
        }
        boolean timing = RailConfig.EXCAVATION_STATS.get();
        long begun = timing ? System.nanoTime() : 0L;
        CompoundTag tag = stored.get();
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(tag, column.pos(), column.volume(), column.lighting());
        long carved = timing ? System.nanoTime() : 0L;
        if (!outcome.changed()) {
            if (timing) {
                this.stats.recordPhases(readDone - started, begun - readDone, carved - begun, 0L);
            }
            return outcome;
        }
        // Last possible moment to notice the chunk is no longer ours.
        if (claim.revoked()) {
            return null;
        }
        long handoff = System.nanoTime();
        var write = chunkMap.write(column.pos(), tag);
        if (timing) {
            write.whenComplete((done, error) -> this.stats.recordFlush(System.nanoTime() - handoff));
            this.stats.recordPhases(readDone - started, begun - readDone, carved - begun,
                    System.nanoTime() - handoff);
        }
        return outcome;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
    }
}
