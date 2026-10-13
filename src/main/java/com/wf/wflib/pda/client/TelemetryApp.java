package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.telemetry.TelemetryKind;
import com.wf.wflib.telemetry.TelemetryLogPacket;
import com.wf.wflib.telemetry.TelemetryLogRequestPacket;
import com.wf.wflib.telemetry.client.TelemetryClient;
import com.wf.wflib.telemetry.client.TelemetryClient.Track;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Datalink event log ({@link TelemetryLogPacket}): reporters left, ALL first; selected one's live fix and log right.
 * History pulled on open; live events pushed.
 */
public final class TelemetryApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "telemetry");
    static final Subject<TelemetryApp> APP = Subject.register("pda_telemetry", TelemetryApp.class,
            () -> TelemetryApp.instance);
    static final Subject<Row> ROW = Subject.register("pda_track", Row.class);
    static final Subject<Row> SELECTED = Subject.register("pda_tsel", Row.class, TelemetryApp::current);
    /** = server ring. */
    private static final int KEPT = 256;
    private static final int REFRESH_TICKS = 20;
    private static final int ACCENT = 0xFFA9D46C;
    private static final int TEXT = 0xFFD2D6CC;
    private static final int DIM = 0xFF8D9488;
    private static final int ALERT = 0xFFD0584A;

    private static final TelemetryApp instance = new TelemetryApp();
    /** Ascending seq. */
    private static final List<TelemetryLogPacket.Entry> ENTRIES = new ArrayList<>();
    private static final List<Row> ROWS = new ArrayList<>();
    private static final List<PdaLog.Line> LINES = new ArrayList<>();
    private static final Row ALL = new Row(0, "ALL TRAFFIC", "", false, "", "");
    private static long selectedId;
    private static boolean open;
    private static int ticks;

    /** @param last latest event + clock; fix = its position */
    record Row(long id, String callsign, String kind, boolean own, String last, String fix) {
    }

    private TelemetryApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> ROWS.size() - 1);
        Signals.items(APP, "rows", a -> ROWS, ROW);
        row(ROW);
        row(SELECTED);
        Signals.bool(ROW, "selected", r -> r.id() == selectedId);
        Signals.bool(ROW, "all", r -> r == ALL);
        Signals.num(ROW, "events", r -> ENTRIES.size());
        Signals.bool(SELECTED, "all", r -> r == ALL);
        Signals.text(SELECTED, "kind", 8, Row::kind);
        Signals.text(SELECTED, "fix", 48, Row::fix);
        Signals.num(SELECTED, "bearing", r -> bearing(TelemetryClient.track(r.id())));
        Signals.num(SELECTED, "alt", r -> {
            Track t = TelemetryClient.track(r.id());
            return t == null ? 0 : Math.round(t.y());
        });
        Signals.num(SELECTED, "heading", r -> {
            Track t = TelemetryClient.track(r.id());
            return t == null ? 0 : Math.floorMod(Math.round(t.heading()), 360);
        });
        NeoForge.EVENT_BUS.addListener(TelemetryApp::onTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ENTRIES.clear();
            selectedId = 0;
            rebuild();
        });
        rebuild();
        PdaApps.register(instance);
    }

    private static void row(Subject<Row> subject) {
        Signals.text(subject, "callsign", 32, Row::callsign);
        Signals.text(subject, "last", 40, Row::last);
        Signals.bool(subject, "own", Row::own);
        Signals.bool(subject, "live", r -> live(TelemetryClient.track(r.id())));
        Signals.num(subject, "range", r -> distance(TelemetryClient.track(r.id())));
    }

    public static void accept(TelemetryLogPacket packet) {
        for (TelemetryLogPacket.Entry e : packet.entries()) {
            int at = ENTRIES.size();
            while (at > 0 && ENTRIES.get(at - 1).seq() > e.seq()) {
                at--;
            }
            if (at == 0 || ENTRIES.get(at - 1).seq() != e.seq()) {
                ENTRIES.add(at, e);
            }
        }
        while (ENTRIES.size() > KEPT) {
            ENTRIES.removeFirst();
        }
        rebuild();
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (open && ++ticks % REFRESH_TICKS == 0) {
            rows();
        }
    }

    private static void rebuild() {
        rows();
        lines();
    }

    /** ALL, live tracks without events, then reporters by latest event. */
    private static void rows() {
        Map<Long, Row> rows = new LinkedHashMap<>();
        for (Track t : TelemetryClient.tracks()) {
            if (!t.lost()) {
                rows.put(t.id(), new Row(t.id(), callsign(t.label(), t.kind(), t.id()), kind(t.kind()), t.own(), "",
                        ""));
            }
        }
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            TelemetryLogPacket.Entry e = ENTRIES.get(i);
            Row was = rows.get(e.id());
            if (was == null || was.last().isEmpty()) {
                rows.remove(e.id());
                rows.put(e.id(), new Row(e.id(), callsign(e.label(), e.kind(), e.id()), kind(e.kind()), e.own(),
                        word(e.type()) + "  " + PdaAlerts.clock(e.dayTime()),
                        String.format(Locale.ROOT, "LAST %s AT %d %d %d", word(e.type()), e.x(), e.y(), e.z())));
            }
        }
        ROWS.clear();
        ROWS.add(ALL);
        List<Row> events = new ArrayList<>();
        for (Row row : rows.values()) {
            (row.last().isEmpty() ? ROWS : events).add(row);
        }
        ROWS.addAll(events);
    }

    /** Selected reporter's events. */
    private static void lines() {
        LINES.clear();
        boolean all = current() == ALL;
        for (TelemetryLogPacket.Entry e : ENTRIES) {
            if (all || e.id() == selectedId) {
                String detail = e.detail().isEmpty() ? "" : " - " + e.detail();
                LINES.add(new PdaLog.Line(PdaAlerts.clock(e.dayTime()) + " " + (all ? callsign(e.label(), e.kind(),
                        e.id()) + "  " : "") + word(e.type()) + detail, colour(e.type())));
            }
        }
        if (LINES.isEmpty()) {
            LINES.add(new PdaLog.Line(all ? "NO DATALINK TRAFFIC" : "NO EVENTS", DIM));
        }
    }

    private static void select(Object item) {
        selectedId = ((Row) item).id();
        rebuild();
    }

    private static Row current() {
        for (int i = 0; i < ROWS.size(); i++) {
            if (ROWS.get(i).id() == selectedId) {
                return ROWS.get(i);
            }
        }
        return ALL;
    }

    private static String callsign(String label, TelemetryKind kind, long id) {
        return String.format(Locale.ROOT, "%s-%04X", (label.isEmpty() ? kind(kind) : label).toUpperCase(Locale.ROOT),
                id & 0xFFFF);
    }

    private static String kind(TelemetryKind kind) {
        return kind == TelemetryKind.MISSILE ? "MISSILE" : "DRONE";
    }

    private static String word(WFEventType type) {
        return type == WFEventType.SPAWN ? "LAUNCH" : type.name().replace('_', ' ');
    }

    private static int colour(WFEventType type) {
        return switch (type) {
            case DESTROYED, INTERCEPTED, FUEL_OUT, POWER_LOW, POWER_OUT -> ALERT;
            case SPAWN, IMPACTED, DETONATED, MISSION_COMPLETE, LANDED -> ACCENT;
            case ASCEND, CRUISE, ATTACK -> TEXT;
            default -> DIM;
        };
    }

    private static boolean live(@Nullable Track track) {
        return track != null && !track.lost();
    }

    private static double distance(@Nullable Track track) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || track == null ? 0 : Math.round(Math.hypot(track.x() - p.getX(), track.z() - p.getZ()));
    }

    /** Degrees clockwise from north. */
    private static double bearing(@Nullable Track track) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || track == null ? 0 : Math.floorMod(Math.round(Math.toDegrees(Math.atan2(
                -(track.x() - p.getX()), track.z() - p.getZ()))) + 180, 360);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.telemetry");
    }

    @Override
    public int badge() {
        int live = 0;
        for (Track t : TelemetryClient.tracks()) {
            if (!t.lost()) {
                live++;
            }
        }
        return live;
    }

    @Override
    public void opened() {
        open = true;
        ticks = 0;
        rebuild();
        PacketDistributor.sendToServer(new TelemetryLogRequestPacket(ENTRIES.isEmpty() ? 0
                : ENTRIES.getLast().seq()));
    }

    @Override
    public void closed() {
        open = false;
    }

    @Override
    public WidgetDef build() {
        float listW = 118;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        String colour = "pda_track.own ? " + PdaShell.ACCENT + " : " + PdaShell.TEXT;
        WidgetDef row = new Button(TelemetryApp::select, "true", "pda_track.selected ? " + PdaShell.PRESS + " : 0",
                PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0, rowW, 22, Point.TOP_LEFT,
                new Sized(Float.toString(rowW), "22", Point.TOP_LEFT, false, stack(
                        at("4", "3", text("{pda_track.callsign}", colour)),
                        at("4", "12", when("pda_track.all", text("{pda_track.events} EVENTS", PdaShell.DIM),
                                when("pda_track.live", text("LIVE  {pda_track.range} m", PdaShell.ACCENT),
                                        text("{pda_track.last}", PdaShell.DIM)))))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        float bodyY = 38;
        String vx = Float.toString(viewX + 4);
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("13", "13", text("TRACKS  {pda_telemetry.count}", PdaShell.DIM)),
                at("12", "25", list("pda_telemetry.rows", 22, 1, Float.toString(rowW), Float.toString(h - 21),
                        PdaShell.EDGE, row)),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                at(vx, "13", text("{pda_tsel.callsign}  {pda_tsel.kind}", "pda_tsel.own ? " + PdaShell.ACCENT
                        + " : " + PdaShell.TEXT)),
                at(vx, "25", when("pda_tsel.all", text("DATALINK EVENTS, OWN SIDE + ALLIES", PdaShell.DIM),
                        when("pda_tsel.live", text("RNG {pda_tsel.range} m  BRG {pda_tsel.bearing|03}  ALT "
                                        + "{pda_tsel.alt}  HDG {pda_tsel.heading|03}", PdaShell.TEXT),
                                text("{pda_tsel.fix}", PdaShell.DIM)))),
                at(vx, Float.toString(bodyY), new PdaLog(LINES, viewW - 8, h - bodyY + 4)));
    }
}
