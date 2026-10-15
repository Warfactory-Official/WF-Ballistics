package com.wf.wflib.client.fx;

import com.wf.wflib.WFSounds;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.PowerProfile;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Rotor loop of one drone; throttle source same as the model's draw state. */
public final class DroneRotorSound extends Emitter {
    /** {@link DroneAudioClient} scan radius. */
    static final float RANGE = 64;
    private static final SoundSpec SPEC = new SoundSpec(WFSounds.DRONE_ROTOR_LOOP.getId(), 0.85F, 1, 0, RANGE, 0);
    private static final float SPOOL_RATE = 0.12F;
    /** Clamp bounds looser than anything the airframe actually produces. */
    private static final float MIN_PITCH = 0.55F;
    private static final float MAX_PITCH = 1.75F;
    /** Below this: loop stopped; {@link DroneAudioClient} may start a fresh one. */
    private static final float CUTOFF = 0.02F;

    private final DroneEntity drone;
    private float spool;

    public DroneRotorSound(DroneEntity drone) {
        super(SPEC, AudioCategory.VEHICLES, RandomSource.create(), true);
        this.drone = drone;
        this.spool = targetScale();
        shape();
        refresh();
    }

    public boolean isDone() {
        return isStopped();
    }

    /** @return rotor scale, a multiple of unladen-hover speed; 0 when unpowered (depleted/shot down). */
    private float targetScale() {
        if (!drone.flight().getDroneState().powered()) {
            return 0;
        }
        return (float) drone.airframe().rotorScale(drone.getThrottle(),
                PowerProfile.DEFAULT.massFactor(drone.hold().isLoaded()));
    }

    /** 0.35 floor: audible down to the pad even near idle. */
    private void shape() {
        setLevel(Mth.clamp(0.35F + 0.65F * spool, 0, 1));
        setPitchScale(Mth.clamp(spool, MIN_PITCH, MAX_PITCH));
    }

    @Override
    protected Vec3 locate() {
        return drone.isAlive() ? drone.position() : null;
    }

    @Override
    public void tick() {
        float target = targetScale();
        spool = Mth.lerp(SPOOL_RATE, spool, target);
        if (target < CUTOFF && spool < CUTOFF) {
            stop();
            return;
        }
        shape();
        super.tick();
    }
}
