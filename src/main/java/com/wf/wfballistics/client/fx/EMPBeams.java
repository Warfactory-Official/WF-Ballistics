package com.wf.wfballistics.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class EMPBeams {

    private static final int MAX_ACTIVE = 512;
    private static final int OUTER_COLOR = 0x33E1FF;
    private static final int INNER_COLOR = 0xFFFFFF;
    private static final int SEGMENTS = 10;
    private static final int LAYERS = 3;
    private static final float THICKNESS = 0.35F;

    private static final List<Ray> ACTIVE = new ArrayList<>();

    private EMPBeams() {
    }

    public static void spawnBurst(double x, double y, double z, int radius, RandomSource rand) {
        int waves = 3;
        int perWave = Math.min(20, 8 + radius / 6);
        for (int w = 0; w < waves; w++) {
            int delay = w * 7;
            for (int i = 0; i < perWave; i++) {
                double theta = rand.nextDouble() * Math.PI * 2.0;
                double phi = Math.acos(2.0 * rand.nextDouble() - 1.0);
                double sinPhi = Math.sin(phi);
                Vec3 dir = new Vec3(sinPhi * Math.cos(theta), Math.cos(phi), sinPhi * Math.sin(theta));
                double length = radius * (0.45 + rand.nextDouble() * 0.55);
                int maxAge = 7 + rand.nextInt(6);
                add(new Ray(x, y, z, dir, length, delay, maxAge, rand.nextLong()));
            }
        }
    }

    public static void spawnStun(double x, double y, double z, RandomSource rand) {
        int rays = 4 + rand.nextInt(3);
        for (int i = 0; i < rays; i++) {
            double theta = rand.nextDouble() * Math.PI * 2.0;
            double phi = Math.acos(2.0 * rand.nextDouble() - 1.0);
            double sinPhi = Math.sin(phi);
            Vec3 dir = new Vec3(sinPhi * Math.cos(theta), Math.cos(phi), sinPhi * Math.sin(theta));
            double length = 1.2 + rand.nextDouble() * 1.3;
            int maxAge = 5 + rand.nextInt(4);
            add(new Ray(x, y, z, dir, length, 0, maxAge, rand.nextLong()));
        }
    }

    private static void add(Ray ray) {
        if (ACTIVE.size() < MAX_ACTIVE) {
            ACTIVE.add(ray);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Ray ray = ACTIVE.get(i);
            if (ray.delay > 0) {
                ray.delay--;
            } else if (++ray.age >= ray.maxAge) {
                ACTIVE.remove(i);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lightning());

        for (Ray ray : ACTIVE) {
            if (ray.delay <= 0) {
                ray.render(consumer, matrix, partialTick);
            }
        }

        buffers.endBatch(RenderType.lightning());
        pose.popPose();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ACTIVE.clear();
        }
    }

    private static final class Ray {

        private final double ox;
        private final double oy;
        private final double oz;
        private final Vec3 u;
        private final Vec3 v;
        private final Vec3 dir;
        private final double length;
        private final int maxAge;
        private final long seed;
        private int delay;
        private int age;

        private Ray(double x, double y, double z, Vec3 dir, double length, int delay, int maxAge, long seed) {
            this.ox = x;
            this.oy = y;
            this.oz = z;
            this.dir = dir.normalize();
            this.length = length;
            this.delay = delay;
            this.maxAge = maxAge;
            this.seed = seed;
            Vec3 ref = Math.abs(this.dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            this.u = this.dir.cross(ref).normalize();
            this.v = this.dir.cross(this.u).normalize();
        }

        private static void tube(VertexConsumer c, Matrix4f m,
                                 double ax, double ay, double az, double bx, double by, double bz,
                                 double aux, double auy, double auz, double avx, double avy, double avz,
                                 int r, int g, int b, int alpha) {
            quad(c, m, ax + aux + avx, ay + auy + avy, az + auz + avz, ax + aux - avx, ay + auy - avy, az + auz - avz,
                    bx + aux - avx, by + auy - avy, bz + auz - avz, bx + aux + avx, by + auy + avy, bz + auz + avz,
                    r, g, b, alpha);
            quad(c, m, ax - aux + avx, ay - auy + avy, az - auz + avz, ax - aux - avx, ay - auy - avy, az - auz - avz,
                    bx - aux - avx, by - auy - avy, bz - auz - avz, bx - aux + avx, by - auy + avy, bz - auz + avz,
                    r, g, b, alpha);
            quad(c, m, ax + aux + avx, ay + auy + avy, az + auz + avz, ax - aux + avx, ay - auy + avy, az - auz + avz,
                    bx - aux + avx, by - auy + avy, bz - auz + avz, bx + aux + avx, by + auy + avy, bz + auz + avz,
                    r, g, b, alpha);
            quad(c, m, ax + aux - avx, ay + auy - avy, az + auz - avz, ax - aux - avx, ay - auy - avy, az - auz - avz,
                    bx - aux - avx, by - auy - avy, bz - auz - avz, bx + aux - avx, by + auy - avy, bz + auz - avz,
                    r, g, b, alpha);
        }

        private static void quad(VertexConsumer c, Matrix4f m,
                                 double x1, double y1, double z1, double x2, double y2, double z2,
                                 double x3, double y3, double z3, double x4, double y4, double z4,
                                 int r, int g, int b, int a) {
            c.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a).endVertex();
            c.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, b, a).endVertex();
            c.vertex(m, (float) x3, (float) y3, (float) z3).color(r, g, b, a).endVertex();
            c.vertex(m, (float) x4, (float) y4, (float) z4).color(r, g, b, a).endVertex();
        }

        private static int lerp(int from, int to, float t) {
            return Mth.clamp((int) (from + (to - from) * t), 0, 255);
        }

        private void render(VertexConsumer consumer, Matrix4f matrix, float partialTick) {
            float life = (age + partialTick) / maxAge;
            if (life >= 1F) {
                return;
            }
            int alpha = (int) (255F * (1F - life));
            if (alpha <= 0) {
                return;
            }
            RandomSource rand = RandomSource.create(seed * 31L + age);
            double jitter = length * 0.06;

            double[] px = new double[SEGMENTS + 1];
            double[] py = new double[SEGMENTS + 1];
            double[] pz = new double[SEGMENTS + 1];
            for (int i = 0; i <= SEGMENTS; i++) {
                double t = length * i / SEGMENTS;
                double bx = ox + dir.x * t;
                double by = oy + dir.y * t;
                double bz = oz + dir.z * t;
                if (i > 0) {
                    double a = rand.nextDouble() * Math.PI * 2.0;
                    double mag = jitter * (0.4 + rand.nextDouble());
                    double jx = (u.x * Math.cos(a) + v.x * Math.sin(a)) * mag;
                    double jy = (u.y * Math.cos(a) + v.y * Math.sin(a)) * mag;
                    double jz = (u.z * Math.cos(a) + v.z * Math.sin(a)) * mag;
                    bx += jx;
                    by += jy;
                    bz += jz;
                }
                px[i] = bx;
                py[i] = by;
                pz[i] = bz;
            }

            float radius = THICKNESS / LAYERS;
            for (int i = 1; i <= SEGMENTS; i++) {
                for (int j = 1; j <= LAYERS; j++) {
                    float inter = LAYERS > 1 ? (float) (j - 1) / (float) (LAYERS - 1) : 0F;
                    int r = lerp(OUTER_COLOR >> 16 & 0xFF, INNER_COLOR >> 16 & 0xFF, inter);
                    int g = lerp(OUTER_COLOR >> 8 & 0xFF, INNER_COLOR >> 8 & 0xFF, inter);
                    int b = lerp(OUTER_COLOR & 0xFF, INNER_COLOR & 0xFF, inter);

                    double rad = radius * j;
                    double aux = u.x * rad, auy = u.y * rad, auz = u.z * rad;
                    double avx = v.x * rad, avy = v.y * rad, avz = v.z * rad;

                    tube(consumer, matrix, px[i - 1], py[i - 1], pz[i - 1], px[i], py[i], pz[i],
                            aux, auy, auz, avx, avy, avz, r, g, b, alpha);
                }
            }
        }
    }
}
