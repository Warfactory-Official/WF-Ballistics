package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.volume.VolumeStyle;
import com.wf.wfballistics.client.fx.MistClientFX;
import com.wf.wfballistics.client.fx.ParticleLight;
import com.wf.wfballistics.entity.MistEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** One gas cloud, assembled from the many {@link MistEntity} cells that actually make it up. */
public class GasVolumeCluster implements WFFlywheelEffect {

    /** How close a cell has to be to the cluster's current bounds to be considered part of it. */
    private static final double MERGE_MARGIN = 1.0;

    /** Largest span, in blocks, a cluster is allowed to reach on any axis. */
    private static final double MAX_SPAN = 28.0;

    /** Ticks between field rebuilds while dirty, so a 220-cell burst does not rebuild 220 times. */
    private static final int REBUILD_INTERVAL = 4;

    /** Ticks the cloud takes to fade out once its last cell is gone. */
    private static final int FADE_TICKS = 20;

    private final Level level;
    private final Fluid fluid;
    private final int tint;
    private final VolumeStyle style;

    private final List<MistEntity> members = new ArrayList<>();

    /** The member boxes, flattened six doubles at a time, republished whenever membership changes. */
    private volatile double[] boxes = new double[0];

    private AABB bounds;

    private boolean dirty = true;
    private int sinceRebuild = REBUILD_INTERVAL;
    private int age;
    private int goneTicks = -1;

    public GasVolumeCluster(MistEntity first) {
        this.level = (Level) first.level();
        this.fluid = first.getFluid();
        this.bounds = first.getBoundingBox();
        this.members.add(first);
        publish();

        this.tint = MistClientFX.tintFor(first);

        AABB box = first.getBoundingBox();
        int packed = ParticleLight.surface(first.level(),
                (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
        this.style = WFVolumeStyles.mist(tint, LightTexture.block(packed), LightTexture.sky(packed));
    }

    /**
     * Offers a cell to this cluster.
     *
     * @return true if it was taken, in which case the caller must not give it to anything else
     */
    public boolean offer(MistEntity cell) {
        if (goneTicks >= 0 || cell.getFluid() != fluid || cell.level() != level) {
            return false;
        }

        AABB box = cell.getBoundingBox();
        if (!bounds.inflate(MERGE_MARGIN).intersects(box)) {
            return false;
        }

        AABB merged = bounds.minmax(box);
        if (merged.getXsize() > MAX_SPAN || merged.getYsize() > MAX_SPAN || merged.getZsize() > MAX_SPAN) {
            return false;
        }

        members.add(cell);
        bounds = merged;
        publish();
        dirty = true;
        return true;
    }

    public AABB bounds() {
        return bounds;
    }

    public VolumeStyle style() {
        return style;
    }

    /** The member boxes as flat {@code [minX, minY, minZ, maxX, maxY, maxZ]} runs. Safe to read anywhere. */
    public double[] boxes() {
        return boxes;
    }

    /** True when the field needs rebuilding and enough ticks have passed to be worth doing it. */
    public boolean shouldRebuild() {
        return dirty && sinceRebuild >= REBUILD_INTERVAL;
    }

    public void rebuilt() {
        dirty = false;
        sinceRebuild = 0;
    }

    public float fade() {
        if (goneTicks >= 0) {
            return Math.max(0F, 1F - goneTicks / (float) FADE_TICKS);
        }
        return Math.min(1F, age / (float) FADE_TICKS);
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new GasVolumeClusterVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        age++;
        sinceRebuild++;

        if (goneTicks >= 0) {
            goneTicks++;
            return;
        }

        boolean removed = members.removeIf(cell -> cell.isRemoved() || !cell.isAlive());

        if (members.isEmpty()) {
            goneTicks = 0;
            return;
        }

        if (removed) {
            AABB shrunk = members.get(0).getBoundingBox();
            for (int i = 1; i < members.size(); i++) {
                shrunk = shrunk.minmax(members.get(i).getBoundingBox());
            }
            bounds = shrunk;
            publish();
            dirty = true;
        }
    }

    private void publish() {
        double[] flat = new double[members.size() * 6];
        for (int i = 0; i < members.size(); i++) {
            AABB box = members.get(i).getBoundingBox();
            int at = i * 6;
            flat[at] = box.minX;
            flat[at + 1] = box.minY;
            flat[at + 2] = box.minZ;
            flat[at + 3] = box.maxX;
            flat[at + 4] = box.maxY;
            flat[at + 5] = box.maxZ;
        }
        boxes = flat;
    }

    @Override
    public boolean isExpired() {
        return goneTicks >= FADE_TICKS;
    }
}
