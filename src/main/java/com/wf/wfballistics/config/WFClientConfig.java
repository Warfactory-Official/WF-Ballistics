package com.wf.wfballistics.config;

import net.minecraftforge.common.ForgeConfigSpec;


public final class WFClientConfig {

    public static final ForgeConfigSpec SPEC;

    // --- Translucent terrain ---
    public static final ForgeConfigSpec.BooleanValue SOLID_TRANSLUCENT;

    // --- Debug ---
    public static final ForgeConfigSpec.BooleanValue SHOW_MISSILE_TARGETS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Rendering options.").push("rendering");
        SOLID_TRANSLUCENT = b
                .comment("Render translucent terrain (water, ice, stained glass, ...) as opaque on Fancy and",
                        "Fast graphics: cheaper than blended translucency, and it lets the instanced translucent",
                        "effects (rocket exhaust) occlude against it correctly instead of showing through. Fabulous",
                        "graphics is left untouched, keeping real (heavier) transparency there.")
                .define("solidTranslucentTerrain", true);
        b.pop();

        b.comment("Debug overlays (singleplayer diagnostics).").push("debug");
        SHOW_MISSILE_TARGETS = b
                .comment("Draw a green box at every missile's current aim point, with a line back to the missile,",
                        "so you can see where each one (recursive missilelets especially) is actually aiming and",
                        "whether that point is on ground or floating in the air. Reads the server-side target off the",
                        "integrated server, so it only works in singleplayer. Off by default.")
                .define("showMissileTargets", false);
        b.pop();

        SPEC = b.build();
    }

    private WFClientConfig() {
    }
}
