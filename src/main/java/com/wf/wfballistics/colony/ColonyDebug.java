package com.wf.wfballistics.colony;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import com.wf.wfballistics.industry.IndustryApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
        reportEvolution(source, registry);
        return registry.colonies().size();
    }

    /**
     * Read the evolution scalar, and what it has unlocked. Worth more than the bare number: the number only
     * matters through the caste table.
     */
    public static int evolution(CommandSourceStack source) {
        reportEvolution(source, ColonyRegistry.get(source.getLevel()));
        return 1;
    }

    /**
     * Set it outright. Evolution takes tens of hours to move on its own, which is not a test.
     */
    public static int evolution(CommandSourceStack source, double value) {
        ColonyRegistry registry = ColonyRegistry.get(source.getLevel());
        registry.setEvolution((float) value);
        reportEvolution(source, registry);
        return 1;
    }

    private static void reportEvolution(CommandSourceStack source, ColonyRegistry registry) {
        float evolution = registry.evolution();
        int pressure = IndustryApi.totalPressure(source.getLevel());

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "evolution %.4f; industry %d; colonies grow and muster at x%.2f",
                evolution, pressure, Evolution.strength(evolution))), false);

        StringBuilder castes = new StringBuilder();
        for (GlyphidCaste caste : GlyphidCaste.VALUES) {
            if (!caste.unlockedAt(evolution)) {
                continue;
            }
            castes.append(castes.isEmpty() ? "" : ", ")
                    .append(caste.lowerName())
                    .append(String.format(Locale.ROOT, " %.0f%%", 100.0F * share(caste, evolution)));
        }
        source.sendSuccess(() -> Component.literal("  fielding: " + castes), false);
    }

    private static float share(GlyphidCaste caste, float evolution) {
        float total = 0.0F;
        for (GlyphidCaste other : GlyphidCaste.VALUES) {
            total += other.weightAt(evolution);
        }
        return total <= 0.0F ? 0.0F : caste.weightAt(evolution) / total;
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
     * Report how the loaded swarm has divided itself up.
     *
     * <p>The split is the whole mechanic and it is invisible from outside: four squads walking to four
     * different places look exactly like one swarm milling about until you know what each of them was told.
     */
    public static int squads(CommandSourceStack source) {
        var swarm = GlyphidTracker.glyphids(source.getLevel());
        if (swarm.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No glyphids in this dimension."), false);
            return 0;
        }

        Map<Integer, int[]> counts = new TreeMap<>();
        Map<Integer, Double> power = new TreeMap<>();
        Map<Integer, String> orders = new TreeMap<>();
        int unassigned = 0;
        for (EntityGlyphid bug : swarm) {
            if (bug.objective == null) {
                unassigned++;
                continue;
            }
            counts.computeIfAbsent(bug.squad, k -> new int[1])[0]++;
            power.merge(bug.squad, bug.getStats().power(), Double::sum);
            orders.putIfAbsent(bug.squad, bug.objective.toString());
        }

        if (counts.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    swarm.size() + " glyphids, none in a squad yet (reassigned every "
                            + GlyphidSquads.REFORM_INTERVAL + " ticks)"), false);
            return 0;
        }
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            int id = entry.getKey();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  squad %d: %d bugs, power %.0f -> %s",
                    id, entry.getValue()[0], power.get(id), orders.get(id))), false);
        }
        int loose = unassigned;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d squads over %d glyphids, %d unassigned", counts.size(), swarm.size(), loose)), false);
        return counts.size();
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
     * Build the nearest colony's nest here and now, rather than waiting for a scout to settle one or for its
     * chunk to load.
     *
     * <p>Reports what landed against what is owed, because that split is the whole of {@link PendingChunkEdits}
     * and it is invisible otherwise: a mound half in an unloaded chunk looks like a mound that failed to
     * build.
     */
    public static int nest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Vec3 pos = source.getPosition();

        Colony colony = registry.nearest(pos.x, pos.z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal(
                    "No colonies; found one first with 'colony found'."), false);
            return 0;
        }
        if (!colony.hasResolvedY()) {
            // Load the chunk before asking how high the ground is. Level.getHeight does not load one: for an
            // unloaded column it quietly answers with the bottom of the world, which builds the mound in the
            // bedrock and clips every chamber off it. Loading it also fires ChunkEvent.Load, which builds the
            // nest properly on the way past -- hence the `built` re-read below.
            level.getChunk(colony.x >> 4, colony.z >> 4);
            if (!colony.hasResolvedY()) {
                colony.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, colony.x, colony.z);
            }
        }

        boolean already = colony.built;
        GlyphidNest.Result result = already ? GlyphidNest.survey(level, colony) : GlyphidNest.place(level, colony);
        colony.built = true;
        registry.setDirty();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s %s: %d blocks (%d written, %d owed to unloaded chunks), %d chambers",
                already ? "Already built" : "Built", colony, result.blocks(), result.written(),
                result.blocks() - result.written(), result.chambers().size())), false);
        for (BlockPos chamber : result.chambers()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  chamber at %d %d %d", chamber.getX(), chamber.getY(), chamber.getZ())), false);
        }
        return result.blocks();
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
        int airborne = 0;
        int shown = 0;
        for (EntityGlyphid bug : swarm) {
            boolean flying = bug.isAirborne();
            // A flying glyphid does no pathfinding at all, so it is not "stalled" for lacking a path.
            boolean pathing = flying || !bug.getNavigation().isDone();
            if (flying) {
                airborne++;
            }
            if (!pathing) {
                idle++;
            }
            if (shown++ < BUG_REPORT_LIMIT) {
                double dx = bug.taskX - bug.getX();
                double dz = bug.taskZ - bug.getZ();
                int distance = (int) Math.sqrt(dx * dx + dz * dz);
                String state = flying
                        ? String.format(Locale.ROOT, "flying, %.0f deg bank", Math.toDegrees(bug.getRoll()))
                        : (pathing ? "pathing" : "no path");
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "  (%d, %d, %d) task %d, %d blocks out, %s, %.0f hp",
                        (int) bug.getX(), (int) bug.getY(), (int) bug.getZ(),
                        bug.getCurrentTask(), distance, state, bug.getHealth())), false);
            }
        }
        int total = swarm.size();
        int stalled = idle;
        int flyers = airborne;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids, %d airborne, %d without a path%s", total, flyers, stalled,
                total > BUG_REPORT_LIMIT ? " (first " + BUG_REPORT_LIMIT + " listed)" : "")), false);
        return total;
    }

    /**
     * Send a warband from the nearest colony at the caller, so an attack can be watched without waiting for
     * one to be provoked and then to walk several thousand blocks.
     */
    public static int dispatch(CommandSourceStack source, int count, boolean flying) {
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
        warband.flying = flying;
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
