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

public class InstancedFlameVisual extends AbstractVisual implements SimpleDynamicVisual, EffectVisual<InstancedFlameEffect> {

    private final InstancedFlameEffect effect;
    private final TransformedInstance[] instances;
    private final Matrix4f scratch = new Matrix4f();
    private final Matrix4f base = new Matrix4f();
    private final BillboardLod lod = new BillboardLod();
    private final boolean[] collapsed;

    public InstancedFlameVisual(VisualizationContext ctx, InstancedFlameEffect effect, float partialTick) {
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
            return;
        }

        float pt = lod.interpolate ? context.partialTick() : 0F;
        int stride = lod.stride;
        Quaternionf camRotation = context.camera().rotation();
        Vec3i origin = renderOrigin();

        float baseScale = Float.NaN;

        for (int i = 0; i < instances.length; i++) {
            InstancedFlameEffect.Flame flame = effect.pool[i];
            TransformedInstance instance = instances[i];

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
