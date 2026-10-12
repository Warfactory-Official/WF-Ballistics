package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;

/**
 * The Flywheel visual for an {@link InstancedTrailEffect}: one instance per ring slot, each 16 bytes, each written
 * exactly once when the pool is built.
 */
public class InstancedTrailVisual extends AbstractVisual implements EffectVisual<InstancedTrailEffect> {

    private final ParticlePool pool;

    public InstancedTrailVisual(VisualizationContext ctx, InstancedTrailEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(), effect.kind().instanceType(),
                effect.kind() == InstancedTrailEffect.Kind.EXHAUST
                        ? ParticleModels.glowing(WFParticleTextures.MIST_SOFT)
                        : ParticleModels.absorbance(WFParticleTextures.MIST_SOFT));
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
