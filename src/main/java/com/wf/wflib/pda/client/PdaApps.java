package com.wf.wflib.pda.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registered apps in registration order; {@link #visible} = home grid, refreshed per client tick. */
public final class PdaApps {
    private static final List<PdaApp> ALL = new ArrayList<>();
    private static final List<PdaApp> VISIBLE = new ArrayList<>();
    private static boolean sealed;

    private PdaApps() {
    }

    /** Client setup only: the shell binds every app once, on first draw. */
    public static synchronized void register(PdaApp app) {
        if (sealed) {
            throw new IllegalStateException("PDA app registered after the shell was built: " + app.id());
        }
        for (PdaApp other : ALL) {
            if (other.id().equals(app.id())) {
                throw new IllegalStateException("PDA app already registered: " + app.id());
            }
        }
        ALL.add(app);
    }

    /** wflib's shell signals + apps; client setup, before other mods' apps. */
    public static void registerBuiltins() {
        PdaShell.register();
        PdaFullscreen.register();
        MapApp.register();
        com.wf.wflib.client.map.WFMaps.addTool(new MarkerClient());
        TelemetryApp.register();
        CamerasApp.register();
        DronesApp.register();
        RadarApp.register();
        SatellitesApp.register();
        AlertsApp.register();
        TerminalApp.register();
        PdaAlerts.register();
    }

    public static PdaApp get(ResourceLocation id) {
        for (PdaApp app : ALL) {
            if (app.id().equals(id)) {
                return app;
            }
        }
        return null;
    }

    static synchronized List<PdaApp> seal() {
        sealed = true;
        return Collections.unmodifiableList(ALL);
    }

    static List<PdaApp> visible() {
        return VISIBLE;
    }

    static void refresh(LocalPlayer player) {
        VISIBLE.clear();
        for (int i = 0; i < ALL.size(); i++) {
            PdaApp app = ALL.get(i);
            if (app.visible(player)) {
                VISIBLE.add(app);
            }
        }
        PdaApp open = PdaClient.app();
        if (open != null && !VISIBLE.contains(open)) {
            PdaClient.home();
        }
    }
}
