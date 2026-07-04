package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.ModModels;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Flywheel visual for a {@link SkeletonBoneEffect}: one {@link TransformedInstance} per bone, drawn from the
 * shared per-part bone models. Each frame the bone is positioned, spun by its yaw, tumbled around its random
 * axis, and faded — a transform + colour upload, no geometry rebuild.
 */
public class SkeletonBoneVisual extends AbstractVisual implements SimpleDynamicVisual, EffectVisual<SkeletonBoneEffect> {

    private final SkeletonBoneEffect effect;
    private final TransformedInstance[] instances;
    private final Matrix4f scratch = new Matrix4f();

    public SkeletonBoneVisual(VisualizationContext ctx, SkeletonBoneEffect effect, float partialTick) {
        super(ctx, (Level) effect.level(), partialTick);
        this.effect = effect;

        Instancer<TransformedInstance> skull = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.BONE_SKULL));
        Instancer<TransformedInstance> torso = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.BONE_TORSO));
        Instancer<TransformedInstance> limb = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.BONE_LIMB));

        this.instances = new TransformedInstance[effect.bones.size()];
        for (int i = 0; i < instances.length; i++) {
            Instancer<TransformedInstance> instancer = switch (effect.bones.get(i).part) {
                case SKULL -> skull;
                case TORSO -> torso;
                case LIMB -> limb;
            };
            instances[i] = instancer.createInstance();
        }
    }

    @Override
    public void beginFrame(Context context) {
        float pt = context.partialTick();
        Vec3i origin = renderOrigin();
        int grey = (int) (Mth.clamp(effect.brightness, 0F, 1F) * 255F);

        for (int i = 0; i < instances.length; i++) {
            SkeletonBoneEffect.Bone bone = effect.bones.get(i);
            TransformedInstance instance = instances[i];

            if (bone.dead()) {
                instance.setTransform(scratch.identity().scale(0F));
                instance.setChanged();
                continue;
            }

            scratch.identity()
                    .translate((float) (bone.ix(pt) - origin.getX()),
                            (float) (bone.iy(pt) - origin.getY()),
                            (float) (bone.iz(pt) - origin.getZ()))
                    .rotateY((float) Math.toRadians(bone.yaw))
                    .rotate(bone.itumble(pt), bone.tax, bone.tay, bone.taz)
                    .scale(bone.scale);

            instance.setTransform(scratch);
            int alpha = (int) (bone.alpha(pt) * 255F);
            instance.colorArgb((alpha << 24) | (grey << 16) | (grey << 8) | grey);
            instance.light(LevelRenderer.getLightColor(level, BlockPos.containing(bone.x, bone.y, bone.z)));
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
