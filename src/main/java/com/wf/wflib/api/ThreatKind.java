package com.wf.wflib.api;

/** What defeats a round: the mechanism add-on armour answers, not its damage. */
public enum ThreatKind {
    /** Rifle and machine-gun ammunition, below 20 mm. */
    SMALL_ARMS,
    /** Penetrator or AP shot: armour is beaten by mass and velocity. */
    KINETIC,
    /** Single shaped charge: a jet formed at the nose. */
    HEAT,
    /** Precursor charge + main charge. */
    TANDEM_HEAT,
    /** Blast and fragments; no penetrator. */
    HE;

    public boolean isShapedCharge() {
        return this == HEAT || this == TANDEM_HEAT;
    }
}
