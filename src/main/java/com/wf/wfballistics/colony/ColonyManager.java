package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTracker;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphid;
import com.wf.wfballistics.entity.glyphid.sim.SimGlyphidRegistry;
import com.wf.wfballistics.industry.IndustryCluster;
import com.wf.wfballistics.industry.IndustryClusters;
import com.wf.wfballistics.industry.IndustryRegistry;
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

/**
 * Runs the colonies: they grow, they take offence, they found new nests, and eventually they march.
 *
 * <p>Everything here reads colony, warband and industry-cell data and nothing else, which is what makes it
 * cheap enough to run for a whole world's worth of colonies whether or not anything is loaded. The one
 * exception is materialisation, which touches the world and therefore only ever happens on the server
 * thread as a chunk loads.
 *
 * <p>Colonies are staggered across {@link ColonyConfig#colonyTickInterval()} by id, so the per-tick cost is
 * the colony count divided by the interval rather than the colony count.
 */
public final class ColonyManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A warband that has not arrived in this many ticks is written off, so a target that became
     * unreachable cannot leak records forever.
     */
    public static final int WARBAND_MAX_AGE = 72_000;

    /**
     * Builds a nest's blocks once its chunk is loaded and the ground height is known. Kept pluggable — it was
     * a hook before the hive port landed, and it stays one because the simulation genuinely does not care
     * whether a colony has blocks: with no builder installed, colonies materialise as data and behave
     * identically. {@link GlyphidNest} is the one that ships.
     */
    public interface NestBuilder {
        void build(ServerLevel level, Colony colony);
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
        // Ahead of the empty check on purpose: evolution has to keep rising while a player is building the
        // industry that will eventually attract the first colony, not start from zero once one exists.
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
            registry.setDirty();
        }
    }

    /**
     * Run {@code rounds} colony updates for every colony, ignoring the stagger, plus the matching warband
     * movement. Exists so the simulation can be exercised in seconds instead of hours: a strike cycle is
     * otherwise tens of minutes of real time, which is not a test.
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
            }
            // Warbands move every tick while colonies update every `interval` ticks, so one round of
            // colony time is `interval` rounds of warband time. Advancing them once per round instead
            // would under-report travel by that factor and make every strike look far slower than it is.
            for (int step = 0; step < interval; step++) {
                tickWarbands(level, registry);
            }
        }
        registry.setDirty();
    }

    /**
     * Count the bodies this colony has standing, so its numbers cannot regrow around them.
     *
     * <p>Recounted from the world rather than tallied as defenders hatch and die, because a tally is a second
     * copy of the truth and this one would have to survive a chunk unloading, a save, a record round trip and
     * a {@code /kill}. Counting is cheap enough not to need the risk: it runs on the colony's own stagger, and
     * only when the nest is somewhere entities are actually ticking — a nest nobody is near cannot have lost a
     * defender since the last count, because nothing there is alive to lose one.
     *
     * <p>Level-wide rather than a box around the nest, so a defender that chased somebody over the hill is
     * still this colony's. The scan is the loaded swarm, once per colony per
     * {@link ColonyConfig#colonyTickInterval()}, and only for nests that are loaded at all.
     */
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

    /**
     * Send a warband at the loudest base this colony can be bothered with, spending the population to do it.
     */
    private static void strike(ServerLevel level, ColonyRegistry registry, Colony colony) {
        IndustryCluster target = IndustryClusters.nearest(level, colony.x, colony.z);
        if (target == null) {
            // Nothing worth attacking yet. Hold the aggression rather than discarding it: the colony is
            // still annoyed, it just has nowhere to take it.
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
        LOGGER.debug("[wfballistics] {} dispatched {}", colony, warband);
    }

    /**
     * Found a new colony further out. Direction is away from world spawn, so the frontier pushes outward
     * and the new nest is at least as strong as its parent rather than weaker.
     */
    private static void expand(ServerLevel level, ColonyRegistry registry, Colony colony) {
        if (registry.colonies().size() >= ColonyConfig.maxColonies()) {
            return;
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

        colony.expansionCooldown = ColonyConfig.expansionCooldownTicks();

        // Crowding rather than a bare "is anything here" test: expansion should thicken a region up to a
        // density and then stop, instead of every colony leapfrogging outward and the frontier running away.
        if (registry.countWithin(newX, newZ, ColonyConfig.crowdingRadius()) >= ColonyConfig.crowdingLimit()) {
            return;
        }
        Colony child = found(level, registry, newX, newZ);
        if (child != null) {
            colony.population -= ColonyConfig.expansionPopulation() * 0.5;
            LOGGER.debug("[wfballistics] {} founded {}", colony, child);
        }
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
     * Found a colony on ground somebody is standing on: resolve its height and build it in the same step,
     * rather than waiting for {@link #onChunkLoaded} to notice a chunk that is already loaded.
     *
     * <p>This is what a scout does when it settles, and it is the only path that creates a colony from
     * inside the world rather than from the simulation.
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
        LOGGER.debug("[wfballistics] a scout settled {}", colony);
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
                LOGGER.debug("[wfballistics] {} reached its target", warband);
            }
            // An arrived warband is not removed: it waits on the target for somebody to turn up, and
            // WarbandMaterialiser turns it into glyphids when they do. Only age retires it, so a target
            // that is never visited cannot leak a record forever.
            if (warband.age > WARBAND_MAX_AGE) {
                expired.add(warband);
            }
        }

        for (Warband warband : expired) {
            registry.remove(warband);
            LOGGER.debug("[wfballistics] {} gave up", warband);
        }
        if (!expired.isEmpty()) {
            registry.setDirty();
        }
    }

    // --- materialisation ---

    /**
     * Resolve and build any colony whose chunk has just loaded.
     *
     * <p>The ground height is sampled here rather than at founding, because a colony founded in an unloaded
     * chunk has no terrain to measure against and guessing would put nests inside hills.
     */
    public static void onChunkLoaded(ServerLevel level, ChunkAccess chunk) {
        ColonyRegistry registry = ColonyRegistry.get(level);
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
            }
        }
    }
}
