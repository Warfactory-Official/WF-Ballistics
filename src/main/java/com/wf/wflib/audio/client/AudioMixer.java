package com.wf.wflib.audio.client;

import com.mojang.blaze3d.audio.Channel;
import com.wf.wflib.WFLib;
import com.wf.wflib.audio.Acoustics;
import com.wf.wflib.mixin.AccessorChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-source OpenAL state the engine lacks: direct low-pass = {@link Emitter} air absorption x cabin muffle
 * ({@code LF 1 - 0.65m}, {@code HF 1 - 0.85m}, {@code m = cabin x category share}; non-wflib world sounds share 1,
 * relative 0), Doppler = {@link Emitter} velocity - camera velocity, c = {@link Acoustics#C_PER_TICK} (listener
 * velocity stays 0). Hearing loss {@code h} ({@link Hearing}, max over providers) on every sound but UI and
 * {@link Cue#ignoresHearing}: {@code LF x (1 - 0.9h)}, {@code HF x (1 - 0.98h)}. Values computed on the client thread, written on the sound thread; at channel start
 * (NeoForge play events) and every tick after vanilla's own updates ({@code MixinSoundEngine}).
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class AudioMixer {
    /** Cabin muffle slew per tick: 4-tick crossfade. */
    private static final float SLEW = 0.25F;
    private static final List<Cabin> CABINS = new CopyOnWriteArrayList<>();
    private static final List<Hearing> HEARING = new CopyOnWriteArrayList<>();

    /** Muffle 0..1 of the camera's enclosure; max over providers. */
    public interface Cabin {
        float muffle(Minecraft mc);
    }

    /** Listener hearing loss 0..1 (flashbang); max over providers, providers own their decay. */
    public interface Hearing {
        float loss(Minecraft mc);
    }

    private static volatile float cabin;
    private static float cabinPrev;
    private static volatile float hearing;
    private static float hearingPrev;
    private static volatile float lvx, lvy, lvz;
    private static Vec3 lastEar;
    private static int filter;
    /** Harness, last tick: playing emitters; channels with a non-unity low-pass. */
    public static volatile int emitters, filtered;

    private AudioMixer() {
    }

    public static void addCabin(Cabin c) {
        CABINS.add(c);
    }

    public static void addHearing(Hearing h) {
        HEARING.add(h);
    }

    public static float hearing() {
        return hearing;
    }

    /** Current cabin muffle (after slew and the config strength). */
    public static float cabin() {
        return cabin;
    }

    /** End of {@code SoundEngine.loadLibrary}: fresh context. */
    public static void libraryLoaded() {
        AL10.alDopplerFactor(1);
        AL11.alSpeedOfSound(Acoustics.C_PER_TICK);
        filter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
    }

    /** Client thread, end of {@code SoundEngine.tickNonPaused}. */
    public static void afterTick(Map<SoundInstance, ChannelAccess.ChannelHandle> channels) {
        float m = cabin;
        float h = hearing;
        boolean sweep = m > 0 || cabinPrev > 0 || h > 0 || hearingPrev > 0;
        cabinPrev = m;
        hearingPrev = h;
        int live = 0, muffled = 0;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> e : channels.entrySet()) {
            SoundInstance s = e.getKey();
            if (sweep || s instanceof Emitter) {
                Params p = params(s, m, h);
                live += s instanceof Emitter ? 1 : 0;
                muffled += p.low < 1 || p.high < 1 ? 1 : 0;
                e.getValue().execute(ch -> apply(ch, p));
            }
        }
        emitters = live;
        filtered = muffled;
    }

    record Params(float low, float high, boolean moving, float vx, float vy, float vz) {
    }

    private static Params params(SoundInstance s, float m, float h) {
        if (s instanceof Emitter e) {
            float share = m * e.muffle();
            return new Params(e.low * (1 - 0.65F * share) * (1 - 0.9F * h),
                    e.high * (1 - 0.85F * share) * (1 - 0.98F * h), true, e.vx - lvx, e.vy - lvy, e.vz - lvz);
        }
        float share = s.isRelative() ? 0 : m;
        boolean exempt = s instanceof Cue c ? c.ignoresHearing()
                : s.isRelative() && s.getSource() == net.minecraft.sounds.SoundSource.MASTER;
        float loss = exempt ? 0 : h;
        return new Params((1 - 0.65F * share) * (1 - 0.9F * loss), (1 - 0.85F * share) * (1 - 0.98F * loss), false,
                0, 0, 0);
    }

    /** Sound thread. */
    private static void apply(Channel ch, Params p) {
        int source = ((AccessorChannel) ch).wflib$source();
        if (p.low >= 1 && p.high >= 1) {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        } else {
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, p.low);
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, p.high);
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
        }
        if (p.moving) {
            AL10.alSource3f(source, AL10.AL_VELOCITY, p.vx, p.vy, p.vz);
        }
    }

    @SubscribeEvent
    public static void onPlay(PlaySoundSourceEvent e) {
        apply(e.getChannel(), params(e.getSound(), cabin, hearing));
    }

    @SubscribeEvent
    public static void onPlayStreaming(PlayStreamingSourceEvent e) {
        apply(e.getChannel(), params(e.getSound(), cabin, hearing));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        AudioOptions.refresh();
        SoundScheduler.tick(mc.isPaused());
        if (mc.level == null) {
            cabin = 0;
            hearing = 0;
            lastEar = null;
            return;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        if (lastEar != null && ear.distanceToSqr(lastEar) < 400) {
            lvx = (float) (ear.x - lastEar.x);
            lvy = (float) (ear.y - lastEar.y);
            lvz = (float) (ear.z - lastEar.z);
        } else {
            lvx = lvy = lvz = 0;
        }
        lastEar = ear;
        float target = 0;
        for (Cabin c : CABINS) {
            target = Math.max(target, c.muffle(mc));
        }
        target *= AudioOptions.cabinMuffle();
        float now = cabin;
        cabin = now + Math.clamp(target - now, -SLEW, SLEW);
        float loss = 0;
        for (Hearing h : HEARING) {
            loss = Math.max(loss, h.loss(mc));
        }
        hearing = Math.clamp(loss, 0, 1);
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre e) {
        SoundScheduler.frame(e.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        SoundScheduler.reset();
    }
}
