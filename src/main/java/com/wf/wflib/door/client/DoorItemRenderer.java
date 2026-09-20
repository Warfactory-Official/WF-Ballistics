package com.wf.wflib.door.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.wflib.client.render.ObjMeshes;
import com.wf.wflib.door.DoorItem;
import com.wf.wflib.probe.ProbeElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Draws a door item as the door: the same obj meshes and the same sheets the placed door is built from, shut,
 * fitted into the item's unit cube.
 */
public class DoorItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static DoorItemRenderer instance;

    public DoorItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    public static DoorItemRenderer instance() {
        if (instance == null) {
            instance = new DoorItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        if (!(stack.getItem() instanceof DoorItem item)) {
            return;
        }
        List<ProbeElement.Mesh.Part> parts = DoorRigs.preview(item.type(), DoorItem.skinOf(stack));
        if (parts.isEmpty()) {
            return;
        }
        float[] box = ObjMeshes.bounds(parts);
        if (box == null) {
            return;
        }

        float span = Math.max(1.0e-3f, Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])));

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        if (ctx == ItemDisplayContext.GUI) {
            pose.mulPose(Axis.XP.rotationDegrees(-20.0f));
            pose.mulPose(Axis.YP.rotationDegrees((float) ((System.currentTimeMillis() / 30L) % 360L)));
        }
        pose.scale(1.0f / span, 1.0f / span, 1.0f / span);
        pose.translate(-(box[0] + box[3]) * 0.5f, -(box[1] + box[4]) * 0.5f, -(box[2] + box[5]) * 0.5f);

        ObjMeshes.draw(pose.last(), buffers, parts, light, overlay);
        pose.popPose();
    }
}
