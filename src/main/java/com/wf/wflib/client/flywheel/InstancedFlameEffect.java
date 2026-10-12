package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.wflib.entity.FireLingeringEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

public class InstancedFlameEffect implements WFFlywheelEffect {

    /** Per block of footprint per tick. */
    private static final float FLAMES_PER_BLOCK = 3.0F;

    /** Was 256. */
    private static final int MAX_EMIT = 768;

    /** Ticks; with {@link #emitPerTick} sizes the emitter's ring capacity. */
    private static final int MAX_LIFE = 16;

    /** Ring capacity margin over live-flame count, so the cursor can't overwrite a still-burning flame. */
    private static final float RING_HEADROOM = 1.2F;

    private final Level level;
    private final FireLingeringEntity source;
    private final ParticleEmitter emitter;

    double cx, cy, cz;

    private boolean sourceGone = false;
    private double minX, minZ, baseY, spanX, spanZ;

    public InstancedFlameEffect(FireLingeringEntity source) {
        this.level = source.level();
        this.source = source;
        refreshFootprint();

        int capacity = (int) (emitPerTick() * MAX_LIFE * RING_HEADROOM);
        this.emitter = ParticleEmitter.create(WFParticleStyles.flame(), capacity, cx, cy, cz);
    }

    private void refreshFootprint() {
        AABB box = source.getBoundingBox();
        minX = box.minX;
        minZ = box.minZ;
        baseY = box.minY;
        spanX = box.maxX - box.minX;
        spanZ = box.maxZ - box.minZ;
        cx = (box.minX + box.maxX) * 0.5;
        cy = box.minY;
        cz = (box.minZ + box.maxZ) * 0.5;
    }

    private int emitPerTick() {
        return flameCount(spanX, spanZ);
    }

    public static int flameCount(double spanX, double spanZ) {
        double area = Math.max(1.0, spanX * spanZ);
        return Mth.clamp((int) Math.ceil(area * FLAMES_PER_BLOCK), 4, MAX_EMIT);
    }

    ParticleEmitter emitter() {
        return emitter;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedFlameVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        if (source.isRemoved() || !source.isAlive()) {
            sourceGone = true;
            return;
        }

        refreshFootprint();

        int emit = emitPerTick();
        for (int k = 0; k < emit; k++) {
            double px = minX + level.random.nextDouble() * spanX;
            double pz = minZ + level.random.nextDouble() * spanZ;
            double py = baseY + level.random.nextDouble() * 0.3;

            // Per-second velocities; hand-ticked FlameParticle used per-tick xd=+-0.01, yd=0.02..0.05.
            emitter.spawn(px, py, pz,
                    (level.random.nextDouble() - 0.5) * 0.4,
                    0.4 + level.random.nextDouble() * 0.6,
                    (level.random.nextDouble() - 0.5) * 0.4,
                    (8 + level.random.nextInt(MAX_LIFE - 8)) / 20F,
                    0.5F + level.random.nextFloat() * 0.5F,
                    level.random.nextFloat() * Mth.TWO_PI,
                    0.8F + level.random.nextFloat() * 0.4F);
        }
    }

    @Override
    public boolean isExpired() {
        return sourceGone && emitter.isIdle();
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }
}
