package com.wf.wflib.debug;

/**
 * Lets a benchmark resize vanilla's path-type cache without restarting the server, so the sizes can be compared
 * against one world in one run instead of four.
 */
public interface ResizablePathTypeCache {

    void wflib$resize(int entries);

    int wflib$capacity();
}
