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
    /** A hydrophone: cheapest idle, costliest while pinging. Reach == {@link #SEISMIC}'s: caps worst-case volume. */
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

    @Nullable
    public Band band() {
        return band;
    }

    public boolean sensing() {
        return band != null;
    }

    public boolean underwater() {
        return band == Band.SONAR;
    }

    public String label() {
        return band == null ? "WEATHER" : band.name();
    }

    public double baseRange() {
        return baseRange;
    }

    public double stationRange() {
        return baseRange;
    }

    /** < {@link #baseRange()} for every kind but {@link #THERMAL}, which relays further than it senses. */
    public double linkRange() {
        return linkRange;
    }

    /** Independent of the private sensing-mast height. */
    public double linkMast() {
        return linkMast;
    }

    public int feDraw() {
        return feDraw;
    }

    public int feCapacity() {
        return feCapacity;
    }

    public int calibrationTicks() {
        return calibrationTicks;
    }

    /** Throws for {@link #WEATHER}; callers must guard with {@link #sensing()} first. */
    public SensorSpec spec(long netId) {
        return spec(netId, false);
    }

    /** {@code pinging} matters only for {@link #SONAR}; other kinds always or never radiate. */
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
