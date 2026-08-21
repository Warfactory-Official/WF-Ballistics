package com.wf.wfballistics.client.anim;

import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.anim.Rotor;
import com.wf.wfballistics.anim.Rotors;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the rotor framework: the spinning parts a model declared through {@link Rotors}, drawn as
 * one flywheel instance each and spun about their own pivots on top of the body transform. Shared by every
 * visual that has rotors (missile pusher props, quadcopter discs) so there is no per-model spin code.
 */
public final class RotorInstances {

    private static final RotorInstances EMPTY = new RotorInstances(new TransformedInstance[0], new Rotor[0],
            new Vector3f[0]);

    private final TransformedInstance[] instances;
    private final Rotor[] specs;
    private final Vector3f[] pivots;

    private RotorInstances(TransformedInstance[] instances, Rotor[] specs, Vector3f[] pivots) {
        this.instances = instances;
        this.specs = specs;
        this.pivots = pivots;
    }

    /**
     * @return instances for every rotor assigned to {@code modelId}, skipping any whose mesh failed to bake.
     */
    public static RotorInstances create(VisualizationContext context, ResourceLocation modelId) {
        List<Rotor> rotors = Rotors.of(modelId);
        if (rotors.isEmpty()) {
            return EMPTY;
        }
        List<TransformedInstance> instances = new ArrayList<>(rotors.size());
        List<Rotor> specs = new ArrayList<>(rotors.size());
        List<Vector3f> pivots = new ArrayList<>(rotors.size());
        for (Rotor rotor : rotors) {
            var model = ModModels.part(rotor.model());
            if (model == null) {
                continue;
            }
            instances.add(context.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
                    .createInstance());
            specs.add(rotor);
            pivots.add(Rotors.pivot(rotor));
        }
        return new RotorInstances(instances.toArray(new TransformedInstance[0]), specs.toArray(new Rotor[0]),
                pivots.toArray(new Vector3f[0]));
    }

    /**
     * Re-pose every rotor for this frame: the body transform with an extra spin about the rotor's own pivot.
     *
     * @param body  the airframe's world transform this frame
     * @param ticks continuous time (tickCount + partialTick) driving the spin angle
     */
    public void update(Matrix4f body, float ticks, int packedLight) {
        for (int i = 0; i < instances.length; i++) {
            Rotor rotor = specs[i];
            Vector3f pivot = pivots[i];
            Vector3f axis = rotor.axis();
            TransformedInstance instance = instances[i];
            // Composed straight into the instance's own matrix. Building a Matrix4f here and handing it to
            // setTransform would allocate one per disc per frame only to copy it into this same field.
            instance.pose.set(body)
                    .translate(pivot.x, pivot.y, pivot.z)
                    .rotate(rotor.angle(ticks), axis.x, axis.y, axis.z)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
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
