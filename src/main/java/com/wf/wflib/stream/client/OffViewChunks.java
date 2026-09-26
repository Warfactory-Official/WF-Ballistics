package com.wf.wflib.stream.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** The client chunk cache's second store: chunks the server sent outside the ring's window. */
public interface OffViewChunks {

    LongOpenHashSet wfOffViewPositions();

    /** @return every position this cache answers for, ring and off-view */
    LongOpenHashSet wfHeldPositions();
}
