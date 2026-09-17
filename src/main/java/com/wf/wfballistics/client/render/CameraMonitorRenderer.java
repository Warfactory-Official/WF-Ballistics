package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.block.CameraMonitorBlock;
import com.wf.wfballistics.block.entity.CameraMonitorBlockEntity;
import com.wf.wfballistics.client.cam.CameraFeedCache;
import com.wf.wfballistics.client.cam.CameraTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a drone feed across the front of a monitor: one quad, three blocks wide and two high, from the origin cell
 * of the screen.
 */
public class CameraMonitorRenderer implements BlockEntityRenderer<CameraMonitorBlockEntity> {

    /** Blocks past which a monitor stops drawing entirely. Matches the governor's falloff distance. */
    private static final double DRAW_DISTANCE = CameraFeedCache.FALLOFF_DISTANCE;
    private static final float STANDOFF = 0.001f;
    private static final float INSET = 0.0625f;

    /** Width of the glass, in blocks, after the bezel. */
    private static final float SCREEN_W = CameraMonitorBlock.WIDTH - INSET * 2.0f;
    /** Height of the glass. The picture is 16:9, so it letterboxes inside this rather than filling it. */
    private static final float SCREEN_H = CameraMonitorBlock.HEIGHT - INSET * 2.0f;

    public CameraMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraMonitorBlockEntity monitor, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        int feedId = monitor.feedId();
        if (feedId == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Direction facing = monitor.getBlockState().getValue(CameraMonitorBlock.FACING);
        Direction right = CameraMonitorBlock.right(monitor.getBlockState());

        Vec3 centre = Vec3.atCenterOf(monitor.getBlockPos())
                .add(right.getStepX() * (CameraMonitorBlock.WIDTH - 1) * 0.5,
                        (CameraMonitorBlock.HEIGHT - 1) * 0.5,
                        right.getStepZ() * (CameraMonitorBlock.WIDTH - 1) * 0.5);
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(centre);
        if (distance > DRAW_DISTANCE) {
            return;
        }

        CameraFeedCache.request(feedId, projectedPixels(mc, distance), distance);

        ResourceLocation texture = CameraFeedCache.texture(feedId);
        if (texture == null || !CameraFeedCache.drawable(feedId)) {
            return;
        }

        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate((CameraMonitorBlock.WIDTH - 1) * 0.5f, (CameraMonitorBlock.HEIGHT - 1) * 0.5f,
                0.5f + STANDOFF);

        VertexConsumer buffer = buffers.getBuffer(RenderType.text(texture));
        Matrix4f matrix = pose.last().pose();
        float halfW = SCREEN_W * 0.5f;
        float halfH = Math.min(SCREEN_H, (float) (SCREEN_W / CameraTarget.ASPECT)) * 0.5f;
        int light = LightTexture.FULL_BRIGHT;
        buffer.addVertex(matrix, -halfW, -halfH, 0.0f).setColor(0xFFFFFFFF).setUv(0.0f, 0.0f).setLight(light);
        buffer.addVertex(matrix, halfW, -halfH, 0.0f).setColor(0xFFFFFFFF).setUv(1.0f, 0.0f).setLight(light);
        buffer.addVertex(matrix, halfW, halfH, 0.0f).setColor(0xFFFFFFFF).setUv(1.0f, 1.0f).setLight(light);
        buffer.addVertex(matrix, -halfW, halfH, 0.0f).setColor(0xFFFFFFFF).setUv(0.0f, 1.0f).setLight(light);
        pose.popPose();
    }

    /**
     * @return how many pixels wide this monitor's screen is on the viewer's display right now.
     */
    private static int projectedPixels(Minecraft mc, double distance) {
        double fov = Math.toRadians(Math.max(30, mc.options.fov().get()));
        double halfExtent = Math.tan(fov * 0.5) * Math.max(0.5, distance);
        double pixels = mc.getWindow().getHeight() * (SCREEN_W / (2.0 * halfExtent));
        return (int) Math.min(4096.0, Math.max(1.0, pixels));
    }

    @Override
    public int getViewDistance() {
        return (int) DRAW_DISTANCE + 16;
    }

    /** The whole screen, not the origin cell. */
    @Override
    public AABB getRenderBoundingBox(CameraMonitorBlockEntity monitor) {
        BlockState state = monitor.getBlockState();
        if (!(state.getBlock() instanceof CameraMonitorBlock)) {
            return new AABB(monitor.getBlockPos());
        }
        BlockPos far = monitor.getBlockPos()
                .relative(CameraMonitorBlock.right(state), CameraMonitorBlock.WIDTH - 1)
                .above(CameraMonitorBlock.HEIGHT - 1);
        return new AABB(monitor.getBlockPos()).minmax(new AABB(far));
    }
}
