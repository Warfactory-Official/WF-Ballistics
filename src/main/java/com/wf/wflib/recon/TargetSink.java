package com.wf.wflib.recon;

import com.wf.wflib.recon.snapshot.TargetSnapshot;

/** Where a {@link TargetSource} puts what it found. */
@FunctionalInterface
public interface TargetSink {
    void accept(TargetSnapshot snapshot);
}
