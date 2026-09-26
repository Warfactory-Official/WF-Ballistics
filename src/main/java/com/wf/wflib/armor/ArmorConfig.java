package com.wf.wflib.armor;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Armour tunables, in their own file so the package stays separable from the rest of the mod.
 *
 * <p>Values are copied into plain static fields on load, the same way {@code WFConfig} does it, so
 * the hot path reads a field rather than a config value and a gametest can set one directly.
 */
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

    /** Master switch. Off means vanilla armour, untouched, exactly as if this package were absent. */
    public static boolean enabled = true;

    /**
     * Whether armour this system was never told about is given a profile derived from its own vanilla
     * numbers. On is the point of the derivation: it means the pack's GregTech and NTM armour works
     * without any of it having been registered. Off restricts the model to authored pieces and leaves
     * everything else on vanilla's armour attributes.
     */
    public static boolean manageVanillaArmor = true;

    /** Whether the whole-entity door runs for mobs, or only for players. */
    public static boolean mobArmor = true;

    /**
     * How often contact with fire, gas or a field is charged.
     *
     * <p>Five ticks, which is a quarter of a second, which is about how fast a person reacts. Below that
     * the extra resolution is charging damage nobody could have avoided; above it, a player who did react
     * in time is charged anyway for the part of the interval they were already out of it.
     *
     * <p><b>This is a balance lever, not only a performance one.</b> A hazard's tier is points per
     * interval, so doubling this halves every environmental wear rate in the game.
     */
    public static int exposureIntervalTicks = 5;

    /**
     * How often accrued exposure is written to the stacks. Coarser than accrual on purpose: a component
     * written on a worn stack is an equipment-sync packet, and that packet, not the allocation, is what
     * a per-tick write actually costs. Accrual is integer, so coarsening the write loses nothing.
     */
    public static int persistIntervalTicks = 100;

    /**
     * What being on fire and standing in lava deal, in condition points per exposure interval. Same
     * units as a suit's THERMAL tier, so a suit rated above one of these shrugs it off outright.
     *
     * <p>For scale: a 600-durability garment is 60,000 points, and there are four intervals a second, so
     * fire at 60 burns an unrated one off in about four minutes and lava at 400 in under forty seconds.
     */
    public static double fireTier = 60.0D;
    public static double lavaTier = 400.0D;

    /**
     * Share of maximum condition that walking into the fire or the gas costs at once, before the
     * timed decay takes over. STALKER's split between a splash and a stay: the splash is a percentage
     * so it costs cheap kit and power armour alike, which is what stops heavy armour being the
     * universal answer. Flat, and not scaled by how bad the hazard is: what scales it is the piece's
     * own resistance to the type, which is a number it already has.
     */
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
            // A derived profile is cached per item, and the negative cache is what makes the miss cheap.
            // Both are wrong the moment this flips, so neither may survive the change.
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
