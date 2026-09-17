package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleInstance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/** A burst of billboards thrown from a point, written once and never touched again. */
public final class InstancedBurst implements WFFlywheelEffect {

    /**
     * Fills a fresh emitter.
     *
     * @return the longest life it spawned, in seconds, which is how long the effect has to stay alive
     */
    @FunctionalInterface
    public interface Fill {
        float fill(ParticleEmitter emitter);
    }

    private final Level level;
    private final ParticleEmitter emitter;
    private final InstanceType<ParticleInstance> type;
    private final Model model;
    private final float lifeSeconds;

    private float age;

    public InstancedBurst(Level level, InstanceType<ParticleInstance> type, Model model, int style, int count,
                          double x, double y, double z, Fill fill) {
        this.level = level;
        this.type = type;
        this.model = model;
        this.emitter = ParticleEmitter.create(style, Math.max(1, count), x, y, z);
        this.lifeSeconds = fill.fill(emitter);
    }

    ParticleEmitter emitter() {
        return emitter;
    }

    InstanceType<ParticleInstance> type() {
        return type;
    }

    Model model() {
        return model;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedBurstVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        age += 1.0f / 20.0f;
    }

    @Override
    public boolean isExpired() {
        return age > lifeSeconds;
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }
}
