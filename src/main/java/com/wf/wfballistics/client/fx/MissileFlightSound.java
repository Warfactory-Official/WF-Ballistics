package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

public final class MissileFlightSound extends AbstractTickableSoundInstance {

    private static final float MAX_VOLUME = 0.9F;
    private static final double HEARING_RANGE = 160.0;
    private static final float BASE_PITCH = 1.0F;
    private static final double SPEED_OF_SOUND = ClientSoundScheduler.SPEED_OF_SOUND;
    private static final float MIN_PITCH = 0.5F;
    private static final float MAX_PITCH = 2.0F;

    private final MissileEntity missile;

    private double prevSourceX, prevSourceY, prevSourceZ;
    private double prevListenerX, prevListenerY, prevListenerZ;
    private boolean primed;

    public MissileFlightSound(MissileEntity missile) {
        super(WFSounds.MISSILE_FLIGHT.get(), SoundSource.HOSTILE, RandomSource.create());
        this.missile = missile;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = MAX_VOLUME;
        this.pitch = BASE_PITCH;
        this.x = missile.getX();
        this.y = missile.getY();
        this.z = missile.getZ();
    }

    @Override
    public void tick() {
        if (!this.missile.isAlive() || this.missile.isRemoved()) {
            this.stop();
            return;
        }

        double sx = this.missile.getX();
        double sy = this.missile.getY();
        double sz = this.missile.getZ();
        this.x = sx;
        this.y = sy;
        this.z = sz;

        Entity listener = Minecraft.getInstance().getCameraEntity();
        if (listener == null) {
            return;
        }
        double lx = listener.getX();
        double ly = listener.getEyeY();
        double lz = listener.getZ();

        double ux = lx - sx;
        double uy = ly - sy;
        double uz = lz - sz;
        double dist = Math.sqrt(ux * ux + uy * uy + uz * uz);
        this.volume = (float) (MAX_VOLUME * Mth.clamp(1.0 - dist / HEARING_RANGE, 0.0, 1.0));

        if (!this.primed) {
            this.prevSourceX = sx;
            this.prevSourceY = sy;
            this.prevSourceZ = sz;
            this.prevListenerX = lx;
            this.prevListenerY = ly;
            this.prevListenerZ = lz;
            this.primed = true;
            return;
        }

        double svx = sx - this.prevSourceX;
        double svy = sy - this.prevSourceY;
        double svz = sz - this.prevSourceZ;
        double lvx = lx - this.prevListenerX;
        double lvy = ly - this.prevListenerY;
        double lvz = lz - this.prevListenerZ;

        if (dist > 1.0E-4) {
            double inv = 1.0 / dist;
            double dirX = ux * inv;
            double dirY = uy * inv;
            double dirZ = uz * inv;
            double vSource = svx * dirX + svy * dirY + svz * dirZ;
            double vListener = lvx * dirX + lvy * dirY + lvz * dirZ;
            double denom = SPEED_OF_SOUND - vSource;
            if (Math.abs(denom) > 1.0E-3) {
                double factor = (SPEED_OF_SOUND - vListener) / denom;
                this.pitch = (float) Mth.clamp(BASE_PITCH * factor, MIN_PITCH, MAX_PITCH);
            }
        }

        this.prevSourceX = sx;
        this.prevSourceY = sy;
        this.prevSourceZ = sz;
        this.prevListenerX = lx;
        this.prevListenerY = ly;
        this.prevListenerZ = lz;
    }
}
