package com.wf.wfballistics.client.flywheel;

import com.wf.wfballistics.ModModels;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;

public final class FlywheelModels {


    private static final Material PARTICLE = SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .diffuse(false)
            .mipmap(false)
            .build();

    private static final RendererReloadCache<PartialModel, Model> PARTICLE_MODEL =
            new RendererReloadCache<>(partial -> BakedModelBuilder.create(partial.get())
                    .materialFunc((renderType, shaded) -> PARTICLE)
                    .build());

    private FlywheelModels() {
    }

    public static Model particleQuad() {
        return PARTICLE_MODEL.get(ModModels.INSTANCED_QUAD);
    }
}
