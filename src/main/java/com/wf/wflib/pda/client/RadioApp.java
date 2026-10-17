package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.pda.drone.DroneStatusRequestPacket;
import com.wf.wflib.radio.RadioTunePacket;
import com.wf.wflib.radio.client.RadioCatalog;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/** Linked bodies carrying a radio set ({@link DronesApp} status): tune the selected one, off between last and first. */
final class RadioApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "radio");
    private static final int REFRESH_TICKS = 10;
    private static final RadioApp INSTANCE = new RadioApp();
    static final Subject<RadioApp> APP = Subject.register("pda_radio", RadioApp.class, () -> INSTANCE);
    static final Subject<DronesApp.Row> ROW = Subject.register("pda_radio_row", DronesApp.Row.class);

    private static final List<DronesApp.Row> ROWS = new ArrayList<>();
    @Nullable
    private static DronesApp.Row selected;
    private static boolean open;
    private static int ticks;

    private RadioApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> rows().size());
        Signals.items(APP, "rows", a -> rows(), ROW);
        Signals.bool(APP, "chosen", a -> current() != null);
        Signals.text(APP, "name", 32, a -> current() == null ? "" : current().name());
        Signals.text(APP, "station", 48, a -> current() == null ? ""
                : RadioCatalog.name(current().entry().station()).getString().toUpperCase(Locale.ROOT));
        Signals.text(APP, "stations", 24, a -> RadioCatalog.stations().size() + " STATIONS");
        Signals.text(ROW, "name", 32, DronesApp.Row::name);
        Signals.text(ROW, "station", 32, r -> RadioCatalog.name(r.entry().station()).getString()
                .toUpperCase(Locale.ROOT));
        Signals.bool(ROW, "selected", r -> r == current());
        NeoForge.EVENT_BUS.addListener(RadioApp::onTick);
        PdaApps.register(INSTANCE);
    }

    /** Rows with a set; refreshed from {@link DronesApp#rows} each read. */
    private static List<DronesApp.Row> rows() {
        ROWS.clear();
        for (DronesApp.Row row : DronesApp.rows()) {
            if (row.entry().radio()) {
                ROWS.add(row);
            }
        }
        return ROWS;
    }

    @Nullable
    private static DronesApp.Row current() {
        List<DronesApp.Row> rows = rows();
        if (selected != null) {
            for (DronesApp.Row row : rows) {
                if (row.entry().id().equals(selected.entry().id())) {
                    return row;
                }
            }
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void tune(int step) {
        DronesApp.Row row = current();
        if (row != null) {
            PacketDistributor.sendToServer(new RadioTunePacket(row.entry().id(),
                    step == 0 ? "" : RadioCatalog.next(row.entry().station(), step)));
            PacketDistributor.sendToServer(DroneStatusRequestPacket.INSTANCE);
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
        return Component.translatable("wflib.pda.app.radio");
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
    }

    @Override
    public void closed() {
        open = false;
    }

    @Override
    public WidgetDef build() {
        float listW = 160;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(item -> selected = (DronesApp.Row) item, "true",
                "pda_radio_row.selected ? " + PdaShell.PRESS + " : 0", PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0,
                0, rowW, 24, Point.TOP_LEFT, new Sized(Float.toString(rowW), "24", Point.TOP_LEFT, false, stack(
                        at("5", "3", text("{pda_radio_row.name}", PdaShell.TEXT)),
                        at("5", "13", text("{pda_radio_row.station}", PdaShell.DIM)))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        String vx = Float.toString(viewX + 6);
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("14", "13", text("RADIOS  {pda_radio.count}", PdaShell.DIM)),
                at("12", "26", list("pda_radio.rows", 24, 1, Float.toString(rowW), Float.toString(h - 22),
                        PdaShell.EDGE, row)),
                when("pda_radio.count == 0", stack(at("14", "34", text("NO LINKED RADIO", PdaShell.DIM)),
                        at("14", "46", text("LINK A BODY CARRYING ONE", PdaShell.DIM)))),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                at(vx, "13", text("{pda_radio.stations}", PdaShell.DIM)),
                when("pda_radio.chosen", stack(
                        at(vx, "30", text("{pda_radio.name}", PdaShell.TEXT)),
                        at(vx, "46", button("<", () -> tune(-1))),
                        at(Float.toString(viewX + 24), "48", text("{pda_radio.station}", PdaShell.ACCENT)),
                        at(Float.toString(viewX + viewW - 20), "46", button(">", () -> tune(1))),
                        at(vx, "66", button("OFF", () -> tune(0))))));
    }

    private static WidgetDef button(String label, Runnable action) {
        return new Button(item -> action.run(), "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS,
                PdaShell.EDGE, 1, 0, 4, 1, 0, 0, Point.TOP_LEFT, text(label, PdaShell.TEXT));
    }
}
