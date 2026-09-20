package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;

/** The Flywheel visual for an {@link InstancedMistEffect}: the billboard path for one gas cell. */
public class InstancedMistVisual extends AbstractVisual implements EffectVisual<InstancedMistEffect> {

    private final ParticlePool pool;

    public InstancedMistVisual(VisualizationContext ctx, InstancedMistEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(), GemRenderParticleTypes.BILLBOARD,
                ParticleModels.absorbance(WFParticleTextures.MIST_SOFT));
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
