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

        b.pop();

        SPEC = b.build();
    }
}
