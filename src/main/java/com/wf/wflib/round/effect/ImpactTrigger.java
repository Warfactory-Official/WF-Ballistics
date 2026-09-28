package com.wf.wflib.round.effect;

import org.jetbrains.annotations.Nullable;

/** When a preset's effect runs. {@link #key} = pack/JSON spelling. */
public enum ImpactTrigger {
    /** Entity struck, strike outcome {@code PROCEED}; may amend the {@code Threat} first. */
    HIT("hit"),
    /** Stopped on a block (loaded, or a deferred impact once its chunk loads). */
    BLOCK("block"),
    /** {@code blockPen} met a loaded block: entry. */
    PIERCE_IN("pierceIn"),
    /** {@code blockPen} exited a loaded block. */
    PIERCE_OUT("pierceOut"),
    /** Round ended at a loaded point, impact not owed to a {@code DeferredImpact}; rocket detonated. */
    END("end");

    public static final ImpactTrigger[] VALUES = values();

    public final String key;
    public final int bit;

    ImpactTrigger(String key) {
        this.key = key;
        this.bit = 1 << ordinal();
    }

    @Nullable
    public static ImpactTrigger byKey(String key) {
        for (ImpactTrigger t : VALUES) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }
}
