package com.wf.wflib.colony;

import com.mojang.logging.LogUtils;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.GlyphidTracker;
import com.wf.wflib.entity.glyphid.sim.SimGlyphid;
import com.wf.wflib.entity.glyphid.sim.SimGlyphidRegistry;
import com.wf.wflib.industry.IndustryCluster;
import com.wf.wflib.industry.IndustryClusters;
import com.wf.wflib.industry.IndustryRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runs the colonies: they grow, they take offence, they found new nests, and eventually they march. */
public final class ColonyManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A warband that has not arrived in this long is written off, so an unreachable target cannot leak. */
    public static final int WARBAND_MAX_AGE = 72_000;

    /** Builds a nest's blocks once its chunk is loaded and the ground height is known. */
    public interface NestBuilder {
        void build(ServerLevel level, Colony colony);

        /** Stamp only the cells grown since the last build. */
        void growCells(ServerLevel level, Colony colony);
    }

    /**
     * What one expansion attempt did, including the reason for a refusal so the debug command can report it rather
     * than guess it from the config.
     *
     * @param child the colony that resulted: a new one for an outpost, the parent itself for a bud
     */
    public record Expansion(Kind kind, @Nullable Colony child, String note) {

        public enum Kind {
            /** Nowhere to put an outpost and no room to bud. */
            NOTHING,
            /** Another mound on this colony's own cluster. */
            BUD,
            /** A separate colony, {@link ColonyConfig#expansionRange()} blocks further out. */
            OUTPOST
        }
    }

    private static @Nullable NestBuilder nestBuilder;

    private ColonyManager() {
    }

    public static void setNestBuilder(@Nullable NestBuilder builder) {
        nestBuilder = builder;
    }

    // --- simulation ---

    public static void tick(ServerLevel level) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        Evolution.tick(level, registry);

        if (registry.colonies().isEmpty() && registry.warbands().isEmpty()) {
            return;
        }
        tickColonies(level, registry);
        tickWarbands(level, registry);
        WarbandMaterialiser.tick(level);
    }

    private static void tickColonies(ServerLevel level, ColonyRegistry registry) {
        int interval = ColonyConfig.colonyTickInterval();
        long now = level.getGameTime();
        IndustryRegistry industry = IndustryRegistry.get(level);

        double strength = Evolution.strength(registry.evolution());

        // Copied because expansion appends to the list while we walk it.
        List<Colony> snapshot = new ArrayList<>(registry.colonies());
        for (Colony colony : snapshot) {
            if (Math.floorMod(colony.id.hashCode() + now, interval) != 0) {
                continue;
            }
            double seconds = interval / 20.0;

            recountGarrison(level, colony);
            if (colony.population < colony.room()) {
                colony.population = Math.min(colony.room(),
                        colony.population + colony.growthPerSecond() * strength * seconds);
            }

            int pressure = industry.pressureWithin(colony.x, colony.z, ColonyConfig.provocationRadius());
            if (pressure > 0) {
                colony.aggression += pressure * ColonyConfig.aggressionPerPressure() * seconds;
            }

            if (colony.expansionCooldown > 0) {
                colony.expansionCooldown -= interval;
            }

            if (colony.canStrike()) {
                strike(level, registry, colony);
            } else if (colony.canExpand()) {
                expand(level, registry, colony);
            }
            materialiseCells(level, colony);
            registry.setDirty();
        }
    }

    /** Give a colony's newly budded cells their blocks, if anybody is there to see them. */
    static void materialiseCells(ServerLevel level, Colony colony) {
        if (nestBuilder == null || !colony.built || colony.builtBuds() >= colony.buds) {
            return;
        }
        if (level.getChunkSource().getChunkNow(colony.x >> 4, colony.z >> 4) == null) {
            return;
        }
        nestBuilder.growCells(level, colony);
    }

    /**
     * Run {@code rounds} colony updates for every colony, ignoring the stagger, plus the matching warband movement,
     * so a strike cycle can be exercised in seconds rather than tens of minutes.
     */
    public static void fastForward(ServerLevel level, int rounds) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        int interval = ColonyConfig.colonyTickInterval();
        IndustryRegistry industry = IndustryRegistry.get(level);
        double seconds = interval / 20.0;
        double strength = Evolution.strength(registry.evolution());

        for (int round = 0; round < rounds; round++) {
            for (Colony colony : new ArrayList<>(registry.colonies())) {
                if (colony.population < colony.room()) {
                    colony.population = Math.min(colony.room(),
                            colony.population + colony.growthPerSecond() * strength * seconds);
                }
                int pressure = industry.pressureWithin(colony.x, colony.z, ColonyConfig.provocationRadius());
                if (pressure > 0) {
                    colony.aggression += pressure * ColonyConfig.aggressionPerPressure() * seconds;
                }
                if (colony.expansionCooldown > 0) {
                    colony.expansionCooldown -= interval;
                }
                if (colony.canStrike()) {
                    strike(level, registry, colony);
                } else if (colony.canExpand()) {
                    expand(level, registry, colony);
                }
                materialiseCells(level, colony);
            }
            for (int step = 0; step < interval; step++) {
                tickWarbands(level, registry);
            }
        }
        registry.setDirty();
    }

    /** Count the bodies this colony has standing, so its numbers cannot regrow around them. */
    private static void recountGarrison(ServerLevel level, Colony colony) {
        if (!colony.built || !colony.hasResolvedY() || !level.isPositionEntityTicking(colony.pos())) {
            return;
        }
        int standing = 0;
        for (EntityGlyphid bug : GlyphidTracker.glyphids(level)) {
            if (bug.garrison && bug.homeX == colony.x && bug.homeZ == colony.z && bug.isAlive()) {
                standing++;
            }
        }
        for (SimGlyphid sim : SimGlyphidRegistry.get(level).view()) {
            if (sim.garrison && sim.homeX == colony.x && sim.homeZ == colony.z) {
                standing++;
            }
        }
        colony.garrison = standing;
    }

    /** Send a warband at the loudest base this colony can be bothered with, spending the population to do it. */
    private static void strike(ServerLevel level, ColonyRegistry registry, Colony colony) {
        IndustryCluster target = IndustryClusters.nearest(level, colony.x, colony.z);
        if (target == null) {
            // Nothing worth attacking yet. Hold the aggression rather than discarding it.
            return;
        }

        int mustered = (int) Math.round(colony.warbandSize() * Evolution.strength(registry.evolution()));
        int size = Math.min(mustered, (int) colony.population);
        if (size <= 0) {
            return;
        }
        colony.population -= size;
        colony.aggression = 0.0;

        Warband warband = new Warband(UUID.randomUUID(), colony.id, colony.x, colony.z,
                target.centerX(), target.centerZ(), size, colony.tier);
        warband.flying = level.random.nextDouble() < ColonyConfig.flyingChance(colony.tier);
        registry.add(warband);
        LOGGER.debug("[wflib] {} dispatched {}", colony, warband);
    }

    /**
     * Grow the colony, one way or the other: a <b>bud</b> welds another mound onto its own cluster (cheap, quick,
     * same record), an <b>outpost</b> founds a separate colony {@link ColonyConfig#expansionRange()} blocks out
     * (expensive, and what moves the frontier).
     */
    static Expansion expand(ServerLevel level, ColonyRegistry registry, Colony colony) {
        Expansion result = attempt(level, registry, colony);
        colony.expansionCooldown = result.kind() == Expansion.Kind.BUD
                ? ColonyConfig.budCooldownTicks()
                : ColonyConfig.expansionCooldownTicks();
        return result;
    }

    private static Expansion attempt(ServerLevel level, ColonyRegistry registry, Colony colony) {
        boolean canBud = colony.canBud() && colony.population >= ColonyConfig.budPopulation();
        boolean canFound = colony.population >= ColonyConfig.expansionPopulation();

        if (canBud && (!canFound || level.random.nextDouble() < ColonyConfig.budChance())) {
            return bud(colony);
        }
        if (!canFound) {
            return new Expansion(Expansion.Kind.NOTHING, null, colony.canBud()
                    ? String.format("%.0f population; a bud costs %d and an outpost %d",
                            colony.population, ColonyConfig.budPopulation(),
                            ColonyConfig.expansionPopulation())
                    : String.format("%.0f population, and all %d cells already grown; an outpost costs %d",
                            colony.population, colony.budCap() + 1, ColonyConfig.expansionPopulation()));
        }

        Expansion outpost = outpost(level, registry, colony);
        if (outpost.kind() != Expansion.Kind.NOTHING) {
            return outpost;
        }
        // Nowhere to put one. Thicken instead, rather than doing nothing for another ten minutes.
        return canBud ? bud(colony) : outpost;
    }

    /** Weld another mound onto the colony where it already stands. */
    private static Expansion bud(Colony colony) {
        colony.buds++;
        colony.population -= ColonyConfig.budPopulation() * 0.5;
        return new Expansion(Expansion.Kind.BUD, colony,
                String.format("grew cell %d of %d", colony.buds + 1, colony.budCap() + 1));
    }

    /** Found a new colony further out. */
    private static Expansion outpost(ServerLevel level, ColonyRegistry registry, Colony colony) {
        if (registry.colonies().size() >= ColonyConfig.maxColonies()) {
            return new Expansion(Expansion.Kind.NOTHING, null,
                    "the dimension is at its " + ColonyConfig.maxColonies() + "-colony ceiling");
        }
        BlockPos spawn = level.getSharedSpawnPos();
        double dx = colony.x - spawn.getX();
        double dz = colony.z - spawn.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }

        // Outward, with a spread so a lineage fans out into a frontier instead of a straight line.
        double spread = (level.random.nextDouble() - 0.5) * Math.PI * 0.75;
        double cos = Math.cos(spread);
        double sin = Math.sin(spread);
        double dirX = (dx / length) * cos - (dz / length) * sin;
        double dirZ = (dx / length) * sin + (dz / length) * cos;

        int range = ColonyConfig.expansionRange();
        int newX = (int) Math.round(colony.x + dirX * range);
        int newZ = (int) Math.round(colony.z + dirZ * range);

        int neighbours = registry.countWithin(newX, newZ, ColonyConfig.crowdingRadius());
        if (neighbours >= ColonyConfig.crowdingLimit()) {
            return new Expansion(Expansion.Kind.NOTHING, null, String.format(
                    "%d colonies already sit within %d blocks of (%d, %d)",
                    neighbours, ColonyConfig.crowdingRadius(), newX, newZ));
        }
        Colony child = found(level, registry, newX, newZ);
        if (child == null) {
            return new Expansion(Expansion.Kind.NOTHING, null, String.format(
                    "(%d, %d) is inside the safe radius or past the frontier", newX, newZ));
        }
        colony.population -= ColonyConfig.expansionPopulation() * 0.5;
        LOGGER.debug("[wflib] {} founded {}", colony, child);
        return new Expansion(Expansion.Kind.OUTPOST, child,
                String.format("founded an outpost %d blocks out", range));
    }

    /**
     * Create a colony at a position, if that position is allowed to have one.
     *
     * @return the new colony, or null inside the safe radius
     */
    public static @Nullable Colony found(ServerLevel level, ColonyRegistry registry, int x, int z) {
        BlockPos spawn = level.getSharedSpawnPos();
        double distance = Colony.distanceFromSpawn(spawn, x, z);
        int tier = Colony.tierAtDistance(distance);
        if (tier < 0 || distance > ColonyConfig.frontierDistance()) {
            return null;
        }
        if (registry.colonies().size() >= ColonyConfig.maxColonies()) {
            return null;
        }
        Colony colony = new Colony(UUID.randomUUID(), x, Colony.Y_UNRESOLVED, z, tier);
        colony.population = colony.populationCap() * 0.25;
        registry.add(colony);
        return colony;
    }

    /**
     * Found a colony on ground somebody is standing on, resolving its height and building it in one step.
     *
     * @return the new colony, or null if this position is not allowed one
     */
    public static @Nullable Colony settle(ServerLevel level, int x, int y, int z) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        Colony colony = found(level, registry, x, z);
        if (colony == null) {
            return null;
        }

        colony.y = y;
        if (nestBuilder != null) {
            nestBuilder.build(level, colony);
            colony.built = true;
        }
        registry.setDirty();
        LOGGER.debug("[wflib] a scout settled {}", colony);
        return colony;
    }

    private static void tickWarbands(ServerLevel level, ColonyRegistry registry) {
        if (registry.warbands().isEmpty()) {
            return;
        }
        double speed = ColonyConfig.warbandSpeed();
        List<Warband> expired = new ArrayList<>();

        for (Warband warband : registry.warbands()) {
            boolean wasArrived = warband.arrived;
            double own = warband.flying ? speed * ColonyConfig.flyingSpeedFactor() : speed;
            if (warband.advance(own) && !wasArrived) {
                LOGGER.debug("[wflib] {} reached its target", warband);
            }
            // An arrived warband waits on the target for somebody to turn up; only age retires it.
            if (warband.age > WARBAND_MAX_AGE) {
                expired.add(warband);
            }
        }

        for (Warband warband : expired) {
            registry.remove(warband);
            LOGGER.debug("[wflib] {} gave up", warband);
        }
        if (!expired.isEmpty()) {
            registry.setDirty();
        }
    }

    // --- materialisation ---

    /** Resolve and build any colony whose chunk has just loaded. */
    public static void onChunkLoaded(ServerLevel level, ChunkAccess chunk) {
        ColonyRegistry registry = ColonyRegistry.get(level);
        // Ahead of the empty check: this is the step that creates the first colony.
        ColonySeeder.seed(level, registry, chunk);
        if (registry.colonies().isEmpty()) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        for (Colony colony : registry.inChunk(pos)) {
            if (!colony.hasResolvedY()) {
                colony.y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        colony.x & 15, colony.z & 15) + 1;
                registry.setDirty();
            }
            if (!colony.built && nestBuilder != null) {
                nestBuilder.build(level, colony);
                colony.built = true;
                registry.setDirty();
            } else if (colony.built && colony.builtBuds() < colony.buds && nestBuilder != null) {
                // Cells budded while this chunk was unloaded, so a cluster is the size its record says.
                nestBuilder.growCells(level, colony);
                registry.setDirty();
            }
        }
    }
}
