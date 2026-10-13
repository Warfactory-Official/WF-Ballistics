package com.wf.wflib.grenade.client;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import com.wf.wflib.client.flywheel.FlywheelEffectManager;
import com.wf.wflib.client.fx.WFLights;
import com.wf.wflib.grenade.GrenadeWarheads;
import com.wf.wflib.grenade.Grenades;
import com.wf.wflib.grenade.ThrownCharge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/** Client side of a {@link ThrownCharge}: tumble/roll, reaction sparks, light, loops. */
public final class ChargeClient {

    /** Air tumble, rad/tick. */
    static final float TUMBLE = 0.55f;
    static final int THERMITE_SPARKS = 22;
    /** Mean ticks between violent spits. */
    static final int SPIT_EVERY = 7;

    private static final Map<ThrownCharge, State> STATES = new WeakHashMap<>();

    private static final class State {
        SparkFountain fountain;
        Emitter loop;
    }

    private ChargeClient() {
    }

    public static void tick(ThrownCharge charge) {
        turn(charge);
        State st = STATES.get(charge);
        if (charge.phase() != ThrownCharge.Phase.REACTING) {
            if (st != null) {
                stop(st);
                STATES.remove(charge);
            }
            return;
        }
        if (st == null) {
            st = new State();
            STATES.put(charge, st);
        }
        ResourceLocation warhead = charge.spec().warhead();
        if (warhead.equals(GrenadeWarheads.THERMITE)) {
            thermite(charge, st);
        } else if (GrenadeWarheads.smokeTint(warhead) != -1) {
            smoke(charge, st, GrenadeWarheads.smokeTint(warhead));
        }
    }

    private static void stop(State st) {
        if (st.fountain != null) {
            st.fountain.end();
        }
        if (st.loop != null) {
            st.loop.end();
        }
    }

    private static void turn(ThrownCharge c) {
        c.spinO.set(c.spin);
        if (c.attached()) {
            return;
        }
        Vec3 v = c.getDeltaMovement();
        if (c.grounded()) {
            double speed = v.horizontalDistance();
            if (speed > 1.0E-4) {
                c.spin.premul(new org.joml.Quaternionf().rotationAxis((float) (speed / 0.05),
                        new Vector3f((float) (-v.z / speed), 0, (float) (v.x / speed))));
            }
        } else if (v.lengthSqr() > 0.0025) {
            Vector3f axis = new Vector3f((float) -v.z, 0, (float) v.x);
            if (axis.lengthSquared() < 1.0E-8) {
                axis.set(1, 0, 0);
            }
            c.spin.premul(new org.joml.Quaternionf().rotationAxis(TUMBLE, axis.normalize()));
        }
    }

    private static void thermite(ThrownCharge c, State st) {
        RandomSource random = c.getRandom();
        Vec3 at = c.position();
        Vec3 vent = c.vent();
        if (st.fountain == null && FlywheelEffectManager.isAvailable(c.level())) {
            st.fountain = new SparkFountain(c);
            FlywheelEffectManager.spawn(st.fountain);
        }
        if (st.loop == null) {
            st.loop = Emitter.following(new SoundSpec(Grenades.THERMITE_LOOP.getId(), 1, 1, 0.05f, 64, 0),
                    AudioCategory.EXPLOSIONS, c, Vec3.ZERO, true);
            Minecraft.getInstance().getSoundManager().play(st.loop);
        }
        int age = c.phaseAge();
        // Ignition flare-up over the first second, then a steady violent burn with spits.
        float ramp = Math.min(1f, age / 20f);
        if (st.fountain != null) {
            st.fountain.spray(at, vent, Math.round(THERMITE_SPARKS * (0.4f + 0.6f * ramp)), 1.5f, 6.5f, 0.45f,
                    random);
            if (random.nextInt(SPIT_EVERY) == 0) {
                st.fountain.spray(at, vent, 60 + random.nextInt(60), 4f, 11f, 0.9f, random);
            }
        }
        if (age % 2 == 0) {
            WFLights.flash(at.x + vent.x * 0.3, at.y + vent.y * 0.3, at.z + vent.z * 0.3, 14, 0xFFE0B0,
                    60 + random.nextFloat() * 50, 3);
        }
        if (random.nextInt(3) == 0) {
            c.level().addParticle(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z,
                    vent.x * 0.05 + random.nextGaussian() * 0.01, 0.06 + random.nextFloat() * 0.04,
                    vent.z * 0.05 + random.nextGaussian() * 0.01);
        }
    }

    private static void smoke(ThrownCharge c, State st, int tint) {
        if (st.loop == null) {
            st.loop = Emitter.following(new SoundSpec(Grenades.SMOKE_HISS.getId(), 0.8f, 1, 0.1f, 32, 0),
                    AudioCategory.WEAPONS, c, Vec3.ZERO, true);
            Minecraft.getInstance().getSoundManager().play(st.loop);
        }
        RandomSource random = c.getRandom();
        Vec3 at = c.position();
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        for (int i = 0; i < 2; i++) {
            c.level().addParticle(new net.minecraft.core.particles.DustParticleOptions(new Vector3f(r, g, b), 3.5f),
                    at.x + random.nextGaussian() * 0.1, at.y + 0.15, at.z + random.nextGaussian() * 0.1, 0, 0.1, 0);
        }
    }
}
