package com.wf.wflib;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PosedBound;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.client.model.PartRigs;
import com.wf.wflib.entity.ModelledProjectile;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import com.wf.wflib.round.client.RoundRenderer;
import com.wf.wflib.round.client.RoundRenderers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws a missile, or anything else shaped like one: the airframe pointed where it is going, banked into its turns,
 * with whatever it has that moves.
 */
public class MissileVisual extends AbstractEntityVisual<Projectile> implements DynamicVisual {

    private static final float ORIENTATION_SMOOTHING = 0.3f;
    private static final float ORIENTATION_CATCHUP = 1.5f;
    // Banking: the missile rolls into horizontal turns so mid-flight looks dynamic instead of rigid.
    private static final float BANK_GAIN = 7.0f;       // roll per (rad/tick) of heading yaw change
    private static final float MAX_BANK = 0.6f;        // ~34 degrees of maximum roll
    private static final float BANK_SMOOTHING = 0.12f; // eases the roll toward its per-tick target
    private static final float TURN_RATE_SMOOTHING = 0.2f;
    private static final float BANK_DEADZONE = 0.004f;
    private static final float HEADING_SMOOTHING = 0.3f;

    private static final int SPIN_LAYER = 0;

    /** The bound of the model as it was last posed, in model space, and whether it has been posed at all. */
    private final Vector4f bound = new Vector4f();
    private boolean bounded;

    private final ResourceLocation modelId;
    private GemRenderInstance instance;
    private PartRigs.Rig rig;

    // How this model rotates to its heading (nose-to-velocity missile, level drone, ...): a swappable strategy.
    private final MissileAttitude attitude;
    private final Quaternionf orientation = new Quaternionf();
    /**
     * Per-frame scratch, owned per visual: flywheel updates visuals on several threads at once, so this cannot be
     * shared between them.
     */
    private final Quaternionf targetOrientation = new Quaternionf();
    private final Vector3f headingScratch = new Vector3f();
    private final Matrix4f pose = new Matrix4f();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final GltfAnimation[] layers = new GltfAnimation[1];
    private final float[] times = new float[1];
    /**
     * Light at the missile's block, resampled once a tick rather than once a frame: it is a chunk lookup, and
     * nothing feeding it changes faster than a tick.
     */
    private int packedLight;
    private int lightTick = Integer.MIN_VALUE;
    private double prevX;
    private double prevY;
    private double prevZ;
    private double curX;
    private double curY;
    private double curZ;
    private int lastPosTick = -1;
    private boolean orientationInit = false;
    // Accumulated downed roll (rad) and the frame time it was last advanced to, in ticks.
    private float downedSpin = 0f;
    private float lastFrameTime = Float.NaN;
    private final Vector3f smoothedHeading = new Vector3f(0.0f, 1.0f, 0.0f);
    private boolean headingInit = false;
    private float prevHeadingYaw = Float.NaN;
    private float turnRate = 0f;
    private float targetBank = 0f;
    private float bank = 0f;

    public MissileVisual(VisualizationContext context, Projectile entity) {
        super(context, entity, 0.0f);

        this.modelId = (entity instanceof ModelledProjectile modelled) ? modelled.getModelId()
                : MissileModels.DEFAULT;
        this.attitude = MissileAttitudeRegistry.get(MissileModels.attitudeId(modelId));

        prevX = entity.getX();
        prevY = entity.getY();
        prevZ = entity.getZ();

        this.lastPosTick = entity.tickCount;

        updatePosition(0.0f);
    }

    /** A {@code RoundRenderer} look replaces the airframe ({@code MissileRenderer}). */
    private boolean drawnByRenderer() {
        ResourceLocation look = entity instanceof MissileEntity m ? m.getLook() : null;
        RoundRenderer renderer = look == null ? null : RoundRenderers.get(look);
        return renderer != null && renderer.hasLook();
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
        PartRigs.Rig current = this.drawnByRenderer() ? null : PartRigs.missile(modelId);
        if (current == null) {
            if (instance != null) {
                instance.delete();
                instance = null;
                rig = null;
            }
            return;
        }
        if (rig != current) {
            if (instance != null) {
                instance.delete();
            }
            rig = current;
            instance = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model()
                            .model())
                    .createInstance();
            instance.colorArgb(0xFFFFFFFF);
        }

        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;

            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();

            lastPosTick = entity.tickCount;

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

            double rhx = curX - prevX, rhy = curY - prevY, rhz = curZ - prevZ;
            if (entity instanceof MissileEntity m && m.isDud()) {
                Vector3f rest = m.dudHeading();
                rhx = rest.x;
                rhy = rest.y;
                rhz = rest.z;
                headingInit = false; // snap: a dud does not ease into its resting attitude
                targetBank = 0f;
            } else if (rhx * rhx + rhy * rhy + rhz * rhz < 1.0E-8) {
                Vec3 dm = entity.getDeltaMovement();
                rhx = dm.x;
                rhy = dm.y;
                rhz = dm.z;
            }
            if (rhx * rhx + rhy * rhy + rhz * rhz > 1.0E-8) {
                headingScratch.set((float) rhx, (float) rhy, (float) rhz)
                        .normalize();
                if (headingInit) {
                    smoothedHeading.lerp(headingScratch, HEADING_SMOOTHING)
                            .normalize();
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
                float cos = Math.abs(orientation.dot(target));
                float angle = (float) (2.0 * Math.acos(Math.min(1.0f, cos)));
                float t = Mth.clamp(ORIENTATION_SMOOTHING + angle * ORIENTATION_CATCHUP,
                        ORIENTATION_SMOOTHING, 1.0f);
                orientation.slerp(target, t);
            } else {
                orientation.set(target);
                orientationInit = true;
            }
        }

        // Ease the roll toward its per-tick target every frame so banking looks smooth.
        bank += (targetBank - bank) * BANK_SMOOTHING;
        float frameTime = entity.tickCount + partialTick;
        if (!Float.isNaN(lastFrameTime) && frameTime > lastFrameTime) {
            float roll = entity instanceof MissileEntity m ? m.downedRoll() : 0f;
            downedSpin = (downedSpin + roll * (frameTime - lastFrameTime)) % Mth.TWO_PI;
        }
        lastFrameTime = frameTime;

        Matrix4f matrix = pose.translation(renderX, renderY, renderZ)
                .rotate(orientation)
                .rotateY(bank + downedSpin); // roll about the model's nose/long axis (local +Y)

        if (entity.tickCount != lightTick) {
            lightTick = entity.tickCount;
            lightPos.set(Mth.floor(curX), Mth.floor(curY), Mth.floor(curZ));
            packedLight = LevelRenderer.getLightColor(entity.level(), lightPos);
        }

        layers[SPIN_LAYER] = current.spin() == null ? null : current.spin()
                .clip();
        times[SPIN_LAYER] = current.spin() == null ? 0.0f : current.spin()
                .timeAt(entity.tickCount + partialTick);

        GemRenderGltfModel gltf = current.model();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), layers, times, 0);

        instance.pose.set(matrix);
        instance.boneBase = posed.boneBase();
        instance.morphBase = posed.morphBase();
        instance.boneSphere.set(posed.sphere());
        bound.set(posed.sphere());
        bounded = true;
        instance.light(packedLight);
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        // Clean up the instance when the entity despawns or explodes
        if (this.instance != null) {
            this.instance.delete();
            this.instance = null;
        }
    }

    /** Whether the missile is on screen, tested against the model rather than against its hitbox. */
    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return super.isVisible(frustum) || (bounded && PosedBound.test(frustum, pose, bound));
    }

    @Override
    public Plan<Context> planFrame() {
        return SimplePlan.of(context -> {
            if (!isVisible(context.frustum())) {
                return;
            }
            updatePosition(context.partialTick());
        });
    }
}
