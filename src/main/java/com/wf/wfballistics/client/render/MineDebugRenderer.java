package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.debug.MineDebug;
import com.wf.wfballistics.mine.MineEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Debug overlay: what each mine is actually watching. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MineDebugRenderer {

    private static final int SEGMENTS = 48;

    private MineDebugRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !MineDebug.renderAreas()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel serverLevel = server.getLevel(mc.level.dimension());
        if (serverLevel == null) {
            return;
        }

        Vec3 cam = event.getCamera()
                .getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers()
                .bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof MineEntity)) {
                continue;
            }
            if (serverLevel.getEntity(e.getUUID()) instanceof MineEntity mine) {
                draw(pose, lines, mine);
            }
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void draw(PoseStack pose, VertexConsumer lines, MineEntity mine) {
        double cx = mine.getX();
        double cz = mine.getZ();
        // Just off the ground so the ring is not z-fighting the block it sits on.
        double y = mine.getY() + 0.02;

        float[] colour = stateColour(mine);
        float r = colour[0], g = colour[1], b = colour[2];

        AABB box = mine.getBoundingBox();
        LevelRenderer.renderLineBox(pose, lines, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                r, g, b, 1.0f);

        // A mine is directional exactly when it has an arc narrower than the whole circle.
        boolean directional = mine.getArc() < 360.0;
        double trigger = mine.getTriggerRange();
        double sneak = mine.getSneakTriggerRange();

        if (directional) {
            double centre = Math.toRadians(mine.getYRot());
            double sweep = Math.toRadians(mine.getArc());
            DebugLines.wedge(pose, lines, cx, y, cz, trigger, centre, sweep, SEGMENTS, r, g, b, 0.9f);
            if (sneak > 0.0) {
                DebugLines.wedge(pose, lines, cx, y, cz, sneak, centre, sweep, SEGMENTS, 1.0f, 0.55f, 0.1f, 0.9f);
            }
            // The facing, drawn past the wedge so the direction is unambiguous edge-on.
            Vec3 facing = mine.facing();
            DebugLines.line(pose, lines, cx, y, cz,
                    cx + facing.x * (trigger + 1.0), y, cz + facing.z * (trigger + 1.0),
                    0.2f, 0.8f, 1.0f, 1.0f);
        } else {
            DebugLines.circle(pose, lines, cx, y, cz, trigger, SEGMENTS, r, g, b, 0.9f);
            if (sneak > 0.0) {
                DebugLines.circle(pose, lines, cx, y, cz, sneak, SEGMENTS, 1.0f, 0.55f, 0.1f, 0.9f);
            }
        }

        // A mine with a bounce height hops before it fires, so its blast starts up here, not at the ring.
        if (mine.bounds()) {
            double apex = mine.getY() + mine.getBounceHeight();
            DebugLines.line(pose, lines, cx, mine.getY(), cz, cx, apex, cz, 0.6f, 0.9f, 1.0f, 0.8f);
            DebugLines.circle(pose, lines, cx, apex, cz, 0.35, 16, 0.6f, 0.9f, 1.0f, 0.8f);
        }
    }

    /** Grey inert, yellow arming, red live, white on its way off. */
    private static float[] stateColour(MineEntity mine) {
        return switch (mine.getState()) {
            case SAFE -> new float[]{0.55f, 0.55f, 0.55f};
            case ARMING -> new float[]{1.0f, 0.9f, 0.2f};
            case ARMED -> new float[]{1.0f, 0.2f, 0.2f};
            case TRIPPED -> new float[]{1.0f, 1.0f, 1.0f};
        };
    }
}
