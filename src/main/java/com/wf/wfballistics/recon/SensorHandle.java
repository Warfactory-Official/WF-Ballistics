package com.wf.wfballistics.recon;

import com.wf.wfballistics.recon.env.Atmosphere;
import com.wf.wfballistics.recon.snapshot.ReconTerrain;
import com.wf.wfballistics.recon.snapshot.ReconTerrainCache;
import com.wf.wfballistics.recon.snapshot.SensorSnapshot;
import net.minecraft.core.BlockPos;

/** One registered sensor, from the network's side. */
public final class SensorHandle {

    private final long id;
    private final BlockPos pos;
    private SensorSpec spec;
    private ReconTerrain terrain = ReconTerrain.EMPTY;
    private long terrainStamp;
    private long lastRefresh;
    private long lastSweep;

    SensorHandle(BlockPos pos, SensorSpec spec, long gameTime) {
        this.id = pos.asLong();
        this.pos = pos.immutable();
        this.spec = spec;
        this.lastRefresh = gameTime;
        this.lastSweep = gameTime - spec.sweepTicks();
        this.terrainStamp = gameTime - ReconTerrainCache.FIELD_MAX_AGE - 1;
    }

    public long id() {
        return id;
    }

    public BlockPos pos() {
        return pos;
    }

    public SensorSpec spec() {
        return spec;
    }

    public ReconTerrain terrain() {
        return terrain;
    }

    void refresh(SensorSpec updated, long gameTime) {
        this.spec = updated;
        this.lastRefresh = gameTime;
    }

    long lastRefresh() {
        return lastRefresh;
    }

    /** <b>Sweeps are phase-aligned to the world clock, not free-running from whenever the sensor was built.</b> */
    boolean sweepDue(long gameTime) {
        return spec.emitting() && gameTime % spec.sweepTicks() == 0 && gameTime != lastSweep;
    }

    void swept(long gameTime) {
        this.lastSweep = gameTime;
    }

    boolean terrainStale(long gameTime) {
        return gameTime - terrainStamp > ReconTerrainCache.FIELD_MAX_AGE;
    }

    void adoptTerrain(ReconTerrain built, long gameTime) {
        this.terrain = built;
        this.terrainStamp = gameTime;
    }

    /**
     * @param hops what the grid says this sensor's data costs to get home. Passed in rather than held because
     *      the topology is the network's business and can change between one sweep and the next without
     *      the sensor knowing.
     * @param atmos the conditions, already carrying whatever correction this network's met probes are worth.
     *      Passed in for the same reason: whether anybody is measuring the weather is a fact about the
     *      network, and a sensor that cached it would keep applying a correction after the station
     *      that justified it was destroyed.
     */
    public SensorSnapshot snapshot(long gameTime, int hops, Atmosphere atmos) {
        return new SensorSnapshot(id, pos.getX() + 0.5, pos.getY() + 0.5 + spec.mastHeight(), pos.getZ() + 0.5,
                spec, hops, atmos, gameTime);
    }
}
