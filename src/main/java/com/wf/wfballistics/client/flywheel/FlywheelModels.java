package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.ModModels;
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
            .transparency(Transparency.ORDER_INDEPENDENT)
            .cutout(CutoutShaders.EPSILON)
            .diffuse(false)
            .mipmap(false)
            .build();

    private static final RendererReloadCache<PartialModel, Model> PARTICLE_MODEL =
            new RendererReloadCache<>(partial -> BakedModelBuilder.create(partial.get())
                    .materialFunc((renderType, shaded) -> PARTICLE)
                    .build());

    private static final Material TRANSLUCENT = SimpleMaterial.builder()
            .transparency(Transparency.ORDER_INDEPENDENT)
            .cutout(CutoutShaders.EPSILON)
            .build();

    private static final RendererReloadCache<PartialModel, Model> TRANSLUCENT_MODEL =
            new RendererReloadCache<>(partial -> BakedModelBuilder.create(partial.get())
                    .materialFunc((renderType, shaded) -> TRANSLUCENT)
                    .build());

    private FlywheelModels() {
    }

    public static Model particleQuad() {
        return PARTICLE_MODEL.get(ModModels.INSTANCED_QUAD);
    }

    public static Model translucent(PartialModel partial) {
        return TRANSLUCENT_MODEL.get(partial);
    }
}
