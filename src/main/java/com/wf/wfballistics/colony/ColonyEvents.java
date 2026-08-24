package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

/**
 * The seam where the off-world colony simulation meets the loaded world: everything owed to a chunk is
 * settled the moment it loads. Nothing is ever written into an unloaded one — see {@link PendingChunkEdits}.
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
            // Build first, drain second: a chunk mid-load does not answer true to level.hasChunk, so a nest
            // built here queues its own blocks as owed. Drained first, they would sit there until the next
            // unload and reload -- a nest that exists in the record and nowhere else.
            ColonyManager.onChunkLoaded(level, event.getChunk());
            PendingChunkEdits.get(level).drain(level, event.getChunk());
        } catch (Exception | LinkageError t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.warn("[wfballistics] colony materialisation failed for chunk {}"
                        + " (further such errors suppressed this session)", event.getChunk().getPos(), t);
            }
        }
    }
}
