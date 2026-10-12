package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.ProjectileLook;
import com.wf.wflib.ammo.client.LookLod;
import com.wf.wflib.ammo.client.LookRockets;
import com.wf.wflib.ammo.client.LookStats;
import com.wf.wflib.round.RocketEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Variant look sprites ({@link LookLod}; rounds + look rockets) + camera-facing streak behind each round with a tracer colour, plus a head dot of fixed angular size (a streak along the
 * view ray, e.g. the shooter's own, projects to nothing): alpha-blended, unlit (an additive streak washed out to white
 * against a daylit sky).
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class TracerRenderer {

    private static final double HALF_WIDTH = 0.05;
    /** Streak half-width per end = HALF_WIDTH within [MIN, MAX] x distance: ~1-3 px at 1080 p, 70 degrees FOV. */
    private static final double MIN_HALF_ANGLE = 0.0012;
    /** Else a tail at the shooter's eye spans the screen. */
    private static final double MAX_HALF_ANGLE = 0.0035;
    /** Head dot half-diagonal, radians: ~2 px. */
    private static final double HEAD_HALF_ANGLE = 0.0025;
    /** Streak length = this many ticks of travel, capped. */
    private static final double STREAK_TICKS = 1.5;
    private static final double MAX_STREAK = 12.0;

    private TracerRenderer() {
    }

    private static double halfWidth(double distance) {
        return Math.max(distance * MIN_HALF_ANGLE, Math.min(HALF_WIDTH, distance * MAX_HALF_ANGLE));
    }

    /**
     * Visible look past its model band ({@link LookLod}): body-colour quad from tail {@code (x, y, z)} along the unit
     * heading, camera-facing about it; projected length {@code hl sin(heading, view ray)} < width => square (own
     * rounds fly near the view ray: the width axis read as a heading 90 deg off).
     */
    private static void sprite(@Nullable ProjectileLook look, double x, double y, double z, double hx, double hy,
                               double hz, VertexConsumer out, Matrix4f m, Vec3 cam, Vector3f up, Vector3f left) {
        if (look == null || !look.visible()) {
            return;
        }
        double len = look.lengthMm() * 0.001;
        double cx = x + hx * len / 2 - cam.x, cy = y + hy * len / 2 - cam.y, cz = z + hz * len / 2 - cam.z;
        double d = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (!LookLod.sprite(look.diameterMm() * 0.001, d)) {
            return;
        }
        double hl = LookLod.halfLength(len, d), hw = LookLod.halfWidth(look.diameterMm() * 0.001, d);
        double sx = hy * cz - hz * cy, sy = hz * cx - hx * cz, sz = hx * cy - hy * cx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        double ax, ay, az;
        if (hl * sl < hw * d) {
            sx = left.x() * hw;
            sy = left.y() * hw;
            sz = left.z() * hw;
            ax = up.x() * hw;
            ay = up.y() * hw;
            az = up.z() * hw;
        } else {
            sx *= hw / sl;
            sy *= hw / sl;
            sz *= hw / sl;
            ax = hx * hl;
            ay = hy * hl;
            az = hz * hl;
        }
        float cr = (look.body() >> 16 & 0xFF) / 255f, cg = (look.body() >> 8 & 0xFF) / 255f,
                cb = (look.body() & 0xFF) / 255f;
        LookStats.sprites++;
        out.addVertex(m, (float) (cx + ax + sx), (float) (cy + ay + sy), (float) (cz + az + sz)).setColor(cr, cg, cb, 1f);
        out.addVertex(m, (float) (cx - ax + sx), (float) (cy - ay + sy), (float) (cz - az + sz)).setColor(cr, cg, cb, 1f);
        out.addVertex(m, (float) (cx - ax - sx), (float) (cy - ay - sy), (float) (cz - az - sz)).setColor(cr, cg, cb, 1f);
        out.addVertex(m, (float) (cx + ax - sx), (float) (cy + ay - sy), (float) (cz + az - sz)).setColor(cr, cg, cb, 1f);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || ClientRounds.live().isEmpty() && LookRockets.live().isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.debugQuads());
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        Matrix4f m = stack.last().pose();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        for (RocketEntity rocket : LookRockets.live()) {
            Vec3 nose = rocket.getViewVector(pt);
            sprite(Ammo.look(rocket.preset().id()), Mth.lerp(pt, rocket.xo, rocket.getX()),
                    Mth.lerp(pt, rocket.yo, rocket.getY()), Mth.lerp(pt, rocket.zo, rocket.getZ()), nose.x, nose.y,
                    nose.z, out, m, cam, up, left);
        }
        for (ClientRounds.Round r : ClientRounds.live()) {
            double v = Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz);
            if (v > 1.0e-9) {
                sprite(Ammo.lookOrNull(r.preset.id()), r.ix(pt), r.iy(pt), r.iz(pt), r.vx / v, r.vy / v, r.vz / v,
                        out, m, cam, up, left);
            }
            int rgb = r.preset.tracerColor();
            if (rgb == 0) {
                continue;
            }
            double speed = Math.sqrt(r.vx * r.vx + r.vy * r.vy + r.vz * r.vz);
            if (speed < 1.0e-4) {
                continue;
            }
            double hx = r.ix(pt), hy = r.iy(pt), hz = r.iz(pt);
            float cr = ((rgb >> 16) & 0xFF) / 255f;
            float cg = ((rgb >> 8) & 0xFF) / 255f;
            float cb = (rgb & 0xFF) / 255f;
            double ex = hx - cam.x, ey = hy - cam.y, ez = hz - cam.z;
            double hd = Math.sqrt(ex * ex + ey * ey + ez * ez);
            float dot = (float) (hd * HEAD_HALF_ANGLE);
            float px = (float) ex, py = (float) ey, pz = (float) ez;
            out.addVertex(m, px + up.x() * dot, py + up.y() * dot, pz + up.z() * dot).setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, px + left.x() * dot, py + left.y() * dot, pz + left.z() * dot).setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, px - up.x() * dot, py - up.y() * dot, pz - up.z() * dot).setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, px - left.x() * dot, py - left.y() * dot, pz - left.z() * dot).setColor(cr, cg, cb, 1.0f);
            double flown = Math.sqrt((hx - r.ox) * (hx - r.ox) + (hy - r.oy) * (hy - r.oy) + (hz - r.oz) * (hz - r.oz));
            double len = Math.min(Math.min(MAX_STREAK, speed * STREAK_TICKS), flown) / speed;
            if (len <= 0) {
                continue;
            }
            Vec3 head = new Vec3(ex, ey, ez);
            Vec3 tail = head.subtract(r.vx * len, r.vy * len, r.vz * len);
            Vec3 side = tail.subtract(head).cross(head).normalize();
            if (!Double.isFinite(side.x) || side.lengthSqr() < 0.5) {
                continue;
            }
            Vec3 hs = side.scale(halfWidth(hd));
            Vec3 ts = side.scale(halfWidth(tail.length()));
            out.addVertex(m, (float) (head.x + hs.x), (float) (head.y + hs.y), (float) (head.z + hs.z))
                    .setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, (float) (head.x - hs.x), (float) (head.y - hs.y), (float) (head.z - hs.z))
                    .setColor(cr, cg, cb, 1.0f);
            out.addVertex(m, (float) (tail.x - ts.x), (float) (tail.y - ts.y), (float) (tail.z - ts.z))
                    .setColor(cr, cg, cb, 0.0f);
            out.addVertex(m, (float) (tail.x + ts.x), (float) (tail.y + ts.y), (float) (tail.z + ts.z))
                    .setColor(cr, cg, cb, 0.0f);
        }
        stack.popPose();
        buffers.endBatch(RenderType.debugQuads());
        LookStats.tracerNanos += System.nanoTime() - t0;
    }
}
