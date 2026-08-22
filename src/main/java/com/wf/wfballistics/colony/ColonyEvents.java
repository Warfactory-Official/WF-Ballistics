package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

/**
 * The seam where the off-world colony simulation meets the loaded world.
 *
 * <p>Everything owed to a chunk is settled the moment it loads: blocks the colonies changed while nobody
 * was there, and nests founded out of sight that now need building on ground whose height is finally
 * knowable. Nothing is written into an unloaded chunk to make this work — see {@link PendingChunkEdits}.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ColonyEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedFailure;

    private ColonyEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            PendingChunkEdits.get(level).drain(level, event.getChunk().getPos());
            ColonyManager.onChunkLoaded(level, event.getChunk());
        } catch (Exception | LinkageError t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.warn("[wfballistics] colony materialisation failed for chunk {}"
                        + " (further such errors suppressed this session)", event.getChunk().getPos(), t);
            }
        }
    }
}
