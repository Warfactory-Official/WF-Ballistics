package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public class FlameParticle extends TextureSheetParticle {

    protected FlameParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.lifetime = 8 + this.random.nextInt(8);
        this.quadSize = 0.4F + this.random.nextFloat() * 0.3F;
        this.hasPhysics = false;
        this.xd = (this.random.nextDouble() - 0.5) * 0.02;
        this.yd = 0.02 + this.random.nextDouble() * 0.03;
        this.zd = (this.random.nextDouble() - 0.5) * 0.02;
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

        this.yd += 0.002;
        this.xd *= 0.9;
        this.zd *= 0.9;
        this.move(this.xd, this.yd, this.zd);

        float life = (float) this.age / this.lifetime;
        this.rCol = Mth.clamp(1.0F - life * 0.3F, 0F, 1F);
        this.gCol = Mth.clamp(0.6F * (1F - life) + 0.1F, 0F, 1F);
        this.bCol = 0.05F;
        this.alpha = Mth.clamp((float) Math.pow(1F - life, 0.5), 0F, 1F) * 0.85F;
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
            WFParticleSprites.flame = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            FlameParticle particle = new FlameParticle(level, x, y, z);
            particle.pickSprite(WFParticleSprites.flame);
            return particle;
        }
    }
}
