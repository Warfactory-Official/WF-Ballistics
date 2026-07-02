package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;


public class RocketFlameParticle extends TextureSheetParticle {

    public RocketFlameParticle(ClientLevel level, double x, double y, double z, float scale) {
        super(level, x, y, z);
        this.lifetime = 300 + this.random.nextInt(50);
        this.quadSize = scale;
        this.hasPhysics = false;
    }

    public void setLifetime(int lifetime) {
        this.lifetime = Math.max(1, lifetime);
    }

    public void setCollision(boolean collision) {
        this.hasPhysics = collision;
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

        this.xd *= 0.91D;
        this.yd *= 0.91D;
        this.zd *= 0.91D;
        this.move(this.xd, this.yd, this.zd);

        float dark = 1 - Math.min((float) this.age / (this.lifetime * 0.25F), 1F);
        this.rCol = Mth.clamp(dark + 0.1F, 0F, 1F);
        this.gCol = Mth.clamp(0.6F * dark + 0.1F, 0F, 1F);
        this.bCol = 0.05F;
        this.alpha = Mth.clamp((float) Math.pow(1 - Math.min((float) this.age / this.lifetime, 1F), 0.5), 0F, 1F) * 0.75F;
    }

    @Override
    public float getQuadSize(float partialTicks) {
        return this.quadSize * (0.5F + (this.age + partialTicks) / this.lifetime * 2F);
    }

    @Override
    public int getLightColor(float partialTick) {
        return 240;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return WFParticleRenderTypes.ADDITIVE;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprites) {
            WFParticleSprites.rocketFlame = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            RocketFlameParticle particle = new RocketFlameParticle(level, x, y, z, 1F);
            particle.setParticleSpeed(xSpeed, ySpeed, zSpeed);
            particle.pickSprite(WFParticleSprites.rocketFlame);
            return particle;
        }
    }
}
