package com.wf.wflib.pda.client;

import com.wf.wflib.WFLib;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/** {@link PdaAlerts} history, newest first; badge = unread. */
final class AlertsApp implements PdaApp {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "alerts");
    static final Subject<AlertsApp> APP = Subject.register("pda_alerts", AlertsApp.class, () -> AlertsApp.instance);
    static final Subject<PdaAlerts.Entry> ENTRY = Subject.register("pda_alert", PdaAlerts.Entry.class);
    private static final AlertsApp instance = new AlertsApp();

    private AlertsApp() {
    }

    static void register() {
        Signals.num(APP, "count", a -> PdaAlerts.entries().size());
        Signals.items(APP, "entries", a -> PdaAlerts.entries(), ENTRY);
        Signals.text(ENTRY, "clock", 5, PdaAlerts.Entry::clock);
        PdaApps.register(instance);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable("wflib.pda.app.alerts");
    }

    @Override
    public int badge() {
        return PdaAlerts.unread();
    }

    @Override
    public void opened() {
        PdaAlerts.read();
    }

    @Override
    public WidgetDef build() {
        float w = PdaShell.APP_WIDTH - 16;
        float h = PdaShell.APP_HEIGHT - 16;
        return stack(
                at("8", "8", PdaShell.panel(w, h)),
                at("12", "12", list("pda_alerts.entries", 13, 0, Float.toString(w - 8), Float.toString(h - 8),
                        PdaShell.EDGE, new Row(w - 18))),
                when("pda_alerts.count == 0", at("14", "14", text("NO ALERTS", PdaShell.DIM))));
    }

    /** Clock + text in the tier colour. */
    private record Row(float w) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            int slot = binder.itemSlot();
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = w;
                    height = 13;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    PdaAlerts.Entry e = (PdaAlerts.Entry) ev.item(slot);
                    if (e == null) {
                        return;
                    }
                    float tx = ev.canvas.text(e.clock(), x + 2, y + 2, 0xFF8D9488, false);
                    ev.canvas.text(e.text(), tx + 6, y + 2, PdaAlerts.colour(e.tier()), false);
                }
            };
        }
    }
}
