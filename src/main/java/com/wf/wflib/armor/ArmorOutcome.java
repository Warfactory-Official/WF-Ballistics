package com.wf.wflib.armor;

/**
 * The qualitative half of a resolution. A single damage number cannot tell "the plate stopped it"
 * from "nothing was there", and those are different injuries, so this travels alongside it.
 *
 * <p>Maps onto WFMedical's existing {@code ArmorEvaluation.Outcome} so its trauma generation does
 * not move; the difference is that these are deterministic on the arithmetic rather than rolled.
 */
public enum ArmorOutcome {
    /** Nothing reached the body. Backface deformation only: a bruise, maybe a cracked rib. */
    STOPPED,
    /** Got through, but the armour took a meaningful share of it. */
    REDUCED,
    /** The armour barely mattered. */
    THROUGH
}
