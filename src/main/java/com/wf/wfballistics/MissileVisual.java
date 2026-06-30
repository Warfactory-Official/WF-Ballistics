package com.wf.wfballistics;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.slf4j.Logger;

public class MissileVisual extends AbstractEntityVisual<Projectile> implements DynamicVisual {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Because minecraft's oldPos values and deltaMovement values are both inaccurate for some fucking reason,
    // we use our own position tracking for rendering smoothing on partial ticks
    private double prevX;
    private double prevY;
    private double prevZ;

    private double curX;
    private double curY;
    private double curZ;

    private int lastPosTick = -1;

    private final TransformedInstance modelInstance;

    public MissileVisual(VisualizationContext context, Projectile entity) {
        super(context, entity, 0.0f);

        var flywheelModel = Models.partial(ModModels.MY_OBJ_MODEL);

        //var testingState = Blocks.WHITE_CONCRETE.defaultBlockState();
        //var testingModel = dev.engine_room.flywheel.lib.model.Models.block(testingState);

        this.modelInstance = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, flywheelModel)
                .createInstance();

        prevX = entity.getX();
        prevY = entity.getX();
        prevZ = entity.getX();

        updatePosition(0.0f);

        this.modelInstance.setChanged();
    }

    private void updatePosition(float partialTick) {
        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;

            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();

            lastPosTick = entity.tickCount;
        }

        float renderX = (float) Mth.lerp(partialTick, prevX, curX);
        float renderY = (float) Mth.lerp(partialTick, prevY, curY);
        float renderZ = (float) Mth.lerp(partialTick, prevZ, curZ);

        Matrix4f matrix = new Matrix4f().translate(renderX, renderY, renderZ);

        BlockPos entityPos = BlockPos.containing(curX, curY, curZ);
        int packedLight = LevelRenderer.getLightColor(entity.level(), entityPos);

        this.modelInstance.light(packedLight);
        this.modelInstance.setTransform(matrix);
        this.modelInstance.setChanged();
    }

    @Override
    protected void _delete() {
        // Clean up the instance when the entity despawns or explodes
        if (this.modelInstance != null) {
            this.modelInstance.delete();
        }
    }

    @Override
    public Plan<Context> planFrame() {
        return new Plan<Context>() {
            @Override
            public void execute(TaskExecutor taskExecutor, Context context, Runnable onCompletion) {
                updatePosition(context.partialTick());
                onCompletion.run();
            }

            @Override
            public Plan<Context> then(Plan<Context> plan) {
                return null;
            }

            @Override
            public Plan<Context> and(Plan<Context> plan) {
                return null;
            }
        };
    }
}