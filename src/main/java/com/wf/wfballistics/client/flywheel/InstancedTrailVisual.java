package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.ModModels;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The Flywheel visual for an {@link InstancedTrailEffect}: one billboarded {@link TransformedInstance} per
 * ring-buffer slot, sharing the {@link ModModels#INSTANCED_QUAD} model. Inactive slots collapse to zero
 * size, so the instance buffer is allocated once and only its contents change frame to frame.
 */
public class InstancedTrailVisual extends AbstractVisual implements SimpleDynamicVisual, EffectVisual<InstancedTrailEffect> {

    private final InstancedTrailEffect effect;
    private final TransformedInstance[] instances;
    private final Matrix4f scratch = new Matrix4f();

    public InstancedTrailVisual(VisualizationContext ctx, InstancedTrailEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.effect = effect;

        Model model = Models.partial(ModModels.INSTANCED_QUAD);
        Instancer<TransformedInstance> instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model);

        this.instances = new TransformedInstance[effect.pool.length];
        for (int i = 0; i < instances.length; i++) {
            instances[i] = instancer.createInstance();
        }
    }

    @Override
    public void beginFrame(Context context) {
        float pt = context.partialTick();
        Quaternionf camRotation = context.camera().rotation();
        Vec3i origin = renderOrigin();

        for (int i = 0; i < instances.length; i++) {
            InstancedTrailEffect.Flame flame = effect.pool[i];
            TransformedInstance instance = instances[i];

            if (!flame.active) {
                instance.setTransform(scratch.identity().scale(0F));
                instance.setChanged();
                continue;
            }

            float scale = flame.scale(pt);
            scratch.identity()
                    .translate((float) (flame.ix(pt) - origin.getX()),
                            (float) (flame.iy(pt) - origin.getY()),
                            (float) (flame.iz(pt) - origin.getZ()))
                    .rotate(camRotation)
                    .scale(scale)
                    .translate(-0.5F, -0.5F, -0.5F);

            instance.setTransform(scratch);
            instance.colorArgb(flame.argb(pt));
            instance.light(0xF000F0);
            instance.setChanged();
        }
    }

    @Override
    protected void _delete() {
        for (TransformedInstance instance : instances) {
            if (instance != null) {
                instance.delete();
            }
        }
    }
}
