package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;

/**
 * Scales the entity-damage radius independently of the block-damage radius, letting a charge shred entities
 * over a wider area than it cracks terrain (or the reverse). Applied by the entity processors before they
 * gather targets.
 */
public interface IEntityRangeMutator {

    float mutateRange(ExplosionAEF explosion, float range);
}
