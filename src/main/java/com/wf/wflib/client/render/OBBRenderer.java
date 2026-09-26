package com.wf.wflib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.sim.MissileSimConfig;
import com.wf.wflib.util.OBB;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** Draws {@link OBB} wireframes for the F3+B debug hitbox overlay. */
public final class OBBRenderer {

    private OBBRenderer() {
    }

    public static void render(Entity entity, List<OBB> obbList, PoseStack poseStack, VertexConsumer buffer,
                              float red, float green, float blue, float alpha) {
        float[] tint = swarmTint(entity);
        render(entity.position(), obbList, poseStack, buffer,
                tint != null ? tint[0] : red, tint != null ? tint[1] : green, tint != null ? tint[2] : blue, alpha);
    }

    /** Boxes relative to {@code origin} (the pose stack's origin). */
    public static void render(Vec3 origin, List<OBB> obbList, PoseStack poseStack, VertexConsumer buffer,
                              float red, float green, float blue, float alpha) {
        for (OBB obb : obbList) {
            Vector3f half = obb.extents();
            renderOBB(poseStack, buffer,
                    obb.centerX() - origin.x, obb.centerY() - origin.y, obb.centerZ() - origin.z,
                    obb.rotation(), half.x, half.y, half.z, red, green, blue, alpha);
        }
    }

    public static void renderOBB(PoseStack poseStack, VertexConsumer buffer,
                                 double centerX, double centerY, double centerZ,
                                 Quaternionf rotation,
                                 double halfX, double halfY, double halfZ,
                                 float red, float green, float blue, float alpha) {
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, centerZ);
        poseStack.mulPose(rotation);
        LevelRenderer.renderLineBox(poseStack, buffer, -halfX, -halfY, -halfZ, halfX, halfY, halfZ,
                red, green, blue, alpha);
        poseStack.popPose();
    }

    /** Debug overlay for the continuous-collision sweep (shown with the OBB on F3+B). */
    public static void renderSweep(Entity entity, List<OBB> obbList, PoseStack poseStack, VertexConsumer buffer) {
        Vec3 delta = entity.getDeltaMovement();
        double moveDist = delta.length();
        if (moveDist < 1.0e-4) {
            return;
        }
        Vec3 heading = delta.scale(1.0 / moveDist);
        double sweepLen = moveDist + noseForward(entity);
        Vec3 pos = entity.position();

        int n = Mth.clamp((int) Math.ceil(sweepLen / MissileSimConfig.COLLISION_MAX_SUBSTEP_DIST),
                1, MissileSimConfig.COLLISION_MAX_SUBSTEPS);

        // Nose-extended corridor centerline (the ray Level.clip walks): red.
        line(poseStack, buffer,
                0, 0, 0,
                heading.x * sweepLen, heading.y * sweepLen, heading.z * sweepLen,
                1f, 0.15f, 0.15f, 1f);

        float[] tint = swarmTint(entity);
        float gr = tint != null ? tint[0] : 1f;
        float gg = tint != null ? tint[1] : 0.9f;
        float gb = tint != null ? tint[2] : 0.1f;
        for (OBB obb : obbList) {
            Vector3f h = obb.extents();
            Quaternionf rot = obb.rotation();
            double cx = obb.centerX() - pos.x, cy = obb.centerY() - pos.y, cz = obb.centerZ() - pos.z;
            for (int i = 1; i <= n; i++) {
                double d = moveDist * i / (double) n;
                renderOBB(poseStack, buffer,
                        cx + heading.x * d, cy + heading.y * d, cz + heading.z * d,
                        rot, h.x, h.y, h.z,
                        gr, gg, gb, 0.5f);
            }
        }
    }

    /**
     * Colour derived from a missile's swarm id (so distinct swarms are visually separable), or null when the entity
     * isn't a swarmed missile: the caller then keeps its default colour.
     */
    private static float[] swarmTint(Entity entity) {
        if (!(entity instanceof MissileEntity missile)) {
            return null;
        }
        long id = missile.swarm().getSwarmId();
        if (id == 0L) {
            return null;
        }
        // Hash the id to a hue; full saturation/value so the swarm colours are bright and distinct.
        float hue = ((id * 2654435761L) & 0xFFFFFFL) / (float) 0x1000000;
        int rgb = Mth.hsvToRgb(hue, 0.85f, 1.0f);
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
        };
    }

    /**
     * Distance from the entity origin to the model's front face along the heading (0 for non-missiles).
     */
    private static double noseForward(Entity entity) {
        if (entity instanceof MissileEntity missile) {
            ResourceLocation id = missile.getModelId();
            return MissileModels.center(id).y + MissileModels.dimensions(id).y * 0.5;
        }
        return 0.0;
    }

    /**
     * Emits a single debug line into the {@code RenderType.lines()} buffer.
     */
    private static void line(PoseStack poseStack, VertexConsumer buffer,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             float r, float g, float b, float a) {
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-5f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        buffer.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(nx, ny, nz);
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }
}
