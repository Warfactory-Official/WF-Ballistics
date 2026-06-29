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
 * The Flywheel visual for an {@link InstancedParticleEffect}: one {@link TransformedInstance} per puff,
 * sharing a single instanced billboard-quad model ({@link ModModels#INSTANCED_QUAD}).
 *
 * <p>Per frame ({@link #beginFrame}) each instance is billboarded toward the camera, scaled, and tinted —
 * just a transform + colour upload, no geometry rebuild. The quad is modelled in {@code [0,1]³} centred on
 * {@code (0.5, 0.5, 0.5)}, so the transform is {@code translate(world − renderOrigin) · cameraRotation ·
 * scale · translate(−½)}.
 */
public class InstancedParticleVisual extends AbstractVisual implements SimpleDynamicVisual, EffectVisual<InstancedParticleEffect> {

    private final InstancedParticleEffect effect;
    private final TransformedInstance[] instances;
    private final Matrix4f scratch = new Matrix4f();

    public InstancedParticleVisual(VisualizationContext ctx, InstancedParticleEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.effect = effect;

        Model model = Models.partial(ModModels.INSTANCED_QUAD);
        Instancer<TransformedInstance> instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model);

        this.instances = new TransformedInstance[effect.puffs.length];
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
            InstancedParticleEffect.Puff puff = effect.puffs[i];
            TransformedInstance instance = instances[i];

            if (puff.dead()) {
                // Collapse to a zero-size quad rather than churning instance allocation.
                instance.setTransform(scratch.identity().scale(0F));
                instance.setChanged();
                continue;
            }

            float scale = puff.scale(pt);
            scratch.identity()
                    .translate((float) (puff.ix(pt) - origin.getX()),
                            (float) (puff.iy(pt) - origin.getY()),
                            (float) (puff.iz(pt) - origin.getZ()))
                    .rotate(camRotation)
                    .scale(scale)
                    .translate(-0.5F, -0.5F, -0.5F);

            instance.setTransform(scratch);
            instance.colorArgb(puff.argb(pt));
            instance.light(0xF000F0); // self-lit, like vanilla explosion particles
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
