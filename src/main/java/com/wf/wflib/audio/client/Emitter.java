package com.wf.wflib.audio.client;

import com.wf.wflib.audio.Acoustics;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * wflib-managed world sound: no engine attenuation; gain = {@link SoundSpec#gain} at the camera x {@link #level} x
 * category slider, set every tick. Air absorption + Doppler (finite-difference velocity) applied per source by
 * {@link AudioMixer}. Source MASTER.
 */
public abstract class Emitter extends AbstractTickableSoundInstance {
    /** Per-tick displacement above this = teleport: no Doppler that tick. */
    private static final double MAX_STEP = 20;

    protected final SoundSpec spec;
    protected final AudioCategory category;
    private float level = 1;
    private final float basePitch;
    private boolean primed;
    private boolean ownVelocity;
    /** Read by the sound thread ({@link AudioMixer}). */
    volatile float vx, vy, vz;
    protected volatile float low = 1, high = 1;

    protected Emitter(SoundSpec spec, AudioCategory category, RandomSource random, boolean looping) {
        super(SoundEvent.createVariableRangeEvent(spec.event()), SoundSource.MASTER, random);
        this.spec = spec;
        this.category = category;
        this.attenuation = Attenuation.NONE;
        this.looping = looping;
        this.delay = 0;
        this.basePitch = spec.rollPitch(random);
        this.pitch = basePitch;
    }

    public static Emitter at(SoundSpec spec, AudioCategory category, double x, double y, double z, long seed) {
        Vec3 at = new Vec3(x, y, z);
        return new Emitter(spec, category, RandomSource.create(seed), false) {
            @Override
            protected Vec3 locate() {
                return at;
            }
        };
    }

    /** Follows {@code e} + {@code offset} (world axes); removed entity => stop. */
    public static Emitter following(SoundSpec spec, AudioCategory category, Entity e, Vec3 offset, boolean loop) {
        return new Emitter(spec, category, RandomSource.create(e.getRandom().nextLong()), loop) {
            @Override
            protected Vec3 locate() {
                return e.isRemoved() ? null : e.position().add(offset);
            }
        };
    }

    /** Follows {@code at}; null => stop. */
    public static Emitter tracking(SoundSpec spec, AudioCategory category, Supplier<Vec3> at, boolean loop) {
        return new Emitter(spec, category, RandomSource.create(), loop) {
            @Override
            protected Vec3 locate() {
                return at.get();
            }
        };
    }

    /** World position now; null => stop. */
    protected abstract Vec3 locate();

    public SoundSpec spec() {
        return spec;
    }

    public AudioCategory category() {
        return category;
    }

    /** Share of the cabin muffle applied, 0..1. */
    protected float muffle() {
        return category.muffle();
    }

    /** Owner gain on top of the spec's, 0..1. */
    public void setLevel(float level) {
        this.level = level;
    }

    /** Pitch = rolled spec pitch x {@code scale}. */
    public void setPitchScale(float scale) {
        this.pitch = basePitch * scale;
    }

    /** Blocks/tick; from now on the owner's (dead-reckoned sources: finite differences would see the snaps). */
    public void setVelocity(double vx, double vy, double vz) {
        ownVelocity = true;
        this.vx = (float) vx;
        this.vy = (float) vy;
        this.vz = (float) vz;
    }

    /** Owner stop (loops). */
    public void end() {
        stop();
    }

    @Override
    public boolean canStartSilent() {
        return looping;
    }

    /** Velocity = displacement since the previous tick, unless {@link #setVelocity} owns it. */
    @Override
    public void tick() {
        double px = x, py = y, pz = z;
        if (!refresh()) {
            stop();
            return;
        }
        if (ownVelocity) {
            return;
        }
        double dx = x - px, dy = y - py, dz = z - pz;
        boolean jump = !primed || dx * dx + dy * dy + dz * dz > MAX_STEP * MAX_STEP;
        vx = jump ? 0 : (float) dx;
        vy = jump ? 0 : (float) dy;
        vz = jump ? 0 : (float) dz;
        primed = true;
    }

    /** Position, gain, absorption from the camera now; false => source gone. */
    public boolean refresh() {
        Vec3 p = locate();
        if (p == null) {
            return false;
        }
        x = p.x;
        y = p.y;
        z = p.z;
        double d = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(p);
        volume = spec.gain(d) * level * AudioOptions.volume(category);
        float t = Acoustics.absorption(d, spec.range());
        low = 1 - 0.25F * t;
        high = 1 - 0.99F * t;
        return true;
    }

    /** Camera distance to the last refreshed position. */
    public double distance() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(new Vec3(x, y, z));
    }
}
