package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.debug.ExplosionTrace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Debug overlay: the last few blasts, as the rays they actually marched. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class ExplosionTraceRenderer {

    private ExplosionTraceRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !ExplosionTrace.enabled()) {
            return;
        }
        var traces = ExplosionTrace.recent();
        if (traces.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();

        Vec3 cam = event.getCamera()
                .getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers()
                .bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (ExplosionTrace.Trace trace : traces) {
            long age = now - trace.gameTime();
            if (age < 0 || age > ExplosionTrace.TTL_TICKS) {
                continue;
            }
            float fade = 1.0f - (float) age / ExplosionTrace.TTL_TICKS;
            draw(pose, lines, trace, fade);
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void draw(PoseStack pose, VertexConsumer lines, ExplosionTrace.Trace trace, float fade) {
        for (ExplosionTrace.Ray ray : trace.rays()) {
            if (ray.broke()) {
                DebugLines.line(pose, lines, ray.from(), ray.to(), 1.0f, 0.55f, 0.1f, 0.85f * fade);
            } else {
                DebugLines.line(pose, lines, ray.from(), ray.to(), 0.25f, 0.45f, 0.9f, 0.35f * fade);
            }
        }

        if (trace.axis() != null) {
            DebugLines.cone(pose, lines, trace.origin(), trace.axis(),
                    Math.toRadians(trace.halfAngleDeg()), trace.size(), 16,
                    0.2f, 1.0f, 1.0f, 0.9f * fade);
        }

        DebugLines.cross(pose, lines, trace.origin(), 0.5, 1.0f, 1.0f, 1.0f, fade);

        for (ExplosionTrace.Victim victim : trace.victims()) {
            float[] c = switch (victim.verdict()) {
                case HIT -> new float[]{0.2f, 1.0f, 0.3f};
                case OUT_OF_RANGE -> new float[]{1.0f, 0.9f, 0.2f};
                case OUTSIDE_CONE -> new float[]{1.0f, 0.2f, 0.2f};
            };
            DebugLines.cross(pose, lines, victim.pos(), 0.6, c[0], c[1], c[2], fade);
            // A line back to the charge pairs each verdict with the blast that produced it.
            DebugLines.line(pose, lines, trace.origin(), victim.pos(), c[0], c[1], c[2], 0.5f * fade);
        }
    }
}
