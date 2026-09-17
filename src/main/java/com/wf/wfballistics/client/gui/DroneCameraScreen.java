package com.wf.wfballistics.client.gui;

import com.wf.wfballistics.client.cam.CameraFeedCache;
import com.wf.wfballistics.client.cam.CameraLink;
import com.wf.wfballistics.client.cam.CameraTarget;
import com.wf.wfballistics.client.cam.FeedBlit;
import com.wf.wfballistics.drone.cam.CameraFeed;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** A camera feed, full size, with the gimbal on the mouse. */
public class DroneCameraScreen extends Screen {

    private static final int PAD = 8;
    /** Room under the video for the readout. Two text rows plus the gap above them. */
    private static final int STRIP = 40;
    /** Extra room when there is a channel row to draw as well. */
    private static final int CHANNEL_ROW = 14;
    /** Degrees of gimbal per pixel dragged. Tuned so a full sweep of a 1080p window is most of a turn. */
    private static final double DRAG_GAIN = 0.22;

    private int[] feedIds;
    private int channel;

    private int left;
    private int top;
    private int videoW;
    private int videoH;
    private int strip;

    public DroneCameraScreen(int feedId) {
        this(new int[]{feedId}, 0);
    }

    public DroneCameraScreen(int[] feedIds, int selected) {
        super(Component.literal("Camera"));
        this.feedIds = feedIds.length == 0 ? new int[]{0} : feedIds;
        this.channel = Mth.clamp(selected, 0, this.feedIds.length - 1);
    }

    private int feedId() {
        return this.feedIds[this.channel];
    }

    private boolean multi() {
        return this.feedIds.length > 1;
    }

    @Override
    protected void init() {
        this.strip = STRIP + (multi() ? CHANNEL_ROW : 0);
        int roomW = this.width - PAD * 2;
        int roomH = this.height - PAD * 2 - this.strip;
        this.videoW = Math.min(roomW, (int) Math.round(roomH * CameraTarget.ASPECT));
        this.videoH = (int) Math.round(this.videoW / CameraTarget.ASPECT);
        this.left = (this.width - this.videoW) / 2;
        this.top = (this.height - this.videoH - this.strip) / 2;
        CameraLink.open(feedId());
    }

    /** Show a different channel. */
    private void select(int index) {
        int next = Math.floorMod(index, this.feedIds.length);
        if (next == this.channel) {
            return;
        }
        this.channel = next;
        CameraLink.open(feedId());
    }

    /** A refreshed channel list from the server, while the screen stays open. */
    public void updatePanel(int[] ids) {
        if (ids.length == 0) {
            return;
        }
        boolean wasMulti = multi();
        int before = feedId();
        this.feedIds = ids;
        this.channel = Math.min(this.channel, ids.length - 1);
        if (wasMulti != multi()) {
            // The channel row appearing or disappearing changes how much room the video gets.
            init();
        }
        if (feedId() != before) {
            CameraLink.open(feedId());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        CameraLink.close();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        double guiScale = this.minecraft.getWindow().getGuiScale();
        int feedId = feedId();
        CameraFeedCache.request(feedId, (int) Math.round(this.videoW * guiScale), 0.0);

        g.fill(left - 2, top - 2, left + videoW + 2, top + videoH + 2, 0xFF101418);
        ResourceLocation texture = CameraFeedCache.texture(feedId);
        CameraFeed feed = CameraFeedCache.feed(feedId);
        if (texture != null && CameraFeedCache.drawable(feedId)) {
            FeedBlit.draw(texture, g.pose().last().pose(), left, top, left + videoW, top + videoH);
        } else {
            noSignal(g, feed);
        }
        readout(g, feed);
        if (multi()) {
            channels(g);
        }
    }

    private void noSignal(GuiGraphics g, CameraFeed feed) {
        g.fill(left, top, left + videoW, top + videoH, 0xFF05080A);
        boolean noTerrain = feed != null && feed.usable() && !CameraFeedCache.hasTerrain(feed);
        String reason = feedId() == 0 ? "CHANNEL EMPTY"
                : feed == null ? "NO FEED"
                : feed.battery() <= 0.0f ? "DRONE POWER LOSS"
                : feed.link() <= 0.05f ? "OUT OF DATALINK RANGE"
                : noTerrain ? "NO TERRAIN DATA"
                : "SIGNAL LOSS";
        g.drawCenteredString(this.font, Component.literal(reason).withStyle(ChatFormatting.DARK_RED),
                left + videoW / 2, top + videoH / 2 - 4, 0xFFAA2222);
        if (noTerrain) {
            g.drawCenteredString(this.font, Component.literal(
                            "camera is beyond this client's render distance"),
                    left + videoW / 2, top + videoH / 2 + 8, 0xFF775544);
        }
    }

    private void readout(GuiGraphics g, CameraFeed feed) {
        int y = top + videoH + 6;
        g.fill(left, y, left + videoW, y + STRIP - 10, 0xC0060A08);
        if (feed == null) {
            g.drawString(this.font, Component.literal("awaiting feed").withStyle(ChatFormatting.DARK_GRAY),
                    left + 6, y + 6, 0xFF555555, false);
            return;
        }
        String line = feed.feedId() < 0
                ? String.format(Locale.ROOT, "CAM-%04X  %s  zoom x%.1f  fov %.0f  link %3.0f%%  MAINS",
                feed.feedId() & 0xFFFF, feed.modeValue().name(), CameraLink.zoom(), feed.fov(),
                feed.link() * 100.0f)
                : String.format(Locale.ROOT,
                        "CAM-%04X  %s  zoom x%.1f  fov %.0f  link %3.0f%%  batt %3.0f%%",
                        feed.feedId() & 0xFFFF, feed.modeValue().name(), CameraLink.zoom(), feed.fov(),
                        feed.link() * 100.0f, feed.battery() * 100.0f);
        g.drawString(this.font, line, left + 6, y + 6, 0xFF6BFF7D, false);
        String help = multi()
                ? "drag to slew   scroll to zoom   M sensor   1-9 or arrows switch camera"
                : "drag to slew   scroll to zoom   M cycles sensor";
        g.drawString(this.font, Component.literal(help).withStyle(ChatFormatting.DARK_GRAY),
                left + 6, y + 16, 0xFF4A6A55, false);
    }

    /** The channel row: one cell per bound camera, the live one lit, the selected one boxed. */
    private void channels(GuiGraphics g) {
        int y = top + videoH + 6 + STRIP - 8;
        int cell = Math.max(18, Math.min(34, videoW / Math.max(1, this.feedIds.length)));
        for (int i = 0; i < this.feedIds.length; i++) {
            int x = left + 6 + i * (cell + 2);
            if (x + cell > left + videoW) {
                break;
            }
            boolean live = this.feedIds[i] != 0;
            boolean here = i == this.channel;
            g.fill(x, y, x + cell, y + 12, here ? 0xFF1E3A28 : 0xC00A1410);
            if (here) {
                g.fill(x, y, x + cell, y + 1, 0xFF6BFF7D);
            }
            g.drawCenteredString(this.font, String.valueOf(i + 1), x + cell / 2, y + 2,
                    live ? (here ? 0xFF9BFFAD : 0xFF5A8F68) : 0xFF8A3A3A);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            CameraLink.pan(dragX * DRAG_GAIN / Math.max(1.0, CameraLink.zoom()),
                    dragY * DRAG_GAIN / Math.max(1.0, CameraLink.zoom()));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0) {
            CameraLink.zoomBy(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_M) {
            CameraLink.cycleMode();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            CameraLink.pan(-CameraLink.yaw(), -CameraLink.pitch());
            return true;
        }
        if (multi()) {
            if (key == GLFW.GLFW_KEY_LEFT) {
                select(this.channel - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_RIGHT) {
                select(this.channel + 1);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                int wanted = key - GLFW.GLFW_KEY_1;
                if (wanted < this.feedIds.length) {
                    select(wanted);
                }
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /**
     * @return where the video sits, for anything that wants to draw over it later.
     */
    public int videoWidth() {
        return Mth.clamp(this.videoW, 0, this.width);
    }
}
