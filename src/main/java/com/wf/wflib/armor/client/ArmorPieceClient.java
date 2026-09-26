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
 * How a piece of armour is drawn.
 *
 * <p>A preset with no {@code model} renders the ordinary way, as a texture on vanilla's humanoid
 * armour model, and never touches GemRender at all. A preset that names a rigged glTF is drawn by
 * GemRender through {@code getHumanoidArmorModel}, which is the same hook vanilla's own armour goes
 * through, so trims, glint and the dyed-leather path all still behave.
 *
 * <p>Two things carried in from what is already known about that hook:
 * <ul>
 *   <li>{@code HumanoidArmorLayer.renderArmorPiece} calls {@code renderToBuffer} several times for one
 *       piece: once per material layer, again for a trim, again for the glint. A model that draws
 *       itself whole must draw once per {@code prepare}, not once per {@code renderToBuffer}, or
 *       enchanted armour costs several times what it should and composites blended geometry on top of
 *       itself. GemRender's prepare/render split is shaped for exactly that.</li>
 *   <li>GemRender has to be in the client's mod list, or a glTF piece draws nothing and says so once.</li>
 * </ul>
 */
public final class ArmorPieceClient {

    private static final IClientItemExtensions VANILLA = new IClientItemExtensions() {
    };

    private static final Map<ResourceLocation, IClientItemExtensions> BY_MODEL = new ConcurrentHashMap<>();

    private ArmorPieceClient() {
    }

    public static IClientItemExtensions of(ArmorPreset preset) {
        ResourceLocation model = preset.model();
        // Kept behind the null check so a mod with no glTF armour never classloads GemRender's model
        // types at all: the holder below is a separate class file and only resolves when it is used.
        return model == null ? VANILLA : BY_MODEL.computeIfAbsent(model, GemRenderArmor::new);
    }

    /** The GemRender path. Separate class so the reference above stays lazy. */
    private static final class GemRenderArmor implements IClientItemExtensions {

        private final ResourceLocation model;
        private com.wf.gemrender.direct.GemRenderArmorModel armorModel;

        private GemRenderArmor(ResourceLocation model) {
            this.model = model;
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
                        (entity, stack, slot) -> com.wf.gemrender.asset.GemRenderModels.get(model));
            }
            return armorModel;
        }
    }
}
