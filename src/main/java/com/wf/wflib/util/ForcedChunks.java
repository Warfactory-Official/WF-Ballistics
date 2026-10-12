package com.wf.wflib.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reference-counted {@link ServerLevel#setChunkForced}. The vanilla flag is one bit per chunk: concurrent
 * gametests whose lanes share a chunk unforce each other's, and the survivor's entities unload mid-test.
 * Server thread only.
 *
 * <p>Raw callers share the bit: GameTest arenas ({@code StructureUtils.forceLoadChunks}) => never unforce a chunk
 * found forced; batch end unforces every chunk => a hold re-forces a chunk found unforced.
 */
public final class ForcedChunks {

    private static final Map<ResourceKey<Level>, Map<Long, Integer>> HOLDS = new HashMap<>();
    /** Chunks this class forced (was unforced at the hold): only these are unforced at the last release. */
    private static final Map<ResourceKey<Level>, Set<Long>> OURS = new HashMap<>();

    private ForcedChunks() {
    }

    /** {@code forced} = take a hold, else drop one; the chunk unforces with its last hold. */
    public static void set(ServerLevel level, int cx, int cz, boolean forced) {
        Map<Long, Integer> holds = HOLDS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Set<Long> ours = OURS.computeIfAbsent(level.dimension(), k -> new HashSet<>());
        long key = ChunkPos.asLong(cx, cz);
        if (forced) {
            holds.merge(key, 1, Integer::sum);
            if (!level.getForcedChunks().contains(key)) {
                level.setChunkForced(cx, cz, true);
                ours.add(key);
            }
            return;
        }
        int count = holds.getOrDefault(key, 0) - 1;
        if (count > 0) {
            holds.put(key, count);
            return;
        }
        holds.remove(key);
        if (ours.remove(key)) {
            level.setChunkForced(cx, cz, false);
        }
    }
}
