package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.item.MissileItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Renders a {@link MissileItem} as the actual 3D missile model — the same baked OBJ the flying entity uses.
 * HBM-style: the model is scaled to stand in the item's unit space, and in the inventory it slowly spins on
 * its long axis so you can read the airframe.
 *
 * <p>The per-perspective placement (hand pose, GUI tilt, ground) comes from the item model's {@code display}
 * block (see {@code assets/.../models/item/missile_render_base.json}); here we only fit the mesh into that
 * unit space and add the spin.
 */
public class MissileItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static MissileItemRenderer instance;

    public MissileItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    public static MissileItemRenderer instance() {
        if (instance == null) {
            instance = new MissileItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        if (!(stack.getItem() instanceof MissileItem missile)) {
            return;
        }
        ModModels.RenderModel model = ModModels.render(missile.modelId());
        BakedModel baked = model.baked();
        if (baked == null) {
            return;
        }

        float scale = (float) (1.0 / model.length());
        Vec3 center = model.center();

        pose.pushPose();

        if (ctx == ItemDisplayContext.GUI) {
            float spin = (float) ((System.currentTimeMillis() / 25L) % 360L);
            pose.translate(0.5, 0.5, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.translate(-0.5, -0.5, -0.5);
        }

        pose.translate(0.5, 0.0, 0.5);
        pose.scale(scale, scale, scale);
        pose.translate(-center.x, 0.0, -center.z);

        VertexConsumer consumer = buffer.getBuffer(RenderType.cutout());
        Minecraft.getInstance().getItemRenderer().renderModelLists(baked, stack, light, overlay, pose, consumer);

        pose.popPose();
    }
}
