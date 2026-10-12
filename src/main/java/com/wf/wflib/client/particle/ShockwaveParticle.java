package com.wf.wflib.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public class ShockwaveParticle extends Particle {

    private final float waveScale;
    /** Ring plane axes and normal: vent frame x, z, y. */
    private final Vector3f u, w, n;

    public ShockwaveParticle(ClientLevel level, double x, double y, double z, float waveScale, int maxAge) {
        this(level, x, y, z, waveScale, maxAge, new Quaternionf());
    }

    /** {@code vent}: ring normal = +y -> open side. */
    public ShockwaveParticle(ClientLevel level, double x, double y, double z, float waveScale, int maxAge,
                             Quaternionf vent) {
        super(level, x, y, z);
        this.waveScale = waveScale;
        this.u = vent.transform(new Vector3f(1, 0, 0));
        this.w = vent.transform(new Vector3f(0, 0, 1));
        this.n = vent.transform(new Vector3f(0, 1, 0));
        this.lifetime = Math.max(1, maxAge);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.xd = this.yd = this.zd = 0.0;
    }

    private static void emit(VertexConsumer buffer, double x, double y, double z, float u, float v, float a, int light) {
        buffer.addVertex((float) x, (float) y, (float) z).setUv(u, v).setColor(1.0F, 1.0F, 1.0F, a).setLight(light);
    }

    private void corner(VertexConsumer buffer, double px, double py, double pz, float a0, float b0, float uu, float vv,
                        float a, int light) {
        emit(buffer, px + u.x * a0 + w.x * b0, py + u.y * a0 + w.y * b0, pz + u.z * a0 + w.z * b0, uu, vv, a, light);
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
        double px = this.x - cam.x - n.x * 0.25;
        double py = this.y - cam.y - n.y * 0.25;
        double pz = this.z - cam.z - n.z * 0.25;

        int light = 0x00F000F0; // full bright
        corner(buffer, px, py, pz, -growth, -growth, 0.0F, 0.0F, a, light);
        corner(buffer, px, py, pz, -growth, growth, 0.0F, 1.0F, a, light);
        corner(buffer, px, py, pz, growth, growth, 1.0F, 1.0F, a, light);
        corner(buffer, px, py, pz, growth, -growth, 1.0F, 0.0F, a, light);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return WFParticleRenderTypes.SHOCKWAVE;
    }
}
