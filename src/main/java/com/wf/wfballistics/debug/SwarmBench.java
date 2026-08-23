package com.wf.wfballistics.debug;

import com.wf.wfballistics.ModEntities;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import com.wf.wfballistics.entity.glyphid.GlyphidSeparation;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidManager;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidPass;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spawns a glyphid swarm on demand and reports what it costs.
 *
 * <p>The point is a <em>controlled</em> before/after: the same command, at the same counts, on the same
 * arena, run against each stage of the swarm work. Numbers taken from an incidental nest in a live world
 * are not comparable between runs and cannot show whether an optimisation helped.
 *
 * <p>Spawns in a ring so the swarm starts spread out and converges, which is the expensive case: every bug
 * pathing to the same place and then packed tightly enough to push against its neighbours. A swarm dropped
 * in a heap and left idle measures almost nothing.
 */
public final class SwarmBench {

    /**
     * Ticks to let the swarm settle into steady-state before the window is worth reading. A swarm measured
     * from the spawn tick is measuring spawning, not swarming.
     */
    public static final int WARMUP_TICKS = 60;

    /**
     * Chunks of margin forceloaded around the ring, so bugs that wander outward keep ticking.
     */
    private static final int ARENA_MARGIN_CHUNKS = 2;

    private static int warmup;

    /**
     * The arena's forced chunks, held so they can be released again. Without this the benchmark silently
     * measures nothing: with no player logged in, chunks outside spawn do not tick, so the swarm exists,
     * is counted, and never runs a single tick of AI.
     */
    private static final Set<ChunkPos> FORCED = new HashSet<>();
    private static @Nullable ServerLevel arenaLevel;

    private SwarmBench() {
    }

    /**
     * Spawn {@code count} glyphids in a ring around the source, clearing any previous run first so two
     * benchmarks never overlap.
     */
    public static int spawn(CommandSourceStack source, int count, double radius) {
        return spawn(source, count, radius, GlyphidCaste.GRUNT);
    }

    /**
     * As above, of one named caste. Explicitly one caste rather than a rolled mix: a benchmark arm that
     * fields a random assortment is not comparable with the next one.
     */
    public static int spawn(CommandSourceStack source, int count, double radius, GlyphidCaste caste) {
        ServerLevel level = source.getLevel();
        Vec3 center = source.getPosition();

        int removed = clearLevel(level);
        releaseArena();
        forceArena(level, center, radius);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            // Spiral outward slightly so a large count doesn't stack every bug on one circle.
            double r = radius * (0.6 + 0.4 * ((double) i / Math.max(1, count)));
            double x = center.x + Math.cos(angle) * r;
            double z = center.z + Math.sin(angle) * r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);

            EntityGlyphid glyphid = caste.type().create(level);
            if (glyphid == null) {
                continue;
            }
            glyphid.moveTo(x, y, z, level.random.nextFloat() * 360F, 0F);
            // Benchmark bugs must not despawn, or the population drifts mid-window and the per-entity
            // figure is measured against a count that was never true.
            glyphid.setPersistenceRequired();
            if (level.addFreshEntity(glyphid)) {
                spawned++;
            }
        }

        SwarmProfiler.setEnabled(true);
        warmup = WARMUP_TICKS;

        int placed = spawned;
        int live = GlyphidTracker.count(level);
        source.sendSuccess(() -> Component.literal(
                "Spawned " + placed + " " + caste.lowerName() + " glyphids in a " + (int) radius + "-block ring"
                        + (removed > 0 ? " (cleared " + removed + " first)" : "")
                        + ", " + FORCED.size() + " chunks forceloaded, " + live + " live."
                        + " Profiling on, warming up for " + WARMUP_TICKS + " ticks."), false);
        return placed;
    }

    /**
     * Forceload the ring and its margin. The arena has to tick for any of this to mean anything, and with
     * nobody logged in nothing outside the spawn chunks does.
     */
    private static void forceArena(ServerLevel level, Vec3 center, double radius) {
        int reach = (int) Math.ceil(radius) + 16;
        int minChunkX = SectionPos.blockToSectionCoord(center.x - reach) - ARENA_MARGIN_CHUNKS;
        int maxChunkX = SectionPos.blockToSectionCoord(center.x + reach) + ARENA_MARGIN_CHUNKS;
        int minChunkZ = SectionPos.blockToSectionCoord(center.z - reach) - ARENA_MARGIN_CHUNKS;
        int maxChunkZ = SectionPos.blockToSectionCoord(center.z + reach) + ARENA_MARGIN_CHUNKS;

        arenaLevel = level;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.setChunkForced(cx, cz, true)) {
                    FORCED.add(new ChunkPos(cx, cz));
                }
            }
        }
    }

    /**
     * Give the arena's chunks back, so a finished benchmark stops costing the server anything.
     */
    private static void releaseArena() {
        if (arenaLevel != null) {
            for (ChunkPos pos : FORCED) {
                arenaLevel.setChunkForced(pos.x, pos.z, false);
            }
        }
        FORCED.clear();
        arenaLevel = null;
    }

    /**
     * Remove every glyphid and hand the arena's chunks back. Clears before releasing: a glyphid in a chunk
     * that has already been let go is not loaded, so it is not in the tracker, so it would survive the
     * clear and contaminate the next run's population.
     */
    public static int clear(CommandSourceStack source) {
        int removed = clearLevel(source.getLevel());
        releaseArena();
        SwarmProfiler.setEnabled(false);
        source.sendSuccess(() -> Component.literal(
                "Removed " + removed + " glyphids, arena released, profiling off."), false);
        return removed;
    }

    private static int clearLevel(ServerLevel level) {
        List<EntityGlyphid> doomed = new ArrayList<>(GlyphidTracker.glyphids(level));
        for (EntityGlyphid glyphid : doomed) {
            glyphid.discard();
        }
        // The records too, or the next run's population starts with the survivors of the last one -- the
        // same contamination §16.2 found when a marching column walked out of the forceloaded box.
        SimGlyphidRegistry registry = SimGlyphidRegistry.get(level);
        int records = registry.count();
        registry.view().clear();
        registry.setDirty();
        return doomed.size() + records;
    }

    /**
     * Scatter {@code count} dummies across a square of {@code spread} blocks, clearing any previous set.
     *
     * <p>Square rather than ring: the swarm materialises into a square, and the interesting failure is a
     * target sitting just outside the attacker's follow range, which a ring of one radius cannot produce.
     */
    public static int dummies(CommandSourceStack source, int count, double spread, double drift) {
        ServerLevel level = source.getLevel();
        Vec3 center = source.getPosition();
        int removed = clearDummies(level);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            // Deterministic scatter: two coprime strides so a rerun places them identically.
            double x = center.x + ((i * 7) % 31) / 30.0 * spread - spread / 2.0;
            double z = center.z + ((i * 13) % 31) / 30.0 * spread - spread / 2.0;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);

            EntityDebugDummy dummy = ModEntities.DEBUG_DUMMY.get().create(level);
            if (dummy == null) {
                continue;
            }
            dummy.moveTo(x, y, z, 0F, 0F);
            dummy.setDrift((float) drift);
            if (level.addFreshEntity(dummy)) {
                spawned++;
            }
        }

        int placed = spawned;
        source.sendSuccess(() -> Component.literal("Placed " + placed + " dummies across " + (int) spread
                + " blocks, drift " + (int) drift
                + (removed > 0 ? " (cleared " + removed + " first)" : "") + "."), false);
        return placed;
    }

    /**
     * @return how much of the swarm is actually landing hits, which a tick-time number cannot tell you.
     */
    public static int dummyReport(CommandSourceStack source) {
        List<EntityDebugDummy> dummies = liveDummies(source.getLevel());
        int hits = 0;
        float damage = 0.0F;
        int engaged = 0;
        for (EntityDebugDummy dummy : dummies) {
            hits += dummy.hits();
            damage += dummy.damageTaken();
            if (dummy.hits() > 0) {
                engaged++;
            }
        }
        int total = hits;
        float dealt = damage;
        int touched = engaged;
        int size = dummies.size();
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "%d dummies, %d engaged, %d hits, %.1f damage", size, touched, total, dealt)), false);
        return 1;
    }

    public static int dummyReset(CommandSourceStack source) {
        List<EntityDebugDummy> dummies = liveDummies(source.getLevel());
        for (EntityDebugDummy dummy : dummies) {
            dummy.resetCounters();
        }
        int size = dummies.size();
        source.sendSuccess(() -> Component.literal("Reset " + size + " dummies."), false);
        return 1;
    }

    public static int dummyClear(CommandSourceStack source) {
        int removed = clearDummies(source.getLevel());
        source.sendSuccess(() -> Component.literal("Removed " + removed + " dummies."), false);
        return 1;
    }

    private static List<EntityDebugDummy> liveDummies(ServerLevel level) {
        List<EntityDebugDummy> found = new ArrayList<>();
        level.getEntities(ModEntities.DEBUG_DUMMY.get(), dummy -> true, found);
        return found;
    }

    private static int clearDummies(ServerLevel level) {
        List<EntityDebugDummy> doomed = liveDummies(level);
        for (EntityDebugDummy dummy : doomed) {
            dummy.discard();
        }
        return doomed.size();
    }

    public static int profile(CommandSourceStack source, boolean on) {
        SwarmProfiler.setEnabled(on);
        warmup = on ? WARMUP_TICKS : 0;
        source.sendSuccess(() -> Component.literal("Swarm profiling " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    /**
     * When set, glyphids skip vanilla's entity-overlap query inside the collision sweep.
     *
     * <p>Exists to measure what that query is worth rather than argue about it. Only {@code Boat} and
     * {@code Shulker} answer yes to {@code canBeCollidedWith}, so for a glyphid the query walks the entity
     * sections over its swept box and returns an empty list every time — but "should be free" and "is free"
     * are different claims, and a toggle lets both be measured against the same world in one server run.
     * Defaults off, so nothing changes until a benchmark asks for it.
     */
    public static boolean skipEntityCollisions = true;

    public static int entityCollisions(CommandSourceStack source, boolean on) {
        skipEntityCollisions = !on;
        source.sendSuccess(() -> Component.literal("Glyphid entity-collision query " + (on ? "on" : "skipped") + "."), false);
        return 1;
    }

    /**
     * When clear, the swarm does not push itself apart at all.
     *
     * <p>Read live, so both arms are the same swarm on the same tick — which matters here more than usual,
     * because what separation is worth depends entirely on how packed the swarm already is.
     */
    public static boolean separation = true;

    /**
     * When clear, glyphids march by pathfinding to a hop rather than by following the shared field.
     *
     * <p>Read live, so both arms are the same swarm on the same tick.
     */
    public static boolean flowField = true;

    /**
     * When clear, every glyphid is a real entity and nothing is ever a {@code SimGlyphid}.
     *
     * <p>Read live, and switching it off gives every outstanding record a body back on the next tick, so the
     * two arms are the same swarm rather than two populations that happen to be the same size.
     */
    public static boolean simTier = true;

    /**
     * Point every glyphid in both tiers at one place, and make that place stick.
     *
     * <p>The benchmark needs a swarm that marches, because the sim tier is the approach march and a glyphid
     * standing still is not in it. Left to the game the objective comes from {@link GlyphidSquads}, and that
     * is not usable as a control: squads see only the entity tier, so as the tier promotes and demotes the
     * roster churns, the rally point is recomputed from whichever bug happens to be first, and it moves.
     * Measured, that gave a swarm whose simulated fraction swung between 35% and 81% within a minute and
     * whose two tiers reported speeds a factor of three apart — a benchmark measuring its own scenario
     * drifting rather than anything about the code.
     *
     * <p>The rally is written as well as the task, and that is the load-bearing half. A reform recomputes
     * every objective from the rally, so setting them equal makes reassignment idempotent: the squads still
     * run, still cost what they cost, and hand every glyphid back the destination it already had.
     */
    public static int march(CommandSourceStack source, Vec3 to) {
        ServerLevel level = source.getLevel();
        int x = Mth.floor(to.x);
        int z = Mth.floor(to.z);
        int y = level.hasChunk(x >> 4, z >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                : Mth.floor(to.y);

        int ordered = 0;
        for (EntityGlyphid bug : GlyphidTracker.glyphids(level)) {
            bug.taskX = x;
            bug.taskY = y;
            bug.taskZ = z;
            bug.hasRally = true;
            bug.rallyX = x;
            bug.rallyY = y;
            bug.rallyZ = z;
            bug.setCurrentTask(GlyphidTasks.TASK_FOLLOW, null);
            ordered++;
        }
        for (SimGlyphid sim : SimGlyphidRegistry.get(level).view()) {
            sim.taskX = x;
            sim.taskY = y;
            sim.taskZ = z;
            sim.hasRally = true;
            sim.rallyX = x;
            sim.rallyY = y;
            sim.rallyZ = z;
            sim.task = GlyphidTasks.TASK_FOLLOW;
            ordered++;
        }
        int marching = ordered;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids marching on (%d, %d, %d).", marching, x, y, z)), false);
        return marching;
    }

    /**
     * When clear, the sim pass runs inline on the world thread instead of on a worker.
     *
     * <p>The control arm, and the reason {@link com.wf.wfballistics.entity.glyphid.sim.SimWorldLive} still
     * exists. Both arms read the same prefetched world at the same point in the tick, so the only difference
     * between them is which thread pays — which is what makes the pair a measurement of the move rather than
     * of two different simulations.
     */
    public static boolean simAsync = true;

    public static int simAsync(CommandSourceStack source, boolean on) {
        for (ServerLevel level : source.getServer().getAllLevels()) {
            SimGlyphidRegistry.get(level).await();
        }
        simAsync = on;
        source.sendSuccess(() -> Component.literal("Glyphid sim pass runs "
                + (on ? "on a worker." : "on the world thread.")), false);
        return 1;
    }

    /**
     * Where the sim pass ran, what it cost and how much of that the world thread waited out.
     */
    public static int simThread(CommandSourceStack source) {
        for (String line : SimGlyphidPass.report(source.getLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static int simTier(CommandSourceStack source, boolean on) {
        simTier = on;
        if (!on) {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                SimGlyphidManager.promoteAll(level);
            }
        }
        source.sendSuccess(() -> Component.literal("Glyphid sim tier " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    /**
     * Set the distance inside which a glyphid must be a real entity.
     *
     * <p>Exists because the interesting arms are the extremes as well as the default: at zero nothing is ever
     * a body once it starts marching, which measures what the tier costs, and at a few hundred nothing is
     * ever a record, which measures what it saves.
     */
    public static int simRange(CommandSourceStack source, double blocks) {
        SimGlyphidManager.range = Math.max(0.0, blocks);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Glyphids are entities within %.0f blocks of a player.", SimGlyphidManager.range)), false);
        return 1;
    }

    /**
     * Stand a pretend player somewhere, or clear the one that is there.
     *
     * <p>See {@link SimGlyphidManager#watcher}. The headless harness has nobody logged in, so without this
     * every arm measures a swarm that is entirely records.
     */
    public static int watcher(CommandSourceStack source, @Nullable Vec3 at) {
        SimGlyphidManager.watcher = at == null ? null : new double[]{at.x, at.z};
        source.sendSuccess(() -> Component.literal(at == null
                ? "Bench watcher cleared."
                : String.format(Locale.ROOT, "Bench watcher standing at (%.0f, %.0f).", at.x, at.z)), false);
        return 1;
    }

    /**
     * Count the records in a box.
     *
     * <p>Records are invisible to {@code @e}, which is most of the point of them and a real problem for a
     * benchmark: every question the harness asks about where a swarm is, it asks with an entity selector.
     * This is the same question for the other tier, so "did any of them end up inside the sealed box" is
     * answerable at all.
     */
    public static int simCount(CommandSourceStack source, Vec3 from, Vec3 to) {
        double minX = Math.min(from.x, to.x);
        double maxX = Math.max(from.x, to.x);
        double minY = Math.min(from.y, to.y);
        double maxY = Math.max(from.y, to.y);
        double minZ = Math.min(from.z, to.z);
        double maxZ = Math.max(from.z, to.z);
        int inside = 0;
        for (SimGlyphid sim : SimGlyphidRegistry.get(source.getLevel()).view()) {
            if (sim.x >= minX && sim.x <= maxX && sim.y >= minY && sim.y <= maxY
                    && sim.z >= minZ && sim.z <= maxZ) {
                inside++;
            }
        }
        int found = inside;
        source.sendSuccess(() -> Component.literal(found + " sim glyphids in the box."), false);
        return found;
    }

    /**
     * Set off a standard AEF blast where the command was run.
     *
     * <p>Here rather than in a test because the thing worth checking is that a blast kills <em>both</em>
     * tiers, and the only honest way to ask is to detonate one over a real swarm and count what is left. A
     * sim tier that quietly made a swarm immune to the mod's own explosions would be a exploit that looked
     * exactly like a performance win.
     */
    public static int blast(CommandSourceStack source, float size) {
        Vec3 at = source.getPosition();
        int before = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        new com.wf.wfballistics.aef.ExplosionAEF(source.getLevel(), at.x, at.y, at.z, size)
                .makeStandard().explode();
        int after = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Blast of %.0f at (%.0f, %.0f, %.0f): %d glyphids before, %d after.",
                size, at.x, at.y, at.z, before, after)), false);
        return 1;
    }

    /**
     * How the swarm is split between the tiers, and how fast each half is walking.
     *
     * <p>The speeds are the point. Two tiers that move at different rates deliver a swarm in two waves, and
     * that failure looks exactly like a design decision from the outside — so the calibration constant that
     * ties them together is checked by reading both numbers off the same run rather than by argument.
     */
    public static int tiers(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int bodies = GlyphidTracker.count(level);
        int records = SimGlyphidManager.count(level);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids: %d entities, %d sim (%.0f%% simulated), entities %.3f b/tick, sim %.3f b/tick",
                bodies + records, bodies, records,
                bodies + records == 0 ? 0.0 : 100.0 * records / (bodies + records),
                entitySpeed(level), SimGlyphidManager.meanSpeed(level))), false);
        return 1;
    }

    private static double entitySpeed(ServerLevel level) {
        double total = 0.0;
        int counted = 0;
        for (EntityGlyphid bug : GlyphidTracker.glyphids(level)) {
            double dx = bug.getX() - bug.xOld;
            double dz = bug.getZ() - bug.zOld;
            total += Math.sqrt(dx * dx + dz * dz);
            counted++;
        }
        return counted == 0 ? 0.0 : total / counted;
    }

    public static int flowField(CommandSourceStack source, boolean on) {
        flowField = on;
        if (!on) {
            com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowFields.clear();
        }
        source.sendSuccess(() -> Component.literal("Glyphid flow field " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int flowFields(CommandSourceStack source) {
        for (String line : com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowFields.report(source.getLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static int separation(CommandSourceStack source, boolean on) {
        separation = on;
        source.sendSuccess(() -> Component.literal("Glyphid separation " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    /**
     * How tightly the swarm is packed, which is the thing separation exists to change.
     */
    public static int density(CommandSourceStack source) {
        double[] density = GlyphidSeparation.density(source.getLevel());
        // Both tiers, because the grid the numbers come out of holds both. Counting only the entities was
        // the same mistake as counting only them in the profiler's population.
        int count = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids: %.2fb to the nearest neighbour on average, closest pair %.2fb, %.0f overlapping",
                count, density[0], density[1], density[2])), false);
        return 1;
    }

    /**
     * When set, glyphids spawn with vanilla's {@code MeleeAttackGoal} instead of the brain goal.
     *
     * <p>Read at spawn, so a benchmark switches arms by re-materialising the swarm — which every run does
     * anyway. Kept past the measurement it was written for because a melee goal is exactly the kind of thing
     * that quietly regresses, and this makes checking it a two-command job rather than a git bisect.
     */
    public static boolean vanillaMeleeGoal;

    /**
     * When set, a glyphid may only start a path search on its own slot: one per entity per repath interval.
     *
     * <p>Read live rather than at spawn, so the two arms are the same swarm on the same tick.
     */
    public static boolean staggerSearches = true;

    /**
     * Charge straight at a target that is close and visible, instead of pathfinding to it.
     */
    public static boolean chargeMelee = true;

    /**
     * Share one A* between glyphids searching the same route, via {@link
     * com.wf.wfballistics.entity.glyphid.ai.GlyphidPathCache}.
     */
    public static boolean sharedPaths = true;

    /**
     * Restore vanilla entity-vs-entity pushing for glyphids.
     *
     * <p>Only exists so that third-party collision optimisations can be measured against something. With the
     * shipping behaviour there is nothing left for them to optimise -- glyphids do not push each other at all
     * -- which would read as "the mod does nothing" when the honest statement is "we already deleted the work
     * it speeds up".
     */
    public static boolean vanillaPush;

    public static int charge(CommandSourceStack source, boolean on) {
        chargeMelee = on;
        source.sendSuccess(() -> Component.literal("Melee charge " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int sharedPaths(CommandSourceStack source, boolean on) {
        sharedPaths = on;
        com.wf.wfballistics.entity.glyphid.ai.GlyphidPathCache.clear();
        source.sendSuccess(() -> Component.literal("Shared paths " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int push(CommandSourceStack source, boolean vanilla) {
        vanillaPush = vanilla;
        source.sendSuccess(() -> Component.literal("Glyphid push: " + (vanilla ? "vanilla" : "skipped") + "."), false);
        return 1;
    }

    /**
     * Resize vanilla's shared path-type cache on every loaded level, live.
     */
    public static int pathCache(CommandSourceStack source, double megabytes) {
        int entries = PathTypeCacheSize.setMegabytes(megabytes);
        int levels = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (level.getPathTypeCache() instanceof ResizablePathTypeCache resizable) {
                resizable.wfballistics$resize(entries);
                levels++;
            }
        }
        int touched = levels;
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Path-type cache: %d entries (%.2f MB) across %d level(s).",
                entries, PathTypeCacheSize.megabytesFor(entries), touched)), false);
        return 1;
    }

    public static int stagger(CommandSourceStack source, boolean on) {
        staggerSearches = on;
        source.sendSuccess(() -> Component.literal("Path-search stagger " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int meleeGoal(CommandSourceStack source, boolean vanilla) {
        vanillaMeleeGoal = vanilla;
        source.sendSuccess(() -> Component.literal("Glyphids will spawn with the "
                + (vanilla ? "vanilla" : "glyphid") + " melee goal."), false);
        return 1;
    }

    public static int report(CommandSourceStack source) {
        if (warmup > 0) {
            int remaining = warmup;
            source.sendSuccess(() -> Component.literal("Still warming up, " + remaining + " ticks to go."), false);
            return 0;
        }
        for (String line : SwarmProfiler.report()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static int reset(CommandSourceStack source) {
        SwarmProfiler.reset();
        warmup = WARMUP_TICKS;
        source.sendSuccess(() -> Component.literal("Window cleared, re-warming."), false);
        return 1;
    }

    /**
     * Close the profiler's tick. Driven from the server tick rather than the level tick so that one game
     * tick is one sample even with several dimensions loaded.
     */
    /**
     * Bytes the server thread has allocated, or -1 where the JVM will not say.
     *
     * <p>Worth having because "is it allocation or is it work?" is otherwise unanswerable from timings alone:
     * garbage does not show up where it is created, it shows up later as a GC pause on some unrelated tick,
     * which is exactly the shape of an unexplained p95.
     */
    private static long lastAllocated = -1L;

    private static long threadAllocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sun) {
                return sun.getCurrentThreadAllocatedBytes();
            }
        } catch (Throwable ignored) {
            // Not a HotSpot JVM, or the extension is unavailable: allocation simply goes unreported.
        }
        return -1L;
    }

    public static void tick(MinecraftServer server) {
        if (!SwarmProfiler.enabled()) {
            lastAllocated = -1L;
            return;
        }
        long allocated = threadAllocatedBytes();
        if (allocated >= 0L && lastAllocated >= 0L) {
            SwarmProfiler.count(SwarmProfiler.Counter.BYTES, allocated - lastAllocated);
        }
        lastAllocated = allocated;

        if (warmup > 0) {
            warmup--;
            SwarmProfiler.reset();
            return;
        }
        int population = 0;
        for (ServerLevel level : server.getAllLevels()) {
            // Both tiers. Counting only the entities would make a swarm that moved half of itself into
            // records look like a swarm that halved -- and the per-glyphid figure derived from it would be
            // twice what it is.
            population += GlyphidTracker.count(level) + SimGlyphidManager.count(level);
        }
        SwarmProfiler.sample(population);
        SwarmProfiler.endTick();
    }

    /**
     * @return a one-line summary for a status command.
     */
    public static String status(ServerLevel level) {
        return GlyphidTracker.count(level) + " glyphids, profiling "
                + (SwarmProfiler.enabled() ? "on (" + SwarmProfiler.samples() + " ticks sampled)" : "off");
    }
}
