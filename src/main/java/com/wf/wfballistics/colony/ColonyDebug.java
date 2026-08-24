package com.wf.wfballistics.colony;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import com.wf.wfballistics.industry.IndustryApi;
import com.wf.wfballistics.industry.IndustryRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inspection and control for the colony simulation.
 *
 * <p>Load-bearing rather than a nicety: the simulation's whole point is running where nobody is watching, so
 * without a way to interrogate it there is no telling a tuning problem from a bug.
 */
public final class ColonyDebug {

    /** Lines a swarm report will print before it just gives totals. */
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

    /** Set it outright. Evolution takes tens of hours to move on its own, which is not a test. */
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
            // Load the chunk first: Level.getHeight does not, and for an unloaded column it answers with the
            // bottom of the world. Loading also fires ChunkEvent.Load, which is why `built` is re-read below.
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
                "%s %s: %d cells, %d blocks (%d written, %d owed to unloaded chunks, %d reinforced), %d chambers",
                already ? "Already built" : "Built", colony, colony.buds + 1, result.blocks(),
                result.written(), result.blocks() - result.written(), result.reinforced(),
                result.chambers().size())), false);
        for (BlockPos chamber : result.chambers()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  chamber at %d %d %d", chamber.getX(), chamber.getY(), chamber.getZ())), false);
        }
        return result.blocks();
    }

    /** Advance the simulation by whole update rounds, ignoring the stagger. */
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

    // --- what is around the player, and what is inside one nest ---

    /**
     * Everything within a radius of the caller: nests, what each is doing, and warbands on their way in.
     *
     * <p>The one command that answers "why have I never seen a hive". Colonies are records first and blocks
     * second, so a nest that exists can still be a thousand blocks away in a chunk nobody has loaded, and the
     * only difference between that and a world with no colonies at all is this list.
     */
    public static int nearby(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Vec3 pos = source.getPosition();

        List<Colony> found = new ArrayList<>();
        for (Colony colony : registry.colonies()) {
            if (Math.hypot(colony.x - pos.x, colony.z - pos.z) <= radius) {
                found.add(colony);
            }
        }
        found.sort(Comparator.comparingDouble(c -> Math.hypot(c.x - pos.x, c.z - pos.z)));

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d of %d colonies within %d blocks; %d cells seeded so far",
                found.size(), registry.colonies().size(), radius, registry.seededCells())), false);
        for (Colony colony : found) {
            double dx = colony.x - pos.x;
            double dz = colony.z - pos.z;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %4d blocks %-2s  (%d, %d)  T%d, pop %.0f/%d, aggro %.0f/%.0f%s",
                    (int) Math.hypot(dx, dz), bearing(dx, dz), colony.x, colony.z, colony.tier,
                    colony.population, colony.populationCap(), colony.aggression,
                    ColonyConfig.strikeThreshold(),
                    colony.built ? ", " + (colony.buds + 1) + " cells, " + colony.spawners + " chambers"
                            : ", not built yet")), false);
        }

        int inbound = 0;
        for (Warband warband : registry.warbands()) {
            if (Math.hypot(warband.targetX - pos.x, warband.targetZ - pos.z) <= radius) {
                inbound++;
                double dx = warband.x - pos.x;
                double dz = warband.z - pos.z;
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "  warband of %d, %d blocks %-2s out%s, %s",
                        warband.count, (int) Math.hypot(dx, dz), bearing(dx, dz),
                        warband.flying ? " (flying)" : "",
                        warband.arrived ? "arrived and waiting" : "still travelling")), false);
            }
        }
        if (found.isEmpty() && inbound == 0) {
            source.sendSuccess(() -> Component.literal(nothingNearby(level, pos, radius)), false);
        }
        return found.size();
    }

    /** Why an empty answer is empty, which is a different question from what is nearby. */
    private static String nothingNearby(ServerLevel level, Vec3 pos, int radius) {
        double fromSpawn = Colony.distanceFromSpawn(level.getSharedSpawnPos(), (int) pos.x, (int) pos.z);
        if (fromSpawn < ColonyConfig.safeRadius()) {
            return String.format(Locale.ROOT,
                    "  Nothing: %d blocks from spawn is inside the %d-block safe radius, where none may exist.",
                    (int) fromSpawn, ColonyConfig.safeRadius());
        }
        if (ColonyConfig.naturalSpacing() <= 0) {
            return "  Nothing, and natural seeding is off (colonies.naturalSpacing = 0), so nothing will"
                    + " appear on its own. Use 'colony found' or turn seeding on.";
        }
        return String.format(Locale.ROOT,
                "  Nothing yet. Nests are seeded one per %d-block cell at %.0f%% chance, and only when the"
                        + " chunk holding the site is loaded -- so walk, do not wait.",
                ColonyConfig.naturalSpacing(), 100.0 * ColonyConfig.naturalChance());
    }

    /**
     * The nearest colony's whole state, and what it is about to do.
     *
     * <p>Reports the derived numbers rather than only the stored ones — seconds to the next strike, seconds to
     * a full population, whether growth is capped by the cap or by the garrison — because the stored ones are
     * individually meaningless. "aggression 46" says nothing; "46 of 100, gaining 0.9/s from 45 industry
     * nearby, so a warband of 16 in about a minute" is the state of the colony.
     */
    public static int inspect(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Vec3 pos = source.getPosition();

        Colony colony = registry.nearest(pos.x, pos.z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal(
                    "No colonies in this dimension. 'colony nearby' explains why."), false);
            return 0;
        }

        double strength = Evolution.strength(registry.evolution());
        int pressure = IndustryRegistry.get(level)
                .pressureWithin(colony.x, colony.z, ColonyConfig.provocationRadius());
        double aggroPerSecond = pressure * ColonyConfig.aggressionPerPressure();
        double growth = colony.growthPerSecond() * strength;
        int distance = (int) Colony.distanceFromSpawn(level.getSharedSpawnPos(), colony.x, colony.z);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "colony T%d at (%d, %d), %d blocks from spawn, %d blocks %s of you",
                colony.tier, colony.x, colony.z, distance,
                (int) Math.hypot(colony.x - pos.x, colony.z - pos.z),
                bearing(colony.x - pos.x, colony.z - pos.z))), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  numbers   %.1f of %.0f allowed (cap %d less %d standing), +%.2f/s%s",
                colony.population, colony.room(), colony.populationCap(), colony.garrison, growth,
                colony.population >= colony.room() ? "  [full]"
                        : String.format(Locale.ROOT, "  [full in %.0f s]",
                                (colony.room() - colony.population) / Math.max(1.0E-6, growth)))), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  anger     %.0f of %.0f, +%.3f/s from %d industry within %d blocks%s",
                colony.aggression, ColonyConfig.strikeThreshold(), aggroPerSecond, pressure,
                ColonyConfig.provocationRadius(), strikeNote(colony, aggroPerSecond))), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  muster    %d per warband at this tier x%.2f evolution = %d, %.0f%% chance of wings",
                colony.warbandSize(), strength,
                (int) Math.round(colony.warbandSize() * strength),
                100.0 * ColonyConfig.flyingChance(colony.tier))), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  nest      %s, %d chambers, %d of %d garrison standing",
                colony.built ? "built" : "not built yet (nothing has loaded its chunk)",
                colony.spawners, colony.garrison, colony.garrisonCap())), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  cluster   %d of %d cells, %d blocks apart, reaching %d blocks out%s; crust %s",
                colony.buds + 1, colony.budCap() + 1, NestCells.spacing(colony), colony.footprintRadius(),
                colony.builtBuds() < colony.buds
                        ? String.format(Locale.ROOT, " (%d not laid yet)", colony.buds - colony.builtBuds()) : "",
                crustNote(registry.evolution(), colony.nestRadius()))), false);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  expansion %s; %d population buds a cell here, %d founds an outpost %d blocks out (%.0f%% bud)",
                colony.expansionCooldown > 0
                        ? String.format(Locale.ROOT, "on cooldown for %.0f s", colony.expansionCooldown / 20.0)
                        : (colony.canExpand() ? "ready now" : "ready, but short of population"),
                ColonyConfig.budPopulation(), ColonyConfig.expansionPopulation(),
                ColonyConfig.expansionRange(), 100.0 * ColonyConfig.budChance())), false);

        int out = 0;
        for (Warband warband : registry.warbands()) {
            if (warband.origin.equals(colony.id)) {
                out++;
                source.sendSuccess(() -> Component.literal("  out       " + warband), false);
            }
        }
        if (out == 0) {
            source.sendSuccess(() -> Component.literal("  out       nothing in the field"), false);
        }
        return 1;
    }

    /**
     * What a mound laid right now would be made of, which is not what the mounds already standing are made
     * of — the crust is baked in when each cell is laid and never revisited.
     */
    private static String crustNote(float evolution, int radius) {
        int depth = GlyphidNest.crustDepth(evolution, radius);
        if (depth < 0) {
            return String.format(Locale.ROOT, "soft (reinforced flesh starts at evolution %.2f)",
                    ColonyConfig.reinforcedEvolution());
        }
        if (depth > radius) {
            return "reinforced throughout";
        }
        return String.format(Locale.ROOT, "reinforced %d blocks deep out of %d", depth, radius);
    }

    private static String strikeNote(Colony colony, double aggroPerSecond) {
        if (colony.canStrike()) {
            return "  [striking on its next update]";
        }
        if (colony.population < colony.warbandSize()) {
            return "  [too few to send, whatever it thinks of you]";
        }
        if (aggroPerSecond <= 0.0) {
            return "  [nothing nearby to be angry about]";
        }
        return String.format(Locale.ROOT, "  [strikes in %.0f s]",
                (ColonyConfig.strikeThreshold() - colony.aggression) / aggroPerSecond);
    }

    /** Compass point, so a report can be walked toward without doing trigonometry in your head. */
    private static String bearing(double dx, double dz) {
        // Minecraft's +z is south and +x is east, and atan2 is measured from east anticlockwise.
        int octant = (int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4.0));
        return switch (Math.floorMod(octant, 8)) {
            case 0 -> "E";
            case 1 -> "SE";
            case 2 -> "S";
            case 3 -> "SW";
            case 4 -> "W";
            case 5 -> "NW";
            case 6 -> "N";
            default -> "NE";
        };
    }

    // --- levers ---

    /** Make the nearest colony angry, without building the factory that would have done it. */
    public static int provoke(CommandSourceStack source, double amount) {
        Colony colony = ColonyRegistry.get(source.getLevel()).nearest(
                source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        colony.aggression += amount;
        ColonyRegistry.get(source.getLevel()).setDirty();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Aggression now %.0f of %.0f%s", colony.aggression, ColonyConfig.strikeThreshold(),
                colony.canStrike() ? "; it will strike on its next update"
                        : "; still short of striking")), false);
        return 1;
    }

    /** Give the nearest colony numbers it has not grown yet. */
    public static int grow(CommandSourceStack source, double amount) {
        Colony colony = ColonyRegistry.get(source.getLevel()).nearest(
                source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        colony.population = Math.max(0.0, colony.population + amount);
        ColonyRegistry.get(source.getLevel()).setDirty();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Population now %.1f (room for %.0f)", colony.population, colony.room())), false);
        return 1;
    }

    /**
     * Run one expansion attempt on the nearest colony now, and say what it did or what stopped it. The note
     * comes back from {@link ColonyManager.Expansion} rather than being guessed from the config here.
     */
    public static int expand(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Colony colony = registry.nearest(source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        colony.expansionCooldown = 0;
        ColonyManager.Expansion result = ColonyManager.expand(level, registry, colony);
        ColonyManager.materialiseCells(level, colony);
        registry.setDirty();

        switch (result.kind()) {
            case BUD -> source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "Budded: %s. It is now %s", result.note(), colony)), false);
            case OUTPOST -> source.sendSuccess(() -> Component.literal(
                    "Founded " + result.child()), false);
            case NOTHING -> source.sendSuccess(() -> Component.literal(
                    "Nothing came of it: " + result.note()), false);
        }
        return result.kind() == ColonyManager.Expansion.Kind.NOTHING ? 0 : 1;
    }

    /**
     * Force the local half of expansion, skipping the roll that usually decides between the two.
     *
     * <p>Worth its own command because the two kinds of expansion produce completely different things and
     * {@code colony expand} will not reliably give you the one you want to look at: at the default budChance
     * a bud is a coin flip, and a colony with cells to spare in a crowded region buds every time regardless.
     * This one is for watching a cluster take shape.
     */
    public static int bud(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        ColonyRegistry registry = ColonyRegistry.get(level);
        Colony colony = registry.nearest(source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        int grown = 0;
        while (grown < count && colony.canBud()) {
            colony.buds++;
            grown++;
        }
        if (grown == 0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "All %d cells already grown; a T%d colony gets %d. Raise colonies.budCapPerTier"
                            + " or find a nest further out.",
                    colony.budCap() + 1, colony.tier, colony.budCap() + 1)), false);
            return 0;
        }
        ColonyManager.materialiseCells(level, colony);
        registry.setDirty();

        int added = grown;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Grew %d cell%s: %s", added, added == 1 ? "" : "s", colony)), false);
        for (NestCells.Cell cell : NestCells.cells(colony, colony.buds - added + 1, colony.buds)) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  cell %d at (%d, %d), %d blocks out",
                    cell.index(), cell.x(), cell.z(),
                    (int) Math.hypot(cell.x() - colony.x, cell.z() - colony.z))), false);
        }
        if (colony.builtBuds() < colony.buds) {
            source.sendSuccess(() -> Component.literal(
                    "  (no blocks laid: the colony's own chunk is not loaded. They arrive when it is.)"),
                    false);
        }
        return added;
    }

    /** Remove the nearest colony's record. Its mound is left standing — the blocks are not the colony. */
    public static int raze(CommandSourceStack source) {
        ColonyRegistry registry = ColonyRegistry.get(source.getLevel());
        Colony colony = registry.nearest(source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendSuccess(() -> Component.literal("No colonies."), false);
            return 0;
        }
        registry.remove(colony);
        source.sendSuccess(() -> Component.literal(
                "Removed " + colony + ". The mound is still there; the colony is not."), false);
        return 1;
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
