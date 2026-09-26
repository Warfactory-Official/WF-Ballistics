package com.wf.wflib.api;

/**
 * @param caliberMm     body diameter
 * @param penetrationMm RHA the round defeats at normal incidence; 0 = no armour-model figure
 */
public record Threat(ThreatKind kind, float caliberMm, float penetrationMm) {

    public static final float SMALL_ARMS_LIMIT_MM = 20.0f;

    public Threat {
        if (kind == ThreatKind.KINETIC && caliberMm < SMALL_ARMS_LIMIT_MM) {
            kind = ThreatKind.SMALL_ARMS;
        }
    }
}
