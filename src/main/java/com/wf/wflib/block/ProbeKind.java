package com.wf.wflib.block;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.SensorSpec;
import org.jetbrains.annotations.Nullable;

/** The five probes, as a table. */
public enum ProbeKind {

    /** The workhorse. */
    RADAR("radar", Band.RADAR, 256.0, 6.0, 128.0, 8.0, 48, 6000, 600),
    /** A geophone. */
    SEISMIC("seismic", Band.SEISMIC, 384.0, 0.0, 96.0, 4.0, 12, 4000, 1200),
    /** An imager. */
    THERMAL("thermal", Band.THERMAL, 96.0, 3.0, 112.0, 6.0, 24, 3000, 300),
    /** A met station. */
    WEATHER("weather", null, 192.0, 4.0, 144.0, 6.0, 8, 2000, 200),
    /**
     * A hydrophone. The cheapest thing on the grid while it listens and the most expensive the moment it
     * pings; reach matches the geophone's so no collection volume grows past the current worst case.
     */
    SONAR("sonar", Band.SONAR, 384.0, 0.0, 96.0, 4.0, 10, 8000, 1200);

    /** What a ping costs, as a multiple of the idle draw. */
    public static final int PING_DRAW = 12;
    /** Ticks between looks while pinging. A pulse and its echo take longer than merely listening. */
    public static final int PING_SWEEP = 40;

    private final String id;
    private final Band band;
    private final double baseRange;
    private final double sensorMast;
    private final double linkRange;
    private final double linkMast;
    private final int feDraw;
    private final int feCapacity;
    private final int calibrationTicks;

    ProbeKind(String id, @Nullable Band band, double baseRange, double sensorMast, double linkRange,
              double linkMast, int feDraw, int feCapacity, int calibrationTicks) {
        this.id = id;
        this.band = band;
        this.baseRange = baseRange;
        this.sensorMast = sensorMast;
        this.linkRange = linkRange;
        this.linkMast = linkMast;
        this.feDraw = feDraw;
        this.feCapacity = feCapacity;
        this.calibrationTicks = calibrationTicks;
    }

    public String id() {
        return id;
    }

    public String blockName() {
        return "probe_" + id;
    }

    /**
     * @return the band this probe works in, or null for {@link #WEATHER}, which works in none.
     */
    @Nullable
    public Band band() {
        return band;
    }

    /**
     * @return true if this probe registers a sensor. False for a met station, which registers a station
     *      instead: the one branch in the block entity, and the only place the fourth kind differs in behaviour
     *      rather than in numbers.
     */
    public boolean sensing() {
        return band != null;
    }

    /**
     * @return true if this probe has to be standing in water to work at all. The whole identity of a water
     *      probe, and the one gate a dry placement reports instead of silently registering nothing.
     */
    public boolean underwater() {
        return band == Band.SONAR;
    }

    /**
     * @return what to call this probe in a readout: its band, or {@code WEATHER} for the one with none.
     */
    public String label() {
        return band == null ? "WEATHER" : band.name();
    }

    public double baseRange() {
        return baseRange;
    }

    /**
     * @return blocks over which a met station's sample is taken as describing the ground. The same number as
     *      {@link #baseRange()} and named separately because it means something entirely different; see
     *      {@link #WEATHER}.
     */
    public double stationRange() {
        return baseRange;
    }

    /**
     * @return blocks the radio reaches in one hop. Not the same as {@link #baseRange()}, and deliberately
     *      shorter: a probe covers more ground than it can relay, so a grid has to be built forward rather than
     *      sprayed outward.
     */
    public double linkRange() {
        return linkRange;
    }

    /**
     * @return blocks above the block that <em>links</em> are measured from. See {@link #SEISMIC} for why this
     *      is not the sensing mast.
     */
    public double linkMast() {
        return linkMast;
    }

    /**
     * @return FE consumed per tick while online.
     */
    public int feDraw() {
        return feDraw;
    }

    public int feCapacity() {
        return feCapacity;
    }

    /**
     * @return ticks of warm-up before this contributes anything.
     */
    public int calibrationTicks() {
        return calibrationTicks;
    }

    /**
     * @return this probe's sensor, on the given network.
     * @throws IllegalStateException for {@link #WEATHER}, which has none. Guarded by {@link #sensing()} at the
     *      one call site rather than silently returning something inert, because a
     *      met station that quietly registered a blind sensor would show up as a
     *      sensor count nobody could account for.
     */
    public SensorSpec spec(long netId) {
        return spec(netId, false);
    }

    /**
     * @param pinging whether this set is radiating. Only {@link #SONAR} has anything to do with it: every
     *      other probe either always radiates or never does, and neither is a decision the player gets
     *      to make tick by tick.
     */
    public SensorSpec spec(long netId, boolean pinging) {
        if (band == null) {
            throw new IllegalStateException("probe kind " + id + " registers a station, not a sensor");
        }
        return switch (band) {
            case RADAR -> SensorSpec.surveillanceRadar(netId, baseRange).withMast(sensorMast).withSweep(20);
            case SEISMIC -> SensorSpec.seismicArray(netId, baseRange);
            case THERMAL -> SensorSpec.thermalImager(netId, baseRange).withMast(sensorMast);
            case SONAR -> pinging
                    ? SensorSpec.sonarArray(netId, baseRange).withRadiating(true).withSweep(PING_SWEEP)
                    : SensorSpec.sonarArray(netId, baseRange);
            default -> throw new IllegalStateException("no probe spec for band " + band);
        };
    }
}
