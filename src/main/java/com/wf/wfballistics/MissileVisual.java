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
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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

    private static final float ORIENTATION_SMOOTHING = 0.3f;
    private final Quaternionf orientation = new Quaternionf();
    private boolean orientationInit = false;

    // Banking: the missile rolls into horizontal turns so mid-flight looks dynamic instead of rigid.
    private static final float BANK_GAIN = 7.0f;       // roll per (rad/tick) of heading yaw change
    private static final float MAX_BANK = 0.6f;        // ~34 degrees of maximum roll
    private static final float BANK_SMOOTHING = 0.12f; // eases the roll toward its per-tick target
    private float prevHeadingYaw = Float.NaN;
    private float targetBank = 0f;
    private float bank = 0f;

    public MissileVisual(VisualizationContext context, Projectile entity) {
        super(context, entity, 0.0f);

        String modelId = (entity instanceof MissileEntity missile) ? missile.getModelId() : MissileModels.DEFAULT;
        var flywheelModel = Models.partial(ModModels.missile(modelId));

        //var testingState = Blocks.WHITE_CONCRETE.defaultBlockState();
        //var testingModel = dev.engine_room.flywheel.lib.model.Models.block(testingState);

        this.modelInstance = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, flywheelModel)
                .createInstance();

        prevX = entity.getX();
        prevY = entity.getY();
        prevZ = entity.getZ();

        this.lastPosTick = entity.tickCount;

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

            // Bank into turns: measure how far the heading yawed this tick and roll proportionally.
            double thx = curX - prevX, thz = curZ - prevZ;
            if (thx * thx + thz * thz > 1.0E-8) {
                float yaw = (float) Mth.atan2(thx, thz);
                if (!Float.isNaN(prevHeadingYaw)) {
                    float dYaw = wrapRadians(yaw - prevHeadingYaw);
                    targetBank = Mth.clamp(-dYaw * BANK_GAIN, -MAX_BANK, MAX_BANK);
                }
                prevHeadingYaw = yaw;
            }
        }

        Vec3i origin = renderOrigin();
        float renderX = (float) (Mth.lerp(partialTick, prevX, curX) - origin.getX());
        float renderY = (float) (Mth.lerp(partialTick, prevY, curY) - origin.getY());
        float renderZ = (float) (Mth.lerp(partialTick, prevZ, curZ) - origin.getZ());


        double hx = curX - prevX, hy = curY - prevY, hz = curZ - prevZ;
        if (hx * hx + hy * hy + hz * hz < 1.0E-8) {
            hx = entity.getDeltaMovement().x;
            hy = entity.getDeltaMovement().y;
            hz = entity.getDeltaMovement().z;
        }

        if (hx * hx + hy * hy + hz * hz > 1.0E-8) {
            Vector3f heading = new Vector3f((float) hx, (float) hy, (float) hz).normalize();
            Quaternionf target = new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f), heading);
            if (orientationInit) {
                orientation.slerp(target, ORIENTATION_SMOOTHING);
            } else {
                orientation.set(target);
                orientationInit = true;
            }
        }

        // Ease the roll toward its per-tick target every frame so banking looks smooth.
        bank += (targetBank - bank) * BANK_SMOOTHING;

        Matrix4f matrix = new Matrix4f()
                .translate(renderX, renderY, renderZ)
                .rotate(orientation)
                .rotateY(bank); // roll about the model's nose/long axis (local +Y)

        BlockPos entityPos = BlockPos.containing(curX, curY, curZ);
        int packedLight = LevelRenderer.getLightColor(entity.level(), entityPos);

        this.modelInstance.light(packedLight);
        this.modelInstance.setTransform(matrix);
        this.modelInstance.setChanged();
    }

    /** Wraps an angle (radians) into [-PI, PI] so a yaw delta across the +/-PI seam stays small. */
    private static float wrapRadians(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        angle %= twoPi;
        if (angle >= (float) Math.PI) {
            angle -= twoPi;
        } else if (angle < (float) -Math.PI) {
            angle += twoPi;
        }
        return angle;
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
        return new Plan<>() {
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