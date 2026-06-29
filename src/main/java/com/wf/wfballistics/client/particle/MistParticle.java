package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import org.jetbrains.annotations.Nullable;

public class MistParticle extends TextureSheetParticle {

    private final float baseSize;

    public MistParticle(ClientLevel level, double x, double y, double z, float size, int rgb) {
        super(level, x, y, z);
        this.lifetime = 50 + this.random.nextInt(10);
        this.baseSize = size;
        this.quadSize = size;
        this.rCol = (rgb >> 16 & 0xFF) / 255F;
        this.gCol = (rgb >> 8 & 0xFF) / 255F;
        this.bCol = (rgb & 0xFF) / 255F;
        this.alpha = 0F;
        this.hasPhysics = false;
        this.yd = 0.02;
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

        this.xd *= 0.9D;
        this.zd *= 0.9D;
        this.move(this.xd, this.yd, this.zd);

        this.oRoll = this.roll;
        this.roll += 0.02F;

        float ageScaled = (float) this.age / this.lifetime;
        float peak = 0.35F;
        if (ageScaled < 0.2F) {
            this.alpha = peak * (ageScaled / 0.2F);
        } else if (ageScaled > 0.8F) {
            this.alpha = peak * (1F - (ageScaled - 0.8F) / 0.2F);
        } else {
            this.alpha = peak;
        }
    }

    @Override
    public float getQuadSize(float partialTicks) {
        return this.baseSize * (0.75F + (this.age + partialTicks) / this.lifetime * 1.5F);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprites) {
            WFParticleSprites.mist = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            MistParticle particle = new MistParticle(level, x, y, z, 0.75F, 0xC0C0C0);
            particle.pickSprite(WFParticleSprites.mist);
            return particle;
        }
    }
}
