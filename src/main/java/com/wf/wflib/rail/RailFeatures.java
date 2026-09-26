package com.wf.wflib.rail;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.rail.align.AlignmentProgress;
import com.wf.wflib.rail.align.AlignmentService;
import com.wf.wflib.rail.build.RailWorks;
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
        // Registered whatever else is installed. Surveying and excavation are both independent of
        // Immersive Railroading, and a config that is only present sometimes is a config whose values
        // throw when read. Its own file, so the rail package stays liftable out of the mod in one piece.
        container.registerConfig(ModConfig.Type.COMMON, RailConfig.SPEC, "wflib-rail.toml");
        if (!RailCompat.isActive()) {
            LOGGER.info("[wflib] Immersive Railroading is absent; surveying and tunnelling still work,"
                    + " laying track does not");
            return;
        }
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
        // Not gated on Immersive Railroading: digging a hole is not one of the things it provides.
        ExcavationService.startup();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ExcavationService.shutdown();
        // A bore train is a machine somebody is watching, and there is nobody left to watch it.
        RailWorks.clear();
        com.wf.wflib.rail.demo.FivePointsFixture.clearAll();
        // The projections are keyed by route id, which the next world will reuse.
        AlignmentProgress.clearCache();
    }

    /** A chunk has loaded, so any worker rewriting its stored copy is now holding stale data. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ExcavationService.onChunkLoad(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Alignments are the planning layer and are deliberately not gated on Immersive Railroading: a
        // route is worth surveying, sharing and arguing about before there is any track to lay on it,
        // and the editor that draws them is not gated either.
        AlignmentService.tick(level);
        ExcavationService.tickIfPresent(level);
        // After the excavator, so a machine that queued a carve this tick is not also waiting a tick
        // for it to be picked up.
        RailWorks.tick(level);
        com.wf.wflib.rail.demo.FivePointsFixture.tick(level);
    }
}
