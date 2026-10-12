package com.wf.wflib.armor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;

import java.util.Map;

/**
 * Dev-only test pieces: Modular Warfare archive models ({@code modular-archive-gltf}) via GemRender.
 * Assets come from the {@code armor_frame.py} resource pack; missing => nothing drawn.
 */
final class MwTestArmor {

    private MwTestArmor() {
    }

    static void addTo(Map<ResourceLocation, ArmorPreset> presets) {
        add(presets, "mw_6b47", ArmorItem.Type.HELMET, "armed_forces_of_the_russian_federation/armor/6b47");
        add(presets, "mw_bearsuit", ArmorItem.Type.CHESTPLATE, "eftc/armor/bearsuit");
        add(presets, "mw_bearpants", ArmorItem.Type.LEGGINGS, "eftc/armor/bearpants");
        add(presets, "mw_uniform", ArmorItem.Type.CHESTPLATE,
                "armed_forces_of_the_russian_federation/armor/russianuniform");
        add(presets, "mw_exo", ArmorItem.Type.CHESTPLATE, "kanonarmor__kanonarmor/armor/exokarkas");
    }

    private static void add(Map<ResourceLocation, ArmorPreset> presets, String path, ArmorItem.Type type,
                            String model) {
        ArmorPreset preset = ArmorPreset.piece(path, type, 100)
                .row(ProtectionType.IMPACT, 0.0D, 0.05D, 0.0D, 0.0D)
                .model(ResourceLocation.fromNamespaceAndPath("mwarchive", "models/" + model + ".gltf"))
                .build();
        presets.put(preset.id(), preset);
    }
}
