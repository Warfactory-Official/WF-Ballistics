package com.wf.wflib.client.gui;

import net.minecraft.client.Minecraft;

/** Opens the camera screen from block and packet code without dragging {@code Screen} onto a server's classpath. */
public final class CameraScreenOpener {

    private CameraScreenOpener() {
    }

    /** One feed and no channel row: a monitor with nothing bound, showing its auto-tuned drone. */
    public static void open(int feedId) {
        open(new int[]{feedId}, 0);
    }

    public static void open(int[] feedIds, int selected) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new DroneCameraScreen(feedIds, selected)));
    }

    /** A refreshed channel list for a receiver. */
    public static void panel(int[] feedIds, int selected) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof DroneCameraScreen screen) {
                screen.updatePanel(feedIds);
            } else {
                mc.setScreen(new DroneCameraScreen(feedIds, selected));
            }
        });
    }
}
