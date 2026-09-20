package com.wf.wflib.debug;

import com.wf.wflib.ModEntities;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidCaste;
import com.wf.wflib.entity.glyphid.GlyphidSeparation;
import com.wf.wflib.entity.glyphid.GlyphidTasks;
import com.wf.wflib.entity.glyphid.GlyphidTracker;
import com.wf.wflib.entity.glyphid.sim.SimGlyphid;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidManager;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidPass;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidRegistry;
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

/** Spawns a glyphid swarm on demand and reports what it costs. */
public final class SwarmBench {

    /** Ticks to let the swarm settle into steady-state before the window is worth reading. */
    public static final int WARMUP_TICKS = 60;

    /**
     * Chunks of margin forceloaded around the ring, so bugs that wander outward keep ticking.
     */
    private static final int ARENA_MARGIN_CHUNKS = 2;

    private static int warmup;

    /** The arena's forced chunks, held so they can be released again. */
    private static final Set<ChunkPos> FORCED = new HashSet<>();
    private static @Nullable ServerLevel arenaLevel;

    private SwarmBench() {
    }

    /**
     * Spawn {@code count} glyphids in a ring around the source, clearing any previous run first so two benchmarks
     * never overlap.
     */
    public static int spawn(CommandSourceStack source, int count, double radius) {
        return spawn(source, count, radius, GlyphidCaste.GRUNT);
    }

    /** As above, of one named caste. */
    public static int spawn(CommandSourceStack source, int count, double radius, GlyphidCaste caste) {
        return spawn(source, count, radius, caste, source.getPosition(), radius, true, true);
    }

    /**
     * Spawn somewhere other than where the command was run, at exactly the height given, forcing chunks out to
     * {@code forceRadius}.
     */
    public static int spawnAt(CommandSourceStack source, int count, double radius, GlyphidCaste caste,
                              Vec3 center, double forceRadius) {
        return spawn(source, count, radius, caste, center, forceRadius, false, true);
    }

    /** Add to the swarm that is already standing rather than replacing it. */
    public static int reinforce(CommandSourceStack source, int count, double radius, GlyphidCaste caste,
                                Vec3 center, double forceRadius) {
        return spawn(source, count, radius, caste, center, forceRadius, false, false);
    }

    private static int spawn(CommandSourceStack source, int count, double radius, GlyphidCaste caste,
                             Vec3 center, double forceRadius, boolean sampleHeight, boolean replace) {
        ServerLevel level = source.getLevel();

        int removed = replace ? clearLevel(level) : 0;
        if (replace) {
            releaseArena();
        }
        forceArena(level, center, forceRadius);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            // Spiral outward slightly so a large count doesn't stack every bug on one circle.
            double r = radius * (0.6 + 0.4 * ((double) i / Math.max(1, count)));
            double x = center.x + Math.cos(angle) * r;
            double z = center.z + Math.sin(angle) * r;
            int y = sampleHeight
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z)
                    : Mth.floor(center.y);

            EntityGlyphid glyphid = caste.type().create(level);
            if (glyphid == null) {
                continue;
            }
            glyphid.moveTo(x, y, z, level.random.nextFloat() * 360F, 0F);
            glyphid.setPersistenceRequired();
            if (level.addFreshEntity(glyphid)) {
                spawned++;
            }
        }

        SwarmProfiler.setEnabled(true);
        if (replace) {
            GlyphidDeaths.setEnabled(true);
        }
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

    /** Forceload the ring and its margin. */
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

    /** Remove every glyphid and hand the arena's chunks back. */
    public static int clear(CommandSourceStack source) {
        int removed = clearLevel(source.getLevel());
        releaseArena();
        SwarmProfiler.setEnabled(false);
        GlyphidDeaths.setEnabled(false);
        source.sendSuccess(() -> Component.literal(
                "Removed " + removed + " glyphids, arena released, profiling off."), false);
        return removed;
    }

    private static int clearLevel(ServerLevel level) {
        List<EntityGlyphid> doomed = new ArrayList<>(GlyphidTracker.glyphids(level));
        for (EntityGlyphid glyphid : doomed) {
            glyphid.discard();
        }
        SimGlyphidRegistry registry = SimGlyphidRegistry.get(level);
        int records = registry.count();
        registry.view().clear();
        registry.setDirty();
        return doomed.size() + records;
    }

    /** Scatter {@code count} dummies across a square of {@code spread} blocks, clearing any previous set. */
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

    /** When set, glyphids skip vanilla's entity-overlap query inside the collision sweep. */
    public static boolean skipEntityCollisions = true;

    public static int entityCollisions(CommandSourceStack source, boolean on) {
        skipEntityCollisions = !on;
        source.sendSuccess(() -> Component.literal("Glyphid entity-collision query " + (on ? "on" : "skipped") + "."), false);
        return 1;
    }

    /** When clear, the swarm does not push itself apart at all. */
    public static boolean separation = true;

    /** When clear, glyphids march by pathfinding to a hop rather than by following the shared field. */
    public static boolean flowField = true;

    /**
     * When clear, no gap is ever proposed as a crossing and any bridge already standing is dissolved on the next
     * tick, so a swarm walks whatever route it would have found without one.
     */
    public static boolean bridges = true;

    /** When clear, every glyphid is a real entity and nothing is ever a {@code SimGlyphid}. */
    public static boolean simTier = true;

    /** Point every glyphid in both tiers at one place, and make that place stick. */
    public static int march(CommandSourceStack source, Vec3 to) {
        return march(source, to, true);
    }

    /**
     * @param sampleHeight take the objective's height from the heightmap rather than from {@code to}. Right
     *      for a destination on open ground, and wrong for every roofed one: the heightmap
     *      answers with the roof, so a swarm ordered into a maze is ordered onto the top of it
     */
    public static int march(CommandSourceStack source, Vec3 to, boolean sampleHeight) {
        ServerLevel level = source.getLevel();
        int x = Mth.floor(to.x);
        int z = Mth.floor(to.z);
        int y = sampleHeight && level.hasChunk(x >> 4, z >> 4)
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

    /** When clear, the sim pass runs inline on the world thread instead of on a worker. */
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

    /** Set the distance inside which a glyphid must be a real entity. */
    public static int simRange(CommandSourceStack source, double blocks) {
        SimGlyphidManager.range = Math.max(0.0, blocks);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Glyphids are entities within %.0f blocks of a player.", SimGlyphidManager.range)), false);
        return 1;
    }

    /** Stand a pretend player somewhere, or clear the one that is there. */
    public static int watcher(CommandSourceStack source, @Nullable Vec3 at) {
        SimGlyphidManager.watcher = at == null ? null : new double[]{at.x, at.z};
        source.sendSuccess(() -> Component.literal(at == null
                ? "Bench watcher cleared."
                : String.format(Locale.ROOT, "Bench watcher standing at (%.0f, %.0f).", at.x, at.z)), false);
        return 1;
    }

    /** Count the records in a box. */
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

    /** Set off a standard AEF blast where the command was run. */
    public static int blast(CommandSourceStack source, float size) {
        Vec3 at = source.getPosition();
        int before = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        new com.wf.wflib.aef.ExplosionAEF(source.getLevel(), at.x, at.y, at.z, size)
                .makeStandard().explode();
        int after = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Blast of %.0f at (%.0f, %.0f, %.0f): %d glyphids before, %d after.",
                size, at.x, at.y, at.z, before, after)), false);
        return 1;
    }

    /** How the swarm is split between the tiers, and how fast each half is walking. */
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
            com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields.clear();
        }
        source.sendSuccess(() -> Component.literal("Glyphid flow field " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int flowFields(CommandSourceStack source) {
        for (String line : com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields.report(source.getLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static int bridges(CommandSourceStack source, boolean on) {
        bridges = on;
        source.sendSuccess(() -> Component.literal("Glyphid bridges " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int bridgeReport(CommandSourceStack source) {
        for (String line : com.wf.wflib.entity.glyphid.nav.GlyphidBridges.report(source.getLevel())) {
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
     * What the swarm is doing, rather than what it costs.
     *
     * @param arrivalRadius how near the objective counts as having arrived
     */
    public static int census(CommandSourceStack source, double arrivalRadius) {
        for (String line : GlyphidCensus.report(source.getLevel(), arrivalRadius)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(
                GlyphidCensus.line(source.getLevel(), arrivalRadius)), false);
        return 1;
    }

    /**
     * Whether the swarm divided, and into what. See {@link GlyphidSquadCensus}.
     */
    public static int squads(CommandSourceStack source) {
        for (String line : GlyphidSquadCensus.report(source.getLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(GlyphidSquadCensus.line(source.getLevel())), false);
        return 1;
    }

    /**
     * How tightly the swarm is packed, which is the thing separation exists to change.
     */
    public static int density(CommandSourceStack source) {
        double[] density = GlyphidSeparation.density(source.getLevel());
        int count = GlyphidTracker.count(source.getLevel()) + SimGlyphidManager.count(source.getLevel());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d glyphids: %.2fb to the nearest neighbour on average, closest pair %.2fb, %.0f overlapping",
                count, density[0], density[1], density[2])), false);
        return 1;
    }

    /** When set, glyphids spawn with vanilla's {@code MeleeAttackGoal} instead of the brain goal. */
    public static boolean vanillaMeleeGoal;

    /** When set, a glyphid may only start a path search on its own slot: one per entity per repath interval. */
    public static boolean staggerSearches = true;

    /**
     * Charge straight at a target that is close and visible, instead of pathfinding to it.
     */
    public static boolean chargeMelee = true;

    /**
     * Share one A* between glyphids searching the same route, via {@link
     * com.wf.wflib.entity.glyphid.ai.GlyphidPathCache}.
     */
    public static boolean sharedPaths = true;

    /** Restore vanilla entity-vs-entity pushing for glyphids. */
    public static boolean vanillaPush;

    public static int charge(CommandSourceStack source, boolean on) {
        chargeMelee = on;
        source.sendSuccess(() -> Component.literal("Melee charge " + (on ? "on" : "off") + "."), false);
        return 1;
    }

    public static int sharedPaths(CommandSourceStack source, boolean on) {
        sharedPaths = on;
        com.wf.wflib.entity.glyphid.ai.GlyphidPathCache.clear();
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
                resizable.wflib$resize(entries);
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

    /** Close the profiler's tick. */
    /** Bytes the server thread has allocated, or -1 where the JVM will not say. */
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
