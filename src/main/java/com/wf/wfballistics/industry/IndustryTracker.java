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
     * The machine most worth attacking near a position, over loaded chunks only.
     *
     * <p><b>Worth, not distance.</b> Standing inside a base, the nearest machine is whichever one a glyphid
     * happens to have walked past, and a swarm that eats the first furnace it trips over is not attacking a
     * factory — it is grazing. The whitelist is already a ranking of how much a block provokes: a fusion
     * reactor is 400 against a furnace's 1, and the generators are the part of a base that hurts to lose.
     * So a candidate scores {@code value * (1 - distance/radius)}, the same linear falloff
     * {@link IndustryRegistry#pressureWithin} weighs a region by. A reactor anywhere in range outranks every
     * furnace in the building; two similar machines are decided by which is closer.
     *
     * <p>Block entities rather than block states, for the same reason the chunk sweep does it: everything
     * this tracks has one, and 98k states a chunk to find them is not affordable on something that runs
     * while a swarm is standing on the doorstep. Unloaded chunks are skipped rather than loaded — a machine
     * nobody has loaded is not one a glyphid can chew.
     *
     * @param awayFrom  a machine already spoken for, or null. Candidates within {@code separation} of it are
     *                  skipped, which is how a second squad is given a different part of the same base
     *                  instead of a neighbour of the block the first one is already eating.
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
