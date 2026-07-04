package com.wf.wfballistics.client.flywheel;

import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The Flywheel visual for an {@link InstancedTrailEffect}: one billboarded {@link TransformedInstance} per
 * ring-buffer slot, sharing the translucent {@link FlywheelModels#particleQuad()} model. Inactive slots collapse to zero
 * size, so the instance buffer is allocated once and only its contents change frame to frame.
 */
public class InstancedTrailVisual extends AbstractVisual implements SimpleDynamicVisual, EffectVisual<InstancedTrailEffect> {

    private final InstancedTrailEffect effect;
    private final TransformedInstance[] instances;
    private final Matrix4f scratch = new Matrix4f();
    private final Matrix4f base = new Matrix4f();
    private final BillboardLod lod = new BillboardLod();
    // Whether each slot is currently collapsed to a zero-size quad, so we only re-upload the collapse once.
    private final boolean[] collapsed;

    public InstancedTrailVisual(VisualizationContext ctx, InstancedTrailEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.effect = effect;

        Model model = FlywheelModels.particleQuad();
        Instancer<TransformedInstance> instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model);

        this.instances = new TransformedInstance[effect.pool.length];
        for (int i = 0; i < instances.length; i++) {
            instances[i] = instancer.createInstance();
        }
        this.collapsed = new boolean[instances.length];
    }

    @Override
    public void beginFrame(Context context) {
        Vec3 camPos = context.camera().getPosition();
        double dx = effect.cx - camPos.x;
        double dy = effect.cy - camPos.y;
        double dz = effect.cz - camPos.z;
        if (!lod.update(context.camera().rotation(), dx * dx + dy * dy + dz * dz)) {
            return; // distant/settled trail: nothing changed enough to re-upload this frame
        }

        float pt = lod.interpolate ? context.partialTick() : 0F;
        int stride = lod.stride;
        Quaternionf camRotation = context.camera().rotation();
        Vec3i origin = renderOrigin();

        // Puffs from one emission section share age, life and scale, so their billboard matrix (rotation +
        // scale about the centre) is identical and only the translation differs. Rebuild that base matrix only
        // when the scale actually changes and reuse it across the run — this skips the per-puff camera rotation
        // for the bulk of a trail.
        float baseScale = Float.NaN;

        for (int i = 0; i < instances.length; i++) {
            InstancedTrailEffect.Flame flame = effect.pool[i];
            TransformedInstance instance = instances[i];

            // Inactive slots, and (on distant trails) the strided-out neighbours a drawn puff stands in for,
            // collapse to a zero-size quad. Only re-upload that collapse once.
            if (!flame.active || (i % stride) != 0) {
                if (!collapsed[i]) {
                    instance.setTransform(scratch.identity().scale(0F));
                    instance.setChanged();
                    collapsed[i] = true;
                }
                continue;
            }
            collapsed[i] = false;

            float scale = flame.scale(pt);
            if (scale != baseScale) {
                base.identity().rotate(camRotation).scale(scale).translate(-0.5F, -0.5F, -0.5F);
                baseScale = scale;
            }
            scratch.set(base).translateLocal(
                    (float) (flame.ix(pt) - origin.getX()),
                    (float) (flame.iy(pt) - origin.getY()),
                    (float) (flame.iz(pt) - origin.getZ()));

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
