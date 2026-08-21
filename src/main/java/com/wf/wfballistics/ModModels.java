package com.wf.wfballistics;

import com.wf.wfballistics.anim.Rotor;
import com.wf.wfballistics.anim.Rotors;
import com.wf.wfballistics.drone.DroneModels;
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
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "effect/instanced_quad")
    );
    // Cremation skeleton bones, instanced per body part (see client.flywheel.SkeletonBoneEffect).
    public static final PartialModel BONE_SKULL = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "effect/bone_skull")
    );
    public static final PartialModel BONE_TORSO = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "effect/bone_torso")
    );
    public static final PartialModel BONE_LIMB = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "effect/bone_limb")
    );
    // The cargo crate, baked so a drone can draw the one it is carrying as part of its own instance rather
    // than trailing a second entity behind it (see client.render.DroneVisual).
    public static final PartialModel CRATE = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "entity/crate")
    );
    private static final Map<ResourceLocation, PartialModel> MISSILES = new HashMap<>();
    private static final Map<ResourceLocation, PartialModel> DRONES = new HashMap<>();
    private static final Map<ResourceLocation, PartialModel> PARTS = new HashMap<>();

    static {
        for (ResourceLocation id : MissileModels.ids()) {
            MISSILES.put(id, PartialModel.of(MissileModels.model(id)));
            // A missile's only moving parts are its props, so its rotor list is its part list.
            for (Rotor rotor : Rotors.of(id)) {
                bakePart(rotor.model());
            }
        }
        for (ResourceLocation id : DroneModels.ids()) {
            DRONES.put(id, PartialModel.of(DroneModels.model(id)));
            // Drones declare their parts up front, rotors and jaws together, so bake the lot.
            for (ResourceLocation part : DroneModels.parts(id)) {
                bakePart(part);
            }
        }
    }

    private static void bakePart(ResourceLocation model) {
        PARTS.computeIfAbsent(model, PartialModel::of);
    }

    /**
     * @return the baked model for a missile id, falling back to {@link MissileModels#DEFAULT} when the id is
     * unknown or its own model failed to bake (missing/unbaked).
     */
    public static PartialModel missile(ResourceLocation id) {
        PartialModel partial = MISSILES.get(id);
        return usableBaked(partial) != null ? partial : MISSILES.get(MissileModels.DEFAULT);
    }

    public static RenderModel render(ResourceLocation id) {
        BakedModel baked = usableBaked(MISSILES.get(id));
        ResourceLocation key = id;
        if (baked == null) {
            key = MissileModels.DEFAULT;
            baked = usableBaked(MISSILES.get(MissileModels.DEFAULT));
        }
        return new RenderModel(baked, Math.max(1.0, MissileModels.length(key)), MissileModels.center(key));
    }

    /**
     * @return the baked airframe for a drone id, falling back to {@link DroneModels#DEFAULT}.
     */
    public static PartialModel drone(ResourceLocation id) {
        PartialModel partial = DRONES.get(id);
        return usableBaked(partial) != null ? partial : DRONES.get(DroneModels.DEFAULT);
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
     * @return the baked model for a moving part (rotor disc, gripper jaw) or null if it wasn't registered.
     */
    public static PartialModel part(ResourceLocation model) {
        return PARTS.get(model);
    }

    public static void init() {
    }
}
