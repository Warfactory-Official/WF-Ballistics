package com.wf.wflib.recon.grid;

/** Grid node block entity whose {@link GridNode#label()} a player may set (PDA terminal {@code rename node}). */
public interface NamedNode {

    /** Empty = default label. Next registration carries it. */
    void rename(String name);
}
