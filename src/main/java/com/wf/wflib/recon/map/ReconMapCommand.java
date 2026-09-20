package com.wf.wflib.recon.map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.wf.wflib.block.entity.ReconHubBlockEntity;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.grid.HubIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@code /reconmap}: turn the grid overlay on, and for an operator, point it at a network. */
public final class ReconMapCommand {

    /** Blocks {@code here} will look for a hub. Generous: the hub you mean may be across the base. */
    private static final double HERE_RADIUS = 256.0;

    private ReconMapCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reconmap")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("on")
                        .executes(ctx -> on(ctx.getSource())))
                .then(Commands.literal("off")
                        .executes(ctx -> off(ctx.getSource())))
                .then(Commands.literal("here")
                        .requires(ReconMapCommand::mayChoose)
                        .executes(ctx -> here(ctx.getSource())))
                .then(Commands.literal("net")
                        .requires(ReconMapCommand::mayChoose)
                        .then(Commands.argument("uuid", StringArgumentType.string())
                                .suggests(ReconMapCommand::suggestNets)
                                .executes(ctx -> net(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "uuid"))))));
    }

    /**
     * @return whether this source may name a network. A non-player source (a command block, the console)
     *      falls back to the permission level, which is the same rule those already run under everywhere else.
     */
    private static boolean mayChoose(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(2) : ReconMapService.mayChooseAnyNet(player);
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ReconMapService.Watch watch = ReconMapService.watchOf(player);
        if (watch == null) {
            source.sendSuccess(() -> Component.literal("recon map: off. /reconmap on to watch your network.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        long netId = watch.netId();
        ReconMapView view = ReconMapService.build(player.serverLevel(), netId);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "recon map: net %s (%s) - %s", Long.toHexString(netId),
                        watch.auto() ? "your faction" : "chosen", ReconMapService.describe(view)))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int on(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        long netId = ReconMapService.watchAuto(player);
        ReconMapView view = ReconMapService.build(player.serverLevel(), netId);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "recon map: net %s - %s", Long.toHexString(netId), ReconMapService.describe(view)))
                .withStyle(ChatFormatting.GREEN), false);
        hintIfEmpty(source, player, view);
        source.sendSuccess(() -> Component.literal(
                        "  drawn by JourneyMap, under the overlay groups 'WF Recon: coverage/links/nodes'")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /**
     * Say why an automatic subscription is showing nothing, which has exactly two causes and neither is visible
     * from an empty map.
     */
    private static void hintIfEmpty(CommandSourceStack source, ServerPlayer player, ReconMapView view) {
        if (!view.isEmpty()) {
            return;
        }
        boolean unaffiliated = com.wf.wflib.recon.ReconOwners.owningEntity(player) == null;
        source.sendSuccess(() -> Component.literal(unaffiliated
                        ? "  you are in no faction, so this is the unaffiliated network"
                        : "  nothing is on your faction's network here - a hub joins it only if it was built "
                                + "inside one of your claims, or bound to it with a grid key")
                .withStyle(ChatFormatting.YELLOW), false);
    }

    private static int off(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ReconMapService.stop(player);
        source.sendSuccess(() -> Component.literal("recon map: off").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Watch whatever network the nearest hub is on. */
    private static int here(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Long netId = ReconMapService.netNear(level, BlockPos.containing(source.getPosition()), HERE_RADIUS);
        if (netId == null) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "No hub within %.0f blocks. A network is rooted at a hub; without one there is nothing "
                            + "to name.", HERE_RADIUS)));
            return 0;
        }
        return adopt(source, player, netId);
    }

    private static int net(CommandSourceStack source, String raw) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Not a UUID: " + raw
                    + ". Networks are named by the hub's UUID, not by the folded net id."));
            return 0;
        }
        return adopt(source, player, SourceIds.of(id));
    }

    private static int adopt(CommandSourceStack source, ServerPlayer player, long netId) {
        ReconMapService.watch(player, netId);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "recon map: net %s (chosen) - %s", Long.toHexString(netId),
                        ReconMapService.describe(ReconMapService.build(player.serverLevel(), netId))))
                .withStyle(ChatFormatting.GREEN), false);
        if (netId == ReconNet.UNAFFILIATED) {
            source.sendSuccess(() -> Component.literal(
                            "  that is the unaffiliated network - everything with no faction claim is on it")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    /** Every identity in play in this dimension, which in practice means every hub's. */
    private static CompletableFuture<Suggestions> suggestNets(CommandContext<CommandSourceStack> ctx,
                                                              SuggestionsBuilder builder) {
        Set<String> options = new LinkedHashSet<>();
        ServerLevel level = ctx.getSource().getLevel();
        for (BlockPos pos : HubIndex.hubs(level)) {
            if (level.getBlockEntity(pos) instanceof ReconHubBlockEntity hub && hub.gridId() != null) {
                options.add(hub.gridId().toString());
            }
        }
        return SharedSuggestionProvider.suggest(options, builder);
    }
}
