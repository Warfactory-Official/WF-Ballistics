package com.wf.wflib.client.fx;

import com.wf.wflib.WFSounds;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.flight.Airframe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/** The rotor loop of one in-world drone, driven by the same flight state the model is drawn from. */
public final class DroneRotorSound extends AbstractTickableSoundInstance {

    /** Loudest a drone gets, at the listener's ear, before distance falloff. */
    private static final float MAX_VOLUME = 0.85F;
    /** How fast the note chases the throttle. */
    private static final float SPOOL_RATE = 0.12F;
    /** Where a rotor note stops being a rotor note. Well outside anything the airframe can actually ask for. */
    private static final float MIN_PITCH = 0.55F;
    private static final float MAX_PITCH = 1.75F;
    /** Below this the loop has faded out and is finished; {@link DroneAudioClient} may start a fresh one. */
    private static final float CUTOFF = 0.02F;
    private static final double SPEED_OF_SOUND = ClientSoundScheduler.SPEED_OF_SOUND;

    private final DroneEntity drone;

    /** Rotor scale the loop is currently playing at, chasing what the throttle is asking for. */
    private float spool;
    /**
     * Where the drone was last tick, so its velocity can be differenced out of its position rather than read from
     * {@code getDeltaMovement()}, which on a client-side entity is only as fresh as the last motion packet the
     * server bothered to send.
     */
    private double lastX, lastY, lastZ;
    private double lastListenerX, lastListenerY, lastListenerZ;
    private boolean primed;

    public DroneRotorSound(DroneEntity drone) {
        super(WFSounds.DRONE_ROTOR_LOOP.get(), SoundSource.NEUTRAL, RandomSource.create());
        this.drone = drone;
        this.looping = true;
        this.delay = 0;
        this.x = drone.getX();
        this.y = drone.getY();
        this.z = drone.getZ();
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.spool = targetScale();
        this.pitch = Mth.clamp(this.spool, MIN_PITCH, MAX_PITCH);
        this.volume = volumeFor(this.spool);
    }

    public boolean isDone() {
        return this.isStopped();
    }

    /**
     * @return rotor speed as a multiple of an unladen hover's, straight off the throttle the server is flying
     *      on, or 0 with the motors cut, which is what a depleted or shot-down drone falls under.
     */
    private float targetScale() {
        if (!this.drone.getDroneState().powered()) {
            return 0.0F;
        }
        return (float) this.drone.airframe().rotorScale(this.drone.getThrottle(),
                PowerProfile.DEFAULT.massFactor(this.drone.isLoaded()));
    }

    /**
     * Harder-working rotors are louder ones, but never silent while they are still turning: the floor keeps a
     * descending drone audible right down to the pad.
     */
    private static float volumeFor(float scale) {
        return MAX_VOLUME * Mth.clamp(0.35F + 0.65F * scale, 0.0F, 1.0F);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !this.drone.isAlive()) {
            this.stop();
            return;
        }

        this.x = this.drone.getX();
        this.y = this.drone.getY();
        this.z = this.drone.getZ();

        float target = targetScale();
        this.spool = Mth.lerp(SPOOL_RATE, this.spool, target);
        if (target < CUTOFF && this.spool < CUTOFF) {
            this.stop();
            return;
        }
        this.volume = volumeFor(this.spool);
        this.pitch = Mth.clamp(this.spool * doppler(mc), MIN_PITCH, MAX_PITCH);
    }

    /** Pitch shift from the drone and the listener closing on or opening from each other. */
    private float doppler(Minecraft mc) {
        Entity listener = mc.getCameraEntity();
        if (listener == null) {
            return 1.0F;
        }
        double lx = listener.getX();
        double ly = listener.getEyeY();
        double lz = listener.getZ();

        double vx = this.x - this.lastX;
        double vy = this.y - this.lastY;
        double vz = this.z - this.lastZ;
        double lvx = lx - this.lastListenerX;
        double lvy = ly - this.lastListenerY;
        double lvz = lz - this.lastListenerZ;

        boolean wasPrimed = this.primed;
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastListenerX = lx;
        this.lastListenerY = ly;
        this.lastListenerZ = lz;
        this.primed = true;
        if (!wasPrimed) {
            return 1.0F;
        }

        double ux = lx - this.x;
        double uy = ly - this.y;
        double uz = lz - this.z;
        double dist = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (dist < 1.0E-4) {
            return 1.0F;
        }
        double inv = 1.0 / dist;
        double towards = (vx * ux + vy * uy + vz * uz) * inv;
        double away = (lvx * ux + lvy * uy + lvz * uz) * inv;
        double denom = SPEED_OF_SOUND - towards;
        if (Math.abs(denom) < 1.0E-3) {
            return 1.0F;
        }
        return (float) ((SPEED_OF_SOUND - away) / denom);
    }
}
