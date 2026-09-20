package com.wf.wflib.client.render;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PosedBound;
import com.wf.wflib.client.model.MineRigs;
import com.wf.wflib.mine.MineCamo;
import com.wf.wflib.mine.MineEntity;
import com.wf.wflib.mine.MineModels;
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
import org.joml.Vector4f;

/**
 * Draws a mine: the body, lying however it came to rest (a tumbled one keeps the pitch and roll it landed at, so a
 * scattered field is not a parade of upright mines), at its own size.
 */
public class MineVisual extends AbstractEntityVisual<MineEntity> implements DynamicVisual {

    private final Matrix4f pose = new Matrix4f();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    /** The bound of the model as it was last posed, in model space, and whether it has been posed at all. */
    private final Vector4f bound = new Vector4f();
    private boolean bounded;

    private GemRenderGltfModel model;
    /** The size the model is authored at, read once per model swap rather than per frame. */
    private Vec3 modelSize = new Vec3(1.0, 1.0, 1.0);
    /** The finish last written to the instance; a repaint is rare, so it is not written every frame. */
    private MineCamo camo;
    private GemRenderInstance instance;
    /** The chain below a moored naval mine; null for every mine that is not one. */
    private final MineMooring mooring;

    private int packedLight;
    private int lightTick = Integer.MIN_VALUE;

    public MineVisual(VisualizationContext context, MineEntity entity) {
        super(context, entity, 0.0f);
        this.mooring = new MineMooring(context);
        updatePose(0.0f);
    }

    private void updatePose(float partialTick) {
        GemRenderGltfModel current = MineRigs.model(entity.getModelId());
        if (current == null) {
            return;
        }
        if (this.model != current) {
            if (this.instance != null) {
                this.instance.delete();
            }
            this.model = current;
            this.modelSize = MineModels.size(entity.getModelId());
            // A new model is a new sheet, so whatever variant the old instance carried means nothing now.
            this.camo = null;
            this.instance = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model())
                    .createInstance();
            this.instance.colorArgb(0xFFFFFFFF);
        }

        Vec3i origin = renderOrigin();
        float renderX = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float renderY = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float renderZ = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.rotLerp(partialTick, entity.xRotO, entity.getXRot());
        float roll = Mth.rotLerp(partialTick, entity.getRollO(), entity.getRoll());

        Vec3 own = this.modelSize;
        float width = (float) (entity.getBbWidth() / Math.max(1.0E-3, own.x));
        float height = (float) (entity.bodyHeight() / Math.max(1.0E-3, own.y));
        Matrix4f matrix = this.pose.translation(renderX, (float) (renderY - entity.buryDepth()), renderZ)
                .rotateY((float) -Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll))
                .scale(width, height, width);

        if (entity.tickCount != this.lightTick) {
            this.lightTick = entity.tickCount;
            this.lightPos.set(Mth.floor(entity.getX()), Mth.floor(entity.getY()), Mth.floor(entity.getZ()));
            this.packedLight = LevelRenderer.getLightColor(entity.level(), this.lightPos);
        }

        GltfAnimation empty = entity.isRack() ? MineRigs.empty(entity.getModelId()) : null;
        float fired = empty == null ? 0.0f : 1.0f - entity.canisterFraction();
        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(current.layout(), current.bounds(), current.morphs(), empty, fired);

        MineCamo camo = entity.getCamo();
        if (camo != this.camo) {
            this.camo = camo;
            this.instance.variant(current.variant(MineRigs.variant(entity.getModelId(), camo)));
        }

        this.instance.pose.set(matrix);
        this.instance.boneBase = posed.boneBase();
        this.instance.morphBase = posed.morphBase();
        this.instance.boneSphere.set(posed.sphere());
        bound.set(posed.sphere());
        bounded = true;
        this.instance.light(this.packedLight);
        this.instance.setChanged();

        // Cosmetic, and entirely the client's own: nothing about the chain is synced or simulated.
        this.mooring.update(entity, origin, renderX, renderY, renderZ, this.packedLight);
    }

    @Override
    protected void _delete() {
        if (this.instance != null) {
            this.instance.delete();
            this.instance = null;
        }
        this.mooring.delete();
    }

    /** Whether the mine is on screen, tested against the model rather than against its hitbox. */
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
