package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.pda.drone.DroneActionPacket;
import com.wf.wflib.pda.drone.DroneDispatchPacket;
import com.wf.wflib.pda.drone.DroneStatusPacket;
import com.wf.wflib.pda.drone.DroneStatusRequestPacket;
import com.wf.wflib.pda.drone.PdaDrones;
import com.wf.wflib.pda.drone.RemoteBody;
import com.wf.wflib.pda.markers.Marker;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiDrag;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.drag;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * PDA Drones app, every linked body (wflib drone, other mods' UAVs): left list; right FEED (feed, link, fuel,
 * systems, task, ammo; CONNECT / REMOVE / FULL) or DISPATCH (selected autonomous body, else nearest pad).
 * Server state polled while open.
 */
public final class DronesApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "drones");
    private static final int REFRESH_TICKS = 10;
    /** Drag units -> degrees at zoom 1. */
    private static final double DRAG_GAIN = 0.35;

    private static final DronesApp INSTANCE = new DronesApp();
    static final Subject<DronesApp> APP = Subject.register("pda_drones", DronesApp.class, () -> INSTANCE);
    static final Subject<Row> ROW = Subject.register("pda_drone", Row.class);
    static final Subject<Row> SELECTED = Subject.register("pda_dronesel", Row.class, DronesApp::current);

    private static final List<Row> ROWS = new ArrayList<>();
    private static final List<PdaLog.Line> REPLIES = new ArrayList<>();
    private static final int REPLY_LINES = 40;
    private static final DroneTask.Kind[] KINDS = {DroneTask.Kind.MOVE_TO, DroneTask.Kind.LOITER,
            DroneTask.Kind.STRIKE, DroneTask.Kind.DELIVER, DroneTask.Kind.MINELAY};
    private static final boolean[] OFFERED = new boolean[KINDS.length];
    private static DroneStatusPacket.Pad pad;
    private static String route = "";
    private static boolean dispatchTab;
    private static int kind;
    /** 0 = own position, n = n-th marker in this dimension. */
    private static int target;
    private static String targetLabel = "HERE";
    private static int selected;
    private static boolean open;
    private static int ticks;

    /** Strings resolved once per server update. */
    record Row(DroneStatusPacket.Entry entry, String name, String detail, String info, String ammo) {
        static Row of(DroneStatusPacket.Entry e) {
            String name = e.name().toUpperCase(Locale.ROOT);
            if (!e.loaded()) {
                return new Row(e, name, e.status(), "", "");
            }
            String info = String.format(Locale.ROOT, "LINK %d%%   %s %d%%   %s", Math.round(e.link() * 100),
                    e.electric() ? "CHARGE" : "FUEL", Math.round(e.fuel() * 100), distance(e.distance()));
            return new Row(e, name, e.status() + "  " + distance(e.distance()), info, String.join("   ", e.ammo()));
        }

        boolean live() {
            return this.entry.feedId() != 0;
        }

        boolean autonomous() {
            return !this.entry.offers().isEmpty();
        }

        boolean connectable() {
            return this.entry.refusal().isEmpty() || this.entry.status().equals("ASLEEP");
        }
    }

    private DronesApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> ROWS.size());
        Signals.items(APP, "rows", a -> ROWS, ROW);
        Signals.num(APP, "feed", a -> feed());
        Signals.bool(APP, "picture", a -> feed() != 0 && CameraFeedCache.drawable(feed()));
        Signals.text(ROW, "name", 32, Row::name);
        Signals.text(ROW, "detail", 32, Row::detail);
        Signals.bool(ROW, "selected", r -> r == current());
        Signals.bool(ROW, "live", Row::live);
        Signals.text(SELECTED, "name", 32, Row::name);
        Signals.text(SELECTED, "status", 24, r -> r.entry().status());
        Signals.text(SELECTED, "info", 64, Row::info);
        Signals.text(SELECTED, "task", 96, r -> r.entry().task().isEmpty() ? "" : "TASK " + r.entry().task());
        Signals.text(SELECTED, "ammo", 128, Row::ammo);
        Signals.text(SELECTED, "refusal", 64, r -> r.entry().refusal().toUpperCase(Locale.ROOT));
        Signals.bool(SELECTED, "live", Row::live);
        Signals.bool(SELECTED, "low", r -> r.entry().loaded() && r.entry().fuel() < 0.2f);
        Signals.bool(SELECTED, "connectable", Row::connectable);
        Signals.bool(APP, "dispatch", a -> dispatchTab);
        Signals.bool(APP, "routed", a -> !route.isEmpty());
        Signals.text(APP, "route", 48, a -> route);
        Signals.text(APP, "target", 48, a -> targetLabel);
        Signals.bool(APP, "ready", a -> !route.isEmpty() && OFFERED[kind]);
        for (int i = 0; i < KINDS.length; i++) {
            int k = i;
            Signals.bool(APP, "offer_" + KINDS[i].id(), a -> OFFERED[k]);
            Signals.bool(APP, "kind_" + KINDS[i].id(), a -> kind == k);
        }
        NeoForge.EVENT_BUS.addListener(DronesApp::onTick);
        PdaApps.register(INSTANCE);
    }

    public static void accept(DroneStatusPacket status) {
        ROWS.clear();
        for (DroneStatusPacket.Entry e : status.drones()) {
            ROWS.add(Row.of(e));
        }
        selected = ROWS.isEmpty() ? 0 : Math.clamp(selected, 0, ROWS.size() - 1);
        pad = status.pad().orElse(null);
        offers();
        target(0);
        link();
    }

    /** Selected autonomous body takes the order, else the nearest pad. */
    private static void offers() {
        Arrays.fill(OFFERED, false);
        Row row = current();
        List<String> offered;
        if (row != null && row.autonomous()) {
            offered = row.entry().offers();
            route = row.name() + " (DIRECT)";
        } else if (pad != null) {
            offered = pad.offers();
            route = String.format(Locale.ROOT, "PAD %d %d %d   %d m", pad.pos().getX(), pad.pos().getY(),
                    pad.pos().getZ(), pad.distance());
        } else {
            route = "";
            return;
        }
        for (int i = 0; i < KINDS.length; i++) {
            OFFERED[i] = offered.contains(KINDS[i].id());
        }
    }

    public static void reply(String line) {
        REPLIES.add(new PdaLog.Line(line, line.startsWith("err") ? 0xFFD0584A : 0xFFA9D46C));
        if (REPLIES.size() > REPLY_LINES) {
            REPLIES.removeFirst();
        }
    }

    private static String distance(int metres) {
        return metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000.0)
                : String.format(Locale.ROOT, "%d m", metres);
    }

    /** Side markers in this dimension. */
    private static List<Marker> markers() {
        Minecraft mc = Minecraft.getInstance();
        List<Marker> out = new ArrayList<>();
        if (mc.level != null) {
            for (Marker m : MarkerClient.markers()) {
                if (m.dimension().equals(mc.level.dimension().location())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static void target(int step) {
        List<Marker> markers = markers();
        target = Math.floorMod(target + step, markers.size() + 1);
        if (target == 0) {
            targetLabel = "HERE (OWN POSITION)";
        } else {
            Marker m = markers.get(target - 1);
            targetLabel = String.format(Locale.ROOT, "%s  %d %d", m.label().toUpperCase(Locale.ROOT),
                    m.pos().getX(), m.pos().getZ());
        }
    }

    private static void send() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || route.isEmpty() || !OFFERED[kind]) {
            return;
        }
        List<Marker> markers = markers();
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        if (target > 0 && target <= markers.size()) {
            Marker m = markers.get(target - 1);
            x = m.pos().getX() + 0.5;
            y = m.pos().getY();
            z = m.pos().getZ() + 0.5;
        }
        Row row = current();
        Optional<UUID> drone = row != null && row.autonomous() ? Optional.of(row.entry().id()) : Optional.empty();
        reply(String.format(Locale.ROOT, "> %s at %d %d", KINDS[kind].id(), (int) x, (int) z));
        PacketDistributor.sendToServer(new DroneDispatchPacket(KINDS[kind].id(), x, y, z, drone));
    }

    private static Row current() {
        return selected < ROWS.size() ? ROWS.get(selected) : null;
    }

    /** Selected body's feed while it holds {@link CameraLink}; else 0. */
    private static int feed() {
        Row row = current();
        return row != null && row.live() && CameraLink.active() && CameraLink.feedId() == row.entry().feedId()
                ? row.entry().feedId() : 0;
    }

    /** Open app => {@link CameraLink} follows the selected live body. */
    private static void link() {
        if (!open) {
            return;
        }
        Row row = current();
        int wanted = row == null ? 0 : row.entry().feedId();
        if (wanted != 0 && !(CameraLink.active() && CameraLink.feedId() == wanted)) {
            CameraLink.open(wanted);
        } else if (wanted == 0 && CameraLink.active() && owned()) {
            CameraLink.close();
        }
    }

    private static boolean owned() {
        for (Row row : ROWS) {
            if (row.live() && row.entry().feedId() == CameraLink.feedId()) {
                return true;
            }
        }
        return false;
    }

    private static void select(Object item) {
        int index = ROWS.indexOf(item);
        if (index < 0 || index == selected) {
            return;
        }
        selected = index;
        PacketDistributor.sendToServer(new DroneActionPacket(PdaDrones.Action.SELECT, index));
        offers();
        link();
    }

    @Nullable
    private static Row rowOf(int feedId) {
        for (Row row : ROWS) {
            if (row.live() && row.entry().feedId() == feedId) {
                return row;
            }
        }
        return null;
    }

    /** Linked body's name; null = no Drones row shows {@code feedId}. */
    @Nullable
    static String name(int feedId) {
        Row row = rowOf(feedId);
        return row == null ? null : row.name();
    }

    static boolean controllable(int feedId) {
        Row row = rowOf(feedId);
        return row != null && row.connectable();
    }

    /** CONNECT the body behind {@code feedId}; PDA lowered. */
    static void control(int feedId) {
        Row row = rowOf(feedId);
        if (row != null && row.connectable()) {
            select(row);
            PacketDistributor.sendToServer(new DroneActionPacket(PdaDrones.Action.CONNECT, ROWS.indexOf(row)));
            PdaClient.lower();
        }
    }

    private static void act(PdaDrones.Action action) {
        if (current() != null) {
            PacketDistributor.sendToServer(new DroneActionPacket(action, selected));
        }
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (open && ++ticks % REFRESH_TICKS == 0) {
            PacketDistributor.sendToServer(DroneStatusRequestPacket.INSTANCE);
        }
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.drones");
    }

    @Override
    public boolean visible(LocalPlayer player) {
        return PdaClient.held();
    }

    @Override
    public void opened() {
        open = true;
        ticks = 0;
        PacketDistributor.sendToServer(DroneStatusRequestPacket.INSTANCE);
        link();
    }

    @Override
    public void closed() {
        open = false;
        if (feed() != 0) {
            CameraLink.close();
        }
    }

    @Override
    public WidgetDef build() {
        float listW = 128;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(DronesApp::select, "true", "pda_drone.selected ? " + PdaShell.PRESS + " : 0",
                PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0, rowW, 24, Point.TOP_LEFT,
                new Sized(Float.toString(rowW), "24", Point.TOP_LEFT, false, stack(
                        at("5", "3", text("{pda_drone.name}", "pda_drone.live ? " + PdaShell.ACCENT + " : "
                                + PdaShell.TEXT)),
                        at("5", "13", text("{pda_drone.detail}", PdaShell.DIM)))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        float feedW = viewW - 8;
        float feedH = (float) Math.floor(feedW * 9 / 16);
        String fw = Float.toString(feedW);
        String fh = Float.toString(feedH);
        UiDrag pan = new UiDrag() {
            @Override
            public void drag(Object item, float dx, float dy) {
                if (feed() != 0) {
                    double gain = DRAG_GAIN / Math.max(1.0, CameraLink.zoom());
                    CameraLink.pan(dx * gain, dy * gain);
                }
            }

            @Override
            public boolean wheel(Object item, float amount) {
                if (feed() == 0) {
                    return false;
                }
                CameraLink.zoomBy(amount);
                return true;
            }
        };
        float infoY = 24 + feedH + 4;
        float buttonsY = h - 14;
        String vx = Float.toString(viewX + 4);
        float right = viewX + viewW - 4;
        WidgetDef feedView = stack(
                when("pda_dronesel.present", place(Float.toString(right - 112), "13", Point.TOP_RIGHT,
                        text("{pda_dronesel.status}", "pda_dronesel.live ? " + PdaShell.ACCENT + " : "
                                + PdaShell.DIM))),
                at(vx, "24", drag(fw, fh, pan, stack(
                        rect(fw, fh, "#ff101210"),
                        new Feed("pda_drones.feed", fw, fh),
                        when("!pda_drones.picture && pda_dronesel.present", place(Float.toString(feedW / 2),
                                Float.toString(feedH / 2 - 4), Point.TOP, text("{pda_dronesel.status}",
                                        PdaShell.DIM)))))),
                when("pda_dronesel.present", stack(
                        at(vx, Float.toString(infoY), text("{pda_dronesel.info}",
                                "pda_dronesel.low ? " + PdaShell.ALERT + " : " + PdaShell.TEXT)),
                        at(vx, Float.toString(infoY + 11), new Cells(feedW)),
                        at(vx, Float.toString(infoY + 24), text("{pda_dronesel.task}", PdaShell.DIM)),
                        at(vx, Float.toString(infoY + 35), text("{pda_dronesel.ammo}", PdaShell.DIM)),
                        when("!pda_dronesel.connectable", place(Float.toString(right), Float.toString(buttonsY - 12),
                                Point.TOP_RIGHT, text("{pda_dronesel.refusal}", PdaShell.DIM))))),
                at(vx, Float.toString(buttonsY), button("CONNECT", "pda_dronesel.present && pda_dronesel.connectable",
                        () -> {
                            act(PdaDrones.Action.CONNECT);
                            PdaClient.lower();
                        })),
                at(Float.toString(viewX + 66), Float.toString(buttonsY), button("REMOVE", "pda_dronesel.present",
                        () -> act(PdaDrones.Action.REMOVE))),
                place(Float.toString(right), Float.toString(buttonsY), Point.TOP_RIGHT, button("FULL",
                        "pda_drones.feed != 0", () -> PdaClient.fullscreen(feed()))));
        WidgetDef[] kinds = new WidgetDef[KINDS.length];
        float kx = viewX + 4;
        for (int i = 0; i < KINDS.length; i++) {
            int k = i;
            String id = KINDS[i].id();
            kinds[i] = at(Float.toString(kx), "52", new Button(item -> kind = k, "pda_drones.offer_" + id,
                    "pda_drones.kind_" + id + " ? " + PdaShell.PRESS + " : " + PdaShell.PANEL, PdaShell.HOVER,
                    PdaShell.PRESS, PdaShell.EDGE, 1, 0, 3, 2, 42, 0, Point.CENTER,
                    text(id.toUpperCase(Locale.ROOT), "pda_drones.offer_" + id + " ? " + PdaShell.TEXT + " : "
                            + PdaShell.DIM)));
            kx += 45;
        }
        float logY = 104;
        WidgetDef dispatchView = stack(
                at(vx, "26", when("pda_drones.routed", text("{pda_drones.route}", PdaShell.TEXT),
                        text("NO AUTONOMOUS DRONE OR PAD", PdaShell.ALERT))),
                at(vx, "40", text("MISSION", PdaShell.DIM)),
                stack(kinds),
                at(vx, "70", text("TARGET", PdaShell.DIM)),
                at(vx, "81", small("<", () -> target(-1))),
                at(Float.toString(viewX + 18), "83", text("{pda_drones.target}", PdaShell.TEXT)),
                place(Float.toString(right), "81", Point.TOP_RIGHT, small(">", () -> target(1))),
                place(Float.toString(right), "40", Point.TOP_RIGHT, new Button(item -> send(), "pda_drones.ready",
                        PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE, 1, 0, 8, 2, 0, 0, Point.CENTER,
                        text("SEND", "pda_drones.ready ? " + PdaShell.ACCENT + " : " + PdaShell.DIM))),
                at(vx, Float.toString(logY), new PdaLog(REPLIES, viewW - 8, h - logY + 4)));
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("14", "13", text("DRONES  {pda_drones.count}", PdaShell.DIM)),
                at("12", "26", list("pda_drones.rows", 24, 1, Float.toString(rowW), Float.toString(h - 22),
                        PdaShell.EDGE, row)),
                when("pda_drones.count == 0", stack(at("14", "34", text("NO DRONES LINKED", PdaShell.DIM)),
                        at("14", "46", text("SNEAK+USE PDA ON ONE", PdaShell.DIM)),
                        at("14", "58", text("OR DISPATCH FROM A PAD", PdaShell.DIM)))),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                when("pda_dronesel.present",
                        at(Float.toString(viewX + 6), "13", text("{pda_dronesel.name}", PdaShell.TEXT))),
                tab("FEED", "!pda_drones.dispatch", () -> dispatchTab = false, right - 54, 10),
                tab("DISPATCH", "pda_drones.dispatch", () -> dispatchTab = true, right, 10),
                when("pda_drones.dispatch", dispatchView, feedView));
    }

    private static WidgetDef button(String label, String enabled, Runnable action) {
        return new Button(item -> action.run(), enabled, PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 8, 2, 0, 0, Point.CENTER, text(label, PdaShell.TEXT));
    }

    private static WidgetDef tab(String label, String active, Runnable action, float right, float y) {
        return place(Float.toString(right), Float.toString(y), Point.TOP_RIGHT, new Button(item -> action.run(), "true",
                active + " ? " + PdaShell.PRESS + " : " + PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS,
                PdaShell.EDGE, 1, 0, 4, 1, 0, 0, Point.TOP_RIGHT, text(label, PdaShell.TEXT)));
    }

    private static WidgetDef small(String label, Runnable action) {
        return new Button(item -> action.run(), "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS,
                PdaShell.EDGE, 1, 0, 3, 1, 0, 0, Point.TOP_LEFT, text(label, PdaShell.TEXT));
    }

    /** Selected body's system cells, wrapped to {@code w}: green >= 75 %, amber >= 40 %, orange > 0, red. */
    private record Cells(float w) implements WidgetDef {
        private static final float CELL_H = 10;
        private static final float GAP = 2;

        private static int colour(float ratio) {
            return ratio >= 0.75f ? 0xFF6CC24A : ratio >= 0.4f ? 0xFFE0C040 : ratio > 0 ? 0xFFE07830 : 0xFFC03030;
        }

        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = w;
                    height = CELL_H;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    Row row = current();
                    if (row == null) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    float cx = x;
                    for (RemoteBody.Cell cell : row.entry().cells()) {
                        float cw = canvas.width(cell.label()) + 6;
                        if (cx + cw > x + w) {
                            break;
                        }
                        canvas.fill(cx, y, cx + cw, y + CELL_H, colour(cell.ratio()));
                        canvas.text(cell.label(), cx + 3, y + 1, cell.ratio() <= 0 ? 0xFFFFFFFF : 0xFF202020,
                                false);
                        cx += cw + GAP;
                    }
                }
            };
        }
    }
}
