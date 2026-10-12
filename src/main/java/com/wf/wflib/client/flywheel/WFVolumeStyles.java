package com.wf.wflib.client.flywheel;

import com.wf.gemrender.volume.VolumeQuality;
import com.wf.gemrender.volume.VolumeStyle;
import com.wf.wflib.config.WFClientConfig;

/** Raymarched counterpart to {@link WFParticleStyles}: density field, not sprites. */
public final class WFVolumeStyles {

    /** Extinction/block at full density. */
    private static final float DENSITY = 1.2F;

    /** Cycles/block, before the shader's own per-octave scaling. */
    private static final float DETAIL = 0.6F;

    /** Blocks/sec drift upward; matches the sprite mist's flat {@code yd}. */
    private static final float RISE = 0.4F;

    /** Henyey-Greenstein asymmetry. */
    private static final float PHASE = 0.3F;

    /** Floor under the sun march: shadowed interior dims, never goes black. */
    private static final float AMBIENT = 0.35F;

    /** Fraction of each half-extent given to edge falloff, so the box never reads as a box. */
    private static final float EDGE = 0.35F;

    private WFVolumeStyles() {
    }

    /**
     * Gas and mist as a volume.
     * @param blockLight 0-15
     * @param skyLight 0-15
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
