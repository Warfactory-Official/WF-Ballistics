package com.wf.wflib.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Reference-counted {@link ServerLevel#setChunkForced}. The vanilla flag is one bit per chunk: concurrent
 * gametests whose lanes share a chunk unforce each other's, and the survivor's entities unload mid-test.
 * Server thread only.
 */
public final class ForcedChunks {

    private static final Map<ResourceKey<Level>, Map<Long, Integer>> HOLDS = new HashMap<>();

    private ForcedChunks() {
    }

    /** {@code forced} = take a hold, else drop one; the chunk unforces with its last hold. */
    public static void set(ServerLevel level, int cx, int cz, boolean forced) {
        Map<Long, Integer> holds = HOLDS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long key = ChunkPos.asLong(cx, cz);
        int count = holds.getOrDefault(key, 0) + (forced ? 1 : -1);
        if (count <= 0) {
            holds.remove(key);
            level.setChunkForced(cx, cz, false);
        } else {
            holds.put(key, count);
            if (forced && count == 1) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }
}
