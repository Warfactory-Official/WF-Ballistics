package com.wf.wflib.rail;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wf.wflib.WFLib;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.ClaimRegistry;
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
import java.util.Locale;

/** {@code /wfrail}: drive the excavator by hand, and read back what it cost. */
@EventBusSubscriber(modid = WFLib.MODID)
public final class RailCommands {

    private RailCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!RailCompat.isActive()) {
            return;
        }
        event.getDispatcher().register(Commands.literal("wfrail")
                .requires(source -> source.hasPermission(2))
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
