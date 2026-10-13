package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.pda.PdaCamerasRequestPacket;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiDrag;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.drag;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Held PDA's camera channels (server-resolved feed ids, {@code CameraPanelPacket}): list left, the selected feed
 * right; drag pans, wheel zooms. Owns {@link CameraLink} while open unless a TV round took it.
 */
public final class CamerasApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "cameras");
    static final Subject<CamerasApp> APP = Subject.register("pda_cams", CamerasApp.class, () -> CamerasApp.instance);
    static final Subject<Integer> CHANNEL = Subject.register("pda_cam", Integer.class);
    /** Drag units -> degrees at zoom 1. */
    private static final double DRAG_GAIN = 0.35;

    private static final CamerasApp instance = new CamerasApp();
    private static final List<Integer> CHANNELS = new ArrayList<>();
    private static int selected = -1;
    private static boolean open;

    private CamerasApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> CHANNELS.size());
        Signals.items(APP, "channels", a -> CHANNELS, CHANNEL);
        Signals.num(APP, "feed", a -> current());
        Signals.bool(APP, "picture", a -> current() != 0 && CameraFeedCache.drawable(current()));
        Signals.num(APP, "zoom", a -> CameraLink.zoom());
        Signals.text(APP, "mode", 12, a -> CameraLink.mode().name());
        Signals.num(APP, "link", a -> feedValue(current(), true));
        Signals.num(APP, "battery", a -> feedValue(current(), false));
        Signals.bool(CHANNEL, "selected", id -> selected >= 0 && selected < CHANNELS.size()
                && CHANNELS.get(selected).equals(id));
        Signals.bool(CHANNEL, "live", id -> id != 0 && CameraFeedCache.live(id));
        Signals.text(CHANNEL, "kind", 8, CamerasApp::kind);
        PdaApps.register(instance);
    }

    /** Server answer: feed id per channel, 0 = unresolved. */
    public static void accept(int[] feedIds, int selectedChannel) {
        CHANNELS.clear();
        for (int id : feedIds) {
            CHANNELS.add(id);
        }
        if (selected < 0 || selected >= CHANNELS.size()) {
            selected = CHANNELS.isEmpty() ? -1 : Math.min(Math.max(selectedChannel, 0), CHANNELS.size() - 1);
        }
        link();
    }

    private static int current() {
        return CameraLink.active() && selected >= 0 && selected < CHANNELS.size()
                && CameraLink.feedId() == CHANNELS.get(selected) ? CameraLink.feedId() : 0;
    }

    private static double feedValue(int id, boolean link) {
        CameraFeed feed = id == 0 ? null : CameraFeedCache.feed(id);
        return feed == null ? 0 : Math.round((link ? feed.link() : feed.battery()) * 100);
    }

    private static String kind(Integer id) {
        CameraFeed feed = id == 0 ? null : CameraFeedCache.feed(id);
        if (feed == null) {
            return "----";
        }
        return switch (feed.kind()) {
            case CameraFeed.DRONE -> "DRONE";
            case CameraFeed.FIXED -> "FIXED";
            case CameraFeed.GUIDED -> "GUIDED";
            default -> "MOUNTED";
        };
    }

    /** Open app => link the selected channel's feed. */
    private static void link() {
        if (!open || selected < 0 || selected >= CHANNELS.size()) {
            return;
        }
        int id = CHANNELS.get(selected);
        if (id != 0 && !(CameraLink.active() && CameraLink.feedId() == id)) {
            CameraLink.open(id);
        }
    }

    private static void select(Object item) {
        selected = CHANNELS.indexOf(item);
        link();
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.cameras");
    }

    @Override
    public boolean visible(LocalPlayer player) {
        return PdaClient.held();
    }

    @Override
    public void opened() {
        open = true;
        PacketDistributor.sendToServer(new PdaCamerasRequestPacket());
        link();
    }

    @Override
    public void closed() {
        open = false;
        if (current() != 0) {
            CameraLink.close();
        }
    }

    @Override
    public WidgetDef build() {
        float listW = 112;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(CamerasApp::select, "true", "pda_cam.selected ? " + PdaShell.PRESS + " : 0",
                PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0, rowW, 20, Point.TOP_LEFT,
                new Sized(Float.toString(rowW), "20", Point.TOP_LEFT, false, stack(
                        at("5", "6", text("CH {index + 1}", "pda_cam.live ? " + PdaShell.ACCENT + " : "
                                + PdaShell.DIM)),
                        place(Float.toString(rowW - 6), "6", Point.TOP_RIGHT, text("{pda_cam.kind}", PdaShell.DIM)))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        float feedW = viewW - 8;
        float feedH = (float) Math.floor(feedW * 9 / 16);
        String fw = Float.toString(feedW);
        String fh = Float.toString(feedH);
        UiDrag pan = new UiDrag() {
            @Override
            public void drag(Object item, float dx, float dy) {
                if (current() != 0) {
                    double gain = DRAG_GAIN / Math.max(1.0, CameraLink.zoom());
                    CameraLink.pan(dx * gain, dy * gain);
                }
            }

            @Override
            public boolean wheel(Object item, float amount) {
                if (current() == 0) {
                    return false;
                }
                CameraLink.zoomBy(amount);
                return true;
            }
        };
        float buttonsY = 24 + feedH + 8;
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("14", "13", text("CHANNELS  {pda_cams.count}", PdaShell.DIM)),
                at("12", "28", list("pda_cams.channels", 20, 1, Float.toString(rowW), Float.toString(h - 24),
                        PdaShell.EDGE, row)),
                when("pda_cams.count == 0", stack(at("14", "34", text("NO CAMERAS", PdaShell.DIM)),
                        at("14", "46", text("SNEAK+USE A MONITOR", PdaShell.DIM)))),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                at(Float.toString(viewX + 6), "13", text("{pda_cams.mode}  x{pda_cams.zoom|.1}  LINK {pda_cams.link}%"
                        + "  BAT {pda_cams.battery}%", PdaShell.DIM)),
                at(Float.toString(viewX + 4), "24", drag(fw, fh, pan, stack(
                        rect(fw, fh, "#ff101210"),
                        new Feed("pda_cams.feed", fw, fh),
                        when("!pda_cams.picture", place(Float.toString(feedW / 2), Float.toString(feedH / 2 - 4),
                                Point.TOP, text("NO SIGNAL", PdaShell.DIM)))))),
                at(Float.toString(viewX + 4), Float.toString(buttonsY), new Button(item -> CameraLink.cycleMode(),
                        "pda_cams.feed != 0", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE, 1, 0, 8,
                        4, 0, 0, Point.CENTER, text("MODE", PdaShell.TEXT))),
                at(Float.toString(viewX + 60), Float.toString(buttonsY), new Button(
                        item -> CameraLink.pan(-CameraLink.yaw(), -CameraLink.pitch()), "pda_cams.feed != 0",
                        PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE, 1, 0, 8, 4, 0, 0, Point.CENTER,
                        text("CENTRE", PdaShell.TEXT))),
                at(Float.toString(viewX + viewW - 50), Float.toString(buttonsY), new Button(
                        item -> PdaClient.fullscreen(current()), "pda_cams.feed != 0", PdaShell.PANEL, PdaShell.HOVER,
                        PdaShell.PRESS, PdaShell.EDGE, 1, 0, 8, 4, 0, 0, Point.CENTER, text("FULL", PdaShell.TEXT))));
    }
}
