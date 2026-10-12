package com.wf.wflib.client.flywheel;

import com.wf.wflib.ModModels;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;

public final class FlywheelModels {


    private static final Material PARTICLE = SimpleMaterial.builder()
            .transparency(Transparency.ADDITIVE)
            .cutout(CutoutShaders.EPSILON)
            .diffuse(false)
            .mipmap(false)
            .build();

    private static final RendererReloadCache<PartialModel, Model> PARTICLE_MODEL =
            new RendererReloadCache<>(partial -> new BakedModelBuilder(partial.get())
                    .materialFunc((renderType, shaded) -> PARTICLE)
                    .build());

    /** Opaque: ORDER_INDEPENDENT blends alpha-1 texels too => parts show through each other, no depth. */
    private static final Material CUTOUT = SimpleMaterial.builder()
            .cutout(CutoutShaders.ONE_TENTH)
            .build();

    private static final RendererReloadCache<PartialModel, Model> CUTOUT_MODEL =
            new RendererReloadCache<>(partial -> new BakedModelBuilder(partial.get())
                    .materialFunc((renderType, shaded) -> CUTOUT)
                    .build());

    private FlywheelModels() {
    }

    public static Model particleQuad() {
        return PARTICLE_MODEL.get(ModModels.INSTANCED_QUAD);
    }

    public static Model cutout(PartialModel partial) {
        return CUTOUT_MODEL.get(partial);
    }
}
