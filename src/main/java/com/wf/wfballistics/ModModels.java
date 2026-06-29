package com.wf.wfballistics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public class ModModels {
    // Locate the physical .obj asset
    public static final PartialModel MY_OBJ_MODEL = PartialModel.of(
            new ResourceLocation(WFBallistics.MODID, "entity/missile_model")
    );

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
