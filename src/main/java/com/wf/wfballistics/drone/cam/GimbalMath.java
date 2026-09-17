package com.wf.wfballistics.drone.cam;

/** How a gimbal moves toward where it has been asked to look. */
public final class GimbalMath {

    /** Fraction of the remaining error covered per tick, before the rate cap bites. */
    public static final float RESPONSE = 0.4f;

    /** Below this the gimbal has arrived. */
    public static final float SETTLED = 0.05f;

    /** Slowest the gimbal will ever move while it still has somewhere to go, as a fraction of top speed. */
    public static final float FLOOR = 0.3f;

    private GimbalMath() {
    }

    /**
     * @param error degrees still to cover, already wrapped by the caller if it is a heading
     * @param ticks elapsed time; fractional on the client, whole on the server
     * @param maxRate the gimbal's top speed in degrees per tick
     * @return how far to move this step, signed
     */
    public static float approach(float error, float ticks, float maxRate) {
        if (ticks <= 0.0f) {
            return 0.0f;
        }
        float magnitude = Math.abs(error);
        if (magnitude <= SETTLED) {
            return error;
        }
        float factor = 1.0f - (float) Math.pow(1.0f - RESPONSE, ticks);
        float step = Math.max(magnitude * factor, maxRate * FLOOR * ticks);
        step = Math.min(step, Math.min(magnitude, maxRate * ticks));
        return Math.copySign(step, error);
    }
}
