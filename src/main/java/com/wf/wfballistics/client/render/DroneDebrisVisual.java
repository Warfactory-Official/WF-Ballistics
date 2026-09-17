package com.wf.wfballistics.client.render;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PosedBound;
import com.wf.wfballistics.client.model.PartRigs;
import com.wf.wfballistics.drone.DroneDebrisEntity;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/** Draws one bone of a drone that came apart: the same mesh it was a moment ago, tumbling on its own. */
public class DroneDebrisVisual extends AbstractEntityVisual<DroneDebrisEntity> implements DynamicVisual {

    /** The bound of the model as it was last posed, in model space, and whether it has been posed at all. */
    private final Vector4f bound = new Vector4f();
    private boolean bounded;

    private GemRenderInstance piece;
    private PartRigs.Rig rig;

    private final Quaternionf spinPose = new Quaternionf();
    private final Matrix4f pose = new Matrix4f();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    /** One layer: the clip that makes this piece a piece. */
    private final GltfAnimation[] layers = new GltfAnimation[1];
    private final float[] times = new float[1];

    /** The piece's own centre within the airframe. */
    private final org.joml.Vector3f centre = new org.joml.Vector3f();
    private boolean centred;

    private double prevX, prevY, prevZ;
    private double curX, curY, curZ;
    private int lastPosTick = -1;
    private float lastPartialTick;
    /** Accumulated tumble about each axis, radians. Integrated per frame, like the drone's rotor phase. */
    private float angleX;
    private float angleY;
    private float angleZ;
    private int packedLight;

    public DroneDebrisVisual(VisualizationContext context, DroneDebrisEntity entity) {
        super(context, entity, 0.0f);
        this.prevX = this.curX = entity.getX();
        this.prevY = this.curY = entity.getY();
        this.prevZ = this.curZ = entity.getZ();
        this.lastPosTick = entity.tickCount;
        float seed = (entity.getId() * 2654435761L >>> 8) % 1000 / 1000.0f;
        this.angleX = seed * (float) Math.PI * 2.0f;
        this.angleY = entity.getYRot() * Mth.DEG_TO_RAD;
        this.angleZ = (1.0f - seed) * (float) Math.PI;
        updatePose(0.0f);
    }

    private void updatePose(float partialTick) {
        PartRigs.Rig current = PartRigs.piece(entity.part());
        if (current == null) {
            return;
        }
        if (rig != current) {
            if (piece != null) {
                piece.delete();
            }
            rig = current;
            piece = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model()
                            .model())
                    .createInstance();
        }
        if (!centred) {
            Vec3 c = com.wf.wfballistics.drone.DroneModels.pieceCenter(entity.part());
            centre.set((float) c.x, (float) c.y, (float) c.z);
            centred = true;
        }

        float elapsed = Math.max(0.0f, entity.tickCount > lastPosTick
                ? (entity.tickCount - lastPosTick) + partialTick - lastPartialTick
                : partialTick - lastPartialTick);
        lastPartialTick = partialTick;

        if (entity.tickCount > lastPosTick) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
            curX = entity.getX();
            curY = entity.getY();
            curZ = entity.getZ();
            lastPosTick = entity.tickCount;
            lightPos.set(Mth.floor(curX), Mth.floor(curY), Mth.floor(curZ));
            packedLight = LevelRenderer.getLightColor(entity.level(), lightPos);
        }

        float remaining = entity.spinScale();
        if (remaining > 0.0f) {
            Vec3 spin = entity.spin();
            angleX += (float) spin.x * remaining * elapsed;
            angleY += (float) spin.y * remaining * elapsed;
            angleZ += (float) spin.z * remaining * elapsed;
        }

        Vec3i origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, prevX, curX) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, prevY, curY) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, prevZ, curZ) - origin.getZ());

        spinPose.identity()
                .rotateY(angleY)
                .rotateX(angleX)
                .rotateZ(angleZ);
        Matrix4f matrix = pose.translation(x, y, z)
                .rotate(spinPose)
                .translate(-centre.x, -centre.y, -centre.z);

        layers[0] = current.piece() == null ? null : current.piece()
                .clip();
        times[0] = current.piece() == null ? 0.0f : current.piece()
                .timeAt(1.0f - entity.alpha(partialTick));

        GemRenderGltfModel gltf = current.model();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(gltf.layout(), gltf.bounds(), gltf.morphs(), layers, times, 0);

        piece.pose.set(matrix);
        piece.boneBase = posed.boneBase();
        piece.morphBase = posed.morphBase();
        piece.boneSphere.set(posed.sphere());
        bound.set(posed.sphere());
        bounded = true;
        piece.light(packedLight);
        piece.setChanged();
    }

    @Override
    protected void _delete() {
        if (this.piece != null) {
            this.piece.delete();
            this.piece = null;
        }
    }

    /** Whether the piece is on screen, tested against the model rather than against its hitbox. */
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
