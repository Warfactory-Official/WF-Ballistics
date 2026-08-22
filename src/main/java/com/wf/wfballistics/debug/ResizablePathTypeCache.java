package com.wf.wfballistics.debug;

/**
 * Lets a benchmark resize vanilla's path-type cache without restarting the server, so the sizes can be
 * compared against one world in one run instead of four.
 *
 * <p>Lives outside the mixin package on purpose: mixin refuses to let anything reference a class inside a
 * declared mixin package directly, and the bench command has to be able to cast to this.
 */
public interface ResizablePathTypeCache {

    void wfballistics$resize(int entries);

    int wfballistics$capacity();
}
