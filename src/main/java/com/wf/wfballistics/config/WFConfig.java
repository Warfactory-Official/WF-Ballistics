package com.wf.wfballistics.config;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.compat.WarforgeCompat;
import com.wf.wfballistics.sim.MissileSimConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Server/common config for the tunables most worth adjusting without recompiling: the WarForge integration
 * toggles, the interceptor combat numbers, and default fuel. Register {@link #SPEC} in the mod constructor
 * ({@code context.registerConfig(ModConfig.Type.COMMON, WFConfig.SPEC)}). On (re)load the values are copied
 * into the plain static fields the gameplay code reads (see {@link MissileSimConfig}), so nothing else has to
 * know about the config.
 */
public final class WFConfig {

    public static final ForgeConfigSpec SPEC;

    // --- WarForge integration ---
    public static final ForgeConfigSpec.BooleanValue WARFORGE_FACTION_FOF;
    public static final ForgeConfigSpec.BooleanValue WARFORGE_CLAIM_PROTECTION;
    // --- Interception ---
    public static final ForgeConfigSpec.DoubleValue INTERCEPT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue INTERCEPT_CROSSING_FACTOR;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_KILL_RADIUS;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_ACQUIRE_RANGE;
    public static final ForgeConfigSpec.DoubleValue SUPERSONIC_SPEED;
    public static final ForgeConfigSpec.BooleanValue INTERCEPTOR_CHIP_MODE;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_HIT_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_GRAZE_DAMAGE;
    // --- Stealth ---
    public static final ForgeConfigSpec.DoubleValue STEALTH_DETECT_RANGE;
    public static final ForgeConfigSpec.DoubleValue STEALTH_DETECT_CHANCE;
    // --- Evasion ---
    public static final ForgeConfigSpec.DoubleValue DIVE_EVASION_MULTIPLIER;
    // --- Batteries ---
    public static final ForgeConfigSpec.IntValue BATTERY_MAGAZINE;
    public static final ForgeConfigSpec.IntValue BATTERY_RELOAD_TICKS;
    // --- Fuel ---
    public static final ForgeConfigSpec.IntValue DEFAULT_FUEL_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

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
                        "instead of a binary destroy/miss — interceptors + CIWS combine and missile toughness",
                        "(health) matters. If false, a successful roll destroys the target outright.")
                .define("chipMode", false);
        INTERCEPTOR_HIT_DAMAGE = b
                .comment("Chip mode: health damage a successful intercept deals.")
                .defineInRange("hitDamage", 60.0, 0.0, 100000.0);
        INTERCEPTOR_GRAZE_DAMAGE = b
                .comment("Chip mode: health damage a missed intercept deals.")
                .defineInRange("grazeDamage", 8.0, 0.0, 100000.0);
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
        MissileSimConfig.STEALTH_DETECT_RANGE = STEALTH_DETECT_RANGE.get();
        MissileSimConfig.STEALTH_DETECT_CHANCE = STEALTH_DETECT_CHANCE.get().floatValue();
        MissileSimConfig.DIVE_EVASION_MULTIPLIER = DIVE_EVASION_MULTIPLIER.get();
        MissileSimConfig.BATTERY_MAGAZINE = BATTERY_MAGAZINE.get();
        MissileSimConfig.BATTERY_RELOAD_TICKS = BATTERY_RELOAD_TICKS.get();
        MissileEntity.DEFAULT_FUEL_TICKS = DEFAULT_FUEL_TICKS.get();
    }
}
