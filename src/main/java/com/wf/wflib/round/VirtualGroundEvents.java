package com.wf.wflib.round;

import com.wf.wflib.WFLib;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Chunk load/unload/save, queued from any thread (C2ME fires off the server thread); {@link #drain} on the server
 * thread invalidates the terrain cache and readies deferred impacts. No world writes in the chunk events.
 */
@EventBusSubscriber(modid = WFLib.MODID)
public final class VirtualGroundEvents {

    private static final Map<ServerLevel, Queues> LEVELS = new ConcurrentHashMap<>();

    private VirtualGroundEvents() {
    }

    private record Queues(ConcurrentLinkedQueue<Long> loaded, ConcurrentLinkedQueue<Long> unloaded,
                          ConcurrentLinkedQueue<Long> saved) {
    }

    private static Queues queues(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, l -> new Queues(new ConcurrentLinkedQueue<>(),
                new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>()));
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            queues(level).loaded.add(chunk.getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            queues(level).unloaded.add(event.getChunk().getPos().toLong());
        }
    }

    /** Fires before the NBT reaches the IO worker, proto chunks included. */
    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            queues(level).saved.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level);
            AsyncTerrainSource.forget(level);
        }
    }

    /** Server thread, before the level's rounds step. */
    static void drain(ServerLevel level) {
        Queues q = LEVELS.get(level);
        if (q == null) {
            return;
        }
        AsyncTerrainSource terrain = AsyncTerrainSource.existing(level);
        DeferredImpacts deferred = q.loaded.isEmpty() ? null : DeferredImpacts.get(level);
        for (Long chunk; (chunk = q.loaded.poll()) != null; ) {
            if (terrain != null) {
                terrain.invalidate(chunk);
            }
            deferred.chunkLoaded(chunk);
        }
        for (Long chunk; (chunk = q.unloaded.poll()) != null; ) {
            if (terrain != null) {
                terrain.invalidate(chunk);
            }
        }
        if (terrain == null && !q.saved.isEmpty()) {
            terrain = AsyncTerrainSource.of(level);
        }
        for (Long chunk; (chunk = q.saved.poll()) != null; ) {
            terrain.saved(chunk);
        }
        if (terrain != null) {
            terrain.syncSaves();
        }
    }
}
