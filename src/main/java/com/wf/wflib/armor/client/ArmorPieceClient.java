package com.wf.wflib.armor.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.armor.ArmorPieceItem;
import com.wf.wflib.armor.ArmorPreset;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
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
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ArmorPieceClient {

    /** Skin overlay part covered by a glTF piece's node; overlay (0.25 px out) pokes through suits hugging the limb. */
    private static final int HAT = 1, JACKET = 2, LEFT_SLEEVE = 4, RIGHT_SLEEVE = 8, LEFT_PANTS = 16, RIGHT_PANTS = 32;
    private static final String[] NODES = {"head", "body", "left_arm", "right_arm", "left_leg", "right_leg"};

    private static final IClientItemExtensions VANILLA = new IClientItemExtensions() {
    };

    private static final Map<ResourceLocation, IClientItemExtensions> BY_MODEL = new ConcurrentHashMap<>();

    private ArmorPieceClient() {
    }

    public static IClientItemExtensions of(ArmorPreset preset) {
        ResourceLocation model = preset.model();
        // Null check first: GemRenderArmor only classloads on first use, so a mod with no glTF
        // armour never touches GemRender's model types at all.
        return model == null ? VANILLA : BY_MODEL.computeIfAbsent(model, m -> new GemRenderArmor(m, preset.nvgMount()));
    }

    /** Worn piece's glTF; null = no glTF piece or not loaded yet. */
    @org.jetbrains.annotations.Nullable
    public static com.wf.gemrender.gltf.GemRenderGltfModel model(ItemStack stack) {
        return stack.getItem() instanceof ArmorPieceItem piece && piece.preset().model() != null
                && of(piece.preset()) instanceof GemRenderArmor armor ? armor.model.get() : null;
    }

    /** After vanilla's skin-customisation visibility: overlays under worn glTF pieces hidden. */
    @SubscribeEvent
    public static void hideCovered(RenderPlayerEvent.Pre e) {
        int covered = 0;
        for (net.minecraft.world.item.ItemStack worn : e.getEntity().getArmorSlots()) {
            if (worn.getItem() instanceof ArmorPieceItem piece && piece.preset().model() != null
                    && of(piece.preset()) instanceof GemRenderArmor armor) {
                covered |= armor.covers();
            }
        }
        PlayerModel<?> m = e.getRenderer().getModel();
        m.hat.visible &= (covered & HAT) == 0;
        m.jacket.visible &= (covered & JACKET) == 0;
        m.leftSleeve.visible &= (covered & LEFT_SLEEVE) == 0;
        m.rightSleeve.visible &= (covered & RIGHT_SLEEVE) == 0;
        m.leftPants.visible &= (covered & LEFT_PANTS) == 0;
        m.rightPants.visible &= (covered & RIGHT_PANTS) == 0;
    }

    private static final class GemRenderArmor implements IClientItemExtensions {

        private final com.wf.gemrender.asset.ModelCache.Handle<com.wf.gemrender.gltf.GemRenderGltfModel> model;
        private final boolean nvgMount;
        private com.wf.gemrender.direct.GemRenderArmorModel armorModel;
        /** {@link #NODES} bits; -1 = model not loaded yet. */
        private int covers = -1;

        private GemRenderArmor(ResourceLocation model, boolean nvgMount) {
            this.model = com.wf.gemrender.asset.GemRenderModels.armor(model);
            this.nvgMount = nvgMount;
        }

        @Override
        public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack,
                                                      EquipmentSlot slot, HumanoidModel<?> original) {
            com.wf.gemrender.direct.GemRenderArmorModel armor = armorModel();
            return armor == null ? original : armor.prepare(entity, stack, slot);
        }

        int covers() {
            if (covers < 0) {
                com.wf.gemrender.gltf.GemRenderGltfModel m = model.get();
                if (m == null) {
                    return 0;
                }
                com.wf.gemrender.gltf.NodeTable table = m.layout().nodeTable();
                int bits = 0;
                for (int i = 0; i < NODES.length; i++) {
                    if (table.slotOfName(NODES[i]) >= 0) {
                        bits |= 1 << i;
                    }
                }
                covers = bits;
            }
            return covers;
        }

        private com.wf.gemrender.direct.GemRenderArmorModel armorModel() {
            if (armorModel == null) {
                armorModel = new com.wf.gemrender.direct.GemRenderArmorModel(
                        new com.wf.gemrender.direct.ArmorAppearance() {
                            @Override
                            public com.wf.gemrender.gltf.GemRenderGltfModel model(LivingEntity entity,
                                                                                  ItemStack stack, EquipmentSlot slot) {
                                return model.get();
                            }

                            @Override
                            public void attachments(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                    Attachments out) {
                                if (nvgMount) {
                                    com.wf.wflib.nvg.client.NvgItemClient.attach(entity,
                                            com.wf.wflib.nvg.NvgWorn.mounted(stack), out);
                                }
                            }
                        });
            }
            return armorModel;
        }
    }
}
