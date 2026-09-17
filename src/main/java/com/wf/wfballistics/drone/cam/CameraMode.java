package com.wf.wfballistics.drone.cam;

import net.minecraft.resources.ResourceLocation;

/**
 * What the sensor head is looking through.
 *
 * @param chain the post chain under {@code assets/wfballistics/shaders/post/}
 * @param drawMul multiplier on the camera's idle power draw. Cooled thermal costs more than a photodiode,
 *      and a drone that can loiter all night on optical but not on thermal is a decision the
 *      operator gets to make rather than one the mod makes for them
 * @param noiseMul how much sensor noise this head shows for the same link quality. Thermal is the noisiest
 *      because it is the one working hardest for its photons
 */
public enum CameraMode {

    /** Daylight television. The reference: everything else is scaled against it. */
    OPTICAL("optical", "wf_cam_optical", 1.0f, 1.0f),
    /** Uncooled microbolometer. */
    THERMAL("thermal", "wf_cam_thermal", 2.2f, 1.6f),
    /** Image-intensified low light. */
    LOWLIGHT("lowlight", "wf_cam_lowlight", 1.3f, 2.4f);

    public static final CameraMode[] VALUES = values();

    private final String id;
    private final String chain;
    private final float drawMul;
    private final float noiseMul;

    CameraMode(String id, String chain, float drawMul, float noiseMul) {
        this.id = id;
        this.chain = chain;
        this.drawMul = drawMul;
        this.noiseMul = noiseMul;
    }

    public String id() {
        return this.id;
    }

    public float drawMultiplier() {
        return this.drawMul;
    }

    public float noiseMultiplier() {
        return this.noiseMul;
    }

    /**
     * @return the post chain this mode is processed by. Client-side use only; kept here so the mapping lives
     *      beside the mode rather than in a switch the renderer has to remember to extend.
     */
    public ResourceLocation chain() {
        return ResourceLocation.fromNamespaceAndPath("wfballistics", "shaders/post/" + this.chain + ".json");
    }

    public static CameraMode byOrdinal(int ordinal) {
        return VALUES[Math.floorMod(ordinal, VALUES.length)];
    }
}
