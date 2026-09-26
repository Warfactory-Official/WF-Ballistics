package com.wf.wflib.tv.client;

import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraTarget;
import com.wf.wflib.client.cam.FeedBlit;
import com.wf.wflib.drone.cam.CameraFeed;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The seeker picture: full view letterboxed, or picture-in-picture top right. */
final class TvOverlay implements LayeredDraw.Layer {

    private static final int MARGIN = 6;
    /** PIP width, share of the screen. */
    private static final float PIP_SHARE = 0.34f;
    private static final int TEXT = 0xFF6BFF7D;
    private static final int DIM = 0xFF4A6A55;
    private static final int ALERT = 0xFFAA2222;

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        int id = TvClient.missileId();
        if (id == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int w;
        int h;
        int x;
        int y;
        if (TvClient.full()) {
            w = sw;
            h = Math.round(w / (float) CameraTarget.ASPECT);
            if (h > sh) {
                h = sh;
                w = Math.round(h * (float) CameraTarget.ASPECT);
            }
            x = (sw - w) / 2;
            y = (sh - h) / 2;
            g.fill(0, 0, sw, sh, 0xFF000000);
        } else {
            w = Math.round(sw * PIP_SHARE);
            h = Math.round(w / (float) CameraTarget.ASPECT);
            x = sw - w - MARGIN;
            y = MARGIN;
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF101418);
        }
        CameraFeedCache.request(id, (int) Math.round(w * mc.getWindow().getGuiScale()), 0.0);

        ResourceLocation texture = CameraFeedCache.texture(id);
        CameraFeed feed = CameraFeedCache.feed(id);
        g.flush();
        if (!TvClient.lost() && texture != null && CameraFeedCache.drawable(id)) {
            FeedBlit.draw(texture, g.pose().last().pose(), x, y, x + w, y + h);
        } else {
            g.fill(x, y, x + w, y + h, 0xFF05080A);
            String reason = TvClient.lost() ? "wflib.tv.lost"
                    : feed == null ? "wflib.tv.linking"
                    : !feed.usable() ? "wflib.tv.range"
                    : "wflib.tv.terrain";
            g.drawCenteredString(mc.font, Component.translatable(reason), x + w / 2, y + h / 2 - 4,
                    TvClient.lost() ? ALERT : TEXT);
        }

        Component hint = Component.translatable("wflib.tv.hint", TvKeys.LINK.getTranslatedKeyMessage(),
                TvKeys.VIEW.getTranslatedKeyMessage());
        int hy = TvClient.full() ? y + h - 12 : y + h + 3;
        g.drawString(mc.font, hint, TvClient.full() ? x + 8 : x + w - mc.font.width(hint), hy, DIM, false);
    }
}
