package com.wf.wflib.event;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wf.wflib.fx.ExplosionCreator;
import com.wf.wflib.entity.mist.GasCloud;
import com.wf.wflib.fluid.WFFluids;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.MissileData;
import com.wf.wflib.api.WFLibAPI;
import com.wf.wflib.chunk.DetonationChunkGuard; // used in ModBusEvents
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.debug.BenchPlayer;
import com.wf.wflib.debug.GlyphidArena;
import com.wf.wflib.debug.GlyphidDeaths;
import com.wf.wflib.debug.MissileDebug;
import com.wf.wflib.debug.OrbitalDebug;
import com.wf.wflib.debug.CameraDebug;
import com.wf.wflib.debug.ReconDebug;
import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.colony.ColonyDebug;
import com.wf.wflib.colony.ColonyManager;
import com.wf.wflib.colony.GlyphidSquads;
import com.wf.wflib.industry.IndustryClusters;
import com.wf.wflib.industry.IndustryDebug;
import java.util.Set;
import java.util.ArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import com.wf.wflib.drone.DroneLaunchQueue;
import com.wf.wflib.drone.ai.coord.CoordinationModels;
import com.wf.wflib.drone.squad.Formation;
import com.wf.wflib.drone.squad.Formations;
import com.wf.wflib.drone.DroneTracker;
import com.wf.wflib.drone.DroneState;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wf.wflib.drone.DroneDrafts;
import com.wf.wflib.drone.DroneMission;
import com.wf.wflib.drone.DroneProgram;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.MineLoad;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.block.entity.DronePadBlockEntity;
import com.wf.wflib.drone.CrateEntity;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.api.WFTelemetry;
import com.wf.wflib.drone.DroneSelfTest;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.wf.wflib.drone.ai.DroneAiScheduler;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.SquadView;
import com.wf.wflib.drone.ai.coord.Slots;
import com.wf.wflib.drone.ai.coord.SquadAnchor;
import com.wf.wflib.drone.ai.state.Tuning;
import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.drone.nav.DronePath;
import com.wf.wflib.drone.nav.PlannerStats;
import com.wf.wflib.item.GridKeyItem;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.map.ReconMapCommand;
import com.wf.wflib.recon.map.ReconMapService;
import com.wf.wflib.recon.example.decoy.DecoyRegistry;
import com.wf.wflib.recon.grid.HubIndex;
import com.wf.wflib.exchange.Exchange;
import com.wf.wflib.exchange.ExchangeManager;
import com.wf.wflib.exchange.ExchangeMode;
import com.wf.wflib.exchange.ExchangeRegistry;
import com.wf.wflib.exchange.StationCode;
import com.wf.wflib.exchange.StationKind;
import com.wf.wflib.exchange.StationRecord;
import com.wf.wflib.exchange.StationRegistry;
import com.wf.wflib.exchange.StationRole;
import com.wf.wflib.build.Blueprint;
import com.wf.wflib.build.BlueprintFormat;
import com.wf.wflib.build.BlueprintLibrary;
import com.wf.wflib.build.BuildJobs;
import com.wf.wflib.build.ConstructionPlan;
import com.wf.wflib.build.SalvagePlan;
import com.wf.wflib.build.SiteRules;
import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.build.WorkPlan;
import com.wf.wflib.work.WorkJob;
import com.wf.wflib.work.WorkRegistry;
import com.wf.wflib.drone.sim.SimDrone;
import com.wf.wflib.drone.sim.SimDroneManager;
import com.wf.wflib.drone.sim.SimDroneRegistry;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.kinetic.KineticSimManager;
import com.wf.wflib.sim.SimMissileManager;
import com.wf.wflib.sim.SimMissileRegistry;
import com.wf.wflib.swarm.SwarmManager;
import com.wf.wflib.util.FragmentationUtil;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidSeparation;
import com.wf.wflib.entity.glyphid.nav.GlyphidBridges;
import com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidManager;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidPass;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidTracking;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import com.wf.wflib.debug.ExplosionTrace;
import com.wf.wflib.debug.MineDebug;
import com.wf.wflib.item.MinePreset;
import com.wf.wflib.item.MinePresetRegistry;
import com.wf.wflib.mine.MineEntity;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side global driver for the missile simulation system.
 */
@EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WFServerEvents {
    private WFServerEvents() {
    }

    /** Let a grid key reach a recon block, which it otherwise cannot. */
    @SubscribeEvent
    public static void onGridKeyUse(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().getItem() instanceof GridKeyItem
                && event.getLevel().getBlockEntity(event.getPos()) instanceof ReconBound) {
            event.setUseBlock(TriState.FALSE);
        }
    }

    /** Sets the glyphid sim tier walking before the world thread starts its own tick, so the two run at once. */
    @SubscribeEvent
    public static void onLevelTickPre(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SimGlyphidManager.beginTick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SimMissileManager.tick(level);
            KineticSimManager.tick(level);
            DroneLaunchQueue.tick(level);
            SimDroneManager.tick(level);
            DroneAiScheduler.tick(level);
            // After the drones have moved, so a zone is judged on where everyone actually is this tick.
            ExchangeManager.tick(level);
            WorkRegistry.get(level).tick(level);
            com.wf.wflib.build.BuildManager.tick(level);
            IndustryClusters.tick(level);
            ColonyManager.tick(level);
            // After the colony tier, because it reassigns bugs the materialiser may have only just placed.
            GlyphidSquads.tick(level);
            SimGlyphidManager.tick(level);
            SimGlyphidTracking.tick(level);
            GlyphidSeparation.tick(level);
            GlyphidBridges.tick(level);
            // Last, so a field built this tick is flooded from terrain the diggers have already changed.
            GlyphidFlowFields.tick(level);
            ReconNet.tick(level);
            com.wf.wflib.orbital.OrbitalNet.tick(level);
            ReconMapService.tick(level);
            com.wf.wflib.drone.cam.CameraNet.tick(level);
            tickScenarios(level);
        }
    }

    /** Closes the swarm profiler's tick. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SwarmBench.tick(event.getServer());
    }

    /** Every explosion in the game, offered to the seismic band. */
    @SubscribeEvent
    public static void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            net.minecraft.world.level.Explosion blast = event.getExplosion();
            Vec3 at = blast.center();
            com.wf.wflib.recon.event.SeismicEvents.report(level, at.x, at.y, at.z, blast.radius());
        }
    }

    /** Drops what a departing player was owed. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        com.wf.wflib.drone.cam.CameraChunkStream.forget(event.getEntity().getUUID());
        ReconMapService.forget(event.getEntity().getUUID());
    }

    /**
     * The drone AI worker pool lives exactly as long as the server, so its threads can't leak across a world
     * reload.
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        DroneAiScheduler.startup();
        SimGlyphidPass.startup();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SimGlyphidPass.shutdown(event.getServer().getAllLevels());
        ReconNet.shutdown();
        com.wf.wflib.orbital.OrbitalNet.shutdown();
        ReconMapService.shutdown();
        com.wf.wflib.drone.cam.CameraNet.shutdown();
        com.wf.wflib.drone.DroneRecall.shutdown();
        HubIndex.shutdown();
        DecoyRegistry.shutdown();
        DroneAiScheduler.shutdown();
        IndustryClusters.clear();
        GlyphidBridges.clear();
        GlyphidFlowFields.clear();
        BenchPlayer.clear();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wflib")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("interceptmode")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("Intercept mode: " + MissileSimConfig.INTERCEPT_MODE), false);
                            return 1;
                        })
                        .then(Commands.literal("chance")
                                .executes(ctx -> setInterceptMode(ctx.getSource(), MissileSimConfig.InterceptResolution.CHANCE_ROLL)))
                        .then(Commands.literal("in_world")
                                .executes(ctx -> setInterceptMode(ctx.getSource(), MissileSimConfig.InterceptResolution.IN_WORLD))))
                .then(Commands.literal("frag")
                        .executes(ctx -> spawnFrag(ctx.getSource(), 16, 1.2))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> spawnFrag(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"), 1.2))
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0, 10.0))
                                        .executes(ctx -> spawnFrag(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                DoubleArgumentType.getDouble(ctx, "speed"))))))
                .then(Commands.literal("boom")
                        .executes(ctx -> boom(ctx.getSource(), "standard"))
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        new String[]{"small", "standard", "large"}, b))
                                .executes(ctx -> boom(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "preset")))))
                .then(Commands.literal("gas")
                        .executes(ctx -> spawnGas(ctx.getSource(), GasCloud.DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> spawnGas(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("intercept")
                        .then(Commands.literal("nearest")
                                .executes(ctx -> interceptNearest(ctx.getSource())))
                        .then(Commands.argument("target", UuidArgument.uuid())
                                .executes(ctx -> interceptUuid(ctx.getSource(), UuidArgument.getUuid(ctx, "target")))))
                .then(Commands.literal("list")
                        .executes(ctx -> listMissiles(ctx.getSource())))
                .then(Commands.literal("simlist")
                        .executes(ctx -> simList(ctx.getSource())))
                .then(Commands.literal("debug")
                        .executes(ctx -> debugStatus(ctx.getSource()))
                        .then(Commands.literal("on")
                                .executes(ctx -> setDebug(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setDebug(ctx.getSource(), false))))
                .then(Commands.literal("track")
                        .then(Commands.literal("latest")
                                .executes(ctx -> trackLatest(ctx.getSource())))
                        .then(Commands.argument("target", UuidArgument.uuid())
                                .executes(ctx -> trackUuid(ctx.getSource(), UuidArgument.getUuid(ctx, "target")))))
                .then(Commands.literal("retarget")
                        .executes(ctx -> retarget(ctx.getSource())))
                .then(Commands.literal("swarm")
                        .then(Commands.argument("count", IntegerArgumentType.integer(2, 32))
                                .executes(ctx -> spawnSwarm(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("station")
                        .then(stationRoleCommand())
                        .then(Commands.literal("code")
                                .executes(ctx -> stationCode(ctx.getSource())))
                        .then(Commands.literal("allow")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> stationAllow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "code"), true))))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> stationAllow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "code"), false))))
                        .then(Commands.literal("send")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> stationSend(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "code")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> stationList(ctx.getSource()))))
                .then(mineDebugCommand())
                .then(demolitionCommand())
                .then(blueprintCommand())
                .then(buildCommand())
                .then(salvageCommand())
                .then(jobCommand())
                .then(Commands.literal("colony")
                        .executes(ctx -> ColonyDebug.status(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> ColonyDebug.list(ctx.getSource())))
                        .then(Commands.literal("nearby")
                                .executes(ctx -> ColonyDebug.nearby(ctx.getSource(), 2_000))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 1_000_000))
                                        .executes(ctx -> ColonyDebug.nearby(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("inspect")
                                .executes(ctx -> ColonyDebug.inspect(ctx.getSource())))
                        .then(Commands.literal("provoke")
                                .executes(ctx -> ColonyDebug.provoke(ctx.getSource(), 100.0))
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 1.0E9))
                                        .executes(ctx -> ColonyDebug.provoke(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "amount")))))
                        .then(Commands.literal("grow")
                                .executes(ctx -> ColonyDebug.grow(ctx.getSource(), 50.0))
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(-1.0E9, 1.0E9))
                                        .executes(ctx -> ColonyDebug.grow(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "amount")))))
                        .then(Commands.literal("expand")
                                .executes(ctx -> ColonyDebug.expand(ctx.getSource())))
                        .then(Commands.literal("bud")
                                .executes(ctx -> ColonyDebug.bud(ctx.getSource(), 1))
                                .then(Commands.argument("cells", IntegerArgumentType.integer(1, 1_000))
                                        .executes(ctx -> ColonyDebug.bud(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "cells")))))
                        .then(Commands.literal("raze")
                                .executes(ctx -> ColonyDebug.raze(ctx.getSource())))
                        .then(Commands.literal("warbands")
                                .executes(ctx -> ColonyDebug.warbands(ctx.getSource())))
                        .then(Commands.literal("found")
                                .executes(ctx -> ColonyDebug.found(ctx.getSource())))
                        .then(Commands.literal("nest")
                                .executes(ctx -> ColonyDebug.nest(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> ColonyDebug.clear(ctx.getSource())))
                        .then(Commands.literal("fastforward")
                                .then(Commands.argument("rounds", IntegerArgumentType.integer(1, 100_000))
                                        .executes(ctx -> ColonyDebug.fastForward(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "rounds")))))
                        .then(Commands.literal("bugs")
                                .executes(ctx -> ColonyDebug.bugs(ctx.getSource())))
                        .then(Commands.literal("squads")
                                .executes(ctx -> ColonyDebug.squads(ctx.getSource())))
                        .then(Commands.literal("evolution")
                                .executes(ctx -> ColonyDebug.evolution(ctx.getSource()))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .executes(ctx -> ColonyDebug.evolution(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "value")))))
                        .then(Commands.literal("dispatch")
                                .executes(ctx -> ColonyDebug.dispatch(ctx.getSource(), 8, false))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                        .executes(ctx -> ColonyDebug.dispatch(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), false))
                                        .then(Commands.literal("flying")
                                                .executes(ctx -> ColonyDebug.dispatch(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"), true)))))
                        .then(Commands.literal("materialise")
                                .executes(ctx -> ColonyDebug.materialise(ctx.getSource(), 64))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                        .executes(ctx -> ColonyDebug.materialise(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("recon")
                        .executes(ctx -> ReconDebug.status(ctx.getSource()))
                        .then(Commands.literal("tracks")
                                .executes(ctx -> ReconDebug.tracks(ctx.getSource())))
                        .then(Commands.literal("explain")
                                .executes(ctx -> ReconDebug.explain(ctx.getSource())))
                        .then(Commands.literal("collect")
                                .executes(ctx -> ReconDebug.collect(ctx.getSource(), 256.0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0, 2048.0))
                                        .executes(ctx -> ReconDebug.collect(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "radius")))))
                        .then(Commands.literal("selftest")
                                .executes(ctx -> ReconDebug.selfTest(ctx.getSource())))
                        .then(Commands.literal("events")
                                .executes(ctx -> ReconDebug.events(ctx.getSource(), 20))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1,
                                                com.wf.wflib.recon.event.SeismicLog.MAX_CAPACITY))
                                        .executes(ctx -> ReconDebug.events(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("weather")
                                .executes(ctx -> ReconDebug.weather(ctx.getSource()))
                                .then(Commands.literal("demo")
                                        .executes(ctx -> ReconDebug.weatherDemo(ctx.getSource()))))
                        .then(Commands.literal("demo")
                                .executes(ctx -> ReconDebug.demo(ctx.getSource())))
                        .then(Commands.literal("bind")
                                .executes(ctx -> ReconDebug.bindings(ctx.getSource(),
                                        ReconDebug.BIND_RADIUS))
                                .then(Commands.literal("list")
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(
                                                        0.0, ReconDebug.BIND_MAX_RADIUS))
                                                .executes(ctx -> ReconDebug.bindings(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "radius")))))
                                .then(Commands.argument("net", StringArgumentType.string())
                                        .suggests(ReconDebug::suggestNets)
                                        .executes(ctx -> ReconDebug.bind(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "net"),
                                                ReconDebug.BIND_RADIUS))
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(
                                                        0.0, ReconDebug.BIND_MAX_RADIUS))
                                                .executes(ctx -> ReconDebug.bind(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "net"),
                                                        DoubleArgumentType.getDouble(ctx, "radius"))))))
                        .then(Commands.literal("grid")
                                .executes(ctx -> ReconDebug.grid(ctx.getSource()))
                                .then(Commands.literal("demo")
                                        .executes(ctx -> ReconDebug.gridDemo(ctx.getSource())))
                                .then(Commands.literal("remint")
                                        .executes(ctx -> ReconDebug.remint(ctx.getSource())))
                                .then(Commands.literal("adopt")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .executes(ctx -> ReconDebug.adopt(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "uuid")))))))
                .then(Commands.literal("orbital")
                        .executes(ctx -> OrbitalDebug.status(ctx.getSource()))
                        .then(Commands.literal("demo")
                                .executes(ctx -> OrbitalDebug.demo(ctx.getSource())))
                        .then(Commands.literal("selftest")
                                .executes(ctx -> OrbitalDebug.selfTest(ctx.getSource())))
                        .then(Commands.literal("catalogue")
                                .executes(ctx -> OrbitalDebug.catalogue(ctx.getSource())))
                        .then(Commands.literal("survey")
                                .executes(ctx -> OrbitalDebug.survey(ctx.getSource())))
                        .then(Commands.literal("launch")
                                .then(Commands.argument("payload", StringArgumentType.word())
                                        .suggests(OrbitalDebug::suggestPayloads)
                                        .executes(ctx -> OrbitalDebug.launch(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "payload"), "leo"))
                                        .then(Commands.argument("orbit", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        new String[]{"leo", "meo", "heo"}, b))
                                                .executes(ctx -> OrbitalDebug.launch(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "payload"),
                                                        StringArgumentType.getString(ctx, "orbit"))))))
                        .then(Commands.literal("cmd")
                                .then(Commands.argument("callsign", StringArgumentType.word())
                                        .suggests(OrbitalDebug::suggestCallsigns)
                                        .then(Commands.argument("verb", StringArgumentType.greedyString())
                                                .executes(ctx -> OrbitalDebug.command(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "callsign"),
                                                        StringArgumentType.getString(ctx, "verb"))))))
                        .then(Commands.literal("verbs")
                                .then(Commands.argument("callsign", StringArgumentType.word())
                                        .suggests(OrbitalDebug::suggestCallsigns)
                                        .executes(ctx -> OrbitalDebug.verbs(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "callsign")))))
                        .then(Commands.literal("at")
                                .then(Commands.argument("callsign", StringArgumentType.word())
                                        .suggests(OrbitalDebug::suggestCallsigns)
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(
                                                        -1200000, 1200000))
                                                .executes(ctx -> OrbitalDebug.at(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "callsign"),
                                                        IntegerArgumentType.getInteger(ctx, "ticks"))))))
                        .then(Commands.literal("deorbit")
                                .then(Commands.argument("callsign", StringArgumentType.word())
                                        .suggests(OrbitalDebug::suggestCallsigns)
                                        .executes(ctx -> OrbitalDebug.deorbit(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "callsign"))))))
                .then(Commands.literal("industry")
                        .executes(ctx -> IndustryDebug.status(ctx.getSource()))
                        .then(Commands.literal("bases")
                                .executes(ctx -> IndustryDebug.bases(ctx.getSource())))
                        .then(Commands.literal("cells")
                                .executes(ctx -> IndustryDebug.cells(ctx.getSource())))
                        .then(Commands.literal("here")
                                .executes(ctx -> IndustryDebug.here(ctx.getSource())))
                        .then(Commands.literal("rescan")
                                .executes(ctx -> IndustryDebug.rescan(ctx.getSource(), 8))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 32))
                                        .executes(ctx -> IndustryDebug.rescan(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radiusChunks"))))))
                .then(Commands.literal("swarmbench")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal(SwarmBench.status(ctx.getSource().getLevel())), false);
                            return 1;
                        })
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 2048))
                                        .executes(ctx -> SwarmBench.spawn(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), 24.0))
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(2.0, 256.0))
                                                .executes(ctx -> SwarmBench.spawn(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        DoubleArgumentType.getDouble(ctx, "radius")))
                                                .then(Commands.argument("caste", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (GlyphidCaste g : GlyphidCaste.VALUES) {
                                                                b.suggest(g.lowerName());
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> {
                                                            GlyphidCaste caste = GlyphidCaste.byName(
                                                                    StringArgumentType.getString(ctx, "caste"));
                                                            if (caste == null) {
                                                                ctx.getSource().sendFailure(
                                                                        Component.literal("Unknown caste."));
                                                                return 0;
                                                            }
                                                            return SwarmBench.spawn(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(ctx, "count"),
                                                                    DoubleArgumentType.getDouble(ctx, "radius"),
                                                                    caste);
                                                        })))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> SwarmBench.clear(ctx.getSource())))
                        .then(Commands.literal("report")
                                .executes(ctx -> SwarmBench.report(ctx.getSource())))
                        .then(Commands.literal("reset")
                                .executes(ctx -> SwarmBench.reset(ctx.getSource())))
                        .then(Commands.literal("profile")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.profile(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.profile(ctx.getSource(), false))))
                        .then(Commands.literal("dummies")
                                .executes(ctx -> SwarmBench.dummyReport(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> SwarmBench.dummyClear(ctx.getSource())))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> SwarmBench.dummyReset(ctx.getSource())))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> SwarmBench.dummies(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), 30.0, 0.0))
                                        .then(Commands.argument("spread", DoubleArgumentType.doubleArg(1.0, 256.0))
                                                .executes(ctx -> SwarmBench.dummies(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        DoubleArgumentType.getDouble(ctx, "spread"), 0.0))
                                                .then(Commands.argument("drift", DoubleArgumentType.doubleArg(0.0, 64.0))
                                                        .executes(ctx -> SwarmBench.dummies(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                DoubleArgumentType.getDouble(ctx, "spread"),
                                                                DoubleArgumentType.getDouble(ctx, "drift")))))))
                        .then(Commands.literal("entitycollisions")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.entityCollisions(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.entityCollisions(ctx.getSource(), false))))
                        .then(Commands.literal("pathcache")
                                .then(Commands.argument("megabytes", DoubleArgumentType.doubleArg(0.05, 256.0))
                                        .executes(ctx -> SwarmBench.pathCache(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "megabytes")))))
                        .then(Commands.literal("charge")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.charge(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.charge(ctx.getSource(), false))))
                        .then(Commands.literal("sharedpaths")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.sharedPaths(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.sharedPaths(ctx.getSource(), false))))
                        .then(Commands.literal("push")
                                .then(Commands.literal("vanilla")
                                        .executes(ctx -> SwarmBench.push(ctx.getSource(), true)))
                                .then(Commands.literal("skip")
                                        .executes(ctx -> SwarmBench.push(ctx.getSource(), false))))
                        .then(Commands.literal("separation")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.separation(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.separation(ctx.getSource(), false))))
                        .then(Commands.literal("density")
                                .executes(ctx -> SwarmBench.density(ctx.getSource())))
                        .then(Commands.literal("census")
                                .executes(ctx -> SwarmBench.census(ctx.getSource(), 6.0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 256.0))
                                        .executes(ctx -> SwarmBench.census(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "radius")))))
                        .then(Commands.literal("squads")
                                .executes(ctx -> SwarmBench.squads(ctx.getSource())))
                        .then(Commands.literal("deaths")
                                .executes(ctx -> GlyphidDeaths.command(ctx.getSource()))
                                .then(Commands.literal("on")
                                        .executes(ctx -> GlyphidDeaths.command(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> GlyphidDeaths.command(ctx.getSource(), false))))
                        .then(Commands.literal("arena")
                                .executes(ctx -> GlyphidArena.report(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> GlyphidArena.clear(ctx.getSource())))
                                .then(Commands.literal("launch")
                                        .executes(ctx -> GlyphidArena.launch(ctx.getSource(), 150))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2048))
                                                .executes(ctx -> GlyphidArena.launch(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count")))
                                                .then(Commands.argument("caste", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (GlyphidCaste g : GlyphidCaste.VALUES) {
                                                                b.suggest(g.lowerName());
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> {
                                                            GlyphidCaste caste = GlyphidCaste.byName(
                                                                    StringArgumentType.getString(ctx, "caste"));
                                                            if (caste == null) {
                                                                ctx.getSource().sendFailure(
                                                                        Component.literal("Unknown caste."));
                                                                return 0;
                                                            }
                                                            return GlyphidArena.launch(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(ctx, "count"),
                                                                    caste);
                                                        }))))
                                .then(Commands.literal("reinforce")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2048))
                                                .then(Commands.argument("caste", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (GlyphidCaste g : GlyphidCaste.VALUES) {
                                                                b.suggest(g.lowerName());
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> {
                                                            GlyphidCaste caste = GlyphidCaste.byName(
                                                                    StringArgumentType.getString(ctx, "caste"));
                                                            if (caste == null) {
                                                                ctx.getSource().sendFailure(
                                                                        Component.literal("Unknown caste."));
                                                                return 0;
                                                            }
                                                            return GlyphidArena.reinforce(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(ctx, "count"),
                                                                    caste);
                                                        }))))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (String s : GlyphidArena.NAMES) {
                                                b.suggest(s);
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> GlyphidArena.build(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), null))
                                        .then(Commands.argument("material", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    b.suggest("stone");
                                                    b.suggest("obsidian");
                                                    b.suggest("bedrock");
                                                    return b.buildFuture();
                                                })
                                                .executes(ctx -> GlyphidArena.build(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "material"))))))
                        .then(Commands.literal("players")
                                .executes(ctx -> BenchPlayer.report(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> BenchPlayer.clear(ctx.getSource())))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("at", Vec3Argument.vec3())
                                                .executes(ctx -> BenchPlayer.add(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        Vec3Argument.getVec3(ctx, "at"))))))
                        .then(Commands.literal("flowfield")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.flowField(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.flowField(ctx.getSource(), false))))
                        .then(Commands.literal("flowfields")
                                .executes(ctx -> SwarmBench.flowFields(ctx.getSource())))
                        .then(Commands.literal("bridges")
                                .executes(ctx -> SwarmBench.bridgeReport(ctx.getSource()))
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.bridges(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.bridges(ctx.getSource(), false))))
                        .then(Commands.literal("sim")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.simTier(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.simTier(ctx.getSource(), false)))
                                .then(Commands.argument("range", DoubleArgumentType.doubleArg(0.0, 4096.0))
                                        .executes(ctx -> SwarmBench.simRange(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "range")))))
                        .then(Commands.literal("march")
                                .then(Commands.argument("to", Vec3Argument.vec3())
                                        .executes(ctx -> SwarmBench.march(ctx.getSource(),
                                                Vec3Argument.getVec3(ctx, "to")))))
                        .then(Commands.literal("simasync")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.simAsync(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.simAsync(ctx.getSource(), false))))
                        .then(Commands.literal("simthread")
                                .executes(ctx -> SwarmBench.simThread(ctx.getSource())))
                        .then(Commands.literal("tiers")
                                .executes(ctx -> SwarmBench.tiers(ctx.getSource())))
                        .then(Commands.literal("simcount")
                                .then(Commands.argument("from", Vec3Argument.vec3())
                                        .then(Commands.argument("to", Vec3Argument.vec3())
                                                .executes(ctx -> SwarmBench.simCount(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "from"),
                                                        Vec3Argument.getVec3(ctx, "to"))))))
                        .then(Commands.literal("blast")
                                .then(Commands.argument("size", DoubleArgumentType.doubleArg(1.0, 64.0))
                                        .executes(ctx -> SwarmBench.blast(ctx.getSource(),
                                                (float) DoubleArgumentType.getDouble(ctx, "size")))))
                        .then(Commands.literal("watcher")
                                .executes(ctx -> SwarmBench.watcher(ctx.getSource(), null))
                                .then(Commands.argument("at", Vec3Argument.vec3())
                                        .executes(ctx -> SwarmBench.watcher(ctx.getSource(),
                                                Vec3Argument.getVec3(ctx, "at")))))
                        .then(Commands.literal("stagger")
                                .then(Commands.literal("on")
                                        .executes(ctx -> SwarmBench.stagger(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> SwarmBench.stagger(ctx.getSource(), false))))
                        .then(Commands.literal("meleegoal")
                                .then(Commands.literal("vanilla")
                                        .executes(ctx -> SwarmBench.meleeGoal(ctx.getSource(), true)))
                                .then(Commands.literal("glyphid")
                                        .executes(ctx -> SwarmBench.meleeGoal(ctx.getSource(), false)))))
                .then(Commands.literal("exchange")
                        .then(Commands.literal("list")
                                .executes(ctx -> exchangeList(ctx.getSource())))
                        .then(Commands.literal("purge")
                                .executes(ctx -> exchangePurge(ctx.getSource())))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", UuidArgument.uuid())
                                        .executes(ctx -> exchangeCancel(ctx.getSource(),
                                                UuidArgument.getUuid(ctx, "id"))))))
                .then(Commands.literal("drone")
                        .then(Commands.literal("list")
                                .executes(ctx -> listDrones(ctx.getSource())))
                        .then(Commands.literal("selftest")
                                .executes(ctx -> selfTest(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearDrones(ctx.getSource())))
                        .then(Commands.literal("recall")
                                .executes(ctx -> recallDrones(ctx.getSource())))
                        .then(Commands.literal("telemetry")
                                .executes(ctx -> droneTelemetry(ctx.getSource())))
                        .then(Commands.literal("threads")
                                .executes(ctx -> droneThreads(ctx.getSource())))
                        .then(Commands.literal("camera")
                                .executes(ctx -> CameraDebug.list(ctx.getSource()))
                                .then(Commands.literal("stats")
                                        .executes(ctx -> CameraDebug.stats(ctx.getSource()))
                                        .then(Commands.literal("reset")
                                                .executes(ctx -> CameraDebug.reset(ctx.getSource()))))
                                .then(Commands.literal("demo")
                                        .executes(ctx -> CameraDebug.demo(ctx.getSource())))
                                .then(Commands.literal("fit")
                                        .then(Commands.literal("recon")
                                                .executes(ctx -> CameraDebug.fit(ctx.getSource(), "recon")))
                                        .then(Commands.literal("standard")
                                                .executes(ctx -> CameraDebug.fit(ctx.getSource(), "standard")))
                                        .then(Commands.literal("none")
                                                .executes(ctx -> CameraDebug.fit(ctx.getSource(), "none")))))
                        .then(Commands.literal("muster")
                                .executes(ctx -> droneMuster(ctx.getSource())))
                        .then(Commands.literal("sim")
                                .executes(ctx -> droneSim(ctx.getSource(), null))
                                .then(Commands.literal("on")
                                        .executes(ctx -> droneSim(ctx.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(ctx -> droneSim(ctx.getSource(), false))))
                        .then(Commands.literal("scenario")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (String s : SCENARIOS) {
                                                b.suggest(s);
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> scenario(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("strike")
                                .then(Commands.argument("destination", Vec3Argument.vec3())
                                        .executes(ctx -> strikeDrones(ctx.getSource(),
                                                Vec3Argument.getVec3(ctx, "destination"), 1, ""))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> strikeDrones(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), ""))
                                                .then(Commands.argument("warhead", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (ResourceLocation id : WarheadRegistry.ids()) {
                                                                b.suggest(id.getPath());
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> strikeDrones(ctx.getSource(),
                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "warhead")))))))
                        .then(Commands.literal("minelay")
                                .then(Commands.argument("destination", Vec3Argument.vec3())
                                        .executes(ctx -> minelayDrones(ctx.getSource(),
                                                Vec3Argument.getVec3(ctx, "destination"), 1, "",
                                                MineLoad.DEFAULT_MINES, MineLoad.DEFAULT_SPACING))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> minelayDrones(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), "",
                                                        MineLoad.DEFAULT_MINES, MineLoad.DEFAULT_SPACING))
                                                .then(Commands.argument("mine", StringArgumentType.word())
                                                        .suggests(SOWN_MINES)
                                                        .executes(ctx -> minelayDrones(ctx.getSource(),
                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "mine"),
                                                                MineLoad.DEFAULT_MINES, MineLoad.DEFAULT_SPACING))
                                                        .then(Commands.argument("mines",
                                                                        IntegerArgumentType.integer(1, MineLoad.MAX_MINES))
                                                                .executes(ctx -> minelayDrones(ctx.getSource(),
                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "mine"),
                                                                        IntegerArgumentType.getInteger(ctx, "mines"),
                                                                        MineLoad.DEFAULT_SPACING))
                                                                .then(Commands.argument("spacing",
                                                                                DoubleArgumentType.doubleArg(MineLoad.MIN_SPACING,
                                                                                        MineLoad.MAX_SPACING))
                                                                        .executes(ctx -> minelayDrones(ctx.getSource(),
                                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                                StringArgumentType.getString(ctx, "mine"),
                                                                                IntegerArgumentType.getInteger(ctx, "mines"),
                                                                                DoubleArgumentType.getDouble(ctx, "spacing")))))))))
                        .then(Commands.literal("pad")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("destination", Vec3Argument.vec3())
                                                .executes(ctx -> configurePad(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        Vec3Argument.getVec3(ctx, "destination"), 1, "vee",
                                                        Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> configurePad(ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                IntegerArgumentType.getInteger(ctx, "count"), "vee",
                                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                                                        .then(Commands.argument("formation", StringArgumentType.word())
                                                                .executes(ctx -> configurePad(ctx.getSource(),
                                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "formation"),
                                                                        Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                                                                .then(Commands.argument("spacing",
                                                                                DoubleArgumentType.doubleArg(
                                                                                        Formation.MIN_SPACING,
                                                                                        Formation.MAX_SPACING))
                                                                        .executes(ctx -> configurePad(ctx.getSource(),
                                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                                StringArgumentType.getString(ctx, "formation"),
                                                                                DoubleArgumentType.getDouble(ctx, "spacing"), CoordinationModels.DEFAULT.getPath()))
                                                                        .then(Commands.argument("coordination", StringArgumentType.word())
                                                                                .suggests((c, b) -> {
                                                                                        for (ResourceLocation id : CoordinationModels.ids()) {
                                                                                            b.suggest(id.getPath());
                                                                                        }
                                                                                        return b.buildFuture();
                                                                                    })
                                                                                .executes(ctx -> configurePad(ctx.getSource(),
                                                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                                        StringArgumentType.getString(ctx, "formation"),
                                                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                                        StringArgumentType.getString(ctx, "coordination"))))))))))
                        .then(Commands.literal("dispatch")
                                .then(Commands.argument("destination", Vec3Argument.vec3())
                                        .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                Vec3Argument.getVec3(ctx, "destination"), 1, "vee",
                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(),
                                                DroneMission.DEFAULT_LAUNCH_INTERVAL, true))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), "vee",
                                                        Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(),
                                                        DroneMission.DEFAULT_LAUNCH_INTERVAL, true))
                                                .then(Commands.argument("formation", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                            for (ResourceLocation id : Formations.ids()) {
                                                                b.suggest(id.getPath());
                                                            }
                                                            return b.buildFuture();
                                                        })
                                                        .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "formation"),
                                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(),
                                                                DroneMission.DEFAULT_LAUNCH_INTERVAL, true))
                                                        .then(Commands.argument("spacing",
                                                                        DoubleArgumentType.doubleArg(
                                                                                Formation.MIN_SPACING,
                                                                                Formation.MAX_SPACING))
                                                                .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "formation"),
                                                                        DoubleArgumentType.getDouble(ctx, "spacing"), CoordinationModels.DEFAULT.getPath(),
                                                                        DroneMission.DEFAULT_LAUNCH_INTERVAL, true))
                                                                .then(Commands.argument("coordination", StringArgumentType.word())
                                                                        .suggests((c, b) -> {
                                                                                for (ResourceLocation id : CoordinationModels.ids()) {
                                                                                    b.suggest(id.getPath());
                                                                                }
                                                                                return b.buildFuture();
                                                                            })
                                                                        .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                                                Vec3Argument.getVec3(ctx, "destination"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                                StringArgumentType.getString(ctx, "formation"),
                                                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                                StringArgumentType.getString(ctx, "coordination"),
                                                                                DroneMission.DEFAULT_LAUNCH_INTERVAL, true))
                                                                        .then(Commands.argument("interval",
                                                                                        IntegerArgumentType.integer(0,
                                                                                                DroneMission.MAX_LAUNCH_INTERVAL))
                                                                                .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                                        StringArgumentType.getString(ctx, "formation"),
                                                                                        DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                                        StringArgumentType.getString(ctx, "coordination"),
                                                                                        IntegerArgumentType.getInteger(ctx, "interval"),
                                                                                        true)))))))))
                        .then(programCommand())));

        ReconMapCommand.register(event.getDispatcher());
    }

    // --- programs ---

    /** The {@code program} subtree: build a queue of steps one command at a time, then fly it. */
    private static LiteralArgumentBuilder<CommandSourceStack> programCommand() {
        LiteralArgumentBuilder<CommandSourceStack> program = Commands.literal("program")
                .executes(ctx -> listProgram(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> listProgram(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> clearProgram(ctx.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1, DroneTask.MAX_STEPS))
                                .executes(ctx -> removeStep(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index")))))
                .then(Commands.literal("exfil")
                        .executes(ctx -> addStep(ctx.getSource(), new DroneTask.Exfil())));

        for (DroneTask.Kind kind : new DroneTask.Kind[]{DroneTask.Kind.MOVE_TO, DroneTask.Kind.DELIVER,
                DroneTask.Kind.COLLECT, DroneTask.Kind.STRIKE, DroneTask.Kind.MINELAY}) {
            program = program.then(Commands.literal(kind.id())
                    .then(Commands.argument("at", Vec3Argument.vec3())
                            .executes(ctx -> addStep(ctx.getSource(),
                                    kind.at(Vec3Argument.getVec3(ctx, "at"))))));
        }

        program = program.then(Commands.literal("loiter")
                .then(Commands.argument("at", Vec3Argument.vec3())
                        .executes(ctx -> addStep(ctx.getSource(), new DroneTask.Loiter(
                                Vec3Argument.getVec3(ctx, "at"), DroneTask.Loiter.DEFAULT_RADIUS, 0)))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0,
                                        DroneTask.MAX_LOITER_RADIUS))
                                .executes(ctx -> addStep(ctx.getSource(), new DroneTask.Loiter(
                                        Vec3Argument.getVec3(ctx, "at"),
                                        DoubleArgumentType.getDouble(ctx, "radius"), 0)))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 36000))
                                        .executes(ctx -> addStep(ctx.getSource(), new DroneTask.Loiter(
                                                Vec3Argument.getVec3(ctx, "at"),
                                                DoubleArgumentType.getDouble(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "seconds") * 20)))))));

        program = program.then(Commands.literal("hold")
                .then(Commands.argument("at", Vec3Argument.vec3())
                        .executes(ctx -> addStep(ctx.getSource(),
                                new DroneTask.Loiter(Vec3Argument.getVec3(ctx, "at"), 0.0, 0)))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 36000))
                                .executes(ctx -> addStep(ctx.getSource(), new DroneTask.Loiter(
                                        Vec3Argument.getVec3(ctx, "at"), 0.0,
                                        IntegerArgumentType.getInteger(ctx, "seconds") * 20))))));

        return program.then(Commands.literal("launch")
                .executes(ctx -> launchProgram(ctx.getSource(), 1, "vee", "", Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> launchProgram(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"), "vee", "",
                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                        .then(Commands.argument("formation", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (ResourceLocation id : Formations.ids()) {
                                        b.suggest(id.getPath());
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> launchProgram(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        StringArgumentType.getString(ctx, "formation"), "",
                                        Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                                .then(Commands.argument("warhead", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (ResourceLocation id : WarheadRegistry.ids()) {
                                                b.suggest(id.getPath());
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> launchProgram(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "formation"),
                                                StringArgumentType.getString(ctx, "warhead"),
                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath()))
                                        .then(Commands.argument("spacing", DoubleArgumentType.doubleArg(
                                                        Formation.MIN_SPACING, Formation.MAX_SPACING))
                                                .executes(ctx -> launchProgram(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        StringArgumentType.getString(ctx, "formation"),
                                                        StringArgumentType.getString(ctx, "warhead"),
                                                        DoubleArgumentType.getDouble(ctx, "spacing"), CoordinationModels.DEFAULT.getPath()))
                                                .then(Commands.argument("coordination", StringArgumentType.word())
                                                        .suggests((c, b) -> {
                                                                for (ResourceLocation id : CoordinationModels.ids()) {
                                                                    b.suggest(id.getPath());
                                                                }
                                                                return b.buildFuture();
                                                            })
                                                        .executes(ctx -> launchProgram(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "formation"),
                                                                StringArgumentType.getString(ctx, "warhead"),
                                                                DoubleArgumentType.getDouble(ctx, "spacing"),
                                                                StringArgumentType.getString(ctx, "coordination")))))))));
    }

    private static int addStep(CommandSourceStack src, DroneTask task) {
        UUID player = draftOwner(src);
        DroneProgram before = DroneDrafts.get(player);
        DroneProgram after = DroneDrafts.edit(player, p -> p.plus(task));
        if (after.tasks().size() == before.tasks().size()) {
            src.sendFailure(Component.literal("Program is full (" + DroneTask.MAX_STEPS + " steps)."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(after.tasks().size() + ". " + task.label()), false);
        return after.tasks().size();
    }

    private static int removeStep(CommandSourceStack src, int oneBased) {
        UUID player = draftOwner(src);
        DroneProgram before = DroneDrafts.get(player);
        if (oneBased > before.tasks().size()) {
            src.sendFailure(Component.literal("No step " + oneBased + "; the program has "
                    + before.tasks().size() + "."));
            return 0;
        }
        DroneProgram after = DroneDrafts.edit(player, p -> p.removing(oneBased - 1));
        src.sendSuccess(() -> Component.literal("Removed step " + oneBased + ", "
                + after.tasks().size() + " left."), false);
        return after.tasks().size();
    }

    private static int clearProgram(CommandSourceStack src) {
        DroneDrafts.clear(draftOwner(src));
        src.sendSuccess(() -> Component.literal("Program cleared."), false);
        return 1;
    }

    private static int listProgram(CommandSourceStack src) {
        DroneProgram program = DroneDrafts.get(draftOwner(src));
        if (program.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No program. Add steps with /wflib drone program <moveto|deliver|collect|strike|"
                            + "loiter|hold|exfil>, then launch it."), false);
            return 0;
        }
        List<DroneTask> tasks = program.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            int n = i + 1;
            DroneTask task = tasks.get(i);
            src.sendSuccess(() -> Component.literal(n + ". " + task.label()), false);
        }
        Vec3 origin = src.getPosition();
        int route = (int) program.routeLength(origin, origin);
        src.sendSuccess(() -> Component.literal(tasks.size() + " step(s), " + route + "m of route")
                .withStyle(ChatFormatting.GRAY), false);
        return tasks.size();
    }

    /** Fly the draft. */
    private static int launchProgram(CommandSourceStack src, int count, String formation, String warhead,
                                     double spacing, String coordination) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();
        DroneProgram program = DroneDrafts.get(draftOwner(src));
        if (program.isEmpty()) {
            src.sendFailure(Component.literal("No program to launch. Add steps first."));
            return 0;
        }

        boolean delivers = program.tasks().stream().anyMatch(t -> t.kind() == DroneTask.Kind.DELIVER);
        boolean strikes = program.tasks().stream().anyMatch(t -> t.kind() == DroneTask.Kind.STRIKE);
        boolean lays = program.tasks().stream().anyMatch(t -> t.kind() == DroneTask.Kind.MINELAY);

        DroneMission mission = new DroneMission();
        mission.program = program;
        mission.count = count;
        mission.formationId = Formations.parse(formation);
        mission.formationSpacing = Formation.clampSpacing(spacing);
        mission.coordinationId = CoordinationModels.parse(coordination);
        if (strikes) {
            mission.payloadId = warhead.isEmpty() ? WarheadRegistry.defaultId() : WarheadRegistry.parse(warhead);
            if (!WarheadRegistry.exists(mission.payloadId)) {
                src.sendFailure(Component.literal("Unknown warhead '" + warhead + "'. Known: "
                        + WarheadRegistry.ids().stream().map(ResourceLocation::getPath).sorted().toList()));
                return 0;
            }
        }
        if (lays) {
            if (strikes) {
                src.sendFailure(Component.literal(
                        "A program cannot both strike and lay mines: one load, one drone. Split it in two."));
                return 0;
            }
            ResourceLocation mine = warhead.isEmpty()
                    ? MinePresetRegistry.defaultSownId() : MinePresetRegistry.parse(warhead);
            if (!MinePresetRegistry.exists(mine)) {
                src.sendFailure(Component.literal("Unknown mine '" + warhead + "'. Known: "
                        + MinePresetRegistry.all().stream().map(p -> p.id().getPath()).sorted().toList()));
                return 0;
            }
            mission.mines = MineLoad.of(mine, MineLoad.DEFAULT_MINES);
        }

        DroneMission.Result result = mission.dispatch(level, origin, delivers ? new CompoundTag() : null);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Launch refused: " + result.error()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "Launched %d drone(s) in %s formation (%s) on a %d-step program (%dm of route)",
                result.ordered(), mission.formationId.getPath(),
                mission.coordinationId.getPath(), program.tasks().size(),
                (int) mission.outboundDistance(origin))), true);
        return result.ordered();
    }

    /**
     * @return whose draft this command edits. Keyed on the player so two people writing programs at once do
     *      not write each other's; anything without a player behind it (a command block, the console) shares one
     *      draft, which is the only sensible reading of "the server's program".
     */
    private static UUID draftOwner(CommandSourceStack src) {
        return src.getEntity() != null ? src.getEntity().getUUID() : SERVER_DRAFT;
    }

    private static final UUID SERVER_DRAFT = new UUID(0L, 0L);

    /** Dispatches a delivery flight from the player's position. */
    private static int dispatchDrones(CommandSourceStack src, Vec3 destination, int count, String formation,
                                      double spacing, String coordination, int interval,
                                      boolean withCrate) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();

        DroneMission mission = new DroneMission();
        mission.destination = destination;
        mission.count = count;
        mission.formationId = Formations.parse(formation);
        mission.formationSpacing = Formation.clampSpacing(spacing);
        mission.coordinationId = CoordinationModels.parse(coordination);
        mission.launchInterval = DroneMission.clampInterval(interval);

        CompoundTag crate = withCrate ? new CompoundTag() : null;

        DroneMission.Result result = mission.dispatch(level, origin, crate);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Dispatch refused: " + result.error()));
            return 0;
        }

        boolean roundTrip = mission.canRoundTrip(origin, withCrate);
        int dist = (int) origin.distanceTo(destination);
        src.sendSuccess(() -> Component.literal(String.format(
                "Dispatched %d drone(s) in %s formation (%s) to %d, %d, %d (%dm)%s",
                result.ordered(), mission.formationId.getPath(), mission.coordinationId.getPath(),
                (int) destination.x, (int) destination.y, (int) destination.z, dist,
                roundTrip ? "" : " - one-way, battery won't cover the return")), true);
        return result.ordered();
    }

    private static final String[] SCENARIOS =
            {"delivery", "strike", "squad", "lowbattery", "downed", "longrange", "terrain"};

    /** Run the pure-logic checks and report them. */
    private static int selfTest(CommandSourceStack src) {
        List<DroneSelfTest.Result> results = DroneSelfTest.runAll();
        long failed = results.stream().filter(r -> !r.passed()).count();
        for (DroneSelfTest.Result r : results) {
            if (!r.passed()) {
                src.sendSuccess(() -> Component.literal("FAIL " + r.name() + " - " + r.detail())
                        .withStyle(ChatFormatting.RED), false);
            }
        }
        long passed = results.size() - failed;
        src.sendSuccess(() -> Component.literal(String.format("Drone self-test: %d passed, %d failed",
                passed, failed)).withStyle(failed == 0 ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return failed == 0 ? 1 : 0;
    }

    /**
     * Set up a canned situation to watch. Everything spawns near the caller.
     */
    private static int scenario(CommandSourceStack src, String name) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();
        DroneMission mission = new DroneMission();

        switch (name.toLowerCase()) {
            case "delivery" -> mission.destination = origin.add(80.0, 0.0, 80.0);
            case "strike" -> {
                mission.destination = origin.add(80.0, 0.0, 80.0);
                mission.payloadId = WarheadRegistry.defaultId();
            }
            case "squad" -> {
                mission.destination = origin.add(120.0, 0.0, 120.0);
                mission.count = 4;
            }
            case "lowbattery" -> {
                // Just enough to get there, nothing like enough to come back: watch it strand and park.
                mission.destination = origin.add(150.0, 0.0, 150.0);
                mission.batteryCapacity = 500.0;
            }
            case "longrange" -> {
                // Far enough that it offloads to the sim and onloads again near the target.
                mission.destination = origin.add(900.0, 0.0, 900.0);
                mission.batteryCapacity = 8000.0;
            }
            case "terrain" -> {
                mission.destination = origin.add(400.0, 0.0, 400.0);
                mission.cruiseAltitude = 10.0;
                mission.batteryCapacity = 6000.0;
            }
            case "downed" -> {
                mission.destination = origin.add(200.0, 0.0, 200.0);
                // Named and shot down by hand a moment later, so it has to exist now rather than be queued.
                mission.launchInterval = 0;
                DroneMission.Result spawned = mission.dispatch(level, origin, null);
                if (!spawned.ok()) {
                    src.sendFailure(Component.literal("Scenario refused: " + spawned.error()));
                    return 0;
                }
                // Let it get airborne first, then knock it down, so the spin-in is actually visible.
                spawned.drones().forEach(d -> d.setState(DroneState.TRANSIT));
                src.sendSuccess(() -> Component.literal(
                        "Launched 1 drone; it will be shot down in a few seconds"), false);
                DOWNED_SCENARIO.add(spawned.drones().get(0).getUUID());
                return 1;
            }
            default -> {
                src.sendFailure(Component.literal("Unknown scenario. Try: " + String.join(", ", SCENARIOS)));
                return 0;
            }
        }

        CompoundTag crate = mission.isStrike() ? null : new CompoundTag();
        DroneMission.Result result = mission.dispatch(level, origin, crate);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Scenario refused: " + result.error()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format("Scenario '%s': %d drone(s) away",
                name, result.ordered())), false);
        return result.ordered();
    }

    /**
     * Drones queued to be shot down by the {@code downed} scenario once they are properly airborne.
     */
    private static final List<UUID> DOWNED_SCENARIO = new ArrayList<>();
    private static int downedScenarioDelay;

    /**
     * Drives the {@code downed} scenario's delayed kill.
     */
    private static void tickScenarios(ServerLevel level) {
        if (DOWNED_SCENARIO.isEmpty()) {
            return;
        }
        if (++downedScenarioDelay < 100) {
            return;
        }
        downedScenarioDelay = 0;
        for (UUID id : new ArrayList<>(DOWNED_SCENARIO)) {
            if (level.getEntity(id) instanceof DroneEntity drone) {
                drone.shootDown();
                DOWNED_SCENARIO.remove(id);
            }
        }
    }

    private static int clearDrones(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        int removed = 0;
        for (DroneEntity drone : new ArrayList<>(DroneTracker.drones(level))) {
            drone.discard();
            removed++;
        }
        for (CrateEntity crate : level.getEntitiesOfClass(CrateEntity.class,
                new AABB(BlockPos.containing(src.getPosition())).inflate(4096.0))) {
            crate.discard();
            removed++;
        }
        SimDroneRegistry registry = SimDroneRegistry.get(level);
        for (SimDrone sd : new ArrayList<>(registry.view())) {
            registry.remove(sd);
            removed++;
        }
        int total = removed;
        src.sendSuccess(() -> Component.literal("Removed " + total + " drone(s)/crate(s)"), true);
        return total;
    }

    private static int recallDrones(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 home = src.getPosition();
        int count = 0;
        for (DroneEntity drone : DroneTracker.drones(level)) {
            if (!drone.getDroneState().powered()) {
                continue;
            }
            drone.setExfil(home);
            drone.setDestination(null);
            drone.setState(DroneState.EXFIL);
            count++;
        }
        for (SimDrone sd : new ArrayList<>(SimDroneRegistry.get(level).view())) {
            sd.exfil = home;
            sd.destination = null;
            sd.state = DroneState.EXFIL;
            count++;
        }
        int total = count;
        src.sendSuccess(() -> Component.literal("Recalled " + total + " drone(s)"), true);
        return total;
    }

    /**
     * Dump the nearest drone's telemetry timeline, the drone equivalent of {@code track}.
     */
    private static int droneTelemetry(CommandSourceStack src) {
        Vec3 from = src.getPosition();
        DroneEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (DroneEntity drone : DroneTracker.drones(src.getLevel())) {
            double d = drone.position().distanceToSqr(from);
            if (d < best) {
                best = d;
                nearest = drone;
            }
        }
        if (nearest == null) {
            src.sendFailure(Component.literal("No drones in this dimension."));
            return 0;
        }
        DroneEntity target = nearest;
        WFTelemetry telemetry = WFTelemetryService.get(target.getUUID());
        if (telemetry == null) {
            // Open one now so the rest of this flight is recorded, and say so rather than silently failing.
            target.openTelemetry();
            src.sendSuccess(() -> Component.literal(
                            "No timeline yet for " + target.getUUID() + " - now recording, run this again shortly.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format("%s - %s, %.0f%% battery, %d events",
                target.getUUID(), target.getDroneState(), target.battery().percent(), telemetry.total()))
                .withStyle(ChatFormatting.GOLD), false);
        List<?> events = telemetry.events();
        for (int i = Math.max(0, events.size() - 20); i < events.size(); i++) {
            String line = events.get(i).toString();
            src.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /** Dispatch an armed flight. */
    private static int strikeDrones(CommandSourceStack src, Vec3 destination, int count, String warhead) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();

        DroneMission mission = new DroneMission();
        mission.destination = destination;
        mission.count = count;
        mission.payloadId = warhead.isEmpty() ? WarheadRegistry.defaultId() : WarheadRegistry.parse(warhead);
        if (!WarheadRegistry.exists(mission.payloadId)) {
            src.sendFailure(Component.literal("Unknown warhead '" + warhead + "'. Known: "
                    + WarheadRegistry.ids().stream().map(ResourceLocation::getPath).sorted().toList()));
            return 0;
        }

        DroneMission.Result result = mission.dispatch(level, origin, null);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Strike refused: " + result.error()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "Launched %d strike drone(s) with %s at %d, %d, %d (%dm)",
                result.ordered(), mission.payloadId.getPath(),
                (int) destination.x, (int) destination.y, (int) destination.z,
                (int) origin.distanceTo(destination))), true);
        return result.ordered();
    }

    /** Suggests the mines built to be dispensed, first and by themselves. */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SOWN_MINES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    MinePresetRegistry.sown().stream().map(preset -> preset.id().getPath()), builder);

    /** Dispatch a mine-laying flight. */
    private static int minelayDrones(CommandSourceStack src, Vec3 destination, int count, String mine,
                                     int mines, double spacing) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();

        ResourceLocation presetId = mine.isEmpty()
                ? MinePresetRegistry.defaultSownId() : MinePresetRegistry.parse(mine);
        MinePreset preset = MinePresetRegistry.get(presetId);
        if (preset == null) {
            src.sendFailure(Component.literal("Unknown mine '" + mine + "'. Known: "
                    + MinePresetRegistry.all().stream().map(p -> p.id().getPath()).sorted().toList()));
            return 0;
        }

        DroneMission mission = new DroneMission();
        mission.destination = destination;
        mission.count = count;
        mission.mines = MineLoad.of(presetId, mines, spacing);
        mission.program = DroneProgram.of(List.of(new DroneTask.Minelay(destination), new DroneTask.Exfil()));

        DroneMission.Result result = mission.dispatch(level, origin, null);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Lay refused: " + result.error()));
            return 0;
        }

        if (!preset.sownOnly()) {
            src.sendSuccess(() -> Component.literal(presetId.getPath()
                            + " is not built to be sown: it has no self-destruct and may not land the right way up")
                    .withStyle(ChatFormatting.YELLOW), false);
        }

        MineLoad rack = mission.mines;
        src.sendSuccess(() -> Component.literal(String.format(
                "Launched %d minelayer(s): %d x %s each, %.0fm strip at %d, %d, %d (%dm out)",
                result.ordered(), rack.capacity(), presetId.getPath(), rack.laneLength(),
                (int) destination.x, (int) destination.y, (int) destination.z,
                (int) origin.distanceTo(destination))), true);
        return result.ordered();
    }

    /** Point a drone pad at a destination. */
    private static int configurePad(CommandSourceStack src, BlockPos pos, Vec3 destination, int count,
                                    String formation, double spacing, String coordination) {
        if (!(src.getLevel().getBlockEntity(pos) instanceof DronePadBlockEntity pad)) {
            src.sendFailure(Component.literal("No drone pad at " + pos.toShortString()));
            return 0;
        }
        DroneMission mission = pad.mission();
        mission.destination = destination;
        mission.count = count;
        mission.formationId = Formations.parse(formation);
        mission.formationSpacing = Formation.clampSpacing(spacing);
        mission.coordinationId = CoordinationModels.parse(coordination);
        mission.exfil = Vec3.atCenterOf(pos.above());
        pad.setMission(mission);
        src.sendSuccess(() -> Component.literal(String.format(
                "Pad at %s set: %d drone(s), %s formation at %.0f spacing, to %d %d %d", pos.toShortString(),
                count, mission.formationId.getPath(), mission.formationSpacing,
                (int) destination.x, (int) destination.y, (int) destination.z)), true);
        return 1;
    }

    /**
     * @return a short description of what a drone is carrying, for the listing.
     */
    private static String load(boolean hasCrate, @Nullable ResourceLocation payloadId) {
        return load(hasCrate, payloadId, null);
    }

    private static String load(boolean hasCrate, @Nullable ResourceLocation payloadId,
                               @Nullable com.wf.wflib.drone.MineLoad mines) {
        if (payloadId != null) {
            return "armed:" + payloadId.getPath();
        }
        if (mines != null && !mines.empty()) {
            return "mines:" + mines.label();
        }
        return hasCrate ? "carrying" : "empty";
    }

    /** Turn off-world simulation on or off. */
    private static int droneSim(CommandSourceStack src, Boolean enabled) {
        if (enabled != null) {
            SimDroneManager.offloadEnabled = enabled;
        }
        boolean on = SimDroneManager.offloadEnabled;
        src.sendSuccess(() -> Component.literal("Drone off-world simulation is " + (on ? "on" : "off")
                + (on ? "" : " - drones stay real for their whole mission")), true);
        return on ? 1 : 0;
    }

    /** Report where the planning actually runs. */
    /** Why every squad in this dimension is or is not ready to leave form-up. */
    private static int droneMuster(CommandSourceStack src) {
        List<SquadView> squads = DroneAiScheduler.squadsFor(src.getLevel());
        if (squads.isEmpty()) {
            src.sendFailure(Component.literal("No drones in this dimension."));
            return 0;
        }
        int held = 0;
        for (SquadView squad : squads) {
            if (squad.size() <= 1) {
                continue;
            }
            int ordered = squad.leader().squadSize();
            List<String> climbing = new ArrayList<>();
            for (DroneSnapshot member : squad.slots()) {
                if (member.state() == DroneState.IDLE || member.state() == DroneState.TAKEOFF) {
                    climbing.add(member.state().name().toLowerCase(Locale.ROOT));
                }
            }
            SquadAnchor anchor = squad.anchor();
            boolean formed = squad.coordination().formedUp(squad, anchor);
            double worst = anchor == null ? Double.NaN : Slots.worstError(squad, anchor);
            double tolerance = Math.max(Tuning.MUSTER_IN_PLACE_FLOOR,
                    squad.spacing() * Tuning.MUSTER_IN_PLACE);
            boolean ready = squad.size() >= ordered && climbing.isEmpty() && formed;
            if (!ready) {
                held++;
            }

            src.sendSuccess(() -> Component.literal(String.format(
                    "squad %08x: %d/%d up, %s in %s at %.0f spacing - %s",
                    squad.squadId(), squad.size(), ordered,
                    squad.coordination().id(),
                    squad.formationId() == null ? "vee" : squad.formationId().getPath(),
                    squad.spacing(), ready ? "READY" : "holding"))
                    .withStyle(ready ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  all up:      %s (%d of %d turned up)",
                    squad.size() >= ordered ? "yes" : "NO", squad.size(), ordered))
                    .withStyle(squad.size() >= ordered ? ChatFormatting.GRAY : ChatFormatting.RED), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  climbed out: %s%s", climbing.isEmpty() ? "yes" : "NO - " + climbing,
                    climbing.isEmpty() ? "" : " still on the way up"))
                    .withStyle(climbing.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.RED), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  in formation: %s (worst slot error %.1f against a tolerance of %.1f%s)",
                    formed ? "yes" : "NO", worst, tolerance,
                    anchor == null ? ", no frame yet" : ""))
                    .withStyle(formed ? ChatFormatting.GRAY : ChatFormatting.RED), false);
            if (!formed && anchor != null) {
                Formation shape = Formations.get(squad.formationId());
                Vec3 forward = Formation.forward(anchor.yaw());
                for (DroneSnapshot member : squad.slots()) {
                    Vec3 slot = shape.slot(squad.indexOf(member), anchor.pos(), forward, squad.spacing());
                    double error = member.pos().distanceTo(slot);
                    Vec3 gap = member.pos().subtract(slot);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "    slot %-2d %-8s off by %5.1f (flat %5.1f, vert %+6.1f)",
                            squad.indexOf(member), member.state().name().toLowerCase(Locale.ROOT),
                            error, gap.horizontalDistance(), gap.y))
                            .withStyle(error > tolerance ? ChatFormatting.RED : ChatFormatting.DARK_GRAY),
                            false);
                }
            }
        }
        return held == 0 ? 1 : 0;
    }

    private static int droneThreads(CommandSourceStack src) {
        boolean armed = WorldThread.armed();
        boolean onWorldThread = WorldThread.isWorldThread();
        String planner = PlannerStats.lastThread();
        // This command runs on the world thread, so a search that ran anywhere else proves the split.
        boolean offThread = !planner.equals(Thread.currentThread().getName());

        src.sendSuccess(() -> Component.literal(String.format(
                "Planner pool: %d worker(s)   assertions %s",
                DroneAiScheduler.poolSize(), armed ? "armed" : "NOT ARMED"))
                .withStyle(armed ? ChatFormatting.GOLD : ChatFormatting.RED), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "This command is on the world thread: %s (%s)",
                onWorldThread, Thread.currentThread().getName())), false);

        if (PlannerStats.count() == 0) {
            src.sendSuccess(() -> Component.literal(
                            "No terrain searches yet - fly a drone, then run this again.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "%d terrain search(es), last on '%s' in %.1fms (worst %.1fms)",
                PlannerStats.count(), planner,
                PlannerStats.lastMicros() / 1000.0, PlannerStats.worstMicros() / 1000.0))
                .withStyle(offThread ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        src.sendSuccess(() -> Component.literal(offThread
                ? "A* is running OFF the world thread, as required."
                : "A* RAN ON THE WORLD THREAD - this is a bug.")
                .withStyle(offThread ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return offThread ? 1 : 0;
    }

    // --- stations and exchanges ---

    /**
     * @return the pad the player is standing on or looking at, or null.
     */
    @Nullable
    private static DronePadBlockEntity nearestPad(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());
        DronePadBlockEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -4, -6), origin.offset(6, 4, 6))) {
            if (level.getBlockEntity(pos) instanceof DronePadBlockEntity pad) {
                double distance = pos.distToCenterSqr(src.getPosition());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pad;
                }
            }
        }
        return best;
    }

    // --- stations, blueprints and work ---

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ROLES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(StationRole.values()).map(StationRole::id).toList(), builder);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> KINDS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(StationKind.values()).map(StationKind::id).toList(), builder);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> BLUEPRINTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    BlueprintLibrary.names(ctx.getSource().getServer()), builder);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> JOBS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    WorkRegistry.get(ctx.getSource().getServer()).all().stream()
                            .map(job -> job.id().toString().substring(0, 8)).toList(), builder);

    private static LiteralArgumentBuilder<CommandSourceStack> stationRoleCommand() {
        return Commands.literal("role")
                .executes(ctx -> stationRoles(ctx.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests(KINDS)
                                .executes(ctx -> stationKind(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "kind")))))
                .then(Commands.literal("on")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(ROLES)
                                .executes(ctx -> stationRole(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "role"), true))))
                .then(Commands.literal("off")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(ROLES)
                                .executes(ctx -> stationRole(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "role"), false))));
    }

    /** {@code /wflib debug}: the two overlays plus a text dump. */
    private static LiteralArgumentBuilder<CommandSourceStack> mineDebugCommand() {
        return Commands.literal("debug")
                .then(Commands.literal("mines")
                        .executes(ctx -> setMineDebug(ctx.getSource(), !MineDebug.renderAreas()))
                        .then(Commands.literal("on").executes(ctx -> setMineDebug(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setMineDebug(ctx.getSource(), false))))
                .then(Commands.literal("explosions")
                        .executes(ctx -> setExplosionDebug(ctx.getSource(), !ExplosionTrace.enabled()))
                        .then(Commands.literal("on").executes(ctx -> setExplosionDebug(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setExplosionDebug(ctx.getSource(), false)))
                        .then(Commands.literal("status").executes(ctx -> {
                            var traces = ExplosionTrace.recent();
                            long now = ctx.getSource().getLevel().getGameTime();
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "recording=%s  traces=%d  server t=%d",
                                    ExplosionTrace.enabled(), traces.size(), now)), false);
                            for (var t : traces) {
                                ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                        "  t=%d (age %d)  size=%.1f  rays=%d/%d  cone=%s",
                                        t.gameTime(), now - t.gameTime(), t.size(), t.rays().size(),
                                        t.totalRays(), t.axis() == null ? "none"
                                                : String.format("(%.2f, %.2f, %.2f) +/-%.0f deg",
                                                        t.axis().x, t.axis().y, t.axis().z,
                                                        t.halfAngleDeg()))), false);
                                for (var v : t.victims()) {
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "    %-13s %-6s dmg=%.1f at %.1f %.1f %.1f", v.verdict(),
                                            v.name(), v.damage(), v.pos().x, v.pos().y, v.pos().z)), false);
                                }
                            }
                            return 1;
                        }))
                        .then(Commands.literal("clear").executes(ctx -> {
                            ExplosionTrace.clear();
                            ctx.getSource().sendSuccess(() -> Component.literal("Cleared recorded blasts."), false);
                            return 1;
                        })))
                .then(Commands.literal("scatter")
                        .executes(ctx -> scatterMines(ctx.getSource(), 8,
                                MinePresetRegistry.defaultId().getPath()))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> scatterMines(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        MinePresetRegistry.defaultId().getPath()))
                                .then(Commands.argument("preset", StringArgumentType.string())
                                        .suggests(MINE_PRESETS)
                                        .executes(ctx -> scatterMines(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "preset"))))))
                .then(Commands.literal("mine")
                        .executes(ctx -> mineInfo(ctx.getSource()))
                        .then(Commands.literal("lay")
                                .then(Commands.argument("preset", StringArgumentType.string())
                                        .suggests(MINE_PRESETS)
                                        .then(Commands.argument("at", Vec3Argument.vec3())
                                                .executes(ctx -> layMine(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "preset"),
                                                        Vec3Argument.getVec3(ctx, "at"), 0.0f,
                                                        Laying.AS_BUILT))
                                                .then(Commands.argument("yaw",
                                                                FloatArgumentType.floatArg(-360.0f, 360.0f))
                                                        .executes(ctx -> layMine(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "preset"),
                                                                Vec3Argument.getVec3(ctx, "at"),
                                                                FloatArgumentType.getFloat(ctx, "yaw"),
                                                                Laying.AS_BUILT))
                                                        .then(Commands.literal("armed")
                                                                .executes(ctx -> layMine(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "preset"),
                                                                        Vec3Argument.getVec3(ctx, "at"),
                                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                                        Laying.ARMED)))
                                                        .then(Commands.literal("safe")
                                                                .executes(ctx -> layMine(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "preset"),
                                                                        Vec3Argument.getVec3(ctx, "at"),
                                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                                        Laying.SAFE))))))));
    }

    /** How {@code /wflib debug mine lay} leaves the mine it laid. */
    private enum Laying {
        /** Whatever the preset says: counting down, or safe if it needs activating. */
        AS_BUILT,
        /** Past the arming delay, the way a rack that has been sitting there a minute already is. */
        ARMED,
        /** Inert, which is what laying one while crouching does; see {@code MineItem#useOn}. */
        SAFE
    }

    /** Lays one mine from its preset, facing a given yaw, optionally already armed. */
    private static int layMine(CommandSourceStack source, String presetName, Vec3 at, float yaw,
                               Laying how) {
        ServerLevel level = source.getLevel();
        MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.parse(presetName));
        if (preset == null) {
            source.sendFailure(Component.literal("No mine preset '" + presetName + "'."));
            return 0;
        }
        MineEntity mine = preset.build(level, yaw);
        mine.moveTo(at.x, at.y, at.z, yaw, 0.0f);
        level.addFreshEntity(mine);
        switch (how) {
            case ARMED -> {
                mine.activate();
                mine.forceArmed();
            }
            case SAFE -> mine.layInert();
            case AS_BUILT -> {
            }
        }
        source.sendSuccess(() -> Component.literal(String.format("Laid %s at %.1f %.1f %.1f facing %.0f (%s)",
                preset.id().getPath(), at.x, at.y, at.z, yaw, mine.getState())), false);
        return 1;
    }

    /** {@code /wflib demolition ...}: the mining-charge side of the mod. */
    private static LiteralArgumentBuilder<CommandSourceStack> demolitionCommand() {
        return Commands.literal("demolition")
                .then(Commands.literal("detonate")
                        .then(Commands.argument("at", BlockPosArgument.blockPos())
                                .executes(ctx -> detonateCharge(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "at")))))
                .then(Commands.literal("fire")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> fireOnCommand(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "targets")))));
    }

    /**
     * Fires detonatable entities (mines), without a detonator, the way {@code detonate} fires a charge without one.
     */
    private static int fireOnCommand(CommandSourceStack source,
                                     java.util.Collection<? extends Entity> targets) {
        ServerLevel level = source.getLevel();
        int fired = 0;
        for (Entity entity : targets) {
            if (entity instanceof com.wf.wflib.demolition.IDetonatableEntity detonatable
                    && detonatable.detonateOnCommand(level, null)) {
                fired++;
            }
        }
        int count = fired;
        if (count == 0) {
            source.sendFailure(Component.literal("Nothing detonatable in that selection."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Fired " + count + " on command"), true);
        return count;
    }

    /** Fire the mining charge at a position without a detonator. */
    private static int detonateCharge(CommandSourceStack source, BlockPos at) {
        ServerLevel level = source.getLevel();
        if (!com.wf.wflib.demolition.IDetonatable.isExplosive(level.getBlockState(at))) {
            source.sendFailure(Component.literal("No detonatable charge at "
                    + at.getX() + ", " + at.getY() + ", " + at.getZ()));
            return 0;
        }
        com.wf.wflib.demolition.IDetonatable.tryDetonate(level, at, null);
        source.sendSuccess(() -> Component.literal("Detonated charge at "
                + at.getX() + ", " + at.getY() + ", " + at.getZ()), true);
        return 1;
    }

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> MINE_PRESETS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    MinePresetRegistry.all().stream().map(preset -> preset.id().getPath()), builder);

    /**
     * Throws a handful of mines up and out from wherever the command was run, the way a deployer or a dispersing
     * payload will: they turn over on the way down and lie at whatever angle they land at.
     */
    private static int scatterMines(CommandSourceStack source, int count, String presetName) {
        ServerLevel level = source.getLevel();
        MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.parse(presetName));
        if (preset == null) {
            source.sendFailure(Component.literal("No mine preset '" + presetName + "'."));
            return 0;
        }
        Vec3 from = source.getPosition()
                .add(0.0, 1.0, 0.0);
        RandomSource random = level.getRandom();
        for (int i = 0; i < count; i++) {
            MineEntity mine = preset.build(level, random.nextFloat() * 360.0f);
            mine.moveTo(from.x, from.y, from.z, mine.getYRot(), 0.0f);
            mine.scatter(new Vec3((random.nextDouble() - 0.5) * 0.7,
                    0.32 + random.nextDouble() * 0.28,
                    (random.nextDouble() - 0.5) * 0.7), random);
            level.addFreshEntity(mine);
        }
        source.sendSuccess(() -> Component.literal(
                "Scattered " + count + " x " + preset.id().getPath()), false);
        return count;
    }

    private static int setMineDebug(CommandSourceStack source, boolean value) {
        MineDebug.setRenderAreas(value);
        source.sendSuccess(() -> Component.literal("Mine detection overlay " + (value ? "on" : "off")
                + (value ? " (singleplayer only)" : "")), false);
        return 1;
    }

    private static int setExplosionDebug(CommandSourceStack source, boolean value) {
        ExplosionTrace.setEnabled(value);
        source.sendSuccess(() -> Component.literal("Explosion raycast recording " + (value ? "on" : "off")
                + (value ? " (singleplayer only to draw)" : "")), false);
        return 1;
    }

    /** Prints the nearest mine's live state. */
    private static int mineInfo(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        MineEntity nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MineEntity mine : level.getEntitiesOfClass(MineEntity.class,
                new AABB(at, at).inflate(64.0), MineEntity::isAlive)) {
            double d = mine.distanceToSqr(at);
            if (d < bestSq) {
                bestSq = d;
                nearest = mine;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("No mine within 64 blocks."));
            return 0;
        }
        MineEntity mine = nearest;
        double distance = Math.sqrt(bestSq);
        Vec3 facing = mine.facing();
        source.sendSuccess(() -> Component.literal(String.format("Mine %s at %.1f %.1f %.1f",
                mine.getStringUUID().substring(0, 8), mine.getX(), mine.getY(), mine.getZ())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  state=%s  trigger=%s  warhead=%s  bounds=%s", mine.getState(),
                mine.getTriggerId().getPath(), mine.getDetonationId().getPath(),
                mine.bounds())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  trigger=%.1f  crouching=%.1f  arc=%.0f  yaw=%.0f facing=(%.2f, %.2f)",
                mine.getTriggerRange(), mine.getSneakTriggerRange(), mine.getArc(), mine.getYRot(),
                facing.x, facing.z)), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  pitch=%.0f  roll=%.0f  tumble=%.0f deg/t  rest tilt=%.0f", mine.getXRot(),
                mine.getRoll(), mine.getTumble(), mine.getRestTilt())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  distance from here=%.2f  in arc=%s", distance,
                inArc(mine, at) ? "yes" : "no")), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  wet=%s  arms only in water=%s  self-destruct=%s", mine.isWet(), mine.armsOnlyInWater(),
                mine.selfDestructTicks() <= 0 ? "never"
                        : mine.ticksToSelfDestruct() / 20 + "s of " + mine.selfDestructTicks() / 20 + "s")),
                false);
        return 1;
    }

    /** Whether {@code at} lies inside a directional mine's wedge, which is the usual reason for silence. */
    private static boolean inArc(MineEntity mine, Vec3 at) {
        Vec3 facing = mine.facing();
        double dx = at.x - mine.getX();
        double dz = at.z - mine.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return true;
        }
        double cos = (dx * facing.x + dz * facing.z) / horizontal;
        return cos >= Math.cos(Math.toRadians(mine.getArc() * 0.5));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> blueprintCommand() {
        return Commands.literal("blueprint")
                .executes(ctx -> blueprintList(ctx.getSource()))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            BlueprintLibrary.invalidate();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("Blueprint cache cleared."), false);
                            return 1;
                        }))
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(BLUEPRINTS)
                        .executes(ctx -> blueprintInfo(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("build")
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(BLUEPRINTS)
                        .executes(ctx -> build(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                BlockPos.containing(ctx.getSource().getPosition())))
                        .then(Commands.argument("at", BlockPosArgument.blockPos())
                                .executes(ctx -> build(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "at")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> salvageCommand() {
        return Commands.literal("salvage")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(ctx -> salvage(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "to")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> jobCommand() {
        return Commands.literal("job")
                .executes(ctx -> jobList(ctx.getSource()))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(JOBS)
                                .executes(ctx -> jobCancel(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("work")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(JOBS)
                                .executes(ctx -> jobWork(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"), 1))
                                .then(Commands.argument("drones", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> jobWork(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                IntegerArgumentType.getInteger(ctx, "drones"))))))
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(JOBS)
                        .executes(ctx -> jobInfo(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"))));
    }

    private static int stationRoles(CommandSourceStack src) {
        StationRecord record = ownStation(src);
        if (record == null) {
            return 0;
        }
        src.sendSuccess(() -> Component.literal("This station is a " + record.kind() + ".")
                .withStyle(ChatFormatting.AQUA), false);
        for (StationRole role : StationRole.values()) {
            boolean on = record.has(role);
            src.sendSuccess(() -> Component.literal(String.format("  %s %-10s %s",
                            on ? "✔" : "✘", role.id(), role.description()))
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    private static int stationKind(CommandSourceStack src, String id) {
        StationRecord record = ownStation(src);
        if (record == null) {
            return 0;
        }
        StationKind kind = StationKind.byId(id);
        if (kind == null) {
            src.sendFailure(Component.literal("No such kind. Try: "
                    + String.join(", ", java.util.Arrays.stream(StationKind.values())
                    .map(StationKind::id).toList())));
            return 0;
        }
        StationRegistry.get(src.getLevel()).setRoles(record.code(), kind.roles());
        src.sendSuccess(() -> Component.literal("This station is now a " + kind.id() + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int stationRole(CommandSourceStack src, String id, boolean on) {
        StationRecord record = ownStation(src);
        if (record == null) {
            return 0;
        }
        StationRole role = StationRole.byId(id);
        if (role == null) {
            src.sendFailure(Component.literal("No such role. Try: "
                    + String.join(", ", java.util.Arrays.stream(StationRole.values())
                    .map(StationRole::id).toList())));
            return 0;
        }
        boolean changed = StationRegistry.get(src.getLevel()).setRole(record.code(), role, on);
        src.sendSuccess(() -> Component.literal(changed
                ? role.id() + (on ? " on. Now a " : " off. Now a ") + record.kind() + "."
                : "No change."), false);
        return changed ? 1 : 0;
    }

    /**
     * @return the station record for the pad the caller is standing at, complaining to them if there is not
     *      one. Every station command is about <em>your</em> station, which is the only one whose position you are
     *      allowed to know.
     */
    @Nullable
    private static StationRecord ownStation(CommandSourceStack src) {
        DronePadBlockEntity pad = nearestPad(src);
        if (pad == null) {
            src.sendFailure(Component.literal("Stand near a drone pad."));
            return null;
        }
        StationRecord record = StationRegistry.get(src.getLevel()).byCode(pad.stationCode(src.getLevel()));
        if (record == null) {
            src.sendFailure(Component.literal("That pad is not registered."));
        }
        return record;
    }

    /**
     * @return the faction of whoever ran this command, or null for the console, which has none, and whose
     *      jobs are therefore judged as an outsider's on every claim.
     */
    @Nullable
    private static UUID factionOf(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        return player == null ? null : com.wf.wflib.recon.ReconOwners.owningEntity(player);
    }

    /**
     * Refuse a job whose ground is not ours to touch, saying where and why.
     *
     * @return true if the job may go ahead
     */
    private static boolean surveyed(CommandSourceStack src, BoundingBox box, String what) {
        BlockPos.MutableBlockPos where = new BlockPos.MutableBlockPos();
        TerritoryVerdict verdict = SiteRules.survey(src.getLevel(), factionOf(src), box, where);
        if (verdict.allowed()) {
            return true;
        }
        src.sendFailure(Component.literal(String.format("Cannot %s there: %s (at %d, %d).",
                what, verdict.reason(), where.getX(), where.getZ())));
        return false;
    }

    private static int blueprintList(CommandSourceStack src) {
        List<String> names = BlueprintLibrary.names(src.getServer());
        if (names.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No blueprints. Put " + String.join("/",
                            com.wf.wflib.build.BlueprintFormats.extensions())
                    + " files in " + BlueprintLibrary.FOLDER + " inside the world folder.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(names.size() + " blueprint(s):")
                .withStyle(ChatFormatting.GOLD), false);
        for (String name : names) {
            src.sendSuccess(() -> Component.literal("• " + name), false);
        }
        return names.size();
    }

    private static int blueprintInfo(CommandSourceStack src, String name) {
        Blueprint blueprint;
        try {
            blueprint = BlueprintLibrary.load(src.getServer(), name);
        } catch (BlueprintFormat.BlueprintException e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        WorkPlan plan = ConstructionPlan.of(blueprint, BlockPos.ZERO);
        src.sendSuccess(() -> Component.literal(String.format("%s  %dx%dx%d  %d blocks, %d states",
                        blueprint.name(), blueprint.size().getX(), blueprint.size().getY(),
                        blueprint.size().getZ(), blueprint.blocks(), blueprint.palette().size() - 1))
                .withStyle(ChatFormatting.GOLD), false);
        reportPlan(src, plan);
        if (blueprint.blockEntities() > 0) {
            src.sendSuccess(() -> Component.literal("  " + blueprint.blockEntities()
                            + " block entities; their contents are not reproduced")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        if (blueprint.unresolved() > 0) {
            src.sendSuccess(() -> Component.literal("  " + blueprint.unresolved()
                            + " palette entries name blocks this server does not have; they load as air")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int build(CommandSourceStack src, String name, BlockPos origin) {
        Blueprint blueprint;
        try {
            blueprint = BlueprintLibrary.load(src.getServer(), name);
        } catch (BlueprintFormat.BlueprintException e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        WorkPlan plan = ConstructionPlan.of(blueprint, origin);
        if (plan.orders().isEmpty()) {
            src.sendFailure(Component.literal("'" + blueprint.name() + "' has nothing to build."));
            return 0;
        }
        if (!surveyed(src, plan.bounds(), "build")) {
            return 0;
        }
        WorkJob job = BuildJobs.construct(src.getLevel(), plan, name, null, factionOf(src));
        WorkRegistry.get(src.getLevel()).add(job);
        src.sendSuccess(() -> Component.literal(String.format("Queued %s: %d blocks at %d, %d, %d  [%s]",
                        blueprint.name(), plan.size(), origin.getX(), origin.getY(), origin.getZ(),
                        job.id().toString().substring(0, 8)))
                .withStyle(ChatFormatting.GREEN), true);
        reportPlan(src, plan);
        return plan.size();
    }

    private static int salvage(CommandSourceStack src, BlockPos from, BlockPos to) {
        BoundingBox box = BoundingBox.fromCorners(from, to);
        WorkPlan plan;
        try {
            plan = SalvagePlan.of(src.getLevel(), box);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        if (plan.orders().isEmpty()) {
            src.sendFailure(Component.literal("Nothing to take down there."));
            return 0;
        }
        if (!surveyed(src, box, "salvage")) {
            return 0;
        }
        WorkJob job = BuildJobs.salvage(src.getLevel(), plan, null, factionOf(src));
        WorkRegistry.get(src.getLevel()).add(job);
        src.sendSuccess(() -> Component.literal(String.format("Queued salvage: %d blocks over %dx%dx%d  [%s]",
                        plan.size(), box.getXSpan(), box.getYSpan(), box.getZSpan(),
                        job.id().toString().substring(0, 8)))
                .withStyle(ChatFormatting.GREEN), true);
        reportPlan(src, plan);
        return plan.size();
    }

    /**
     * The bill and the caveats, shared by everything that makes or inspects a plan.
     */
    private static void reportPlan(CommandSourceStack src, WorkPlan plan) {
        List<String> lines = plan.billLines();
        int shown = Math.min(6, lines.size());
        for (int i = 0; i < shown; i++) {
            String line = lines.get(i);
            src.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.GRAY), false);
        }
        if (lines.size() > shown) {
            int rest = lines.size() - shown;
            src.sendSuccess(() -> Component.literal("  ...and " + rest + " more kinds, "
                    + plan.totalItems() + " items in total").withStyle(ChatFormatting.GRAY), false);
        }
        if (plan.unbuildable() > 0) {
            src.sendSuccess(() -> Component.literal("  " + plan.unbuildable()
                            + " blocks have no item that places them (fluids, potted plants) "
                            + "and are left out")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    private static int jobList(CommandSourceStack src) {
        List<WorkJob> jobs = WorkRegistry.get(src.getServer()).all();
        if (jobs.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No work queued."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(jobs.size() + " job(s):")
                .withStyle(ChatFormatting.GOLD), false);
        for (WorkJob job : jobs) {
            BlockPos at = job.centre();
            String line = String.format("• %s  %s  %s  %d, %d, %d  %s",
                    job.id().toString().substring(0, 8), job.kind().getPath(), job.label(),
                    at.getX(), at.getY(), at.getZ(), job.progress());
            src.sendSuccess(() -> Component.literal(line).withStyle(
                    job.over() ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW), false);
        }
        return jobs.size();
    }

    private static int jobInfo(CommandSourceStack src, String prefix) {
        WorkJob job = findJob(src, prefix);
        if (job == null) {
            return 0;
        }
        BoundingBox box = job.bounds();
        src.sendSuccess(() -> Component.literal(job.label() + "  [" + job.id() + "]")
                .withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal(String.format("  %s in %s, %dx%dx%d from %d, %d, %d",
                job.kind().getPath(), job.dimension().location().getPath(),
                box.getXSpan(), box.getYSpan(), box.getZSpan(),
                box.minX(), box.minY(), box.minZ())), false);
        src.sendSuccess(() -> Component.literal("  " + job.progress()
                + ", frontier at sequence " + job.queue().frontier()), false);
        String station = BuildJobs.stationOf(job);
        if (station != null) {
            src.sendSuccess(() -> Component.literal("  station " + StationCode.pretty(station)), false);
        }
        return 1;
    }

    /** Put drones from the nearest pad onto a job. */
    private static int jobWork(CommandSourceStack src, String prefix, int drones) {
        WorkJob job = findJob(src, prefix);
        if (job == null) {
            return 0;
        }
        if (!job.dimension().equals(src.getLevel().dimension())) {
            src.sendFailure(Component.literal("That job is in another dimension."));
            return 0;
        }
        if (job.over()) {
            src.sendFailure(Component.literal("That job is " + job.progress() + "."));
            return 0;
        }
        DronePadBlockEntity pad = nearestPad(src);
        if (pad == null) {
            src.sendFailure(Component.literal("Stand near a drone pad."));
            return 0;
        }
        DroneMission mission = pad.mission();
        mission.count = drones;
        mission.destination = Vec3.atCenterOf(job.centre());
        mission.payloadId = null;
        mission.program = DroneProgram.EMPTY;
        mission.mode = ExchangeMode.DIRECT;
        mission.recipientCode = null;
        mission.jobId = job.id();
        DroneMission.Result result = mission.dispatch(src.getLevel(),
                Vec3.atCenterOf(pad.getBlockPos()).add(0.0, 1.0, 0.0), null);
        if (!result.ok()) {
            src.sendFailure(Component.literal("Refused: " + result.error()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(String.format("%d drone(s) on %s.",
                result.ordered(), job.label())).withStyle(ChatFormatting.GREEN), true);
        return result.ordered();
    }

    private static int jobCancel(CommandSourceStack src, String prefix) {
        WorkJob job = findJob(src, prefix);
        if (job == null) {
            return 0;
        }
        if (!WorkRegistry.get(src.getServer()).cancel(job.id())) {
            src.sendFailure(Component.literal("Already cancelled."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Cancelled " + job.label() + "."), true);
        return 1;
    }

    /**
     * @return the one job whose id starts with {@code prefix}, or null having said why not. Prefixes rather
     *      than full UUIDs because the list prints prefixes and nobody is retyping thirty-six characters
     */
    @Nullable
    private static WorkJob findJob(CommandSourceStack src, String prefix) {
        List<WorkJob> matches = WorkRegistry.get(src.getServer()).all().stream()
                .filter(job -> job.id().toString().startsWith(prefix.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        if (matches.isEmpty()) {
            src.sendFailure(Component.literal("No job starting '" + prefix + "'."));
            return null;
        }
        if (matches.size() > 1) {
            src.sendFailure(Component.literal(matches.size() + " jobs start '" + prefix + "'."));
            return null;
        }
        return matches.get(0);
    }

    private static int stationCode(CommandSourceStack src) {
        DronePadBlockEntity pad = nearestPad(src);
        if (pad == null) {
            src.sendFailure(Component.literal("Stand near a drone pad."));
            return 0;
        }
        String code = pad.stationCode(src.getLevel());
        StationRecord record = StationRegistry.get(src.getLevel()).byCode(code);
        int allowed = record == null ? 0 : record.allowed().size();
        src.sendSuccess(() -> Component.literal("Station code: " + StationCode.pretty(code))
                .withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.literal("Accepting handshakes from " + allowed + " station(s). "
                + "Give this code to anyone you want to trade with; it reveals nothing about where you are.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int stationAllow(CommandSourceStack src, String rawCode, boolean allow) {
        DronePadBlockEntity pad = nearestPad(src);
        if (pad == null) {
            src.sendFailure(Component.literal("Stand near a drone pad."));
            return 0;
        }
        String peer = StationCode.normalise(rawCode);
        if (!StationCode.valid(peer)) {
            src.sendFailure(Component.literal("That is not a valid station code."));
            return 0;
        }
        String own = pad.stationCode(src.getLevel());
        StationRegistry stations = StationRegistry.get(src.getLevel());
        boolean changed = allow ? stations.allow(own, peer) : stations.revoke(own, peer);
        src.sendSuccess(() -> Component.literal(changed
                ? (allow ? "Now accepting from " + StationCode.pretty(peer)
                         : "No longer accepting from " + StationCode.pretty(peer))
                : "No change."), false);
        return changed ? 1 : 0;
    }

    /** Send the nearest pad's cargo to another station by handshake. */
    private static int stationSend(CommandSourceStack src, String rawCode) {
        DronePadBlockEntity pad = nearestPad(src);
        if (pad == null) {
            src.sendFailure(Component.literal("Stand near a drone pad."));
            return 0;
        }
        String peer = StationCode.normalise(rawCode);
        if (!StationCode.valid(peer)) {
            src.sendFailure(Component.literal("That is not a valid station code."));
            return 0;
        }
        DroneMission mission = pad.mission();
        mission.mode = ExchangeMode.HANDSHAKE;
        mission.recipientCode = peer;
        pad.setMission(mission);
        String error = pad.dispatch();
        if (error != null) {
            // Whatever the pad said, and it is written not to say anything revealing.
            src.sendFailure(Component.literal("Refused: " + error));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Handshake away. The meeting point is not reported.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int stationList(CommandSourceStack src) {
        List<StationRecord> stations = StationRegistry.get(src.getLevel()).all();
        if (stations.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No stations registered."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(stations.size() + " station(s):")
                .withStyle(ChatFormatting.GOLD), false);
        for (StationRecord record : stations) {
            String line = String.format("• %s  %s  %s %d, %d, %d  allows %d",
                    StationCode.pretty(record.code()), record.kind(),
                    record.dimension().location().getPath(),
                    record.pos().getX(), record.pos().getY(), record.pos().getZ(), record.allowed().size());
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return stations.size();
    }

    private static int exchangeList(CommandSourceStack src) {
        List<Exchange> exchanges = ExchangeRegistry.get(src.getLevel()).all();
        if (exchanges.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No exchanges."), false);
            return 0;
        }
        for (Exchange exchange : exchanges) {
            String line = String.format("• %s  %s  %s <-> %s  at %d, %d  %s",
                    exchange.id().toString().substring(0, 8), exchange.phase(),
                    StationCode.pretty(exchange.sender().code()),
                    StationCode.pretty(exchange.recipient().code()),
                    (int) exchange.rendezvous().x, (int) exchange.rendezvous().z, exchange.note());
            src.sendSuccess(() -> Component.literal(line).withStyle(
                    exchange.active() ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY), false);
        }
        return exchanges.size();
    }

    /**
     * Cancel every live exchange. The testing counterpart of {@code drone clear}.
     */
    private static int exchangePurge(CommandSourceStack src) {
        List<Exchange> active = ExchangeRegistry.get(src.getLevel()).active();
        for (Exchange exchange : active) {
            ExchangeManager.cancel(src.getServer(), exchange, "purged by an operator");
        }
        src.sendSuccess(() -> Component.literal("Cancelled " + active.size() + " exchange(s)."), true);
        return active.size();
    }

    private static int exchangeCancel(CommandSourceStack src, UUID id) {
        Exchange exchange = ExchangeRegistry.get(src.getLevel()).byId(id);
        if (exchange == null) {
            src.sendFailure(Component.literal("No such exchange."));
            return 0;
        }
        ExchangeManager.cancel(src.getServer(), exchange, "cancelled by an operator");
        src.sendSuccess(() -> Component.literal("Exchange cancelled."), true);
        return 1;
    }

    private static int listDrones(CommandSourceStack src) {
        Set<DroneEntity> drones = DroneTracker.drones(src.getLevel());
        List<SimDrone> simulated = SimDroneRegistry.get(src.getLevel()).view();
        int queued = DroneLaunchQueue.get(src.getLevel()).pending();
        if (drones.isEmpty() && simulated.isEmpty() && queued == 0) {
            src.sendSuccess(() -> Component.literal("No drones in this dimension."), false);
            return 0;
        }
        if (queued > 0) {
            src.sendSuccess(() -> Component.literal(queued + " more still to launch")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        Vec3 from = src.getPosition();
        for (SimDrone sd : new ArrayList<>(simulated)) {
            Vec3 flat = sd.pos.multiply(1, 0, 1);
            String dest = sd.destination == null ? "done"
                    : (int) flat.distanceTo(sd.destination.multiply(1, 0, 1)) + "m";
            String line = String.format("• %-11s y=%-4.0f dst %-6s exf %-5dm  %3.0f%% battery  %-16s  [SIM]",
                    sd.state, sd.pos.y, dest, (int) flat.distanceTo(sd.exfil.multiply(1, 0, 1)),
                    100.0 * sd.charge / sd.capacity, load(sd.cargo != null, sd.payloadId, sd.mines));
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        }
        if (drones.isEmpty()) {
            return simulated.size();
        }
        List<DroneEntity> sorted = new ArrayList<>(drones);
        sorted.sort(Comparator.comparingDouble(d -> d.position().distanceToSqr(from)));
        src.sendSuccess(() -> Component.literal(sorted.size() + " drone(s):").withStyle(ChatFormatting.GOLD), false);
        for (DroneEntity drone : sorted.subList(0, Math.min(sorted.size(), 30))) {
            Vec3 flat = drone.position().multiply(1, 0, 1);
            String shownDest = drone.getDestination() == null ? "done"
                    : (int) flat.distanceTo(drone.getDestination().multiply(1, 0, 1)) + "m";
            DronePath path = drone.getPath();
            String dest = drone.isClassified() ? "[CLSFD]" : shownDest;
            String line = String.format(
                    "• %-11s y=%-4.0f dst %-6s exf %-5dm  %3.0f%% bat  %-8s spd%.2f thr%.2f tilt%2.0f° agl%-4.0f %s%s",
                    drone.getDroneState(), drone.getY(), dest,
                    (int) flat.distanceTo(drone.getExfil().multiply(1, 0, 1)),
                    drone.battery().percent(), load(drone.hasCargo(), drone.getPayloadId(), drone.getMines()),
                    drone.getDeltaMovement().horizontalDistance(),
                    drone.getAttitude().throttle(), Math.toDegrees(drone.getAttitude().tilt()),
                    drone.getY() - TerrainSampler.groundY(src.getLevel(), drone.getX(), drone.getZ(), drone.getY()),
                    path == null ? "no route" : path.waypoints().size() + "wp" + (path.partial() ? "+" : ""),
                    drone.leader() ? "  [lead]" : "");
            src.sendSuccess(() -> Component.literal(line).withStyle(
                    drone.getDroneState() == DroneState.DEPLETED ? ChatFormatting.RED : ChatFormatting.YELLOW), false);
        }
        return sorted.size();
    }

    /**
     * Scatters a spherical burst of bomblets from the player's eye position.
     */
    private static int spawnFrag(CommandSourceStack src, int count, double speed) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 origin = player.getEyePosition();
        FragmentationUtil.burst(player.serverLevel(), origin, count, speed);
        src.sendSuccess(() -> Component.literal("Spawned " + count + " bomblets"), true);
        return count;
    }

    /**
     * Releases a mustard gas cloud at the command's position: the same {@link GasCloud#spawn} call the chemical
     * warhead makes, without having to fly a missile at something.
     */
    private static int spawnGas(CommandSourceStack src, int radius) {
        Vec3 pos = src.getPosition();
        int cells = GasCloud.spawn(src.getLevel(), WFFluids.MUSTARD_GAS.get(), pos, radius,
                GasCloud.DEFAULT_MAX_CELLS, GasCloud.DEFAULT_DURATION);
        src.sendSuccess(() -> Component.literal("Released mustard gas at " + BlockPos.containing(pos)
                + ": " + cells + " cells"), true);
        return cells;
    }

    /**
     * Draws a blast's effect at the command's position without a warhead behind it: the same {@link
     * ExplosionCreator} call a missile makes, so the smoke, the debris and the water foam can be looked at without
     * flying something into the sea first.
     */
    private static int boom(CommandSourceStack src, String preset) {
        Vec3 pos = src.getPosition();
        ServerLevel level = src.getLevel();
        switch (preset) {
            case "small" -> ExplosionCreator.composeEffectSmall(level, pos.x, pos.y, pos.z);
            case "large" -> ExplosionCreator.composeEffectLarge(level, pos.x, pos.y, pos.z);
            default -> ExplosionCreator.composeEffectStandard(level, pos.x, pos.y, pos.z);
        }
        src.sendSuccess(() -> Component.literal("Blast effect (" + preset + ") at "
                + BlockPos.containing(pos)), true);
        return 1;
    }

    private static int setInterceptMode(CommandSourceStack src, MissileSimConfig.InterceptResolution mode) {
        MissileSimConfig.INTERCEPT_MODE = mode;
        src.sendSuccess(() -> Component.literal("Intercept mode set to " + mode), true);
        return 1;
    }

    /**
     * Launches a real interceptor from the player's eye in NEAREST mode (targeting mode 1: it auto-acquires the
     * closest non-friendly missile each tick).
     */
    private static int interceptNearest(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
        spawnInterceptor(player.serverLevel(), player, spawn, null);
        src.sendSuccess(() -> Component.literal("Launched interceptor (nearest hostile)."), true);
        return 1;
    }

    /** Launches an interceptor locked on a specific missile UUID (targeting mode 2). */
    private static int interceptUuid(CommandSourceStack src, UUID target) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
        if (level.getEntity(target) instanceof MissileEntity) {
            spawnInterceptor(level, player, spawn, target);
            src.sendSuccess(() -> Component.literal("Launched interceptor locked on " + target), true);
            return 1;
        }
        if (SimMissileRegistry.get(level).getById(target) != null) {
            SimMissileManager.launchInterceptor(level, spawn, target, MissileSimConfig.DEFAULT_INTERCEPT_CHANCE);
            src.sendSuccess(() -> Component.literal("Launched simulated interceptor at " + target), true);
            return 1;
        }
        src.sendFailure(Component.literal("No missile with UUID " + target));
        return 0;
    }

    /** Lists nearby missiles with their UUID (used to lock one), type, phase, speed, fuel and stealth flag. */
    private static int listMissiles(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        double r = 512.0;
        AABB box = player.getBoundingBox().inflate(r);
        List<MissileEntity> ms = level.getEntitiesOfClass(MissileEntity.class, box, MissileEntity::isAlive);
        if (ms.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No missiles within " + (int) r + " blocks."), false);
            return 0;
        }
        ms.sort(Comparator.comparingDouble(player::distanceToSqr));
        int total = ms.size();
        src.sendSuccess(() -> Component.literal(total + " missile(s) nearby (click a line to target it):")
                .withStyle(ChatFormatting.GOLD), false);
        int limit = Math.min(total, 30);
        for (int i = 0; i < limit; i++) {
            MissileEntity m = ms.get(i);
            int fuelPct = m.getFuelCapacity() > 0 ? Math.round(100.0f * m.getFuel() / m.getFuelCapacity()) : 0;
            int dist = (int) Math.sqrt(player.distanceToSqr(m));
            String tags = (m.isInterceptor() ? " [INT]" : "") + (m.isStealth() ? " [STEALTH]" : "")
                    + (m.getEvasion() > 0.0f ? " [EVA " + Math.round(m.getEvasion() * 100) + "%]" : "");
            String uuid = m.getUUID().toString();
            String line = String.format("• %s  %dm  %s  spd %.1f  fuel %d%%%s  %s",
                    m.getModelId().getPath(), dist, m.getPhase(), m.getCruiseSpeed(), fuelPct, tags, uuid);
            ChatFormatting colour = m.isStealth() ? ChatFormatting.LIGHT_PURPLE
                    : (m.isInterceptor() ? ChatFormatting.AQUA : ChatFormatting.YELLOW);
            Component comp = Component.literal(line).withStyle(s -> s
                    .withColor(colour)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/wflib intercept " + uuid)));
            src.sendSuccess(() -> comp, false);
        }
        return total;
    }

    /**
     * Lists every missile currently in off-world simulation for the player's dimension, nearest-to-target first,
     * with the honest fuel picture: powered range vs.
     */
    private static int simList(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<MissileData> sims = WFLibAPI.listSimMissiles(level);
        if (sims.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No missiles in off-world simulation."), false);
            return 0;
        }
        sims.sort(Comparator.comparingDouble(MissileData::horizontalDistance));
        int total = sims.size();
        src.sendSuccess(() -> Component.literal(total + " simulated missile(s), nearest target first "
                + "(click a line to track it):").withStyle(ChatFormatting.GOLD), false);
        int limit = Math.min(total, 40);
        for (int i = 0; i < limit; i++) {
            MissileData d = sims.get(i);
            boolean reach = d.canReach();
            String eta = reach ? String.format("ETA ~%.0fs", d.etaTicks() / 20.0) : "WON'T REACH";
            String line = String.format("• %s  %s  dist %dm  spd %.1f  fuel %d (range %dm)  %s  %s",
                    d.model(), d.phase(), (int) d.horizontalDistance(), d.speed(),
                    d.fuel(), (int) d.poweredRange(), eta, d.id());
            ChatFormatting colour = reach ? ChatFormatting.YELLOW : ChatFormatting.RED;
            Component comp = Component.literal(line).withStyle(s -> s
                    .withColor(colour)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/wflib track " + d.id())));
            src.sendSuccess(() -> comp, false);
        }
        return total;
    }

    private static int debugStatus(CommandSourceStack src) {
        String tracking = MissileDebug.latest() != null ? " (latest " + MissileDebug.latest() + ")" : "";
        src.sendSuccess(() -> Component.literal("Missile debug logging: "
                + (MissileDebug.enabled() ? "ON" : "OFF") + tracking), false);
        return 1;
    }

    private static int setDebug(CommandSourceStack src, boolean on) {
        MissileDebug.setEnabled(on);
        src.sendSuccess(() -> Component.literal("Missile debug logging " + (on ? "enabled" : "disabled")
                + ". New missiles are auto-tracked; watch the server log."), true);
        return 1;
    }

    private static int trackLatest(CommandSourceStack src) {
        UUID latest = MissileDebug.latest();
        if (latest == null) {
            src.sendFailure(Component.literal("No missile tracked yet. Enable debug then launch one."));
            return 0;
        }
        MissileDebug.track(latest);
        src.sendSuccess(() -> Component.literal("Tracking latest missile " + latest), true);
        return 1;
    }

    private static int trackUuid(CommandSourceStack src, UUID id) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        if (level.getEntity(id) instanceof MissileEntity m) {
            WFLibAPI.openTelemetry(m);
        } else if (SimMissileRegistry.get(level).getById(id) != null) {
            WFLibAPI.openTelemetry(id, level.getGameTime());
        } else {
            src.sendFailure(Component.literal("No missile with UUID " + id + " in this dimension."));
            return 0;
        }
        MissileDebug.track(id);
        String hint = MissileDebug.enabled() ? "" : " Enable output with /wflib debug on.";
        src.sendSuccess(() -> Component.literal("Tracking missile " + id + "." + hint), true);
        return 1;
    }

    /**
     * Re-tasks the player's nearest owned missile/drone (same control id, or same WarForge faction) to strike the
     * entity the player is looking at: e.g.
     */
    private static int retarget(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Entity aim = aimedEntity(player);
        if (aim == null) {
            src.sendFailure(Component.literal("Look directly at the entity you want re-tasked as the target."));
            return 0;
        }
        UUID mine = player.getUUID();
        UUID myTeam = com.wf.wflib.compat.WarforgeCompat.factionOfPlayer(mine);
        AABB box = player.getBoundingBox().inflate(512.0);
        List<MissileEntity> ms = level.getEntitiesOfClass(MissileEntity.class, box,
                m -> m.isAlive() && !m.isInterceptor()
                        && (mine.equals(m.getControlId()) || (myTeam != null && myTeam.equals(m.getTeamId()))));
        if (ms.isEmpty()) {
            src.sendFailure(Component.literal("No missile of yours nearby to re-task."));
            return 0;
        }
        ms.sort(Comparator.comparingDouble(player::distanceToSqr));
        MissileEntity m = ms.get(0);
        m.setDesignatedTarget(aim.getUUID());
        src.sendSuccess(() -> Component.literal("Re-tasked " + m.getModelId().getPath() + " onto "
                + aim.getName().getString()), true);
        return 1;
    }

    /**
     * Launches a swarm of {@code count} missiles toward the player's aim: one commander flying the mission and the
     * rest holding a wedge formation on it (see {@link SwarmManager}).
     */
    private static int spawnSwarm(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 target = swarmTarget(level, player);
        long swarmId = SwarmManager.newId(level);
        UUID team = com.wf.wflib.recon.ReconOwners.owningEntity(player);
        MissilePreset preset = MissilePresetRegistry.get(MissilePresetRegistry.rl("cruise"));
        Vec3 base = player.getEyePosition().add(player.getLookAngle().scale(3.0));
        for (int i = 0; i < count; i++) {
            MissileEntity m = preset.build(level, target);
            m.setControlId(player.getUUID());
            m.setTeamId(team);
            m.setSwarmId(swarmId);
            if (i == 0) {
                m.setCommander(true);
            }
            double side = (i % 2 == 0 ? 1.0 : -1.0) * ((double) (i + 1) / 2) * 2.0;
            Vec3 spawn = base.add(side, i * 0.4, 0.0);
            m.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
            level.addFreshEntity(m);
        }
        src.sendSuccess(() -> Component.literal("Launched a swarm of " + count + " (1 commander, "
                + (count - 1) + " in formation)."), true);
        return count;
    }

    private static Vec3 swarmTarget(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(512.0));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
    }

    private static Entity aimedEntity(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(256.0));
        AABB search = new AABB(eye, end).inflate(1.0);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, search,
                x -> x != player && x.isAlive() && x.isPickable() && !(x instanceof MissileEntity))) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(0.3).clip(eye, end);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(eye);
                if (d < bestSq) {
                    bestSq = d;
                    best = e;
                }
            }
        }
        return best;
    }

    private static void spawnInterceptor(ServerLevel level, ServerPlayer player, Vec3 spawn, UUID lock) {
        MissilePreset preset = MissilePresetRegistry.get(MissilePresetRegistry.rl("interceptor"));
        MissileEntity m = preset.build(level, spawn);
        m.setControlId(player.getUUID());
        m.setTeamId(com.wf.wflib.recon.ReconOwners.owningEntity(player));
        if (lock != null) {
            m.setInterceptLock(lock);
        }
        m.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
        level.addFreshEntity(m);
    }

    // --- MOD bus subscribers ---

    /** Inner class on the MOD bus for events that must be registered there. */
    @EventBusSubscriber(modid = WFLib.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
            // Every TicketController used for forceChunk must be registered here, or forceChunk throws.
            event.register(DetonationChunkGuard.CONTROLLER);
            event.register(com.wf.wflib.sim.MissileListenerRegistry.CHUNK_TICKET);
            event.register(com.wf.wflib.entity.EntityExplosionChunkLoading.CHUNK_TICKET);
        }
    }
}
