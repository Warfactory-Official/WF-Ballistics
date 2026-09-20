package com.wf.wflib.client.flywheel;

import com.wf.gemrender.volume.GemRenderVolumeTypes;
import com.wf.gemrender.volume.Volume;
import com.wf.gemrender.volume.VolumeField;
import com.wf.gemrender.volume.VolumeInstance;
import com.wf.gemrender.volume.VolumeModels;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Draws a {@link GasVolumeCluster} as one raymarched volume. */
public class GasVolumeClusterVisual extends AbstractVisual
        implements EffectVisual<GasVolumeCluster>, SimpleDynamicVisual {

    private final GasVolumeCluster cluster;
    private final Volume volume;
    private final VolumeField field;

    private final VolumeInstance instance;

    public GasVolumeClusterVisual(VisualizationContext ctx, GasVolumeCluster cluster, float partialTick) {
        super(ctx, (Level) cluster.level(), partialTick);
        this.cluster = cluster;

        this.field = VolumeField.create();
        this.volume = Volume.create(cluster.style())
                .seed(cluster.hashCode() * 0.000173F);

        if (field != null) {
            volume.field(field);
        }

        this.instance = ctx.instancerProvider()
                .instancer(GemRenderVolumeTypes.VOLUME, VolumeModels.cloud())
                .createInstance();
        this.instance.volume(volume.slot());

        rebuildField();
        push();
    }

    @Override
    public void beginFrame(Context context) {
        if (cluster.shouldRebuild()) {
            rebuildField();
        }
        push();
    }

    /** Voxelises every live cell into the field. */
    private void rebuildField() {
        if (field == null) {
            cluster.rebuilt();
            return;
        }

        AABB bounds = cluster.bounds();
        field.begin(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        double[] boxes = cluster.boxes();
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            field.addBox(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5]);
        }

        field.commit();
        cluster.rebuilt();
    }

    private void push() {
        AABB bounds = cluster.bounds();
        Vec3i origin = renderOrigin();

        volume.extent((float) (bounds.getXsize() * 0.5), (float) (bounds.getYsize() * 0.5),
                        (float) (bounds.getZsize() * 0.5))
                .fade(cluster.fade());

        instance.center((float) ((bounds.minX + bounds.maxX) * 0.5 - origin.getX()),
                (float) ((bounds.minY + bounds.maxY) * 0.5 - origin.getY()),
                (float) ((bounds.minZ + bounds.maxZ) * 0.5 - origin.getZ()));
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
        // Closing the volume releases the field's atlas tile too.
        volume.close();
    }
}
