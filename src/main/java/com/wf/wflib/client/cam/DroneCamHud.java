package com.wf.wflib.client.cam;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.Datalink;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiHud;
import com.wf.wflib.ui.UiInstance;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.UiLayouts;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.column;
import static com.wf.wflib.ui.Ui.layout;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.shadowed;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.when;

/**
 * Remote-body view overlay, screen space, one look piloted ({@link #pilot}) and watched full screen ({@link #watch}):
 * link meter, LOW SIGNAL, SIGNAL LOST card. Watched adds name, mode, AGL / speed / battery, gimbal, cross; piloted
 * leaves those to the host mod's flight HUD. Watched drone/mounted feeds skip the burned-in {@link CameraOsd}.
 */
public final class DroneCamHud {
    public static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "drone_cam");
    static final Subject<DroneCamHud> CAM = Subject.register("drone_cam", DroneCamHud.class, DroneCamHud::active);
    private static final DroneCamHud INSTANCE = new DroneCamHud();
    /** Client ticks a pilot report stays live. */
    private static final int PILOT_TTL = 2;

    private static final String GREEN = "#dd00ff00";
    private static final String DIM = "#9966ff99";
    private static final String AMBER = "#ddffaa00";
    private static final String RED = "#ddff0000";

    private static float pilotLink;
    @Nullable
    private static KeyMapping pilotRelease;
    @Nullable
    private static InputConstants.Key releaseKey;
    private static String releaseName = "";
    private static boolean pilotFreeLook;
    private static long pilotUntil = Long.MIN_VALUE;
    private static int watched;
    @Nullable
    private static String title;
    private static UiInstance instance;

    private DroneCamHud() {
    }

    /**
     * Client tick while piloting a remote body; stops showing {@link #PILOT_TTL} ticks after the last call.
     * {@code release}: the host's leave binding, shown on SIGNAL LOST. {@code freeLook}: mouse turns the camera only.
     */
    public static void pilot(float link, KeyMapping release, boolean freeLook) {
        pilotLink = link;
        pilotFreeLook = freeLook;
        if (release != pilotRelease || release.getKey() != releaseKey) {
            pilotRelease = release;
            releaseKey = release.getKey();
            releaseName = release.getTranslatedKeyMessage().getString();
        }
        pilotUntil = gameTime() + PILOT_TTL;
    }

    /** Full-screen feed, 0 = none; {@code name} null => feed id. */
    public static void watch(int feedId, @Nullable String name) {
        watched = feedId;
        title = name;
    }

    /** Burned-in OSD replaced by this overlay. */
    static boolean covers(CameraFeed feed) {
        return feed.feedId() == watched && remote(feed);
    }

    private static boolean remote(CameraFeed feed) {
        return feed.kind() == CameraFeed.DRONE || feed.kind() == CameraFeed.MOUNTED;
    }

    private static long gameTime() {
        var level = Minecraft.getInstance().level;
        return level == null ? Long.MIN_VALUE : level.getGameTime();
    }

    @Nullable
    private static CameraFeed watchedFeed() {
        CameraFeed feed = watched == 0 ? null : CameraFeedCache.feed(watched);
        return feed != null && remote(feed) ? feed : null;
    }

    @Nullable
    private static DroneCamHud active() {
        return watchedFeed() != null || gameTime() <= pilotUntil ? INSTANCE : null;
    }

    private static float link() {
        CameraFeed feed = watchedFeed();
        return feed == null ? pilotLink : feed.link();
    }

    private static boolean lost() {
        CameraFeed feed = watchedFeed();
        return feed == null ? !Datalink.usable(pilotLink) : !CameraFeedCache.drawable(watched) || !feed.usable();
    }

    public static void register() {
        Signals.num(CAM, "link", c -> Math.max(0.0f, Math.min(1.0f, link())));
        Signals.bool(CAM, "lost", c -> lost());
        Signals.bool(CAM, "watched", c -> watchedFeed() != null);
        Signals.num(CAM, "id", c -> watched);
        Signals.bool(CAM, "named", c -> title != null);
        Signals.text(CAM, "title", 24, c -> title == null ? "" : title);
        Signals.text(CAM, "release", 16, c -> releaseName);
        Signals.bool(CAM, "free", c -> pilotFreeLook && watchedFeed() == null);
        Signals.text(CAM, "mode", 16, c -> watchedFeed() == null ? "" : watchedFeed().modeValue().name());
        Signals.num(CAM, "altitude", c -> watchedFeed() == null ? 0.0f : watchedFeed().altitude());
        Signals.num(CAM, "speed", c -> watchedFeed() == null ? 0.0f : watchedFeed().speed());
        Signals.num(CAM, "battery", c -> watchedFeed() == null ? 0.0f
                : Math.max(0.0f, Math.min(1.0f, watchedFeed().battery())));
        Signals.num(CAM, "yaw", c -> watchedFeed() == null ? 0.0f : FeedGimbal.yaw(watchedFeed()));
        Signals.num(CAM, "pitch", c -> watchedFeed() == null ? 0.0f : FeedGimbal.pitch(watchedFeed()));
        UiLayouts.register(hudLayout());
    }

    /** After every HUD source it must draw over. */
    public static void addToHud() {
        UiLayout hud = UiLayouts.get(LAYOUT);
        UiHud.addSource(UiHud.Layer.MAIN, out -> {
            if (active() == null) {
                return;
            }
            if (instance == null) {
                instance = hud.instantiate();
            }
            out.add(instance);
        });
    }

    private static UiLayout hudLayout() {
        String bars = "round(drone_cam.link * 5)";
        String linkColor = bars + " <= 1 ? " + RED + " : " + bars + " <= 2 ? " + AMBER + " : " + GREEN;
        WidgetDef[] meter = new WidgetDef[6];
        meter[0] = at("0", "0", shadowed("LNK", GREEN));
        for (int i = 0; i < 5; i++) {
            int h = 2 + i * 2;
            meter[i + 1] = at(Integer.toString(22 + i * 4), Integer.toString(10 - h),
                    rect(3, h, bars + " > " + i + " ? (" + linkColor + ") : #66003311"));
        }
        String battery = "drone_cam.battery < 0.1 ? " + RED + " : drone_cam.battery < 0.25 ? " + AMBER + " : " + GREEN;
        WidgetDef watchedInfo = when("drone_cam.watched", stack(
                at("8", "8", when("drone_cam.named", shadowed("{drone_cam.title}  {drone_cam.mode}", GREEN),
                        shadowed("CAM-{mod(drone_cam.id, 65536)|04X}  {drone_cam.mode}", GREEN))),
                at("8", "ui.height - 44", column(2,
                        shadowed("AGL {drone_cam.altitude|5}", GREEN),
                        shadowed("SPD {drone_cam.speed|5.1}", GREEN),
                        shadowed("BAT {drone_cam.battery * 100|3}%", battery))),
                place("ui.width - 8", "ui.height - 24", Point.TOP_RIGHT,
                        shadowed("{mod(drone_cam.yaw + 360, 360)|03} / {drone_cam.pitch|+03}", GREEN)),
                at("floor(ui.width / 2)", "floor(ui.height / 2)", stack(
                        at("-8", "0", rect(6, 1, GREEN)), at("3", "0", rect(6, 1, GREEN)),
                        at("0", "-8", rect(1, 6, GREEN)), at("0", "3", rect(1, 6, GREEN))))));
        return layout(LAYOUT, when("drone_cam.present", when("drone_cam.lost", stack(
                        rect("ui.width", "ui.height", "#ff050805"),
                        place("ui.width / 2", "ui.height / 2 - 10", Point.TOP, shadowed("SIGNAL LOST", RED)),
                        place("ui.width / 2", "ui.height / 2 + 2", Point.TOP,
                                shadowed("LNK {drone_cam.link * 100|3}%", DIM)),
                        when("!drone_cam.watched", place("ui.width / 2", "ui.height / 2 + 14", Point.TOP,
                                shadowed("[{drone_cam.release}] disconnect", DIM)))),
                stack(
                        at("ui.width - 50", "8", stack(meter)),
                        when(bars + " <= 1", place("ui.width / 2", "ui.height / 2 - 40", Point.TOP,
                                shadowed("LOW SIGNAL", RED))),
                        when("drone_cam.free", place("ui.width / 2", "ui.height / 2 + 40", Point.TOP,
                                shadowed("FREE LOOK", AMBER))),
                        watchedInfo))));
    }
}
