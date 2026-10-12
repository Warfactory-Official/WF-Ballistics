package com.wf.wflib.tv.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraTarget;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiHud;
import com.wf.wflib.ui.UiInstance;
import com.wf.wflib.ui.UiLayouts;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

import static com.wf.wflib.ui.Ui.*;

/**
 * The seeker picture on the HUD, layout {@code wflib:tv}: full view letterboxed, or picture-in-picture top
 * right (34% of the width). Subject {@code tv} exists while a TV round is linked.
 */
public final class TvHud {
    public static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tv");
    public static final Subject<TvHud> TV = Subject.register("tv", TvHud.class, TvHud::active);

    private static final TvHud INSTANCE = new TvHud();
    private static final String TEXT = "#ff6bff7d";
    private static final String DIM = "#ff4a6a55";
    private static final String ALERT = "#ffaa2222";

    private static UiInstance instance;
    private static String hint = "";
    private static long hintAt;

    private TvHud() {
    }

    private static TvHud active() {
        return TvClient.missileId() == 0 ? null : INSTANCE;
    }

    /** Key names re-read once a second: a rebind shows up, a frame does not allocate. */
    private static String hint() {
        long now = System.currentTimeMillis();
        if (now - hintAt > 1000) {
            hintAt = now;
            hint = net.minecraft.network.chat.Component.translatable("wflib.tv.hint",
                    TvKeys.LINK.getTranslatedKeyMessage(), TvKeys.VIEW.getTranslatedKeyMessage()).getString();
        }
        return hint;
    }

    private static String reason() {
        int id = TvClient.missileId();
        CameraFeed feed = CameraFeedCache.feed(id);
        String key = TvClient.lost() ? "wflib.tv.lost" : feed == null ? "wflib.tv.linking"
                : !feed.usable() ? "wflib.tv.range" : "wflib.tv.terrain";
        return Language.getInstance().getOrDefault(key);
    }

    public static void register() {
        Signals.num(TV, "missile", t -> TvClient.missileId());
        Signals.bool(TV, "full", t -> TvClient.full());
        Signals.bool(TV, "lost", t -> TvClient.lost());
        Signals.bool(TV, "picture", t -> !TvClient.lost() && CameraFeedCache.texture(TvClient.missileId()) != null
                && CameraFeedCache.drawable(TvClient.missileId()));
        Signals.text(TV, "reason", 64, t -> reason());
        Signals.text(TV, "hint", 96, t -> hint());
        double aspect = CameraTarget.ASPECT;
        String w = "(tv.full ? min(ui.width, round(ui.height * " + aspect + ")) : round(ui.width * 0.34))";
        String h = "round(" + w + " / " + aspect + ")";
        String x = "(tv.full ? floor((ui.width - " + w + ") / 2) : ui.width - " + w + " - 6)";
        String y = "(tv.full ? floor((ui.height - " + h + ") / 2) : 6)";
        UiLayouts.register(layout(LAYOUT, when("tv.present", stack(
                when("tv.full", rect("ui.width", "ui.height", "#ff000000"),
                        at(x + " - 1", y + " - 1", rect("(" + w + ") + 2", "(" + h + ") + 2", "#ff101418"))),
                at(x, y, new Sized(w, h, Point.TOP_LEFT, false, stack(
                        when("!tv.picture", stack(rect(w, h, "#ff05080a"),
                                place("floor((" + w + ") / 2)", "(" + h + ") / 2 - 4", Point.TOP,
                                        shadowed("{tv.reason}", "tv.lost ? " + ALERT + " : " + TEXT)))),
                        new Feed("tv.lost ? 0 : tv.missile", w, h)))),
                when("tv.full", at(x + " + 8", y + " + (" + h + ") - 12", text("{tv.hint}", DIM)),
                        place(x + " + (" + w + ")", y + " + (" + h + ") + 3", Point.TOP_RIGHT, text("{tv.hint}", DIM)))))));
        UiHud.addSource(UiHud.Layer.MAIN, out -> {
            if (TvClient.missileId() == 0 || Minecraft.getInstance().player == null) {
                return;
            }
            if (instance == null || !instance.current()) {
                instance = UiLayouts.get(LAYOUT).instantiate();
            }
            out.add(instance);
        });
    }
}
