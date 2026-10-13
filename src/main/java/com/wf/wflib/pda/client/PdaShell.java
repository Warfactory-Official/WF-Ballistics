package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Box;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.grid;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * PDA screen, absolute units {@link #WIDTH} x {@link #HEIGHT}: top bar (back, title, clock), home grid of
 * {@link PdaApps} or the open app below it, cursor last. One instance; apps bound once.
 */
public final class PdaShell {
    public static final float WIDTH = 384;
    public static final float HEIGHT = 240;
    public static final float BAR = 18;
    public static final float APP_WIDTH = WIDTH;
    public static final float APP_HEIGHT = HEIGHT - BAR;

    /** STALKER-panel palette, ARGB expressions; apps reuse it. */
    public static final String BACK = "#ff3a3e3a";
    public static final String BAR_BACK = "#ff2c2f2c";
    public static final String PANEL = "#ff454a45";
    public static final String HOVER = "#ff545b53";
    public static final String PRESS = "#ff626b5f";
    public static final String EDGE = "#ff787f76";
    public static final String TEXT = "#ffd2d6cc";
    public static final String DIM = "#ff8d9488";
    public static final String ACCENT = "#ffa9d46c";
    public static final String ALERT = "#ffd0584a";
    static final int CLEAR = 0xFF3A3E3A;
    private static final int CURSOR = 0xFFA9D46C;
    private static final int CURSOR_EDGE = 0xFF141614;

    public static final Subject<PdaShell> PDA = Subject.register("pda", PdaShell.class, PdaShell::instance);
    public static final Subject<PdaApp> APP = Subject.register("pda_app", PdaApp.class);

    private static final PdaShell INSTANCE = new PdaShell();
    private static final Map<PdaApp, String> TITLES = new IdentityHashMap<>();
    private static UiLayout layout;

    private PdaShell() {
    }

    private static PdaShell instance() {
        return INSTANCE;
    }

    static void register() {
        Signals.text(PDA, "title", 32, s -> PdaClient.app() == null ? "PDA" : title(PdaClient.app()));
        Signals.bool(PDA, "home", s -> PdaClient.app() == null);
        Signals.bool(PDA, "focused", s -> PdaClient.focused());
        Signals.items(PDA, "apps", s -> PdaApps.visible(), APP);
        Signals.text(APP, "title", 24, PdaShell::title);
        Signals.num(APP, "badge", PdaApp::badge);
    }

    /** App panel: {@link #PANEL} fill, 1-unit {@link #EDGE} border. */
    public static WidgetDef panel(float w, float h) {
        return new Box(PANEL, EDGE, 1, 0, 0, 0, w, h, Point.TOP_LEFT, null);
    }

    private static String title(PdaApp app) {
        return TITLES.computeIfAbsent(app, a -> a.title().getString());
    }

    /** Built on first draw: every app registered by then. */
    static UiLayout layout() {
        if (layout == null) {
            List<PdaApp> apps = PdaApps.seal();
            List<WidgetDef> roots = new ArrayList<>(apps.size());
            for (PdaApp app : apps) {
                roots.add(app.build());
            }
            layout = new UiLayout(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda"), stack(
                    rect(WIDTH, HEIGHT, BACK),
                    rect(WIDTH, BAR, BAR_BACK),
                    at("0", Float.toString(BAR - 1), rect(WIDTH, 1, EDGE)),
                    when("pda.home", at("8", "5", text("{pda.title}", TEXT)),
                            stack(at("2", "2", new Button(item -> PdaClient.home(), "true", "0", HOVER, PRESS, EDGE,
                                            1, 0, 6, 2, 0, 13, Point.CENTER, text("< HOME", TEXT))),
                                    at("64", "5", text("{pda.title}", TEXT)))),
                    place(Float.toString(WIDTH - 8), "5", Point.TOP_RIGHT,
                            text("{world.hour|02}:{world.minute|02}", TEXT)),
                    at("0", Float.toString(BAR), new AppHost(apps, roots, home())),
                    new Cursor()), 0);
        }
        return layout;
    }

    private static WidgetDef home() {
        float gap = 8;
        float margin = 12;
        float w = (float) Math.floor((WIDTH - margin * 2 - gap * 3) / 4);
        float h = (float) Math.floor((APP_HEIGHT - margin * 2 - gap * 2) / 3);
        WidgetDef tile = new Button(item -> PdaClient.open((PdaApp) item), "true", PANEL, HOVER, PRESS, EDGE, 1, 0,
                0, 0, w, h, Point.TOP_LEFT, new Sized(Float.toString(w), Float.toString(h), Point.TOP_LEFT, false, stack(
                place(Float.toString(w / 2), Float.toString(h / 2 - 4), Point.TOP, text("{pda_app.title}", TEXT)),
                when("pda_app.badge > 0", place(Float.toString(w - 5), "5", Point.TOP_RIGHT,
                        text("{pda_app.badge}", ALERT))))));
        return at(Float.toString(margin), Float.toString(margin), grid("pda.apps", 4, gap, tile));
    }

    /** Open app's root, else the home grid; {@link #APP_WIDTH} x {@link #APP_HEIGHT}. */
    private record AppHost(List<PdaApp> apps, List<WidgetDef> roots, WidgetDef home) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            Widget homeNode = binder.child(home);
            Widget[] nodes = new Widget[roots.size()];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = binder.child(roots.get(i));
            }
            return new Widget() {
                private Widget active;

                @Override
                public void measure(UiEval ev) {
                    int index = apps.indexOf(PdaClient.app());
                    active = index < 0 ? homeNode : nodes[index];
                    active.measure(ev);
                    width = APP_WIDTH;
                    height = APP_HEIGHT;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    active.draw(ev, x, y, APP_WIDTH, APP_HEIGHT);
                }
            };
        }
    }

    /** Arrow at the cursor while focused. */
    private record Cursor() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = 0;
                    height = 0;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    if (!PdaClient.focused()) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    float cx = PdaClient.cursorX();
                    float cy = PdaClient.cursorY();
                    canvas.triangle(cx - 1, cy - 2, cx - 1, cy + 14, cx + 10, cy + 10, CURSOR_EDGE);
                    canvas.triangle(cx, cy, cx, cy + 11, cx + 7.5f, cy + 8.5f, CURSOR);
                }
            };
        }
    }
}
