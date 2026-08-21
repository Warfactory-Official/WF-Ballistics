package com.wf.wfballistics.client.anim;

import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.anim.Bones;
import com.wf.wfballistics.anim.Jaw;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the gripper framework: the jaws a model declared through {@link Bones}, drawn as one
 * flywheel instance each and swung about their own hinges on top of the body transform. The
 * {@link RotorInstances} counterpart for parts that open rather than turn.
 */
public final class JawInstances {

    private static final JawInstances EMPTY = new JawInstances(new TransformedInstance[0], new Jaw[0]);

    private final TransformedInstance[] instances;
    private final Jaw[] specs;

    private JawInstances(TransformedInstance[] instances, Jaw[] specs) {
        this.instances = instances;
        this.specs = specs;
    }

    /**
     * @return instances for every jaw assigned to {@code modelId}, skipping any whose mesh failed to bake.
     */
    public static JawInstances create(VisualizationContext context, ResourceLocation modelId) {
        List<Jaw> jaws = Bones.jaws(modelId);
        if (jaws.isEmpty()) {
            return EMPTY;
        }
        List<TransformedInstance> instances = new ArrayList<>(jaws.size());
        List<Jaw> specs = new ArrayList<>(jaws.size());
        for (Jaw jaw : jaws) {
            var model = ModModels.part(jaw.model());
            if (model == null) {
                continue;
            }
            instances.add(context.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
                    .createInstance());
            specs.add(jaw);
        }
        return new JawInstances(instances.toArray(new TransformedInstance[0]), specs.toArray(new Jaw[0]));
    }

    /**
     * Re-pose every jaw for this frame.
     *
     * @param body the airframe's world transform this frame
     * @param open 0 for shut around the payload, 1 for fully open
     */
    public void update(Matrix4f body, float open, int packedLight) {
        for (int i = 0; i < instances.length; i++) {
            Jaw jaw = specs[i];
            var hinge = jaw.hinge();
            var axis = jaw.axis();
            TransformedInstance instance = instances[i];
            // Composed into the instance's own matrix: see RotorInstances#update.
            instance.pose.set(body)
                    .translate(hinge.x, hinge.y, hinge.z)
                    .rotate(jaw.angle(open), axis.x, axis.y, axis.z)
                    .translate(-hinge.x, -hinge.y, -hinge.z);
            instance.light(packedLight);
            instance.setChanged();
        }
    }

    public void delete() {
        for (TransformedInstance instance : instances) {
            instance.delete();
        }
    }
}
