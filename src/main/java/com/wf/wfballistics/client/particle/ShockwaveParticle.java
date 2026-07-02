package com.wf.wfballistics.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;


public class ShockwaveParticle extends Particle {

    private final float waveScale;

    public ShockwaveParticle(ClientLevel level, double x, double y, double z, float waveScale, int maxAge) {
        super(level, x, y, z);
        this.waveScale = waveScale;
        this.lifetime = Math.max(1, maxAge);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.xd = this.yd = this.zd = 0.0;
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        float t = this.age + partialTicks;
        float growth = (float) ((1.0 - Math.exp(-0.125 * t)) * this.waveScale);
        float a = Mth.clamp(1.0F - t / this.lifetime, 0.0F, 1.0F);
        if (a <= 0.0F || growth <= 0.0F) {
            return;
        }

        Vec3 cam = camera.getPosition();
        double px = this.x - cam.x;
        double py = this.y - cam.y - 0.25;
        double pz = this.z - cam.z;

        int light = 0x00F000F0; // full bright
        emit(buffer, px - growth, py, pz - growth, 0.0F, 0.0F, a, light);
        emit(buffer, px - growth, py, pz + growth, 0.0F, 1.0F, a, light);
        emit(buffer, px + growth, py, pz + growth, 1.0F, 1.0F, a, light);
        emit(buffer, px + growth, py, pz - growth, 1.0F, 0.0F, a, light);
    }

    private static void emit(VertexConsumer buffer, double x, double y, double z, float u, float v, float a, int light) {
        buffer.vertex(x, y, z).uv(u, v).color(1.0F, 1.0F, 1.0F, a).uv2(light).endVertex();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return WFParticleRenderTypes.SHOCKWAVE;
    }
}
