package com.wf.wflib.client.cam;

/** The out-of-view chunks this client is holding on behalf of a drone feed. */
public interface FeedChunkStore {

    /** Release pinned chunks no live feed is near any more. */
    void wfCamSweepPins();

    void wfCamClearPins();

    int wfCamPinnedCount();
}
