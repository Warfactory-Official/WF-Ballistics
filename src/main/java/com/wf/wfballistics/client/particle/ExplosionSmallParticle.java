package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public class ExplosionSmallParticle extends TextureSheetParticle {

    private final float baseScale;
    private final float hue;

    public ExplosionSmallParticle(ClientLevel level, double x, double y, double z, float scale, float speedMult) {
        super(level, x, y, z);
        this.lifetime = 25 + this.random.nextInt(10);
        this.baseScale = scale * 0.9F + this.random.nextFloat() * 0.2F;
        this.quadSize = this.baseScale;
        this.xd = this.random.nextGaussian() * speedMult;
        this.zd = this.random.nextGaussian() * speedMult;
        this.hue = 20F + this.random.nextFloat() * 20F;
        this.hasPhysics = true;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
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

        this.yd += 0.004D;
        this.xd *= 0.65D;
        this.zd *= 0.65D;

        float ageScaled = (float) this.age / this.lifetime;
        this.oRoll = this.roll;
        this.roll += (1 - ageScaled) * 0.5F * ((this.hashCode() % 2) - 0.5F);

        int rgb = Mth.hsvToRgb(
                this.hue / 255F,
                Math.max(1F - ageScaled * 2F, 0F),
                Mth.clamp(1.25F - ageScaled * 2F, this.hue * 0.01F - 0.1F, 1F));
        this.rCol = (rgb >> 16 & 0xFF) / 255F;
        this.gCol = (rgb >> 8 & 0xFF) / 255F;
        this.bCol = (rgb & 0xFF) / 255F;
        this.alpha = (float) Math.pow(1 - Math.min(ageScaled, 1), 0.25) * 0.7F * 0.5F;

        this.move(this.xd, this.yd, this.zd);
    }

    @Override
    public float getQuadSize(float partialTicks) {
        double ageScaled = (this.age + partialTicks) / this.lifetime;
        return (float) (0.25 + 1 - Math.pow(1 - ageScaled, 4) + (this.age + partialTicks) * 0.02) * this.baseScale;
    }

    @Override
    public int getLightColor(float partialTick) {
        return 0xF000F0; // full bright
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprites) {
            WFParticleSprites.explosionSmall = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            ExplosionSmallParticle particle = new ExplosionSmallParticle(level, x, y, z, 2F, 0.5F);
            particle.pickSprite(WFParticleSprites.explosionSmall);
            return particle;
        }
    }
}
