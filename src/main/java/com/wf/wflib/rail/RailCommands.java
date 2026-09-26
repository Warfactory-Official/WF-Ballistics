package com.wf.wflib.rail;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wf.wflib.WFLib;
import com.wf.wflib.compat.WarforgeCompat;
import net.minecraft.server.level.ServerPlayer;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignPoint;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.DesignClass;
import com.wf.wflib.rail.align.LineColour;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import com.wf.wflib.rail.excavate.TunnelProfiles;
import com.wf.wflib.rail.excavate.ClaimRegistry;
import com.wf.wflib.rail.build.BoreTrain;
import com.wf.wflib.rail.build.RailWorks;
import com.wf.wflib.rail.build.TrackJob;
import com.wf.wflib.rail.demo.FivePoints;
import com.wf.wflib.rail.plan.Leg;
import com.wf.wflib.rail.plan.RailGraph;
import com.wf.wflib.rail.plan.Stations;
import com.wf.wflib.rail.supply.Bill;
import com.wf.wflib.rail.supply.Depot;
import com.wf.wflib.rail.supply.Stores;
import com.wf.wflib.rail.demo.FivePointsFixture;
import com.wf.wflib.rail.excavate.ExcavationService;
import com.wf.wflib.rail.excavate.LightingPolicy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /wfrail}: drive the excavator by hand, and read back what it cost. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class RailCommands {

    private RailCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // The planning layer is not gated on Immersive Railroading, so neither is the part of this
        // command that reads it: a route is worth surveying and arguing about before there is any track
        // to lay on it. Everything that drives the excavator is.
        var root = Commands.literal("wfrail")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("routes")
                        .executes(ctx -> routes(ctx.getSource())))
                .then(Commands.literal("report")
                        .then(Commands.literal("built")
                                .executes(ctx -> report(ctx.getSource(), true)))
                        .then(Commands.literal("gone")
                                .executes(ctx -> report(ctx.getSource(), false))))
                .then(Commands.literal("build")
                        .executes(ctx -> buildNamed(ctx, RailConfig.TUNNEL_PROFILE.get(), null, null))
                        .then(Commands.literal("size")
                                .then(Commands.argument("width", IntegerArgumentType.integer(1, 32))
                                        .then(Commands.argument("height",
                                                        IntegerArgumentType.integer(1, 32))
                                                .executes(ctx -> buildSize(ctx, null, null))
                                                .then(Commands.argument("floor",
                                                                IntegerArgumentType.integer(-64, 320))
                                                        .executes(ctx -> buildSize(ctx, floorOf(ctx), null))
                                                        .then(Commands.literal("lit").executes(
                                                                ctx -> buildSize(ctx, floorOf(ctx), true)))
                                                        .then(Commands.literal("dark").executes(
                                                                ctx -> buildSize(ctx, floorOf(ctx),
                                                                        false)))))))
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests(PROFILE_SUGGESTIONS)
                                .executes(ctx -> buildNamed(ctx, nameOf(ctx), null, null))
                                .then(Commands.argument("floor", IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> buildNamed(ctx, nameOf(ctx), floorOf(ctx), null))
                                        .then(Commands.literal("lit").executes(
                                                ctx -> buildNamed(ctx, nameOf(ctx), floorOf(ctx), true)))
                                        .then(Commands.literal("dark").executes(
                                                ctx -> buildNamed(ctx, nameOf(ctx), floorOf(ctx),
                                                        false))))))
                .then(Commands.literal("survey")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("from", Vec3Argument.vec3())
                                        .then(Commands.argument("to", Vec3Argument.vec3())
                                                .executes(ctx -> survey(ctx,
                                                        DesignClass.BRANCH))
                                                .then(Commands.argument("class",
                                                                StringArgumentType.word())
                                                        .suggests(CLASS_SUGGESTIONS)
                                                        .executes(ctx -> survey(ctx,
                                                                designClass(ctx))))))))
                .then(Commands.literal("extend")
                        .then(Commands.argument("to", Vec3Argument.vec3())
                                .executes(ctx -> extend(ctx))))
                .then(Commands.literal("drive")
                        .executes(ctx -> drive(ctx, RailConfig.TUNNEL_PROFILE.get(), null, null))
                        .then(Commands.literal("stop")
                                .executes(ctx -> stopWorks(ctx.getSource())))
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests(PROFILE_SUGGESTIONS)
                                .executes(ctx -> drive(ctx, nameOf(ctx), null, null))
                                .then(Commands.argument("floor", IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> drive(ctx, nameOf(ctx), floorOf(ctx), null))
                                        .then(Commands.argument("speed",
                                                        DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                                .executes(ctx -> drive(ctx, nameOf(ctx), floorOf(ctx),
                                                        DoubleArgumentType.getDouble(ctx, "speed")))))))
                .then(Commands.literal("track")
                        .executes(ctx -> track(ctx.getSource(), RailConfig.TUNNEL_PROFILE.get(), null))
                        .then(Commands.literal("clear")
                                .executes(ctx -> trackClear(ctx.getSource(),
                                        RailConfig.TUNNEL_PROFILE.get(), null))
                                .then(Commands.argument("floor",
                                                IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> trackClear(ctx.getSource(),
                                                RailConfig.TUNNEL_PROFILE.get(), floorOf(ctx)))))
                        .then(Commands.literal("check")
                                .executes(ctx -> trackCheck(ctx.getSource(), null))
                                .then(Commands.argument("floor",
                                                IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> trackCheck(ctx.getSource(), floorOf(ctx)))))
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests(PROFILE_SUGGESTIONS)
                                .executes(ctx -> track(ctx.getSource(), nameOf(ctx), null))
                                .then(Commands.argument("floor", IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> track(ctx.getSource(), nameOf(ctx),
                                                floorOf(ctx))))))
                .then(Commands.literal("stock")
                        .executes(ctx -> stockList(ctx.getSource()))
                        .then(Commands.argument("stock", StringArgumentType.greedyString())
                                .suggests(STOCK_SUGGESTIONS)
                                .executes(ctx -> stock(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "stock")))))
                .then(Commands.literal("junctions")
                        .executes(ctx -> junctions(ctx.getSource()))
                        .then(Commands.literal("build")
                                .executes(ctx -> buildJunctions(ctx.getSource(), null))
                                .then(Commands.argument("floor",
                                                IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> buildJunctions(ctx.getSource(),
                                                floorOf(ctx))))))
                .then(Commands.literal("dispatch")
                        .executes(ctx -> dispatch(ctx.getSource(), null))
                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                .executes(ctx -> dispatch(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "speed")))))
                .then(Commands.literal("station")
                        .then(Commands.literal("list")
                                .executes(ctx -> stations(ctx.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> stationAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(STATION_SUGGESTIONS)
                                        .executes(ctx -> stationRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("build")
                                .then(Commands.argument("from", StringArgumentType.string())
                                        .suggests(STATION_SUGGESTIONS)
                                        .then(Commands.argument("to", StringArgumentType.string())
                                                .suggests(STATION_SUGGESTIONS)
                                                .executes(ctx -> stationBuild(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to"), null))
                                                .then(Commands.argument("speed",
                                                                DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                                        .executes(ctx -> stationBuild(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "from"),
                                                                StringArgumentType.getString(ctx, "to"),
                                                                DoubleArgumentType.getDouble(ctx, "speed"))))))))
                .then(Commands.literal("roll")
                        .executes(ctx -> roll(ctx.getSource(), ROLL_LIMIT))
                        .then(Commands.argument("blocks",
                                        DoubleArgumentType.doubleArg(1.0, 20000.0))
                                .executes(ctx -> roll(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "blocks")))))
                .then(Commands.literal("demo")
                        .then(Commands.literal("survey")
                                .executes(ctx -> demoSurvey(ctx.getSource(),
                                        FivePoints.ORIGIN_X, FivePoints.ORIGIN_Z))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> demoSurvey(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))
                        .then(Commands.literal("build")
                                .executes(ctx -> demoBuild(ctx.getSource(),
                                        RailConfig.BORE_TRAIN_SPEED.get(), false, 1))
                                .then(Commands.literal("together")
                                        .executes(ctx -> demoBuild(ctx.getSource(),
                                                RailConfig.BORE_TRAIN_SPEED.get(), false, GANG))
                                        .then(Commands.argument("speed",
                                                        DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                                .executes(ctx -> demoBuild(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "speed"),
                                                        false, GANG))
                                                .then(Commands.argument("trains",
                                                                IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> demoBuild(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "speed"),
                                                                false,
                                                                IntegerArgumentType.getInteger(ctx, "trains"))))))
                                .then(Commands.argument("speed",
                                                DoubleArgumentType.doubleArg(0.5D, 64.0D))
                                        .executes(ctx -> demoBuild(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "speed"), false, 1))
                                        .then(Commands.literal("reverse")
                                                .executes(ctx -> demoBuild(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "speed"),
                                                        true, 1)))))
                        .then(Commands.literal("check")
                                .executes(ctx -> demoCheck(ctx.getSource())))
                        .then(Commands.literal("stock")
                                .executes(ctx -> demoStock(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> demoClear(ctx.getSource())))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("stop", StringArgumentType.greedyString())
                                        .suggests(STOP_SUGGESTIONS)
                                        .executes(ctx -> demoTp(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "stop"))))))
                .then(Commands.literal("section")
                        .executes(ctx -> section(ctx.getSource(), null, null))
                        .then(Commands.argument("chainage", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> section(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "chainage"), null))
                                .then(Commands.argument("floor",
                                                IntegerArgumentType.integer(-64, 320))
                                        .executes(ctx -> section(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "chainage"),
                                                floorOf(ctx))))))
                .then(Commands.literal("works")
                        .executes(ctx -> works(ctx.getSource())))
                .then(Commands.literal("profiles")
                        .executes(ctx -> profiles(ctx.getSource()))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reloadProfiles(ctx.getSource()))));
        if (!RailCompat.isActive()) {
            event.getDispatcher().register(root);
            return;
        }
        event.getDispatcher().register(root
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx.getSource()))
                        .then(Commands.literal("reset")
                                .executes(ctx -> resetStats(ctx.getSource())))
                        .then(Commands.literal("phases")
                                .executes(ctx -> phases(ctx.getSource()))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("loaded")
                        .then(Commands.argument("from", ColumnPosArgument.columnPos())
                                .then(Commands.argument("to", ColumnPosArgument.columnPos())
                                        .executes(ctx -> loaded(ctx.getSource(),
                                                ColumnPosArgument.getColumnPos(ctx, "from"),
                                                ColumnPosArgument.getColumnPos(ctx, "to"))))))
                .then(Commands.literal("bore")
                        .then(Commands.argument("from", Vec3Argument.vec3())
                                .then(Commands.argument("to", Vec3Argument.vec3())
                                        .then(withLighting(
                                                Commands.argument("radius",
                                                        DoubleArgumentType.doubleArg(0.5D, 32.0D)),
                                                RailCommands::bore)))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("from", Vec3Argument.vec3())
                                .then(withLighting(Commands.argument("to", Vec3Argument.vec3()),
                                        RailCommands::clear)))));
    }

    /** What a carve subcommand does, once its lighting policy is known. */
    @FunctionalInterface
    private interface Carve {
        int run(CommandContext<CommandSourceStack> ctx, LightingPolicy lighting);
    }

    /** Hang both the implicit- and explicit-policy forms off a carve's last argument. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withLighting(T last, Carve carve) {
        return last
                .executes(ctx -> carve.run(ctx, LightingPolicy.DEFERRED))
                .then(Commands.argument("lighting", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(LightingPolicy.values())
                                        .map(p -> p.name().toLowerCase(Locale.ROOT)),
                                builder))
                        .executes(ctx -> {
                            LightingPolicy policy = lighting(StringArgumentType.getString(ctx, "lighting"));
                            if (policy == null) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "lighting is one of deferred, keep"));
                                return 0;
                            }
                            return carve.run(ctx, policy);
                        }));
    }

    private static LightingPolicy lighting(String name) {
        for (LightingPolicy policy : LightingPolicy.values()) {
            if (policy.name().equalsIgnoreCase(name)) {
                return policy;
            }
        }
        return null;
    }

    private static int bore(CommandContext<CommandSourceStack> ctx, LightingPolicy lighting) {
        Vec3 from = Vec3Argument.getVec3(ctx, "from");
        Vec3 to = Vec3Argument.getVec3(ctx, "to");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        return submit(ctx.getSource(), CarveVolume.capsule(from, to, radius), lighting,
                "tunnel r=" + radius);
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, LightingPolicy lighting) {
        BlockPos from = BlockPos.containing(Vec3Argument.getVec3(ctx, "from"));
        BlockPos to = BlockPos.containing(Vec3Argument.getVec3(ctx, "to"));
        return submit(ctx.getSource(), CarveVolume.box(BoundingBox.fromCorners(from, to)), lighting, "cut");
    }

    private static int submit(CommandSourceStack source, CarveVolume volume, LightingPolicy lighting,
                              String what) {
        ServerLevel level = source.getLevel();
        int columns = ExcavationService.of(level).submit(level, volume, lighting);
        BoundingBox box = volume.bounds();
        int loaded = loadedColumns(level, box);
        source.sendSuccess(() -> Component.literal(
                "queued " + what + " over " + columns + " column(s), " + loaded + " loaded"
                        + " (lighting " + lighting.name().toLowerCase(Locale.ROOT) + ")"), false);
        return columns;
    }

    /** @return resident columns, which is how many will take the attended path. */
    private static int loadedColumns(ServerLevel level, BoundingBox box) {
        int loaded = 0;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (ClaimRegistry.isLoaded(level, new ChunkPos(cx, cz))) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** Which of a region's columns are resident, without loading any of them. */
    private static int loaded(CommandSourceStack source, ColumnPos from, ColumnPos to) {
        ServerLevel level = source.getLevel();
        // Block coordinates, like /forceload takes, because a chunk coordinate is not what anyone has to hand.
        BoundingBox box = BoundingBox.fromCorners(
                new BlockPos(from.x(), 0, from.z()), new BlockPos(to.x(), 0, to.z()));
        int columns = ((box.maxX() >> 4) - (box.minX() >> 4) + 1) * ((box.maxZ() >> 4) - (box.minZ() >> 4) + 1);
        int loaded = loadedColumns(level, box);
        source.sendSuccess(() -> Component.literal(loaded + " of " + columns + " column(s) loaded"), false);
        return loaded;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ExcavationService service = ExcavationService.of(level);
        source.sendSuccess(() -> Component.literal(String.format(
                "%s in %s: %d queued, %d in flight, %d attended, off-thread %s%s",
                RailCompat.isForced() ? "rail forced on" : "rail active",
                level.dimension().location(),
                service.pending(), service.inFlight(), service.attended(),
                RailConfig.EXCAVATION_ENABLED.get() ? "on" : "off",
                RailCompat.trackGraphAvailable() ? ", track graph available" : "")), false);
        return 1;
    }

    /**
     * Every route this world holds, whoever owns it.
     *
     * <p>The server's own view, unfiltered, which is the thing no client can show: the map only ever
     * receives what its player is entitled to see, so an operator wondering why a faction's railway is
     * missing has nowhere else to look.</p>
     */
    private static int routes(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var store = AlignmentStore.of(level);
        var all = store.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no routes in "
                    + level.dimension().location()), false);
            return 0;
        }
        for (Alignment alignment : all) {
            double length = alignment.compile().centreline().length();
            String owner = WarforgeCompat.factionName(alignment.ownerFaction());
            String line = String.format(Locale.ROOT,
                    "%s  %s  %s  %.0f blocks  %.0f%% built  %d point(s)  rev %d  owner %s",
                    alignment.id().toString().substring(0, 8),
                    alignment.name().isEmpty() ? "unnamed" : alignment.name(),
                    alignment.status().lowerName(), length,
                    alignment.built().fractionOf(length) * 100.0, alignment.points().size(),
                    alignment.revision().number(), owner == null ? "none" : owner);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    /**
     * Report track laid, or gone, on whichever route runs under this position.
     *
     * <p>The same call a track layer makes as it works, addressed by where you are standing rather than
     * by how far along a route you are, which is the only thing a machine knows. Run from a command it
     * is the builder's counterpart to the surveyor's menu: {@code /execute at @e[...] run wfrail report
     * built} is how a machine would drive it.</p>
     *
     * <p>Unlike the block-place path this considers every route in the world rather than the caller's
     * own faction's, because an operator correcting the record is acting on the world, not on their
     * own railway.</p>
     */
    private static int report(CommandSourceStack source, boolean laid) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        java.util.UUID route = com.wf.wflib.rail.align.AlignmentProgress.report(
                level, null, at.x, at.z, TrackReporter.MIN_TOLERANCE, laid);
        if (route == null) {
            source.sendFailure(Component.literal("no route within reach of "
                    + String.format(Locale.ROOT, "%.0f, %.0f", at.x, at.z)));
            return 0;
        }
        Alignment alignment = AlignmentStore.of(level).get(route);
        double length = alignment.compile().centreline().length();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s is now %s, %.0f%% built (%.0f of %.0f blocks)",
                alignment.name().isEmpty() ? "unnamed route" : alignment.name(),
                alignment.status().lowerName(), alignment.built().fractionOf(length) * 100.0,
                alignment.built().builtLength(), length)), false);
        return 1;
    }

    /** How far from a route you may stand and still mean that one. */
    private static final double BUILD_REACH = 64.0;

    /** Torch spacing for the ad-hoc box section, which has no drawing of its own to say. */
    private static final int BOX_TORCH_SPACING = 8;

    private static final SuggestionProvider<CommandSourceStack> PROFILE_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(TunnelProfiles.names(), builder);

    private static int floorOf(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "floor");
    }

    private static String nameOf(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "profile");
    }

    /** {@code /wfrail build size <w> <h>}: a plain box, for when no drawn section is wanted. */
    private static int buildSize(CommandContext<CommandSourceStack> ctx, Integer floor, Boolean lit) {
        boolean lighting = lit != null ? lit : RailConfig.TUNNEL_LIGHTING.get();
        String lining = RailConfig.TUNNEL_LINING.get();
        TunnelProfile profile = TunnelProfile.box(
                IntegerArgumentType.getInteger(ctx, "width"),
                IntegerArgumentType.getInteger(ctx, "height"),
                lining, lighting ? BOX_TORCH_SPACING : 0);
        return build(ctx.getSource(), profile, lining, floor, lighting);
    }

    /** {@code /wfrail build <profile>}: one of the drawn sections. */
    private static int buildNamed(CommandContext<CommandSourceStack> ctx, String name, Integer floor,
                                  Boolean lit) {
        TunnelProfile profile = TunnelProfiles.get(name);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("no tunnel section called '" + name
                    + "'; /wfrail profiles lists them, and they live in "
                    + TunnelProfiles.directory()));
            return 0;
        }
        boolean lighting = lit != null ? lit : RailConfig.TUNNEL_LIGHTING.get();
        return build(ctx.getSource(), profile, profile.liningOr(RailConfig.TUNNEL_LINING.get()), floor,
                lighting);
    }

    /**
     * Dig and line the route you are standing on.
     *
     * <p>The autonomous half of the survey: the line was drawn on a map, and this is the one instruction
     * that turns it into a tunnel. The floor defaults to where you are standing, because a route carries
     * no vertical profile yet.</p>
     */
    private static int build(CommandSourceStack source, TunnelProfile profile, String lining,
                             Integer floor, boolean lit) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        var hit = com.wf.wflib.rail.align.AlignmentProgress.nearest(level, null, at.x, at.z, BUILD_REACH);
        if (hit == null) {
            source.sendFailure(Component.literal("no surveyed route within "
                    + (int) BUILD_REACH + " blocks; survey one on the map first"));
            return 0;
        }
        Alignment route = hit.alignment();
        int floorY = floor != null ? floor : (int) Math.floor(at.y);

        var submitted = TunnelBuilder.build(level, route, profile, lining, floorY, lit,
                LightingPolicy.DEFERRED, diggerFor(source, route));
        if (submitted.refused()) {
            source.sendFailure(Component.literal("that route crosses ground you may not build on,"
                    + " so none of it was dug:"));
            for (String reason : submitted.territory().reasons(4)) {
                source.sendFailure(Component.literal("  " + reason));
            }
            return 0;
        }
        if (submitted.stretches() == 0) {
            source.sendFailure(Component.literal("that route has no length to build"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "boring %s: %.0f blocks, %s section (%dx%d) at y=%d, lined with %s, %s"
                        + " (%d stretch(es), %d column(s))",
                route.name().isEmpty() ? "unnamed route" : route.name(), submitted.length(),
                submitted.profile(), profile.boreWidth(), profile.boreHeight(), floorY, lining,
                submitted.torches() > 0 ? submitted.torches() + " torches" : "unlit",
                submitted.stretches(), submitted.columns())), true);
        return submitted.columns();
    }

    private static final SuggestionProvider<CommandSourceStack> STOCK_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    RailCompat.trackGraphAvailable()
                            ? com.wf.wflib.rail.build.ir.IrStock.names() : List.of(), builder);

    /**
     * Put a piece of Immersive Railroading stock where you are standing.
     *
     * <p>The counterpart of {@code /wfrail drive}: a machine that claims to have built a railway is
     * only believed once something has run down it, and stock is normally placed by clicking the track
     * with the item, which no automated check can do.</p>
     */
    private static int stock(CommandSourceStack source, String defId) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there is no stock to place"));
            return 0;
        }
        Vec3 at = source.getPosition();
        float yaw = source.getEntity() != null ? source.getEntity().getYRot() : 0.0f;
        var placed = com.wf.wflib.rail.build.ir.IrStock.spawn(source.getLevel(), at, yaw, defId);
        if (placed.name() == null) {
            source.sendFailure(Component.literal(placed.problem()
                    + "; /wfrail stock lists what this install has"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "placed %s at %.0f, %.0f, %.0f on %.3fm gauge track", placed.name(), at.x, at.y, at.z,
                placed.gauge())), true);
        return 1;
    }

    private static int stockList(CommandSourceStack source) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed"));
            return 0;
        }
        List<String> names = com.wf.wflib.rail.build.ir.IrStock.names();
        source.sendSuccess(() -> Component.literal(names.size() + " piece(s) of stock: "
                + String.join(", ", names.subList(0, Math.min(names.size(), 40)))), false);
        return names.size();
    }

    private static final SuggestionProvider<CommandSourceStack> CLASS_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    Arrays.stream(DesignClass.values()).map(c -> c.name().toLowerCase(Locale.ROOT)),
                    builder);

    private static DesignClass designClass(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "class");
        for (DesignClass value : DesignClass.values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return DesignClass.BRANCH;
    }

    /**
     * Survey a straight line between two points.
     *
     * <p>The map editor is where a route is normally drawn, and it should be: a railway is argued over
     * on a map by people pointing at it. This is the other half of the same thing - a route laid out
     * from coordinates someone already has, by a command, which a machine or a script can also drive.
     * It creates the same faction-owned route the editor does, at the same revision 1.</p>
     */
    private static int survey(CommandContext<CommandSourceStack> ctx, DesignClass designClass) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("a route belongs to whoever surveys it,"
                    + " so this has to be run by a player"));
            return 0;
        }
        Vec3 from = Vec3Argument.getVec3(ctx, "from");
        Vec3 to = Vec3Argument.getVec3(ctx, "to");
        double radius = designClass.defaultRadius();
        List<AlignPoint> points = List.of(new AlignPoint(from.x, from.z, radius),
                new AlignPoint(to.x, to.z, radius));
        String name = StringArgumentType.getString(ctx, "name");
        UUID id = UUID.randomUUID();
        var outcome = com.wf.wflib.rail.align.AlignmentEdits.save(player, source.getLevel(), id, name,
                LineColour.AMBER.rgb(), designClass, points, 0);
        if (outcome != com.wf.wflib.rail.align.AlignmentEdits.SaveOutcome.SAVED) {
            source.sendFailure(Component.literal("that route was not saved: "
                    + outcome.name().toLowerCase(Locale.ROOT)));
            return 0;
        }
        Alignment route = AlignmentStore.of(source.getLevel()).get(id);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "surveyed %s: %.0f blocks, %s class", name,
                route.compile().centreline().length(),
                designClass.name().toLowerCase(Locale.ROOT))), true);
        return 1;
    }

    /** Add another point of intersection to the end of the route you are standing on. */
    private static int extend(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("this has to be run by a player"));
            return 0;
        }
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        Vec3 to = Vec3Argument.getVec3(ctx, "to");
        List<AlignPoint> points = new java.util.ArrayList<>(route.points());
        points.add(new AlignPoint(to.x, to.z, route.designClass().defaultRadius()));
        var outcome = com.wf.wflib.rail.align.AlignmentEdits.save(player, source.getLevel(), route.id(),
                route.name(), route.coreColour(), route.designClass(), points,
                route.revision().number());
        if (outcome != com.wf.wflib.rail.align.AlignmentEdits.SaveOutcome.SAVED) {
            source.sendFailure(Component.literal("that route was not changed: "
                    + outcome.name().toLowerCase(Locale.ROOT)));
            return 0;
        }
        Alignment next = AlignmentStore.of(source.getLevel()).get(route.id());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s is now %.0f blocks over %d point(s)", next.name(),
                next.compile().centreline().length(), next.points().size())), true);
        return 1;
    }

    /** Which faction a machine on this route digs for. */
    private static UUID diggerFor(CommandSourceStack source, Alignment route) {
        // The route's own faction, not whoever typed the command: a tunnel belongs to the railway, and
        // an operator running this for someone must not launder their territory.
        if (route.ownerFaction() != null) {
            return route.ownerFaction();
        }
        return source.getEntity() instanceof ServerPlayer player
                ? WarforgeCompat.factionOfPlayer(player.getUUID()) : null;
    }

    /** The route under the caller, or null with the failure already reported. */
    private static Alignment routeAt(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        var hit = com.wf.wflib.rail.align.AlignmentProgress.nearest(source.getLevel(), null, at.x, at.z,
                BUILD_REACH);
        if (hit == null) {
            source.sendFailure(Component.literal("no surveyed route within "
                    + (int) BUILD_REACH + " blocks; survey one on the map first"));
            return null;
        }
        return hit.alignment();
    }

    private static TunnelProfile sectionNamed(CommandSourceStack source, String name) {
        TunnelProfile profile = TunnelProfiles.get(name);
        if (profile == null) {
            source.sendFailure(Component.literal("no tunnel section called '" + name
                    + "'; /wfrail profiles lists them, and they live in "
                    + TunnelProfiles.directory()));
        }
        return profile;
    }

    /**
     * Put a bore train on the route you are standing on and set it going.
     *
     * <p>The difference from {@code build} is not what gets made but whether you can watch it happen:
     * the same tunnel, cut a few blocks at a time by a machine with the track gang behind it, at a pace
     * you can walk alongside.</p>
     */
    private static int drive(CommandContext<CommandSourceStack> ctx, String profileName, Integer floor,
                             Double speed) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        TunnelProfile profile = sectionNamed(source, profileName);
        if (profile == null) {
            return 0;
        }
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        if (RailWorks.on(level, route.id()) != null) {
            source.sendFailure(Component.literal("a machine is already working that route;"
                    + " /wfrail drive stop calls it off"));
            return 0;
        }
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        String lining = profile.liningOr(RailConfig.TUNNEL_LINING.get());
        BoreTrain.Launch launch = BoreTrain.launch(level, route, profile, lining, floorY,
                RailConfig.TUNNEL_LIGHTING.get(),
                speed != null ? speed : RailConfig.BORE_TRAIN_SPEED.get(),
                RailConfig.TRACK_BOOST_SPACING.get(), diggerFor(source, route));
        if (launch.refused()) {
            source.sendFailure(Component.literal("that route crosses ground you may not build on,"
                    + " so the machine was not started:"));
            for (String reason : launch.territory().reasons(4)) {
                source.sendFailure(Component.literal("  " + reason));
            }
            return 0;
        }
        if (launch.train() == null) {
            source.sendFailure(Component.literal(launch.problem()));
            return 0;
        }
        RailWorks.start(level, launch.train());
        BoreTrain train = launch.train();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "bore train away on %s: %.0f blocks of %s section (%dx%d) at y=%d, lined with %s,"
                        + " track behind it at %.0f blocks/s",
                train.routeName(), train.length(), train.profileName(), profile.boreWidth(),
                profile.boreHeight(), floorY, lining,
                speed != null ? speed : RailConfig.BORE_TRAIN_SPEED.get())), true);
        return 1;
    }

    /**
     * Send a work train out from a depot, paying its way.
     *
     * <p>The stand-in for a station's construction mode, and deliberately only that: the station this
     * belongs on is a multiblock with a controller and an inventory, and everything below the depot -
     * the bill, the load, the trip back - is written against a plain container so that the controller
     * only has to hand one over.</p>
     */
    private static int dispatch(CommandSourceStack source, Double speed) {
        ServerLevel level = source.getLevel();
        TunnelProfile profile = sectionNamed(source, RailConfig.TUNNEL_PROFILE.get());
        Alignment route = profile == null ? null : routeAt(source);
        if (route == null) {
            return 0;
        }
        if (RailWorks.on(level, route.id()) != null) {
            source.sendFailure(Component.literal("a machine is already working that route;"
                    + " /wfrail drive stop calls it off"));
            return 0;
        }
        var centre = route.compile().centreline();
        var start = centre.at(0.0);
        int floorY = (int) Math.floor(source.getPosition().y);
        Depot depot = depotNear(level, start.x(), floorY, start.z());
        if (depot == null) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "no depot: put a chest within %d blocks of %.0f, %d, %.0f, where %s starts, and"
                            + " stock it with what the line is made of",
                    DEPOT_REACH, start.x(), floorY, start.z(), route.name())));
            return 0;
        }
        String lining = profile.liningOr(RailConfig.TUNNEL_LINING.get());
        Bill bill = Bill.of(profile, lining, RailConfig.TRACK_MATERIAL.get());
        // What is left, not what the route is. A line that ran out of bricks at 58 blocks is dispatched
        // again to finish the other 92, and charging it for the first 58 a second time is how a railway
        // becomes impossible to pay for. /wfrail report gone clears the record if it is wrong.
        Leg whole = Leg.of(route);
        double owed = Math.max(0.0, whole.length() - whole.builtAlong(route.built()));
        Bill.Estimate cost = Bill.forLength(profile, owed);
        Stores store = new Stores(List.of(depot.container(level)));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s is %.0f blocks%s and will take about %d %s, %d %s and %d %s;"
                        + " the depot has %d, %d and %d",
                route.name(), centre.length(),
                owed < centre.length() - 1.0
                        ? String.format(Locale.ROOT, " with %.0f already built, so %.0f to go",
                                centre.length() - owed, owed)
                        : "",
                cost.lining(), name(bill.lining()), cost.track(),
                name(bill.track()), cost.torch(), name(bill.torch()), store.count(bill.lining()),
                store.count(bill.track()), store.count(bill.torch()))), false);
        if (owed <= 0.0) {
            source.sendSuccess(() -> Component.literal("that route is already built"), false);
            return 1;
        }
        if (store.count(bill.lining()) <= 0 && store.count(bill.track()) <= 0) {
            source.sendFailure(Component.literal("the depot holds none of either, so the train would"
                    + " come straight back; load it first"));
            return 0;
        }
        BoreTrain.Launch launch = BoreTrain.launch(level, route, profile, lining, floorY,
                RailConfig.TUNNEL_LIGHTING.get(),
                speed != null ? speed : RailConfig.BORE_TRAIN_SPEED.get(),
                RailConfig.TRACK_BOOST_SPACING.get(), diggerFor(source, route), depot,
                line -> source.sendSystemMessage(Component.literal(line)));
        if (launch.refused()) {
            source.sendFailure(Component.literal("that route crosses ground you may not build on,"
                    + " so the train was not dispatched:"));
            for (String reason : launch.territory().reasons(4)) {
                source.sendFailure(Component.literal("  " + reason));
            }
            return 0;
        }
        if (launch.train() == null) {
            source.sendFailure(Component.literal(launch.problem()));
            return 0;
        }
        // Loaded before it sets off, out of the depot it is stood next to, exactly as its resupply
        // trips will be. A work train carries what it builds with and nothing is put in it for free.
        RailWorks.start(level, launch.train());
        source.sendSuccess(() -> Component.literal("work train dispatched on " + route.name()
                + " from the depot at " + depot.at().toShortString()
                + "; it will run back for more when it runs out"), true);
        if (owed < centre.length() - 1.0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "it will run out to the railhead at %.0f and carry on from there", 
                    centre.length() - owed)), false);
        }
        return 1;
    }

    private static String name(net.minecraft.world.item.Item item) {
        return item.getDescription().getString();
    }

    // ------------------------------------------------------------------ stations, and building between them

    /** How far from a station the junction it stands at may be. A platform is not a survey point. */
    private static final int STATION_REACH = 32;

    private static final SuggestionProvider<CommandSourceStack> STATION_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    Stations.of(ctx.getSource().getLevel()).names(), builder);

    /**
     * Name the place you are standing as a station.
     *
     * <p>A station is a name and a position, and what makes it useful is what is already near it: the
     * junction {@link RailGraph} finds within reach of it, which is where trains can be sent, and the
     * inventory {@link #depotNear} finds within reach of it, which is what pays for them. Registering
     * one checks both and says which is missing, because a station with no junction is a signpost and
     * a station with no chest cannot dispatch anything.</p>
     */
    private static int stationAdd(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        Stations stations = Stations.of(level);
        if (!stations.put(name, at)) {
            source.sendFailure(Component.literal("that name will not do, or this world already has "
                    + Stations.MAX + " stations"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("station " + name.trim() + " at "
                + at.toShortString()), true);
        RailGraph graph = RailGraph.of(AlignmentStore.of(level).all());
        RailGraph.Place place = graph.nearest(at.getX(), at.getZ(), STATION_REACH);
        if (place == null) {
            source.sendSuccess(() -> Component.literal("  no surveyed junction within " + STATION_REACH
                    + " blocks, so nothing can be dispatched to it yet"), false);
        } else {
            source.sendSuccess(() -> Component.literal("  on " + place.describe()), false);
        }
        if (depotNear(level, at.getX(), at.getY(), at.getZ()) == null) {
            source.sendSuccess(() -> Component.literal("  no depot: put a chest within " + DEPOT_REACH
                    + " blocks and stock it, and trains can be dispatched from here"), false);
        }
        return 1;
    }

    private static int stationRemove(CommandSourceStack source, String name) {
        if (!Stations.of(source.getLevel()).remove(name)) {
            source.sendFailure(Component.literal("no station called '" + name + "'"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("station " + name + " removed"), true);
        return 1;
    }

    private static int stations(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Stations stations = Stations.of(level);
        if (stations.size() == 0) {
            source.sendSuccess(() -> Component.literal(
                    "no stations here; /wfrail station add <name> names the place you are standing"),
                    false);
            return 0;
        }
        RailGraph graph = RailGraph.of(AlignmentStore.of(level).all());
        for (Stations.Station station : stations.all()) {
            RailGraph.Place place = graph.nearest(station.at().getX(), station.at().getZ(),
                    STATION_REACH);
            boolean depot = depotNear(level, station.at().getX(), station.at().getY(),
                    station.at().getZ()) != null;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s at %s: %s%s",
                    station.name(), station.at().toShortString(),
                    place == null ? "no line" : place.describe(),
                    depot ? ", depot" : ", no depot")), false);
        }
        return stations.size();
    }

    /**
     * Build the railway from one station to another.
     *
     * <p>The user's whole story in one command: a place that holds materials, a network drawn on the
     * map, and a train that works out its own way over it. The path is found over the survey rather
     * than over the ground, so the line to be built and the line already built are one route with a
     * railhead somewhere in the middle of it, which is what lets a train reach a worksite three
     * junctions out on rails it laid last week.</p>
     */
    private static int stationBuild(CommandSourceStack source, String fromName, String toName,
                                    Double speed) {
        ServerLevel level = source.getLevel();
        TunnelProfile profile = sectionNamed(source, RailConfig.TUNNEL_PROFILE.get());
        if (profile == null) {
            return 0;
        }
        Stations stations = Stations.of(level);
        Stations.Station from = stations.get(fromName);
        Stations.Station to = stations.get(toName);
        if (from == null || to == null) {
            source.sendFailure(Component.literal("no station called \'"
                    + (from == null ? fromName : toName) + "\'; /wfrail station list shows them"));
            return 0;
        }
        var routes = AlignmentStore.of(level).all();
        RailGraph graph = RailGraph.of(routes);
        RailGraph.Place a = graph.nearest(from.at().getX(), from.at().getZ(), STATION_REACH);
        RailGraph.Place b = graph.nearest(to.at().getX(), to.at().getZ(), STATION_REACH);
        if (a == null || b == null) {
            source.sendFailure(Component.literal("no surveyed junction within " + STATION_REACH
                    + " blocks of " + (a == null ? from.name() : to.name())
                    + "; a station has to stand where a route ends or where two of them meet"));
            return 0;
        }
        if (a.id() == b.id()) {
            source.sendFailure(Component.literal(from.name() + " and " + to.name()
                    + " stand at the same junction, so there is nothing between them to build"));
            return 0;
        }
        RailGraph.Journey journey = graph.between(a, b);
        if (journey.isEmpty()) {
            source.sendFailure(Component.literal("nothing surveyed joins " + from.name() + " to "
                    + to.name() + "; two lines that cross are not a way through, only a switch or a"
                    + " joint is"));
            return 0;
        }
        // One faction's railway. The ground rule is enforced per chunk as the face reaches it, but a
        // journey that runs over somebody else's line would be this faction's machine laying their
        // track for them and would stop dead at their first claim; saying so here is the honest answer.
        UUID owner = null;
        boolean shared = false;
        for (Leg leg : journey.legs()) {
            Alignment route = routeIn(routes, leg.route());
            UUID theirs = route == null ? null : route.ownerFaction();
            if (leg == journey.legs().get(0)) {
                owner = theirs;
            } else if (owner == null ? theirs != null : !owner.equals(theirs)) {
                shared = true;
            }
        }
        if (shared) {
            source.sendFailure(Component.literal("that way runs over another faction's line;"
                    + " a work train only builds its own railway"));
            return 0;
        }
        int floorY = from.at().getY();
        Depot depot = depotNear(level, from.at().getX(), floorY, from.at().getZ());
        if (depot == null) {
            source.sendFailure(Component.literal("no depot at " + from.name()
                    + ": put a chest within " + DEPOT_REACH + " blocks of " + from.at().toShortString()
                    + " and stock it with what the line is made of"));
            return 0;
        }
        double owed = owed(routes, journey);
        String lining = profile.liningOr(RailConfig.TUNNEL_LINING.get());
        Bill bill = Bill.of(profile, lining, RailConfig.TRACK_MATERIAL.get());
        Bill.Estimate cost = Bill.forLength(profile, owed);
        Stores store = new Stores(List.of(depot.container(level)));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s to %s: %.0f blocks over %d leg(s) - %s", from.name(), to.name(),
                journey.length(), journey.legs().size(), journey.describe())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%.0f blocks still to build, about %d %s, %d %s and %d %s;"
                        + " the depot has %d, %d and %d",
                owed, cost.lining(), name(bill.lining()), cost.track(), name(bill.track()),
                cost.torch(), name(bill.torch()), store.count(bill.lining()),
                store.count(bill.track()), store.count(bill.torch()))), false);
        if (owed <= 0.0) {
            source.sendSuccess(() -> Component.literal("that railway is already built"), false);
            return 1;
        }
        if (store.count(bill.lining()) <= 0 && store.count(bill.track()) <= 0) {
            source.sendFailure(Component.literal("the depot holds none of either, so the train would"
                    + " come straight back; load it first"));
            return 0;
        }
        BoreTrain.Launch launch = BoreTrain.launch(level, journey.legs(), profile, lining, floorY,
                RailConfig.TUNNEL_LIGHTING.get(),
                speed != null ? speed : RailConfig.BORE_TRAIN_SPEED.get(),
                RailConfig.TRACK_BOOST_SPACING.get(), diggerFor(source, routes, journey), depot,
                line -> source.sendSystemMessage(Component.literal(line)));
        if (launch.refused()) {
            source.sendFailure(Component.literal("that journey crosses ground you may not build on,"
                    + " so the train was not dispatched:"));
            for (String reason : launch.territory().reasons(4)) {
                source.sendFailure(Component.literal("  " + reason));
            }
            return 0;
        }
        if (launch.train() == null) {
            source.sendFailure(Component.literal(launch.problem()));
            return 0;
        }
        if (!RailWorks.start(level, launch.train())) {
            source.sendFailure(Component.literal("a machine is already working one of those lines;"
                    + " /wfrail drive stop calls it off"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("work train dispatched from " + from.name()
                + " to " + to.name() + ", out of the depot at " + depot.at().toShortString()
                + "; it will run back here for more when it runs out"), true);
        return 1;
    }

    /** Blocks of the journey with no track on them yet, which is what the bill is for. */
    private static double owed(java.util.Collection<Alignment> routes, RailGraph.Journey journey) {
        double total = 0.0;
        for (Leg leg : journey.legs()) {
            Alignment route = routeIn(routes, leg.route());
            total += Math.max(0.0, leg.length()
                    - (route == null ? 0.0 : leg.builtAlong(route.built())));
        }
        return total;
    }

    private static Alignment routeIn(java.util.Collection<Alignment> routes, UUID id) {
        for (Alignment route : routes) {
            if (route.id().equals(id)) {
                return route;
            }
        }
        return null;
    }

    /** Which faction a journey digs for: the first leg's owner, which is the line being extended. */
    private static UUID diggerFor(CommandSourceStack source, java.util.Collection<Alignment> routes,
                                  RailGraph.Journey journey) {
        Alignment first = routeIn(routes, journey.legs().get(0).route());
        return first != null ? diggerFor(source, first)
                : source.getEntity() instanceof ServerPlayer player
                        ? WarforgeCompat.factionOfPlayer(player.getUUID()) : null;
    }

    /**
     * The nearest inventory to a point, which is what a depot is.
     *
     * <p>Searched rather than nominated, because the thing a player does is build a chest at the end of
     * the line and expect the railway to use it.</p>
     */
    private static Depot depotNear(ServerLevel level, double x, int y, double z) {
        BlockPos centre = BlockPos.containing(x, y, z);
        Depot best = null;
        double nearest = Double.MAX_VALUE;
        for (int dy = -DEPOT_LIFT; dy <= DEPOT_LIFT; dy++) {
            for (int dz = -DEPOT_REACH; dz <= DEPOT_REACH; dz++) {
                for (int dx = -DEPOT_REACH; dx <= DEPOT_REACH; dx++) {
                    BlockPos at = centre.offset(dx, dy, dz);
                    if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof net.minecraft.world.Container)) {
                        continue;
                    }
                    double away = at.distSqr(centre);
                    if (away < nearest) {
                        nearest = away;
                        best = new Depot(at);
                    }
                }
            }
        }
        return best;
    }

    /** Lay track along a route that has already been dug. */
    private static int track(CommandSourceStack source, String profileName, Integer floor) {
        ServerLevel level = source.getLevel();
        TunnelProfile profile = sectionNamed(source, profileName);
        if (profile == null) {
            return 0;
        }
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        TrackJob job = TrackJob.on(level, route, profile,
                profile.liningOr(RailConfig.TUNNEL_LINING.get()), floorY, RailConfig.TRACK_BOOST_SPACING.get(), diggerFor(source, route));
        if (job == null) {
            source.sendFailure(Component.literal("that route is too short to lay track on"));
            return 0;
        }
        RailWorks.lay(level, job);
        source.sendSuccess(() -> Component.literal("laying " + job.pieces() + " piece(s) of track along "
                + job.routeName() + " at y=" + floorY), true);
        return job.pieces();
    }

    /**
     * Ask the track graph whether the route you are standing on actually carries a railway.
     *
     * <p>The only check that distinguishes a railway from a picture of one: stock runs where the graph
     * says there is track, and a tunnel with sleepers down the middle of it looks finished either way.</p>
     */
    private static int trackCheck(CommandSourceStack source, Integer floor) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there is no track graph to ask"));
            return 0;
        }
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        var coverage = com.wf.wflib.rail.build.ir.IrStock.check(source.getLevel(),
                route.compile().centreline(), floorY, TRACK_CHECK_STEP);
        String name = route.name().isEmpty() ? "unnamed route" : route.name();
        if (coverage.complete()) {
            source.sendSuccess(() -> Component.literal(name + ": " + coverage.describe()), false);
        } else {
            source.sendFailure(Component.literal(name + ": " + coverage.describe()));
        }
        return coverage.onTrack();
    }

    /** How finely {@code /wfrail track check} samples the route. Finer than one piece of track. */
    private static final double TRACK_CHECK_STEP = 2.0;

    /**
     * Take this route's own track back out, so it can be laid again differently.
     *
     * <p>The other half of {@code /wfrail track}, and not a convenience: IR will not put a second
     * anchor where one already is, so a line whose piece joins are in the wrong place stays that way
     * however many times it is re-laid. Without this, "lay that route again with its joins at the
     * junction" is advice nobody can act on.</p>
     *
     * <p>It takes out only pieces that are on this route <em>and</em> running the way it runs, so a line
     * crossing this one keeps its own track through the crossing.</p>
     */
    private static int trackClear(CommandSourceStack source, String profileName, Integer floor) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there is no track graph to clear"));
            return 0;
        }
        TunnelProfile profile = sectionNamed(source, profileName);
        if (profile == null) {
            return 0;
        }
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        var centreline = route.compile().centreline();
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        CarveVolume.Corridor path = TunnelBuilder.corridor(centreline, 0.0, centreline.length(),
                profile, floorY);
        var box = path.bounds();
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                // Anchors sit on the floor course, so one pass over it is the whole railway.
                cells.add(new BlockPos(x, floorY, z));
            }
        }
        int removed = com.wf.wflib.rail.build.ir.IrTrackClear.remove(level,
                cam72cam.mod.world.World.get(level), cells, centreline, profile.width() / 2.0 + 1.0);
        String name = route.name().isEmpty() ? "unnamed route" : route.name();
        source.sendSuccess(() -> Component.literal(removed == 0
                ? name + " has no track of its own at y=" + floorY
                : "took " + removed + " piece(s) of track out of " + name
                        + "; /wfrail track lays it again"), true);
        return removed;
    }

    /**
     * Every place two of this world's routes meet, and what each one has to be built as.
     *
     * <p>Read off the survey graph rather than off the ground, so it answers the question before
     * anything is dug: a branch that leaves the main line at fifty degrees is a turnout nobody can
     * build, and the time to find that out is while it is still a line on a map.</p>
     */
    private static int junctions(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var meetings = com.wf.wflib.rail.align.RouteMeetings.find(level);
        if (meetings.isEmpty()) {
            source.sendSuccess(() -> Component.literal("no two routes in "
                    + level.dimension().location() + " meet"), false);
            return 0;
        }
        for (var meeting : meetings) {
            String note = meeting.sameOwner() ? ""
                    : "  (different owners, so nothing will be built here)";
            source.sendSuccess(() -> Component.literal(meeting.describe() + note), false);
        }
        source.sendSuccess(() -> Component.literal(meetings.size() + " meeting(s);"
                + " /wfrail junctions build puts the junctions in"), false);
        return meetings.size();
    }

    /**
     * Build the junctions between this world's routes.
     *
     * <p>Two kinds need building and one does not. A <b>crossing</b> needs nothing: the track layer
     * already lays the second line's sleepers over the first line's and keeps both, and IR offers a
     * train every path it finds in a block, which is what a diamond is. A <b>turnout</b> is a switch,
     * because a train has to be <em>sent</em> down a branch. And a <b>joint</b> is a connecting curve:
     * two routes that end at the same place do not make one railway if they meet at an angle, because
     * the rails kink there and IR's pathing always prefers to carry straight on. See
     * {@link com.wf.wflib.rail.build.ir.IrJunction}.</p>
     *
     * <p>A junction is never cut into a route another faction owns. That is the same rule as the
     * tunnel: the excavator and the track layer both write blocks with no place event behind them, so
     * nothing else in the game is going to stop it, and a switch left set the wrong way puts somebody
     * else's train on your railway.</p>
     */
    private static int buildJunctions(CommandSourceStack source, Integer floor) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there are no junctions to build"));
            return 0;
        }
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        int[] count = junctions(source.getLevel(), floorY,
                line -> source.sendSuccess(() -> Component.literal(line), true),
                line -> source.sendFailure(Component.literal(line)));
        source.sendSuccess(() -> Component.literal(count[0] + " junction(s) built"
                + (count[1] > 0 ? ", " + count[1] + " not" : "")
                + "; crossings need nothing built, the track layer shares their blocks"), false);
        return count[0];
    }

    /**
     * Build every junction in a level, and say what happened to each.
     *
     * @return how many were built, and how many were not
     */
    public static int[] junctions(ServerLevel level, int floorY, java.util.function.Consumer<String> ok,
                                  java.util.function.Consumer<String> no) {
        var store = AlignmentStore.of(level);
        int built = 0;
        int skipped = 0;
        for (var meeting : com.wf.wflib.rail.align.RouteMeetings.find(level)) {
            var kind = meeting.kind();
            if (kind == com.wf.wflib.rail.align.RouteMeeting.Kind.CROSSING) {
                continue;
            }
            if (!meeting.sameOwner()) {
                no.accept(meeting.a().label() + " meets " + meeting.b().label()
                        + ", which belongs to another faction;"
                        + " a junction into somebody else's line is theirs to build");
                skipped++;
                continue;
            }
            Alignment first = store.get(meeting.a().route());
            Alignment second = store.get(meeting.b().route());
            if (first == null || second == null) {
                continue;
            }
            String done;
            String problem;
            if (kind == com.wf.wflib.rail.align.RouteMeeting.Kind.JOINT) {
                var result = com.wf.wflib.rail.build.ir.IrJunction.build(level, meeting,
                        first.compile().centreline(), second.compile().centreline(), floorY,
                        RailConfig.IR_TRACK.get(), RailConfig.IR_GAUGE.get(),
                        RailConfig.TURNOUT_LEAD.get());
                done = result.done() ? String.format(Locale.ROOT,
                        "junction curve in at %.0f, %.0f: %s onto %s, %s%s", meeting.x(), meeting.z(),
                        meeting.a().label(), meeting.b().label(), result.describe(),
                        result.cut() > 0 ? ", " + result.cut() + " block(s) cut for it" : "") : null;
                problem = result.problem();
            } else {
                var result = com.wf.wflib.rail.build.ir.IrTurnout.build(level, meeting,
                        store.get(meeting.through().route()).compile().centreline(),
                        store.get(meeting.branch().route()).compile().centreline(), floorY,
                        RailConfig.IR_TRACK.get(), RailConfig.IR_GAUGE.get(),
                        RailConfig.TURNOUT_LEAD.get());
                done = result.done() ? String.format(Locale.ROOT,
                        "turnout in at %.0f, %.0f: %s off %s, diverging %.0f degrees%s",
                        meeting.x(), meeting.z(), meeting.branch().label(),
                        meeting.through().label(), Math.abs(result.divergence()),
                        result.cut() > 0 ? ", " + result.cut() + " block(s) cut for it" : "") : null;
                problem = result.problem();
            }
            if (done != null) {
                built++;
                ok.accept(done);
            } else {
                skipped++;
                no.accept(problem);
            }
        }
        return new int[]{built, skipped};
    }

    // ------------------------------------------------------------------ the Five Points network

    /** How far a roll is let run when nobody says. Longer than the longest line in a test network. */
    private static final double ROLL_LIMIT = 3000.0;

    /** How far from the start of a route a depot may be, and how far up or down. */
    private static final int DEPOT_REACH = 12;
    private static final int DEPOT_LIFT = 4;

    /**
     * Machines out at once when the demo is built {@code together}, unless a number is given.
     *
     * <p>A few rather than all nine. Every one of them is a tunnel being driven through ground the
     * others have been told is solid, which is the case being tested; putting a machine on every route
     * at once would also mean no route is ever built while another is only half dug, and that overlap
     * is the interesting half.</p>
     */
    private static final int GANG = 3;

    private static final SuggestionProvider<CommandSourceStack> STOP_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    FivePoints.stops().stream().map(FivePoints.Stop::name), builder);

    /**
     * Roll a train from where you stand and say where it gets to.
     *
     * <p>Not a check on one route: it follows the railway wherever the railway goes, across a crossing
     * and out of one route onto another, which is the only question a network raises that a route does
     * not. It is Immersive Railroading's own pathing, so where it stops is where a train stops.</p>
     */
    private static int roll(CommandSourceStack source, double limit) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there is no track graph to roll along"));
            return 0;
        }
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("a roll starts where you stand and goes where you"
                    + " are facing, so this has to be run by something in the world"));
            return 0;
        }
        Vec3 at = source.getPosition();
        var ran = com.wf.wflib.rail.build.ir.IrRoll.roll(source.getLevel(),
                new Vec3(at.x, Math.floor(at.y), at.z), source.getEntity().getYRot(), limit);
        source.sendSuccess(() -> Component.literal(ran.describe()), false);
        return (int) ran.blocks();
    }

    private static int demoSurvey(CommandSourceStack source, int originX, int originZ) {
        ServerPlayer player = playerOrTell(source, "a route belongs to whoever surveys it");
        if (player == null) {
            return 0;
        }
        int drawn = FivePointsFixture.survey(player, source.getLevel(), originX, originZ);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Five Points drawn at %d, %d: %d route(s), %.0f blocks of railway, floor y=%d",
                originX, originZ, drawn, FivePoints.length(), FivePoints.FLOOR_Y)), true);
        for (FivePoints.Stop stop : FivePoints.stops()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %-14s %d, %d",
                    stop.name(), (int) stop.worldX(originX), (int) stop.worldZ(originZ))), false);
        }
        source.sendSuccess(() -> Component.literal("/wfrail junctions lists what meets what;"
                + " /wfrail demo build sets it going"), false);
        return drawn;
    }

    private static int demoBuild(CommandSourceStack source, double speed, boolean reverse, int gang) {
        ServerPlayer player = playerOrTell(source, "somebody has to be told how it is going");
        if (player == null) {
            return 0;
        }
        String refused = FivePointsFixture.start(source.getLevel(), player,
                RailConfig.TUNNEL_PROFILE.get(), FivePoints.FLOOR_Y, speed, reverse, gang);
        if (refused != null) {
            source.sendFailure(Component.literal(refused));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "building Five Points%s at %.0f blocks/s: %.0f blocks of route, %s,"
                        + " every finished line re-checked after each of them",
                reverse ? " in reverse order" : "", speed, FivePoints.length(),
                gang > 1 ? gang + " machines out at once" : "one line at a time")), true);
        return 1;
    }

    private static int demoCheck(CommandSourceStack source) {
        List<String> verdict = com.wf.wflib.rail.demo.FivePointsCheck.verdict(source.getLevel(),
                FivePoints.FLOOR_Y);
        for (String line : verdict) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return verdict.size();
    }

    private static int demoStock(CommandSourceStack source) {
        if (!RailCompat.trackGraphAvailable()) {
            source.sendFailure(Component.literal("Immersive Railroading is not installed,"
                    + " so there is no stock to place"));
            return 0;
        }
        var placed = com.wf.wflib.rail.demo.FivePointsStock.put(source.getLevel(),
                FivePoints.FLOOR_Y);
        for (String note : placed.notes()) {
            source.sendFailure(Component.literal("  " + note));
        }
        source.sendSuccess(() -> Component.literal(placed.stock()
                + " piece(s) of stock on the network; the hand cars need no fuel, so get on one"), true);
        return placed.stock();
    }

    private static int demoClear(CommandSourceStack source) {
        ServerPlayer player = playerOrTell(source, "removing a route is somebody's decision");
        if (player == null) {
            return 0;
        }
        int gone = FivePointsFixture.clear(player, source.getLevel());
        source.sendSuccess(() -> Component.literal(gone + " route(s) taken off the map."
                + " The tunnels and the track are still there:"
                + " /wfrail track clear takes a line's track out"), true);
        return gone;
    }

    private static int demoTp(CommandSourceStack source, String name) {
        ServerPlayer player = playerOrTell(source, "there is nothing to move");
        if (player == null) {
            return 0;
        }
        FivePoints.Stop stop = FivePoints.stop(name.trim());
        if (stop == null) {
            source.sendFailure(Component.literal("no stop called '" + name + "'"));
            return 0;
        }
        List<Alignment> drawn = FivePointsFixture.drawn(source.getLevel());
        if (drawn.isEmpty()) {
            source.sendFailure(Component.literal("the network is not drawn here"));
            return 0;
        }
        Alignment first = drawn.get(0);
        FivePoints.Route table = FivePoints.route(first.name());
        double originX = first.points().get(0).x() - table.points().get(0)[0];
        double originZ = first.points().get(0).z() - table.points().get(0)[1];
        player.teleportTo(source.getLevel(), stop.worldX(originX), FivePoints.FLOOR_Y + 1,
                stop.worldZ(originZ), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("at " + stop.name()), false);
        return 1;
    }

    /** The player who ran this, or null with the reason already reported. */
    private static ServerPlayer playerOrTell(CommandSourceStack source, String why) {
        try {
            return source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal(why + ", so this has to be run by a player"));
            return null;
        }
    }

    /**
     * Print the tunnel's cross-section here, beside the section it was drawn from.
     *
     * <p>From inside, a bore that came out a block narrow or with a course of its wall missing looks
     * like a tunnel, and every part of this build agrees about the section right up until the blocks
     * are written. This is the only thing that shows the difference.</p>
     */
    private static int section(CommandSourceStack source, Double chainage, Integer floor) {
        Alignment route = routeAt(source);
        if (route == null) {
            return 0;
        }
        TunnelProfile profile = sectionNamed(source, RailConfig.TUNNEL_PROFILE.get());
        if (profile == null) {
            return 0;
        }
        var line = route.compile().centreline();
        int floorY = floor != null ? floor : (int) Math.floor(source.getPosition().y);
        double at = chainage != null ? chainage
                : com.wf.wflib.rail.align.AlignmentProgress.chainageAt(route,
                        source.getPosition().x, source.getPosition().z, 48.0);
        if (at < 0.0) {
            source.sendFailure(Component.literal("you are not on that route; give a chainage"));
            return 0;
        }
        var probe = com.wf.wflib.rail.excavate.SectionProbe.at(source.getLevel(), line, profile,
                floorY, at);
        for (String row : com.wf.wflib.rail.excavate.SectionProbe.describe(probe, route.name())) {
            source.sendSuccess(() -> Component.literal(row), false);
        }
        return 1;
    }

    /** Every machine currently building railway in this world. */
    private static int works(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var trains = RailWorks.trains(level);
        var jobs = RailWorks.jobs(level);
        if (trains.isEmpty() && jobs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("nothing being built in "
                    + level.dimension().location()), false);
            return 0;
        }
        for (BoreTrain train : trains) {
            source.sendSuccess(() -> Component.literal("bore train on " + train.routeName() + ": "
                    + train.summary()), false);
        }
        for (TrackJob job : jobs) {
            source.sendSuccess(() -> Component.literal("track gang on " + job.routeName() + ": "
                    + job.summary()), false);
        }
        return trains.size() + jobs.size();
    }

    private static int stopWorks(CommandSourceStack source) {
        boolean network = FivePointsFixture.stop(source.getLevel());
        int stopped = RailWorks.stopAll(source.getLevel(), "called off");
        if (network) {
            source.sendSuccess(() -> Component.literal("the Five Points run was called off too"), true);
        }
        source.sendSuccess(() -> Component.literal(stopped == 0 ? "nothing was working"
                : "called off " + stopped + " machine(s); what they had already built stays"), true);
        return stopped;
    }

    /** Every drawn section, with its drawing, so the shape can be checked without leaving the game. */
    private static int profiles(CommandSourceStack source) {
        var all = TunnelProfiles.all();
        if (all.isEmpty()) {
            source.sendFailure(Component.literal("no tunnel sections loaded; look in "
                    + TunnelProfiles.directory()));
            return 0;
        }
        for (TunnelProfile profile : all) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%s: %d wide, %d tall, %s", profile.name(), profile.boreWidth(),
                    profile.boreHeight(),
                    profile.torchSpacing() > 0 ? "a torch every " + profile.torchSpacing() + " blocks"
                            : "no torches")), false);
            for (String row : profile.render()) {
                source.sendSuccess(() -> Component.literal("  " + row), false);
            }
        }
        source.sendSuccess(() -> Component.literal("from " + TunnelProfiles.directory()), false);
        return all.size();
    }

    private static int reloadProfiles(CommandSourceStack source) {
        TunnelProfiles.reload();
        source.sendSuccess(() -> Component.literal(TunnelProfiles.names().size()
                + " tunnel section(s) loaded"), true);
        return TunnelProfiles.names().size();
    }

    private static int stats(CommandSourceStack source) {
        ExcavationService service = ExcavationService.of(source.getLevel());
        source.sendSuccess(() -> Component.literal(service.stats().toString()), false);
        return 1;
    }

    /** The phase breakdown, which is the only thing that says what to make faster. */
    private static int phases(CommandSourceStack source) {
        ExcavationService service = ExcavationService.of(source.getLevel());
        source.sendSuccess(() -> Component.literal(service.stats().phases()), false);
        return 1;
    }

    private static int resetStats(CommandSourceStack source) {
        ExcavationService.of(source.getLevel()).stats().reset();
        source.sendSuccess(() -> Component.literal("carve stats reset"), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        ExcavationService service = ExcavationService.of(source.getLevel());
        int dropped = service.pending();
        int running = service.inFlight();
        int breaking = service.attended();
        service.cancel();
        source.sendSuccess(() -> Component.literal("dropped " + dropped + " queued, revoked " + running
                + " in flight, abandoned " + breaking + " attended"), false);
        return dropped + running + breaking;
    }
}
