package com.wf.wfballistics.recon.env;

import net.minecraft.core.BlockPos;

/** A met probe as the network sees it: a position, how far its sample is good for, and when it last said so. */
public final class WeatherStation {

    /** Fraction of the range within which the sample is taken at face value. */
    public static final double FULL_FRACTION = 0.5;

    private final long id;
    private final BlockPos pos;
    private double range;
    private long lastRefresh;

    public WeatherStation(BlockPos pos, double range, long gameTime) {
        this.id = pos.asLong();
        this.pos = pos.immutable();
        this.range = range;
        this.lastRefresh = gameTime;
    }

    public long id() {
        return id;
    }

    public BlockPos pos() {
        return pos;
    }

    public double range() {
        return range;
    }

    public long lastRefresh() {
        return lastRefresh;
    }

    public void refresh(double updated, long gameTime) {
        this.range = updated;
        this.lastRefresh = gameTime;
    }

    /**
     * @return how well this station's sample describes that position, 0 to 1.
     */
    public double coverageAt(double x, double y, double z) {
        double dx = pos.getX() + 0.5 - x;
        double dy = pos.getY() + 0.5 - y;
        double dz = pos.getZ() + 0.5 - z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double full = range * FULL_FRACTION;
        if (dist <= full) {
            return 1.0;
        }
        if (dist >= range) {
            return 0.0;
        }
        return (range - dist) / (range - full);
    }
}
