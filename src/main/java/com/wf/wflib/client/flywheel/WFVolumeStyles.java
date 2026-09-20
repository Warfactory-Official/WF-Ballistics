package com.wf.wflib.client.flywheel;

import com.wf.gemrender.volume.VolumeQuality;
import com.wf.gemrender.volume.VolumeStyle;
import com.wf.wflib.config.WFClientConfig;

/**
 * The raymarched counterpart to {@link WFParticleStyles}: what a gas cloud looks like when it is a density field
 * rather than a pile of sprites.
 */
public final class WFVolumeStyles {

    /** Extinction per block at full density: the number that decides how solid a cloud looks. */
    private static final float DENSITY = 1.2F;

    /** Noise frequency, in cycles per block, before the shader's own per-octave scaling. */
    private static final float DETAIL = 0.6F;

    /** Blocks per second the field drifts upward, matching the hand-ticked mist's flat {@code yd}. */
    private static final float RISE = 0.4F;

    /** Henyey-Greenstein asymmetry. */
    private static final float PHASE = 0.3F;

    /** Floor under the sun march, so the shadowed interior goes dim rather than black. */
    private static final float AMBIENT = 0.35F;

    /** Fraction of each half-extent given over to the edge falloff, so the box never shows as a box. */
    private static final float EDGE = 0.35F;

    private WFVolumeStyles() {
    }

    /**
     * Gas and mist as a volume.
     *
     * @param blockLight block light 0-15 where the cloud sits
     * @param skyLight sky light 0-15 where the cloud sits
     */
    public static VolumeStyle mist(int rgb, int blockLight, int skyLight) {
        return VolumeStyle.builder()
                .density(DENSITY)
                .tint(rgb)
                .detail(DETAIL)
                .edge(EDGE)
                .rise(RISE)
                .phase(PHASE)
                .ambient(AMBIENT)
                .light(blockLight / 16.0F, skyLight / 16.0F)
                .quality(VolumeQuality.parse(WFClientConfig.volumetricGasQuality()))
                .build();
    }
}
