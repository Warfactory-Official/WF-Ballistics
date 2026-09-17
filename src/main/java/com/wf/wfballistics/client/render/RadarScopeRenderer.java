package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.block.RadarScopeBlock;
import com.wf.wfballistics.block.entity.RadarScopeBlockEntity;
import com.wf.wfballistics.client.scope.ScopeCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Draws a network's scope onto the front face of a {@link RadarScopeBlockEntity}. */
public class RadarScopeRenderer implements BlockEntityRenderer<RadarScopeBlockEntity> {

    /** Blocks past which a screen stops drawing. Roughly where 256 pixels stop being legible anyway. */
    private static final double DRAW_DISTANCE = 48.0;
    /** How far the display stands off the block face, so it does not z-fight with the model. */
    private static final float STANDOFF = 0.001f;
    /** Margin inside the block face, in block units, so the screen has a visible bezel. */
    private static final float INSET = 0.0625f;

    public RadarScopeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RadarScopeBlockEntity scope, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (camera.distanceToSqr(Vec3.atCenterOf(scope.getBlockPos())) > DRAW_DISTANCE * DRAW_DISTANCE) {
            return;
        }
        ResourceLocation texture = ScopeCache.texture(scope.netId());
        if (texture == null) {
            return;
        }

        Direction facing = scope.getBlockState().getValue(RadarScopeBlock.FACING);
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(0.0f, 0.0f, 0.5f + STANDOFF);

        VertexConsumer buffer = buffers.getBuffer(RenderType.text(texture));
        Matrix4f matrix = pose.last().pose();
        float lo = -0.5f + INSET;
        float hi = 0.5f - INSET;
        int light = LightTexture.FULL_BRIGHT;
        buffer.addVertex(matrix, lo, lo, 0.0f).setColor(0xFFFFFFFF).setUv(0.0f, 1.0f).setLight(light);
        buffer.addVertex(matrix, hi, lo, 0.0f).setColor(0xFFFFFFFF).setUv(1.0f, 1.0f).setLight(light);
        buffer.addVertex(matrix, hi, hi, 0.0f).setColor(0xFFFFFFFF).setUv(1.0f, 0.0f).setLight(light);
        buffer.addVertex(matrix, lo, hi, 0.0f).setColor(0xFFFFFFFF).setUv(0.0f, 0.0f).setLight(light);
        pose.popPose();
    }

    /**
     * Kept in the render pass at any distance the block itself is visible; the distance test above is what actually
     * stops the work, and it is cheaper than the one vanilla would do.
     */
    @Override
    public int getViewDistance() {
        return (int) DRAW_DISTANCE + 16;
    }
}
