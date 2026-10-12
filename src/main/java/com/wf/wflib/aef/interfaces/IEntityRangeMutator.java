package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;

/** Entity-damage radius, independent of block-damage radius. */
public interface IEntityRangeMutator {

    float mutateRange(ExplosionAEF explosion, float range);
}
