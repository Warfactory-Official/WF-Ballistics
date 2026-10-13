package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.pda.PdaRadarPacket;
import com.wf.wflib.pda.PdaRadarSubscribePacket;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.scope.ScopeFrame;
import com.wf.wflib.recon.track.IffState;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Own recon net's picture around the player ({@link PdaRadarPacket}, server-gated by {@link Uplink}): radar and
 * sonar contacts in one fused list; scope right, sonar-held contacts drawn as rings.
 */
public final class RadarApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "radar");
    static final Subject<RadarApp> APP = Subject.register("pda_radar", RadarApp.class, () -> RadarApp.instance);
    static final Subject<Contact> CONTACT = Subject.register("pda_contact", Contact.class);
    static final Subject<Contact> SELECTED = Subject.register("pda_csel", Contact.class, () -> RadarApp.selected);
    private static final float[] RANGES = {512, 1024, 2048, 4096, 8192};
    /** Reselect across frames: same contact = nearest within this, blocks. */
    private static final double RESELECT = 48;

    private static final RadarApp instance = new RadarApp();
    private static final List<Contact> CONTACTS = new ArrayList<>();
    private static Uplink.Link link = Uplink.Link.NONE;
    private static boolean hasNet;
    private static boolean heard;
    private static int rangeIndex = 1;
    private static Contact selected;
    private static boolean open;

    /** One blip, strings resolved once per frame received. */
    record Contact(ScopeFrame.Blip blip, String kind, String bands, String quality, boolean sonar) {
        double distance() {
            LocalPlayer p = Minecraft.getInstance().player;
            return p == null ? 0 : Math.hypot(blip.x() - p.getX(), blip.z() - p.getZ());
        }

        double bearing() {
            LocalPlayer p = Minecraft.getInstance().player;
            return p == null ? 0 : Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-(blip.x() - p.getX()),
                    blip.z() - p.getZ()))) + 180, 360);
        }

        IffState iff() {
            return IffState.values()[Math.floorMod(blip.iff(), IffState.values().length)];
        }
    }

    private RadarApp() {
    }

    static void register() {
        Signals.text(APP, "status", 24, a -> status());
        Signals.bool(APP, "live", a -> link != Uplink.Link.NONE);
        Signals.num(APP, "range", a -> RANGES[rangeIndex]);
        Signals.num(APP, "count", a -> CONTACTS.size());
        Signals.items(APP, "contacts", a -> CONTACTS, CONTACT);
        contact(CONTACT);
        contact(SELECTED);
        Signals.bool(CONTACT, "selected", c -> c == selected);
        PdaApps.register(instance);
    }

    private static void contact(Subject<Contact> subject) {
        Signals.text(subject, "kind", 10, Contact::kind);
        Signals.text(subject, "bands", 8, Contact::bands);
        Signals.text(subject, "quality", 10, Contact::quality);
        Signals.choice(subject, "iff", IffState.class, Contact::iff);
        Signals.bool(subject, "sonar", Contact::sonar);
        Signals.num(subject, "range", Contact::distance);
        Signals.num(subject, "bearing", Contact::bearing);
        Signals.num(subject, "alt", c -> c.blip().y());
        Signals.num(subject, "count", c -> c.blip().count());
    }

    private static String status() {
        if (!heard) {
            return "LINKING";
        }
        if (!hasNet) {
            return "NO NETWORK";
        }
        return switch (link) {
            case GROUND -> "GROUND LINK";
            case RELAY -> "SAT RELAY";
            case NONE -> "NO UPLINK";
        };
    }

    public static void accept(PdaRadarPacket packet) {
        heard = true;
        hasNet = packet.hasNet();
        link = packet.link();
        double sx = selected == null ? 0 : selected.blip().x();
        double sz = selected == null ? 0 : selected.blip().z();
        boolean hadSelection = selected != null;
        selected = null;
        CONTACTS.clear();
        ScopeFrame frame = packet.frame();
        if (frame == null) {
            return;
        }
        int sonarBit = 1 << Band.SONAR.ordinal();
        double best = RESELECT * RESELECT;
        for (ScopeFrame.Blip blip : frame.blips()) {
            Contact contact = new Contact(blip, blip.kindValue().name(), blip.bandTag(),
                    blip.qualityValue().name(), (blip.bands() & sonarBit) != 0);
            CONTACTS.add(contact);
            if (hadSelection) {
                double dx = blip.x() - sx;
                double dz = blip.z() - sz;
                if (dx * dx + dz * dz < best) {
                    best = dx * dx + dz * dz;
                    selected = contact;
                }
            }
        }
        CONTACTS.sort(Comparator.comparingDouble(Contact::distance));
    }

    private static void subscribe() {
        PacketDistributor.sendToServer(new PdaRadarSubscribePacket(open ? RANGES[rangeIndex] : 0));
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.radar");
    }

    @Override
    public void opened() {
        open = true;
        heard = false;
        subscribe();
    }

    @Override
    public void closed() {
        open = false;
        subscribe();
        CONTACTS.clear();
        selected = null;
    }

    @Override
    public WidgetDef build() {
        float listW = 140;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(item -> selected = (Contact) item, "true", "pda_contact.selected ? "
                + PdaShell.PRESS + " : 0", PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0, rowW, 20, Point.TOP_LEFT,
                new Sized(Float.toString(rowW), "20", Point.TOP_LEFT, false, stack(
                        at("5", "6", text("{pda_contact.kind}", iffColor("pda_contact"))),
                        place(Float.toString(rowW - 6), "6", Point.TOP_RIGHT,
                                text("{pda_contact.range}m", PdaShell.DIM)))));
        WidgetDef zoom = stack(
                at("0", "0", rangeButton("-", -1)),
                at("22", "0", rangeButton("+", 1)));
        float plotX = listW + 16;
        float plotW = PdaShell.APP_WIDTH - plotX - 8;
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("14", "13", text("{pda_radar.status}", "pda_radar.live ? " + PdaShell.ACCENT + " : "
                        + PdaShell.ALERT)),
                at("14", "26", text("CONTACTS  {pda_radar.count}", PdaShell.DIM)),
                at("12", "40", list("pda_radar.contacts", 20, 1, Float.toString(rowW), Float.toString(h - 36),
                        PdaShell.EDGE, row)),
                at(Float.toString(plotX), "8", PdaShell.panel(plotW, h)),
                at(Float.toString(plotX + 6), "13", text("NORTH UP  {pda_radar.range}m", PdaShell.DIM)),
                at(Float.toString(plotX + plotW - 50), "10", zoom),
                at(Float.toString(plotX), "26", new Scope(plotW, h - 62)),
                at(Float.toString(plotX + 8), Float.toString(h - 30), when("pda_csel.present", stack(
                        text("{pda_csel.iff|upper} {pda_csel.kind}  x{pda_csel.count}  [{pda_csel.bands}]",
                                iffColor("pda_csel")),
                        at("0", "12", text("RNG {pda_csel.range}m  BRG {pda_csel.bearing|03}  ALT {pda_csel.alt}  "
                                + "{pda_csel.quality}", PdaShell.TEXT))),
                        text("SELECT A CONTACT", PdaShell.DIM))));
    }

    private static WidgetDef rangeButton(String label, int step) {
        return new Button(item -> {
            rangeIndex = Math.max(0, Math.min(RANGES.length - 1, rangeIndex + step));
            subscribe();
        }, "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE, 1, 0, 0, 0, 18, 12, Point.CENTER,
                text(label, PdaShell.TEXT));
    }

    private static String iffColor(String subject) {
        return subject + ".iff == " + subject + ".iff.friendly ? " + PdaShell.ACCENT + " : " + subject + ".iff == "
                + subject + ".iff.hostile ? " + PdaShell.ALERT + " : " + PdaShell.TEXT;
    }

    /** Scope around the live player position, north up; sonar-held contacts = rings, others = squares. */
    private record Scope(float w, float h) implements WidgetDef {
        private static final int FRIENDLY = 0xFFA9D46C;
        private static final int HOSTILE = 0xFFD0584A;
        private static final int UNKNOWN = 0xFFE0B860;
        private static final int RING = 0xFF5B6259;
        private static final int SELF = 0xFFD2D6CC;

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
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p == null) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    float cx = x + w / 2;
                    float cy = y + h / 2;
                    float radius = Math.min(w, h) / 2 - 4;
                    canvas.ring(cx, cy, radius, 1, RING);
                    canvas.ring(cx, cy, radius / 2, 1, RING);
                    canvas.line(cx, cy - radius, cx, cy + radius, 1, RING);
                    canvas.line(cx - radius, cy, cx + radius, cy, 1, RING);
                    canvas.triangle(cx, cy - 4, cx - 3, cy + 3, cx + 3, cy + 3, SELF);
                    if (link == Uplink.Link.NONE) {
                        return;
                    }
                    float scale = radius / RANGES[rangeIndex];
                    for (int i = 0; i < CONTACTS.size(); i++) {
                        Contact c = CONTACTS.get(i);
                        float dx = (float) (c.blip().x() - p.getX()) * scale;
                        float dz = (float) (c.blip().z() - p.getZ()) * scale;
                        if (dx * dx + dz * dz > radius * radius) {
                            continue;
                        }
                        float bx = cx + dx;
                        float by = cy + dz;
                        int argb = switch (c.iff()) {
                            case FRIENDLY -> FRIENDLY;
                            case HOSTILE -> HOSTILE;
                            case UNKNOWN -> UNKNOWN;
                        };
                        if (c.sonar()) {
                            canvas.ring(bx, by, 3, 1.2f, argb);
                        } else {
                            canvas.fill(bx - 2, by - 2, bx + 2, by + 2, argb);
                        }
                        float error = c.blip().error() * scale;
                        if (error > 4) {
                            canvas.ring(bx, by, Math.min(error, radius), 0.6f, argb & 0x80FFFFFF);
                        }
                        if (c == selected) {
                            canvas.ring(bx, by, 7, 1, SELF);
                        }
                    }
                }
            };
        }
    }
}
