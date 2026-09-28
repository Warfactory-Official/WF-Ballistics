package com.wf.wflib.round;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DeferredImpact}s by chunk, saved with the level (a chunk may stay unloaded across restarts). Chunk load
 * queues its bucket; applied at the next rounds resolve, never inside {@code ChunkEvent.Load}. Server thread.
 */
public final class DeferredImpacts extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "wflib_deferred_impacts";
    /** Store bound; past it new impacts are dropped. */
    static final int MAX = 4096;
    private static final long SWEEP = 20L;

    private final Long2ObjectOpenHashMap<List<DeferredImpact>> byChunk = new Long2ObjectOpenHashMap<>();
    private final LongArrayList loaded = new LongArrayList();
    private int count;
    private int dropped;

    static DeferredImpacts get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DeferredImpacts::new, DeferredImpacts::load), NAME);
    }

    /** Owed to {@code chunk}, in impact order (tests, tools). */
    public static List<DeferredImpact> pending(ServerLevel level, ChunkPos chunk) {
        List<DeferredImpact> list = get(level).byChunk.get(chunk.toLong());
        return list == null ? List.of() : List.copyOf(list);
    }

    void add(long chunk, DeferredImpact impact) {
        if (count >= MAX) {
            if (dropped++ % 1000 == 0) {
                LOGGER.warn("Deferred impacts full ({}): {} dropped", MAX, dropped);
            }
            return;
        }
        byChunk.computeIfAbsent(chunk, k -> new ArrayList<>(2)).add(impact);
        count++;
        setDirty();
    }

    void chunkLoaded(long chunk) {
        if (byChunk.containsKey(chunk)) {
            loaded.add(chunk);
        }
    }

    /**
     * Buckets of chunks loaded since the last call and still loaded. Every {@link #SWEEP} ticks all owed chunks are
     * checked too: a holder back at FULL before its unload was processed fires no {@code ChunkEvent.Load}.
     */
    void apply(ServerLevel level) {
        if (!byChunk.isEmpty() && level.getGameTime() % SWEEP == 0L) {
            for (long chunk : byChunk.keySet()) {
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) {
                    loaded.add(chunk);
                }
            }
        }
        if (loaded.isEmpty()) {
            return;
        }
        long[] ready = loaded.toLongArray();
        loaded.clear();
        for (long chunk : ready) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null) {
                continue;
            }
            List<DeferredImpact> bucket = byChunk.remove(chunk);
            if (bucket == null) {
                continue;
            }
            count -= bucket.size();
            setDirty();
            for (DeferredImpact impact : bucket) {
                Rounds.applyDeferred(level, impact);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2ObjectOpenHashMap.Entry<List<DeferredImpact>> e : byChunk.long2ObjectEntrySet()) {
            for (DeferredImpact impact : e.getValue()) {
                CompoundTag t = impact.save();
                t.putLong("chunk", e.getLongKey());
                list.add(t);
            }
        }
        tag.put("impacts", list);
        return tag;
    }

    private static DeferredImpacts load(CompoundTag tag, HolderLookup.Provider registries) {
        DeferredImpacts store = new DeferredImpacts();
        ListTag list = tag.getList("impacts", Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size(); k++) {
            CompoundTag t = list.getCompound(k);
            store.byChunk.computeIfAbsent(t.getLong("chunk"), c -> new ArrayList<>(2)).add(DeferredImpact.load(t));
            store.count++;
        }
        return store;
    }
}
