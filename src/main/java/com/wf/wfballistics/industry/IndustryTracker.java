package com.wf.wfballistics.industry;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Keeps {@link IndustryRegistry} in step with the world. */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class IndustryTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedFailure;

    private IndustryTracker() {
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            int value = IndustryValues.valueOf(event.getPlacedBlock());
            if (value > 0) {
                IndustryRegistry.get(level).add(event.getPos(), value);
                IndustryClusters.markDirty(level);
            }
        } catch (Exception | LinkageError t) {
            logFailure(t);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            if (IndustryValues.valueOf(event.getState()) > 0) {
                IndustryRegistry.get(level).remove(event.getPos());
                IndustryClusters.markDirty(level);
            }
        } catch (Exception | LinkageError t) {
            logFailure(t);
        }
    }

    /** Schedule the first scan of a session. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            IndustryClusters.markDirty(level);
        }
    }

    /** Sweep a chunk the first time it is ever loaded. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!IndustryConfig.backfillEnabled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        try {
            IndustryRegistry registry = IndustryRegistry.get(level);
            if (registry.isScanned(pos)) {
                return;
            }
            registry.markScanned(pos);

            boolean found = false;
            for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
                int value = IndustryValues.valueOf(chunk.getBlockState(blockPos));
                if (value > 0) {
                    registry.add(blockPos, value);
                    found = true;
                }
            }
            if (found) {
                IndustryClusters.markDirty(level);
            }
        } catch (Exception | LinkageError t) {
            logFailure(t);
        }
    }

    /**
     * The machine most worth attacking near a position, over loaded chunks only.
     *
     * @param awayFrom a machine already spoken for, or null. Candidates within {@code separation} of it are
     *      skipped, which is how a second squad is given a different part of the same base
     *      instead of a neighbour of the block the first one is already eating.
     * @return the best machine within {@code radius} horizontally, or null if there is none.
     */
    public static @Nullable BlockPos pressingMachine(ServerLevel level, double x, double z, double radius,
                                                     @Nullable BlockPos awayFrom, double separation) {
        int minChunkX = SectionPos.blockToSectionCoord(x - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(x + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(z - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(z + radius);
        double separationSq = separation * separation;

        BlockPos best = null;
        double bestScore = 0.0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    int value = IndustryValues.valueOf(chunk.getBlockState(pos));
                    if (value <= 0) {
                        continue;
                    }
                    double dx = pos.getX() + 0.5 - x;
                    double dz = pos.getZ() + 0.5 - z;
                    double distSq = dx * dx + dz * dz;
                    if (distSq > radius * radius) {
                        continue;
                    }
                    if (awayFrom != null && awayFrom.distSqr(pos) < separationSq) {
                        continue;
                    }
                    double score = value * (1.0 - Math.sqrt(distSq) / radius);
                    if (score > bestScore) {
                        bestScore = score;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static void logFailure(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.warn("[wfballistics] industry registry update failed; tracking skipped for this block"
                    + " (further such errors suppressed this session)", t);
        }
    }
}
