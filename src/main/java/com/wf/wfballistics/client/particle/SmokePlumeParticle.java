package com.wf.wfballistics.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/** A heavy plume of dark smoke. */
public class SmokePlumeParticle extends TextureSheetParticle {

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private final SpriteSet sprites;
    private final float spin;
    private float driftX;
    private float driftZ;
    private float driftY;

    protected SmokePlumeParticle(ClientLevel level, double x, double y, double z,
                                 double dx, double dy, double dz, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.lifetime = 160 + this.random.nextInt(140); // ~8-15s
        this.gravity = -0.012F;
        this.friction = 0.95F;
        this.hasPhysics = false;
        this.quadSize *= 3.0F + this.random.nextFloat() * 7.5F;
        this.xd = dx;
        this.yd = dy;
        this.zd = dz;
        float grey = 0.22F + this.random.nextFloat() * 0.22F;
        this.setColor(grey, grey, grey);
        this.roll = this.random.nextFloat() * TWO_PI;
        this.oRoll = this.roll;
        this.spin = (this.random.nextFloat() - 0.5F) * 0.04F;

        double hLen = Math.sqrt(dx * dx + dz * dz + dy * dy);
        if (hLen > 1.0e-4) {
            this.driftX = (float) (dx / hLen) * 0.1F;
            this.driftZ = (float) (dz / hLen) * 0.1F;
            this.driftY = (float) (dy / hLen) * 0.1F;
        } else {
            this.driftX = 0.0F;
            this.driftZ = 0.0F;
            this.driftY = 0.0F;
        }
        updateSprite();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        this.oRoll = this.roll;
        this.xd += this.driftX;
        this.zd += this.driftZ;
        this.yd += this.driftY;

        this.driftX = -(driftX - driftX / 2);
        this.driftZ = -(driftZ - driftZ / 2);
        this.driftY = -driftY / 2;

        super.tick();
        this.roll += this.spin;
        updateSprite();
        // Full cloud -> less opaque as it ages.
        this.alpha = Math.max(0.0F, 1.0F - (float) this.age / (float) this.lifetime);
    }

    /** Run the sprite animation in reverse: start on the fullest frame, end on the smallest/wispiest. */
    private void updateSprite() {
        this.setSprite(this.sprites.get(this.lifetime - this.age, this.lifetime));
    }

    @Override
    public float getQuadSize(float partialTicks) {
        // Full cloud -> smaller as it ages.
        float progress = (this.age + partialTicks) / this.lifetime;
        return this.quadSize * Math.max(0.35F, 1.0F - 0.6F * progress);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new SmokePlumeParticle(level, x, y, z, dx, dy, dz, this.sprites);
        }
    }
}
