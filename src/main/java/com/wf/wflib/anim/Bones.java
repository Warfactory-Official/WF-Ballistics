package com.wf.wflib.anim;

import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Alternates spin CW/CCW by bearing rank for torque balance; RotorDamage sums degreesPerTick sign on loss. */
public final class Bones {

    private Bones() {
    }

    /**
     * @param rotorSpeed magnitude; sign assigned here by bearing rank, not passed in
     * @param pieceId node name -> id: wreckage piece, or bone to drive
     */
    public static void rig(ResourceLocation modelId, float rotorSpeed, List<String> nodes,
                           List<Vec3> pivots, Function<String, ResourceLocation> pieceId) {
        if (nodes.size() != pivots.size()) {
            throw new IllegalArgumentException(modelId + " declares " + nodes.size() + " rotors but "
                    + pivots.size() + " pivots");
        }

        List<Integer> byBearing = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            byBearing.add(i);
        }
        byBearing.sort(Comparator.comparingDouble(i -> {
            Vec3 p = pivots.get(i);
            return Math.atan2(p.z, p.x);
        }));

        float[] speeds = new float[nodes.size()];
        for (int rank = 0; rank < byBearing.size(); rank++) {
            speeds[byBearing.get(rank)] = (rank % 2 == 0) ? rotorSpeed : -rotorSpeed;
        }

        for (int i = 0; i < nodes.size(); i++) {
            Vec3 pivot = pivots.get(i);
            Rotors.assign(modelId, Rotor.of(pieceId.apply(nodes.get(i)), Axis.Y, speeds[i])
                    .at(nodes.get(i), (float) pivot.x, (float) pivot.y, (float) pivot.z));
        }
    }
}
