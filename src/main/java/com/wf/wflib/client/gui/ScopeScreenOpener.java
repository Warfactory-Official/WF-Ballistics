package com.wf.wflib.client.gui;

import net.minecraft.client.Minecraft;

/** Opens the scope screen from block code without dragging {@code Screen} onto a server's classpath. */
public final class ScopeScreenOpener {

    private ScopeScreenOpener() {
    }

    public static void open(long netId, double originX, double originZ) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new RadarScopeScreen(netId, originX, originZ)));
    }
}
