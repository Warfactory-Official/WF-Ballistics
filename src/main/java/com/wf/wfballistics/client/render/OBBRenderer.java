package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.sim.MissileSimConfig;
import com.wf.wfballistics.util.OBB;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Draws {@link OBB} wireframes for the F3+B debug hitbox overlay. Ported from SuperbWarfare's
 * {@code OBBRenderer} (Kotlin). Invoked from {@link com.wf.wfballistics.mixin.MixinEntityRenderDispatcher}
 * after the vanilla AABB box is drawn; the pose stack is already at the entity's render origin.
 */
public final class OBBRenderer {

    private OBBRenderer() {
    }

    public static void render(Entity entity, List<OBB> obbList, PoseStack poseStack, VertexConsumer buffer,
                              float red, float green, float blue, float alpha, float partialTicks) {
        Vec3 position = entity.position();
        // Members of a swarm are drawn in that swarm's colour so distinct clusters are separable on F3+B;
        // a lone (non-swarm) missile keeps the caller's colour.
        float[] tint = swarmTint(entity);
        float r = tint != null ? tint[0] : red;
        float g = tint != null ? tint[1] : green;
        float b = tint != null ? tint[2] : blue;
        for (OBB obb : obbList) {
            org.joml.Vector3d center = obb.center();
            org.joml.Vector3d halfExtents = obb.extents();
            Quaterniond rotation = obb.rotation();
            if (obb.part() == OBB.Part.INTERACTIVE) {
                renderOBB(poseStack, buffer,
                        center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                        rotation,
                        halfExtents.x(), halfExtents.y(), halfExtents.z(),
                        1f, 0.8f, 0f, 1f);
            } else {
                renderOBB(poseStack, buffer,
                        center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                        rotation,
                        halfExtents.x(), halfExtents.y(), halfExtents.z(),
                        r, g, b, alpha);
            }
        }
    }

    public static void renderOBB(PoseStack poseStack, VertexConsumer buffer,
                                 double centerX, double centerY, double centerZ,
                                 Quaterniond rotation,
                                 double halfX, double halfY, double halfZ,
                                 float red, float green, float blue, float alpha) {
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, centerZ);
        poseStack.mulPose(new Quaternionf((float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w));
        LevelRenderer.renderLineBox(
                poseStack,
                buffer,
                -halfX, -halfY, -halfZ,
                halfX, halfY, halfZ,
                red, green, blue, alpha);
        poseStack.popPose();
    }

    /**
     * Debug overlay for the continuous-collision sweep (shown with the OBB on F3+B). Draws the
     * nose-extended corridor the block DDA walks (red centerline) and the body OBB stepped along this
     * tick's movement at the same substep cadence the server uses ({@link MissileSimConfig}), so the
     * swept volume — the thing that stops fast missiles tunnelling — is visible. Client-side, rebuilt
     * from the synced velocity; geometry only, it does not show the server's actual hit point.
     */
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

        // Body OBB stepped along this tick's move at the server substep cadence: ghost boxes.
        // Tinted by swarm (else yellow). i = 0 is the current pose (already drawn), so start at 1.
        float[] tint = swarmTint(entity);
        float gr = tint != null ? tint[0] : 1f;
        float gg = tint != null ? tint[1] : 0.9f;
        float gb = tint != null ? tint[2] : 0.1f;
        for (OBB obb : obbList) {
            if (obb.part() == OBB.Part.EMPTY) {
                continue;
            }
            org.joml.Vector3d c = obb.center();
            org.joml.Vector3d h = obb.extents();
            Quaterniond rot = obb.rotation();
            double cx = c.x - pos.x, cy = c.y - pos.y, cz = c.z - pos.z;
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
     * Colour derived from a missile's swarm id (so distinct swarms are visually separable), or null when
     * the entity isn't a swarmed missile — the caller then keeps its default colour.
     */
    private static float[] swarmTint(Entity entity) {
        if (!(entity instanceof MissileEntity missile)) {
            return null;
        }
        long id = missile.getSwarmId();
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
        buffer.vertex(pose, (float) x0, (float) y0, (float) z0).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
        buffer.vertex(pose, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
    }
}
