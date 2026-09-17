package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.particle.ParticleEmitter;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/** A burst of hot puffs (the cloud an explosion throws out) rendered through GemRender's particle instancer. */
public class InstancedParticleEffect implements WFFlywheelEffect {

    private static final float LIFE_TICKS_MIN = 150F;
    private static final float LIFE_TICKS_JITTER = 90F;

    private final Level level;
    private final ParticleEmitter emitter;
    private final float lifeSeconds;

    /** Spawn point, kept for callers that place lights or sounds on the same burst. */
    final double cx, cy, cz;

    private float age;

    public InstancedParticleEffect(Level level, double x, double y, double z, int count, float scale, float speed) {
        this.level = level;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        this.emitter = ParticleEmitter.create(WFParticleStyles.puff(), count, x, y, z);

        float longest = 0F;
        for (int i = 0; i < count; i++) {
            float life = (LIFE_TICKS_MIN + level.random.nextFloat() * LIFE_TICKS_JITTER) / 20F;
            longest = Math.max(longest, life);

            emitter.spawn(x, y, z,
                    level.random.nextGaussian() * speed * 20.0,
                    level.random.nextDouble() * 1.0,
                    level.random.nextGaussian() * speed * 20.0,
                    life,
                    scale * (0.9F + level.random.nextFloat() * 0.2F),
                    level.random.nextFloat() * Mth.TWO_PI,
                    0.85F + level.random.nextFloat() * 0.3F);
        }
        this.lifeSeconds = longest;
    }

    ParticleEmitter emitter() {
        return emitter;
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
        age += 1F / 20F;
    }

    @Override
    public boolean isExpired() {
        return age >= lifeSeconds;
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }
}
