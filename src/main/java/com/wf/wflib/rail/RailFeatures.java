package com.wf.wflib.rail;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.rail.excavate.ExcavationService;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

/** Wires the rail package into the mod, and is the only place it touches the rest of it. */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RailFeatures {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RailFeatures() {
    }

    /** Called from the mod constructor. Registers nothing at all when IR is absent. */
    public static void init(ModContainer container) {
        if (!RailCompat.isActive()) {
            return;
        }
        // Its own file, so the rail package stays liftable out of the mod in one piece.
        container.registerConfig(ModConfig.Type.COMMON, RailConfig.SPEC, "wflib-rail.toml");
        if (RailCompat.isForced()) {
            LOGGER.info("[wflib] rail features forced on without Immersive Railroading;"
                    + " only the IR-independent parts (excavation) will do anything");
            return;
        }
        LOGGER.info("[wflib] Immersive Railroading found; rail features enabled");
        if (!RailCompat.trackGraphAvailable()) {
            LOGGER.warn("[wflib] TrackAPI is missing, so nothing can read IR's track graph;"
                    + " excavation still works but track laying will not");
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (RailCompat.isActive()) {
            ExcavationService.startup();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (RailCompat.isActive()) {
            ExcavationService.shutdown();
        }
    }

    /** A chunk has loaded, so any worker rewriting its stored copy is now holding stale data. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (RailCompat.isActive() && event.getLevel() instanceof ServerLevel level) {
            ExcavationService.onChunkLoad(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (RailCompat.isActive() && event.getLevel() instanceof ServerLevel level) {
            ExcavationService.tickIfPresent(level);
        }
    }
}
