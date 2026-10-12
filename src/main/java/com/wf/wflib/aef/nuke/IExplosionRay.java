package com.wf.wflib.aef.nuke;

/** Chunk prefetch (own time budget) separate from destruction. */
public interface IExplosionRay {
    void cacheChunksTick(int processTimeMs);

    void destructionTick(int processTimeMs);

    void cancel();

    boolean isComplete();
}
