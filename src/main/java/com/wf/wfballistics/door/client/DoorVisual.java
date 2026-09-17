package com.wf.wfballistics.door.client;

import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import com.wf.gemrender.render.PoseCache;
import com.wf.gemrender.render.PoseLod;
import com.wf.wfballistics.door.DoorBlock;
import com.wf.wfballistics.door.DoorBlockEntity;
import com.wf.wfballistics.door.DoorRole;
import com.wf.wfballistics.door.DoorState;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/** Draws a whole door from its core: one instance, one draw, and a pose that is a function of one number. */
public class DoorVisual extends AbstractBlockEntityVisual<DoorBlockEntity> implements SimpleDynamicVisual {

    private final Matrix4f pose = new Matrix4f();

    private DoorRigs.DoorModel model;
    private GemRenderInstance instance;
    private int skin = -1;

    public DoorVisual(VisualizationContext ctx, DoorBlockEntity door, float partialTick) {
        super(ctx, door, partialTick);
        refresh(partialTick, 0);
    }

    @Override
    public void beginFrame(Context ctx) {
        refresh(ctx.partialTick(), PoseLod.getInstance()
                .levelAt(pos.distToCenterSqr(ctx.camera().getPosition())));
    }

    private void refresh(float partialTick, int lod) {
        if (blockEntity.getBlockState()
                .getValue(DoorBlock.ROLE) != DoorRole.CORE) {
            hide();
            return;
        }

        DoorRigs.DoorModel current = DoorRigs.model(blockEntity.type(), blockEntity.skin());
        if (current == null) {
            return;
        }
        if (model != current || skin != blockEntity.skin()) {
            if (instance != null) {
                instance.delete();
            }
            model = current;
            skin = blockEntity.skin();
            instance = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, current.model().model())
                    .createInstance();
            instance.colorArgb(0xFFFFFFFF);
            instance.pose.set(basePose(current));
            relight(instance);
        }

        float unit = blockEntity.openFraction(partialTick);
        AnimationDrive drive = blockEntity.state() == DoorState.CLOSING ? current.close() : current.open();

        PoseCache.Pose posed = PoseCache.getInstance()
                .pose(current.model().layout(), current.model().bounds(), current.model().morphs(),
                        drive.clip(), drive.timeAt(unit), lod);

        if (instance.boneBase != posed.boneBase() || instance.morphBase != posed.morphBase()
                || !instance.boneSphere.equals(posed.sphere())) {
            instance.boneBase = posed.boneBase();
            instance.morphBase = posed.morphBase();
            instance.boneSphere.set(posed.sphere());
            instance.setChanged();
        }
    }

    private Matrix4f basePose(DoorRigs.DoorModel current) {
        Direction facing = blockState.hasProperty(DoorBlock.FACING)
                ? blockState.getValue(DoorBlock.FACING)
                : Direction.NORTH;
        return pose.translation(visualPos.getX() + 0.5f, visualPos.getY(), visualPos.getZ() + 0.5f)
                .rotateY((float) Math.toRadians(yaw(facing)))
                .mul(current.base());
    }

    /** The angles NTM's door renderer uses, kept so a door faces the way it did there. */
    private static float yaw(Direction facing) {
        return switch (facing) {
            case NORTH -> 90.0f;
            case WEST -> 180.0f;
            case SOUTH -> 270.0f;
            default -> 0.0f;
        };
    }

    @Override
    public void collectCrumblingInstances(java.util.function.Consumer<dev.engine_room.flywheel.api.instance.Instance> consumer) {
        if (instance != null) {
            consumer.accept(instance);
        }
    }

    @Override
    public void updateLight(float partialTick) {
        if (instance != null) {
            relight(instance);
        }
    }

    private void hide() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
        model = null;
        skin = -1;
    }

    @Override
    protected void _delete() {
        hide();
    }
}
