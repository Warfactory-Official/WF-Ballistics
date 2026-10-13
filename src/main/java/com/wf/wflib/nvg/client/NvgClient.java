package com.wf.wflib.nvg.client;

import com.wf.gemrender.light.Lights;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.FeedPass;
import com.wf.wflib.nvg.NvgActionPacket;
import com.wf.wflib.nvg.NvgServer;
import com.wf.wflib.nvg.NvgWorn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Local player's goggle: keys, flip timeline (predicted; state from the server), tube power-up. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class NvgClient {
    /** Ticks from photocathode on to full gain. */
    static final int WARM_TICKS = 10;
    /** Night-sky irradiance, GemRender light units: moonless, and added at full moon. */
    static final double STARLIGHT = 0.004, MOONLIGHT = 0.03;
    /** Merged sensor passes write night sky into 8-bit colour: lifted above quantisation; their chains amplify. */
    static final double MERGED_SCALE = 25.0;

    private static long flipStart = Long.MIN_VALUE;
    private static boolean wasDown;
    private static long downAt;
    private static long now;

    private NvgClient() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            wasDown = false;
            return;
        }
        now = mc.level.getGameTime();
        boolean down = NvgWorn.down(player);
        if (down && !wasDown) {
            downAt = now;
        }
        wasDown = down;
        boolean worn = !NvgWorn.nvg(player).isEmpty();
        while (NvgKeys.FLIP.consumeClick()) {
            if (worn && flip(0.0f) < 0.0f) {
                flipStart = now;
                PacketDistributor.sendToServer(new NvgActionPacket(NvgActionPacket.Action.FLIP));
            }
        }
        while (NvgKeys.IR.consumeClick()) {
            if (worn) {
                PacketDistributor.sendToServer(new NvgActionPacket(NvgActionPacket.Action.IR));
            }
        }
        while (NvgKeys.GAIN_UP.consumeClick()) {
            if (worn) {
                PacketDistributor.sendToServer(new NvgActionPacket(NvgActionPacket.Action.GAIN_UP));
            }
        }
        while (NvgKeys.GAIN_DOWN.consumeClick()) {
            if (worn) {
                PacketDistributor.sendToServer(new NvgActionPacket(NvgActionPacket.Action.GAIN_DOWN));
            }
        }
    }

    /** Flip progress {@code [0, 1)}, or -1 when idle. */
    public static float flip(float partialTick) {
        float t = (now - flipStart + partialTick) / NvgServer.FLIP_TICKS;
        return t >= 0.0f && t < 1.0f ? t : -1.0f;
    }

    /** Hand weight at the mount: smoothstep up to the swap tick, back down after. */
    public static float reach(float partialTick) {
        float t = flip(partialTick);
        if (t < 0.0f) {
            return 0.0f;
        }
        float swap = (float) NvgServer.SWAP_TICK / NvgServer.FLIP_TICKS;
        float x = t < swap ? t / swap : (1.0f - t) / (1.0f - swap);
        return x * x * (3.0f - 2.0f * x);
    }

    /** Goggle over the eyes, first person. */
    public static boolean viewing() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && wasDown && mc.options.getCameraType().isFirstPerson() && !mc.player.isSpectator();
    }

    /** Tube output {@code [0, 1]}: power-up ramp after the goggle came down. */
    public static float warm(float partialTick) {
        return Mth.clamp((now - downAt + partialTick) / WARM_TICKS, 0.0f, 1.0f);
    }

    public static ItemStack worn() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : NvgWorn.nvg(player);
    }

    /**
     * {@link Lights#nightSky} source: starlight floor + moon by phase, faded in with darkness, dimmed by rain; MERGED x
     * {@link #MERGED_SCALE}. Skyless dimensions 0.
     */
    public static double nightSky(Lights.IrView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimensionType().hasSkyLight()) {
            return 0.0;
        }
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double night = Mth.clamp((1.0 - mc.level.getSkyDarken(partial)) / 0.8, 0.0, 1.0);
        double clouds = 1.0 - 0.6 * mc.level.getRainLevel(partial);
        return night * clouds * (STARLIGHT + MOONLIGHT * mc.level.getMoonBrightness())
                * (view == Lights.IrView.MERGED ? MERGED_SCALE : 1.0);
    }

    /** {@link Lights#irView} source. */
    public static Lights.IrView irView() {
        if (FeedPass.active()) {
            return FeedPass.amplifying() ? Lights.IrView.MERGED : Lights.IrView.OFF;
        }
        if (com.wf.wflib.client.cam.ScreenSensor.amplifying()) {
            return Lights.IrView.MERGED;
        }
        return viewing() ? Lights.IrView.SPLIT : Lights.IrView.OFF;
    }
}
