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
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public class InstancedDebrisEffect implements WFFlywheelEffect {

    /** Must track {@code Debris.MAX_AGE} (private, can't reference directly). */
    private static final int LIFE_TICKS = 100;

    /** {@code Debris.MOTION_MULT} (private) x ticks/s: converts {@link #launch}'s per-tick velocity to per-second. */
    private static final double MOTION_PER_SECOND = 3.0 * 20.0;

    private static final float TAU = (float) (Math.PI * 2.0);

    private final Level level;
    private final ParticleEmitter emitter;
    private final Model model;
    private final float lifeSeconds;

    private float age;

    /** @param probe shared across every effect of one explosion: one ground cache for all of them. */
    public InstancedDebrisEffect(Level level, double x, double y, double z, List<Vec3> origins,
                                 JarModels.Rubble rubble, float velocity, Quaternionf vent, RandomSource random,
                                 ParticleCollision.Probe probe) {
        this.level = level;
        this.model = rubble.model();
        this.lifeSeconds = LIFE_TICKS / 20.0f;
        this.emitter = ParticleEmitter.create(WFParticleStyles.debris(), Math.max(1, origins.size()), x, y, z);

        for (Vec3 o : origins) {
            Vec3 v = launch(x, y, z, o, velocity, vent, random).scale(MOTION_PER_SECOND);
            emitter.spawn(probe, o.x, o.y, o.z, v.x, v.y, v.z, lifeSeconds, 1.0f,
                    random.nextFloat() * TAU, random.nextFloat() * TAU, rubble.radius());
        }
    }

    /**
     * Per-tick velocity, pre {@code Debris.MOTION_MULT}, in the vent frame (+y -> open side): away from blast in plan,
     * 45-70 deg up (hand-tuned cone); chunk over the centre => random bearing.
     */
    public static Vec3 launch(double x, double y, double z, Vec3 origin, float velocity, Quaternionf vent,
                              RandomSource random) {
        Vector3f d = vent.transformInverse(new Vector3f((float) (origin.x - x), (float) (origin.y - y),
                (float) (origin.z - z)));
        double dx = d.x, dz = d.z;
        double azimuth = dx * dx + dz * dz < 0.25 ? random.nextDouble() * Math.PI * 2.0 : Math.atan2(dz, dx)
                + (random.nextDouble() - 0.5) * 0.6;
        double elevation = Math.toRadians(45.0 + random.nextDouble() * 25.0);
        double speed = velocity * (0.75 + random.nextDouble() * 0.4);
        double horizontal = speed * Math.cos(elevation);
        Vector3f v = vent.transform(new Vector3f((float) (horizontal * Math.cos(azimuth)), (float) (speed * Math.sin(elevation)),
                (float) (horizontal * Math.sin(azimuth))));
        return new Vec3(v.x, v.y, v.z);
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
