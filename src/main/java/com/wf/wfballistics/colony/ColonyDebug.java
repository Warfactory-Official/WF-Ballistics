package com.wf.wfballistics.colony;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Inspection and control for the colony simulation.
 *
 * <p>Load-bearing rather than a nicety: this simulation's whole point is running where nobody is watching,
 * so without a way to interrogate it there is no way to tell a tuning problem from a bug. {@code fastforward}
 * exists because a strike cycle is otherwise tens of minutes of real time.
 */
public final class ColonyDebug {

    private ColonyDebug() {
    }

    public static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        PendingChunkEdits edits = PendingChunkEdits.get(level);
        BlockPos spawn = level.getSharedSpawnPos();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d colonies, %d warbands in flight; %d blocks owed to %d unloaded chunks",
                registry.colonies().size(), registry.warbands().size(),
                edits.pendingBlocks(), edits.pendingChunks())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "spawn (%d, %d); safe radius %d, full strength at %d, max tier %d",
                spawn.getX(), spawn.getZ(), ColonyConfig.safeRadius(),
                ColonyConfig.fullStrengthDistance(), ColonyConfig.maxTier())), false);
        return registry.colonies().size();
    }

    public static int list(CommandSourceStack source) {
        ColonyRegistry registry = ColonyRegistry.get(source.getLevel());
        if (registry.colonies().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        BlockPos spawn = source.getLevel().getSharedSpawnPos();
        for (Colony colony : registry.colonies()) {
            int distance = (int) Colony.distanceFromSpawn(spawn, colony.x, colony.z);
            source.sendSuccess(() -> Component.literal("  " + colony + " @ " + distance + " from spawn"), false);
        }
        return registry.colonies().size();
    }

    public static int warbands(CommandSourceStack source) {
        ColonyRegistry registry = ColonyRegistry.get(source.getLevel());
        if (registry.warbands().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No warbands in flight."), false);
            return 0;
        }
        for (Warband warband : registry.warbands()) {
            source.sendSuccess(() -> Component.literal("  " + warband), false);
        }
        return registry.warbands().size();
    }

    /**
     * Found a colony where the caller is standing, so the simulation can be seeded without waiting for
     * worldgen to place one.
     */
    public static int found(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Colony colony = ColonyManager.found(level, registry, (int) pos.x, (int) pos.z);

        if (colony == null) {
            source.sendSuccess(() -> Component.literal(
                    "Inside the safe radius (" + ColonyConfig.safeRadius() + " blocks); no colony founded."),
                    false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Founded " + colony), false);
        return 1;
    }

    /**
     * Advance the simulation by whole update rounds, ignoring the stagger.
     */
    public static int fastForward(CommandSourceStack source, int rounds) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        int coloniesBefore = registry.colonies().size();
        int warbandsBefore = registry.warbands().size();

        ColonyManager.fastForward(level, rounds);

        int colonies = registry.colonies().size();
        int warbands = registry.warbands().size();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Ran %d rounds (%.1f simulated minutes): colonies %d -> %d, warbands %d -> %d",
                rounds, rounds * ColonyConfig.colonyTickInterval() / 20.0 / 60.0,
                coloniesBefore, colonies, warbandsBefore, warbands)), false);
        return colonies;
    }

    public static int clear(CommandSourceStack source) {
        ColonyRegistry registry = ColonyRegistry.get(source.getLevel());
        int colonies = registry.colonies().size();
        int warbands = registry.warbands().size();
        registry.colonies().clear();
        registry.warbands().clear();
        registry.setDirty();
        source.sendSuccess(() -> Component.literal(
                "Cleared " + colonies + " colonies and " + warbands + " warbands."), false);
        return colonies;
    }
}
