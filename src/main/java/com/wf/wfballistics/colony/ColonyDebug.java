package com.wf.wfballistics.colony;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
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

    /**
     * Lines a swarm report will print before it just gives totals.
     */
    private static final int BUG_REPORT_LIMIT = 16;

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

    /**
     * Report what every materialised glyphid thinks it is doing.
     *
     * <p>A swarm that arrives and then mills around looks identical from outside to one that arrives and
     * marches, and the difference is whether it has a path. Without this the only way to tell them apart is
     * to rebuild the mod with a print statement in it.
     */
    public static int bugs(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var swarm = GlyphidTracker.glyphids(level);
        if (swarm.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No glyphids in this dimension."), false);
            return 0;
        }

        int idle = 0;
        int shown = 0;
        for (EntityGlyphid bug : swarm) {
            boolean pathing = !bug.getNavigation().isDone();
            if (!pathing) {
                idle++;
            }
            if (shown++ < BUG_REPORT_LIMIT) {
                double dx = bug.taskX - bug.getX();
                double dz = bug.taskZ - bug.getZ();
                int distance = (int) Math.sqrt(dx * dx + dz * dz);
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "  (%d, %d, %d) task %d, %d blocks out, %s, %.0f hp",
                        (int) bug.getX(), (int) bug.getY(), (int) bug.getZ(),
                        bug.getCurrentTask(), distance,
                        pathing ? "pathing" : "no path", bug.getHealth())), false);
            }
        }
        int total = swarm.size();
        int stalled = idle;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids, %d without a path%s", total, stalled,
                total > BUG_REPORT_LIMIT ? " (first " + BUG_REPORT_LIMIT + " listed)" : "")), false);
        return total;
    }

    /**
     * Send a warband from the nearest colony at the caller, so an attack can be watched without waiting for
     * one to be provoked and then to walk several thousand blocks.
     */
    public static int dispatch(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Vec3 pos = source.getPosition();

        Colony origin = registry.nearest(pos.x, pos.z);
        if (origin == null) {
            source.sendSuccess(() -> Component.literal(
                    "No colonies to send one; found one first with 'colony found'."), false);
            return 0;
        }

        Warband warband = new Warband(java.util.UUID.randomUUID(), origin.id, origin.x, origin.z,
                (int) pos.x, (int) pos.z, count, origin.tier);
        registry.add(warband);
        source.sendSuccess(() -> Component.literal("Dispatched " + warband), false);
        return count;
    }

    /**
     * Materialise the nearest warband on the spot, ignoring the per-tick budget.
     *
     * <p>Reports what was placed against what is still owed, which is the invariant that matters: bodies
     * that could not be placed stay in the record rather than being lost.
     */
    public static int materialise(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Vec3 pos = source.getPosition();

        Warband nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (Warband warband : registry.warbands()) {
            double dx = warband.x - pos.x;
            double dz = warband.z - pos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestSq) {
                bestSq = distSq;
                nearest = warband;
            }
        }
        if (nearest == null) {
            source.sendSuccess(() -> Component.literal("No warbands in flight."), false);
            return 0;
        }

        int before = nearest.count;
        int spawned = WarbandMaterialiser.materialise(level, registry, nearest, count);
        int owed = before - spawned;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Placed %d of %d; %d still owed to the record", spawned, before, owed)), false);
        return spawned;
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
