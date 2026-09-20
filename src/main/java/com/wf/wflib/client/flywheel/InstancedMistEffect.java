package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.wflib.client.fx.MistClientFX;
import com.wf.wflib.client.fx.ParticleLight;
import net.minecraft.client.renderer.LightTexture;
import com.wf.wflib.entity.MistEntity;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

/** A gas or mist cloud, rendered through GemRender. */
public class InstancedMistEffect implements WFFlywheelEffect {

    /** Particles per block of cloud volume per tick. */
    private static final double PARTICLE_DENSITY = 0.09;

    /** Ceiling per cloud per tick. */
    private static final int MAX_EMIT = 180;

    private static final int LIFE_TICKS_MIN = 50;
    private static final int LIFE_TICKS_JITTER = 10;

    private static final float RING_HEADROOM = 1.2F;

    /**
     * Vanilla's {@code getQuadSize} is a half-extent (its quad spans plus and minus that), while GemRender's spans
     * [-0.5, 0.5] and scales by the full width.
     */
    private static final float BASE_SIZE = 1.5F;

    private final Level level;
    private final MistEntity source;
    private final ParticleEmitter emitter;

    private boolean sourceGone = false;

    public InstancedMistEffect(MistEntity source) {
        this.level = source.level();
        this.source = source;

        AABB box = source.getBoundingBox();
        double mx = (box.minX + box.maxX) * 0.5;
        double my = (box.minY + box.maxY) * 0.5;
        double mz = (box.minZ + box.maxZ) * 0.5;

        int packed = ParticleLight.surface(source.level(), mx, my, mz);
        int tint = MistClientFX.tintFor(source);
        int blockLight = LightTexture.block(packed);
        int skyLight = LightTexture.sky(packed);

        int capacity = (int) (emitFor(box) * (LIFE_TICKS_MIN + LIFE_TICKS_JITTER) * RING_HEADROOM);
        emitter = ParticleEmitter.create(WFParticleStyles.mist(tint, blockLight, skyLight),
                capacity, mx, my, mz);
    }

    private static int emitFor(AABB box) {
        double volume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        return Mth.clamp((int) Math.round(volume * PARTICLE_DENSITY), 1, MAX_EMIT);
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
        return new InstancedMistVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        if (source.isRemoved() || !source.isAlive()) {
            sourceGone = true;
            return;
        }

        AABB box = source.getBoundingBox();
        int emit = emitFor(box);

        for (int i = 0; i < emit; i++) {
            double x = box.minX + level.random.nextDouble() * (box.maxX - box.minX);
            double y = box.minY + level.random.nextDouble() * (box.maxY - box.minY);
            double z = box.minZ + level.random.nextDouble() * (box.maxZ - box.minZ);

            emitter.spawn(x, y, z,
                    0.0, 0.4, 0.0,
                    (LIFE_TICKS_MIN + level.random.nextInt(LIFE_TICKS_JITTER)) / 20F,
                    BASE_SIZE,
                    level.random.nextFloat() * Mth.TWO_PI,
                    1.0F);
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
