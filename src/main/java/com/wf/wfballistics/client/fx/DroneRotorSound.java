package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.PowerProfile;
import com.wf.wfballistics.drone.flight.Airframe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * The rotor loop of one in-world drone, driven by the same flight state the model is drawn from.
 *
 * <p>The loop asset is a quadcopter holding an unladen hover, so playing it back at the airframe's own
 * {@link Airframe#rotorScale} is all the engine note needs to be: a drone clawing up over a ridge or lifting
 * a loaded crate is turning its rotors faster, so it plays faster, and one settling onto a pad winds down.
 * That is the same number {@code DroneVisual} spins the discs with: picture and sound cannot disagree
 * about how hard the thing is working, because neither of them is inventing it.
 *
 * <p>Distance is left to OpenAL: the {@code attenuation_distance} in {@code sounds.json} sets the radius and
 * the sound engine does the falloff and the panning, which is why this is an ordinary positioned looping
 * sound rather than the hand-attenuated {@link RemoteMissileFlightSound}: that one has to fake all of this
 * because it is tracking a missile with no entity attached to it.
 *
 * <p>Started and re-started by {@link DroneAudioClient}.
 */
public final class DroneRotorSound extends AbstractTickableSoundInstance {

    /** Loudest a drone gets, at the listener's ear, before distance falloff. */
    private static final float MAX_VOLUME = 0.85F;
    /**
     * How fast the note chases the throttle. Rotors have inertia, and this is the audible half of the same
     * spool {@code DroneVisual} gives the discs: kept in step with it so a drone gunning it does not sound
     * like it got there before it looks like it did.
     */
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
     * Where the drone was last tick, so its velocity can be differenced out of its position rather than read
     * from {@code getDeltaMovement()}, which on a client-side entity is only as fresh as the last motion
     * packet the server bothered to send.
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
        // Come in already spooled to whatever it is doing: a drone flown in from out of earshot is mid-flight,
        // not starting its motors, and should not be heard winding up from nothing every time it comes in range.
        this.spool = targetScale();
        this.pitch = Mth.clamp(this.spool, MIN_PITCH, MAX_PITCH);
        this.volume = volumeFor(this.spool);
    }

    public boolean isDone() {
        return this.isStopped();
    }

    /**
     * @return rotor speed as a multiple of an unladen hover's, straight off the throttle the server is flying
     * on, or 0 with the motors cut, which is what a depleted or shot-down drone falls under.
     */
    private float targetScale() {
        if (!this.drone.getDroneState().powered()) {
            return 0.0F;
        }
        // The stock airframe and profile rather than the drone's own, because neither of those fields is
        // synced: a client-side drone still holds the defaults. Same reason DroneVisual reaches for the
        // constants, and the same constants, so the two stay in agreement.
        return (float) Airframe.QUADCOPTER.rotorScale(this.drone.getThrottle(),
                PowerProfile.DEFAULT.massFactor(this.drone.isLoaded()));
    }

    /**
     * Harder-working rotors are louder ones, but never silent while they are still turning: the floor keeps
     * a descending drone audible right down to the pad.
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
            // Motors cut and the loop has wound out behind them. Let it go rather than hold a channel open
            // for a drone parked on a pad; DroneAudioClient starts a new one the moment it lifts again.
            // Both halves matter: winding out is not enough on its own, or a drone caught on the tick its
            // throttle reads zero (spawned, or one state into a takeoff) would kill a loop it is about to
            // need and stutter as it got it back.
            this.stop();
            return;
        }
        this.volume = volumeFor(this.spool);
        this.pitch = Mth.clamp(this.spool * doppler(mc), MIN_PITCH, MAX_PITCH);
    }

    /**
     * Pitch shift from the drone and the listener closing on or opening from each other. Small, a drone
     * cruises at a fraction of {@link ClientSoundScheduler#SPEED_OF_SOUND}, but it is what makes one passing
     * overhead read as passing rather than as merely being loud for a moment.
     */
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
        // First tick has no previous listener position to difference against, so there is no velocity to be
        // had yet and anything computed from one would be a lurch on the note.
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
