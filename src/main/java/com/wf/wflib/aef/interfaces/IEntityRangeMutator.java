package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;

/**
 * Scales the entity-damage radius independently of the block-damage radius, letting a charge shred entities over a
 * wider area than it cracks terrain (or the reverse).
 */
public interface IEntityRangeMutator {

    float mutateRange(ExplosionAEF explosion, float range);
}
