package com.wf.wflib.round.terrain;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-disk terrain of a level's unloaded chunks, one decoded column per chunk. Server thread owns the cache; decodes
 * run on {@link #EXECUTOR}. Invalidated on chunk load, unload and save.
 * <ul>
 *   <li>Saved chunk ({@code ChunkDataEvent.Save}) => read through the IO worker (pending write if any) until an IO
 *   worker sync issued after the save completes; else region file directly.</li>
 *   <li>Stale window: chunk off FULL -> unload save (needs tick spare time): previous save.</li>
 *   <li>Never saved (ungenerated) => air. Failed read/decode => retried {@link #MAX_ATTEMPTS} times, then air.</li>
 *   <li>Air columns: own FIFO set ({@link #MAX_ABSENT}), never in the column LRU: misses flying on over ungenerated
 *   ground must not evict decoded terrain.</li>
 * </ul>
 */
public final class AsyncTerrainSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** LRU bounds: columns, and heap per {@link #cachedBytes}. */
    static final int MAX_COLUMNS = 2048;
    static final long MAX_BYTES = 64L << 20;
    /** Air columns remembered; oldest dropped (re-looked up: one stat or read). ~1 MB. */
    static final int MAX_ABSENT = 1 << 15;
    /** Requests beyond this wait for a free slot. */
    public static final int MAX_IN_FLIGHT = 256;
    static final int MAX_ATTEMPTS = 3;
    /** Retry n waits n * this, ticks. */
    static final int RETRY_TICKS = 5;
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "WFLib terrain " + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final Map<ServerLevel, AsyncTerrainSource> LEVELS = new IdentityHashMap<>();

    private final ServerLevel level;
    private final Path regionDir;
    private final HolderGetter<Block> blocks;
    private final int minSection;
    private final SectionSolidity[] air;
    private final Long2ObjectLinkedOpenHashMap<SectionSolidity[]> columns = new Long2ObjectLinkedOpenHashMap<>();
    private final LongLinkedOpenHashSet absent = new LongLinkedOpenHashSet();
    private long heldBytes;
    /** Chunk -> request token; a result lands only while its token is current. */
    private final Long2ObjectOpenHashMap<Object> inFlight = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Decoded> done = new ConcurrentLinkedQueue<>();
    /** Regions whose file exists (never deleted while running). */
    private final LongOpenHashSet regions = new LongOpenHashSet();
    /** Saved chunk -> save generation; read through the IO worker while present. */
    private final Long2LongOpenHashMap saved = new Long2LongOpenHashMap();
    private long generation;
    @Nullable
    private CompletableFuture<Void> sync;
    private long syncedGeneration;
    private final Long2IntOpenHashMap failures = new Long2IntOpenHashMap();
    /** Failed chunk -> game time of its next attempt. */
    private final Long2LongOpenHashMap retryAt = new Long2LongOpenHashMap();
    private long decodeNanos;
    private int decodes;
    private long stats;

    private record Decoded(long chunk, Object token, @Nullable SectionSolidity[] column, @Nullable Throwable error,
                           long nanos) {
    }

    private AsyncTerrainSource(ServerLevel level) {
        this.level = level;
        this.regionDir = RegionReader.directory(level);
        this.blocks = level.holderLookup(Registries.BLOCK);
        this.minSection = level.getMinSection();
        this.air = new SectionSolidity[level.getSectionsCount()];
        Arrays.fill(this.air, SectionSolidity.AIR);
    }

    /** Server thread. */
    public static AsyncTerrainSource of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, AsyncTerrainSource::new);
    }

    @Nullable
    public static AsyncTerrainSource existing(ServerLevel level) {
        return LEVELS.get(level);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    public int minSection() {
        return minSection;
    }

    /**
     * Decoded column, or null after queueing its decode (or while the queue is full or a failed read backs off). No
     * region file => air at once (one stat per region with a file, per column without). Drains finished decodes
     * first.
     */
    @Nullable
    public SectionSolidity[] column(int cx, int cz) {
        drain();
        long key = ChunkPos.asLong(cx, cz);
        if (absent.contains(key)) {
            return air;
        }
        SectionSolidity[] column = columns.getAndMoveToFirst(key);
        if (column != null || inFlight.containsKey(key) || inFlight.size() >= MAX_IN_FLIGHT
                || retryAt.containsKey(key) && retryAt.get(key) > level.getGameTime()) {
            return column;
        }
        if (!saved.containsKey(key) && !regionExists(cx, cz)) {
            store(key, air);
            return air;
        }
        request(key, cx, cz);
        return null;
    }

    /** Column if decoded; no request, no LRU touch. */
    @Nullable
    public SectionSolidity[] peek(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        return absent.contains(key) ? air : columns.get(key);
    }

    private boolean regionExists(int cx, int cz) {
        long region = ChunkPos.asLong(cx >> 5, cz >> 5);
        if (regions.contains(region)) {
            return true;
        }
        stats++;
        if (Files.exists(RegionReader.file(regionDir, cx, cz))) {
            regions.add(region);
            return true;
        }
        return false;
    }

    private void request(long key, int cx, int cz) {
        Object token = new Object();
        inFlight.put(key, token);
        if (saved.containsKey(key)) {
            long t0 = System.nanoTime();
            level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).whenCompleteAsync((tag, error) -> {
                if (error != null) {
                    done.add(new Decoded(key, token, null, error, System.nanoTime() - t0));
                } else {
                    decode(key, token, cx, cz, tag.orElse(null), t0);
                }
            }, EXECUTOR);
            return;
        }
        EXECUTOR.execute(() -> {
            long t0 = System.nanoTime();
            CompoundTag tag;
            try {
                tag = RegionReader.read(regionDir, cx, cz);
            } catch (Exception e) {
                done.add(new Decoded(key, token, null, e, System.nanoTime() - t0));
                return;
            }
            decode(key, token, cx, cz, tag, t0);
        });
    }

    /** Worker thread. */
    private void decode(long key, Object token, int cx, int cz, @Nullable CompoundTag tag, long t0) {
        try {
            SectionSolidity[] column = tag == null ? air : SolidityDecoder.decode(
                    RegionReader.requirePosition(tag, cx, cz), blocks, minSection, air.length);
            done.add(new Decoded(key, token, column, null, System.nanoTime() - t0));
        } catch (Exception e) {
            done.add(new Decoded(key, token, null, e, System.nanoTime() - t0));
        }
    }

    private void drain() {
        for (Decoded d; (d = done.poll()) != null; ) {
            if (inFlight.get(d.chunk) != d.token) {
                continue;
            }
            inFlight.remove(d.chunk);
            if (d.error != null) {
                failed(d.chunk, d.error);
                continue;
            }
            failures.remove(d.chunk);
            retryAt.remove(d.chunk);
            decodeNanos += d.nanos;
            decodes++;
            store(d.chunk, d.column);
        }
    }

    private void failed(long chunk, Throwable error) {
        int attempts = failures.addTo(chunk, 1) + 1;
        if (attempts < MAX_ATTEMPTS) {
            LOGGER.debug("Virtual ground: chunk {},{} in {} unreadable (attempt {})", ChunkPos.getX(chunk),
                    ChunkPos.getZ(chunk), regionDir, attempts, error);
            retryAt.put(chunk, level.getGameTime() + (long) RETRY_TICKS * attempts);
            return;
        }
        LOGGER.error("Virtual ground: chunk {},{} in {} unreadable after {} attempts, flown as air",
                ChunkPos.getX(chunk), ChunkPos.getZ(chunk), regionDir, attempts, error);
        failures.remove(chunk);
        retryAt.remove(chunk);
        store(chunk, air);
    }

    private void store(long key, SectionSolidity[] column) {
        if (column == air) {
            remove(key);
            absent.add(key);
            if (absent.size() > MAX_ABSENT) {
                absent.removeFirstLong();
            }
            return;
        }
        SectionSolidity[] old = columns.putAndMoveToFirst(key, column);
        heldBytes += bytes(column) - (old == null ? 0L : bytes(old));
        while (columns.size() > MAX_COLUMNS || heldBytes > MAX_BYTES && columns.size() > 1) {
            heldBytes -= bytes(columns.removeLast());
        }
    }

    private void remove(long key) {
        absent.remove(key);
        SectionSolidity[] old = columns.remove(key);
        if (old != null) {
            heldBytes -= bytes(old);
        }
    }

    /** Chunk loaded or unloaded: disk copy no longer authoritative. */
    public void invalidate(long chunk) {
        remove(chunk);
        inFlight.remove(chunk);
        failures.remove(chunk);
        retryAt.remove(chunk);
    }

    /** Server thread: every level's decoded columns and in-flight decodes dropped (baked resistance stale). */
    public static void invalidateAll() {
        for (AsyncTerrainSource t : LEVELS.values()) {
            t.columns.clear();
            t.heldBytes = 0L;
            t.inFlight.clear();
        }
    }

    /** Chunk handed to the IO worker ({@code ChunkDataEvent.Save}): its region slot is stale until written. */
    public void saved(long chunk) {
        invalidate(chunk);
        saved.put(chunk, generation);
        regions.add(ChunkPos.asLong(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5));
    }

    /**
     * Server thread, once a tick: saves covered by a completed IO worker sync (every write queued before it done)
     * read from the region file again.
     */
    public void syncSaves() {
        if (sync != null) {
            if (!sync.isDone()) {
                return;
            }
            if (!sync.isCompletedExceptionally()) {
                for (ObjectIterator<Long2LongMap.Entry> it = saved.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
                    if (it.next().getLongValue() <= syncedGeneration) {
                        it.remove();
                    }
                }
            }
            sync = null;
        }
        if (!saved.isEmpty()) {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            syncedGeneration = generation++;
            sync = ((IOWorker) chunkMap.chunkScanner()).synchronize(false);
        }
    }

    /** Failed reads of the chunk since its last success or invalidation. */
    public int failedAttempts(int cx, int cz) {
        drain();
        return failures.get(ChunkPos.asLong(cx, cz));
    }

    /** Chunks read through the IO worker. */
    public int savedChunks() {
        return saved.size();
    }

    /** Decoded non-air columns. */
    public int cachedColumns() {
        drain();
        return columns.size();
    }

    public int absentColumns() {
        drain();
        return absent.size();
    }

    public int pendingColumns() {
        return inFlight.size();
    }

    /** Region-file stats so far. */
    public long stats() {
        return stats;
    }

    /** Heap held by cached columns beyond shared sections. */
    public long cachedBytes() {
        return heldBytes;
    }

    private static long bytes(SectionSolidity[] column) {
        long n = 16L + 4L * column.length;
        for (SectionSolidity s : column) {
            n += s.bytes();
        }
        return n;
    }

    public int decodes() {
        return decodes;
    }

    /** Worker time over {@link #decodes} (read + inflate + palette; IO worker route: + its queue). */
    public long decodeNanos() {
        return decodeNanos;
    }
}
