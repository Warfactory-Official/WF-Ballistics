package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.network.MissileFlightAudioPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * A looping missile flight sound driven entirely by {@link MissileFlightAudioPacket} heartbeats from the
 * server, rather than by a tracked entity. This is what lets a missile be heard before it renders and across
 * the entity↔off-world-sim boundary. Position is dead-reckoned from the last reported velocity between
 * heartbeats and snapped on each new one; the loop fades out and stops if heartbeats stop arriving (detonation,
 * leaving range, unload). Managed by {@link MissileAudioClient}.
 */
public final class RemoteMissileFlightSound extends AbstractTickableSoundInstance {

    private static final float MAX_VOLUME = 0.9F;
    private static final float MIN_PITCH = 0.5F;
    private static final float MAX_PITCH = 2.0F;
    private static final double SPEED_OF_SOUND = ClientSoundScheduler.SPEED_OF_SOUND;

    // Reported source position/velocity (updated per heartbeat, dead-reckoned in between) and tuning.
    private double curX, curY, curZ;
    private double velX, velY, velZ;
    private double range;
    private float basePitch;
    private double speedPitch;
    private int ticksSinceUpdate;

    private double prevListenerX, prevListenerY, prevListenerZ;
    private boolean primed;

    public RemoteMissileFlightSound(SoundEvent sound, MissileFlightAudioPacket pkt) {
        super(sound, SoundSource.HOSTILE, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.pitch = pkt.basePitch();
        applyUpdate(pkt);
        this.x = this.curX;
        this.y = this.curY;
        this.z = this.curZ;
        // Start at the right distance-based volume so there's no 1-tick silent gap. canStartSilent() (below)
        // covers the case where this is exactly 0 (missile entering at the very edge of range) — otherwise the
        // sound engine would cull a volume-zero sound at play time and it would never tick up.
        this.volume = distanceVolume();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    private float distanceVolume() {
        Entity listener = Minecraft.getInstance().getCameraEntity();
        if (listener == null) {
            return MAX_VOLUME;
        }
        double dx = listener.getX() - this.curX;
        double dy = listener.getEyeY() - this.curY;
        double dz = listener.getZ() - this.curZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) (MAX_VOLUME * Mth.clamp(1.0 - dist / this.range, 0.0, 1.0));
    }

    /** Fold in a fresh heartbeat: snap to the reported position, refresh velocity/tuning, reset staleness. */
    public void update(MissileFlightAudioPacket pkt) {
        applyUpdate(pkt);
    }

    private void applyUpdate(MissileFlightAudioPacket pkt) {
        this.curX = pkt.x();
        this.curY = pkt.y();
        this.curZ = pkt.z();
        this.velX = pkt.vx();
        this.velY = pkt.vy();
        this.velZ = pkt.vz();
        this.range = pkt.range();
        this.basePitch = pkt.basePitch();
        this.speedPitch = pkt.speedPitch();
        this.ticksSinceUpdate = 0;
    }

    public boolean isDone() {
        return this.isStopped();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.stop();
            return;
        }
        this.ticksSinceUpdate++;
        if (this.ticksSinceUpdate >= MissileFlightAudioPacket.CLIENT_TIMEOUT) {
            this.stop();
            return;
        }

        // Dead-reckon between heartbeats so the source keeps moving smoothly.
        this.curX += this.velX;
        this.curY += this.velY;
        this.curZ += this.velZ;
        this.x = this.curX;
        this.y = this.curY;
        this.z = this.curZ;

        Entity listener = mc.getCameraEntity();
        if (listener == null) {
            return;
        }
        double lx = listener.getX();
        double ly = listener.getEyeY();
        double lz = listener.getZ();

        double ux = lx - this.curX;
        double uy = ly - this.curY;
        double uz = lz - this.curZ;
        double dist = Math.sqrt(ux * ux + uy * uy + uz * uz);

        // Volume: linear fade to silence at `range`, plus a short fade once heartbeats go stale (so a missile
        // that detonates or leaves range fades out over a few ticks instead of cutting abruptly).
        float distFade = (float) Mth.clamp(1.0 - dist / this.range, 0.0, 1.0);
        this.volume = MAX_VOLUME * distFade * stalenessFade();

        // Engine pitch: idle base plus a rev proportional to the missile's OWN speed (speedPitch); 0 keeps it
        // constant (the loitering-drone exception). Then multiplied by the closing-motion Doppler factor.
        double ownSpeed = Math.sqrt(this.velX * this.velX + this.velY * this.velY + this.velZ * this.velZ);
        double enginePitch = this.basePitch + this.speedPitch * ownSpeed;

        if (!this.primed) {
            this.prevListenerX = lx;
            this.prevListenerY = ly;
            this.prevListenerZ = lz;
            this.primed = true;
            this.pitch = (float) Mth.clamp(enginePitch, MIN_PITCH, MAX_PITCH);
            return;
        }

        double lvx = lx - this.prevListenerX;
        double lvy = ly - this.prevListenerY;
        double lvz = lz - this.prevListenerZ;
        double doppler = 1.0;
        if (dist > 1.0E-4) {
            double inv = 1.0 / dist;
            double dirX = ux * inv;
            double dirY = uy * inv;
            double dirZ = uz * inv;
            double vSource = this.velX * dirX + this.velY * dirY + this.velZ * dirZ;
            double vListener = lvx * dirX + lvy * dirY + lvz * dirZ;
            double denom = SPEED_OF_SOUND - vSource;
            if (Math.abs(denom) > 1.0E-3) {
                doppler = (SPEED_OF_SOUND - vListener) / denom;
            }
        }
        this.pitch = (float) Mth.clamp(enginePitch * doppler, MIN_PITCH, MAX_PITCH);

        this.prevListenerX = lx;
        this.prevListenerY = ly;
        this.prevListenerZ = lz;
    }

    private float stalenessFade() {
        int fresh = MissileFlightAudioPacket.UPDATE_INTERVAL + 1;
        if (this.ticksSinceUpdate <= fresh) {
            return 1.0F;
        }
        int span = MissileFlightAudioPacket.CLIENT_TIMEOUT - fresh;
        return (float) Mth.clamp((double) (MissileFlightAudioPacket.CLIENT_TIMEOUT - this.ticksSinceUpdate) / span,
                0.0, 1.0);
    }
}
