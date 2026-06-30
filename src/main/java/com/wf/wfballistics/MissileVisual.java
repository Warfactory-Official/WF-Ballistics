package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.slf4j.Logger;

public class MissileVisual extends AbstractEntityVisual<Projectile> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TransformedInstance modelInstance;

    public MissileVisual(VisualizationContext context, Projectile entity) {
        super(context, entity, 0.0f);

        var flywheelModel = Models.partial(ModModels.MY_OBJ_MODEL);

        //var testingState = Blocks.WHITE_CONCRETE.defaultBlockState();
        //var testingModel = dev.engine_room.flywheel.lib.model.Models.block(testingState);

        this.modelInstance = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, flywheelModel)
                .createInstance();

        updatePosition();

        this.modelInstance.setChanged();
    }

    private void updatePosition() {
        // Build a simple translation matrix to place the model at the entity's exact coordinates
        Matrix4f matrix = new Matrix4f().translate(
                (float) entity.getX(),
                (float) entity.getY(),
                (float) entity.getZ()
        );

        BlockPos entityPos = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        int packedLight = LevelRenderer.getLightColor(entity.level(), entityPos);

        this.modelInstance.light(packedLight);
        this.modelInstance.setTransform(matrix);
        this.modelInstance.setChanged(); // Tell Flywheel to upload the new position to the GPU
    }

    @Override
    protected void _delete() {
        // Clean up the instance when the entity despawns or explodes
        if (this.modelInstance != null) {
            this.modelInstance.delete();
        }
    }
}