package com.wf.wflib.armor;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Armour tunables. Mirrors {@code WFConfig}: values copied to static fields on load so the hot path avoids spec reads and gametests can set fields directly. */
public final class ArmorConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED_VALUE;
    private static final ModConfigSpec.BooleanValue MANAGE_VANILLA_VALUE;
    private static final ModConfigSpec.BooleanValue MOB_ARMOR_VALUE;
    private static final ModConfigSpec.IntValue EXPOSURE_INTERVAL_VALUE;
    private static final ModConfigSpec.IntValue PERSIST_INTERVAL_VALUE;
    private static final ModConfigSpec.DoubleValue FIRE_TIER_VALUE;
    private static final ModConfigSpec.DoubleValue LAVA_TIER_VALUE;
    private static final ModConfigSpec.DoubleValue ACUTE_FRACTION_VALUE;

    /** Off => vanilla armour, untouched. */
    public static boolean enabled = true;

    /** Derive a profile from vanilla defense/toughness for unregistered armour (e.g. GregTech, NTM); off restricts to authored pieces. */
    public static boolean manageVanillaArmor = true;

    /** Whole-entity resolution also covers mobs; off = players only. */
    public static boolean mobArmor = true;

    /** Ticks between exposure charges. Balance lever, not just perf: hazard tier is points/interval, so doubling this halves every environmental wear rate. */
    public static int exposureIntervalTicks = 5;

    /** Ticks between flushing accrued exposure to stacks. Coarser than accrual: a component write on a worn stack is an equipment-sync packet, the real cost; integer accrual means coarsening loses nothing. */
    public static int persistIntervalTicks = 100;

    /** Fire/lava damage, condition points per exposure interval; same units as a suit's THERMAL tier. */
    public static double fireTier = 60.0D;
    public static double lavaTier = 400.0D;

    /** Flat share of max condition lost on first contact with a hazard, before sustained decay. Percentage, not points, so cheap and powered armour pay the same proportion; hazard severity is read via tier, not this. */
    public static double acuteFraction = 0.04D;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Warfactory armour: one DT/DR resolver for every hit, per protection type.").push("armor");
        ENABLED_VALUE = builder
                .comment("Master switch. Off leaves vanilla armour entirely alone.")
                .define("enabled", true);
        MANAGE_VANILLA_VALUE = builder
                .comment("Give armour with no authored profile one derived from its own vanilla defense",
                        "and toughness, and strip those attributes so it is not counted twice.",
                        "Off restricts this system to authored pieces.")
                .define("manageVanillaArmor", true);
        MOB_ARMOR_VALUE = builder
                .comment("Resolve armour for mobs as well as players.")
                .define("mobArmor", true);
        EXPOSURE_INTERVAL_VALUE = builder
                .comment("Ticks between charges for standing in fire, gas or a field. Five ticks is a",
                        "quarter of a second, about human reaction time.",
                        "A balance lever as well as a performance one: a hazard's tier is points per",
                        "interval, so doubling this halves every environmental wear rate in the game.")
                .defineInRange("exposureIntervalTicks", 5, 1, 1200);
        PERSIST_INTERVAL_VALUE = builder
                .comment("Ticks between writing accrued exposure onto the armour stacks.")
                .defineInRange("persistIntervalTicks", 100, 1, 12000);
        FIRE_TIER_VALUE = builder
                .comment("What being on fire deals, in condition points per exposure interval.")
                .defineInRange("fireTier", 60.0D, 0.0D, 1.0E7D);
        LAVA_TIER_VALUE = builder
                .comment("What standing in lava deals, in condition points per exposure interval.")
                .defineInRange("lavaTier", 400.0D, 0.0D, 1.0E7D);
        ACUTE_FRACTION_VALUE = builder
                .comment("Share of maximum condition that first contact with a hazard costs outright.")
                .defineInRange("acuteFraction", 0.04D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private ArmorConfig() {
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        enabled = ENABLED_VALUE.get();
        boolean manage = MANAGE_VANILLA_VALUE.get();
        if (manage != manageVanillaArmor) {
            // manage flips => cached derived specs and the negative cache are both stale.
            ArmorProfiles.clearDerived();
        }
        manageVanillaArmor = manage;
        mobArmor = MOB_ARMOR_VALUE.get();
        exposureIntervalTicks = EXPOSURE_INTERVAL_VALUE.get();
        persistIntervalTicks = PERSIST_INTERVAL_VALUE.get();
        fireTier = FIRE_TIER_VALUE.get();
        lavaTier = LAVA_TIER_VALUE.get();
        acuteFraction = ACUTE_FRACTION_VALUE.get();
    }
}
