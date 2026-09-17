package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleInstance;
import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;

/** The Flywheel visual for an {@link InstancedDebrisEffect}: one instance per chunk of ground, written once. */
public class InstancedDebrisVisual extends AbstractVisual implements EffectVisual<InstancedDebrisEffect> {

    private static final InstanceType<ParticleInstance> DEBRIS = GemRenderParticleTypes.custom(
            WFParticleTextures.DEBRIS_VERTEX, WFParticleTextures.MESH_CULL);

    private final ParticlePool pool;

    public InstancedDebrisVisual(VisualizationContext ctx, InstancedDebrisEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.pool = new ParticlePool(ctx, effect.emitter(), DEBRIS, effect.model());
    }

    @Override
    protected void _delete() {
        pool.delete();
    }
}
