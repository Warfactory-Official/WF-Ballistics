package com.wf.wflib.rail;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Rail feature tuning. Its own file, so the rail package stays liftable out of the mod in one piece.
 */
public final class RailConfig {

    public static final ModConfigSpec SPEC;

    // --- Excavation ---
    public static final ModConfigSpec.BooleanValue EXCAVATION_ENABLED;
    public static final ModConfigSpec.IntValue EXCAVATION_WORKERS;
    public static final ModConfigSpec.IntValue EXCAVATION_IN_FLIGHT;
    public static final ModConfigSpec.IntValue ATTENDED_BLOCK_BUDGET;
    public static final ModConfigSpec.IntValue CLAIM_KEEPOUT;
    public static final ModConfigSpec.BooleanValue DROP_ATTENDED_BLOCKS;
    public static final ModConfigSpec.BooleanValue EXCAVATION_STATS;
    public static final ModConfigSpec.ConfigValue<String> TUNNEL_LINING;
    public static final ModConfigSpec.ConfigValue<String> TUNNEL_PROFILE;
    public static final ModConfigSpec.BooleanValue TUNNEL_LIGHTING;
    public static final ModConfigSpec.BooleanValue TUNNEL_TRACK;

    // --- Track laying ---
    public static final ModConfigSpec.IntValue TRACK_BOOST_SPACING;
    public static final ModConfigSpec.DoubleValue BORE_TRAIN_SPEED;
    public static final ModConfigSpec.ConfigValue<String> IR_TRACK;
    public static final ModConfigSpec.DoubleValue IR_GAUGE;
    public static final ModConfigSpec.IntValue SPOIL_CARS;
    public static final ModConfigSpec.ConfigValue<String> TRACK_MATERIAL;
    public static final ModConfigSpec.DoubleValue TURNOUT_LEAD;

    private RailConfig() {
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Terrain excavation. Carving an unloaded chunk rewrites its stored NBT directly, off-thread,",
                        "and never touches a loaded chunk - see the class docs on ExcavationService for the",
                        "claim model and what it deliberately does not guarantee.")
                .push("excavation");

        EXCAVATION_ENABLED = b
                .comment("Master switch for off-thread excavation. Off means every carve falls back to the",
                        "attended path (normal block breaks on the server thread), which is correct but slow.")
                .define("enabled", true);

        EXCAVATION_WORKERS = b
                .comment("Worker threads for stored-chunk carving. Clamped to available processors minus two,",
                        "so it can never starve the server thread or the drone AI pool.")
                .defineInRange("workers", 2, 1, 8);

        EXCAVATION_IN_FLIGHT = b
                .comment("How many chunk columns may be claimed and in flight at once. This is the abort-cost",
                        "dial: every in-flight column is work that gets thrown away when a player walks up.")
                .defineInRange("maxInFlight", 4, 1, 32);

        ATTENDED_BLOCK_BUDGET = b
                .comment("Blocks per tick the attended path may break in a loaded chunk. Keeps a bore cutting",
                        "through a loaded area from stalling the tick it runs on.")
                .defineInRange("attendedBlockBudget", 64, 1, 4096);

        CLAIM_KEEPOUT = b
                .comment("Chunks of clearance kept between a claimed column and the nearest player. A claim is",
                        "revoked the moment a player comes inside this, before the chunk itself loads.")
                .defineInRange("claimKeepout", 3, 1, 32);

        DROP_ATTENDED_BLOCKS = b
                .comment("Whether the attended path drops the blocks it breaks. Off routes spoil straight to the",
                        "consist's hoppers instead of spawning item entities.")
                .define("dropAttendedBlocks", false);

        EXCAVATION_STATS = b
                .comment("Record the per-carve phase breakdown read/queue/carve/store/flush, via",
                        "/wfrail stats phases. A few nanoTime calls against a ~700us column, and the only",
                        "thing that says what to make faster - the flush is 15x everything else put",
                        "together under sync-chunk-writes.")
                .define("stats", true);

        TUNNEL_LINING = b
                .comment("Block a bored tunnel is lined with. The lining goes in before the bore is opened,",
                        "which is what keeps an aquifer or a lava lake out, so it has to be something solid",
                        "and full: a slab or a fence would leave the tunnel open to whatever it cut into.")
                .define("tunnelLining", "minecraft:deepslate_bricks");

        TUNNEL_PROFILE = b
                .comment("Which drawn section /wfrail build uses when it is not told one. Sections live in",
                        "config/wflib-rail-profiles as plain text you can edit or add to; '.' is tunnel,",
                        "'#' is lining and 'L' is a torch.")
                .define("tunnelProfile", "standard");

        TUNNEL_LIGHTING = b
                .comment("Whether to run the lighting pass. The section decides where its torches go and",
                        "how far apart; this only decides whether they go in at all. Off leaves a sealed",
                        "unlit tunnel, which mobs will spawn in within a minute.")
                .define("tunnelLighting", true);

        TUNNEL_TRACK = b
                .comment("Whether a finished bore has track laid in it. The point of a tunnel on a rail",
                        "route is the railway, and a tunnel with no rails in it is a corridor.")
                .define("tunnelTrack", true);

        b.pop();

        b.comment("Laying track. With Immersive Railroading installed a route is built as real IR track:",
                        "cubic curves in IR's own graph, which is what stock runs on. Without it, vanilla",
                        "rail, which a minecart runs on and an IR locomotive does not.",
                        "Where routes meet, /wfrail junctions builds the junction: two lines that cross",
                        "share their crossing blocks, and a branch gets a real switch you can throw.")
                .push("track");

        TRACK_BOOST_SPACING = b
                .comment("Blocks between powered rails, each with its own block of redstone buried under",
                        "it. Zero lays plain rail throughout, which needs something else to push a cart.")
                .defineInRange("boostSpacing", 8, 0, 64);

        BORE_TRAIN_SPEED = b
                .comment("How fast a bore train drives, in blocks per second. This is a machine you stand",
                        "and watch, so the default is a pace you can walk alongside; the cost per block",
                        "is the same however fast it goes.")
                .defineInRange("boreTrainSpeed", 8.0, 0.5, 64.0);

        IR_TRACK = b
                .comment("Which Immersive Railroading track definition a machine builds. IR ships",
                        "'default', 'concrete' and 'railsonly'; a pack may add more. An id this install",
                        "does not have falls back to 'default' with a line in the log.")
                .define("irTrack", "default");

        IR_GAUGE = b
                .comment("Track gauge in metres. 1.435 is standard gauge, which is what IR's stock is",
                        "built to; narrow gauge stock will not run on it and vice versa, so this is a",
                        "decision about the whole railway rather than about one tunnel.")
                .defineInRange("irGauge", 1.435, 0.1, 10.0);

        SPOIL_CARS = b
                .comment("Most spoil cars a bore train will grow to. It starts with one and couples",
                        "another on whenever the last is full, because a tunnel produces roughly a",
                        "block of spoil per block cut and a fixed pair of cars throws most of a long",
                        "one away. Past this it keeps digging and counts what it left behind.")
                .defineInRange("spoilCars", 16, 1, 64);

        TRACK_MATERIAL = b
                .comment("What one block of track is paid for with, when a train is building against a",
                        "depot. The tunnel's own lining block pays for the lining, so that one follows",
                        "the section and is not set here; this is the rail itself. An id this install",
                        "does not have falls back to minecraft:rail.")
                .define("trackMaterial", "minecraft:rail");

        TURNOUT_LEAD = b
                .comment("How far along the branch a turnout's diverging curve runs, in blocks. This is",
                        "the turnout's number in the railway sense: short is sharp and slow, long is",
                        "shallow and fast, and it costs that much straight taken out of the main line.",
                        "The curve always leaves tangent to the main line, whatever this is set to.")
                .defineInRange("turnoutLead", 14.0, 4.0, 64.0);

        b.pop();

        SPEC = b.build();
    }
}
