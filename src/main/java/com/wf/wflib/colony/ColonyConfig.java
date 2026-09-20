package com.wf.wflib.colony;

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

    private static double budChance = 0.55;
    private static int budPopulation = 12;
    private static int budCooldownTicks = 4_800;
    private static int budCapBase = 1;
    private static int budCapPerTier = 1;
    private static double reinforcedEvolution = 0.35;

    private static int colonyTickInterval = 20;
    private static double warbandSpeed = 0.35;
    private static int maxColonies = 250;
    private static int frontierDistance = 24_000;
    private static int provocationRadius = 1_500;
    private static int crowdingRadius = 1_800;
    private static int crowdingLimit = 4;

    private static int garrisonPerChamber = 6;

    private static int naturalSpacing = 512;
    private static double naturalChance = 0.7;

    private static int materialiseRange = 128;
    private static int materialisePerTick = 4;

    private static double flyingChancePerTier = 0.2;
    private static double flyingSpeedFactor = 3.0;

    private static double evolutionTimeFactor = 4.0E-5;
    private static double evolutionPressureFactor = 2.0E-8;
    private static double evolutionStrengthBonus = 0.5;

    private ColonyConfig() {
    }

    /**
     * No colony may exist within this many blocks of world spawn: the starting area stays quiet, which is what
     * makes moving outward a decision rather than a formality.
     */
    public static int safeRadius() {
        return safeRadius;
    }

    /** Distance from spawn at which colonies reach {@link #maxTier()}. */
    public static int fullStrengthDistance() {
        return fullStrengthDistance;
    }

    public static int maxTier() {
        return maxTier;
    }

    /** Population a colony adds per second at tier 0, before the tier multiplier. */
    public static double growthPerSecond() {
        return growthPerSecond;
    }

    /** Population cap at tier 0. Each tier adds another multiple of this. */
    public static int basePopulationCap() {
        return basePopulationCap;
    }

    /** Aggression gained per point of nearby industry provocation, per second. */
    public static double aggressionPerPressure() {
        return aggressionPerPressure;
    }

    /** Aggression at which a colony with the numbers to spare sends a warband. */
    public static double strikeThreshold() {
        return strikeThreshold;
    }

    /** Warband size at tier 0; each tier adds another multiple. */
    public static int baseWarbandSize() {
        return baseWarbandSize;
    }

    /** Population a colony must hold before it can afford to found another. */
    public static int expansionPopulation() {
        return expansionPopulation;
    }

    public static int expansionCooldownTicks() {
        return expansionCooldownTicks;
    }

    /** How far a new colony is founded from its parent. */
    public static int expansionRange() {
        return expansionRange;
    }

    /**
     * Share of expansions that grow the colony in place rather than found one further out, when it can afford
     * either.
     */
    public static double budChance() {
        return budChance;
    }

    /** Population a colony must hold to bud. Cheaper than {@link #expansionPopulation()}: same colony. */
    public static int budPopulation() {
        return budPopulation;
    }

    public static int budCooldownTicks() {
        return budCooldownTicks;
    }

    /** Mounds a tier-0 colony may bud, beyond the one it starts as. */
    public static int budCapBase() {
        return budCapBase;
    }

    /** How many more each tier adds. At the defaults a tier-0 colony reaches two mounds and a tier-4 one six. */
    public static int budCapPerTier() {
        return budCapPerTier;
    }

    /** Evolution at which colonies start laying reinforced flesh. */
    public static double reinforcedEvolution() {
        return reinforcedEvolution;
    }

    /** Ticks between colony updates. Colonies are staggered across the window, so cost is count/interval. */
    public static int colonyTickInterval() {
        return colonyTickInterval;
    }

    /** Blocks per tick a travelling warband covers. */
    public static double warbandSpeed() {
        return warbandSpeed;
    }

    /** Hard ceiling per level. Expansion is exponential, so it needs a stop and not just a cooldown. */
    public static int maxColonies() {
        return maxColonies;
    }

    /** No colony beyond this from spawn, or an expanding lineage walks to the world border and keeps going. */
    public static int frontierDistance() {
        return frontierDistance;
    }

    /** How far a colony can smell industry. */
    public static int provocationRadius() {
        return provocationRadius;
    }

    /**
     * Radius within which {@link #crowdingLimit()} applies.
     */
    public static int crowdingRadius() {
        return crowdingRadius;
    }

    /** Colonies within {@link #crowdingRadius()} that block a site, so expansion fills in rather than flees. */
    public static int crowdingLimit() {
        return crowdingLimit;
    }

    /** Defenders one egg chamber keeps standing, so a nest with its chambers dug out holds none. */
    public static int garrisonPerChamber() {
        return garrisonPerChamber;
    }

    /** Side of the grid cell {@link ColonySeeder} places natural nests on, and so how far apart hives are. */
    public static int naturalSpacing() {
        return naturalSpacing;
    }

    /** Chance a cell has a nest at all, so the frontier is patchy rather than a lattice. */
    public static double naturalChance() {
        return naturalChance;
    }

    /** How close a player has to be before a warband stops being a record and becomes glyphids. */
    public static int materialiseRange() {
        return materialiseRange;
    }

    /** Bodies placed per level tick, so a large warband streams in rather than spawning inside one tick. */
    public static int materialisePerTick() {
        return materialisePerTick;
    }

    /**
     * Chance per tier that a warband is on the wing, so wings are a frontier thing: at the default a tier-0 nest
     * never fields them and a tier-4 one usually does.
     */
    public static double flyingChance(int tier) {
        return Math.min(1.0, flyingChancePerTier * tier);
    }

    /** How much faster a flying warband crosses the map than a walking one. */
    public static double flyingSpeedFactor() {
        return flyingSpeedFactor;
    }

    /** Share of the remaining gap closed per {@link Evolution#INTERVAL} by time alone. */
    public static double evolutionTimeFactor() {
        return evolutionTimeFactor;
    }

    /** The same, per point of standing industry: the term that ties evolution to what was built. */
    public static double evolutionPressureFactor() {
        return evolutionPressureFactor;
    }

    /** What a fully evolved world adds to growth and warband size, as a fraction of the base. */
    public static double evolutionStrengthBonus() {
        return evolutionStrengthBonus;
    }

    public static void applyEvolution(double timeFactor, double pressureFactor, double strengthBonus) {
        evolutionTimeFactor = Math.max(0.0, timeFactor);
        evolutionPressureFactor = Math.max(0.0, pressureFactor);
        evolutionStrengthBonus = Math.max(0.0, strengthBonus);
    }

    public static void applyBudding(double chance, int population, int cooldown, int capBase, int capPerTier,
                                    double reinforcedAt) {
        budChance = Math.max(0.0, Math.min(1.0, chance));
        budPopulation = Math.max(1, population);
        budCooldownTicks = Math.max(1, cooldown);
        budCapBase = Math.max(0, capBase);
        budCapPerTier = Math.max(0, capPerTier);
        reinforcedEvolution = Math.max(0.0, Math.min(1.0, reinforcedAt));
    }

    public static void applySeeding(int spacing, double chance) {
        naturalSpacing = Math.max(0, spacing);
        naturalChance = Math.max(0.0, Math.min(1.0, chance));
    }

    public static void applyMaterialisation(int range, int perTick) {
        materialiseRange = Math.max(16, range);
        materialisePerTick = Math.max(0, perTick);
    }

    public static void applyGarrison(int perChamber) {
        garrisonPerChamber = Math.max(0, perChamber);
    }

    public static void applyFlight(double chancePerTier, double speedFactor) {
        flyingChancePerTier = Math.max(0.0, chancePerTier);
        flyingSpeedFactor = Math.max(1.0, speedFactor);
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
