package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Additive camera-facing streak behind each round with a tracer colour. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class TracerRenderer {

    private static final float HALF_WIDTH = 0.05f;
    /** Streak length = this many ticks of travel, capped. */
    private static final double STREAK_TICKS = 1.5;
    private static final double MAX_STREAK = 12.0;

    private TracerRenderer() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ClientRounds.live().isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.lightning());
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        Matrix4f m = stack.last().pose();
        for (ClientRounds.Round r : ClientRounds.live()) {
            int rgb = r.preset.tracerColor();
            if (rgb == 0) {
                continue;
            }
            double speed = Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz);
            if (speed < 1.0e-4) {
                continue;
            }
            double len = Math.min(MAX_STREAK, speed * STREAK_TICKS) / speed;
            Vec3 head = new Vec3(r.ix(pt) - cam.x, r.iy(pt) - cam.y, r.iz(pt) - cam.z);
            Vec3 tail = head.subtract(r.vx * len, r.vy * len, r.vz * len);
            Vec3 side = tail.subtract(head).cross(head).normalize().scale(HALF_WIDTH);
            if (!Double.isFinite(side.x)) {
                continue;
            }
            float cr = ((rgb >> 16) & 0xFF) / 255f;
            float cg = ((rgb >> 8) & 0xFF) / 255f;
            float cb = (rgb & 0xFF) / 255f;
            out.addVertex(m, (float) (head.x + side.x), (float) (head.y + side.y), (float) (head.z + side.z))
                    .setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, (float) (head.x - side.x), (float) (head.y - side.y), (float) (head.z - side.z))
                    .setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, (float) (tail.x - side.x), (float) (tail.y - side.y), (float) (tail.z - side.z))
                    .setColor(cr, cg, cb, 0.0f);
            out.addVertex(m, (float) (tail.x + side.x), (float) (tail.y + side.y), (float) (tail.z + side.z))
                    .setColor(cr, cg, cb, 0.0f);
        }
        stack.popPose();
        buffers.endBatch(RenderType.lightning());
    }
}
