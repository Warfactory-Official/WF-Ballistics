package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleInstance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;

/** The Flywheel visual for an {@link InstancedFlameEffect}. */
public class InstancedFlameVisual extends AbstractVisual implements EffectVisual<InstancedFlameEffect> {

    private static final InstanceType<ParticleInstance> FLAME = GemRenderParticleTypes.custom(
            WFParticleTextures.FLAME_VERTEX, WFParticleTextures.FLAME_CULL);

    private final ParticlePool pool;

    public InstancedFlameVisual(VisualizationContext ctx, InstancedFlameEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(), FLAME,
                ParticleModels.additive(WFParticleTextures.PARTICLE_BASE));
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
