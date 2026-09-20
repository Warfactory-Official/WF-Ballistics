package com.wf.wflib.recon.env;

/**
 * The conditions a sensor is working in, and how much of them anybody actually measured.
 *
 * @param solar sun elevation, {@code +1} at local noon, {@code -1} at midnight, {@code 0} at dawn and dusk
 *      and in any dimension with no sky. The only thing time of day is allowed to be, so that
 *      "night" is a continuous quantity rather than a flag with a cliff at dusk.
 * @param rain rainfall, 0 to 1. The level's own rain level, so it ramps over the several seconds vanilla
 *      takes to start and stop a shower rather than snapping.
 * @param thunder thunder, 0 to 1, on top of {@code rain} rather than instead of it.
 * @param coverage how well a weather station on this network is measuring this sensor's conditions, 0 to 1.
 *      Zero for every sensor that is not on a grid with a met probe: a turret's own set included.
 */
public record Atmosphere(double solar, double rain, double thunder, double coverage) {

    /** Clear, dry, and level with the horizon. */
    public static final Atmosphere STANDARD = new Atmosphere(0.0, 0.0, 0.0, 0.0);

    /** Fraction of an environmental loss that measuring it recovers. */
    public static final double RECOVERY = 0.6;

    /** Degrees per unit of biome temperature, and the offset. */
    public static final double TEMP_SCALE = 30.0;
    public static final double TEMP_OFFSET = -9.0;
    /** Degrees between local noon and midnight in a bone-dry biome. */
    public static final double DIURNAL_SWING = 12.0;
    /** Degrees the surface loses in full rain. */
    public static final double RAIN_CHILL = 6.0;

    /**
     * @return ambient surface temperature in degrees, for ground of this climate under these conditions.
     * @param baseTemp the biome's own temperature, in vanilla units.
     * @param downfall the biome's own downfall, 0 to 1. Enters as {@code 1 - downfall}: humidity is thermal
     *      mass, and thermal mass is what stops ground swinging between noon and midnight.
     */
    public double ambientC(double baseTemp, double downfall) {
        double arid = 1.0 - clamp01(downfall);
        return TEMP_SCALE * baseTemp + TEMP_OFFSET
                + DIURNAL_SWING * solar * arid
                - RAIN_CHILL * clamp01(rain);
    }

    /**
     * One band's raw environment factor, as the sensor actually gets to use it.
     *
     * @param raw a multiplier on effective detection range: below one is a penalty, above one a bonus.
     */
    public double correct(double raw) {
        double c = clamp01(coverage);
        if (raw >= 1.0) {
            return 1.0 + (raw - 1.0) * c;
        }
        return raw + (1.0 - raw) * RECOVERY * c;
    }

    /**
     * @return the fraction of a loss this sensor's processing can filter out, for the few effects that are not
     *      a range multiplier: radar's clutter floor, which rain raises and a rain rate lets you subtract.
     */
    public double share() {
        return RECOVERY * clamp01(coverage);
    }

    public boolean measured() {
        return coverage > 0.0;
    }

    public Atmosphere withCoverage(double c) {
        return new Atmosphere(solar, rain, thunder, clamp01(c));
    }

    /**
     * @return "clear", "raining" or "thundering", for a readout. Derived rather than stored, because the
     *      numbers are the state and a word that could disagree with them would be worse than no word.
     */
    public String conditions() {
        if (thunder > 0.1) {
            return "thundering";
        }
        return rain > 0.1 ? "raining" : "clear";
    }

    /**
     * @return "night" side of the clock, for a readout that would otherwise print a bare {@code -0.71}.
     */
    public String daypart() {
        if (solar > 0.5) {
            return "midday";
        }
        if (solar > 0.05) {
            return "day";
        }
        if (solar < -0.5) {
            return "deep night";
        }
        return solar < -0.05 ? "night" : "twilight";
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
