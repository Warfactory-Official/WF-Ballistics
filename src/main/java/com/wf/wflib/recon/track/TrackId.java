package com.wf.wflib.recon.track;

/** A handle on one track, unique within a network for as long as that track lives. */
public record TrackId(long value) {

    @Override
    public String toString() {
        return "T" + value;
    }
}
