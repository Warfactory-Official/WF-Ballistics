package com.wf.wflib.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.wflib.WFLib;
import com.wf.wflib.laser.LaserBeamPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Laser beams from {@link LaserBeamPacket}: additive red sheath + white core, alive 2 ticks per packet. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class LaserBeams {

    private static final int LIFE = 2;
    private static final int MAX_ACTIVE = 256;
    private static final float SHEATH = 0.09f;
    private static final float CORE = 0.025f;

    private static final List<Beam> ACTIVE = new ArrayList<>();

    private LaserBeams() {
    }

    public static void accept(LaserBeamPacket packet) {
        if (ACTIVE.size() < MAX_ACTIVE) {
            ACTIVE.add(new Beam(new Vec3(packet.from()), new Vec3(packet.to())));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().isPaused()) {
            ACTIVE.removeIf(beam -> ++beam.age >= LIFE);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lightning());
        for (Beam beam : ACTIVE) {
            beam.tube(consumer, matrix, SHEATH, 255, 40, 20, 110);
            beam.tube(consumer, matrix, CORE, 255, 235, 220, 255);
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

    private static final class Beam {
        private final Vec3 from;
        private final Vec3 to;
        private final Vec3 u;
        private final Vec3 v;
        private int age;

        Beam(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            Vec3 dir = to.subtract(from).normalize();
            Vec3 ref = Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            this.u = dir.cross(ref).normalize();
            this.v = dir.cross(this.u).normalize();
        }

        /** Two crossed quads along the beam. */
        void tube(VertexConsumer c, Matrix4f m, float r, int red, int green, int blue, int alpha) {
            for (Vec3 side : new Vec3[]{this.u.scale(r), this.v.scale(r)}) {
                vertex(c, m, this.from.add(side), red, green, blue, alpha);
                vertex(c, m, this.from.subtract(side), red, green, blue, alpha);
                vertex(c, m, this.to.subtract(side), red, green, blue, alpha);
                vertex(c, m, this.to.add(side), red, green, blue, alpha);
            }
        }

        private static void vertex(VertexConsumer c, Matrix4f m, Vec3 p, int r, int g, int b, int a) {
            c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
        }
    }
}
