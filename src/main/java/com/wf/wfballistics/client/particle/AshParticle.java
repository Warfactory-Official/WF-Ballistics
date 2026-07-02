package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
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

        if (!this.onGround) {
            this.oRoll = this.roll;
            this.roll += 0.1F;
        }
        this.move(this.xd, this.yd, this.zd);

        float left = this.lifetime - this.age;
        this.alpha = left < 40 ? left / 40F : 1F;
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
