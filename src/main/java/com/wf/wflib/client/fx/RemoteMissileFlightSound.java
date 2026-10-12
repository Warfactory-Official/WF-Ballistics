package com.wf.wflib.client.fx;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import com.wf.wflib.network.MissileFlightAudioPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Flight loop driven by {@link MissileFlightAudioPacket} heartbeats, dead-reckoned between them. */
public final class RemoteMissileFlightSound extends Emitter {
    private static final float VOLUME = 0.9F;

    private double curX, curY, curZ;
    private double velX, velY, velZ;
    private float basePitch;
    private double speedPitch;
    private int ticksSinceUpdate;

    public RemoteMissileFlightSound(MissileFlightAudioPacket pkt) {
        super(new SoundSpec(pkt.sound(), VOLUME, 1, 0, pkt.range(), 0), AudioCategory.WEAPONS, RandomSource.create(),
                true);
        applyUpdate(pkt);
        refresh();
    }

    public void update(MissileFlightAudioPacket pkt) {
        applyUpdate(pkt);
    }

    private void applyUpdate(MissileFlightAudioPacket pkt) {
        curX = pkt.x();
        curY = pkt.y();
        curZ = pkt.z();
        velX = pkt.vx();
        velY = pkt.vy();
        velZ = pkt.vz();
        basePitch = pkt.basePitch();
        speedPitch = pkt.speedPitch();
        ticksSinceUpdate = 0;
        setVelocity(velX, velY, velZ);
    }

    public boolean isDone() {
        return isStopped();
    }

    @Override
    protected Vec3 locate() {
        return ticksSinceUpdate >= MissileFlightAudioPacket.CLIENT_TIMEOUT ? null : new Vec3(curX, curY, curZ);
    }

    @Override
    public void tick() {
        ticksSinceUpdate++;
        curX += velX;
        curY += velY;
        curZ += velZ;
        double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        setPitchScale((float) Mth.clamp(basePitch + speedPitch * speed, 0.5, 2.0));
        setLevel(stalenessFade());
        super.tick();
    }

    private float stalenessFade() {
        int fresh = MissileFlightAudioPacket.UPDATE_INTERVAL + 1;
        if (ticksSinceUpdate <= fresh) {
            return 1;
        }
        int span = MissileFlightAudioPacket.CLIENT_TIMEOUT - fresh;
        return Mth.clamp((float) (MissileFlightAudioPacket.CLIENT_TIMEOUT - ticksSinceUpdate) / span, 0, 1);
    }
}
