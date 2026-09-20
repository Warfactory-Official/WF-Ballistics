package com.wf.wflib;

import com.wf.wflib.anim.Rotor;
import com.wf.wflib.anim.Rotors;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModModels {

    private static final Map<ResourceLocation, PartialModel> PROPS = new LinkedHashMap<>();

    // Flat billboard quad instanced for GPU-batched particle clouds (see client.flywheel).
    public static final PartialModel INSTANCED_QUAD = prop("effect/instanced_quad");
    // Cremation skeleton bones, instanced per body part (see client.flywheel.SkeletonBoneEffect).
    public static final PartialModel BONE_SKULL = prop("effect/bone_skull");
    public static final PartialModel BONE_TORSO = prop("effect/bone_torso");
    public static final PartialModel BONE_LIMB = prop("effect/bone_limb");
    public static final PartialModel CRATE = prop("entity/crate");
    public static final PartialModel BOMBLET = prop("entity/bomblet");
    private static final Map<ResourceLocation, PartialModel> MISSILES = new HashMap<>();
    private static final Map<ResourceLocation, PartialModel> PARTS = new LinkedHashMap<>();

    static {
        for (ResourceLocation id : MissileModels.ids()) {
            MISSILES.put(id, PartialModel.of(MissileModels.model(id)));
            // A missile's only moving parts are its props, so its rotor list is its part list.
            for (Rotor rotor : Rotors.of(id)) {
                bakePart(rotor.model());
            }
        }
    }

    private static void bakePart(ResourceLocation model) {
        PARTS.computeIfAbsent(model, PartialModel::of);
    }

    /** Declares a standalone model and remembers it, so the model gallery can enumerate what is here. */
    private static PartialModel prop(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
        PartialModel partial = PartialModel.of(id);
        PROPS.put(id, partial);
        return partial;
    }

    /**
     * @return the loose models above (the billboards, the bones, the crate, the bomblet), by model id,
     *      in the order they are declared. For the model gallery; nothing in the game looks a prop up by id.
     */
    public static Map<ResourceLocation, PartialModel> props() {
        return Collections.unmodifiableMap(PROPS);
    }

    /** @return every moving part baked for a missile, by model id, in registration order. */
    public static Map<ResourceLocation, PartialModel> parts() {
        return Collections.unmodifiableMap(PARTS);
    }

    /**
     * @return whether this missile's <em>own</em> model baked.
     */
    public static boolean baked(ResourceLocation id) {
        return usableBaked(MISSILES.get(id)) != null;
    }

    /**
     * @return the baked model for a missile id, falling back to {@link MissileModels#DEFAULT} when the id is
     *      unknown or its own model failed to bake (missing/unbaked).
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
