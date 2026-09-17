package com.wf.wfballistics.orbital;

/** The economy, as durations rather than rates. */
public final class OrbitalConfig {

    /** Ticks between a satellite's housekeeping ticks. */
    public static final int SLOW_TICK = 20;

    /** Two minutes a revolution at 10 blocks/tick: overhead for a few seconds, and the best pictures. */
    public static final double LEO_ALTITUDE = 1500.0;
    public static final double LEO_PERIOD = 2400.0;
    /** Twenty passes before the track comes back to a strip it has already flown. */
    public static final double LEO_DRIFT = 1200.0;

    /** Five minutes a revolution: a longer look over more ground, at worse resolution. */
    public static final double MEO_ALTITUDE = 4000.0;
    public static final double MEO_PERIOD = 6000.0;
    public static final double MEO_DRIFT = 2000.0;

    /** Ten minutes a revolution: a long dwell, an awkward intercept, and coarse everything. */
    public static final double HEO_ALTITUDE = 10000.0;
    public static final double HEO_PERIOD = 12000.0;
    public static final double HEO_DRIFT = 3000.0;

    // --- fuel: a stock ------------------------------------------------------------------------------

    /** A full tank. The unit is arbitrary; what matters is what it buys below. */
    public static final double FUEL_CAPACITY = 1200.0;
    /** Holding station, per tick. */
    public static final double PARK_FUEL_PER_TICK = 0.03;
    /** One manoeuvre: an evasion, or a retarget. */
    public static final double MANOEUVRE_FUEL = 200.0;

    // --- power: a rate ------------------------------------------------------------------------------

    /** A full battery. */
    public static final double POWER_CAPACITY = 1000.0;
    /** Charge while it is day, per tick. */
    public static final double SOLAR_PER_TICK = 0.20;
    /** The housekeeping draw of simply existing. */
    public static final double IDLE_POWER_PER_TICK = 0.02;

    // --- payload draws ------------------------------------------------------------------------------

    /** One active radar sweep. */
    public static final double RECON_SWEEP_POWER = 6.0;
    /** Ticks between orbital radar sweeps. */
    public static final int RECON_SWEEP_TICKS = 40;
    /** Carrying somebody else's picture, per slow tick. Cheap: a relay is a wire, not an instrument. */
    public static final double RELAY_POWER_PER_SLOW_TICK = 2.0;

    // --- mining -------------------------------------------------------------------------------------

    /** Cargo units a miner accrues per slow tick while it has the power to run. */
    public static final double MINER_UNITS_PER_SLOW_TICK = 0.25;
    /** Running the drill, per slow tick. Comparable to a radar sweep: mining is work, not scenery. */
    public static final double MINER_POWER_PER_SLOW_TICK = 5.0;
    /** Units a hold takes before the drill stops. */
    public static final int MINER_HOLD = 108;
    /** Fuel a propelled lander burns. A ballistic capsule costs nothing but the shell. */
    public static final double LANDER_FUEL = 120.0;
    /** Blocks of scatter per 1000 blocks of altitude for a ballistic capsule. */
    public static final double BALLISTIC_CEP_PER_KM = 140.0;
    /** Blocks either side of the aim point a propelled lander will accept a pad at. */
    public static final double PAD_CAPTURE_RANGE = 96.0;

    // --- things that come down ----------------------------------------------------------------------

    /** Blocks downrange anything released from orbit starts its run. */
    public static final double RELEASE_DISTANCE = 1100.0;
    /** Altitude the simulated leg is flown at. */
    public static final double RELEASE_ALTITUDE = 300.0;
    /** Blocks per tick. A rod arrives in about twenty seconds of flight, all of it visible. */
    public static final double ROD_SPEED = 3.0;
    /** Blocks per tick. A cargo shuttle is slower, and correspondingly easier to shoot at. */
    public static final double SHUTTLE_SPEED = 1.2;
    /** Rods in a salvo unless told otherwise, and the spread they arrive with. */
    public static final int ROD_SALVO = 3;
    public static final double ROD_DISPERSION = 24.0;
    /** Power to release one rod. Cheap: the expensive part is that the rod is gone. */
    public static final double ROD_POWER = 40.0;

    // --- the death ray ------------------------------------------------------------------------------

    /** Charge a shot needs, drawn from the battery at whatever rate the panels and the bird allow. */
    public static final double LASER_CHARGE = 1400.0;
    /** Charge taken per slow tick while charging, subject to the battery having it. */
    public static final double LASER_CHARGE_RATE = 8.0;
    /** Blast size at full charge in clear air. Weather takes it down from here. */
    public static final float LASER_BLAST = 9.0f;
    /** Attenuation below which the shot is refused rather than wasted. */
    public static final double LASER_MIN_TRANSMISSION = 0.45;

    // --- counter-space ------------------------------------------------------------------------------

    /** Blocks per slow tick a cheap hunter closes on its target. */
    public static final double HUNTER_CLOSE_RATE = 90.0;
    /** Separation at which the hunter is committed and the defender is out of decisions. */
    public static final double HUNTER_KILL_RANGE = 120.0;
    /** Power a bird spends noticing a hunter and making the burn. Evading is not free even with a full tank. */
    public static final double EVADE_POWER = 60.0;
    /** Power a point-defence engagement costs, and the separation it can reach out to. */
    public static final double POINT_DEFENCE_POWER = 45.0;
    public static final double POINT_DEFENCE_RANGE = 400.0;
    /** Blocks a jammer denies the downlink over. */
    public static final double JAMMER_RANGE = 2200.0;
    /** Whether an irreversible orbital kill needs a declared war. */
    public static boolean requireWarForOrbitalKills = false;

    // --- imagery ------------------------------------------------------------------------------------

    /** Columns imaged per slow tick. */
    public static final int IMAGERY_COLUMNS_PER_SLOW_TICK = 96;
    /** Running the camera, per slow tick. Comparable to a radar sweep: looking is work. */
    public static final double IMAGERY_POWER_PER_SLOW_TICK = 6.0;
    /** Chunks held around the camera, and therefore the width of the strip it can actually image. */
    public static final int IMAGERY_PRELOAD_CHUNKS = 8;

    // --- geometry -----------------------------------------------------------------------------------

    /**
     * @return blocks of ground radius a footprint covers, from altitude. Low and narrow, high and wide: the
     *      trade every orbit class is really about.
     */
    public static double swathFor(double altitude) {
        return altitude * 0.34;
    }

    /**
     * @return the error radius of a fix from this altitude, in blocks. Roughly constant across a footprint
     *      rather than growing with range, because an orbital instrument measures a cell and not a bearing.
     */
    public static double resolutionFor(double altitude) {
        return altitude * 0.016;
    }

    /**
     * @return the smallest cross-section this altitude can pick out. A low bird sees missiles and vehicles;
     *      a high one needs something considerably larger before it says anything at all.
     */
    public static double minRcsFor(double altitude) {
        return 0.5 * altitude / LEO_ALTITUDE;
    }

    /**
     * @return how far from a ground station a bird can be and still be talked to. Geometry over time is the
     *      constraint, not distance: a bird on the far side of its track is simply out of contact until the next
     *      pass, and the counterplay is more ground stations, or a relay.
     */
    public static double contactRangeFor(double altitude) {
        return altitude * 2.0;
    }

    /**
     * @return how far a relay bird can reach along the network. Well beyond its own contact range: reaching
     *      further than anybody else is the entire product a relay sells, and a high relay sells more of it.
     */
    public static double relayReachFor(double altitude) {
        return altitude * 6.0;
    }

    /** Blocks from a ground station within which someone else's bird is seen well enough to catalogue. */
    public static final double SURVEILLANCE_RANGE = 3000.0;
    /** Entries a network's catalogue holds before the oldest is dropped. */
    public static final int CATALOGUE_CAPACITY = 64;

    // --- relay --------------------------------------------------------------------------------------

    /** Error multiplier a track picks up crossing a relay. */
    public static final double RELAY_ERROR = 1.35;
    /**
     * Links a contact is charged for crossing a relay, and the thing that makes the loop impossible rather than
     * merely bounded.
     */
    public static final int RELAY_HOP_COST = com.wf.wfballistics.recon.grid.ReconGrid.MAX_HOPS + 1;

    private OrbitalConfig() {
    }
}
