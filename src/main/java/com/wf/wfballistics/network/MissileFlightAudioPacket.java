package com.wf.wfballistics.network;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.client.ClientPacketHandler;
import com.wf.wfballistics.sim.SimMissile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;


public record MissileFlightAudioPacket(UUID id, double x, double y, double z,
                                       float vx, float vy, float vz,
                                       ResourceLocation sound, float range,
                                       float basePitch, float speedPitch) {

    /** Ticks between position/audio heartbeats for one missile. */
    public static final int UPDATE_INTERVAL = 3;
    /** Ticks without a heartbeat before the client stops a loop (must exceed {@link #UPDATE_INTERVAL}). */
    public static final int CLIENT_TIMEOUT = 8;

    public static void encode(MissileFlightAudioPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.id);
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        buf.writeFloat(p.vx);
        buf.writeFloat(p.vy);
        buf.writeFloat(p.vz);
        buf.writeResourceLocation(p.sound);
        buf.writeFloat(p.range);
        buf.writeFloat(p.basePitch);
        buf.writeFloat(p.speedPitch);
    }

    public static MissileFlightAudioPacket decode(FriendlyByteBuf buf) {
        return new MissileFlightAudioPacket(buf.readUUID(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readResourceLocation(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(MissileFlightAudioPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleMissileAudio(p)));
        ctx.get().setPacketHandled(true);
    }


    /** Broadcast a live missile entity's flight audio to players within its range. Server only. */
    public static void broadcastEntity(MissileEntity m) {
        if (m.level().isClientSide) {
            return;
        }
        SoundEvent sound = m.getFlightSound();
        double range = m.getFlightSoundRange();
        Vec3 v = m.getDeltaMovement();
        MissileFlightAudioPacket p = new MissileFlightAudioPacket(m.getUUID(),
                m.getX(), m.getY(), m.getZ(),
                (float) v.x, (float) v.y, (float) v.z,
                sound.getLocation(), (float) range,
                m.getFlightSoundBasePitch(), (float) m.getFlightSoundSpeedPitch());
        WFNetwork.sendToAllAround(m.level(), m.getX(), m.getY(), m.getZ(), range, p);
    }

    /** Broadcast an off-world simulated missile's flight audio to players within its range. {@code vel} is
     *  the missile's per-tick velocity (blocks/tick), used for Doppler + engine-rev on the client. */
    public static void broadcastSim(ServerLevel level, SimMissile sm, Vec3 vel) {
        ResourceLocation sound = sm.flightSoundId != null ? sm.flightSoundId : WFSounds.MISSILE_FLIGHT.getId();
        MissileFlightAudioPacket p = new MissileFlightAudioPacket(sm.id,
                sm.pos.x, sm.pos.y, sm.pos.z,
                (float) vel.x, (float) vel.y, (float) vel.z,
                sound, (float) sm.flightSoundRange,
                sm.flightSoundBasePitch, (float) sm.flightSoundSpeedPitch);
        WFNetwork.sendToAllAround(level, sm.pos.x, sm.pos.y, sm.pos.z, sm.flightSoundRange, p);
    }
}
