package com.wf.wflib.probe.client;

import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeJobPacket;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/** Progress of the player's channelled action, under the crosshair; the reason it stopped, briefly. */
public final class ProbeJobOverlay implements LayeredDraw.Layer {

    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 4;
    private static final int BELOW_CROSSHAIR = 14;
    private static final int NOTICE_TICKS = 40;
    static final net.minecraft.resources.ResourceLocation STOP =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.wf.wflib.WFLib.MODID, "stop");

    @Nullable
    private static ProbeAction pending;
    @Nullable
    private static Component label;
    private static long startTick;
    private static int total;
    @Nullable
    private static Component notice;
    private static long noticeUntil;

    /** The row G just sent; its label titles the bar if the server starts it. */
    static void sent(ProbeAction action) {
        pending = action;
    }

    public static boolean running() {
        return label != null;
    }

    public static void accept(ProbeJobPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        if (packet.ticks() > 0) {
            label = pending != null && pending.id().equals(packet.action()) && pending.arg() == packet.arg()
                    ? pending.label() : Component.translatable("probe.wflib.job.working");
            startTick = now;
            total = packet.ticks();
            notice = null;
        } else {
            label = null;
            if (packet.ticks() < 0) {
                notice = packet.message();
                noticeUntil = now + NOTICE_TICKS;
            }
        }
        pending = null;
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2 + BELOW_CROSSHAIR;
        long now = mc.level.getGameTime();
        if (label != null) {
            float t = Mth.clamp((now - startTick + delta.getGameTimeDeltaPartialTick(false)) / total, 0, 1);
            graphics.drawCenteredString(font, label, cx, y, 0xFFFFFFFF);
            int x0 = cx - BAR_WIDTH / 2;
            int by = y + font.lineHeight + 2;
            graphics.fill(x0 - 1, by - 1, x0 + BAR_WIDTH + 1, by + BAR_HEIGHT + 1, 0xC0101014);
            graphics.fill(x0, by, x0 + Math.round(BAR_WIDTH * t), by + BAR_HEIGHT, 0xFFE0E0E0);
            float left = Math.max(0, total * (1 - t)) / 20.0f;
            graphics.drawCenteredString(font, Component.translatable("probe.wflib.job.remaining",
                    String.format(java.util.Locale.ROOT, "%.1f", left)), cx, by + BAR_HEIGHT + 3, 0xFFA0A0A0);
        } else if (notice != null && now < noticeUntil) {
            graphics.drawCenteredString(font, notice, cx, y, 0xFFFF5555);
        }
    }
}
