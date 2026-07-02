package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.BombletEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;


public class BombletRenderer extends EntityRenderer<BombletEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(WFBallistics.MODID, "textures/entity/bomblet.png");
    private static final float HALF = 0.15f;

    public BombletRenderer(EntityRendererProvider.Context context) {
        super(context);
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
        PoseStack.Pose pose = poseStack.last();
        cube(pose.pose(), pose.normal(), consumer, HALF, LightTexture.FULL_BRIGHT);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static void cube(Matrix4f m, Matrix3f n, VertexConsumer c, float h, int light) {
        face(m, n, c, light, -h, h, -h, -h, h, h, h, h, h, h, h, -h, 0, 1, 0);   // +Y top
        face(m, n, c, light, -h, -h, h, -h, -h, -h, h, -h, -h, h, -h, h, 0, -1, 0); // -Y bottom
        face(m, n, c, light, h, h, -h, h, h, h, h, -h, h, h, -h, -h, 1, 0, 0);    // +X
        face(m, n, c, light, -h, h, h, -h, h, -h, -h, -h, -h, -h, -h, h, -1, 0, 0); // -X
        face(m, n, c, light, h, h, h, -h, h, h, -h, -h, h, h, -h, h, 0, 0, 1);    // +Z
        face(m, n, c, light, -h, h, -h, h, h, -h, h, -h, -h, -h, -h, -h, 0, 0, -1); // -Z
    }

    private static void face(Matrix4f m, Matrix3f n, VertexConsumer c, int light,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz) {
        vert(m, n, c, light, x0, y0, z0, 0.0f, 0.0f, nx, ny, nz);
        vert(m, n, c, light, x1, y1, z1, 0.0f, 1.0f, nx, ny, nz);
        vert(m, n, c, light, x2, y2, z2, 1.0f, 1.0f, nx, ny, nz);
        vert(m, n, c, light, x3, y3, z3, 1.0f, 0.0f, nx, ny, nz);
    }

    private static void vert(Matrix4f m, Matrix3f n, VertexConsumer c, int light,
                             float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        c.vertex(m, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(n, nx, ny, nz)
                .endVertex();
    }
}
