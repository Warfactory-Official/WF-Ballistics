package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;


@Mod.EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class ClientSoundScheduler {

    public static final double SPEED_OF_SOUND = 8.575;

    private static final List<Pending> PENDING = new ArrayList<>();

    private ClientSoundScheduler() { }

    public static int soundDelay(double distance) {
        return (int) (distance / SPEED_OF_SOUND);
    }

    public static void playDelayed(double x, double y, double z, SoundEvent sound, SoundSource source,
                                   float volume, float pitch, int delayTicks) {
        if (delayTicks <= 0) {
            play(x, y, z, sound, source, volume, pitch);
        } else {
            PENDING.add(new Pending(delayTicks, x, y, z, sound, source, volume, pitch));
        }
    }

    private static void play(double x, double y, double z, SoundEvent sound, SoundSource source,
                             float volume, float pitch) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.playLocalSound(x, y, z, sound, source, volume, pitch, false);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null) {
            return;
        }
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Pending p = PENDING.get(i);
            if (--p.ticksLeft <= 0) {
                play(p.x, p.y, p.z, p.sound, p.source, p.volume, p.pitch);
                PENDING.remove(i);
            }
        }
    }

    private static final class Pending {
        int ticksLeft;
        final double x, y, z;
        final SoundEvent sound;
        final SoundSource source;
        final float volume, pitch;

        Pending(int ticksLeft, double x, double y, double z, SoundEvent sound, SoundSource source,
                float volume, float pitch) {
            this.ticksLeft = ticksLeft;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sound = sound;
            this.source = source;
            this.volume = volume;
            this.pitch = pitch;
        }
    }
}
