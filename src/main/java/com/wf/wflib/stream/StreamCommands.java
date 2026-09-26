package com.wf.wflib.stream;

import com.mojang.brigadier.Command;
import com.wf.wflib.mixin.AccessorChunkMap;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** {@code /wflib stream ...}. */
public final class StreamCommands {

    private static final String CATEGORY = "category";
    private static final String ENABLE = "enable";

    private StreamCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("stream")
                .then(Commands.literal("audit").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            int[] counts = ChunkStreamAudit.server(EntityArgument.getPlayer(context, "player"),
                                    "wflib-stream-audit-server.txt");
                            context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                                    "vanilla=%d streamed=%d expected=%d", counts[0], counts[1], counts[2])), false);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("status").executes(StreamCommands::status))
                .then(Commands.literal("sent")
                        .executes(context -> sent(context, 12))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(context -> sent(context, IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("sleeping").executes(StreamCommands::sleeping))
                .then(Commands.literal("reset").executes(StreamCommands::reset))
                .then(Commands.literal("debug")
                        .then(Commands.argument(CATEGORY, StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    List<String> names = new ArrayList<>();
                                    names.add("all");
                                    for (StreamDebug.Category category : StreamDebug.Category.values()) {
                                        names.add(category.name().toLowerCase(Locale.ROOT));
                                    }
                                    return SharedSuggestionProvider.suggest(names, builder);
                                })
                                .then(Commands.argument(ENABLE, BoolArgumentType.bool())
                                        .executes(StreamCommands::debug))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        for (String line : StreamDebug.status()) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** What ChunkMap believes the player holds. Tracked here, absent on the client => hole nothing repairs. */
    private static int sent(CommandContext<CommandSourceStack> context, int radius) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        PlayerChunkSender sender = player.connection.chunkSender;
        ChunkTrackingView view = player.getChunkTrackingView();
        ChunkPos body = player.chunkPosition();

        StreamDebug.report("sent | body {} radius {} | server view distance {} | tracking view {}",
                body, radius, ((AccessorChunkMap) chunkMap).wfPlayerViewDistance(player),
                view instanceof ChunkTrackingView.Positioned(ChunkPos center, int viewDistance)
                        ? center + " r" + viewDistance : "empty");
        StreamDebug.report("sent | # tracked+loaded  t tracked+not loaded  p queued to send"
                + "  . not tracked  B body");

        int tracked = 0;
        int stale = 0;
        int queued = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int x = body.x + dx;
                int z = body.z + dz;
                boolean inView = view.contains(x, z);
                boolean loaded = level.getChunkSource().getChunkNow(x, z) != null;
                boolean pending = sender.isPending(ChunkPos.asLong(x, z));
                if (inView) {
                    tracked++;
                    if (!loaded) {
                        stale++;
                    }
                }
                if (pending) {
                    queued++;
                }
                if (x == body.x && z == body.z) {
                    row.append('B');
                } else if (pending) {
                    row.append('p');
                } else if (!inView) {
                    row.append('.');
                } else {
                    row.append(loaded ? '#' : 't');
                }
            }
            StreamDebug.report("sent | {}", row);
        }
        String summary = tracked + " tracked, " + stale + " tracked-but-unloaded, " + queued + " queued to send";
        StreamDebug.report("sent | {}", summary);
        context.getSource().sendSuccess(() -> Component.literal("server chunk view -> logs (" + summary + ")"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int sleeping(CommandContext<CommandSourceStack> context) {
        HostWakeupData data = HostWakeupData.get(context.getSource().getServer());
        if (data.all().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("no hosts registered for wakeup"), false);
            return Command.SINGLE_SUCCESS;
        }
        for (HostWakeupData.Entry entry : data.all()) {
            ServerLevel level = context.getSource().getServer().getLevel(entry.dimension());
            boolean loaded = level != null && level.getEntity(entry.hostId()) != null;
            String line = String.format(Locale.ROOT, "%s %s @ %.1f %.1f %.1f chunk %s [%s]",
                    StreamDebug.shortId(entry.hostId()), entry.dimension().location(),
                    entry.position().x, entry.position().y, entry.position().z, entry.chunk(),
                    loaded ? "loaded" : "sleeping");
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        StreamDebug.setOverride(null);
        context.getSource().sendSuccess(() -> Component.literal("chunk stream debug follows the config again"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int debug(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, CATEGORY).toUpperCase(Locale.ROOT);
        boolean enable = BoolArgumentType.getBool(context, ENABLE);
        EnumSet<StreamDebug.Category> categories = EnumSet.copyOf(StreamDebug.active());
        if (name.equals("ALL")) {
            categories = enable ? EnumSet.allOf(StreamDebug.Category.class)
                    : EnumSet.noneOf(StreamDebug.Category.class);
        } else {
            StreamDebug.Category category;
            try {
                category = StreamDebug.Category.valueOf(name);
            } catch (IllegalArgumentException e) {
                context.getSource().sendFailure(Component.literal("unknown category " + name));
                return 0;
            }
            if (enable) {
                categories.add(category);
            } else {
                categories.remove(category);
            }
        }
        StreamDebug.setOverride(categories);
        EnumSet<StreamDebug.Category> result = categories;
        context.getSource().sendSuccess(() -> Component.literal("chunk stream debug -> "
                + (result.isEmpty() ? "off" : result.toString())), true);
        return Command.SINGLE_SUCCESS;
    }

}
