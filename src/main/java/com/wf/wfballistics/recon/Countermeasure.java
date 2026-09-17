package com.wf.wfballistics.recon;

import com.wf.wfballistics.recon.snapshot.SensorSnapshot;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;

/** Something a target carries that reduces or distorts what it presents to a sensor. */
public interface Countermeasure {

    /**
     * @return the band this works in. A countermeasure is only consulted for its own band's sensors.
     */
    Band band();

    /**
     * @param in what the target presents so far; countermeasures compose in the order they are carried
     * @param self the target carrying this
     * @param sensor the sensor being defeated
     * @return the modified signature. Never null.
     */
    Signature modify(Signature in, TargetSnapshot self, SensorSnapshot sensor);
}
