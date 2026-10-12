package com.wf.wflib.armor;

/** Maps onto WFMedical's {@code ArmorEvaluation.Outcome} (same trauma generation); deterministic from arithmetic here, not rolled. */
public enum ArmorOutcome {
    /** Nothing got through; backface deformation can still bruise/crack a rib. */
    STOPPED,
    REDUCED,
    THROUGH
}
