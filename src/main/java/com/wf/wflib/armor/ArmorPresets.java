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

/** Shipped armour, as worked examples for the model (not balance-tuned): soft-armour niche, a cheap insert filling its gap, a hazmat suit whose tier rating makes a hazard survivable indefinitely. */
public final class ArmorPresets {

    private static final Map<ResourceLocation, ArmorPreset> PRESETS = new LinkedHashMap<>();
    private static boolean registered;

    static {
        // Vest: weave+padding -> sharp/impact; weak vs kinetic alone by design, pockets exist for plates.
        add(ArmorPreset.piece("plate_carrier", ArmorItem.Type.CHESTPLATE, 600)
                .row(ProtectionType.SHARP, 4.0D, 0.45D, 3.0D, 0.0D)
                .row(ProtectionType.IMPACT, 2.0D, 0.35D, 2.0D, 0.0D)
                .row(ProtectionType.KINETIC, 2.0D, 0.10D, 1.0D, 0.0D)
                .row(ProtectionType.BLAST, 1.0D, 0.15D, 1.0D, 0.0D)
                .inserts(2, ProtectionType.KINETIC, ProtectionType.BLAST)
                .wornFloor(0.10D)
                .build());

        // Steel: high hardness threshold (buckshot marks, doesn't grind); high wornFloor, still steel once spent.
        add(ArmorPreset.insert("steel_plate", 3000)
                .row(ProtectionType.KINETIC, 20.0D, 0.50D, 15.0D, 0.0D)
                .row(ProtectionType.BLAST, 6.0D, 0.25D, 8.0D, 0.0D)
                .wornFloor(0.60D)
                .build());

        // Ceramic: higher DT/DR than steel, wornFloor 0 (shatters).
        add(ArmorPreset.insert("ceramic_plate", 1200)
                .row(ProtectionType.KINETIC, 28.0D, 0.55D, 12.0D, 0.0D)
                .row(ProtectionType.BLAST, 4.0D, 0.20D, 6.0D, 0.0D)
                .wornFloor(0.0D)
                .build());

        // Sealed suit: value is tier, not DT/DR (near-zero vs bullets). tier=200 chemical -> shrugs a
        // full-strength gas cloud; tier=80 thermal -> fire-immune, lava still hurts.
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

        if (!net.neoforged.fml.loading.FMLEnvironment.production) {
            MwTestArmor.addTo(PRESETS);
        }
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
     * Registers each preset's spec with {@link ArmorProfiles}, once items exist. Separate from the
     * static initializer above: that one runs during item-registry construction, before any item
     * object exists to register a profile against.
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
