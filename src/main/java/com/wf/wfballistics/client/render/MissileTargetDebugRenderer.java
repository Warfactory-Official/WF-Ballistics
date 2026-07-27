package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.config.WFClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Debug overlay: a green wire block at every missile's current aim point, plus a faint line from the missile
 * to it — so it's obvious where each missile (a recursive missilelet especially) is actually aiming, and
 * whether that point is on solid ground or floating in empty air over a crater.
 *
 * <p>The aim point isn't synced to the client (it's server-only state on {@link MissileEntity}); rather than
 * add a sync, this reads the real value straight off the integrated singleplayer server's copy of the entity,
 * looked up by UUID. It therefore only draws in singleplayer, which is exactly where this is used.
 *
 * <p>Off by default; toggle with the {@code debug.showMissileTargets} client config
 * ({@link WFClientConfig#SHOW_MISSILE_TARGETS}).
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class MissileTargetDebugRenderer {

    private MissileTargetDebugRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!WFClientConfig.SHOW_MISSILE_TARGETS.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Singleplayer only: pull the true aim point off the integrated server's entity (same process), so
        // nothing has to be synced. No server here (real multiplayer) -> nothing to draw.
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel serverLevel = server.getLevel(mc.level.dimension());
        if (serverLevel == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof MissileEntity)) {
                continue;
            }
            if (!(serverLevel.getEntity(e.getUUID()) instanceof MissileEntity serverMissile)) {
                continue;
            }
            Vec3 target = serverMissile.getTarget();
            if (target == null || (target.x == 0.0 && target.y == 0.0 && target.z == 0.0)) {
                continue; // unset / default
            }

            double bx = Math.floor(target.x);
            double by = Math.floor(target.y);
            double bz = Math.floor(target.z);
            LevelRenderer.renderLineBox(pose, consumer,
                    bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                    0.15F, 1.0F, 0.2F, 1.0F);

            // Line from the missile to its aim point: pairs each box with its missile and shows the aim vector.
            Vec3 mp = e.getPosition(partialTick);
            line(pose, consumer, mp.x, mp.y, mp.z, target.x, target.y, target.z, 0.15F, 1.0F, 0.2F, 0.5F);
        }

        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void line(PoseStack pose, VertexConsumer buffer,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             float r, float g, float b, float a) {
        Matrix4f mat = pose.last().pose();
        Matrix3f norm = pose.last().normal();
        float nx = (float) (x1 - x0);
        float ny = (float) (y1 - y0);
        float nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-5F) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        buffer.vertex(mat, (float) x0, (float) y0, (float) z0).color(r, g, b, a).normal(norm, nx, ny, nz).endVertex();
        buffer.vertex(mat, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(norm, nx, ny, nz).endVertex();
    }
}
