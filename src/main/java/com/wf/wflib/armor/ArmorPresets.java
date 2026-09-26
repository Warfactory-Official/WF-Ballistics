package com.wf.wflib.armor;

import com.wf.wflib.item.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The armour this mod ships, and the worked examples for the model.
 *
 * <p>Three things are being demonstrated here rather than balanced: a garment that is good at what
 * soft armour is good at and bad at what it is not; an insert that fills exactly that gap and is a
 * cheap consumable in front of an expensive base; and a suit whose whole value is a tier rating that
 * makes a hazard survivable indefinitely rather than merely survivable for longer.
 */
public final class ArmorPresets {

    private static final Map<ResourceLocation, ArmorPreset> PRESETS = new LinkedHashMap<>();
    private static boolean registered;

    static {
        // A vest: stabs and blunt force, which is what a weave and padding are for. Nearly useless
        // against rifle fire on its own, and that is the point of the pockets on the front of it.
        add(ArmorPreset.piece("plate_carrier", ArmorItem.Type.CHESTPLATE, 600)
                .row(ProtectionType.SHARP, 4.0D, 0.45D, 3.0D, 0.0D)
                .row(ProtectionType.IMPACT, 2.0D, 0.35D, 2.0D, 0.0D)
                .row(ProtectionType.KINETIC, 2.0D, 0.10D, 1.0D, 0.0D)
                .row(ProtectionType.BLAST, 1.0D, 0.15D, 1.0D, 0.0D)
                .inserts(2, ProtectionType.KINETIC, ProtectionType.BLAST)
                .wornFloor(0.10D)
                .build());

        // Steel: a real threshold, and hard enough that buckshot marks it rather than grinding it down.
        // Wears slowly and keeps working when it is spent, because it is still steel afterwards.
        add(ArmorPreset.insert("steel_plate", 3000)
                .row(ProtectionType.KINETIC, 20.0D, 0.50D, 15.0D, 0.0D)
                .row(ProtectionType.BLAST, 6.0D, 0.25D, 8.0D, 0.0D)
                .wornFloor(0.60D)
                .build());

        // Ceramic: stops more than steel and dies doing it. Same two numbers, opposite material.
        add(ArmorPreset.insert("ceramic_plate", 1200)
                .row(ProtectionType.KINETIC, 28.0D, 0.55D, 12.0D, 0.0D)
                .row(ProtectionType.BLAST, 4.0D, 0.20D, 6.0D, 0.0D)
                .wornFloor(0.0D)
                .build());

        // A sealed suit. Its whole value is the tier: a field it is rated for is survivable forever in
        // it, which is not something a percentage can express. Stops nothing at all that arrives as a
        // bullet. The tiers are in condition points per exposure interval, the same units the hazards
        // are authored in, so 200 means "stands in anything up to a full-strength gas cloud" and 80
        // means "will not catch fire, but lava is still lava".
        add(ArmorPreset.piece("hazmat_suit", ArmorItem.Type.CHESTPLATE, 400)
                .row(ProtectionType.CHEMICAL, 0.0D, 0.85D, 0.0D, 200.0D)
                .row(ProtectionType.THERMAL, 0.0D, 0.20D, 0.0D, 80.0D)
                .row(ProtectionType.IMPACT, 0.0D, 0.05D, 0.0D, 0.0D)
                .build());

        add(ArmorPreset.piece("combat_helmet", ArmorItem.Type.HELMET, 350)
                .row(ProtectionType.KINETIC, 6.0D, 0.25D, 5.0D, 0.0D)
                .row(ProtectionType.SHARP, 6.0D, 0.40D, 4.0D, 0.0D)
                .row(ProtectionType.IMPACT, 4.0D, 0.40D, 3.0D, 0.0D)
                .row(ProtectionType.BLAST, 2.0D, 0.20D, 2.0D, 0.0D)
                .wornFloor(0.25D)
                .build());
    }

    private ArmorPresets() {
    }

    private static void add(ArmorPreset preset) {
        PRESETS.put(preset.id(), preset);
    }

    public static void register(ArmorPreset preset) {
        if (registered) {
            throw new IllegalStateException("armour presets are enumerated into items at registry time: "
                    + preset.id() + " is too late");
        }
        add(preset);
    }

    public static Collection<ArmorPreset> all() {
        return Collections.unmodifiableCollection(PRESETS.values());
    }

    @Nullable
    public static ArmorPreset get(ResourceLocation id) {
        return PRESETS.get(id);
    }

    /**
     * Hands every preset's spec to {@link ArmorProfiles}, once the items exist. Separate from the
     * static block above on purpose: that one runs while the item registry is still being built, and
     * a profile needs the item it belongs to.
     */
    public static void bootstrap() {
        if (registered) {
            return;
        }
        registered = true;
        for (ArmorPreset preset : PRESETS.values()) {
            Item item = ModItems.armorItem(preset.id());
            if (item != null) {
                ArmorProfiles.register(item, preset.spec());
            }
        }
    }
}
