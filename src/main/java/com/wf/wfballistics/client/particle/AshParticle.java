package com.wf.wfballistics.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The settling cinders left when something is cremated. Dark, near-weightless flakes that drift down,
 * tumble until they land, and then linger for a long time before fading out — so a kill leaves a visible
 * scattering of ash on the ground rather than a single puff.
 */
public class AshParticle extends TextureSheetParticle {

    public AshParticle(ClientLevel level, double x, double y, double z, float scale) {
        super(level, x, y, z);
        this.lifetime = 1200 + this.random.nextInt(20);
        this.quadSize = scale * 0.9F + this.random.nextFloat() * 0.2F;
        float grey = this.random.nextFloat() * 0.1F + 0.1F;
        this.setColor(grey, grey, grey);
        this.hasPhysics = true;
        this.roll = this.random.nextFloat() * 6.2831855F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        this.yd -= 0.01D;
        this.xd *= 0.95D;
        this.yd *= 0.99D;
        this.zd *= 0.95D;

        this.oRoll = this.roll;
        if (!this.onGround) {
            this.roll += 0.1F;
        }
        this.move(this.xd, this.yd, this.zd);

        float left = this.lifetime - this.age;
        this.alpha = left < 40 ? left / 40F : 1F;
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        Vec3 cam = camera.getPosition();
        float x = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cam.x());
        float y = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cam.y()) + (this.onGround ? 0.02F : 0.0F);
        float z = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cam.z());
        float size = this.getQuadSize(partialTicks);
        float roll = Mth.lerp(partialTicks, this.oRoll, this.roll);
        float cos = Mth.cos(roll) * size;
        float sin = Mth.sin(roll) * size;
        float u0 = this.getU0();
        float u1 = this.getU1();
        float v0 = this.getV0();
        float v1 = this.getV1();
        int light = com.wf.wfballistics.client.fx.ParticleLight.surface(this.level, this.x, this.y, this.z);

        float[][] corner = {{-1F, -1F}, {-1F, 1F}, {1F, 1F}, {1F, -1F}};
        float[][] uv = {{u1, v1}, {u1, v0}, {u0, v0}, {u0, v1}};
        for (int i = 0; i < 4; i++) {
            float bx = corner[i][0];
            float bz = corner[i][1];
            float rx = bx * cos - bz * sin;
            float rz = bx * sin + bz * cos;
            buffer.vertex(x + rx, y, z + rz)
                    .uv(uv[i][0], uv[i][1])
                    .color(this.rCol, this.gCol, this.bCol, this.alpha)
                    .uv2(light)
                    .endVertex();
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprites) {
            WFParticleSprites.ash = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            AshParticle particle = new AshParticle(level, x, y, z, 0.15F);
            particle.pickSprite(WFParticleSprites.ash);
            return particle;
        }
    }
}
