package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.client.cam.CameraTarget;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiHud;
import com.wf.wflib.ui.UiInstance;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.resources.ResourceLocation;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.layout;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.shadowed;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.when;

/**
 * A PDA feed on the whole game screen ({@link PdaClient#fullscreen}): letterboxed, mouse pans the linked gimbal,
 * wheel zooms; use / Esc returns to the PDA.
 */
final class PdaFullscreen {
    static final Subject<PdaFullscreen> FULL = Subject.register("pda_full", PdaFullscreen.class, PdaFullscreen::active);
    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_full");
    private static final PdaFullscreen INSTANCE = new PdaFullscreen();
    /** {@code LocalPlayer.turn} units -> degrees at zoom 1 (= TV link). */
    static final double TURN_SCALE = 0.15;

    private static UiInstance instance;

    private PdaFullscreen() {
    }

    private static PdaFullscreen active() {
        return PdaClient.fullscreenFeed() == 0 ? null : INSTANCE;
    }

    static void register() {
        Signals.num(FULL, "feed", f -> PdaClient.fullscreenFeed());
        Signals.bool(FULL, "picture", f -> CameraFeedCache.drawable(PdaClient.fullscreenFeed()));
        Signals.num(FULL, "zoom", f -> CameraLink.zoom());
        double aspect = CameraTarget.ASPECT;
        String w = "min(ui.width, round(ui.height * " + aspect + "))";
        String h = "round(" + w + " / " + aspect + ")";
        String x = "floor((ui.width - " + w + ") / 2)";
        String y = "floor((ui.height - " + h + ") / 2)";
        UiLayout full = layout(LAYOUT, when("pda_full.present", stack(
                rect("ui.width", "ui.height", "#ff000000"),
                at(x, y, new Sized(w, h, Point.TOP_LEFT, false, new Feed("pda_full.feed", w, h))),
                when("!pda_full.picture", place("ui.width / 2", "ui.height / 2 - 4", Point.TOP,
                        shadowed("NO SIGNAL", PdaShell.DIM))),
                place("ui.width / 2", "ui.height - 14", Point.TOP, shadowed(
                        "x{pda_full.zoom|.1}  scroll zoom  [{key:key.use}] / Esc",
                        PdaShell.TEXT)))));
        UiHud.addSource(UiHud.Layer.MAIN, out -> {
            if (PdaClient.fullscreenFeed() == 0) {
                return;
            }
            if (instance == null) {
                instance = full.instantiate();
            }
            out.add(instance);
        });
    }

    static void steer(double yaw, double pitch) {
        double gain = TURN_SCALE / Math.max(1.0, CameraLink.zoom());
        CameraLink.pan(yaw * gain, pitch * gain);
    }
}
