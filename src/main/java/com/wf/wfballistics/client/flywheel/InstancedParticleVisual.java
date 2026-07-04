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
 * The Flywheel visual for an {@link InstancedParticleEffect}: one {@link TransformedInstance} per puff,
 * sharing a single translucent instanced billboard-quad ({@link FlywheelModels#particleQuad()}).
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
    private final Matrix4f base = new Matrix4f();
    private final BillboardLod lod = new BillboardLod();
    // Whether each slot is currently collapsed to a zero-size quad, so we only re-upload the collapse once.
    private final boolean[] collapsed;

    public InstancedParticleVisual(VisualizationContext ctx, InstancedParticleEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.effect = effect;

        Model model = FlywheelModels.particleQuad();
        Instancer<TransformedInstance> instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model);

        this.instances = new TransformedInstance[effect.puffs.length];
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
            return; // distant/settled cloud: nothing changed enough to re-upload this frame
        }

        float pt = lod.interpolate ? context.partialTick() : 0F;
        int stride = lod.stride;
        Quaternionf camRotation = context.camera().rotation();
        Vec3i origin = renderOrigin();

        // Reuse the rotation+scale billboard matrix across consecutive puffs of equal scale; only translation
        // differs between them (see InstancedTrailVisual for the same trick).
        float baseScale = Float.NaN;

        for (int i = 0; i < instances.length; i++) {
            InstancedParticleEffect.Puff puff = effect.puffs[i];
            TransformedInstance instance = instances[i];

            // Dead puffs, and (on distant clouds) strided-out ones, collapse to a zero-size quad. Only
            // re-upload that collapse once rather than every frame.
            if (puff.dead() || (i % stride) != 0) {
                if (!collapsed[i]) {
                    instance.setTransform(scratch.identity().scale(0F));
                    instance.setChanged();
                    collapsed[i] = true;
                }
                continue;
            }
            collapsed[i] = false;

            float scale = puff.scale(pt);
            if (scale != baseScale) {
                base.identity().rotate(camRotation).scale(scale).translate(-0.5F, -0.5F, -0.5F);
                baseScale = scale;
            }
            scratch.set(base).translateLocal(
                    (float) (puff.ix(pt) - origin.getX()),
                    (float) (puff.iy(pt) - origin.getY()),
                    (float) (puff.iz(pt) - origin.getZ()));

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
