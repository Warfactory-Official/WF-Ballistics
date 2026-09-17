package com.wf.wfballistics.recon;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A block whose network can be named, rather than only worked out. */
public interface ReconBound {

    /**
     * @return the identity this block was told to use, or null if it is still working one out for itself.
     */
    @Nullable
    UUID boundNet();

    /**
     * Put this block on a network, or hand it back its own judgement.
     *
     * @param id the identity to use, or null to go back to deriving one. World thread only, and effective from
     *      the call: a block that caches a resolved net re-resolves it here rather than at its next
     *      scheduled refresh, so {@link #netId()} is right the moment this returns.
     */
    void bindNet(@Nullable UUID id);

    /**
     * @return the net this block is on right now, bound or derived. Folded through {@link SourceIds}, so it is
     *      comparable with a transponder code and with every other net id in the mod.
     */
    long netId();

    /**
     * @return an identity this block can hand to another, or null if it has none to give.
     */
    @Nullable
    default UUID identity() {
        return boundNet();
    }
}
