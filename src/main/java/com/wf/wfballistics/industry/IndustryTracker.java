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

/**
 * Keeps {@link IndustryRegistry} in step with the world.
 *
 * <p>Three sources, because block events alone are not enough. Placement and breaking cover what a player
 * does; the chunk sweep covers everything else — worldgen and structures, KubeJS, AE2 and robot placement,
 * {@code /setblock}, and every machine that was already standing before this feature existed. Without the
 * sweep an established base reads as provoking nothing at all, which is the opposite of the intent.
 *
 * <p>Every registry update is wrapped: industry tracking is not worth crashing a block placement or a chunk
 * load over, so a failure degrades to one logged warning.
 */
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

    /**
     * Schedule the first scan of a session.
     *
     * <p>The pressure field is saved data and comes back with the world; the cluster list is derived and
     * does not. Nothing else would ask for it: the scan is event-driven off placements and first-time chunk
     * sweeps, and an established base triggers neither on a restart -- every chunk of it has been swept
     * before. Without this, a server that has been restarted reports no bases at all until somebody places
     * a machine, and everything that targets one quietly does nothing.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            IndustryClusters.markDirty(level);
        }
    }

    /**
     * Sweep a chunk the first time it is ever loaded. Only block entities are examined, not every block in
     * the section: the machines this cares about all have one, and iterating 98k block states per chunk to
     * find them would be a real cost on a pipeline that is already the pack's most expensive.
     */
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
     * The nearest tracked machine to a position, over loaded chunks only.
     *
     * <p>Exists because the cell field is 512 blocks to a side. That is the right resolution for deciding
     * which region a colony resents and far too coarse to walk to: a cluster centre is a value-weighted
     * average of cells, so a base that straddles a cell boundary reports a centre with no machine anywhere
     * near it. Anything that has to point at a machine in the loaded world has to look at the world.
     *
     * <p>Block entities rather than block states, for the same reason the chunk sweep does it: everything
     * this tracks has one, and 98k states a chunk to find them is not affordable on something that runs
     * while a swarm is standing on the doorstep. Unloaded chunks are skipped rather than loaded — a machine
     * nobody has loaded is not one a glyphid can chew.
     *
     * @return the nearest machine within {@code radius} horizontally, or null if there is none.
     */
    public static @Nullable BlockPos nearestMachine(ServerLevel level, double x, double z, double radius) {
        int minChunkX = SectionPos.blockToSectionCoord(x - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(x + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(z - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(z + radius);

        BlockPos best = null;
        double bestSq = radius * radius;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (IndustryValues.valueOf(chunk.getBlockState(pos)) <= 0) {
                        continue;
                    }
                    double dx = pos.getX() + 0.5 - x;
                    double dz = pos.getZ() + 0.5 - z;
                    double distSq = dx * dx + dz * dz;
                    if (distSq < bestSq) {
                        bestSq = distSq;
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
