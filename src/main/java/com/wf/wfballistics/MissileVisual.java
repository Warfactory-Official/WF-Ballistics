package com.wf.wfballistics;

import com.wf.wfballistics.client.anim.RotorInstances;
import com.wf.wfballistics.attitude.MissileAttitude;
import com.wf.wfballistics.attitude.MissileAttitudeRegistry;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public class MissileVisual extends AbstractEntityVisual<Projectile> implements DynamicVisual {

    private static final float ORIENTATION_SMOOTHING = 0.3f;
    // Extra slerp per radian the model currently trails the true heading. The hitbox OBB snaps straight to
    // the heading (no smoothing), so on a hard pivot a fixed slerp leaves the model lagging behind its own
    // box; this pulls the model onto the heading fast when the error is large while keeping cruise smooth.
    private static final float ORIENTATION_CATCHUP = 1.5f;
    // Banking: the missile rolls into horizontal turns so mid-flight looks dynamic instead of rigid.
    private static final float BANK_GAIN = 7.0f;       // roll per (rad/tick) of heading yaw change
    private static final float MAX_BANK = 0.6f;        // ~34 degrees of maximum roll
    private static final float BANK_SMOOTHING = 0.12f; // eases the roll toward its per-tick target
    // The raw per-tick heading yaw is dominated by client/server position-sync noise. A missile's roll is
    // invisible, but on a winged drone that noise becomes a visible wing-rock, so the turn rate driving the
    // roll is low-passed (only a sustained turn banks) and a small noise floor is gated out.
    private static final float TURN_RATE_SMOOTHING = 0.2f;
    private static final float BANK_DEADZONE = 0.004f;
    // Every-tick position sync carries small per-tick control + quantization noise, most visible as the nose
    // bobbing up and down on near-level cruise. The heading feeding the model orientation is low-passed so that
    // jitter is filtered out while a sustained climb/dive still accumulates through it and pitches the nose.
    private static final float HEADING_SMOOTHING = 0.3f;
    private final TransformedInstance modelInstance;
    // Spinning parts (propellers/rotors), spun continuously in updatePosition.
    private final RotorInstances rotors;
    // How this model rotates to its heading (nose-to-velocity missile, level drone, ...): a swappable strategy.
    private final MissileAttitude attitude;
    private final Quaternionf orientation = new Quaternionf();
    /**
     * Per-frame scratch, owned per visual: flywheel updates visuals on several threads at once, so this
     * cannot be shared between them.
     */
    private final Quaternionf targetOrientation = new Quaternionf();
    private final Vector3f headingScratch = new Vector3f();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    /**
     * Light at the missile's block, resampled once a tick rather than once a frame: it is a chunk lookup,
     * and nothing feeding it changes faster than a tick.
     */
    private int packedLight;
    private int lightTick = Integer.MIN_VALUE;
    // Minecraft's oldPos and deltaMovement are both unreliable for this entity, so we track position
    // ourselves to smooth rendering across partial ticks.
    private double prevX;
    private double prevY;
    private double prevZ;
    private double curX;
    private double curY;
    private double curZ;
    private int lastPosTick = -1;
    private boolean orientationInit = false;
    private final Vector3f smoothedHeading = new Vector3f(0.0f, 1.0f, 0.0f);
    private boolean headingInit = false;
    private float prevHeadingYaw = Float.NaN;
    private float turnRate = 0f;
    private float targetBank = 0f;
    private float bank = 0f;

    public MissileVisual(VisualizationContext context, Projectile entity) {
        super(context, entity, 0.0f);

        ResourceLocation modelId = (entity instanceof MissileEntity missile) ? missile.getModelId() : MissileModels.DEFAULT;
        this.attitude = MissileAttitudeRegistry.get(MissileModels.attitudeId(modelId));
        var flywheelModel = Models.partial(ModModels.missile(modelId));

        this.modelInstance = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, flywheelModel)
                .createInstance();

        this.rotors = RotorInstances.create(context, modelId);

        prevX = entity.getX();
        prevY = entity.getY();
        prevZ = entity.getZ();

        this.lastPosTick = entity.tickCount;

        updatePosition(0.0f);

        this.modelInstance.setChanged();
    }

    /**
     * Wraps an angle (radians) into [-PI, PI] so a yaw delta across the +/-PI seam stays small.
     */
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

    private void updatePosition(float partialTick) {
        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;

            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();

            lastPosTick = entity.tickCount;

            // Bank into turns: roll proportional to a low-passed heading turn rate so only a sustained turn
            // banks the airframe, keeping position-sync noise from rocking the wings.
            double thx = curX - prevX, thz = curZ - prevZ;
            if (thx * thx + thz * thz > 1.0E-8) {
                float yaw = (float) Mth.atan2(thx, thz);
                if (!Float.isNaN(prevHeadingYaw)) {
                    float dYaw = wrapRadians(yaw - prevHeadingYaw);
                    turnRate += (dYaw - turnRate) * TURN_RATE_SMOOTHING;
                }
                prevHeadingYaw = yaw;
            } else {
                turnRate -= turnRate * TURN_RATE_SMOOTHING;
            }

            float effectiveTurn = turnRate;
            if (Math.abs(effectiveTurn) <= BANK_DEADZONE) {
                effectiveTurn = 0f;
            } else {
                effectiveTurn -= Math.signum(effectiveTurn) * BANK_DEADZONE;
            }
            targetBank = Mth.clamp(-effectiveTurn * BANK_GAIN, -MAX_BANK, MAX_BANK);

            // Low-pass the heading used for orientation once per tick, so per-tick sync noise doesn't reach the
            // model. Prefer the position delta; fall back to velocity when the missile barely moved this tick.
            double rhx = curX - prevX, rhy = curY - prevY, rhz = curZ - prevZ;
            if (rhx * rhx + rhy * rhy + rhz * rhz < 1.0E-8) {
                Vec3 dm = entity.getDeltaMovement();
                rhx = dm.x;
                rhy = dm.y;
                rhz = dm.z;
            }
            if (rhx * rhx + rhy * rhy + rhz * rhz > 1.0E-8) {
                headingScratch.set((float) rhx, (float) rhy, (float) rhz).normalize();
                if (headingInit) {
                    smoothedHeading.lerp(headingScratch, HEADING_SMOOTHING).normalize();
                } else {
                    smoothedHeading.set(headingScratch);
                    headingInit = true;
                }
            }
        }

        Vec3i origin = renderOrigin();
        float renderX = (float) (Mth.lerp(partialTick, prevX, curX) - origin.getX());
        float renderY = (float) (Mth.lerp(partialTick, prevY, curY) - origin.getY());
        float renderZ = (float) (Mth.lerp(partialTick, prevZ, curZ) - origin.getZ());


        if (headingInit) {
            // Passed straight in: MissileAttitude is contracted not to modify the heading it is given.
            Quaternionf target = attitude.orientation(smoothedHeading, targetOrientation);
            if (orientationInit) {
                // Scale the catch-up by how far the model currently is from the true heading: gentle cruise
                // turns stay smooth, but a hard dive/turn snaps on so the model doesn't trail its hitbox.
                float cos = Math.abs(orientation.dot(target));
                float angle = (float) (2.0 * Math.acos(Math.min(1.0f, cos)));
                float t = Mth.clamp(ORIENTATION_SMOOTHING + angle * ORIENTATION_CATCHUP, ORIENTATION_SMOOTHING, 1.0f);
                orientation.slerp(target, t);
            } else {
                orientation.set(target);
                orientationInit = true;
            }
        }

        // Ease the roll toward its per-tick target every frame so banking looks smooth.
        bank += (targetBank - bank) * BANK_SMOOTHING;

        // Built straight into the instance's own matrix; the rotors then read it as their parent transform.
        Matrix4f matrix = this.modelInstance.pose;
        matrix.translation(renderX, renderY, renderZ)
                .rotate(orientation)
                .rotateY(bank); // roll about the model's nose/long axis (local +Y)

        if (entity.tickCount != lightTick) {
            lightTick = entity.tickCount;
            lightPos.set(Mth.floor(curX), Mth.floor(curY), Mth.floor(curZ));
            packedLight = LevelRenderer.getLightColor(entity.level(), lightPos);
        }

        this.modelInstance.light(packedLight);
        this.modelInstance.setChanged();

        this.rotors.update(matrix, entity.tickCount + partialTick, packedLight);
    }

    @Override
    protected void _delete() {
        // Clean up the instance when the entity despawns or explodes
        if (this.modelInstance != null) {
            this.modelInstance.delete();
        }
        this.rotors.delete();
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