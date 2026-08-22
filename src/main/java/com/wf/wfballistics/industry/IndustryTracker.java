package com.wf.wfballistics.industry;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
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

    private static void logFailure(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.warn("[wfballistics] industry registry update failed; tracking skipped for this block"
                    + " (further such errors suppressed this session)", t);
        }
    }
}
