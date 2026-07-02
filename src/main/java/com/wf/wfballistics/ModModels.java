package com.wf.wfballistics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class ModModels {

    // Flat billboard quad instanced for GPU-batched particle clouds (see client.flywheel).
    public static final PartialModel INSTANCED_QUAD = PartialModel.of(
            new ResourceLocation(WFBallistics.MODID, "effect/instanced_quad")
    );
    // Cremation skeleton bones, instanced per body part (see client.flywheel.SkeletonBoneEffect).
    public static final PartialModel BONE_SKULL = PartialModel.of(
            new ResourceLocation(WFBallistics.MODID, "effect/bone_skull")
    );
    public static final PartialModel BONE_TORSO = PartialModel.of(
            new ResourceLocation(WFBallistics.MODID, "effect/bone_torso")
    );
    public static final PartialModel BONE_LIMB = PartialModel.of(
            new ResourceLocation(WFBallistics.MODID, "effect/bone_limb")
    );
    private static final Map<String, PartialModel> MISSILES = new HashMap<>();
    // Baked models for spinning parts, keyed by the rotor mesh's model location (see MissileModels.Rotor).
    private static final Map<ResourceLocation, PartialModel> ROTORS = new HashMap<>();

    static {
        for (String id : MissileModels.ids()) {
            MISSILES.put(id, PartialModel.of(MissileModels.model(id)));
            for (MissileModels.Rotor rotor : MissileModels.rotors(id)) {
                ROTORS.computeIfAbsent(rotor.model(), PartialModel::of);
            }
        }
    }

    /**
     * @return the baked model for a missile id, falling back to {@link MissileModels#DEFAULT}.
     */
    public static PartialModel missile(String id) {
        return MISSILES.getOrDefault(id, MISSILES.get(MissileModels.DEFAULT));
    }

    /**
     * @return the baked model for a spinning part, or null if it wasn't registered.
     */
    public static PartialModel rotor(ResourceLocation model) {
        return ROTORS.get(model);
    }

    public static void init() {
    }
}
