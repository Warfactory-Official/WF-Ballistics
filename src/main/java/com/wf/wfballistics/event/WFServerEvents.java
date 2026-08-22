package com.wf.wfballistics.event;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.api.MissileData;
import com.wf.wfballistics.api.WFBallisticsAPI;
import com.wf.wfballistics.chunk.DetonationChunkGuard; // used in ModBusEvents
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.debug.MissileDebug;
import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.colony.ColonyDebug;
import com.wf.wfballistics.colony.ColonyManager;
import com.wf.wfballistics.industry.IndustryClusters;
import com.wf.wfballistics.industry.IndustryDebug;
import java.util.Set;
import java.util.ArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import com.wf.wfballistics.drone.DroneLaunchQueue;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.drone.DroneTracker;
import com.wf.wfballistics.drone.DroneState;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.wf.wfballistics.drone.DroneDrafts;
import com.wf.wfballistics.drone.DroneMission;
import com.wf.wfballistics.drone.DroneProgram;
import com.wf.wfballistics.drone.DroneTask;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.block.entity.DronePadBlockEntity;
import com.wf.wfballistics.drone.CrateEntity;
import com.wf.wfballistics.api.WFTelemetryService;
import com.wf.wfballistics.api.WFTelemetry;
import com.wf.wfballistics.drone.DroneSelfTest;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.wf.wfballistics.drone.ai.DroneAiScheduler;
import com.wf.wfballistics.drone.ai.TerrainSampler;
import com.wf.wfballistics.drone.WorldThread;
import com.wf.wfballistics.drone.nav.DronePath;
import com.wf.wfballistics.drone.nav.PlannerStats;
import com.wf.wfballistics.exchange.Exchange;
import com.wf.wfballistics.exchange.ExchangeManager;
import com.wf.wfballistics.exchange.ExchangeMode;
import com.wf.wfballistics.exchange.ExchangeRegistry;
import com.wf.wfballistics.exchange.StationCode;
import com.wf.wfballistics.exchange.StationKind;
import com.wf.wfballistics.exchange.StationRecord;
import com.wf.wfballistics.exchange.StationRegistry;
import com.wf.wfballistics.exchange.StationRole;
import com.wf.wfballistics.build.Blueprint;
import com.wf.wfballistics.build.BlueprintFormat;
import com.wf.wfballistics.build.BlueprintLibrary;
import com.wf.wfballistics.build.BuildJobs;
import com.wf.wfballistics.build.ConstructionPlan;
import com.wf.wfballistics.build.SalvagePlan;
import com.wf.wfballistics.build.SiteRules;
import com.wf.wfballistics.compat.TerritoryVerdict;
import com.wf.wfballistics.build.WorkPlan;
import com.wf.wfballistics.work.WorkJob;
import com.wf.wfballistics.work.WorkRegistry;
import com.wf.wfballistics.drone.sim.SimDrone;
import com.wf.wfballistics.drone.sim.SimDroneManager;
import com.wf.wfballistics.drone.sim.SimDroneRegistry;
import com.wf.wfballistics.item.MissilePreset;
import com.wf.wfballistics.item.MissilePresetRegistry;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.sim.SimMissileManager;
import com.wf.wfballistics.sim.SimMissileRegistry;
import com.wf.wfballistics.swarm.SwarmManager;
import com.wf.wfballistics.util.FragmentationUtil;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side global driver for the missile simulation system.
 */
@EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WFServerEvents {
    private WFServerEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SimMissileManager.tick(level);
            // Decide offloads/onloads first, so a drone that changed form is planned in its new form
            // during the same tick.
            DroneLaunchQueue.tick(level);
            SimDroneManager.tick(level);
            DroneAiScheduler.tick(level);
            // After the drones have moved, so a zone is judged on where everyone actually is this tick.
            ExchangeManager.tick(level);
            // Expire lapsed work claims after the workers have had their tick, so a claim is only ever
            // taken back from a drone that had this tick to make progress on it.
            WorkRegistry.get(level).tick(level);
            // The build system's own housekeeping, kept out of the generic queue: whether the ground is
            // still ours, and holding the blocks being worked on loaded.
            com.wf.wfballistics.build.BuildManager.tick(level);
            // Event-driven and debounced: this only dispatches a scan when industry actually
            // changed and the world has since gone quiet.
            IndustryClusters.tick(level);
            ColonyManager.tick(level);
            tickScenarios(level);
        }
    }

    /**
     * Closes the swarm profiler's tick. On the server tick rather than the level tick so one game tick is
     * one sample however many dimensions are loaded.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SwarmBench.tick(event.getServer());
    }

    /**
     * The drone AI worker pool lives exactly as long as the server, so its threads can't leak across a
     * world reload.
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        DroneAiScheduler.startup();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        DroneAiScheduler.shutdown();
        IndustryClusters.clear();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfballistics")
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
                .then(blueprintCommand())
                .then(buildCommand())
                .then(salvageCommand())
                .then(jobCommand())
                .then(Commands.literal("colony")
                        .executes(ctx -> ColonyDebug.status(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> ColonyDebug.list(ctx.getSource())))
                        .then(Commands.literal("warbands")
                                .executes(ctx -> ColonyDebug.warbands(ctx.getSource())))
                        .then(Commands.literal("found")
                                .executes(ctx -> ColonyDebug.found(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> ColonyDebug.clear(ctx.getSource())))
                        .then(Commands.literal("fastforward")
                                .then(Commands.argument("rounds", IntegerArgumentType.integer(1, 100_000))
                                        .executes(ctx -> ColonyDebug.fastForward(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "rounds")))))
                        .then(Commands.literal("bugs")
                                .executes(ctx -> ColonyDebug.bugs(ctx.getSource())))
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
                                                        DoubleArgumentType.getDouble(ctx, "radius"))))))
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
                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(), true))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), "vee",
                                                        Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(), true))
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
                                                                Formation.DEFAULT_SPACING, CoordinationModels.DEFAULT.getPath(), true))
                                                        .then(Commands.argument("spacing",
                                                                        DoubleArgumentType.doubleArg(
                                                                                Formation.MIN_SPACING,
                                                                                Formation.MAX_SPACING))
                                                                .executes(ctx -> dispatchDrones(ctx.getSource(),
                                                                        Vec3Argument.getVec3(ctx, "destination"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "formation"),
                                                                        DoubleArgumentType.getDouble(ctx, "spacing"), CoordinationModels.DEFAULT.getPath(),
                                                                        true))
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
                                                                                true))))))))
                        .then(programCommand())));
    }

    // --- programs ---

    /**
     * The {@code program} subtree: build a queue of steps one command at a time, then fly it.
     *
     * <p>Each {@code add} appends to the invoking player's draft (see {@link DroneDrafts}) rather than
     * launching anything, which is what makes a multi-stop mission writable at all from a command line: a
     * single command taking an arbitrary number of waypoints is not a shape brigadier has.
     */
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

        // The steps that are nothing but a place to be. Same shape each, so they are built rather than typed
        // out four times.
        for (DroneTask.Kind kind : new DroneTask.Kind[]{DroneTask.Kind.MOVE_TO, DroneTask.Kind.DELIVER,
                DroneTask.Kind.COLLECT, DroneTask.Kind.STRIKE}) {
            program = program.then(Commands.literal(kind.id())
                    .then(Commands.argument("at", Vec3Argument.vec3())
                            .executes(ctx -> addStep(ctx.getSource(),
                                    kind.at(Vec3Argument.getVec3(ctx, "at"))))));
        }

        // Loiter carries two more numbers, and both have a default worth having: a wide circle, and "stay
        // until the battery says to leave".
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

        // "Hold" is a loiter with no circle to it. Spelled separately because that is how anyone asking for
        // it would say it, not because it is a different step.
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
                    "No program. Add steps with /wfballistics drone program <moveto|deliver|collect|strike|"
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

    /**
     * Fly the draft. The mission kind is read off the program rather than asked for: a program that delivers
     * needs a crate, one that strikes needs a warhead, and one that only watches needs neither.
     */
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
     * not write each other's; anything without a player behind it (a command block, the console) shares one
     * draft, which is the only sensible reading of "the server's program".
     */
    private static UUID draftOwner(CommandSourceStack src) {
        return src.getEntity() != null ? src.getEntity().getUUID() : SERVER_DRAFT;
    }

    private static final UUID SERVER_DRAFT = new UUID(0L, 0L);

    /**
     * Dispatches a delivery flight from the player's position. The crate is spawned pre-attached to the
     * leader; the mission is refused outright if the battery can't cover the outbound leg.
     */
    private static int dispatchDrones(CommandSourceStack src, Vec3 destination, int count, String formation,
                                      double spacing, String coordination,
                                      boolean withCrate) {
        ServerLevel level = src.getLevel();
        Vec3 origin = src.getPosition();

        DroneMission mission = new DroneMission();
        mission.destination = destination;
        mission.count = count;
        mission.formationId = Formations.parse(formation);
        mission.formationSpacing = Formation.clampSpacing(spacing);
        mission.coordinationId = CoordinationModels.parse(coordination);

        // An empty tag still means "carrying a crate": the drone holds cargo, crates are only spawned
        // when one is let go of.
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

    /**
     * Run the pure-logic checks and report them. Everything the drone AI decides is a function of a snapshot,
     * so the whole decision layer can be asserted here without flying anything.
     */
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
                // The navigation test. At the default 40 blocks up a drone simply flies over most terrain and
                // never has to think about it; down at 10 it has to follow the ground, climb ridges early and
                // route around anything it cannot climb. Watch the agl and rte columns of `drone list`.
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

    /**
     * Dispatch an armed flight. Each drone carries its own warhead and flies an attack run at the target,
     * releasing early enough that the bomb's fall carries it onto the aim point.
     */
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

    /**
     * Point a drone pad at a destination. The pad then flies that mission on every redstone rising edge,
     * carrying whatever is in its cargo slots.
     */
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
        if (payloadId != null) {
            return "armed:" + payloadId.getPath();
        }
        return hasCrate ? "carrying" : "empty";
    }

    /**
     * Turn off-world simulation on or off. Off keeps drones in the world for a whole mission, which is the
     * only way to watch the terrain following work when there is no player out on the route to hold them
     * real.
     */
    private static int droneSim(CommandSourceStack src, Boolean enabled) {
        if (enabled != null) {
            SimDroneManager.offloadEnabled = enabled;
        }
        boolean on = SimDroneManager.offloadEnabled;
        src.sendSuccess(() -> Component.literal("Drone off-world simulation is " + (on ? "on" : "off")
                + (on ? "" : " - drones stay real for their whole mission")), true);
        return on ? 1 : 0;
    }

    /**
     * Report where the planning actually runs. The off-thread split is the load-bearing property of the drone
     * AI, so it is worth being able to check rather than assume: this shows the thread the last terrain search
     * ran on, what it cost, and whether the assertions guarding the boundary are armed.
     */
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
     * one. Every station command is about <em>your</em> station, which is the only one whose position you are
     * allowed to know.
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
     * jobs are therefore judged as an outsider's on every claim.
     */
    @Nullable
    private static UUID factionOf(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        return player == null ? null : WarforgeCompat.factionOfPlayer(player.getUUID());
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
                            com.wf.wfballistics.build.BlueprintFormats.extensions())
                    + " files in " + BlueprintLibrary.FOLDER + " inside the world folder.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(names.size() + " blueprint(s):")
                .withStyle(ChatFormatting.GOLD), false);
        for (String name : names) {
            // Names only. Reading every file to show its dimensions would mean parsing megabytes to answer
            // "what have I got"; `blueprint <name>` is where the reading happens.
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

    /**
     * Put drones from the nearest pad onto a job.
     *
     * <p>The drones fly out as an ordinary squad, which is what gets them formation-keeping, staggered
     * launch, terrain following and battery aborts for nothing. What makes them builders rather than
     * couriers is the assignment they are given the moment they are airborne; from the flight model's point
     * of view the destination simply changes every time one of them finishes a block.
     */
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
        // Aimed at the site, not at a block: the pilot rewrites the destination on the first tick a drone is
        // in the air, and the only thing this has to do is be somewhere the battery check can price.
        mission.destination = Vec3.atCenterOf(job.centre());
        mission.payloadId = null;
        mission.program = DroneProgram.EMPTY;
        mission.mode = ExchangeMode.DIRECT;
        mission.recipientCode = null;
        // Carried on the mission rather than stamped onto the drones the dispatch hands back, because on a
        // staggered launch most of them do not exist yet. DroneLaunchQueue replays the same mission for each
        // one as it comes up, so every drone in the flight joins the same job.
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
     * than full UUIDs because the list prints prefixes and nobody is retyping thirty-six characters
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
        // Deliberately not checked against the directory: confirming whether a code exists would turn this
        // command into a way to test guesses. An allow-list entry for a station that never existed is inert.
        boolean changed = allow ? stations.allow(own, peer) : stations.revoke(own, peer);
        src.sendSuccess(() -> Component.literal(changed
                ? (allow ? "Now accepting from " + StationCode.pretty(peer)
                         : "No longer accepting from " + StationCode.pretty(peer))
                : "No change."), false);
        return changed ? 1 : 0;
    }

    /**
     * Send the nearest pad's cargo to another station by handshake. The command-line equivalent of setting
     * the pad screen to Handshake, typing a code and pressing Dispatch.
     */
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
        // A flight goes up one drone at a time, so a squad can legitimately be short-handed for a few
        // seconds. Saying so is the difference between "still launching" and "lost three of them".
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
                    100.0 * sd.charge / sd.capacity, load(sd.cargo != null, sd.payloadId));
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
            // The flight numbers on the end are the ones worth watching when something looks wrong: the
            // throttle says how hard it is working, the lean says which way it is being pushed, the AGL says
            // whether the terrain following is doing its job, and the route length says whether it has a plan
            // at all or is flying blind at the destination.
            DronePath path = drone.getPath();
            // A classified drone's destination stays out of the listing. An operator can read the world
            // directly if they must, but a shared admin screen should not be the easiest way to break an
            // exchange that the mechanic says has to be broken by following the drone.
            String dest = drone.isClassified() ? "[CLSFD]" : shownDest;
            String line = String.format(
                    "• %-11s y=%-4.0f dst %-6s exf %-5dm  %3.0f%% bat  %-8s spd%.2f thr%.2f tilt%2.0f° agl%-4.0f %s%s",
                    drone.getDroneState(), drone.getY(), dest,
                    (int) flat.distanceTo(drone.getExfil().multiply(1, 0, 1)),
                    drone.battery().percent(), load(drone.hasCargo(), drone.getPayloadId()),
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

    private static int setInterceptMode(CommandSourceStack src, MissileSimConfig.InterceptResolution mode) {
        MissileSimConfig.INTERCEPT_MODE = mode;
        src.sendSuccess(() -> Component.literal("Intercept mode set to " + mode), true);
        return 1;
    }

    /**
     * Launches a real interceptor from the player's eye in NEAREST mode (targeting mode 1: it auto-acquires
     * the closest non-friendly missile each tick).
     */
    private static int interceptNearest(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Vec3 spawn = player.getEyePosition().add(player.getLookAngle().scale(2.0));
        spawnInterceptor(player.serverLevel(), player, spawn, null);
        src.sendSuccess(() -> Component.literal("Launched interceptor (nearest hostile)."), true);
        return 1;
    }

    /**
     * Launches an interceptor locked on a specific missile UUID (targeting mode 2). Resolves against real
     * entities first, then the off-world simulation (a sim interceptor closes on the simulated track).
     */
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

    /**
     * Lists nearby missiles with their UUID (used to lock one), type, phase, speed, fuel and stealth flag.
     * Each line is click-to-target: clicking suggests {@code /wfballistics intercept <uuid>}.
     */
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
                            "/wfballistics intercept " + uuid)));
            src.sendSuccess(() -> comp, false);
        }
        return total;
    }

    /**
     * Lists every missile currently in off-world simulation for the player's dimension, nearest-to-target first,
     * with the honest fuel picture: powered range vs. distance and whether it will actually reach under power.
     * Each line is click-to-track for the debug logger.
     */
    private static int simList(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<MissileData> sims = WFBallisticsAPI.listSimMissiles(level);
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
                            "/wfballistics track " + d.id())));
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
            WFBallisticsAPI.openTelemetry(m);
        } else if (SimMissileRegistry.get(level).getById(id) != null) {
            WFBallisticsAPI.openTelemetry(id, level.getGameTime());
        } else {
            src.sendFailure(Component.literal("No missile with UUID " + id + " in this dimension."));
            return 0;
        }
        MissileDebug.track(id);
        String hint = MissileDebug.enabled() ? "" : " Enable output with /wfballistics debug on.";
        src.sendSuccess(() -> Component.literal("Tracking missile " + id + "." + hint), true);
        return 1;
    }

    /**
     * Re-tasks the player's nearest owned missile/drone (same control id, or same WarForge faction) to strike
     * the entity the player is looking at: e.g. redirecting a loitering munition mid-flight.
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
        UUID myTeam = com.wf.wfballistics.compat.WarforgeCompat.factionOfPlayer(mine);
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
     * Launches a swarm of {@code count} missiles toward the player's aim: one commander flying the mission and
     * the rest holding a wedge formation on it (see {@link SwarmManager}). If the commander is intercepted, the
     * nearest survivor takes over and the rest re-form on it.
     */
    private static int spawnSwarm(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Vec3 target = swarmTarget(level, player);
        long swarmId = SwarmManager.newId(level);
        UUID team = WarforgeCompat.factionOfPlayer(player.getUUID());
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
        m.setTeamId(com.wf.wfballistics.compat.WarforgeCompat.factionOfPlayer(player.getUUID()));
        if (lock != null) {
            m.setInterceptLock(lock);
        }
        m.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot(), 0.0f);
        level.addFreshEntity(m);
    }

    // --- MOD bus subscribers ---

    /**
     * Inner class on the MOD bus for events that must be registered there.
     * RegisterTicketControllersEvent fires on the MOD bus.
     */
    @EventBusSubscriber(modid = WFBallistics.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
            // Every TicketController used for forceChunk must be registered here, or forceChunk throws.
            event.register(DetonationChunkGuard.CONTROLLER);
            event.register(com.wf.wfballistics.sim.MissileListenerRegistry.CHUNK_TICKET);
            event.register(com.wf.wfballistics.entity.EntityExplosionChunkLoading.CHUNK_TICKET);
        }
    }
}
