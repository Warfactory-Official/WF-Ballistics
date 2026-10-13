package com.wf.wflib.pda.client;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.WFSounds;
import com.wf.wflib.pda.alerts.PdaAlert;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.UiHud;
import com.wf.wflib.ui.UiInstance;
import com.wf.wflib.ui.UiLayout;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.wf.wflib.ui.Ui.layout;
import static com.wf.wflib.ui.Ui.place;

/**
 * PDA notifications, client end: any system {@link #post}s (server: {@code PdaAlertPacket}); while a PDA is carried =>
 * HUD toast + vibration buzz + Alerts app history. Without a PDA nothing is shown.
 */
public final class PdaAlerts {
    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "pda_toasts");
    private static final int HISTORY = 32;
    private static final int TOASTS = 3;
    private static final long TOAST_MS = 5000;
    private static final long FADE_MS = 800;
    private static final int[] COLOURS = {0xFFD2D6CC, 0xFFE0B860, 0xFFD0584A};

    /** {@code text} resolved once. */
    record Entry(String text, PdaAlert.Tier tier, long at, String clock) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static int unread;
    private static UiInstance toasts;

    private PdaAlerts() {
    }

    static void register() {
        // Top-left: minimaps own top-right.
        UiLayout layout = layout(LAYOUT, place("6", "6", Point.TOP_LEFT, new Toasts())).absolute();
        UiHud.addSource(UiHud.Layer.MAIN, out -> {
            if (!ENTRIES.isEmpty() && PdaClient.carried()
                    && Util.getMillis() - ENTRIES.getFirst().at < TOAST_MS + FADE_MS) {
                if (toasts == null) {
                    toasts = layout.instantiate();
                }
                out.add(toasts);
            }
        });
    }

    public static void post(Component message, PdaAlert.Tier tier) {
        if (!PdaClient.carried()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ENTRIES.addFirst(new Entry(message.getString(), tier, Util.getMillis(),
                clock(mc.level == null ? 0 : mc.level.getDayTime())));
        if (ENTRIES.size() > HISTORY) {
            ENTRIES.removeLast();
        }
        unread = Math.min(unread + 1, HISTORY);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(WFSounds.PDA_BUZZ.get(), 1.0f, 0.8f));
    }

    /** HH:MM, day starting 06:00. */
    static String clock(long dayTime) {
        long time = dayTime + 6000;
        return String.format(Locale.ROOT, "%02d:%02d", time / 1000 % 24, time % 1000 * 60 / 1000);
    }

    /** Newest first. */
    static List<Entry> entries() {
        return ENTRIES;
    }

    static int unread() {
        return unread;
    }

    static void read() {
        unread = 0;
    }

    static int colour(PdaAlert.Tier tier) {
        return COLOURS[tier.ordinal()];
    }

    /** Newest {@link #TOASTS} under {@link #TOAST_MS}, newest on top, fading out. */
    private record Toasts() implements WidgetDef {
        private static final float W = 220;
        private static final float ROW = 14;

        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = W;
                    height = ROW * TOASTS;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    Canvas canvas = ev.canvas;
                    long now = Util.getMillis();
                    float row = y;
                    for (int i = 0; i < ENTRIES.size() && i < TOASTS; i++) {
                        Entry e = ENTRIES.get(i);
                        long age = now - e.at;
                        float alpha = age < TOAST_MS ? 1 : 1 - (float) (age - TOAST_MS) / FADE_MS;
                        // Font alpha < 4/255 draws opaque.
                        if (alpha < 0.05f) {
                            break;
                        }
                        int a = Math.round(alpha * 0xD0) << 24;
                        float tw = Math.min(W - 8, canvas.width(e.text));
                        float x0 = x;
                        canvas.fill(x0, row, x0 + tw + 8, row + ROW - 2, a | 0x1A1C1A);
                        canvas.fill(x0, row, x0 + 2, row + ROW - 2, (Math.round(alpha * 0xFF) << 24)
                                | colour(e.tier) & 0xFFFFFF);
                        canvas.text(e.text, x0 + 5, row + 3, (Math.round(alpha * 0xFF) << 24) | 0xD2D6CC, false);
                        row += ROW;
                    }
                }
            };
        }
    }
}
