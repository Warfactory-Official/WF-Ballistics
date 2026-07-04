package com.wf.wfballistics.config;

import net.minecraftforge.common.ForgeConfigSpec;



public final class WFClientConfig {

    public static final ForgeConfigSpec SPEC;

    // --- Translucent terrain ---
    public static final ForgeConfigSpec.BooleanValue SOLID_TRANSLUCENT;

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

        SPEC = b.build();
    }

    private WFClientConfig() {
    }
}
