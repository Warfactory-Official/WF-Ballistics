package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.CameraMode;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiInstance;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.UiLayouts;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.resources.ResourceLocation;

import static com.wf.wflib.ui.Ui.*;

/**
 * The overlay burned into a feed: battery, mode, attitude, link. Layout {@code wflib:camera_osd} over a
 * 320 x 180 unit space; subject {@code feed} = the feed being drawn.
 */
public final class CameraOsd {
    public static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "camera_osd");
    public static final Subject<CameraFeed> FEED = Subject.register("feed", CameraFeed.class);

    /** 16:9, small enough that vanilla's font reads as a HUD. */
    private static final int OSD_W = 320;
    private static final int OSD_H = 180;

    private static final String TINT = "feed.thermal ? #ffffc24a : #ff6bff7d";
    private static final String RED = "#ffff5a3c";
    private static final String AMBER = "#ffffc24a";

    private CameraOsd() {
    }

    public static void register() {
        Signals.num(FEED, "id", CameraFeed::feedId);
        Signals.bool(FEED, "thermal", f -> f.modeValue() == CameraMode.THERMAL);
        Signals.bool(FEED, "guided", f -> f.kind() == CameraFeed.GUIDED);
        Signals.bool(FEED, "fixed", f -> f.kind() == CameraFeed.FIXED);
        Signals.text(FEED, "mode", 16, f -> f.modeValue().name());
        Signals.num(FEED, "altitude", CameraFeed::altitude);
        Signals.num(FEED, "speed", CameraFeed::speed);
        Signals.num(FEED, "battery", f -> Math.max(0.0f, Math.min(1.0f, f.battery())));
        Signals.num(FEED, "link", f -> Math.max(0.0f, Math.min(1.0f, f.link())));
        Signals.num(FEED, "yaw", FeedGimbal::yaw);
        Signals.num(FEED, "pitch", FeedGimbal::pitch);
        Signals.bool(FEED, "usable", CameraFeed::usable);
        UiLayouts.register(osdLayout());
    }

    static void draw(CameraTarget target, CameraFeed feed) {
        if (target.osd == null) {
            target.osd = UiLayouts.get(LAYOUT).instantiate();
        }
        target.osd.context(subject -> subject == FEED ? feed : null);
        RenderTarget fbo = target.target();
        Canvas canvas = Canvas.shared().begin(fbo.frameBufferId, fbo.width, fbo.height, OSD_W, OSD_H, true);
        try {
            target.osd.render(canvas, 0, 0, OSD_W, OSD_H, 0);
        } finally {
            canvas.end();
        }
    }

    private static UiLayout osdLayout() {
        String battery = "feed.battery < 0.1 ? " + RED + " : feed.battery < 0.25 ? " + AMBER + " : " + TINT;
        String bars = "round(feed.link * 5)";
        String link = bars + " <= 1 ? " + RED + " : " + bars + " <= 2 ? " + AMBER + " : #ff6bff7d";
        WidgetDef[] linkBars = new WidgetDef[5];
        for (int i = 0; i < 5; i++) {
            int h = 2 + i * 2;
            linkBars[i] = at(Integer.toString(OSD_W - 30 + i * 4), Integer.toString(OSD_H - 30 - h),
                    rect(3, h, bars + " > " + i + " ? (" + link + ") : #ff3a6b44"));
        }
        return layout(LAYOUT, stack(
                bracket(6, 6, 1, 1), bracket(OSD_W - 7, 6, -1, 1), bracket(6, OSD_H - 7, 1, -1),
                bracket(OSD_W - 7, OSD_H - 7, -1, -1),
                at("10", "10", when("feed.guided", text("TV-{mod(feed.id, 65536)|04X}  {feed.mode}", TINT),
                        text("CAM-{mod(feed.id, 65536)|04X}  {feed.mode}", TINT))),
                place(Integer.toString(OSD_W - 10), "10", Point.TOP_RIGHT,
                        text("{world.hour|02}:{world.minute|02}", TINT)),
                when("feed.fixed", at("10", Integer.toString(OSD_H - 24), text("MAINS", TINT)), stack(
                        at("10", Integer.toString(OSD_H - 34), text("AGL {feed.altitude|5}", TINT)),
                        at("10", Integer.toString(OSD_H - 24), text("SPD {feed.speed|5.1}", TINT)),
                        at("10", Integer.toString(OSD_H - 14), stack(
                                rect(46, 6, "#80000000"),
                                new com.wf.wflib.ui.widget.Rect("46", "6", battery, 0, 1),
                                at("1", "1", rect("44 * feed.battery", "4", battery)),
                                at("50", "-1", text("{feed.battery * 100|3}%", battery)),
                                at("72", "-1", when("feed.guided", text("MTR", battery),
                                        when("feed.battery < 0.1", text("RTB", RED)))))))),
                place(Integer.toString(OSD_W - 10), Integer.toString(OSD_H - 24), Point.TOP_RIGHT,
                        text("{mod(feed.yaw + 360, 360)|03} / {feed.pitch|+03}", TINT)),
                stack(linkBars),
                when(bars + " <= 1", place(Integer.toString(OSD_W / 2), Integer.toString(OSD_H / 2 - 30), Point.TOP,
                        text("LOW SIGNAL", RED))),
                at(Integer.toString(OSD_W / 2), Integer.toString(OSD_H / 2), stack(
                        at("-8", "0", rect(6, 1, TINT)), at("3", "0", rect(6, 1, TINT)),
                        at("0", "-8", rect(1, 6, TINT)), at("0", "3", rect(1, 6, TINT))))));
    }

    /** One corner mark, twelve units along each axis from {@code (x, y)} in the direction given. */
    private static WidgetDef bracket(int x, int y, int dx, int dy) {
        int left = dx > 0 ? x : x - 11;
        int top = dy > 0 ? y : y - 11;
        return stack(
                at(Integer.toString(left), Integer.toString(y), rect(12, 1, TINT)),
                at(Integer.toString(x), Integer.toString(top), rect(1, 12, TINT)));
    }
}
