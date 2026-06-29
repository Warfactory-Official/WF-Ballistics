package com.wf.wfballistics;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.resources.ResourceLocation;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.Models;

public class ModModels {
    // Locate the physical .obj asset
    public static final PartialModel MY_OBJ_MODEL = PartialModel.of(
            new ResourceLocation("wfballistics", "entity/missile_model")
    );


    public static void init() {
    }
}