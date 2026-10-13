package com.wf.wflib.grenade.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Cue;
import com.wf.wflib.client.fx.WFLights;
import com.wf.wflib.grenade.FlashPacket;
import com.wf.wflib.grenade.Grenades;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Flashbang on the receiving client. Post chain after the hand, before the GUI: veil + dazzle ({@code White}),
 * afterimage of the frame at the flash ({@code After}). Hearing loss via {@code AudioMixer.Hearing} + ringing.
 * Times ms; {@code I} = packet strength.
 * <pre>
 * white: min(1, 1.4 I) for 500 + 2500 I, then (1-t)^2 over 3000 I
 * after: 0.9 I, (1-t)^2 over 3000 + 6000 I
 * deaf:  D for 1000 + 3000 D, then linear over 2000 + 6000 D
 * </pre>
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class Flashbang {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CHAIN = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
            "shaders/post/wf_flashbang.json");

    private static float whitePeak;
    private static long whiteStart;
    private static long whiteHold;
    private static long whiteFade;
    private static float afterPeak;
    private static long afterLife;
    private static boolean capture;

    private static float deafPeak;
    private static long deafStart;
    private static long deafHold;
    private static long deafFade;
    @Nullable
    private static Cue ring;

    @Nullable
    private static PostChain chain;
    private static boolean broken;
    private static int width;
    private static int height;

    private Flashbang() {
    }

    public static void receive(FlashPacket pkt) {
        WFLights.flash(pkt.x(), pkt.y(), pkt.z(), 28, 0xFFFFFF, 900, 3);
        long now = Util.getMillis();
        if (pkt.flash() > 0.02f && pkt.flash() >= white(now)) {
            float i = pkt.flash();
            whitePeak = Math.min(1f, 1.4f * i);
            whiteStart = now;
            whiteHold = (long) (500 + 2500 * i);
            whiteFade = (long) (3000 * i) + 1;
            afterPeak = 0.9f * i;
            afterLife = (long) (3000 + 6000 * i);
            capture = true;
        }
        if (pkt.deaf() > 0.05f && pkt.deaf() >= deaf(now)) {
            float d = pkt.deaf();
            deafPeak = d;
            deafStart = now;
            deafHold = (long) (1000 + 3000 * d);
            deafFade = (long) (2000 + 6000 * d);
            if (ring != null) {
                ring.fadeOut(0);
            }
            ring = new Cue(new SoundSpec(Grenades.TINNITUS.getId(), Math.min(1f, 0.7f * d), 1, 0, 16, 0),
                    AudioCategory.ALERTS, true).ignoreHearing();
            ring.fadeOut((int) ((deafHold + deafFade) / 50));
            Minecraft.getInstance().getSoundManager().play(ring);
        }
    }

    static float white(long now) {
        long t = now - whiteStart;
        if (whitePeak <= 0 || t < 0) {
            return 0;
        }
        if (t < whiteHold) {
            return whitePeak;
        }
        float f = 1f - (float) (t - whiteHold) / whiteFade;
        return f <= 0 ? 0 : whitePeak * f * f;
    }

    static float after(long now) {
        long t = now - whiteStart;
        if (afterPeak <= 0 || t < 0) {
            return 0;
        }
        float f = 1f - (float) t / afterLife;
        return f <= 0 ? 0 : afterPeak * f * f;
    }

    static float deaf(long now) {
        long t = now - deafStart;
        if (deafPeak <= 0 || t < 0) {
            return 0;
        }
        if (t < deafHold) {
            return deafPeak;
        }
        float f = 1f - (float) (t - deafHold) / deafFade;
        return f <= 0 ? 0 : deafPeak * f;
    }

    /** {@code AudioMixer.Hearing}. */
    public static float hearingLoss(Minecraft mc) {
        return deaf(Util.getMillis());
    }

    /** Harness. */
    public static float[] state() {
        long now = Util.getMillis();
        return new float[]{white(now), after(now), deaf(now)};
    }

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Pre event) {
        long now = Util.getMillis();
        float w = white(now);
        float a = after(now);
        if (w <= 0 && a <= 0 && !capture) {
            close();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (!ensure(mc, main)) {
            return;
        }
        chain.setUniform("Capture", capture ? 1f : 0f);
        chain.setUniform("White", w);
        chain.setUniform("After", a);
        capture = false;
        chain.process(event.getPartialTick().getGameTimeDeltaPartialTick(true));
        main.bindWrite(true);
    }

    private static boolean ensure(Minecraft mc, RenderTarget main) {
        if (broken) {
            return false;
        }
        if (chain == null) {
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
                width = main.width;
                height = main.height;
                chain.resize(width, height);
                capture = true;
            } catch (Exception e) {
                broken = true;
                LOGGER.error("flashbang: no usable post chain ({})", e.toString());
                return false;
            }
        } else if (main.width != width || main.height != height) {
            width = main.width;
            height = main.height;
            chain.resize(width, height);
            capture = true;
        }
        return true;
    }

    private static void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        whitePeak = 0;
        afterPeak = 0;
        deafPeak = 0;
        capture = false;
        if (ring != null) {
            ring.fadeOut(0);
            ring = null;
        }
        close();
    }
}
