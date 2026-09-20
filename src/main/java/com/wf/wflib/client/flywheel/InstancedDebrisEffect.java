package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.ParticleCollision;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.wflib.client.wiaj.JarModels;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/** The chunks of ground an explosion throws out, rendered through GemRender's particle instancer. */
public class InstancedDebrisEffect implements WFFlywheelEffect {

    /** Ticks a chunk lives, matching the old {@code Debris.MAX_AGE}. */
    private static final int LIFE_TICKS = 100;

    /** The old {@code Debris.MOTION_MULT}, applied to a per-tick velocity that is then made per-second. */
    private static final double MOTION_PER_SECOND = 3.0 * 20.0;

    private static final float TAU = (float) (Math.PI * 2.0);

    private final Level level;
    private final ParticleEmitter emitter;
    private final Model model;
    private final float lifeSeconds;

    private float age;

    /**
     * @param probe shared across every effect of one explosion, so all of them read one cache of the ground
     *      they are all going to land on
     */
    public InstancedDebrisEffect(Level level, double x, double y, double z, JarModels.Rubble rubble, int count,
                                 float velocity, RandomSource random, ParticleCollision.Probe probe) {
        this.level = level;
        this.model = rubble.model();
        this.lifeSeconds = LIFE_TICKS / 20.0f;
        this.emitter = ParticleEmitter.create(WFParticleStyles.debris(), Math.max(1, count), x, y, z);

        for (int i = 0; i < count; i++) {
            // The same cone the hand-ticked debris was thrown in: 45 to 70 degrees up, any bearing.
            double elevation = Math.toRadians(45.0 + random.nextDouble() * 25.0);
            double azimuth = random.nextDouble() * Math.PI * 2.0;
            double horizontal = velocity * Math.cos(elevation) * MOTION_PER_SECOND;

            emitter.spawn(probe, x, y, z,
                    horizontal * Math.cos(azimuth),
                    velocity * Math.sin(elevation) * MOTION_PER_SECOND,
                    -horizontal * Math.sin(azimuth),
                    lifeSeconds, 1.0f,
                    random.nextFloat() * TAU, random.nextFloat() * TAU,
                    rubble.radius());
        }
    }

    ParticleEmitter emitter() {
        return emitter;
    }

    Model model() {
        return model;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new InstancedDebrisVisual(ctx, this, partialTick);
    }

    @Override
    public void tickEffect() {
        age += 1.0f / 20.0f;
    }

    @Override
    public boolean isExpired() {
        return age > lifeSeconds;
    }

    @Override
    public void disposeEffect() {
        emitter.close();
    }
}
