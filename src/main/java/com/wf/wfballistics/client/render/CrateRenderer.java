package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.drone.CrateEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The cargo crate: a textured cube, falling or sitting on the ground. A crate being carried is not drawn
 * here at all: the drone holding it draws it as part of its own model (see {@code DroneVisual}).
 */
public class CrateRenderer extends EntityRenderer<CrateEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/crate.png");
    private static final float HALF = CrateEntity.SIZE / 2.0f;

    public CrateRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer c, float h, int light) {
        face(pose, c, light, -h, h, -h, -h, h, h, h, h, h, h, h, -h, 0, 1, 0);
        face(pose, c, light, -h, -h, h, -h, -h, -h, h, -h, -h, h, -h, h, 0, -1, 0);
        face(pose, c, light, h, h, -h, h, h, h, h, -h, h, h, -h, -h, 1, 0, 0);
        face(pose, c, light, -h, h, h, -h, h, -h, -h, -h, -h, -h, -h, h, -1, 0, 0);
        face(pose, c, light, h, h, h, -h, h, h, -h, -h, h, h, -h, h, 0, 0, 1);
        face(pose, c, light, -h, h, -h, h, h, -h, h, -h, -h, -h, -h, -h, 0, 0, -1);
    }

    private static void face(PoseStack.Pose pose, VertexConsumer c, int light,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz) {
        vert(pose, c, light, x0, y0, z0, 0.0f, 0.0f, nx, ny, nz);
        vert(pose, c, light, x1, y1, z1, 0.0f, 1.0f, nx, ny, nz);
        vert(pose, c, light, x2, y2, z2, 1.0f, 1.0f, nx, ny, nz);
        vert(pose, c, light, x3, y3, z3, 1.0f, 0.0f, nx, ny, nz);
    }

    private static void vert(PoseStack.Pose pose, VertexConsumer c, int light,
                             float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        c.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(CrateEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(CrateEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.translate(0.0, HALF, 0.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        cube(poseStack.last(), consumer, HALF, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
