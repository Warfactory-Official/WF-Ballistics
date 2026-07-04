package com.wf.wfballistics.client.flywheel;

import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * A cloud of billboard "puffs" rendered through Flywheel as GPU instances rather than vanilla particles.
 *
 * <p>This is a Flywheel {@link Effect} — Flywheel's particle analogue. The CPU only simulates the puffs
 * (cheap structs) and the matching {@link InstancedParticleVisual} uploads one instanced quad per puff to
 * the GPU, so a whole cloud is one instanced draw call instead of the per-render-type buffer rebuild vanilla
 * does. Lifecycle (tick + removal) is driven by {@link InstancedParticleManager}.
 *
 * <p>The simulation mirrors {@link com.wf.wfballistics.client.particle.ExplosionSmallParticle}: hot orange
 * puffs that swell, drift, and fade.
 */
public class InstancedParticleEffect implements WFFlywheelEffect {

    final Puff[] puffs;
    final int maxAge;
    // Spawn point, used as the effect centre for the distance LOD.
    final double cx, cy, cz;
    private final Level level;
    int age = 0;

    public InstancedParticleEffect(Level level, double x, double y, double z, int count, float scale, float speed) {
        this.level = level;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        this.puffs = new Puff[count];
        int max = 0;
        for (int i = 0; i < count; i++) {
            Puff p = new Puff(level.random, x, y, z, scale, speed);
            puffs[i] = p;
            max = Math.max(max, p.life);
        }
        this.maxAge = max + 1;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedParticleVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        age++;
        for (Puff p : puffs) {
            p.tick();
        }
    }

    @Override
    public boolean isExpired() {
        return age >= maxAge;
    }

    static final class Puff {
        final float baseScale;
        final float hue;
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life;

        Puff(RandomSource r, double x, double y, double z, float scale, float speed) {
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = r.nextGaussian() * speed;
            this.vz = r.nextGaussian() * speed;
            this.vy = r.nextDouble() * 0.05;
            this.baseScale = scale * 0.9F + r.nextFloat() * 0.2F;
            this.life = 45 + r.nextInt(30);
            this.hue = 20F + r.nextFloat() * 20F;
        }

        void tick() {
            px = x;
            py = y;
            pz = z;
            vy += 0.004;
            vx *= 0.65;
            vz *= 0.65;
            x += vx;
            y += vy;
            z += vz;
            age++;
        }

        double ix(float pt) {
            return px + (x - px) * pt;
        }

        double iy(float pt) {
            return py + (y - py) * pt;
        }

        double iz(float pt) {
            return pz + (z - pz) * pt;
        }

        float scale(float pt) {
            double a = (age + pt) / life;
            return (float) (0.25 + 1 - Math.pow(1 - a, 4) + (age + pt) * 0.02) * baseScale;
        }

        // Packed ARGB for this frame
        int argb(float pt) {
            float a = (age + pt) / life;
            float alpha = Mth.clamp((float) Math.pow(1 - Math.min(a, 1), 0.25) * 0.7F, 0F, 1F);
            int rgb = Mth.hsvToRgb(hue / 255F, Math.max(1F - a * 2F, 0F), Mth.clamp(1.25F - a * 2F, hue * 0.01F - 0.1F, 1F));
            return ((int) (alpha * 255F) << 24) | (rgb & 0xFFFFFF);
        }

        boolean dead() {
            return age >= life;
        }
    }
}
