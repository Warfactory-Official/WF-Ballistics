package com.wf.wfballistics.client.flywheel;

import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;


public final class BillboardLod {

    private static final double MID_SQ = 64.0 * 64.0;
    private static final double FAR_SQ = 160.0 * 160.0;
    private static final double VERY_FAR_SQ = 320.0 * 320.0;
    // Quaternion dot above which two camera orientations count as "not rotated".
    private static final float STILL_DOT = 0.99999f;

    private final Quaternionf lastCamRot = new Quaternionf();
    private boolean hasLast = false;
    private int frame = 0;
    private int lastUpdate = Integer.MIN_VALUE;

    /** Draw only puffs whose index is a multiple of this; collapse the rest. Valid after {@link #update}. */
    public int stride = 1;
    /** Whether to interpolate positions/colours by partial tick this frame. Valid after {@link #update}. */
    public boolean interpolate = true;

    /**
     * @return true if the effect should be refreshed this frame; false to leave its instances untouched.
     */
    public boolean update(Quaternionf camRot, double distanceSq) {
        frame++;

       //Fabulous gets a pass. It's uses proper shaders
        if (Minecraft.useShaderTransparency()) {
            stride = 1;
            interpolate = true;
            lastCamRot.set(camRot);
            hasLast = true;
            lastUpdate = frame;
            return true;
        }

        boolean camStill = hasLast && Math.abs(lastCamRot.dot(camRot)) > STILL_DOT;

        int interval;
        boolean refreshOnCamMove;
        if (distanceSq < MID_SQ) {
            interval = 1;
            stride = 1;
            interpolate = true;
            refreshOnCamMove = true;
        } else if (distanceSq < FAR_SQ) {
            interval = 2;
            stride = 1;
            interpolate = true;
            refreshOnCamMove = true;
        } else if (distanceSq < VERY_FAR_SQ) {
            interval = 3;
            stride = 2;
            interpolate = false;
            refreshOnCamMove = false;
        } else {
            interval = 6;
            stride = 4;
            interpolate = false;
            refreshOnCamMove = false;
        }

        boolean due = frame - lastUpdate >= interval;
        if (!due && !(refreshOnCamMove && !camStill)) {
            return false;
        }
        lastUpdate = frame;
        lastCamRot.set(camRot);
        hasLast = true;
        return true;
    }
}
