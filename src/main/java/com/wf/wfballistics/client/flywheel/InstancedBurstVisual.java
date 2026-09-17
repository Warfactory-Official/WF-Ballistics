package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;

/**
 * The Flywheel visual for an {@link InstancedBurst}: one 16-byte instance per particle, written when the burst is
 * created and never rewritten.
 */
public class InstancedBurstVisual extends AbstractVisual implements EffectVisual<InstancedBurst> {

    private final ParticlePool pool;

    public InstancedBurstVisual(VisualizationContext ctx, InstancedBurst effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(), effect.type(), effect.model());
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
