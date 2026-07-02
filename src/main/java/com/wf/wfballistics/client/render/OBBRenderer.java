package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.util.OBB;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Draws {@link OBB} wireframes for the F3+B debug hitbox overlay. Ported from SuperbWarfare's
 * {@code OBBRenderer} (Kotlin). Invoked from {@link com.wf.wfballistics.mixin.MixinEntityRenderDispatcher}
 * after the vanilla AABB box is drawn; the pose stack is already at the entity's render origin.
 */
public final class OBBRenderer {

    private OBBRenderer() { }

    public static void render(Entity entity, List<OBB> obbList, PoseStack poseStack, VertexConsumer buffer,
                              float red, float green, float blue, float alpha, float partialTicks) {
        Vec3 position = entity.position();
        for (OBB obb : obbList) {
            org.joml.Vector3d center = obb.center();
            org.joml.Vector3d halfExtents = obb.extents();
            Quaterniond rotation = obb.rotation();
            if (obb.part() == OBB.Part.INTERACTIVE) {
                renderOBB(poseStack, buffer,
                        center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                        rotation,
                        halfExtents.x(), halfExtents.y(), halfExtents.z(),
                        1f, 0.8f, 0f, 1f);
            } else {
                renderOBB(poseStack, buffer,
                        center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                        rotation,
                        halfExtents.x(), halfExtents.y(), halfExtents.z(),
                        red, green, blue, alpha);
            }
        }
    }

    public static void renderOBB(PoseStack poseStack, VertexConsumer buffer,
                                 double centerX, double centerY, double centerZ,
                                 Quaterniond rotation,
                                 double halfX, double halfY, double halfZ,
                                 float red, float green, float blue, float alpha) {
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, centerZ);
        poseStack.mulPose(new Quaternionf((float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w));
        LevelRenderer.renderLineBox(
                poseStack,
                buffer,
                -halfX, -halfY, -halfZ,
                halfX, halfY, halfZ,
                red, green, blue, alpha);
        poseStack.popPose();
    }
}
