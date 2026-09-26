package com.wf.wflib.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WFClientConfig {

    public static final ModConfigSpec SPEC;

    // --- Debug ---
    public static final ModConfigSpec.BooleanValue SHOW_MISSILE_TARGETS;

    // --- Gas clouds ---
    public static final ModConfigSpec.BooleanValue VOLUMETRIC_GAS;
    public static final ModConfigSpec.ConfigValue<String> VOLUMETRIC_GAS_QUALITY;

    /**
     * Overrides for the two gas settings, so a dev run can flip them from the command line without editing (or
     * racing) the generated toml.
     */
    private static final String VOLUMETRIC_GAS_PROPERTY = "wflib.volumetricGas";
    private static final String VOLUMETRIC_GAS_QUALITY_PROPERTY = "wflib.volumetricGasQuality";

    // --- Drone camera feeds ---
    public static final ModConfigSpec.IntValue CAMERA_MAX_WIDTH;
    public static final ModConfigSpec.IntValue CAMERA_MAX_FPS;
    public static final ModConfigSpec.IntValue CAMERA_MIN_FPS;
    public static final ModConfigSpec.DoubleValue CAMERA_FULL_RATE_DISTANCE;
    public static final ModConfigSpec.IntValue CAMERA_BUDGET;
    public static final ModConfigSpec.IntValue CAMERA_FEED_VIEW_DISTANCE;
    public static final ModConfigSpec.BooleanValue CAMERA_EFFECTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Debug overlays (singleplayer diagnostics).").push("debug");
        SHOW_MISSILE_TARGETS = b
                .comment("Draw a green box at every missile's current aim point, with a line back to the missile,",
                        "so you can see where each one (recursive missilelets especially) is actually aiming and",
                        "whether that point is on ground or floating in the air. Reads the server-side target off the",
                        "integrated server, so it only works in singleplayer. Off by default.")
                .define("showMissileTargets", false);
        b.pop();

        b.comment("Gas and mist clouds.").push("gas");
        VOLUMETRIC_GAS = b
                .comment("Draw gas clouds by raymarching a density field through the cloud's bounding box",
                        "instead of stacking hundreds of billboard particles. One draw call per cloud, with",
                        "self-shadowing, so a cloud reads as lit smoke with real depth rather than as a pile",
                        "of sprites, and it no longer thins out when you walk into it, because there are no",
                        "sprites to run out of. The cost moves from particle count to screen coverage: a",
                        "distant cloud is nearly free and one filling the screen is the expensive case, which",
                        "is the opposite of how the billboard path behaves. Needs Flywheel.")
                .define("volumetric", false);
        VOLUMETRIC_GAS_QUALITY = b
                .comment("Steps the raymarch takes, and whether it marches a second time toward the key",
                        "light for self-shadowing: LOW is 24 steps and no shadowing, which reads as flat fog;",
                        "MEDIUM is 48 with a 4-step light march; HIGH is 96 with 6. MEDIUM is the first",
                        "setting that looks like smoke. Ignored unless volumetric is on.")
                .define("volumetricQuality", "MEDIUM");
        b.pop();

        b.comment("Drone camera feeds.").push("droneCamera");
        CAMERA_MAX_WIDTH = b
                .comment("Widest a feed is ever rendered, in pixels; height follows at 16:9. A feed is also",
                        "capped by how large it is actually being drawn, so a monitor across the room costs a",
                        "fraction of this whatever it is set to: raising it only helps a full-screen feed.",
                        "1280 (720p) is the default; 1920 (1080p) is the ceiling.")
                .defineInRange("maxWidth", 1280, 256, 1920);
        CAMERA_MAX_FPS = b
                .comment("Frames per second for the feed the operator is actually looking at. Still the most",
                        "expensive setting here (a feed frame is a second pass over the terrain), but it is",
                        "now a pass and not much more: each feed carries its own section visibility graph, so",
                        "it no longer forces the main view's to be rebuilt as well. 120 is the ceiling.")
                .defineInRange("maxFps", 60, 1, 120);
        CAMERA_MIN_FPS = b
                .comment("Frames per second a feed falls to when it is small or far away. Low is fine: a",
                        "monitor read across a room is legible at ten, and the OSD is composited after the",
                        "video so the readouts never look choppy.")
                .defineInRange("minFps", 10, 1, 120);
        CAMERA_FULL_RATE_DISTANCE = b
                .comment("Blocks within which a monitor gets the full frame rate, falling off linearly to",
                        "minFps at the distance a monitor stops drawing at all.")
                .defineInRange("fullRateDistance", 10.0, 0.0, 64.0);
        CAMERA_BUDGET = b
                .comment("How many feeds may be re-rendered in a single frame. One is strongly recommended and",
                        "is enough for any number of feeds: they take turns, and a wall of eight monitors",
                        "showing eight different drones costs exactly what one monitor costs, each just",
                        "updating an eighth as often.")
                .defineInRange("framesPerTick", 1, 1, 4);
        CAMERA_FEED_VIEW_DISTANCE = b
                .comment("Chunks either side of the drone a feed renders, and the size of the section grid it",
                        "keeps to do it. This is a bubble around the drone, not a distance from you: the server",
                        "streams the terrain for it, so a drone a kilometre out sees exactly as far as one",
                        "overhead. Should match the server's droneCameraStreaming.radius: larger than the",
                        "server sends only builds empty sections, smaller than it sends wastes what it sent.",
                        "Each feed being drawn holds a grid this size, so this is the setting to lower first if",
                        "a wall of monitors costs memory.")
                .defineInRange("feedViewDistance", 8, 2, 16);
        CAMERA_EFFECTS = b
                .comment("Run the video post chain (macroblocking, sensor noise, desaturation, thermal",
                        "palette) on feeds and on vehicle sights that use it. Turning this off leaves a clean picture at the same frame rate and saves",
                        "two full-screen passes per feed frame, and loses the link quality read-out that the",
                        "artifacts are, so a failing datalink stops being visible.")
                .define("videoEffects", true);
        b.pop();

        SPEC = b.build();
    }

    /** Whether gas should be raymarched rather than drawn as billboards. */
    public static boolean volumetricGas() {
        String override = System.getProperty(VOLUMETRIC_GAS_PROPERTY);
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        try {
            return VOLUMETRIC_GAS.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** The raymarch quality name; see {@link #volumetricGas()} for why this is not just a config read. */
    public static String volumetricGasQuality() {
        String override = System.getProperty(VOLUMETRIC_GAS_QUALITY_PROPERTY);
        if (override != null) {
            return override;
        }
        try {
            return VOLUMETRIC_GAS_QUALITY.get();
        } catch (IllegalStateException e) {
            return "MEDIUM";
        }
    }

    private WFClientConfig() {
    }
}
