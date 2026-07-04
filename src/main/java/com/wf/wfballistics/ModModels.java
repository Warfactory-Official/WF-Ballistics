package com.wf.wfballistics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

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
     * @return the baked model for a missile id, falling back to {@link MissileModels#DEFAULT} when the id is
     * unknown or its own model failed to bake (missing/unbaked).
     */
    public static PartialModel missile(String id) {
        PartialModel partial = MISSILES.get(id);
        return usableBaked(partial) != null ? partial : MISSILES.get(MissileModels.DEFAULT);
    }

    public static RenderModel render(String id) {
        BakedModel baked = usableBaked(MISSILES.get(id));
        String key = id;
        if (baked == null) {
            key = MissileModels.DEFAULT;
            baked = usableBaked(MISSILES.get(MissileModels.DEFAULT));
        }
        return new RenderModel(baked, Math.max(1.0, MissileModels.length(key)), MissileModels.center(key));
    }

    private static BakedModel usableBaked(PartialModel partial) {
        if (partial == null) {
            return null;
        }
        BakedModel baked = partial.get();
        return baked == null || baked == Minecraft.getInstance().getModelManager().getMissingModel()
                ? null : baked;
    }

    public record RenderModel(BakedModel baked, double length, Vec3 center) {
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
