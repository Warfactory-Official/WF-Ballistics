package com.wf.wfballistics.config;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.colony.ColonyConfig;
import com.wf.wfballistics.industry.IndustryConfig;
import com.wf.wfballistics.industry.IndustryValues;
import com.wf.wfballistics.sim.MissileSimConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server/common config for the tunables most worth adjusting without recompiling: the WarForge integration
 * toggles, the interceptor combat numbers, and default fuel. Register {@link #SPEC} in the mod constructor
 * ({@code context.registerConfig(ModConfig.Type.COMMON, WFConfig.SPEC)}). On (re)load the values are copied
 * into the plain static fields the gameplay code reads (see {@link MissileSimConfig}), so nothing else has to
 * know about the config.
 */
public final class WFConfig {

    public static final ModConfigSpec SPEC;

    // --- WarForge integration ---
    public static final ModConfigSpec.BooleanValue WARFORGE_FACTION_FOF;
    public static final ModConfigSpec.BooleanValue WARFORGE_CLAIM_PROTECTION;
    // --- Interception ---
    public static final ModConfigSpec.DoubleValue INTERCEPT_CHANCE;
    public static final ModConfigSpec.DoubleValue INTERCEPT_CROSSING_FACTOR;
    public static final ModConfigSpec.DoubleValue INTERCEPTOR_KILL_RADIUS;
    public static final ModConfigSpec.DoubleValue INTERCEPTOR_ACQUIRE_RANGE;
    public static final ModConfigSpec.DoubleValue SUPERSONIC_SPEED;
    public static final ModConfigSpec.BooleanValue INTERCEPTOR_CHIP_MODE;
    public static final ModConfigSpec.DoubleValue INTERCEPTOR_HIT_DAMAGE;
    public static final ModConfigSpec.DoubleValue INTERCEPTOR_GRAZE_DAMAGE;
    public static final ModConfigSpec.DoubleValue MIN_PROJECTILE_DAMAGE;
    // --- Stealth ---
    public static final ModConfigSpec.DoubleValue STEALTH_DETECT_RANGE;
    public static final ModConfigSpec.DoubleValue STEALTH_DETECT_CHANCE;
    // --- Evasion ---
    public static final ModConfigSpec.DoubleValue DIVE_EVASION_MULTIPLIER;
    // --- Batteries ---
    public static final ModConfigSpec.IntValue BATTERY_MAGAZINE;
    public static final ModConfigSpec.IntValue BATTERY_RELOAD_TICKS;
    // --- Fuel ---
    public static final ModConfigSpec.IntValue DEFAULT_FUEL_TICKS;
    // --- Incendiary ---
    public static final ModConfigSpec.BooleanValue FIRE_BOMBLETS_AS_FIREBALLS;
    // --- Off-world simulation ---
    public static final ModConfigSpec.DoubleValue SIM_PLAYER_KEEP_RANGE;
    public static final ModConfigSpec.DoubleValue SIM_TERMINAL_RANGE;
    public static final ModConfigSpec.DoubleValue SIM_SPAWN_MARGIN;
    public static final ModConfigSpec.IntValue SIM_CRUISE_DELAY_TICKS;
    // --- Industry tracking (glyphid aggression input) ---
    public static final ModConfigSpec.ConfigValue<List<? extends String>> INDUSTRY_MACHINES;
    public static final ModConfigSpec.IntValue INDUSTRY_CELL_CHUNKS;
    public static final ModConfigSpec.IntValue INDUSTRY_CLUSTER_GAP;
    public static final ModConfigSpec.IntValue INDUSTRY_CLUSTER_MIN_VALUE;
    public static final ModConfigSpec.IntValue INDUSTRY_RECOMPUTE_DELAY;
    public static final ModConfigSpec.BooleanValue INDUSTRY_BACKFILL;
    // --- Colonies ---
    public static final ModConfigSpec.IntValue COLONY_SAFE_RADIUS;
    public static final ModConfigSpec.IntValue COLONY_FULL_STRENGTH_DISTANCE;
    public static final ModConfigSpec.IntValue COLONY_MAX_TIER;
    public static final ModConfigSpec.DoubleValue COLONY_GROWTH_PER_SECOND;
    public static final ModConfigSpec.IntValue COLONY_POPULATION_CAP;
    public static final ModConfigSpec.DoubleValue COLONY_AGGRESSION_PER_PRESSURE;
    public static final ModConfigSpec.DoubleValue COLONY_STRIKE_THRESHOLD;
    public static final ModConfigSpec.IntValue COLONY_WARBAND_SIZE;
    public static final ModConfigSpec.IntValue COLONY_EXPANSION_POPULATION;
    public static final ModConfigSpec.IntValue COLONY_EXPANSION_COOLDOWN;
    public static final ModConfigSpec.IntValue COLONY_EXPANSION_RANGE;
    public static final ModConfigSpec.IntValue COLONY_TICK_INTERVAL;
    public static final ModConfigSpec.DoubleValue COLONY_WARBAND_SPEED;
    public static final ModConfigSpec.IntValue COLONY_MAX_COLONIES;
    public static final ModConfigSpec.IntValue COLONY_FRONTIER_DISTANCE;
    public static final ModConfigSpec.IntValue COLONY_PROVOCATION_RADIUS;
    public static final ModConfigSpec.IntValue COLONY_CROWDING_RADIUS;
    public static final ModConfigSpec.IntValue COLONY_CROWDING_LIMIT;
    public static final ModConfigSpec.IntValue COLONY_MATERIALISE_RANGE;
    public static final ModConfigSpec.IntValue COLONY_MATERIALISE_PER_TICK;
    // --- Glyphids ---
    public static final ModConfigSpec.BooleanValue GLYPHID_EXTENDED_TARGETING;
    public static final ModConfigSpec.BooleanValue GLYPHID_DIG;
    public static final ModConfigSpec.BooleanValue GLYPHID_WAYPOINT_DEBUG;
    // --- Debug ---
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("WarForge Factions integration (only has any effect when the 'warforge' mod is installed).")
                .push("warforge");
        WARFORGE_FACTION_FOF = b
                .comment("Treat missiles of the same / allied / truced WarForge faction as friendly (so a",
                        "faction's defenses don't engage its own missiles across different launchers).")
                .define("factionFriendOrFoe", true);
        WARFORGE_CLAIM_PROTECTION = b
                .comment("Missile explosions respect WarForge land claims (protected blocks survive, except in",
                        "active siege zones). Individual blasts can still opt out (e.g. strategic nukes).")
                .define("explosionsRespectClaims", true);
        b.pop();

        b.comment("Interceptor combat tuning.").push("interception");
        INTERCEPT_CHANCE = b
                .comment("Default per-interceptor kill probability on a proper (timed) intercept.")
                .defineInRange("killChance", 0.90, 0.0, 1.0);
        INTERCEPT_CROSSING_FACTOR = b
                .comment("Multiplier applied to the kill chance for a 'crossing' shot (interceptor too slow to",
                        "run the target down, only able to cross its path).")
                .defineInRange("crossingShotFactor", 0.35, 0.0, 1.0);
        INTERCEPTOR_KILL_RADIUS = b
                .comment("Separation (blocks) at which the interceptor's closest-approach test rolls for a kill.")
                .defineInRange("killRadius", 6.0, 0.5, 64.0);
        INTERCEPTOR_ACQUIRE_RANGE = b
                .comment("How far a NEAREST-mode interceptor scans for a hostile missile each tick (blocks).")
                .defineInRange("acquireRange", 200.0, 16.0, 1024.0);
        SUPERSONIC_SPEED = b
                .comment("cruiseSpeed (blocks/tick) at or above which a missile is classed 'supersonic'.")
                .defineInRange("supersonicSpeed", 2.5, 0.5, 64.0);
        INTERCEPTOR_CHIP_MODE = b
                .comment("If true, a proximity intercept damages the target's health pool (shared with CIWS)",
                        "instead of a binary destroy/miss: interceptors + CIWS combine and missile toughness",
                        "(health) matters. If false, a successful roll destroys the target outright.")
                .define("chipMode", false);
        INTERCEPTOR_HIT_DAMAGE = b
                .comment("Chip mode: health damage a successful intercept deals.")
                .defineInRange("hitDamage", 60.0, 0.0, 100000.0);
        INTERCEPTOR_GRAZE_DAMAGE = b
                .comment("Chip mode: health damage a missed intercept deals.")
                .defineInRange("grazeDamage", 8.0, 0.0, 100000.0);
        b.pop();

        b.comment("Missile toughness against incidental damage.").push("durability");
        MIN_PROJECTILE_DAMAGE = b
                .comment("Projectile hits weaker than this bounce off a missile instead of damaging it, so an",
                        "archer cannot plink one out of the sky and bypass interceptors and CIWS entirely.",
                        "Purpose-built anti-air hits far harder and is unaffected. 0 restores vanilla damage.")
                .defineInRange("minProjectileDamage", 20.0, 0.0, 100000.0);
        b.pop();

        b.comment("Stealth missiles: reduced-observability, not invisible.").push("stealth");
        STEALTH_DETECT_RANGE = b
                .comment("Range (blocks) within which automatic detection can see a stealth missile at all.")
                .defineInRange("detectRange", 32.0, 0.0, 512.0);
        STEALTH_DETECT_CHANCE = b
                .comment("Per-scan probability a stealth missile within that range is detected.")
                .defineInRange("detectChance", 0.25, 0.0, 1.0);
        b.pop();

        b.comment("Evasion: higher-tier missiles shrug off interception more often.").push("evasion");
        DIVE_EVASION_MULTIPLIER = b
                .comment("Multiplier on a missile's evasion during its terminal dive (hardest to hit there).")
                .defineInRange("diveMultiplier", 1.5, 0.0, 10.0);
        b.pop();

        b.comment("Interceptor batteries.").push("batteries");
        BATTERY_MAGAZINE = b
                .comment("Interceptors a battery can fire before it must reload; 0 = unlimited (no logistics).")
                .defineInRange("magazine", 0, 0, 100000);
        BATTERY_RELOAD_TICKS = b
                .comment("Ticks a battery takes to regenerate one interceptor toward its magazine.")
                .defineInRange("reloadTicks", 200, 1, 1_000_000);
        b.pop();

        b.comment("Fuel.").push("fuel");
        DEFAULT_FUEL_TICKS = b
                .comment("Default ticks of powered flight for a missile with no explicit fuel configured.")
                .defineInRange("defaultFuelTicks", 1200, 1, 1_000_000);
        b.pop();

        b.comment("Incendiary submunitions.").push("incendiary");
        FIRE_BOMBLETS_AS_FIREBALLS = b
                .comment("If true, the fire cluster warhead scatters flying fireballs instead of fire bomblets.")
                .define("fireBombletsAsFireballs", false);
        b.pop();

        b.comment("Off-world missile simulation: when a cruising missile is swapped between a real, ticking,",
                        "rendered entity and a lightweight off-world sim. Larger ranges keep missiles real (and",
                        "visible) farther out at the cost of more ticking + chunk-loading; smaller ranges favour",
                        "performance.")
                .push("simulation");
        SIM_PLAYER_KEEP_RANGE = b
                .comment("Radius (blocks) around each player within which a missile stays a real, tracked, rendered",
                        "entity. Defaults to 512 to match the missile's tracking range (32 chunks) so a departing",
                        "missile stays visible to the edge of view distance instead of vanishing mid-flight. Lower",
                        "it to reduce how many missiles tick and force-load chunks near players.")
                .defineInRange("playerKeepRange", 512.0, 16.0, 4096.0);
        SIM_TERMINAL_RANGE = b
                .comment("Horizontal distance (blocks) to its target at which a simulated missile respawns for its",
                        "terminal run, independent of any nearby player.")
                .defineInRange("terminalRange", 1000.0, 0.0, 100000.0);
        SIM_SPAWN_MARGIN = b
                .comment("Missiles rematerialize this many blocks before crossing a keep-range boundary, so they are",
                        "already live by the time they enter view (no spawn pop-in).")
                .defineInRange("spawnMargin", 16.0, 0.0, 512.0);
        SIM_CRUISE_DELAY_TICKS = b
                .comment("Ticks a missile must cruise before it may offload to the sim at all (20 ticks = 1s).")
                .defineInRange("cruiseDelayTicks", 100, 0, 1_000_000);
        b.pop();

        b.comment("Industry tracking: what provokes the glyphids, and how bases are detected.",
                        "Machines register on placement and on the one-off sweep each chunk gets when it first",
                        "loads, so a base built before this feature existed still counts.")
                .push("industry");
        INDUSTRY_MACHINES = b
                .comment("Blocks that provoke, and by how much.",
                        "Format: \"registryId=value\" (value defaults to 1 if omitted).",
                        "Dirty industry is meant to score far higher than clean: a diesel farm should draw",
                        "attention long before an equivalent electric base does.",
                        "wfcore overrides these at runtime when it is installed.")
                .defineList("machines", IndustryValues.DEFAULTS, o -> o instanceof String);
        INDUSTRY_CELL_CHUNKS = b
                .comment("Region cell size in chunks. Provocation is accumulated per cell, and bases are",
                        "detected by grouping neighbouring occupied cells. Larger cells are cheaper and",
                        "coarser; changing this rebuilds the index on next load.")
                .defineInRange("regionCellChunks", 32, 1, 512);
        INDUSTRY_CLUSTER_GAP = b
                .comment("How many empty cells may sit between two occupied ones before they count as",
                        "separate bases. The cell-space equivalent of DBSCAN's eps.")
                .defineInRange("clusterGapCells", 1, 0, 16);
        INDUSTRY_CLUSTER_MIN_VALUE = b
                .comment("Combined value a group of cells needs before it counts as a base rather than noise.",
                        "The equivalent of DBSCAN's minPts, but measured in provocation, so one dirty plant",
                        "outweighs a scattering of furnaces.")
                .defineInRange("clusterMinValue", 50, 1, 1_000_000);
        INDUSTRY_RECOMPUTE_DELAY = b
                .comment("Ticks of quiet after the last change before bases are re-detected (off-thread).",
                        "Debouncing matters: laying out a factory is hundreds of placements in a few seconds.")
                .defineInRange("recomputeDelayTicks", 100, 1, 72_000);
        INDUSTRY_BACKFILL = b
                .comment("Sweep each chunk once, on its first load, for machines no placement event reported",
                        "(worldgen, structures, KubeJS, AE2, /setblock, anything pre-existing).",
                        "Only block entities are examined, so this is cheap.")
                .define("chunkBackfill", true);
        b.pop();

        b.comment("Colony simulation: nests grow, take offence and march whether or not chunks are loaded.",
                        "Strength scales with distance from world spawn, so the frontier is the dangerous part.")
                .push("colonies");
        COLONY_SAFE_RADIUS = b
                .comment("No colony may exist within this many blocks of world spawn.")
                .defineInRange("safeRadius", 512, 0, 1_000_000);
        COLONY_FULL_STRENGTH_DISTANCE = b
                .comment("Distance from spawn at which colonies reach the maximum tier. Strength ramps",
                        "linearly between the safe radius and here.")
                .defineInRange("fullStrengthDistance", 12_000, 1, 30_000_000);
        COLONY_MAX_TIER = b
                .comment("Highest colony tier. Each tier multiplies population cap, growth and warband size.")
                .defineInRange("maxTier", 4, 0, 32);
        COLONY_GROWTH_PER_SECOND = b
                .comment("Population a tier-0 colony gains per second.")
                .defineInRange("growthPerSecond", 0.35, 0.0, 1000.0);
        COLONY_POPULATION_CAP = b
                .comment("Population cap at tier 0; each tier adds another multiple of this.")
                .defineInRange("populationCap", 20, 1, 100_000);
        COLONY_AGGRESSION_PER_PRESSURE = b
                .comment("Aggression gained per point of nearby industry provocation, per second.",
                        "This is the link between what the player runs and when they get attacked.")
                .defineInRange("aggressionPerPressure", 0.02, 0.0, 1000.0);
        COLONY_STRIKE_THRESHOLD = b
                .comment("Aggression at which a colony with the numbers to spare sends a warband.")
                .defineInRange("strikeThreshold", 100.0, 1.0, 1_000_000.0);
        COLONY_WARBAND_SIZE = b
                .comment("Warband size at tier 0; each tier adds another multiple.")
                .defineInRange("warbandSize", 8, 1, 10_000);
        COLONY_EXPANSION_POPULATION = b
                .comment("Population a colony must hold before it can afford to found another.")
                .defineInRange("expansionPopulation", 25, 1, 100_000);
        COLONY_EXPANSION_COOLDOWN = b
                .comment("Ticks before a colony may expand again (12000 = 10 minutes).")
                .defineInRange("expansionCooldownTicks", 12_000, 1, 10_000_000);
        COLONY_EXPANSION_RANGE = b
                .comment("How far from its parent a new colony is founded, outward from spawn.")
                .defineInRange("expansionRange", 900, 16, 100_000);
        COLONY_TICK_INTERVAL = b
                .comment("Ticks between colony updates. Colonies are staggered across this window, so the",
                        "per-tick cost is the colony count divided by this.")
                .defineInRange("tickIntervalTicks", 20, 1, 1200);
        COLONY_WARBAND_SPEED = b
                .comment("Blocks per tick a travelling warband covers while off-world.")
                .defineInRange("warbandSpeed", 0.35, 0.01, 64.0);
        COLONY_MAX_COLONIES = b
                .comment("Hard ceiling on colonies per dimension. Expansion is exponential -- every colony",
                        "founded can found more -- so it needs a stop, not just a cooldown.")
                .defineInRange("maxColonies", 250, 0, 100_000);
        COLONY_FRONTIER_DISTANCE = b
                .comment("No colony may be founded beyond this distance from spawn.")
                .defineInRange("frontierDistance", 24_000, 1, 30_000_000);
        COLONY_PROVOCATION_RADIUS = b
                .comment("How far a colony can smell industry. Independent of the industry cell size on",
                        "purpose, so tuning cells for performance does not silently change gameplay.")
                .defineInRange("provocationRadius", 1_500, 16, 100_000);
        COLONY_CROWDING_RADIUS = b
                .comment("Radius the crowding limit below is measured over.")
                .defineInRange("crowdingRadius", 1_800, 16, 100_000);
        COLONY_CROWDING_LIMIT = b
                .comment("Colonies already within the crowding radius of a proposed site that will block it.",
                        "This is what makes expansion fill in territory instead of fleeing outward forever.")
                .defineInRange("crowdingLimit", 4, 1, 1_000);
        COLONY_MATERIALISE_RANGE = b
                .comment("How close a player has to be before a warband stops being one record and becomes",
                        "glyphids. Nothing is ever placed in an unloaded chunk regardless of this.")
                .defineInRange("materialiseRange", 128, 16, 100_000);
        COLONY_MATERIALISE_PER_TICK = b
                .comment("Glyphids placed per dimension per tick. A large warband streams in over several",
                        "ticks rather than spawning hundreds of entities inside one. 0 disables spawning.")
                .defineInRange("materialisePerTick", 4, 0, 1_000);
        b.pop();

        b.comment("Glyphid swarm behaviour.").push("glyphids");
        GLYPHID_EXTENDED_TARGETING = b
                .comment("Glyphids hunt players across 128 blocks instead of only the 16 around them.",
                        "This is what turns a nest into a base attack, and it is the single biggest lever on",
                        "how much work a swarm does per tick.")
                .define("extendedTargeting", false);
        GLYPHID_DIG = b
                .comment("Glyphids chew through terrain and buildings to reach a waypoint. With this off they",
                        "path around obstacles instead, which is cheaper but lets walls stop them entirely.")
                .define("digging", true);
        GLYPHID_WAYPOINT_DEBUG = b
                .comment("Render colony waypoints as coloured dust particles (client-side).")
                .define("waypointDebug", false);
        b.pop();

        b.comment("Debugging.").push("debug");
        DEBUG_LOGGING = b
                .comment("Log detailed per-missile flight telemetry to the server log and auto-track new missiles.",
                        "Toggle at runtime with /wfballistics debug on|off; list off-world tracks with simlist.")
                .define("missileLogging", false);
        b.pop();

        SPEC = b.build();
    }

    private WFConfig() {
    }

    /**
     * Copies the loaded config values into the plain static fields the gameplay code reads. Subscribed on the
     * mod event bus for both initial load and reload.
     */
    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        WarforgeCompat.setFactionFoFEnabled(WARFORGE_FACTION_FOF.get());
        WarforgeCompat.setClaimProtectionEnabled(WARFORGE_CLAIM_PROTECTION.get());
        MissileSimConfig.DEFAULT_INTERCEPT_CHANCE = INTERCEPT_CHANCE.get().floatValue();
        MissileSimConfig.INTERCEPTOR_CROSSING_HIT_FACTOR = INTERCEPT_CROSSING_FACTOR.get().floatValue();
        MissileSimConfig.INTERCEPTOR_KILL_RADIUS = INTERCEPTOR_KILL_RADIUS.get();
        MissileSimConfig.INTERCEPTOR_ACQUIRE_RANGE = INTERCEPTOR_ACQUIRE_RANGE.get();
        MissileSimConfig.SUPERSONIC_SPEED = SUPERSONIC_SPEED.get();
        MissileSimConfig.INTERCEPTOR_CHIP_MODE = INTERCEPTOR_CHIP_MODE.get();
        MissileSimConfig.INTERCEPTOR_HIT_DAMAGE = INTERCEPTOR_HIT_DAMAGE.get().floatValue();
        MissileSimConfig.INTERCEPTOR_GRAZE_DAMAGE = INTERCEPTOR_GRAZE_DAMAGE.get().floatValue();
        MissileSimConfig.MIN_PROJECTILE_DAMAGE = MIN_PROJECTILE_DAMAGE.get().floatValue();
        MissileSimConfig.STEALTH_DETECT_RANGE = STEALTH_DETECT_RANGE.get();
        MissileSimConfig.STEALTH_DETECT_CHANCE = STEALTH_DETECT_CHANCE.get().floatValue();
        MissileSimConfig.DIVE_EVASION_MULTIPLIER = DIVE_EVASION_MULTIPLIER.get();
        MissileSimConfig.BATTERY_MAGAZINE = BATTERY_MAGAZINE.get();
        MissileSimConfig.BATTERY_RELOAD_TICKS = BATTERY_RELOAD_TICKS.get();
        MissileEntity.DEFAULT_FUEL_TICKS = DEFAULT_FUEL_TICKS.get();
        com.wf.wfballistics.warhead.FireCluster.asFireballs = FIRE_BOMBLETS_AS_FIREBALLS.get();
        MissileSimConfig.PLAYER_LISTENER_RANGE = SIM_PLAYER_KEEP_RANGE.get();
        MissileSimConfig.DESTINATION_RANGE = SIM_TERMINAL_RANGE.get();
        MissileSimConfig.LISTENER_SPAWN_MARGIN = SIM_SPAWN_MARGIN.get();
        MissileSimConfig.CRUISE_SIM_DELAY_TICKS = SIM_CRUISE_DELAY_TICKS.get();
        com.wf.wfballistics.debug.MissileDebug.configureDefault(DEBUG_LOGGING.get());
        IndustryValues.bake(INDUSTRY_MACHINES.get());
        IndustryConfig.apply(INDUSTRY_CELL_CHUNKS.get(), INDUSTRY_CLUSTER_GAP.get(),
                INDUSTRY_CLUSTER_MIN_VALUE.get(), INDUSTRY_RECOMPUTE_DELAY.get(), INDUSTRY_BACKFILL.get());
        ColonyConfig.apply(COLONY_SAFE_RADIUS.get(), COLONY_FULL_STRENGTH_DISTANCE.get(), COLONY_MAX_TIER.get(),
                COLONY_GROWTH_PER_SECOND.get(), COLONY_POPULATION_CAP.get(),
                COLONY_AGGRESSION_PER_PRESSURE.get(), COLONY_STRIKE_THRESHOLD.get(), COLONY_WARBAND_SIZE.get(),
                COLONY_EXPANSION_POPULATION.get(), COLONY_EXPANSION_COOLDOWN.get(), COLONY_EXPANSION_RANGE.get(),
                COLONY_TICK_INTERVAL.get(), COLONY_WARBAND_SPEED.get(),
                COLONY_MAX_COLONIES.get(), COLONY_FRONTIER_DISTANCE.get(), COLONY_PROVOCATION_RADIUS.get(),
                COLONY_CROWDING_RADIUS.get(), COLONY_CROWDING_LIMIT.get());
        ColonyConfig.applyMaterialisation(COLONY_MATERIALISE_RANGE.get(), COLONY_MATERIALISE_PER_TICK.get());
    }
}
