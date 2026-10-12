package com.wf.wflib.countermeasure.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import com.wf.wflib.countermeasure.FlareEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Camera-facing emissive quad, pulsing. */
public class FlareRenderer extends EntityRenderer<FlareEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "textures/entity/flare.png");

    public FlareRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(FlareEntity flare, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(FlareEntity flare, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light) {
        pose.pushPose();
        pose.translate(0.0, 0.1, 0.0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        float scale = 0.9f + (float) Math.sin((flare.tickCount + partialTick) * 0.5f) * 0.1f;
        pose.scale(scale, scale, scale);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE));
        PoseStack.Pose last = pose.last();
        vertex(buffer, last, -0.5f, -0.5f, 0, 1);
        vertex(buffer, last, -0.5f, 0.5f, 0, 0);
        vertex(buffer, last, 0.5f, 0.5f, 1, 0);
        vertex(buffer, last, 0.5f, -0.5f, 1, 1);
        pose.popPose();
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v) {
        buffer.addVertex(pose.pose(), x, y, 0).setColor(1f, 1f, 1f, 1f).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, -1);
    }

    @Override
    public ResourceLocation getTextureLocation(FlareEntity flare) {
        return TEXTURE;
    }
}
