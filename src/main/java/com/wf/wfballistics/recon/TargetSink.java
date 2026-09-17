package com.wf.wfballistics.recon;

import com.wf.wfballistics.recon.snapshot.TargetSnapshot;

/** Where a {@link TargetSource} puts what it found. */
@FunctionalInterface
public interface TargetSink {
    void accept(TargetSnapshot snapshot);
}
