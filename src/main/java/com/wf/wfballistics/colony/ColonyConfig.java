package com.wf.wfballistics.colony;

/**
 * Colony simulation tunables, copied out of {@code WFConfig} on load like {@code IndustryConfig}.
 */
public final class ColonyConfig {

    private static int safeRadius = 512;
    private static int fullStrengthDistance = 12_000;
    private static int maxTier = 4;

    private static double growthPerSecond = 0.35;
    private static int basePopulationCap = 20;
    private static double aggressionPerPressure = 0.02;
    private static double strikeThreshold = 100.0;
    private static int baseWarbandSize = 8;

    private static int expansionPopulation = 25;
    private static int expansionCooldownTicks = 12_000;
    private static int expansionRange = 900;
    private static int colonyTickInterval = 20;
    private static double warbandSpeed = 0.35;
    private static int maxColonies = 250;
    private static int frontierDistance = 24_000;
    private static int provocationRadius = 1_500;
    private static int crowdingRadius = 1_800;
    private static int crowdingLimit = 4;

    private static int materialiseRange = 128;
    private static int materialisePerTick = 4;

    private ColonyConfig() {
    }

    /**
     * No colony may exist within this many blocks of world spawn: the starting area stays quiet, which is
     * what makes moving outward a decision rather than a formality.
     */
    public static int safeRadius() {
        return safeRadius;
    }

    /**
     * Distance from spawn at which colonies reach {@link #maxTier()}. Between the safe radius and here,
     * strength ramps linearly.
     */
    public static int fullStrengthDistance() {
        return fullStrengthDistance;
    }

    public static int maxTier() {
        return maxTier;
    }

    /**
     * Population a colony adds per second at tier 0, before the tier multiplier.
     */
    public static double growthPerSecond() {
        return growthPerSecond;
    }

    /**
     * Population cap at tier 0. Each tier adds another multiple of this.
     */
    public static int basePopulationCap() {
        return basePopulationCap;
    }

    /**
     * Aggression gained per point of nearby industry provocation, per second.
     */
    public static double aggressionPerPressure() {
        return aggressionPerPressure;
    }

    /**
     * Aggression at which a colony with the numbers to spare sends a warband.
     */
    public static double strikeThreshold() {
        return strikeThreshold;
    }

    /**
     * Warband size at tier 0; each tier adds another multiple.
     */
    public static int baseWarbandSize() {
        return baseWarbandSize;
    }

    /**
     * Population a colony must hold before it can afford to found another.
     */
    public static int expansionPopulation() {
        return expansionPopulation;
    }

    public static int expansionCooldownTicks() {
        return expansionCooldownTicks;
    }

    /**
     * How far a new colony is founded from its parent.
     */
    public static int expansionRange() {
        return expansionRange;
    }

    /**
     * Ticks between colony updates. Colonies are staggered across this window, so the per-tick cost is
     * the colony count divided by this rather than the colony count.
     */
    public static int colonyTickInterval() {
        return colonyTickInterval;
    }

    /**
     * Blocks per tick a travelling warband covers.
     */
    public static double warbandSpeed() {
        return warbandSpeed;
    }

    /**
     * Hard ceiling on colonies per level. Expansion is exponential by nature -- every colony founded can
     * found more -- so it needs a stop, not just a cooldown.
     */
    public static int maxColonies() {
        return maxColonies;
    }

    /**
     * No colony may be founded beyond this distance from spawn. Without it a lineage expanding outward
     * walks to the world border and keeps going.
     */
    public static int frontierDistance() {
        return frontierDistance;
    }

    /**
     * How far a colony can smell industry.
     */
    public static int provocationRadius() {
        return provocationRadius;
    }

    /**
     * Radius within which {@link #crowdingLimit()} applies.
     */
    public static int crowdingRadius() {
        return crowdingRadius;
    }

    /**
     * Colonies already within {@link #crowdingRadius()} of a proposed site that will block it. Turns
     * expansion into filling in territory rather than fleeing outward.
     */
    public static int crowdingLimit() {
        return crowdingLimit;
    }

    /**
     * How close a player has to be before a warband stops being a record and becomes glyphids.
     */
    public static int materialiseRange() {
        return materialiseRange;
    }

    /**
     * Bodies placed per level tick, across every warband. Caps the cost of a large warband arriving: it
     * streams in over several ticks instead of spawning hundreds of entities inside one.
     */
    public static int materialisePerTick() {
        return materialisePerTick;
    }

    public static void applyMaterialisation(int range, int perTick) {
        materialiseRange = Math.max(16, range);
        materialisePerTick = Math.max(0, perTick);
    }

    public static void apply(int safe, int fullStrength, int tiers, double growth, int popCap,
                             double aggroPerPressure, double strike, int warbandSize,
                             int expansionPop, int expansionCooldown, int expansionDistance,
                             int tickInterval, double speed, int colonyCap, int frontier,
                             int provocation, int crowdRadius, int crowdLimit) {
        safeRadius = Math.max(0, safe);
        fullStrengthDistance = Math.max(safeRadius + 1, fullStrength);
        maxTier = Math.max(0, tiers);
        growthPerSecond = Math.max(0.0, growth);
        basePopulationCap = Math.max(1, popCap);
        aggressionPerPressure = Math.max(0.0, aggroPerPressure);
        strikeThreshold = Math.max(1.0, strike);
        baseWarbandSize = Math.max(1, warbandSize);
        expansionPopulation = Math.max(1, expansionPop);
        expansionCooldownTicks = Math.max(1, expansionCooldown);
        expansionRange = Math.max(16, expansionDistance);
        colonyTickInterval = Math.max(1, tickInterval);
        warbandSpeed = Math.max(0.01, speed);
        maxColonies = Math.max(0, colonyCap);
        frontierDistance = Math.max(safeRadius + 1, frontier);
        provocationRadius = Math.max(16, provocation);
        crowdingRadius = Math.max(16, crowdRadius);
        crowdingLimit = Math.max(1, crowdLimit);
    }
}
