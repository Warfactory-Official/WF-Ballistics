package com.wf.wflib.recon.propagate;

import com.wf.wflib.recon.Band;
import com.wf.wflib.recon.detect.Plot;
import com.wf.wflib.recon.snapshot.ReconTerrain;
import com.wf.wflib.recon.snapshot.SensorSnapshot;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import org.jetbrains.annotations.Nullable;

/** How much of a target reaches a receiver, and what the receiver can say about it. */
public interface Propagator {

    Band band();

    /**
     * @param gameTime the tick this sweep is for. Also the only entropy a propagator gets: measurement noise
     *      is derived from it and the two ids, so a pass is reproducible and needs no RNG to share.
     * @return what the sensor made of this target, or null if it made nothing of it.
     */
    @Nullable
    Plot detect(SensorSnapshot sensor, TargetSnapshot target, ReconTerrain terrain, long gameTime);
}
