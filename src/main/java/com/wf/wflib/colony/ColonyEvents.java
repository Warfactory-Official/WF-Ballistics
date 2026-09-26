package com.wf.wflib.colony;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * The seam where the off-world colony simulation meets the loaded world: everything owed to a chunk is settled
 * once it has loaded.
 * <p>
 * Never inside {@code ChunkEvent.Load}: a block write there reaches blocking {@code getChunk} for a neighbour whose
 * FULL waits on this chunk (Sable's {@code LevelChunk#setBlockState} hook reads neighbour voxels) => server thread
 * parks forever. Settled on the level tick once all 8 neighbours are resident.
 */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ColonyEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> LOADED = new HashMap<>();
    private static boolean loggedFailure;

    private ColonyEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LOADED.computeIfAbsent(level.dimension(), key -> new LongLinkedOpenHashSet()).add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LongLinkedOpenHashSet queue = LOADED.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        ServerChunkCache chunks = level.getChunkSource();
        for (LongIterator it = queue.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            LevelChunk chunk = chunks.getChunkNow(x, z);
            if (chunk == null) {
                // Unloaded first; its next load queues it again.
                it.remove();
                continue;
            }
            if (!neighboursResident(chunks, x, z)) {
                continue;
            }
            it.remove();
            try {
                ColonyManager.onChunkLoaded(level, chunk);
                PendingChunkEdits.get(level).drain(level, chunk);
            } catch (Exception | LinkageError t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    LOGGER.warn("[wflib] colony materialisation failed for chunk {}"
                            + " (further such errors suppressed this session)", chunk.getPos(), t);
                }
            }
        }
    }

    private static boolean neighboursResident(ServerChunkCache chunks, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && chunks.getChunkNow(x + dx, z + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void shutdown() {
        LOADED.clear();
    }
}
