package com.wf.wflib.armor.client;

import com.wf.wflib.armor.ArmorPreset;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No {@code model} on a preset => vanilla's humanoid armour model, untouched by GemRender. A rigged
 * glTF routes through GemRender's {@code getHumanoidArmorModel}, the same hook vanilla's armour uses,
 * so trims, glint and dyed leather still work.
 *
 * <p>{@code HumanoidArmorLayer.renderArmorPiece} calls {@code renderToBuffer} once per material layer
 * plus once each for trim and glint; a self-drawn model must draw once per {@code prepare} instead, or
 * enchanted armour costs multiples and composites blended geometry on itself. GemRender's prepare/
 * render split exists for this.
 *
 * <p>GemRender missing from the client's mod list => a glTF piece draws nothing and says so once.
 */
public final class ArmorPieceClient {

    private static final IClientItemExtensions VANILLA = new IClientItemExtensions() {
    };

    private static final Map<ResourceLocation, IClientItemExtensions> BY_MODEL = new ConcurrentHashMap<>();

    private ArmorPieceClient() {
    }

    public static IClientItemExtensions of(ArmorPreset preset) {
        ResourceLocation model = preset.model();
        // Null check first: GemRenderArmor only classloads on first use, so a mod with no glTF
        // armour never touches GemRender's model types at all.
        return model == null ? VANILLA : BY_MODEL.computeIfAbsent(model, GemRenderArmor::new);
    }

    private static final class GemRenderArmor implements IClientItemExtensions {

        private final com.wf.gemrender.asset.ModelCache.Handle<com.wf.gemrender.gltf.GemRenderGltfModel> model;
        private com.wf.gemrender.direct.GemRenderArmorModel armorModel;

        private GemRenderArmor(ResourceLocation model) {
            this.model = com.wf.gemrender.asset.GemRenderModels.armor(model);
        }

        @Override
        public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack,
                                                      EquipmentSlot slot, HumanoidModel<?> original) {
            com.wf.gemrender.direct.GemRenderArmorModel armor = armorModel();
            return armor == null ? original : armor.prepare(entity, stack, slot);
        }

        private com.wf.gemrender.direct.GemRenderArmorModel armorModel() {
            if (armorModel == null) {
                armorModel = new com.wf.gemrender.direct.GemRenderArmorModel(
                        (entity, stack, slot) -> model.get());
            }
            return armorModel;
        }
    }
}
