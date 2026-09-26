package com.wf.wflib.armor;

import com.wf.wflib.WFLib;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@link ArmorMaterial} per worn preset, existing almost entirely so vanilla has something to
 * hang the armour texture on.
 *
 * <p>They declare ordinary iron-grade defense, which looks like a contradiction and is not. A piece
 * cannot have two authorities, so while this system is switched on {@code ArmorEvents} strips those
 * attributes off every piece it manages, and the numbers below never reach a damage calculation. What
 * they are for is the master switch: with {@code armor.enabled} off, a plate carrier should be a chest
 * piece rather than a cosmetic, and these are what it falls back to.
 */
public final class ArmorMaterials {

    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, WFLib.MODID);

    private static final Map<ResourceLocation, DeferredHolder<ArmorMaterial, ArmorMaterial>> BY_PRESET =
            new LinkedHashMap<>();

    /** Iron's spread, and only ever read when this system is switched off. */
    private static final Map<ArmorItem.Type, Integer> FALLBACK_DEFENSE = Map.of(
            ArmorItem.Type.HELMET, 2,
            ArmorItem.Type.CHESTPLATE, 6,
            ArmorItem.Type.LEGGINGS, 5,
            ArmorItem.Type.BOOTS, 2,
            ArmorItem.Type.BODY, 6);

    static {
        for (ArmorPreset preset : ArmorPresets.all()) {
            if (preset.isInsert()) {
                continue;
            }
            BY_PRESET.put(preset.id(), MATERIALS.register(preset.path(), () -> new ArmorMaterial(
                    FALLBACK_DEFENSE,
                    9,
                    SoundEvents.ARMOR_EQUIP_IRON,
                    () -> Ingredient.EMPTY,
                    // Named after the preset, so the texture lives at
                    // textures/models/armor/<preset>_layer_1.png. A preset drawn by GemRender never
                    // reaches this, but it still needs a material to be an ArmorItem at all.
                    List.of(new ArmorMaterial.Layer(preset.id())),
                    0.0F,
                    0.0F)));
        }
    }

    private ArmorMaterials() {
    }

    public static Holder<ArmorMaterial> of(ArmorPreset preset) {
        DeferredHolder<ArmorMaterial, ArmorMaterial> holder = BY_PRESET.get(preset.id());
        if (holder == null) {
            throw new IllegalStateException("no armour material for " + preset.id()
                    + " (inserts have none: " + preset.isInsert() + ")");
        }
        return holder;
    }

    public static void register(IEventBus bus) {
        MATERIALS.register(bus);
    }
}
