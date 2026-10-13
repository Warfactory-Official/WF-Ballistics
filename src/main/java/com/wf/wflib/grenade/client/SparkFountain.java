package com.wf.wflib.grenade.client;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.particle.ParticleCollision;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticlePool;
import com.wf.gemrender.particle.ParticleStyle;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.flywheel.WFFlywheelEffect;
import com.wf.gemrender.particle.LevelContactProbe;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/** Streak sparks from a burning source while it lives; expires once its last spark is out. Units/s. */
public final class SparkFountain implements WFFlywheelEffect {

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
            "textures/particle/spark.png");
    static final int CAPACITY = 4096;
    static final float MAX_LIFE = 1.6f;

    private static int style = -1;

    private final Level level;
    private final Entity source;
    private final ParticleEmitter emitter;
    private final Model model;
    private final ParticleCollision.Probe probe;
    private float sinceEnd = -1;

    public SparkFountain(Entity source) {
        this.level = source.level();
        this.source = source;
        Vec3 at = source.position();
        this.emitter = ParticleEmitter.create(style(), CAPACITY, at.x, at.y, at.z);
        this.model = ParticleModels.glowing(TEXTURE);
        this.probe = LevelContactProbe.of(level);
    }

    private static int style() {
        if (style < 0) {
            style = ParticleBuffer.getInstance().registerStyle(ParticleStyle.builder()
                    .drag(ParticleStyle.dragFromPerTickFactor(0.95F))
                    .gravity(12)
                    .size(0.035F, 0)
                    .tint(0xFFE6B0)
                    .cool(0.3F, 0.7F)
                    .streak(0.04F)
                    .glow(1)
                    .bouncesOnContact(0.35F, 0.5F)
                    .build());
        }
        return style;
    }

    /** {@code n} sparks from {@code at} into a cone of half-angle ~{@code spread} rad about {@code dir}. */
    public void spray(Vec3 at, Vec3 dir, int n, float speedMin, float speedMax, float spread, RandomSource random) {
        for (int i = 0; i < n; i++) {
            double s = speedMin + (speedMax - speedMin) * random.nextFloat();
            double vx = dir.x + random.nextGaussian() * spread;
            double vy = dir.y + random.nextGaussian() * spread;
            double vz = dir.z + random.nextGaussian() * spread;
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len < 1.0E-6) {
                continue;
            }
            emitter.spawn(probe, at.x, at.y, at.z, vx / len * s, vy / len * s, vz / len * s,
                    0.35f + random.nextFloat() * (MAX_LIFE - 0.35f), 0.6f + random.nextFloat() * 0.9f,
                    random.nextFloat() * 6.2832f, 1, 0.02f);
        }
    }

    /** Source done: no more sprays; live sparks finish. */
    public void end() {
        if (sinceEnd < 0) {
            sinceEnd = 0;
        }
    }

    public boolean ended() {
        return sinceEnd >= 0;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new Visual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        if (sinceEnd < 0 && source.isRemoved()) {
            end();
        }
        if (sinceEnd >= 0) {
            sinceEnd += 0.05f;
        }
    }

    @Override
    public boolean isExpired() {
        return sinceEnd > MAX_LIFE + 0.5f;
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }

    private static final class Visual extends AbstractVisual implements EffectVisual<SparkFountain> {
        private final ParticlePool pool;

        Visual(VisualizationContext ctx, SparkFountain f, float partialTick) {
            super(ctx, f.level, partialTick);
            this.pool = new ParticlePool(ctx, f.emitter, GemRenderParticleTypes.STREAK, f.model);
        }

        @Override
        protected void _delete() {
            pool.delete();
        }
    }
}
