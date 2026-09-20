package com.wf.wflib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wflib.WFLib;
import com.wf.wflib.entity.BombletEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;


public class BombletRenderer extends EntityRenderer<BombletEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/entity/bomblet.png");
    private static final float HALF = 0.15f;

    public BombletRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static void cube(PoseStack.Pose pose, VertexConsumer c, float h, int light) {
        face(pose, c, light, -h, h, -h, -h, h, h, h, h, h, h, h, -h, 0, 1, 0);   // +Y top
        face(pose, c, light, -h, -h, h, -h, -h, -h, h, -h, -h, h, -h, h, 0, -1, 0); // -Y bottom
        face(pose, c, light, h, h, -h, h, h, h, h, -h, h, h, -h, -h, 1, 0, 0);    // +X
        face(pose, c, light, -h, h, h, -h, h, -h, -h, -h, -h, -h, -h, h, -1, 0, 0); // -X
        face(pose, c, light, h, h, h, -h, h, h, -h, -h, h, h, -h, h, 0, 0, 1);    // +Z
        face(pose, c, light, -h, h, -h, h, h, -h, h, -h, -h, -h, -h, -h, 0, 0, -1); // -Z
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
    public ResourceLocation getTextureLocation(BombletEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(BombletEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        float spin = (entity.tickCount + partialTicks) * 12.0f;
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(spin * 0.7f));

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        cube(poseStack.last(), consumer, HALF, LightTexture.FULL_BRIGHT);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
