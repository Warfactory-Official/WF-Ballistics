package com.wf.wflib.client.render;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PosedBound;
import com.wf.wflib.ModModels;
import com.wf.wflib.attitude.MissileAttitude;
import com.wf.wflib.attitude.MissileAttitudeRegistry;
import com.wf.wflib.client.model.MineRigs;
import com.wf.wflib.client.model.PartRigs;
import com.wf.wflib.drone.CrateEntity;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneModels;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.flight.Airframe;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.mine.MineModels;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class DroneVisual extends AbstractEntityVisual<DroneEntity> implements DynamicVisual {

    private static final float ATTITUDE_SMOOTHING = 0.35f;
    private static final float SPOOL_RATE = 0.12f;
    private static final float CRATE_HALF = CrateEntity.SIZE / 2.0f;
    /** Matches {@code BombletRenderer.HALF}; centres the hang under the mount instead of by its top. */
    private static final float BOMB_DROP = 0.15f;

    private static final int MINES_DRAWN = 4;
    private static final int MINES_ABREAST = 2;
    private static final float MINE_PITCH = 1.15f;

    private static final int SPIN_LAYER = 0;
    private static final int STOW_LAYER = 1;

    /** Last-posed bound, model space; valid only once {@code bounded}. */
    private final Vector4f bound = new Vector4f();
    private boolean bounded;

    private final ResourceLocation modelId;
    private GemRenderInstance body;
    private PartRigs.Rig rig;

    private final TransformedInstance crate;
    private final TransformedInstance bomb;
    private final GemRenderInstance[] mines = new GemRenderInstance[MINES_DRAWN];
    private GemRenderGltfModel mineModel;
    private Vec3 mineSize = new Vec3(1.0, 1.0, 1.0);
    private final Matrix4f slung = new Matrix4f();
    private final Vector3f mount;
    private final MissileAttitude attitude;
    private final Airframe airframe;
    // Reused per frame; avoids per-frame allocation.
    private final Vector3f heading = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final Quaternionf lean = new Quaternionf();
    private final Matrix4f pose = new Matrix4f();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final GltfAnimation[] layers = new GltfAnimation[2];
    private final float[] times = new float[2];

    private double prevX, prevY, prevZ;
    private double curX, curY, curZ;
    private int lastPosTick = -1;
    private float prevYaw;
    private float curYaw;
    private float roll;
    private float pitch;
    /** Rotor phase; ticks of turning. */
    private float rotorPhase;
    private float rotorScale;
    private float lastPartialTick;
    /** Refreshed once per tick (see {@link #sampleTick}), not per frame. */
    private int sampledTick = Integer.MIN_VALUE;
    private int packedLight;
    private float targetRoll;
    private float targetPitch;
    private float targetRotorScale;
    private boolean carryingCrate;
    private boolean carryingBomb;
    private int mineCount;
    @org.jetbrains.annotations.Nullable
    private ResourceLocation mineModelId;

    public DroneVisual(VisualizationContext context, DroneEntity entity) {
        super(context, entity, 0.0f);

        this.modelId = entity.getModelId();
        this.attitude = MissileAttitudeRegistry.get(DroneModels.attitudeId(modelId));
        this.airframe = DroneModels.airframe(modelId);
        this.crate = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.CRATE))
                .createInstance();
        this.bomb = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(ModModels.BOMBLET))
                .createInstance();
        Vec3 m = DroneModels.mount(modelId);
        this.mount = new Vector3f((float) m.x, (float) m.y, (float) m.z);

        this.prevX = this.curX = entity.getX();
        this.prevY = this.curY = entity.getY();
        this.prevZ = this.curZ = entity.getZ();
        this.prevYaw = this.curYaw = entity.headingRadians();
        this.lastPosTick = entity.tickCount;
        sampleTick();
        this.roll = this.targetRoll;
        this.pitch = this.targetPitch;
        this.rotorScale = this.targetRotorScale;

        updatePose(0.0f);
    }

    private void sampleTick() {
        sampledTick = entity.tickCount;
        targetRoll = entity.getRoll();
        targetPitch = entity.getPitch();
        boolean loaded = entity.hold().isLoaded();
        carryingCrate = entity.hold().hasCrateAboard();
        carryingBomb = entity.hold().hasPayloadAboard();
        mineModelId = entity.hold().slungMineModel();
        mineCount = entity.hold().slungMineCount();
        targetRotorScale = entity.flight().getDroneState()
                .powered()
                        ? (float) airframe.rotorScale(entity.getThrottle(),
                                PowerProfile.DEFAULT.massFactor(loaded))
                        : 0.0f;
        lightPos.set(Mth.floor(curX), Mth.floor(curY), Mth.floor(curZ));
        packedLight = LevelRenderer.getLightColor(entity.level(), lightPos);
    }

    private void updatePose(float partialTick) {
        PartRigs.Rig current = PartRigs.drone(modelId);
        if (current == null) {
            return;
        }
        if (rig != current) {
            if (body != null) {
                body.delete();
            }
            rig = current;
            body = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model()
                            .model())
                    .createInstance();
            body.colorArgb(0xFFFFFFFF);
        }

        float elapsed = Math.max(0.0f, entity.tickCount > lastPosTick
                ? (entity.tickCount - lastPosTick) + partialTick - lastPartialTick
                : partialTick - lastPartialTick);
        lastPartialTick = partialTick;

        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
            prevYaw = curYaw;
            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();
            curYaw = entity.headingRadians();
            lastPosTick = entity.tickCount;
        }

        if (entity.tickCount != sampledTick) {
            sampleTick();
        }

        roll += (targetRoll - roll) * ATTITUDE_SMOOTHING;
        pitch += (targetPitch - pitch) * ATTITUDE_SMOOTHING;

        rotorScale += Mth.clamp(targetRotorScale - rotorScale, -SPOOL_RATE * elapsed, SPOOL_RATE * elapsed);
        rotorPhase += rotorScale * elapsed;

        Vec3i origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, prevX, curX) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, prevY, curY) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, prevZ, curZ) - origin.getZ());
        float yaw = prevYaw + wrap(curYaw - prevYaw) * partialTick;

        double tiltX = roll * Math.cos(yaw) + pitch * Math.sin(yaw);
        double tiltZ = pitch * Math.cos(yaw) - roll * Math.sin(yaw);

        heading.set((float) Math.sin(yaw), 0.0f, (float) Math.cos(yaw));
        attitude.orientation(heading, orientation);
        FlightAttitude.leanRotation(tiltX, tiltZ, lean);

        Matrix4f matrix = pose.translation(x, y, z)
                .rotate(lean)
                .rotate(orientation);

        layers[SPIN_LAYER] = current.spin() == null ? null : current.spin()
                .clip();
        times[SPIN_LAYER] = current.spin() == null ? 0.0f : current.spin()
                .timeAt(rotorPhase);
        layers[STOW_LAYER] = current.stow() == null ? null : current.stow()
                .clip();
        times[STOW_LAYER] = current.stow() == null ? 0.0f : current.stow()
                .timeAt(1.0f);

        GemRenderGltfModel gltf = current.model();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), layers, times, 0);

        body.pose.set(matrix);
        body.boneBase = posed.boneBase();
        body.morphBase = posed.morphBase();
        body.boneSphere.set(posed.sphere());
        bound.set(posed.sphere());
        bounded = true;
        body.light(packedLight);
        body.setChanged();

        updateCrate(matrix, packedLight);
        updateBomb(matrix, packedLight);
        updateMines(matrix, packedLight);
    }

    private void updateCrate(Matrix4f body, int packedLight) {
        if (!carryingCrate) {
            this.crate.setZeroTransform();
            this.crate.setChanged();
            return;
        }
        this.crate.pose.set(body)
                .translate(mount.x, mount.y - CRATE_HALF, mount.z);
        this.crate.light(packedLight);
        this.crate.setChanged();
    }

    private void updateBomb(Matrix4f body, int packedLight) {
        if (!carryingBomb) {
            this.bomb.setZeroTransform();
            this.bomb.setChanged();
            return;
        }
        this.bomb.pose.set(body)
                .translate(mount.x, mount.y - BOMB_DROP, mount.z);
        this.bomb.light(packedLight);
        this.bomb.setChanged();
    }

    private void updateMines(Matrix4f body, int packedLight) {
        ResourceLocation id = mineModelId;
        GemRenderGltfModel current = id == null ? null : MineRigs.model(id);
        if (current != mineModel) {
            for (int i = 0; i < mines.length; i++) {
                if (mines[i] != null) {
                    mines[i].delete();
                    mines[i] = null;
                }
            }
            mineModel = current;
            mineSize = id == null ? new Vec3(1.0, 1.0, 1.0) : MineModels.size(id);
        }
        if (current == null) {
            return;
        }

        int drawn = Math.min(mineCount, MINES_DRAWN);
        PoseCache.Pose posed = drawn <= 0 ? null : PoseCache.getInstance()
                .pose(current.layout(), current.bounds(), current.morphs(), (GltfAnimation) null, 0.0f);

        float pitch = (float) mineSize.x * MINE_PITCH;
        int rows = (MINES_DRAWN + MINES_ABREAST - 1) / MINES_ABREAST;
        for (int i = 0; i < mines.length; i++) {
            if (i >= drawn) {
                if (mines[i] != null) {
                    mines[i].setZeroTransform();
                    mines[i].setChanged();
                }
                continue;
            }
            if (mines[i] == null) {
                mines[i] = instancerProvider()
                        .instancer(GemRenderInstanceTypes.SKINNED, current.model())
                        .createInstance();
                mines[i].colorArgb(0xFFFFFFFF);
            }
            float across = (i % MINES_ABREAST - (MINES_ABREAST - 1) * 0.5f) * pitch;
            float along = (i / MINES_ABREAST - (rows - 1) * 0.5f) * pitch;
            GemRenderInstance instance = mines[i];
            instance.pose.set(slung.set(body)
                    .translate(mount.x + across, mount.y - (float) mineSize.y, mount.z + along));
            instance.boneBase = posed.boneBase();
            instance.morphBase = posed.morphBase();
            instance.boneSphere.set(posed.sphere());
            instance.light(packedLight);
            instance.setChanged();
        }
    }

    private static float wrap(float angle) {
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
        if (this.body != null) {
            this.body.delete();
            this.body = null;
        }
        this.crate.delete();
        this.bomb.delete();
        for (int i = 0; i < mines.length; i++) {
            if (mines[i] != null) {
                mines[i].delete();
                mines[i] = null;
            }
        }
    }

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
            updatePose(context.partialTick());
        });
    }
}
