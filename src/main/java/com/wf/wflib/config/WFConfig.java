package com.wf.wflib.config;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.colony.ColonyConfig;
import com.wf.wflib.entity.glyphid.GlyphidStats;
import com.wf.wflib.industry.IndustryConfig;
import com.wf.wflib.industry.IndustryValues;
import com.wf.wflib.sim.MissileSimConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server/common config for the tunables most worth adjusting without recompiling: the WarForge integration toggles,
 * the interceptor combat numbers, and default fuel.
 */
public final class WFConfig {

    public static final ModConfigSpec SPEC;

    // --- WarForge integration ---
    public static final ModConfigSpec.BooleanValue WARFORGE_FACTION_FOF;
    public static final ModConfigSpec.BooleanValue WARFORGE_CLAIM_PROTECTION;
    // --- Orbital ---
    public static final ModConfigSpec.BooleanValue REQUIRE_WAR_FOR_ORBITAL_KILLS;
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
    public static final ModConfigSpec.BooleanValue REWIND_WHOLE_FLIGHT;
    // --- Stealth ---
    public static final ModConfigSpec.DoubleValue STEALTH_RCS;
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
    public static final ModConfigSpec.IntValue CAMERA_STREAM_RADIUS;
    public static final ModConfigSpec.IntValue CAMERA_STREAM_BUDGET;
    public static final ModConfigSpec.IntValue DETACHED_STREAM_RADIUS;
    public static final ModConfigSpec.IntValue DETACHED_CHUNKS_PER_TICK;
    public static final ModConfigSpec.BooleanValue DETACHED_SUPPRESS_BODY_ENTITIES;
    public static final ModConfigSpec.IntValue DETACHED_BODY_VIEW_DISTANCE;
    public static final ModConfigSpec.IntValue DETACHED_BODY_TICKET_RADIUS;
    public static final ModConfigSpec.IntValue WAKEUP_TIMEOUT;
    public static final ModConfigSpec.IntValue WAKEUP_TICKET_RADIUS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> STREAM_DEBUG;
    public static final ModConfigSpec.IntValue STREAM_DEBUG_HEARTBEAT;
    public static final ModConfigSpec.IntValue CAMERA_MAX_CHANNELS;

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
    public static final ModConfigSpec.DoubleValue COLONY_BUD_CHANCE;
    public static final ModConfigSpec.IntValue COLONY_BUD_POPULATION;
    public static final ModConfigSpec.IntValue COLONY_BUD_COOLDOWN;
    public static final ModConfigSpec.IntValue COLONY_BUD_CAP_BASE;
    public static final ModConfigSpec.IntValue COLONY_BUD_CAP_PER_TIER;
    public static final ModConfigSpec.DoubleValue COLONY_REINFORCED_EVOLUTION;
    public static final ModConfigSpec.IntValue COLONY_TICK_INTERVAL;
    public static final ModConfigSpec.DoubleValue COLONY_WARBAND_SPEED;
    public static final ModConfigSpec.IntValue COLONY_MAX_COLONIES;
    public static final ModConfigSpec.IntValue COLONY_FRONTIER_DISTANCE;
    public static final ModConfigSpec.IntValue COLONY_PROVOCATION_RADIUS;
    public static final ModConfigSpec.IntValue COLONY_CROWDING_RADIUS;
    public static final ModConfigSpec.IntValue COLONY_CROWDING_LIMIT;
    public static final ModConfigSpec.IntValue COLONY_NATURAL_SPACING;
    public static final ModConfigSpec.DoubleValue COLONY_NATURAL_CHANCE;
    public static final ModConfigSpec.IntValue COLONY_MATERIALISE_RANGE;
    public static final ModConfigSpec.IntValue COLONY_MATERIALISE_PER_TICK;
    public static final ModConfigSpec.DoubleValue COLONY_FLYING_CHANCE_PER_TIER;
    public static final ModConfigSpec.DoubleValue COLONY_FLYING_SPEED_FACTOR;
    public static final ModConfigSpec.DoubleValue COLONY_EVOLUTION_TIME_FACTOR;
    public static final ModConfigSpec.DoubleValue COLONY_EVOLUTION_PRESSURE_FACTOR;
    public static final ModConfigSpec.DoubleValue COLONY_EVOLUTION_STRENGTH_BONUS;
    // --- Glyphids ---
    public static final ModConfigSpec.DoubleValue GLYPHID_PACE;
    public static final ModConfigSpec.BooleanValue GLYPHID_EXTENDED_TARGETING;
    public static final ModConfigSpec.BooleanValue GLYPHID_DIG;
    public static final ModConfigSpec.BooleanValue GLYPHID_DIG_OVERLAY;
    public static final ModConfigSpec.BooleanValue GLYPHID_WAYPOINT_DEBUG;
    public static final ModConfigSpec.BooleanValue GLYPHID_BOMBING;
    public static final ModConfigSpec.IntValue GLYPHID_BOMB_LOAD;
    public static final ModConfigSpec.IntValue GLYPHID_BOMB_INTERVAL;
    public static final ModConfigSpec.DoubleValue GLYPHID_ACID_DAMAGE;
    public static final ModConfigSpec.DoubleValue GLYPHID_ACID_RADIUS;
    public static final ModConfigSpec.BooleanValue GLYPHID_ACID_CORRODES;
    public static final ModConfigSpec.IntValue GLYPHID_BOMB_BLAST;
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

        b.comment("Orbital systems.").push("orbital");
        REQUIRE_WAR_FOR_ORBITAL_KILLS = b
                .comment("Whether destroying another faction's satellite needs a declared war.",
                        "Off by default: anything overhead is fair game, which is the harsher setting and what",
                        "gives parking over somebody's base its edge. Deliberately narrow - it gates the kill",
                        "and never the tracking or the jamming, so turning it on makes nobody invisible.")
                .define("requireWarForOrbitalKills", false);
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

        b.comment("Rounds.").push("rounds");
        REWIND_WHOLE_FLIGHT = b
                .comment("Lag compensation: true = every step of a round sweeps targets as its shooter saw them;",
                        "false = first step only, later steps sweep live targets.")
                .define("rewindWholeFlight", true);
        b.pop();

        b.comment("Stealth missiles: reduced-observability, not invisible.").push("stealth");
        STEALTH_RCS = b
                .comment("Radar cross-section of a stealth missile, against a reference of 1.0.",
                        "Detection range scales with the fourth root of this, so halving the range a missile",
                        "is seen at costs a sixteenfold reduction. The default gives about 32 blocks against",
                        "a 200-block interceptor battery, matching the fixed window this replaced.")
                .defineInRange("rcs", 0.00067, 0.0, 1.0);
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

        b.comment("Terrain streaming for drone camera feeds. A client can only draw chunks the server has sent",
                        "it, and the server only sends the ones around the player's own body, so without this a",
                        "feed goes black the moment the drone outruns its operator's view distance, however far",
                        "the datalink reaches. These chunks are loaded but never ticked, and are sent only to the",
                        "people actually watching a feed.")
                .push("droneCameraStreaming");
        CAMERA_STREAM_RADIUS = b
                .comment("Chunks either side of a watched drone to keep loaded and stream to its audience. This is",
                        "how far a feed can see, and it is a bubble around the drone rather than a distance from",
                        "the player, so it costs the same whether the drone is fifty blocks away or a thousand.",
                        "Eight is 128 blocks of terrain in every direction, which reads as a horizon on a camera",
                        "that small. Raising it is quadratic in both bandwidth and the chunks the server must",
                        "keep resident. Zero disables streaming entirely and puts the old render-distance ceiling",
                        "back.")
                .defineInRange("radius", 8, 0, 16);
        CAMERA_STREAM_BUDGET = b
                .comment("Chunk packets built per publish (five publishes a second), shared out between however",
                        "many feeds have an audience. This is a fill-rate limit, not a cap: what does not fit is",
                        "sent on the next publish, nearest ring first, so a feed sharpens from the middle",
                        "outwards. Lower it if opening a feed briefly stutters other players on a busy server.")
                .defineInRange("chunksPerPublish", 24, 1, 256);
        b.pop();

        b.comment("Remote operation: a player rides an entity from afar (a UAV) while their body stays parked.",
                        "Their view is moved onto the entity and its terrain streamed to them.")
                .push("detachedBody");
        DETACHED_STREAM_RADIUS = b
                .comment("Chunk radius streamed around the remotely operated entity, clamped to the server view distance.")
                .defineInRange("streamRadius", 12, 2, 32);
        DETACHED_CHUNKS_PER_TICK = b
                .comment("Chunk packets sent to one operator per tick. Unbounded, taking control dumps the whole",
                        "view square at once and stalls input for seconds.")
                .defineInRange("maxChunksPerTick", 16, 1, 1024);
        DETACHED_SUPPRESS_BODY_ENTITIES = b
                .comment("Stop tracking entities around the parked body to its operator. Whatever the body rides or",
                        "carries stays tracked; everything returns when control ends.")
                .define("suppressBodyStream", true);
        DETACHED_BODY_VIEW_DISTANCE = b
                .comment("View distance of the parked body. Below 2 keeps the normal view distance.")
                .defineInRange("bodyViewDistance", 2, -1, 32);
        DETACHED_BODY_TICKET_RADIUS = b
                .comment("Chunk radius loaded around the parked body instead of its player ticket. Chunks outside it",
                        "stop ticking (mobs, redstone, farms) until control ends. Negative keeps the player ticket.")
                .defineInRange("bodyTicketRadius", 2, -1, 8);
        WAKEUP_TIMEOUT = b
                .comment("Ticks to wait for a remotely operable entity in unloaded terrain to load on connect.")
                .defineInRange("wakeupTimeout", 200, 20, 6000);
        WAKEUP_TICKET_RADIUS = b
                .comment("Chunk ticket radius while waking such an entity.")
                .defineInRange("wakeupTicketRadius", 2, 1, 8);
        b.pop();

        b.push("streamDebug");
        STREAM_DEBUG = b
                .comment("Chunk streaming log categories: ALL, SESSION, TICKET, CHUNK, CENTER, WAKEUP, CLIENT, CACHE.",
                        "Empty = off. `/wflib stream debug` overrides until `/wflib stream reset`.")
                .defineListAllowEmpty("categories", java.util.List.of(), () -> "", o -> o instanceof String);
        STREAM_DEBUG_HEARTBEAT = b
                .comment("Ticks between debug heartbeat lines.")
                .defineInRange("heartbeatTicks", 40, 1, 12000);
        b.pop();

        b.comment("Camera panels: the monitor block and the handheld receiver. Both hold a bounded list of",
                        "bound cameras (fixed ones and camera-equipped drones alike), and switch between them.")
                .push("cameraChannels");
        CAMERA_MAX_CHANNELS = b
                .comment("How many cameras one monitor or one receiver can have bound at once. The limit is what",
                        "makes covering a base a matter of choosing angles rather than papering the walls: every",
                        "bound camera is one you did not bind somewhere else. Lowering this below what is already",
                        "bound does not delete anything: an over-full panel keeps working and simply refuses the",
                        "next binding.")
                .defineInRange("maxBound", 8, 1, 32);
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
        COLONY_BUD_CHANCE = b
                .comment("Expansion comes in two kinds. An outpost is a whole new colony expansionRange blocks",
                        "further out; a bud is another mound welded onto this colony's own cluster, on a hex",
                        "lattice, sharing its population and its anger. This is the share that buds when the",
                        "colony could afford either. It is not a hard split: an outpost with nowhere to go",
                        "falls back to budding, so a crowded region thickens instead of stalling.")
                .defineInRange("budChance", 0.55, 0.0, 1.0);
        COLONY_BUD_POPULATION = b
                .comment("Population a colony must hold to bud. Cheaper than expansionPopulation because a bud",
                        "is the same colony growing rather than a second one starting from nothing.")
                .defineInRange("budPopulation", 12, 1, 100_000);
        COLONY_BUD_COOLDOWN = b
                .comment("Ticks before a colony may expand again after budding (4800 = 4 minutes). Shorter than",
                        "the outpost cooldown, so a cluster fills in faster than the frontier moves.")
                .defineInRange("budCooldownTicks", 4_800, 1, 10_000_000);
        COLONY_BUD_CAP_BASE = b
                .comment("Mounds a tier-0 colony may bud, beyond the one it starts as.")
                .defineInRange("budCapBase", 1, 0, 1_000);
        COLONY_BUD_CAP_PER_TIER = b
                .comment("How many more each tier adds. At the defaults a tier-0 colony tops out at two mounds",
                        "and a tier-4 one at six, so how sprawling a hive is says how far from spawn it is.")
                .defineInRange("budCapPerTier", 1, 0, 1_000);
        COLONY_REINFORCED_EVOLUTION = b
                .comment("Evolution at which colonies start laying reinforced flesh: the same nest block with",
                        "a brown cast, 60x the blast resistance and a hardness that wants a pickaxe. Above this",
                        "the hardened crust deepens with evolution until a fully evolved world builds mounds",
                        "reinforced all the way through. Baked in at the moment each mound is laid, so an old",
                        "hive keeps its soft shell and only the cells it grows later come up brown.")
                .defineInRange("reinforcedEvolution", 0.35, 0.0, 1.0);
        COLONY_TICK_INTERVAL = b
                .comment("Ticks between colony updates. Colonies are staggered across this window, so the",
                        "per-tick cost is the colony count divided by this.")
                .defineInRange("tickIntervalTicks", 20, 1, 1200);
        COLONY_WARBAND_SPEED = b
                .comment("Blocks per tick a travelling warband covers while off-world.")
                .defineInRange("warbandSpeed", 0.35, 0.01, 64.0);
        COLONY_MAX_COLONIES = b
                .comment("Hard ceiling on colonies per dimension. Expansion is exponential (every colony",
                        "founded can found more), so it needs a stop, not just a cooldown.")
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
        COLONY_NATURAL_SPACING = b
                .comment("Side of the grid cell natural colonies are seeded on, in blocks. One cell holds at",
                        "most one nest, at a position drawn from the world seed, so placement is reproducible",
                        "and does not depend on which chunk a player walks into first. This is the setting that",
                        "decides how far apart hives are; 0 turns natural seeding off entirely and leaves",
                        "colonies to expansion and the 'colony found' command.")
                .defineInRange("naturalSpacing", 512, 0, 100_000);
        COLONY_NATURAL_CHANCE = b
                .comment("Chance that a cell has a nest at all. Below 1 the frontier is patchy rather than a",
                        "lattice, which is what stops the spacing from reading as a grid on a map.")
                .defineInRange("naturalChance", 0.7, 0.0, 1.0);
        COLONY_MATERIALISE_RANGE = b
                .comment("How close a player has to be before a warband stops being one record and becomes",
                        "glyphids. Nothing is ever placed in an unloaded chunk regardless of this.")
                .defineInRange("materialiseRange", 128, 16, 100_000);
        COLONY_MATERIALISE_PER_TICK = b
                .comment("Glyphids placed per dimension per tick. A large warband streams in over several",
                        "ticks rather than spawning hundreds of entities inside one. 0 disables spawning.")
                .defineInRange("materialisePerTick", 4, 0, 1_000);
        COLONY_FLYING_CHANCE_PER_TIER = b
                .comment("Chance per colony tier that a warband flies instead of walking. At the default a",
                        "tier-0 nest never fields wings and a tier-4 one usually does, so flight is something",
                        "the frontier has. Flying glyphids do no pathfinding, so they are also cheaper.")
                .defineInRange("flyingChancePerTier", 0.2, 0.0, 1.0);
        COLONY_FLYING_SPEED_FACTOR = b
                .comment("How much faster a flying warband crosses the map than a walking one.")
                .defineInRange("flyingSpeedFactor", 3.0, 1.0, 64.0);
        COLONY_EVOLUTION_TIME_FACTOR = b
                .comment("Share of the remaining gap to full evolution closed every 200 ticks by time alone.",
                        "Evolution gates which castes a colony may field and adds a little to how fast it",
                        "grows. At the default an untouched world is roughly half evolved after fifty hours.",
                        "0 pins evolution to industry alone.")
                .defineInRange("evolutionTimeFactor", 4.0E-5, 0.0, 1.0);
        COLONY_EVOLUTION_PRESSURE_FACTOR = b
                .comment("The same, per point of standing industry across the world. This is the term that",
                        "makes evolution a consequence of what was built rather than of how long the game",
                        "was left running. 0 makes evolution purely a matter of time.")
                .defineInRange("evolutionPressureFactor", 2.0E-8, 0.0, 1.0);
        COLONY_EVOLUTION_STRENGTH_BONUS = b
                .comment("How much a fully evolved world adds to colony growth and warband size. At the",
                        "default a late colony musters half again what an early one of the same tier does.")
                .defineInRange("evolutionStrengthBonus", 0.5, 0.0, 16.0);
        b.pop();

        b.comment("Glyphid swarm behaviour.").push("glyphids");
        GLYPHID_PACE = b
                .comment("Multiplier on every caste's movement speed. The caste table is relative (a behemoth",
                        "is 0.8 of a grunt, a scout 1.5), and this sets the swarm's absolute pace without",
                        "disturbing those ratios. A glyphid that cannot keep up with what it is chasing is",
                        "scenery, so this is the lever for how far a player can outrun one.")
                .defineInRange("pace", 1.25, 0.05, 8.0);
        GLYPHID_EXTENDED_TARGETING = b
                .comment("Glyphids hunt players across 128 blocks instead of only the 16 around them.",
                        "This is what turns a nest into a base attack, and it is the single biggest lever on",
                        "how much work a swarm does per tick.")
                .define("extendedTargeting", false);
        GLYPHID_DIG = b
                .comment("Glyphids chew through terrain and buildings to reach a waypoint. With this off they",
                        "path around obstacles instead, which is cheaper but lets walls stop them entirely.")
                .define("digging", true);
        GLYPHID_DIG_OVERLAY = b
                .comment("Show the block-breaking cracks on whatever a glyphid is chewing. Purely visual, but",
                        "it is a packet per crack stage to every player in range, so a swarm eating a wall in",
                        "front of a crowded base is where it would cost anything.")
                .define("diggingOverlay", true);
        GLYPHID_WAYPOINT_DEBUG = b
                .comment("Render colony waypoints as coloured dust particles (client-side).")
                .define("waypointDebug", false);
        GLYPHID_BOMBING = b
                .comment("Flying glyphids drop acid on what they were sent to attack. This is what makes wings",
                        "a threat to a base rather than just a faster way of arriving at one.")
                .define("bombing", true);
        GLYPHID_BOMB_LOAD = b
                .comment("Bombs each flying glyphid carries. Finite, so a raid is something a base survives and",
                        "rebuilds from rather than a bug parked overhead dissolving it forever.")
                .defineInRange("bombLoad", 4, 0, 512);
        GLYPHID_BOMB_INTERVAL = b
                .comment("Ticks between bomb releases from one glyphid.")
                .defineInRange("bombIntervalTicks", 30, 1, 1200);
        GLYPHID_ACID_DAMAGE = b
                .comment("Damage per second to anything standing in a fresh acid pool. Tapers to nothing as",
                        "the pool dries out.")
                .defineInRange("acidDamage", 3.0, 0.0, 1000.0);
        GLYPHID_ACID_RADIUS = b
                .comment("Horizontal radius of the acid pool a bomb leaves.")
                .defineInRange("acidRadius", 3.0, 0.5, 32.0);
        GLYPHID_ACID_CORRODES = b
                .comment("Acid pools dissolve the blocks under them. Obsidian-grade blast resistance holds;",
                        "most machine casings and building blocks do not.")
                .define("acidCorrodesBlocks", true);
        GLYPHID_BOMB_BLAST = b
                .comment("Blast radius for the explosive payload, used by castes that drop it instead of acid.")
                .defineInRange("bombBlastRadius", 3, 1, 64);
        b.pop();

        b.comment("Debugging.").push("debug");
        DEBUG_LOGGING = b
                .comment("Log detailed per-missile flight telemetry to the server log and auto-track new missiles.",
                        "Toggle at runtime with /wflib debug on|off; list off-world tracks with simlist.")
                .define("missileLogging", false);
        b.pop();

        SPEC = b.build();
    }

    private WFConfig() {
    }

    /** Copies the loaded config values into the plain static fields the gameplay code reads. */
    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        WarforgeCompat.setFactionFoFEnabled(WARFORGE_FACTION_FOF.get());
        WarforgeCompat.setClaimProtectionEnabled(WARFORGE_CLAIM_PROTECTION.get());
        com.wf.wflib.orbital.OrbitalConfig.requireWarForOrbitalKills =
                REQUIRE_WAR_FOR_ORBITAL_KILLS.get();
        MissileSimConfig.DEFAULT_INTERCEPT_CHANCE = INTERCEPT_CHANCE.get().floatValue();
        MissileSimConfig.INTERCEPTOR_CROSSING_HIT_FACTOR = INTERCEPT_CROSSING_FACTOR.get().floatValue();
        MissileSimConfig.INTERCEPTOR_KILL_RADIUS = INTERCEPTOR_KILL_RADIUS.get();
        MissileSimConfig.INTERCEPTOR_ACQUIRE_RANGE = INTERCEPTOR_ACQUIRE_RANGE.get();
        MissileSimConfig.SUPERSONIC_SPEED = SUPERSONIC_SPEED.get();
        MissileSimConfig.INTERCEPTOR_CHIP_MODE = INTERCEPTOR_CHIP_MODE.get();
        MissileSimConfig.INTERCEPTOR_HIT_DAMAGE = INTERCEPTOR_HIT_DAMAGE.get().floatValue();
        MissileSimConfig.INTERCEPTOR_GRAZE_DAMAGE = INTERCEPTOR_GRAZE_DAMAGE.get().floatValue();
        MissileSimConfig.MIN_PROJECTILE_DAMAGE = MIN_PROJECTILE_DAMAGE.get().floatValue();
        com.wf.wflib.round.Rounds.rewindWholeFlight = REWIND_WHOLE_FLIGHT.get();
        MissileSimConfig.STEALTH_RCS = STEALTH_RCS.get().floatValue();
        MissileSimConfig.DIVE_EVASION_MULTIPLIER = DIVE_EVASION_MULTIPLIER.get();
        MissileSimConfig.BATTERY_MAGAZINE = BATTERY_MAGAZINE.get();
        MissileSimConfig.BATTERY_RELOAD_TICKS = BATTERY_RELOAD_TICKS.get();
        MissileEntity.DEFAULT_FUEL_TICKS = DEFAULT_FUEL_TICKS.get();
        com.wf.wflib.warhead.FireCluster.asFireballs = FIRE_BOMBLETS_AS_FIREBALLS.get();
        MissileSimConfig.PLAYER_LISTENER_RANGE = SIM_PLAYER_KEEP_RANGE.get();
        MissileSimConfig.DESTINATION_RANGE = SIM_TERMINAL_RANGE.get();
        MissileSimConfig.LISTENER_SPAWN_MARGIN = SIM_SPAWN_MARGIN.get();
        MissileSimConfig.CRUISE_SIM_DELAY_TICKS = SIM_CRUISE_DELAY_TICKS.get();
        com.wf.wflib.debug.MissileDebug.configureDefault(DEBUG_LOGGING.get());
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
        ColonyConfig.applyFlight(COLONY_FLYING_CHANCE_PER_TIER.get(), COLONY_FLYING_SPEED_FACTOR.get());
        ColonyConfig.applyEvolution(COLONY_EVOLUTION_TIME_FACTOR.get(), COLONY_EVOLUTION_PRESSURE_FACTOR.get(),
                COLONY_EVOLUTION_STRENGTH_BONUS.get());
        ColonyConfig.applySeeding(COLONY_NATURAL_SPACING.get(), COLONY_NATURAL_CHANCE.get());
        ColonyConfig.applyBudding(COLONY_BUD_CHANCE.get(), COLONY_BUD_POPULATION.get(),
                COLONY_BUD_COOLDOWN.get(), COLONY_BUD_CAP_BASE.get(), COLONY_BUD_CAP_PER_TIER.get(),
                COLONY_REINFORCED_EVOLUTION.get());
        GlyphidStats.applyPace(GLYPHID_PACE.get());
    }
}
