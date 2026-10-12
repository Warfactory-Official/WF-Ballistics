package com.wf.wflib.debug;

/** Resizes vanilla's path-type cache without a server restart: compare sizes in one world/run, not N. */
public interface ResizablePathTypeCache {

    void wflib$resize(int entries);

    int wflib$capacity();
}
