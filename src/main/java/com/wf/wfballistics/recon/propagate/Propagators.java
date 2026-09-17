package com.wf.wfballistics.recon.propagate;

import com.wf.wfballistics.recon.Band;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/** Which model runs for which band. */
public final class Propagators {

    private static final Map<Band, Propagator> BY_BAND = new EnumMap<>(Band.class);

    static {
        register(RadarPropagator.INSTANCE);
        register(SeismicPropagator.INSTANCE);
        register(ThermalPropagator.INSTANCE);
        register(SonarPropagator.INSTANCE);
    }

    private Propagators() {
    }

    public static void register(Propagator propagator) {
        BY_BAND.put(propagator.band(), propagator);
    }

    @Nullable
    public static Propagator of(Band band) {
        return BY_BAND.get(band);
    }
}
