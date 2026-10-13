package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.client.survey.SurveyCache;
import com.wf.wflib.orbital.survey.SurveyTile;
import com.wf.wflib.pda.alerts.PdaAlert;
import com.wf.wflib.pda.sat.SatCommandPacket;
import com.wf.wflib.pda.sat.SatReplyPacket;
import com.wf.wflib.pda.sat.SatStatusPacket;
import com.wf.wflib.pda.sat.SpotRequestPacket;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.TextLine;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import com.wf.wflib.ui.widget.TextField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Own net's satellites ({@link SatStatusPacket}): list left; selected bird's state right, under it a command console
 * (typed {@code [callsign] verb args}, server command bus) or the survey imagery around the player. Alerts: a bird
 * coming overhead, a hunter closing.
 */
public final class SatellitesApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "satellites");
    static final Subject<SatellitesApp> APP = Subject.register("pda_sats", SatellitesApp.class,
            () -> SatellitesApp.instance);
    static final Subject<Row> ROW = Subject.register("pda_sat", Row.class);
    static final Subject<Row> SELECTED = Subject.register("pda_satsel", Row.class, SatellitesApp::current);
    private static final int LOG_LINES = 100;
    private static final int TICKS_PER_SECOND = 20;
    private static final float[] SCALES = {2, 4, 8, 16};
    private static final int ACCENT = 0xFFA9D46C;
    private static final int TEXT = 0xFFD2D6CC;
    private static final int DIM = 0xFF8D9488;
    private static final int ALERT = 0xFFD0584A;

    private static final SatellitesApp instance = new SatellitesApp();
    private static final List<Row> ROWS = new ArrayList<>();
    private static final List<PdaLog.Line> LOG = new ArrayList<>();
    /** Last reply line seen per bird: a change = a queued command's result. */
    private static final Map<Long, String> REPLIES = new HashMap<>();
    /** Last line logged per bird; a status reply equal to it was already shown by {@link SatReplyPacket}. */
    private static final Map<Long, String> LOGGED = new HashMap<>();
    private static final TextLine INPUT = new TextLine(96, SatellitesApp::submit);
    private static Uplink.Link link = Uplink.Link.NONE;
    private static long selectedId;
    private static boolean imagery;
    private static int scale = 2;
    private static SurveyCache.Mode mode = SurveyCache.Mode.TERRAIN;

    /** One bird with display strings built once per update. */
    record Row(SatStatusPacket.Sat sat, String payload, String sub, String info, String pass) {
        static Row of(SatStatusPacket.Sat s) {
            String payload = s.payload().substring(s.payload().indexOf(':') + 1).toUpperCase(Locale.ROOT);
            String where = !s.contact() ? "NO CONTACT" : s.overhead() ? "OVERHEAD" : s.nextPass() < 0 ? "IN CONTACT"
                    : "PASS " + clock(s.nextPass());
            String info = String.format(Locale.ROOT, "FUEL %d%%  PWR %d%%  POS %d %d", Math.round(s.fuel() * 100),
                    Math.round(s.power() * 100), s.x(), s.z());
            String pass = (s.overhead() ? "OVERHEAD NOW" : s.nextPass() < 0 ? "NO PASS WITHIN 20 MIN"
                    : "NEXT PASS " + clock(s.nextPass())) + (s.threat() >= 0 ? "   HUNTER " + clock(s.threat()) : "");
            return new Row(s, payload, payload + "  " + where, info, pass);
        }
    }

    private SatellitesApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> ROWS.size());
        Signals.items(APP, "rows", a -> ROWS, ROW);
        Signals.bool(APP, "uplink", a -> link != Uplink.Link.NONE);
        Signals.bool(APP, "imagery", a -> imagery);
        Signals.text(APP, "mode", 12, a -> mode.name());
        Signals.num(APP, "scale", a -> SCALES[scale]);
        Signals.text(ROW, "callsign", 32, r -> r.sat().callsign());
        Signals.text(ROW, "sub", 40, Row::sub);
        Signals.bool(ROW, "contact", r -> r.sat().contact());
        Signals.bool(ROW, "selected", r -> r.sat().id() == selectedId);
        Signals.text(SELECTED, "callsign", 32, r -> r.sat().callsign());
        Signals.text(SELECTED, "payload", 24, Row::payload);
        Signals.text(SELECTED, "info", 64, Row::info);
        Signals.text(SELECTED, "pass", 64, Row::pass);
        Signals.bool(SELECTED, "threat", r -> r.sat().threat() >= 0);
        log("SATELLITE CONSOLE. TYPE 'help'.", DIM);
        PdaApps.register(instance);
    }

    public static void accept(SatStatusPacket status) {
        link = status.link();
        SurveyCache.bind(status.netId());
        Map<Long, Row> old = new HashMap<>();
        for (Row row : ROWS) {
            old.put(row.sat().id(), row);
        }
        ROWS.clear();
        for (SatStatusPacket.Sat sat : status.sats()) {
            Row row = Row.of(sat);
            ROWS.add(row);
            Row was = old.get(sat.id());
            if (was != null && !was.sat().overhead() && sat.overhead()) {
                PdaAlerts.post(Component.translatable("wflib.pda.alert.sat_overhead", sat.callsign()),
                        PdaAlert.Tier.INFO);
            }
            if (was != null && was.sat().threat() < 0 && sat.threat() >= 0) {
                PdaAlerts.post(Component.translatable("wflib.pda.alert.sat_hunter", sat.callsign(),
                        sat.threat() / TICKS_PER_SECOND), PdaAlert.Tier.CRITICAL);
            }
            String reply = REPLIES.put(sat.id(), sat.reply());
            if (was != null && reply != null && !reply.equals(sat.reply()) && !sat.reply().isEmpty()
                    && !sat.reply().equals(LOGGED.get(sat.id()))) {
                reply(sat.id(), sat.callsign(), sat.reply());
            }
        }
        if (current() == null && !ROWS.isEmpty()) {
            selectedId = ROWS.getFirst().sat().id();
        }
    }

    public static void accept(SatReplyPacket reply) {
        Row row = byId(reply.satId());
        reply(reply.satId(), row == null ? "SAT" : row.sat().callsign(), reply.line());
    }

    private static void reply(long satId, String callsign, String line) {
        LOGGED.put(satId, line);
        log(callsign + ": " + line, line.startsWith("err") ? ALERT : ACCENT);
    }

    private static Row current() {
        return byId(selectedId);
    }

    private static Row byId(long id) {
        for (int i = 0; i < ROWS.size(); i++) {
            if (ROWS.get(i).sat().id() == id) {
                return ROWS.get(i);
            }
        }
        return null;
    }

    /** Callsign, {@code #n} or {@code n} (1-based list index). */
    private static Row byName(String name) {
        String digits = name.startsWith("#") ? name.substring(1) : name;
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
            int index = digits.length() > 3 ? -1 : Integer.parseInt(digits) - 1;
            return index >= 0 && index < ROWS.size() ? ROWS.get(index) : null;
        }
        for (Row row : ROWS) {
            if (row.sat().callsign().equalsIgnoreCase(name)) {
                return row;
            }
        }
        return null;
    }

    private static void log(String text, int colour) {
        LOG.add(new PdaLog.Line(text, colour));
        if (LOG.size() > LOG_LINES) {
            LOG.removeFirst();
        }
    }

    private static String clock(int ticks) {
        int seconds = ticks / TICKS_PER_SECOND;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Console line: local words, else {@code [callsign|#n|n] verb args} to the bus. */
    private static void submit(String line) {
        log("> " + line, TEXT);
        String[] words = line.split("\\s+");
        switch (words[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(words.length > 1 ? byName(words[1]) : current());
            case "clear" -> LOG.clear();
            case "sats" -> {
                if (ROWS.isEmpty()) {
                    log(link == Uplink.Link.NONE ? "no uplink" : "no satellites on this network", DIM);
                }
                for (int i = 0; i < ROWS.size(); i++) {
                    Row row = ROWS.get(i);
                    log("#" + (i + 1) + " " + row.sat().callsign() + "  " + row.sub(), TEXT);
                }
            }
            case "spot" -> requestSpot(words);
            case "select" -> {
                Row row = words.length > 1 ? byName(words[1]) : null;
                if (row == null) {
                    log("err: no such satellite", ALERT);
                } else {
                    selectedId = row.sat().id();
                    log("selected " + row.sat().callsign(), DIM);
                }
            }
            default -> command(words);
        }
    }

    /** {@code spot [x z]}: selected bird, the player's position by default. */
    private static void requestSpot(String[] words) {
        Row row = current();
        LocalPlayer p = Minecraft.getInstance().player;
        if (row == null || p == null) {
            log("err: no satellite selected", ALERT);
            return;
        }
        double x = p.getX();
        double z = p.getZ();
        if (words.length == 3) {
            try {
                x = Double.parseDouble(words[1]);
                z = Double.parseDouble(words[2]);
            } catch (NumberFormatException e) {
                log("err: spot [x z]", ALERT);
                return;
            }
        } else if (words.length != 1) {
            log("err: spot [x z]", ALERT);
            return;
        }
        PacketDistributor.sendToServer(new SpotRequestPacket(row.sat().id(), x, z));
    }

    /** Spot granted: fullscreen feed. */
    public static void spot(int feedId) {
        CameraLink.open(feedId);
        PdaClient.fullscreen(feedId);
    }

    private static void command(String[] words) {
        Row target = byName(words[0]);
        int verbAt = 0;
        if (target != null && words.length > 1) {
            verbAt = 1;
        } else {
            target = current();
        }
        if (target == null) {
            log(link == Uplink.Link.NONE ? "err: no uplink" : "err: no satellite selected", ALERT);
            return;
        }
        String verb = words[verbAt].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(words, verbAt + 1, words.length);
        SatStatusPacket.Verb known = null;
        for (SatStatusPacket.Verb v : target.sat().verbs()) {
            if (v.name().equalsIgnoreCase(verb)) {
                known = v;
            }
        }
        if (known == null) {
            log("err: " + target.sat().callsign() + " has no verb '" + verb + "' (help)", ALERT);
            return;
        }
        if (args.length != known.arity()) {
            log("err: " + verb + " takes " + known.arity() + " argument(s)", ALERT);
            return;
        }
        PacketDistributor.sendToServer(new SatCommandPacket(target.sat().id(), verb, List.of(args)));
    }

    private static void help(Row row) {
        log("local: help [sat], sats, select <sat|#n>, spot [x z], clear", DIM);
        log("bus: [sat|#n] <verb> <args>  (selected sat if omitted)", DIM);
        if (row == null) {
            return;
        }
        for (SatStatusPacket.Verb v : row.sat().verbs()) {
            log((v.pure() ? "  ? " : "  ! ") + v.name() + " /" + v.arity() + "  " + v.describe(), DIM);
        }
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.satellites");
    }

    @Override
    public boolean visible(LocalPlayer player) {
        return PdaClient.held();
    }

    @Override
    public int badge() {
        int threats = 0;
        for (int i = 0; i < ROWS.size(); i++) {
            if (ROWS.get(i).sat().threat() >= 0) {
                threats++;
            }
        }
        return threats;
    }

    @Override
    public WidgetDef build() {
        float listW = 118;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(item -> selectedId = ((Row) item).sat().id(), "true",
                "pda_sat.selected ? " + PdaShell.PRESS + " : 0", PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0,
                rowW, 22, Point.TOP_LEFT, new Sized(Float.toString(rowW), "22", Point.TOP_LEFT, false, stack(
                at("4", "3", text("{pda_sat.callsign}", "pda_sat.contact ? " + PdaShell.ACCENT + " : "
                        + PdaShell.TEXT)),
                at("4", "12", text("{pda_sat.sub}", PdaShell.DIM)))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        float innerW = viewW - 8;
        float bodyY = 48;
        float bodyH = h - bodyY + 4;
        String vx = Float.toString(viewX + 4);
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("13", "13", text("SATS  {pda_sats.count}", PdaShell.DIM)),
                at("12", "25", list("pda_sats.rows", 22, 1, Float.toString(rowW), Float.toString(h - 21),
                        PdaShell.EDGE, row)),
                when("!pda_sats.uplink", at("13", "31", text("NO UPLINK", PdaShell.ALERT)),
                        when("pda_sats.count == 0", at("13", "31", text("NO SATELLITES", PdaShell.DIM)))),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                when("pda_satsel.present", stack(
                        at(vx, "13", text("{pda_satsel.callsign}  {pda_satsel.payload}", PdaShell.TEXT)),
                        at(vx, "25", text("{pda_satsel.info}", PdaShell.DIM)),
                        at(vx, "36", text("{pda_satsel.pass}", "pda_satsel.threat ? " + PdaShell.ALERT + " : "
                                + PdaShell.DIM)))),
                tab("CONSOLE", "!pda_sats.imagery", () -> imagery = false, viewX + viewW - 58, 10),
                tab("IMAGERY", "pda_sats.imagery", () -> imagery = true, viewX + viewW - 4, 10),
                when("pda_sats.imagery",
                        stack(at(vx, Float.toString(bodyY), new Survey(innerW, bodyH - 4)),
                                at(vx, Float.toString(bodyY + 2), text("{pda_sats.mode}  {pda_sats.scale} m/u",
                                        PdaShell.TEXT)),
                                small("SPOT", () -> requestSpot(new String[]{"spot"}), viewX + viewW - 46, bodyY + 2),
                                small("-", () -> scale = Math.min(SCALES.length - 1, scale + 1), viewX + viewW - 32,
                                        bodyY + 2),
                                small("+", () -> scale = Math.max(0, scale - 1), viewX + viewW - 18, bodyY + 2),
                                small("M", () -> mode = SurveyCache.Mode.values()[
                                        (mode.ordinal() + 1) % SurveyCache.Mode.values().length],
                                        viewX + viewW - 4, bodyY + 2)),
                        stack(at(vx, Float.toString(bodyY), new PdaLog(LOG, innerW, bodyH - 20)),
                                at(vx, Float.toString(bodyY + bodyH - 16), new TextField(INPUT, "> ", innerW, 13,
                                        0xFF101210, 0xFF787F76, 0xFFA9D46C, TEXT, ACCENT)))));
    }

    private static WidgetDef tab(String label, String active, Runnable action, float right, float y) {
        return place(Float.toString(right), Float.toString(y), Point.TOP_RIGHT, new Button(item -> action.run(), "true",
                active + " ? " + PdaShell.PRESS + " : " + PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS,
                PdaShell.EDGE, 1, 0, 4, 1, 0, 0, Point.TOP_RIGHT, text(label, PdaShell.TEXT)));
    }

    private static WidgetDef small(String label, Runnable action, float right, float y) {
        return place(Float.toString(right), Float.toString(y), Point.TOP_RIGHT, new Button(item -> action.run(), "true", PdaShell.PANEL, PdaShell.HOVER,
                PdaShell.PRESS, PdaShell.EDGE, 1, 0, 3, 1, 0, 0, Point.TOP_RIGHT, text(label, PdaShell.TEXT)));
    }

    /** Survey tiles around the player, north up, {@link #SCALES} blocks per unit. */
    private record Survey(float w, float h) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = w;
                    height = h;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    Canvas canvas = ev.canvas;
                    canvas.fill(x, y, x + w, y + h, 0xFF101210);
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p == null || link == Uplink.Link.NONE) {
                        return;
                    }
                    float bpu = SCALES[scale];
                    double minX = p.getX() - w / 2 * bpu;
                    double minZ = p.getZ() - h / 2 * bpu;
                    double maxX = minX + w * bpu;
                    double maxZ = minZ + h * bpu;
                    int t0x = SurveyTile.tileOf(minX);
                    int t0z = SurveyTile.tileOf(minZ);
                    int t1x = SurveyTile.tileOf(maxX);
                    int t1z = SurveyTile.tileOf(maxZ);
                    for (int tz = t0z; tz <= t1z; tz++) {
                        for (int tx = t0x; tx <= t1x; tx++) {
                            SurveyCache.Held held = SurveyCache.tile(tx, tz, mode);
                            if (held == null || held.id() == null) {
                                continue;
                            }
                            double bx0 = Math.max(minX, (double) tx * SurveyTile.BLOCKS);
                            double bz0 = Math.max(minZ, (double) tz * SurveyTile.BLOCKS);
                            double bx1 = Math.min(maxX, (double) (tx + 1) * SurveyTile.BLOCKS);
                            double bz1 = Math.min(maxZ, (double) (tz + 1) * SurveyTile.BLOCKS);
                            float u0 = (float) ((bx0 - (double) tx * SurveyTile.BLOCKS) / SurveyTile.BLOCKS);
                            float v0 = (float) ((bz0 - (double) tz * SurveyTile.BLOCKS) / SurveyTile.BLOCKS);
                            float u1 = (float) ((bx1 - (double) tx * SurveyTile.BLOCKS) / SurveyTile.BLOCKS);
                            float v1 = (float) ((bz1 - (double) tz * SurveyTile.BLOCKS) / SurveyTile.BLOCKS);
                            canvas.sprite(held.id(), x + (float) ((bx0 - minX) / bpu), y + (float) ((bz0 - minZ) / bpu),
                                    x + (float) ((bx1 - minX) / bpu), y + (float) ((bz1 - minZ) / bpu), u0, v0, u1, v1,
                                    0xFFFFFFFF);
                        }
                    }
                    float cx = x + w / 2;
                    float cy = y + h / 2;
                    float a = (float) Math.toRadians(p.getYRot());
                    float sx = (float) -Math.sin(a);
                    float sy = (float) Math.cos(a);
                    canvas.triangle(cx + sx * 5, cy + sy * 5, cx - sy * 3 - sx * 3, cy + sx * 3 - sy * 3,
                            cx + sy * 3 - sx * 3, cy - sx * 3 - sy * 3, ACCENT);
                }
            };
        }
    }
}
