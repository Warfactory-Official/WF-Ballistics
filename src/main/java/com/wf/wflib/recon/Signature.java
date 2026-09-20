package com.wf.wflib.recon;

/**
 * What a target emits or reflects, per {@link Band}.
 *
 * @param acoustic how loud this thing is. One number for both acoustic bands, because it is one noise: the
 *      band decides whether it is being carried by air ({@link Band#ACOUSTIC}) or by water
 *      ({@link Band#SONAR}). A countermeasure still separates them, since it declares one band and is
 *      consulted only for that one.
 */
public record Signature(float radarRcs, float seismicEnergy, float thermal, float acoustic, float em) {

    /**
     * Emits and reflects nothing in any band. Never detected.
     */
    public static final Signature NONE = new Signature(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

    public static Signature radar(float rcs) {
        return new Signature(rcs, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public float on(Band band) {
        return switch (band) {
            case RADAR -> radarRcs;
            case SEISMIC -> seismicEnergy;
            case THERMAL -> thermal;
            case ACOUSTIC, SONAR -> acoustic;
            case EM -> em;
        };
    }

    public Signature with(Band band, float value) {
        float v = Math.max(0.0f, value);
        return switch (band) {
            case RADAR -> new Signature(v, seismicEnergy, thermal, acoustic, em);
            case SEISMIC -> new Signature(radarRcs, v, thermal, acoustic, em);
            case THERMAL -> new Signature(radarRcs, seismicEnergy, v, acoustic, em);
            case ACOUSTIC, SONAR -> new Signature(radarRcs, seismicEnergy, thermal, v, em);
            case EM -> new Signature(radarRcs, seismicEnergy, thermal, acoustic, v);
        };
    }

    public Signature scaled(Band band, float factor) {
        return with(band, on(band) * factor);
    }

    /**
     * @return this signature with every band scaled. Used by broad effects like a hull coating, where picking
     *      out one band would be arbitrary.
     */
    public Signature scaledAll(float factor) {
        float f = Math.max(0.0f, factor);
        return new Signature(radarRcs * f, seismicEnergy * f, thermal * f, acoustic * f, em * f);
    }
}
