package com.wf.wflib.nvg.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.direct.ArmorAppearance;
import com.wf.gemrender.direct.GemRenderArmorModel;
import com.wf.gemrender.direct.GemRenderItemRenderer;
import com.wf.gemrender.direct.ItemAppearance;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.WFLib;
import com.wf.wflib.nvg.NvgServer;
import com.wf.wflib.nvg.NvgWorn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Goggle models: item cell, standalone on the head harness, at a helmet's {@code socket_nvg}. */
public final class NvgItemClient implements IClientItemExtensions {
    public static final NvgItemClient INSTANCE = new NvgItemClient();

    /** Hinge angle flipped up, radians about +X (glTF armour space, face -Z). */
    static final float UP_ANGLE = (float) Math.toRadians(110.0);
    static final String SOCKET = "socket_nvg";
    static final String PIVOT = "pivot";
    /** Longest side in the item cell. */
    static final float FILL = 0.9f;

    private static final Map<Item, ModelCache.Handle<GemRenderGltfModel>> GOGGLES = new HashMap<>();
    private static final Map<LivingEntity, Hinge> HINGES = new WeakHashMap<>();
    private static ModelCache.Handle<GemRenderGltfModel> harness;
    private static GemRenderArmorModel harnessModel;
    private static GemRenderItemRenderer itemRenderer;

    private static final class Hinge {
        boolean down;
        float from;
        double at = Double.NEGATIVE_INFINITY;
    }

    private NvgItemClient() {
    }

    @Nullable
    static GemRenderGltfModel goggle(Item item) {
        return GOGGLES.computeIfAbsent(item, i -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
            return GemRenderModels.handle(id.withPath(p -> "models/nvg/" + p + ".glb"));
        }).get();
    }

    /** Goggle at {@link #SOCKET}; hinge eased over the server's swap window. */
    public static void attach(@Nullable LivingEntity entity, ItemStack nvg, ArmorAppearance.Attachments out) {
        if (nvg.isEmpty()) {
            return;
        }
        GemRenderGltfModel model = goggle(nvg.getItem());
        if (model == null) {
            return;
        }
        float lift = entity == null ? (NvgWorn.state(nvg).down() ? 0.0f : 1.0f) : lift(entity, NvgWorn.state(nvg).down());
        out.attach(model, SOCKET, (table, state) -> {
            int pivot = table.slotOfName(PIVOT);
            if (pivot >= 0 && table.isPosable(pivot)) {
                Quaternionf rest = new Quaternionf();
                table.restRotation(pivot, rest);
                table.setRotation(state, pivot, rest.rotateX(UP_ANGLE * lift));
            }
        });
    }

    private static float lift(LivingEntity entity, boolean down) {
        Minecraft mc = Minecraft.getInstance();
        double now = (mc.level == null ? 0L : mc.level.getGameTime())
                + mc.getTimer().getGameTimeDeltaPartialTick(false);
        Hinge hinge = HINGES.computeIfAbsent(entity, e -> {
            Hinge h = new Hinge();
            h.down = down;
            return h;
        });
        float span = NvgServer.FLIP_TICKS - NvgServer.SWAP_TICK;
        if (hinge.down != down) {
            hinge.from = current(hinge, now, span);
            hinge.down = down;
            hinge.at = now;
        }
        return current(hinge, now, span);
    }

    private static float current(Hinge hinge, double now, float span) {
        float target = hinge.down ? 0.0f : 1.0f;
        float t = Mth.clamp((float) ((now - hinge.at) / span), 0.0f, 1.0f);
        return Mth.lerp(t * t * (3.0f - 2.0f * t), hinge.from, target);
    }

    @Override
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                  HumanoidModel<?> original) {
        if (harnessModel == null) {
            harness = GemRenderModels.armor(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "models/nvg/harness.glb"));
            harnessModel = new GemRenderArmorModel(new ArmorAppearance() {
                @Override
                public GemRenderGltfModel model(@Nullable LivingEntity wearer, ItemStack worn, EquipmentSlot at) {
                    return at == EquipmentSlot.HEAD ? harness.get() : null;
                }

                @Override
                public void attachments(@Nullable LivingEntity wearer, ItemStack worn, EquipmentSlot at,
                                        Attachments out) {
                    attach(wearer, worn, out);
                }
            });
        }
        return harnessModel.prepare(entity, stack, slot);
    }

    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        if (itemRenderer == null) {
            itemRenderer = new GemRenderItemRenderer(new ItemAppearance() {
                @Nullable
                @Override
                public GemRenderGltfModel model(ItemStack stack, ItemDisplayContext context) {
                    return goggle(stack.getItem());
                }

                /** Model origin = hinge, tubes below-front: recentre, eyepieces toward the viewer. */
                @Override
                public void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
                    pose.mulPose(Axis.YP.rotationDegrees(200.0f));
                    pose.mulPose(Axis.XP.rotationDegrees(15.0f));
                    float s = FILL / 0.36f;
                    pose.scale(s, s, s);
                    pose.translate(0.0f, 0.12f, 0.06f);
                }
            });
        }
        return itemRenderer;
    }
}
