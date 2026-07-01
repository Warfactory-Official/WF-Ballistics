package com.wf.wfballistics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class ModModels {

    private static final Map<String, PartialModel> MISSILES = new HashMap<>();
    static {
        for (String id : MissileModels.ids()) {
            MISSILES.put(id, PartialModel.of(MissileModels.model(id)));
        }
    }

    /** @return the baked model for a missile id, falling back to {@link MissileModels#DEFAULT}. */
    public static PartialModel missile(String id) {
        return MISSILES.getOrDefault(id, MISSILES.get(MissileModels.DEFAULT));
    }

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

    public static void init() {
    }
}
